package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.FileOperation;

public record FileRevision(
        long globalVersion,
        String filename,
        FileOperation operation,
        long fileVersion,
        String checksum,
        long sizeBytes,
        String contentBase64) {
}
