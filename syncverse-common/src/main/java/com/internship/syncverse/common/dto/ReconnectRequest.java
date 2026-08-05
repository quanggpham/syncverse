package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.MessageType;

public record ReconnectRequest(MessageType messageType, String clientName, long lastSeenGlobalVersion) {
}
