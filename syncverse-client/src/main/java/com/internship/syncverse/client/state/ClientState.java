package com.internship.syncverse.client.state;

import java.util.Map;

public record ClientState(
        int formatVersion,
        String clientName,
        long lastSeenGlobalVersion,
        Map<String, FileManifestEntry> manifest,
        PendingOperation pendingOperation) {

    public ClientState {
        manifest = Map.copyOf(manifest);
    }

    public static ClientState empty(String clientName) {
        return new ClientState(1, clientName, 0, Map.of(), null);
    }
}
