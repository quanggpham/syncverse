package com.internship.syncverse.client.state;

import com.internship.syncverse.common.protocol.FileOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicClientStateStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stateLivesBesideWorkspaceAndRoundTripsExactly() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("alice"));
        AtomicClientStateStore store = new AtomicClientStateStore(workspace);
        PendingOperation pending = new PendingOperation(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                "note.txt", FileOperation.UPDATE, 7,
                "a".repeat(64), "ZXhhY3QtYnl0ZXM=");
        ClientState expected = new ClientState(
                1, "Alice_Node", 42,
                Map.of("note.txt", new FileManifestEntry("b".repeat(64), 7, false)),
                pending);

        store.save(expected);

        assertEquals(temporaryDirectory.resolve("alice.syncverse-state.json"), store.path());
        assertEquals(expected, store.load().orElseThrow());
    }

    @Test
    void failedReplacementLeavesPreviousStateParseable() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("alice"));
        AtomicClientStateStore initialStore = new AtomicClientStateStore(workspace);
        ClientState previous = ClientState.empty("Alice_Node");
        initialStore.save(previous);
        AtomicClientStateStore failingStore = new AtomicClientStateStore(
                workspace, (source, target) -> {
                    throw new IOException("injected move failure");
                });

        assertThrows(IOException.class, () -> failingStore.save(
                new ClientState(1, "Alice_Node", 9, Map.of(), null)));

        assertEquals(previous, initialStore.load().orElseThrow());
    }
}
