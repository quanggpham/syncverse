package com.internship.syncverse.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.syncverse.server.SyncVerseServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = SyncVerseServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "syncverse.server-name=ErrorContractServer",
                "spring.datasource.url=jdbc:h2:mem:error-contract;DB_CLOSE_DELAY=-1"
        })
@Import(ErrorContractIT.FailureConfiguration.class)
class ErrorContractIT {

    private static final String REQUEST_ID = "contract-request-42";
    private static final String SECRET = "SELECT content_base64 FROM file_state session=full-secret";

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void malformedRequestUsesCorrelatedSafeError() throws Exception {
        HttpResponse<String> response = post("/api/register", "{broken", null);
        String generatedRequestId = response.headers()
                .firstValue("X-Request-Id").orElseThrow();

        assertFalse(generatedRequestId.isBlank());
        assertError(response, 400, "INVALID_REQUEST", generatedRequestId);
    }

    @Test
    void staleDeleteUsesUniformErrorBody() throws Exception {
        HttpResponse<String> registration = post("/api/register",
                "{\"messageType\":\"HELLO\",\"clientName\":\"Error_Alice\"}", null);
        UUID sessionId = UUID.fromString(mapper.readTree(registration.body()).get("sessionId").asText());
        String request = "{\"messageType\":\"FILE_CHANGE\","
                + "\"sessionId\":\"" + sessionId + "\","
                + "\"operationId\":\"" + UUID.randomUUID() + "\","
                + "\"filename\":\"stale.txt\",\"operation\":\"DELETE\","
                + "\"baseFileVersion\":1,\"checksum\":null,\"contentBase64\":null}";

        assertError(post("/api/files/changes", request, REQUEST_ID),
                409, "STALE_DELETE", REQUEST_ID);
    }

    @Test
    void expiredSessionUsesUniformErrorBody() throws Exception {
        String request = "{\"messageType\":\"HEARTBEAT\",\"sessionId\":\""
                + UUID.randomUUID() + "\"}";

        assertError(post("/api/heartbeat", request, REQUEST_ID),
                410, "SESSION_EXPIRED", REQUEST_ID);
    }

    @Test
    void oversizedRequestUsesUniformErrorBody() throws Exception {
        assertError(post("/api/register", "x".repeat(2 * 1024 * 1024 + 1), REQUEST_ID),
                413, "FILE_TOO_LARGE", REQUEST_ID);
    }

    @Test
    void unexpectedFailureIsMappedWithoutInternalDetails() throws Exception {
        HttpResponse<String> response = get("/api/test/failure", REQUEST_ID);

        assertError(response, 500, "SERVER_ERROR", REQUEST_ID);
        assertFalse(response.body().contains(SECRET));
        assertFalse(response.body().contains("IllegalStateException"));
        assertFalse(response.body().contains("stackTrace"));
    }

    private void assertError(
            HttpResponse<String> response, int status, String code, String requestId)
            throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals(requestId, response.headers().firstValue("X-Request-Id").orElseThrow());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(code, body.get("code").asText());
        assertFalse(body.get("message").asText().isBlank());
        assertEquals(requestId, body.get("requestId").asText());
        assertNotNull(body.get("timestamp"));
        assertTrue(body.get("timestamp").isTextual());
        assertFalse(response.body().contains("contentBase64"));
    }

    private HttpResponse<String> post(String path, String json, String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (requestId != null) {
            request.header("X-Request-Id", requestId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String requestId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("X-Request-Id", requestId)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @TestConfiguration
    static class FailureConfiguration {
        @Bean
        FailureController failureController() {
            return new FailureController();
        }
    }

    @RestController
    static class FailureController {
        @GetMapping("/api/test/failure")
        void fail() {
            throw new IllegalStateException(SECRET);
        }
    }
}
