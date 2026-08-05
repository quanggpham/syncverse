package com.internship.syncverse.client.http;

import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerApiClientTest {

    @Test
    void staleDeleteErrorIsPermanentInsteadOfRetryable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/files/changes", exchange -> {
            byte[] body = ("{\"code\":\"STALE_DELETE\","
                    + "\"message\":\"Canonical file was preserved\","
                    + "\"requestId\":\"request-1\","
                    + "\"timestamp\":\"2026-08-05T00:00:00Z\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ServerApiClient client = ServerApiClient.http(URI.create(
                    "http://localhost:" + server.getAddress().getPort()));
            FileChangeRequest request = new FileChangeRequest(
                    MessageType.FILE_CHANGE,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "stale.txt",
                    FileOperation.DELETE,
                    1,
                    null,
                    null);

            ServerApiException exception = assertThrows(
                    ServerApiException.class, () -> client.fileChange(request));

            assertEquals(ServerApiException.Kind.PERMANENT, exception.kind());
        } finally {
            server.stop(0);
        }
    }
}
