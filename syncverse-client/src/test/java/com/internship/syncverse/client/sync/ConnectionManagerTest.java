package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.common.dto.RegisterResponse;
import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.dto.DeltaResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionManagerTest {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void registerSuccessMovesClientToReconcilingThenOnline() throws Exception {
        FakeServerApi api = new FakeServerApi();
        ConnectionManager manager = manager(api);

        manager.start();

        assertEquals(ClientMode.RECONCILING, manager.mode());
        assertEquals(api.registeredSession, manager.sessionId());
        manager.reconciliationComplete(0);
        assertEquals(ClientMode.ONLINE, manager.mode());
        manager.shutdown();
    }

    @Test
    void registerNetworkFailureStartsOfflineInsteadOfStoppingClient() throws Exception {
        FakeServerApi api = new FakeServerApi();
        api.failRegister = true;
        ConnectionManager manager = manager(api);

        manager.start();

        assertEquals(ClientMode.OFFLINE, manager.mode());
        manager.shutdown();
    }

    @Test
    void heartbeatFailureMovesClientOffline() throws Exception {
        FakeServerApi api = new FakeServerApi();
        ConnectionManager manager = manager(api);
        manager.start();
        api.failHeartbeat = true;

        manager.tick();

        assertEquals(ClientMode.OFFLINE, manager.mode());
        manager.shutdown();
    }

    @Test
    void reconnectSuccessMovesClientToReconcilingWithNewSession() throws Exception {
        FakeServerApi api = new FakeServerApi();
        ConnectionManager manager = manager(api);
        manager.start();
        UUID original = manager.sessionId();
        api.failHeartbeat = true;
        manager.tick();
        api.failHeartbeat = false;

        manager.tick();

        assertEquals(ClientMode.RECONCILING, manager.mode());
        assertNotEquals(original, manager.sessionId());
        manager.shutdown();
    }

    @Test
    void shutdownCancelsHeartbeatScheduler() throws Exception {
        ConnectionManager manager = manager(new FakeServerApi());
        manager.start();

        manager.shutdown();

        assertTrue(manager.isStopped());
        assertTrue(scheduler.isShutdown());
    }

    @Test
    void initiallyOfflineClientStartsSessionWorkAfterRecovery() {
        FakeServerApi api = new FakeServerApi();
        api.failRegister = true;
        AtomicInteger sessionStarts = new AtomicInteger();
        ConnectionManager manager = new ConnectionManager(
                api, "Alice_Node", scheduler, Duration.ofDays(1),
                sessionStarts::incrementAndGet);

        manager.start();
        assertEquals(0, sessionStarts.get());
        api.failRegister = false;
        manager.tick();

        assertEquals(ClientMode.RECONCILING, manager.mode());
        assertEquals(1, sessionStarts.get());
        manager.shutdown();
    }

    @Test
    void returningClientReconnectsFromItsPersistedCursor() {
        FakeServerApi api = new FakeServerApi();
        AtomicLong cursor = new AtomicLong(37);
        ConnectionManager manager = new ConnectionManager(
                api, "Alice_Node", scheduler, Duration.ofDays(1),
                () -> { }, cursor::get, true);

        manager.start();

        assertEquals(0, api.registerCalls);
        assertEquals(1, api.reconnectCalls);
        assertEquals(37, api.reconnectCursor);
        assertEquals(ClientMode.RECONCILING, manager.mode());
        manager.shutdown();
    }

    @Test
    void persistedZeroCursorStillUsesReconnect() {
        FakeServerApi api = new FakeServerApi();
        ConnectionManager manager = new ConnectionManager(
                api, "Alice_Node", scheduler, Duration.ofDays(1),
                () -> { }, () -> 0, true);

        manager.start();

        assertEquals(0, api.registerCalls);
        assertEquals(1, api.reconnectCalls);
        assertEquals(ClientMode.RECONCILING, manager.mode());
        manager.shutdown();
    }

    @Test
    void reconcilingRemainsUntilCoordinatorCompletes() {
        FakeServerApi api = new FakeServerApi();
        ConnectionManager manager = new ConnectionManager(
                api, "Alice_Node", scheduler, Duration.ofDays(1),
                () -> { }, () -> 9, true);
        manager.start();

        manager.tick();

        assertEquals(ClientMode.RECONCILING, manager.mode());
        manager.reconciliationComplete(9);
        assertEquals(ClientMode.ONLINE, manager.mode());
        manager.shutdown();
    }

    @Test
    void staleSessionCannotCompleteReplacementSession() {
        FakeServerApi api = new FakeServerApi();
        ConnectionManager manager = new ConnectionManager(
                api, "Alice_Node", scheduler, Duration.ofDays(1),
                () -> { }, () -> 9, true);
        manager.start();
        UUID firstSession = manager.sessionId();
        api.failHeartbeat = true;
        manager.tick();
        api.failHeartbeat = false;
        manager.tick();
        UUID replacementSession = manager.sessionId();

        manager.reconciliationComplete(firstSession, 9);

        assertNotEquals(firstSession, replacementSession);
        assertEquals(ClientMode.RECONCILING, manager.mode());
        manager.reconciliationComplete(replacementSession, 9);
        assertEquals(ClientMode.ONLINE, manager.mode());
        manager.shutdown();
    }

    private ConnectionManager manager(ServerApiClient api) {
        return new ConnectionManager(
                api, "Alice_Node", scheduler, Duration.ofDays(1));
    }

    private static final class FakeServerApi implements ServerApiClient {
        private final UUID registeredSession = UUID.randomUUID();
        private boolean failRegister;
        private boolean failHeartbeat;
        private int registerCalls;
        private int reconnectCalls;
        private long reconnectCursor;

        @Override
        public RegisterResponse register(String clientName) throws ServerApiException {
            registerCalls++;
            if (failRegister) {
                throw ServerApiException.retryable("server unavailable");
            }
            return new RegisterResponse(clientName, registeredSession, 0);
        }

        @Override
        public RegisterResponse reconnect(String clientName, long lastSeenGlobalVersion) {
            reconnectCalls++;
            reconnectCursor = lastSeenGlobalVersion;
            return new RegisterResponse(clientName, UUID.randomUUID(), lastSeenGlobalVersion);
        }

        @Override
        public void heartbeat(UUID sessionId) throws ServerApiException {
            if (failHeartbeat) {
                throw ServerApiException.retryable("server unavailable");
            }
        }

        @Override
        public FileChangeResponse fileChange(FileChangeRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DeltaResponse deltas(UUID sessionId, long since) {
            throw new UnsupportedOperationException();
        }
    }
}
