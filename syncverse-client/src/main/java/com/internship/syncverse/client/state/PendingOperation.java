package com.internship.syncverse.client.state;

import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import com.internship.syncverse.common.dto.FileChangeRequest;

import java.util.UUID;

public record PendingOperation(
        UUID operationId,
        String filename,
        FileOperation operation,
        long baseFileVersion,
        String checksum,
        String contentBase64) {

    public FileChangeRequest request(UUID sessionId) {
        return new FileChangeRequest(
                MessageType.FILE_CHANGE,
                sessionId,
                operationId,
                filename,
                operation,
                baseFileVersion,
                checksum,
                contentBase64);
    }
}
