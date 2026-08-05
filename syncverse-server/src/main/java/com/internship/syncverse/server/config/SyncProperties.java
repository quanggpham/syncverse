package com.internship.syncverse.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("syncverse")
public record SyncProperties(
        String serverName,
        Duration heartbeatInterval,
        Duration sessionExpiry,
        Duration longPollTimeout) {
}
