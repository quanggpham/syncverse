package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.MessageType;

public record RegisterRequest(MessageType messageType, String clientName) {
}
