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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DirectoryScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryScanner.class);
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
        if (!path.getParent().equals(workspace)) {
            return java.util.Optional.empty();
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.info("Skipping subdirectory '{}' (SyncVerse only supports flat directories)", path.getFileName());
            return java.util.Optional.empty();
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.info("Skipping non-regular file or symbolic link '{}'", path.getFileName());
            return java.util.Optional.empty();
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            LOGGER.warn("Skipping file '{}' (size {} bytes exceeds 1MB cap)", path.getFileName(), Files.size(path));
            return java.util.Optional.empty();
        }
        byte[] content = Files.readAllBytes(path);
        if (content.length > MAX_FILE_BYTES) {
            LOGGER.warn("Skipping file '{}' (size {} bytes exceeds 1MB cap)", path.getFileName(), content.length);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new FileSnapshot(
                path.getFileName().toString(), content.length, checksum(content), content));
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
