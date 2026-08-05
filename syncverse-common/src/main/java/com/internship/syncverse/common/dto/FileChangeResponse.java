package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.ChangeOutcome;

public record FileChangeResponse(
        ChangeOutcome outcome,
        String requestedFilename,
        String acceptedFilename,
        Long globalVersion,
        long fileVersion) {
}
