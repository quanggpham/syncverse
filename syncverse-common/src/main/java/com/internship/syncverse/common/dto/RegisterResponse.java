package com.internship.syncverse.common.dto;

import java.util.UUID;

public record RegisterResponse(String clientName, UUID sessionId, long currentGlobalVersion) {
}
