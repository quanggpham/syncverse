package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.FileRevision;
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

    private AtomicClientStateStore store() throws Exception {
        return new AtomicClientStateStore(
                Files.createDirectory(temporaryDirectory.resolve("workspace")));
    }

    private static FileRevision revision(long version, String filename, String marker) {
        return new FileRevision(version, filename, FileOperation.UPDATE,
                version, marker.repeat(64).substring(0, 64), marker.length(), "eA==");
    }
}
