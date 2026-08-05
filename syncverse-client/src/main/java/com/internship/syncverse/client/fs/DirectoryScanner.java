package com.internship.syncverse.client.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class DirectoryScanner {

    public static final int MAX_FILE_BYTES = 1_048_576;

    private final Path workspace;

    public DirectoryScanner(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public Map<String, FileSnapshot> scan() throws IOException {
        Map<String, FileSnapshot> snapshots = new TreeMap<>();
        try (Stream<Path> entries = Files.list(workspace)) {
            for (Path path : entries.toList()) {
                snapshot(path).ifPresent(value -> snapshots.put(value.filename(), value));
            }
        }
        return Map.copyOf(snapshots);
    }

    public java.util.Optional<FileSnapshot> snapshot(String filename) throws IOException {
        return snapshot(workspace.resolve(filename));
    }

    private java.util.Optional<FileSnapshot> snapshot(Path path) throws IOException {
        if (!path.getParent().equals(workspace)
                || isInternalTemporaryFile(path.getFileName().toString())
                || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_FILE_BYTES) {
            return java.util.Optional.empty();
        }
        byte[] content = Files.readAllBytes(path);
        if (content.length > MAX_FILE_BYTES) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new FileSnapshot(
                path.getFileName().toString(), content.length, checksum(content), content));
    }

    static boolean isInternalTemporaryFile(String filename) {
        return filename.startsWith(".syncverse-") && filename.endsWith(".tmp");
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
