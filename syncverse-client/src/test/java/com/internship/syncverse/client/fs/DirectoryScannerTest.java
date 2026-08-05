package com.internship.syncverse.client.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DirectoryScannerTest {

    @TempDir
    Path workspace;

    @Test
    void scansOnlyRootRegularFilesWithinDecodedLimit() throws Exception {
        Files.writeString(workspace.resolve("valid.txt"), "hello");
        Files.writeString(workspace.resolve(".syncverse-123.tmp"), "in-flight-remote-bytes");
        Path child = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(child.resolve("nested.txt"), "ignored");
        Files.write(workspace.resolve("large.bin"), new byte[1_048_577]);
        try {
            Files.createSymbolicLink(workspace.resolve("link.txt"), workspace.resolve("valid.txt"));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            // Symlinks are not available on every Windows test environment.
        }

        Map<String, FileSnapshot> snapshots = new DirectoryScanner(workspace).scan();

        assertEquals(java.util.Set.of("valid.txt", ".syncverse-123.tmp"), snapshots.keySet());
        assertEquals(5, snapshots.get("valid.txt").sizeBytes());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                snapshots.get("valid.txt").checksum());
        assertFalse(snapshots.containsKey("nested.txt"));
    }
}
