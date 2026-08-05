package com.internship.syncverse.client.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgumentsTest {

    @TempDir
    Path tempDir;

    @Test
    void requiresExactlyTwoArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"Alice_Node"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"Alice_Node", "folder", "extra"}));
    }

    @Test
    void rejectsInvalidClientName() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"Alice Node", tempDir.toString()}));
    }

    @Test
    void normalizesWorkspaceToAbsolutePath() throws Exception {
        Path nested = tempDir.resolve("nested").resolve("..").resolve("workspace");

        CliArguments arguments = CliArguments.parse(
                new String[]{"Alice_Node", nested.toString()});

        assertEquals(nested.toAbsolutePath().normalize(), arguments.workspace());
    }

    @Test
    void createsMissingWorkspaceDirectory() throws Exception {
        Path missing = tempDir.resolve("new-workspace");

        CliArguments arguments = CliArguments.parse(
                new String[]{"Alice_Node", missing.toString()});

        assertTrue(Files.isDirectory(arguments.workspace()));
    }

    @Test
    void rejectsWorkspaceThatIsARegularFile() throws Exception {
        Path file = Files.writeString(tempDir.resolve("not-a-folder.txt"), "data");

        assertThrows(IllegalArgumentException.class,
                () -> CliArguments.parse(new String[]{"Alice_Node", file.toString()}));
    }
}
