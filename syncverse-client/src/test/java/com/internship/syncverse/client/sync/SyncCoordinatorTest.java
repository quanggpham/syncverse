package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.dto.RegisterResponse;
import com.internship.syncverse.common.protocol.FileOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncCoordinatorTest {

    @TempDir
    Path temporaryDirectory;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void appliesOrderedRevisionsAndDoesNotAdvancePastFailure() throws Exception {
        AtomicClientStateStore store = store();
        ClientState initial = new ClientState(1, "Bob_Node", 42, Map.of(), null);
        List<Long> attempted = new ArrayList<>();
        RevisionApplier failingAt43 = (state, revision) -> {
            attempted.add(revision.globalVersion());
            throw new IllegalStateException("cannot apply " + revision.globalVersion());
        };
        SyncCoordinator coordinator = new SyncCoordinator(
                initial, store, failingAt43, executor);

        assertThrows(ExecutionException.class, () -> coordinator.accept(
                new DeltaResponse(42, 44, List.of(
                        revision(43, "one.txt", "one"),
                        revision(44, "two.txt", "two")))).get());

        assertEquals(List.of(43L), attempted);
        assertEquals(42, coordinator.state().lastSeenGlobalVersion());
    }

    @Test
    void uploadAckVersionDoesNotSkipInterveningDeltaCursor() throws Exception {
        AtomicClientStateStore store = store();
        ClientState acknowledged = new ClientState(
                1, "Bob_Node", 42,
                Map.of("mine.txt", new FileManifestEntry("b".repeat(64), 44, false)),
                null);
        List<Long> physicallyApplied = new ArrayList<>();
        RevisionApplier applier = (state, revision) -> {
            physicallyApplied.add(revision.globalVersion());
            HashMap<String, FileManifestEntry> manifest = new HashMap<>(state.manifest());
            manifest.put(revision.filename(), new FileManifestEntry(
                    revision.checksum(), revision.fileVersion(), false));
            return new ClientState(state.formatVersion(), state.clientName(),
                    state.lastSeenGlobalVersion(), manifest, state.pendingOperation());
        };
        SyncCoordinator coordinator = new SyncCoordinator(
                acknowledged, store, applier, executor);

        ClientState result = coordinator.accept(new DeltaResponse(42, 44, List.of(
                revision(43, "theirs.txt", "theirs"),
                new FileRevision(44, "mine.txt", FileOperation.UPDATE,
                        44, "b".repeat(64), 4, "bWluZQ==")))).get();

        assertEquals(List.of(43L), physicallyApplied);
        assertEquals(44, result.lastSeenGlobalVersion());
        assertEquals(result, store.load().orElseThrow());
    }

    @Test
    void retryableInitialUploadDoesNotEscapeStartAndRemainsPending() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("live-workspace"));
        Files.writeString(workspace.resolve("offline.txt"), "local-change");
        AtomicClientStateStore store = new AtomicClientStateStore(workspace);
        UUID session = UUID.randomUUID();
        ServerApiClient offlineUploadApi = new StubServerApi() {
            @Override
            public FileChangeResponse fileChange(FileChangeRequest request)
                    throws ServerApiException {
                throw ServerApiException.retryable("network lost after registration");
            }

            @Override
            public DeltaResponse deltas(UUID sessionId, long since)
                    throws ServerApiException {
                throw ServerApiException.retryable("network lost after registration");
            }
        };
        SyncCoordinator coordinator = new SyncCoordinator(
                workspace, offlineUploadApi, () -> session, store,
                ClientState.empty("Alice_Node"));
        try {
            coordinator.start();

            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos();
            while (store.load().map(ClientState::pendingOperation).orElse(null) == null
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            org.junit.jupiter.api.Assertions.assertNotNull(
                    store.load().orElseThrow().pendingOperation());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void permanentFailureIsNotRecreatedAcrossEmptyDeltaPolls() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("rejected-workspace"));
        Files.writeString(workspace.resolve("rejected.txt"), "unchanged");
        AtomicClientStateStore store = new AtomicClientStateStore(workspace);
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        Semaphore pollResponses = new Semaphore(0);
        ServerApiClient permanentlyRejectingApi = new StubServerApi() {
            @Override
            public FileChangeResponse fileChange(FileChangeRequest request)
                    throws ServerApiException {
                uploads.incrementAndGet();
                throw ServerApiException.permanent("validation rejected");
            }

            @Override
            public DeltaResponse deltas(UUID sessionId, long since)
                    throws ServerApiException {
                polls.incrementAndGet();
                try {
                    pollResponses.acquire();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw ServerApiException.retryable("stopped", exception);
                }
                return new DeltaResponse(since, since, List.of());
            }
        };
        SyncCoordinator coordinator = new SyncCoordinator(
                workspace, permanentlyRejectingApi, UUID::randomUUID, store,
                ClientState.empty("Alice_Node"));
        try {
            coordinator.start();
            awaitAtLeast(uploads, 1);
            for (int expectedPolls = 2; expectedPolls <= 4; expectedPolls++) {
                pollResponses.release();
                awaitAtLeast(polls, expectedPolls);
            }

            assertEquals(1, uploads.get());
        } finally {
            coordinator.close();
        }
    }

    private AtomicClientStateStore store() throws Exception {
        return new AtomicClientStateStore(
                Files.createDirectory(temporaryDirectory.resolve("workspace")));
    }

    private static FileRevision revision(long version, String filename, String marker) {
        return new FileRevision(version, filename, FileOperation.UPDATE,
                version, marker.repeat(64).substring(0, 64), marker.length(), "eA==");
    }

    private static void awaitAtLeast(AtomicInteger value, int expected) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos();
        while (value.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, value.get());
    }

    private abstract static class StubServerApi implements ServerApiClient {
        @Override
        public RegisterResponse register(String clientName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisterResponse reconnect(String clientName, long lastSeenGlobalVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void heartbeat(UUID sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DeltaResponse deltas(UUID sessionId, long since) throws ServerApiException {
            return new DeltaResponse(since, since, List.of());
        }
    }
}
