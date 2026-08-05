package com.internship.syncverse.client.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.syncverse.common.dto.HeartbeatRequest;
import com.internship.syncverse.common.dto.ReconnectRequest;
import com.internship.syncverse.common.dto.RegisterRequest;
import com.internship.syncverse.common.dto.RegisterResponse;
import com.internship.syncverse.common.protocol.MessageType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

public interface ServerApiClient {

    RegisterResponse register(String clientName) throws ServerApiException;

    RegisterResponse reconnect(String clientName, long lastSeenGlobalVersion)
            throws ServerApiException;

    void heartbeat(UUID sessionId) throws ServerApiException;

    static ServerApiClient http(URI serverUri) {
        return new JdkServerApiClient(serverUri);
    }
}

final class JdkServerApiClient implements ServerApiClient {

    private final URI serverUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    JdkServerApiClient(URI serverUri) {
        this.serverUri = serverUri;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public RegisterResponse register(String clientName) throws ServerApiException {
        RegisterRequest request = new RegisterRequest(MessageType.HELLO, clientName);
        return post("/api/register", request, 201, RegisterResponse.class);
    }

    @Override
    public RegisterResponse reconnect(String clientName, long lastSeenGlobalVersion)
            throws ServerApiException {
        ReconnectRequest request = new ReconnectRequest(
                MessageType.RECONNECT, clientName, lastSeenGlobalVersion);
        return post("/api/reconnect", request, 200, RegisterResponse.class);
    }

    @Override
    public void heartbeat(UUID sessionId) throws ServerApiException {
        HeartbeatRequest request = new HeartbeatRequest(MessageType.HEARTBEAT, sessionId);
        post("/api/heartbeat", request, 204, Void.class);
    }

    private <T> T post(String path, Object body, int expectedStatus, Class<T> responseType)
            throws ServerApiException {
        try {
            HttpRequest request = HttpRequest.newBuilder(serverUri.resolve(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            requireStatus(response, expectedStatus);
            if (responseType == Void.class) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ServerApiException.retryable("HTTP request interrupted", exception);
        } catch (IOException exception) {
            throw ServerApiException.retryable("Cannot reach SyncVerse server", exception);
        }
    }

    private static void requireStatus(HttpResponse<String> response, int expectedStatus)
            throws ServerApiException {
        int status = response.statusCode();
        if (status == expectedStatus) {
            return;
        }
        String message = "Server returned HTTP " + status + ": " + response.body();
        if (status == 410) {
            throw ServerApiException.sessionExpired(message);
        }
        if (status == 429 || status >= 500) {
            throw ServerApiException.retryable(message);
        }
        throw ServerApiException.permanent(message);
    }
}
