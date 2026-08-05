package com.internship.syncverse.e2e;

import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.sync.SyncCoordinator;
import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.dto.RegisterResponse;
import com.internship.syncverse.server.SyncVerseServer;
import com.internship.syncverse.server.persistence.OperationReceiptRepository;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = SyncVerseServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "syncverse.server-name=LiveSyncServer",
                "syncverse.session-expiry=1m",
                "syncverse.long-poll-timeout=100ms",
                "spring.datasource.url=jdbc:h2:mem:live-sync;DB_CLOSE_DELAY=-1"
        })
class LiveSyncIT {

    @TempDir
    Path temporaryDirectory;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OperationReceiptRepository receipts;

    @Autowired
    private ChangeLogRepository changes;

    @Test
    void createUpdateDeleteConvergeWithoutBobFeedbackUploads() throws Exception {
        long receiptsBefore = receipts.count();
        Path aliceWorkspace = Files.createDirectory(temporaryDirectory.resolve("alice"));
        Path bobWorkspace = Files.createDirectory(temporaryDirectory.resolve("bob"));
        Files.writeString(aliceWorkspace.resolve("note.txt"), "one");
        CountingApi aliceApi = api();
        CountingApi bobApi = api();
        RegisterResponse aliceSession = aliceApi.register("Alice_Node");
        RegisterResponse bobSession = bobApi.register("Bob_Node");
        SyncCoordinator alice = coordinator(
                aliceWorkspace, aliceApi, aliceSession, "Alice_Node");
        SyncCoordinator bob = coordinator(
                bobWorkspace, bobApi, bobSession, "Bob_Node");
        try {
            alice.start();
            bob.start();
            eventually(Duration.ofSeconds(5),
                    () -> Files.exists(bobWorkspace.resolve("note.txt"))
                            && Files.readString(bobWorkspace.resolve("note.txt")).equals("one"));

            Files.writeString(aliceWorkspace.resolve("note.txt"), "two");
            eventually(Duration.ofSeconds(5),
                    () -> Files.readString(bobWorkspace.resolve("note.txt")).equals("two"));

            Files.delete(aliceWorkspace.resolve("note.txt"));
            eventually(Duration.ofSeconds(5),
                    () -> Files.notExists(bobWorkspace.resolve("note.txt")));

            assertEquals(3, aliceApi.fileChanges.get());
            assertEquals(0, bobApi.fileChanges.get());
            assertEquals(receiptsBefore + 3, receipts.count());
            assertTrue(bob.state().manifest().get("note.txt").deleted());
        } finally {
            alice.close();
            bob.close();
        }
    }

    @Test
    void staleOfflineUpdatePreservesCanonicalAndConflictOnOriginatingClient() throws Exception {
        String filename = "conflict-shared.txt";
        Path aliceWorkspace = Files.createDirectory(temporaryDirectory.resolve("conflict-alice"));
        Path bobWorkspace = Files.createDirectory(temporaryDirectory.resolve("conflict-bob"));
        Files.writeString(aliceWorkspace.resolve(filename), "base");
        CountingApi aliceApi = api();
        CountingApi bobApi = api();
        RegisterResponse aliceSession = aliceApi.register("Conflict_Alice");
        RegisterResponse bobSession = bobApi.register("Conflict_Bob");
        AtomicClientStateStore bobStore = new AtomicClientStateStore(bobWorkspace);
        SyncCoordinator alice = coordinator(
                aliceWorkspace, aliceApi, aliceSession, "Conflict_Alice");
        SyncCoordinator bob = new SyncCoordinator(
                bobWorkspace, bobApi, bobSession::sessionId, bobStore,
                ClientState.empty("Conflict_Bob"));
        SyncCoordinator restartedBob = null;
        try {
            alice.start();
            bob.start();
            eventually(Duration.ofSeconds(5),
                    () -> Files.exists(bobWorkspace.resolve(filename)));
            bob.close();

            Files.writeString(aliceWorkspace.resolve(filename), "alice-new");
            eventually(Duration.ofSeconds(5), () -> aliceApi.fileChanges.get() == 2);
            Files.writeString(bobWorkspace.resolve(filename), "bob-new");
            ClientState offlineState = bobStore.load().orElseThrow();
            RegisterResponse reconnected = bobApi.reconnect(
                    "Conflict_Bob", offlineState.lastSeenGlobalVersion());
            restartedBob = new SyncCoordinator(
                    bobWorkspace, bobApi, reconnected::sessionId, bobStore, offlineState);
            restartedBob.start(reconnected.currentGlobalVersion(), ignored -> { });

            SyncCoordinator activeBob = restartedBob;
            try {
                eventually(Duration.ofSeconds(5), () ->
                        Files.readString(bobWorkspace.resolve(filename)).equals("alice-new")
                                && java.util.Objects.equals(
                                conflictContent(bobWorkspace), "bob-new")
                                && java.util.Objects.equals(
                                conflictContent(aliceWorkspace), "bob-new")
                                && activeBob.state().lastSeenGlobalVersion() >= 3);
            } catch (AssertionError failure) {
                throw new AssertionError(
                        "alice=" + workspaceContents(aliceWorkspace)
                                + ", bob=" + workspaceContents(bobWorkspace)
                                + ", aliceOps=" + aliceApi.fileChanges
                                + ", bobOps=" + bobApi.fileChanges
                                + ", bobCursor=" + activeBob.state().lastSeenGlobalVersion()
                                + ", changes=" + changes.findAfter(0, 20),
                        failure);
            }

            assertEquals(1, bobApi.fileChanges.get());
        } finally {
            alice.close();
            if (restartedBob != null) {
                restartedBob.close();
            }
        }
    }

    @Test
    void staleOfflineDeleteIsSuppressedAndRemoteUpdateIsRestored() throws Exception {
        String filename = "delete-shared.txt";
        Path aliceWorkspace = Files.createDirectory(temporaryDirectory.resolve("delete-alice"));
        Path bobWorkspace = Files.createDirectory(temporaryDirectory.resolve("delete-bob"));
        Files.writeString(aliceWorkspace.resolve(filename), "base");
        CountingApi aliceApi = api();
        CountingApi bobApi = api();
        RegisterResponse aliceSession = aliceApi.register("Delete_Alice");
        RegisterResponse bobSession = bobApi.register("Delete_Bob");
        AtomicClientStateStore bobStore = new AtomicClientStateStore(bobWorkspace);
        SyncCoordinator alice = coordinator(
                aliceWorkspace, aliceApi, aliceSession, "Delete_Alice");
        SyncCoordinator bob = new SyncCoordinator(
                bobWorkspace, bobApi, bobSession::sessionId, bobStore,
                ClientState.empty("Delete_Bob"));
        SyncCoordinator restartedBob = null;
        try {
            alice.start();
            bob.start();
            eventually(Duration.ofSeconds(5),
                    () -> Files.exists(bobWorkspace.resolve(filename)));
            bob.close();

            Files.writeString(aliceWorkspace.resolve(filename), "server-new");
            eventually(Duration.ofSeconds(5), () -> aliceApi.fileChanges.get() == 2);
            Files.delete(bobWorkspace.resolve(filename));
            ClientState offlineState = bobStore.load().orElseThrow();
            RegisterResponse reconnected = bobApi.reconnect(
                    "Delete_Bob", offlineState.lastSeenGlobalVersion());
            restartedBob = new SyncCoordinator(
                    bobWorkspace, bobApi, reconnected::sessionId, bobStore, offlineState);
            restartedBob.start(reconnected.currentGlobalVersion(), ignored -> { });

            SyncCoordinator activeBob = restartedBob;
            eventually(Duration.ofSeconds(5), () ->
                    Files.exists(bobWorkspace.resolve(filename))
                            && Files.readString(bobWorkspace.resolve(filename))
                            .equals("server-new")
                            && activeBob.state().pendingOperation() == null);

            assertEquals(0, bobApi.fileChanges.get());
        } finally {
            alice.close();
            if (restartedBob != null) {
                restartedBob.close();
            }
        }
    }

    private static String conflictContent(Path workspace) throws Exception {
        try (var files = Files.list(workspace)) {
            var conflict = files.filter(path -> path.getFileName().toString()
                            .contains(".conflict-"))
                    .findFirst();
            return conflict.isPresent() ? Files.readString(conflict.orElseThrow()) : null;
        }
    }

    private static java.util.Map<String, String> workspaceContents(Path workspace)
            throws Exception {
        java.util.Map<String, String> contents = new java.util.TreeMap<>();
        try (var files = Files.list(workspace)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                contents.put(path.getFileName().toString(), Files.readString(path));
            }
        }
        return contents;
    }

    private SyncCoordinator coordinator(
            Path workspace, CountingApi api, RegisterResponse registration, String clientName) {
        AtomicClientStateStore store = new AtomicClientStateStore(workspace);
        return new SyncCoordinator(
                workspace, api, registration::sessionId, store, ClientState.empty(clientName));
    }

    private CountingApi api() {
        return new CountingApi(ServerApiClient.http(
                URI.create("http://localhost:" + port)));
    }

    private static void eventually(Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(true, condition.evaluate(), "Condition did not converge before " + timeout);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    private static final class CountingApi implements ServerApiClient {
        private final ServerApiClient delegate;
        private final AtomicInteger fileChanges = new AtomicInteger();

        private CountingApi(ServerApiClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public RegisterResponse register(String clientName) throws ServerApiException {
            return delegate.register(clientName);
        }

        @Override
        public RegisterResponse reconnect(String clientName, long lastSeenGlobalVersion)
                throws ServerApiException {
            return delegate.reconnect(clientName, lastSeenGlobalVersion);
        }

        @Override
        public void heartbeat(UUID sessionId) throws ServerApiException {
            delegate.heartbeat(sessionId);
        }

        @Override
        public FileChangeResponse fileChange(FileChangeRequest request)
                throws ServerApiException {
            fileChanges.incrementAndGet();
            return delegate.fileChange(request);
        }

        @Override
        public DeltaResponse deltas(UUID sessionId, long since) throws ServerApiException {
            return delegate.deltas(sessionId, since);
        }
    }
}
