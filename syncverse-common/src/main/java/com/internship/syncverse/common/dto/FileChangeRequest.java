package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;

import java.util.UUID;

public record FileChangeRequest(
        MessageType messageType,
        UUID sessionId,
        UUID operationId,
        String filename,
        FileOperation operation,
        long baseFileVersion,
        String checksum,
        String contentBase64) {
}
