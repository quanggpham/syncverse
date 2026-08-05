package com.internship.syncverse.server.persistence;

import com.internship.syncverse.common.protocol.FileOperation;

import java.time.Instant;
import java.util.Arrays;

public record ChangeRecord(
        long globalVersion,
        String filename,
        FileOperation operation,
        byte[] content,
        String checksum,
        long sizeBytes,
        String clientName,
        Instant createdAt) {

    public ChangeRecord {
        content = copy(content);
    }

    @Override
    public byte[] content() {
        return copy(content);
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }
}
