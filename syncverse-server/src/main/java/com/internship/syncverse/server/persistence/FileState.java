package com.internship.syncverse.server.persistence;

import java.time.Instant;
import java.util.Arrays;

public record FileState(
        String filename,
        byte[] content,
        String checksum,
        long sizeBytes,
        long fileVersion,
        boolean deleted,
        String modifiedBy,
        Instant modifiedAt) {

    public FileState {
        content = copy(content);
    }

    public static FileState present(
            String filename,
            byte[] content,
            String checksum,
            long sizeBytes,
            long fileVersion,
            String modifiedBy,
            Instant modifiedAt) {
        return new FileState(filename, content, checksum, sizeBytes,
                fileVersion, false, modifiedBy, modifiedAt);
    }

    public static FileState tombstone(
            String filename, long fileVersion, String modifiedBy, Instant modifiedAt) {
        return new FileState(filename, null, null, 0,
                fileVersion, true, modifiedBy, modifiedAt);
    }

    @Override
    public byte[] content() {
        return copy(content);
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }
}
