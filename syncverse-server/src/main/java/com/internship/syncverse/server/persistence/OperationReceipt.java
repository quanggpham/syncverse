package com.internship.syncverse.server.persistence;

import com.internship.syncverse.common.protocol.ChangeOutcome;

import java.time.Instant;
import java.util.UUID;

public record OperationReceipt(
        UUID operationId,
        ChangeOutcome outcome,
        String requestedFilename,
        String acceptedFilename,
        Long globalVersion,
        long fileVersion,
        Instant createdAt) {
}
