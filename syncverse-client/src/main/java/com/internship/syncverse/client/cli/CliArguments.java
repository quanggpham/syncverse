package com.internship.syncverse.client.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public record CliArguments(String clientName, Path workspace) {

    private static final Pattern CLIENT_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public static CliArguments parse(String[] args) throws IOException {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: java -jar client.jar <clientName> <folderPath>");
        }
        if (!CLIENT_NAME.matcher(args[0]).matches()) {
            throw new IllegalArgumentException(
                    "Client name must match [A-Za-z0-9_-]{1,64}");
        }

        Path workspace = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(workspace) && !Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("Workspace path is not a directory: " + workspace);
        }
        Files.createDirectories(workspace);
        return new CliArguments(args[0], workspace);
    }
}
