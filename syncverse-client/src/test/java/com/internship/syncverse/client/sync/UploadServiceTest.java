package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.client.state.PendingOperation;
import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.RegisterResponse;
import com.internship.syncverse.common.protocol.ChangeOutcome;
import com.internship.syncverse.common.protocol.FileOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsExactOperationBeforeSubmissionAndAckDoesNotAdvanceCursor() throws Exception {
        AtomicClientStateStore store = store();
        ClientState initial = new ClientState(1, "Alice_Node", 42, java.util.Map.of(), null);
        store.save(initial);
        PendingOperation operation = operation();
        InspectingApi api = new InspectingApi(store, operation);
        UploadService uploads = new UploadService(api, store);

        ClientState acknowledged = uploads.submit(UUID.randomUUID(), initial, operation);

        assertEquals(42, acknowledged.lastSeenGlobalVersion());
        assertNull(acknowledged.pendingOperation());
        assertEquals(43, acknowledged.manifest().get("note.txt").fileVersion());
        assertEquals(acknowledged, store.load().orElseThrow());
        assertEquals(operation.operationId(), api.received.operationId());
        assertEquals(operation.contentBase64(), api.received.contentBase64());
    }

    @Test
    void failedSubmissionLeavesExactPendingOperationOnDisk() throws Exception {
        AtomicClientStateStore store = store();
        ClientState initial = ClientState.empty("Alice_Node");
        PendingOperation operation = operation();
        ServerApiClient failingApi = new StubApi() {
            @Override
            public FileChangeResponse fileChange(FileChangeRequest request)
                    throws ServerApiException {
                throw ServerApiException.retryable("offline");
            }
        };

        assertThrows(ServerApiException.class,
                () -> new UploadService(failingApi, store)
                        .submit(UUID.randomUUID(), initial, operation));

        assertEquals(operation, store.load().orElseThrow().pendingOperation());
    }

    @Test
    void conflictAckDoesNotClaimConflictFileAlreadyExistsLocally() throws Exception {
        AtomicClientStateStore store = store();
        ClientState initial = new ClientState(
                1, "Bob_Node", 5,
                java.util.Map.of("note.txt", new FileManifestEntry("c".repeat(64), 5, false)),
                null);
        PendingOperation operation = new PendingOperation(
                UUID.randomUUID(), "note.txt", FileOperation.UPDATE, 5,
                "a".repeat(64), "Ym9iLWJ5dGVz");
        ServerApiClient api = new StubApi() {
            @Override
            public FileChangeResponse fileChange(FileChangeRequest request) {
                return new FileChangeResponse(
                        ChangeOutcome.CONFLICT_COPY_CREATED,
                        "note.txt", "note.conflict-Bob_Node-a1b2c3d4.txt", 8L, 8);
            }
        };

        ClientState result = new UploadService(api, store)
                .submit(UUID.randomUUID(), initial, operation);

        assertEquals(initial.manifest(), result.manifest());
        assertNull(result.pendingOperation());
    }

    @Test
    void staleDeleteAckPreventsAutomaticRetryUntilRemoteDeltaRestoresFile() throws Exception {
        AtomicClientStateStore store = store();
        ClientState initial = new ClientState(
                1, "Bob_Node", 5,
                java.util.Map.of("note.txt", new FileManifestEntry("c".repeat(64), 5, false)),
                null);
        PendingOperation operation = new PendingOperation(
                UUID.randomUUID(), "note.txt", FileOperation.DELETE, 5, null, null);
        ServerApiClient api = new StubApi() {
            @Override
            public FileChangeResponse fileChange(FileChangeRequest request) {
                return new FileChangeResponse(
                        ChangeOutcome.CONFLICT_REJECTED, "note.txt", "note.txt", null, 8);
            }
        };

        ClientState result = new UploadService(api, store)
                .submit(UUID.randomUUID(), initial, operation);

        assertNull(result.pendingOperation());
        assertEquals(8, result.manifest().get("note.txt").fileVersion());
        assertEquals(true, result.manifest().get("note.txt").deleted());
    }

    @Test
    void permanentFailureClearsUnretryablePendingOperation() throws Exception {
        AtomicClientStateStore store = store();
        ClientState initial = ClientState.empty("Alice_Node");
        ServerApiClient api = new StubApi() {
            @Override
            public FileChangeResponse fileChange(FileChangeRequest request)
                    throws ServerApiException {
                throw ServerApiException.permanent("invalid");
            }
        };

        assertThrows(ServerApiException.class, () -> new UploadService(api, store)
                .submit(UUID.randomUUID(), initial, operation()));

        assertNull(store.load().orElseThrow().pendingOperation());
    }

    private AtomicClientStateStore store() throws Exception {
        return new AtomicClientStateStore(
                Files.createDirectory(temporaryDirectory.resolve(UUID.randomUUID().toString())));
    }

    private static PendingOperation operation() {
        return new PendingOperation(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "note.txt", FileOperation.CREATE, 0,
                "a".repeat(64), "ZXhhY3QtYnl0ZXM=");
    }

    private abstract static class StubApi implements ServerApiClient {
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
        public DeltaResponse deltas(UUID sessionId, long since) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InspectingApi extends StubApi {
        private final AtomicClientStateStore store;
        private final PendingOperation expectedPending;
        private FileChangeRequest received;

        private InspectingApi(
                AtomicClientStateStore store, PendingOperation expectedPending) {
            this.store = store;
            this.expectedPending = expectedPending;
        }

        @Override
        public FileChangeResponse fileChange(FileChangeRequest request) throws ServerApiException {
            try {
                assertEquals(expectedPending, store.load().orElseThrow().pendingOperation());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            received = request;
            return new FileChangeResponse(
                    ChangeOutcome.APPLIED, "note.txt", "note.txt", 43L, 43);
        }
    }
}
