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

    @Test
    void createUpdateDeleteConvergeWithoutBobFeedbackUploads() throws Exception {
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
            assertEquals(3, receipts.count());
            assertTrue(bob.state().manifest().get("note.txt").deleted());
        } finally {
            alice.close();
            bob.close();
        }
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
