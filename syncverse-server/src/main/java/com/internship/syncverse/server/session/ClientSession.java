package com.internship.syncverse.server.session;

import java.time.Instant;
import java.util.UUID;

public record ClientSession(String clientName, UUID sessionId, Instant lastSeenAt) {
}
