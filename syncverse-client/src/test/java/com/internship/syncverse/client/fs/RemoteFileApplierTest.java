package com.internship.syncverse.client.fs;

import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.common.protocol.FileOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteFileApplierTest {

    @TempDir
    Path temporaryDirectory;

    private Path workspace;
    private AtomicClientStateStore store;
    private RemoteFileApplier applier;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporaryDirectory.resolve("bob"));
        store = new AtomicClientStateStore(workspace);
        applier = new RemoteFileApplier(workspace, store);
    }

    @Test
    void createAndUpdateUseValidatedBytesAndPersistManifest() throws Exception {
        ClientState state = ClientState.empty("Bob_Node");

        state = applier.apply(state, revision(1, "note.txt", FileOperation.CREATE, "one"));
        state = applier.apply(state, revision(2, "note.txt", FileOperation.UPDATE, "two"));

        assertEquals("two", Files.readString(workspace.resolve("note.txt")));
        assertEquals(2, state.manifest().get("note.txt").fileVersion());
        assertEquals(state, store.load().orElseThrow());
        assertEquals(java.util.Set.of("note.txt"), new DirectoryScanner(workspace).scan().keySet());
    }

    @Test
    void checksumMismatchIsRejectedBeforeCanonicalMutation() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "keep");
        FileRevision corrupt = new FileRevision(
                3, "note.txt", FileOperation.UPDATE, 3,
                "0".repeat(64), 3, Base64.getEncoder().encodeToString(bytes("bad")));

        assertThrows(InvalidRemoteRevisionException.class,
                () -> applier.apply(ClientState.empty("Bob_Node"), corrupt));

        assertEquals("keep", Files.readString(workspace.resolve("note.txt")));
        assertFalse(Files.exists(store.path()));
    }

    @Test
    void deleteRemovesFileAndPersistsTombstone() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "old");

        ClientState state = applier.apply(
                ClientState.empty("Bob_Node"),
                new FileRevision(4, "note.txt", FileOperation.DELETE,
                        4, null, 0, null));

        assertFalse(Files.exists(workspace.resolve("note.txt")));
        assertEquals(true, state.manifest().get("note.txt").deleted());
        assertEquals(4, state.manifest().get("note.txt").fileVersion());
    }

    private static FileRevision revision(
            long version, String filename, FileOperation operation, String content) {
        byte[] bytes = bytes(content);
        return new FileRevision(version, filename, operation, version,
                checksum(bytes), bytes.length, Base64.getEncoder().encodeToString(bytes));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
