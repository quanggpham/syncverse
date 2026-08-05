package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.MessageType;

import java.util.UUID;

public record HeartbeatRequest(MessageType messageType, UUID sessionId) {
}
