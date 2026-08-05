package com.internship.syncverse.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncVerseClientTest {

    @Test
    void serverUrlDefaultsToLocalhost() {
        assertEquals(URI.create("http://localhost:8080"),
                SyncVerseClient.serverUri(Map.of()));
    }

    @Test
    void serverUrlCanBeOverriddenByEnvironment() {
        assertEquals(URI.create("http://sync.example:9090"),
                SyncVerseClient.serverUri(Map.of(
                        "SYNCVERSE_SERVER_URL", "http://sync.example:9090")));
    }
}
