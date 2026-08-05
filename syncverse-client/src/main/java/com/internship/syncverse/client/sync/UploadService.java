package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.client.state.PendingOperation;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.protocol.ChangeOutcome;
import com.internship.syncverse.common.protocol.FileOperation;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

public final class UploadService {

    private final ServerApiClient serverApi;
    private final AtomicClientStateStore stateStore;

    public UploadService(ServerApiClient serverApi, AtomicClientStateStore stateStore) {
        this.serverApi = serverApi;
        this.stateStore = stateStore;
    }

    public ClientState submit(
            UUID sessionId, ClientState state, PendingOperation operation)
            throws IOException, ServerApiException {
        return submitWithResponse(sessionId, state, operation).state();
    }

    UploadResult submitWithResponse(
            UUID sessionId, ClientState state, PendingOperation operation)
            throws IOException, ServerApiException {
        if (state.pendingOperation() != null
                && !state.pendingOperation().equals(operation)) {
            throw new IllegalStateException("A different upload operation is already pending");
        }
        ClientState pendingState = new ClientState(
                state.formatVersion(),
                state.clientName(),
                state.lastSeenGlobalVersion(),
                state.manifest(),
                operation);
        stateStore.save(pendingState);

        FileChangeResponse response;
        try {
            response = serverApi.fileChange(operation.request(sessionId));
        } catch (ServerApiException exception) {
            if (exception.kind() == ServerApiException.Kind.PERMANENT) {
                stateStore.save(withoutPending(state));
            }
            throw exception;
        }
        HashMap<String, FileManifestEntry> manifest = new HashMap<>(state.manifest());
        if (response.outcome() == ChangeOutcome.APPLIED
                || response.outcome() == ChangeOutcome.DUPLICATE) {
            manifest.put(response.acceptedFilename(), new FileManifestEntry(
                    operation.operation() == FileOperation.DELETE ? null : operation.checksum(),
                    response.fileVersion(),
                    operation.operation() == FileOperation.DELETE));
        } else if (response.outcome() == ChangeOutcome.CONFLICT_REJECTED) {
            manifest.put(operation.filename(), new FileManifestEntry(
                    null, response.fileVersion(), true));
        }
        ClientState acknowledged = new ClientState(
                state.formatVersion(),
                state.clientName(),
                state.lastSeenGlobalVersion(),
                manifest,
                null);
        stateStore.save(acknowledged);
        return new UploadResult(acknowledged, response);
    }

    private static ClientState withoutPending(ClientState state) {
        return new ClientState(
                state.formatVersion(), state.clientName(), state.lastSeenGlobalVersion(),
                state.manifest(), null);
    }
}

record UploadResult(ClientState state, FileChangeResponse response) {
}
