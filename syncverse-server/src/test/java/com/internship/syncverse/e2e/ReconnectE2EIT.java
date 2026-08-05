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
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import com.internship.syncverse.server.persistence.OperationReceiptRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = SyncVerseServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "syncverse.server-name=ReconnectServer",
                "syncverse.session-expiry=1m",
                "syncverse.long-poll-timeout=100ms",
                "spring.datasource.url=jdbc:h2:mem:reconnect-e2e;DB_CLOSE_DELAY=-1"
        })
class ReconnectE2EIT {

    @TempDir
    Path temporaryDirectory;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ChangeLogRepository changes;

    @Autowired
    private OperationReceiptRepository receipts;

    @Test
    void offlineClientCatchesUpThreeOrderedRevisions() throws Exception {
        long versionBefore = changes.maxVersion();
        Path aliceWorkspace = Files.createDirectory(temporaryDirectory.resolve("catchup-alice"));
        Path bobWorkspace = Files.createDirectory(temporaryDirectory.resolve("catchup-bob"));
        CountingApi aliceApi = new CountingApi(httpApi());
        CountingApi bobApi = new CountingApi(httpApi());
        RegisterResponse aliceSession = aliceApi.register("Catchup_Alice");
        RegisterResponse bobSession = bobApi.register("Catchup_Bob");
        AtomicClientStateStore bobStore = new AtomicClientStateStore(bobWorkspace);
        SyncCoordinator alice = coordinator(
                aliceWorkspace, aliceApi, aliceSession, "Catchup_Alice");
        SyncCoordinator bob = new SyncCoordinator(
                bobWorkspace, bobApi, bobSession::sessionId, bobStore,
                ClientState.empty("Catchup_Bob"));
        SyncCoordinator restartedBob = null;
        try {
            alice.start();
            bob.start();
            bob.close();

            Files.writeString(aliceWorkspace.resolve("missed-one.txt"), "one");
            eventually(Duration.ofSeconds(5), () -> aliceApi.fileChanges.get() == 1);
            Files.writeString(aliceWorkspace.resolve("missed-two.txt"), "two");
            eventually(Duration.ofSeconds(5), () -> aliceApi.fileChanges.get() == 2);
            Files.writeString(aliceWorkspace.resolve("missed-three.txt"), "three");
            eventually(Duration.ofSeconds(5), () -> aliceApi.fileChanges.get() == 3);

            ClientState offlineState = bobStore.load().orElseThrow();
            RegisterResponse session = bobApi.reconnect(
                    "Catchup_Bob", offlineState.lastSeenGlobalVersion());
            restartedBob = new SyncCoordinator(
                    bobWorkspace, bobApi, session::sessionId, bobStore, offlineState);
            restartedBob.start(session.currentGlobalVersion(), ignored -> { });
            SyncCoordinator activeBob = restartedBob;

            eventually(Duration.ofSeconds(5), () ->
                    Files.exists(bobWorkspace.resolve("missed-one.txt"))
                            && Files.exists(bobWorkspace.resolve("missed-two.txt"))
                            && Files.exists(bobWorkspace.resolve("missed-three.txt"))
                            && Files.readString(bobWorkspace.resolve("missed-one.txt")).equals("one")
                            && Files.readString(bobWorkspace.resolve("missed-two.txt")).equals("two")
                            && Files.readString(bobWorkspace.resolve("missed-three.txt")).equals("three")
                            && activeBob.state().lastSeenGlobalVersion() >= versionBefore + 3);

            assertEquals(0, bobApi.fileChanges.get());
            var records = changes.findAfter(versionBefore, 20);
            assertEquals(3, records.size());
            assertTrue(records.get(0).globalVersion() < records.get(1).globalVersion());
            assertTrue(records.get(1).globalVersion() < records.get(2).globalVersion());
        } finally {
            alice.close();
            if (restartedBob != null) {
                restartedBob.close();
            }
        }
    }

    @Test
    void droppedUploadResponseSurvivesClientRestartWithSameOperation() throws Exception {
        long changesBefore = changes.count();
        long receiptsBefore = receipts.count();
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("dropped-response"));
        Files.writeString(workspace.resolve("drop-once.txt"), "durable");
        DropFirstResponseApi api = new DropFirstResponseApi(httpApi());
        RegisterResponse session = api.register("Dropped_Alice");
        AtomicClientStateStore store = new AtomicClientStateStore(workspace);
        SyncCoordinator firstProcess = new SyncCoordinator(
                workspace, api, session::sessionId, store,
                ClientState.empty("Dropped_Alice"));
        SyncCoordinator restartedProcess = null;
        try {
            firstProcess.start();
            eventually(Duration.ofSeconds(5), () ->
                    api.attempts.get() == 1
                            && store.load().orElseThrow().pendingOperation() != null);
            UUID operationId = store.load().orElseThrow()
                    .pendingOperation().operationId();
            firstProcess.close();

            ClientState persisted = store.load().orElseThrow();
            CountingApi recoveredApi = new CountingApi(httpApi());
            RegisterResponse recoveredSession = recoveredApi.reconnect(
                    "Dropped_Alice", persisted.lastSeenGlobalVersion());
            restartedProcess = new SyncCoordinator(
                    workspace, recoveredApi, recoveredSession::sessionId, store, persisted);
            restartedProcess.start(
                    recoveredSession.currentGlobalVersion(), ignored -> { });
            SyncCoordinator activeProcess = restartedProcess;
            eventually(Duration.ofSeconds(5), () ->
                    activeProcess.state().pendingOperation() == null
                            && activeProcess.state().lastSeenGlobalVersion()
                            >= recoveredSession.currentGlobalVersion());

            assertEquals(1, api.attempts.get());
            assertEquals(1, recoveredApi.fileChanges.get());
            assertTrue(receipts.find(operationId).isPresent());
            assertEquals(changesBefore + 1, changes.count());
            assertEquals(receiptsBefore + 1, receipts.count());
        } finally {
            firstProcess.close();
            if (restartedProcess != null) {
                restartedProcess.close();
            }
        }
    }

    @Test
    void newClientWithIdenticalServerBytesRecordsVersionWithoutConflictUpload()
            throws Exception {
        long changesBefore = changes.count();
        Path aliceWorkspace = Files.createDirectory(temporaryDirectory.resolve("same-alice"));
        Path bobWorkspace = Files.createDirectory(temporaryDirectory.resolve("same-bob"));
        Files.writeString(aliceWorkspace.resolve("already-same.txt"), "same-bytes");
        Files.writeString(bobWorkspace.resolve("already-same.txt"), "same-bytes");
        CountingApi aliceApi = new CountingApi(httpApi());
        CountingApi bobApi = new CountingApi(httpApi());
        RegisterResponse aliceSession = aliceApi.register("Same_Alice");
        SyncCoordinator alice = coordinator(
                aliceWorkspace, aliceApi, aliceSession, "Same_Alice");
        SyncCoordinator bob = null;
        try {
            alice.start();
            eventually(Duration.ofSeconds(5), () -> changes.count() == changesBefore + 1);

            RegisterResponse bobSession = bobApi.register("Same_Bob");
            AtomicClientStateStore bobStore = new AtomicClientStateStore(bobWorkspace);
            bob = new SyncCoordinator(
                    bobWorkspace, bobApi, bobSession::sessionId, bobStore,
                    ClientState.empty("Same_Bob"));
            bob.start(bobSession.currentGlobalVersion(), ignored -> { });
            SyncCoordinator activeBob = bob;

            eventually(Duration.ofSeconds(5), () ->
                    activeBob.state().lastSeenGlobalVersion()
                            >= bobSession.currentGlobalVersion());

            assertEquals(0, bobApi.fileChanges.get());
            assertEquals(changesBefore + 1, changes.count());
        } finally {
            alice.close();
            if (bob != null) {
                bob.close();
            }
        }
    }

    private SyncCoordinator coordinator(
            Path workspace, ServerApiClient api, RegisterResponse registration, String clientName) {
        AtomicClientStateStore store = new AtomicClientStateStore(workspace);
        return new SyncCoordinator(
                workspace, api, registration::sessionId, store, ClientState.empty(clientName));
    }

    private ServerApiClient httpApi() {
        return ServerApiClient.http(URI.create("http://localhost:" + port));
    }

    private static void eventually(Duration timeout, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(condition.evaluate(), "Condition did not converge before " + timeout);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    private static class CountingApi implements ServerApiClient {
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
        public RegisterResponse reconnect(String clientName, long cursor)
                throws ServerApiException {
            return delegate.reconnect(clientName, cursor);
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

    private static final class DropFirstResponseApi extends CountingApi {
        private final AtomicBoolean drop = new AtomicBoolean(true);
        private final AtomicInteger attempts = new AtomicInteger();

        private DropFirstResponseApi(ServerApiClient delegate) {
            super(delegate);
        }

        @Override
        public FileChangeResponse fileChange(FileChangeRequest request)
                throws ServerApiException {
            attempts.incrementAndGet();
            FileChangeResponse response = super.fileChange(request);
            if (drop.compareAndSet(true, false)) {
                throw ServerApiException.retryable("simulated response loss");
            }
            return response;
        }
    }
}
