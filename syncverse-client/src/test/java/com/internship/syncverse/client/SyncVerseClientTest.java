package com.internship.syncverse.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void serverUrlRejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncVerseClient.serverUri(Map.of(
                        "SYNCVERSE_SERVER_URL", "ftp://sync.example/files")));
    }
}
