package com.internship.syncverse.server.api;

import com.internship.syncverse.server.SyncVerseServer;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = SyncVerseServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "syncverse.server-name=TestServer",
                "syncverse.session-expiry=15s",
                "spring.datasource.url=jdbc:h2:mem:registration-api;DB_CLOSE_DELAY=-1"
        })
@Import(RegistrationApiIT.ClockConfiguration.class)
class RegistrationApiIT {

    private static final int MAX_HTTP_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final Pattern SESSION_ID =
            Pattern.compile("\\\"sessionId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ChangeLogRepository changes;

    @Test
    void registerAndHeartbeatSucceed() throws Exception {
        HttpResponse<String> registration = post("/api/register",
                "{\"messageType\":\"HELLO\",\"clientName\":\"Alice_Node\"}");
        UUID sessionId = sessionId(registration.body());

        HttpResponse<String> heartbeat = post("/api/heartbeat",
                "{\"messageType\":\"HEARTBEAT\",\"sessionId\":\"" + sessionId + "\"}");

        assertEquals(201, registration.statusCode());
        assertEquals(204, heartbeat.statusCode());
    }

    @Test
    void registerReportsCurrentGlobalVersion() throws Exception {
        long version = changes.append(
                "existing.txt", FileOperation.CREATE, new byte[]{1},
                "a".repeat(64), 1, "Alice_Node", clock.instant());

        HttpResponse<String> response = post("/api/register",
                "{\"messageType\":\"HELLO\",\"clientName\":\"Version_Node\"}");

        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"currentGlobalVersion\":" + version));
    }

    @Test
    void reconnectIssuesNewSession() throws Exception {
        UUID first = sessionId(post("/api/register",
                "{\"messageType\":\"HELLO\",\"clientName\":\"Reconnect_Node\"}").body());

        HttpResponse<String> response = post("/api/reconnect",
                "{\"messageType\":\"RECONNECT\",\"clientName\":\"Reconnect_Node\"," +
                        "\"lastSeenGlobalVersion\":0}");

        assertEquals(200, response.statusCode());
        assertNotEquals(first, sessionId(response.body()));
    }

    @Test
    void invalidClientNameUsesStableErrorContract() throws Exception {
        HttpResponse<String> response = post("/api/register",
                "{\"messageType\":\"HELLO\",\"clientName\":\"bad name\"}");

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"INVALID_REQUEST\""));
    }

    @Test
    void expiredSessionReturnsGone() throws Exception {
        UUID sessionId = sessionId(post("/api/register",
                "{\"messageType\":\"HELLO\",\"clientName\":\"Expiry_Node\"}").body());
        clock.advance(Duration.ofSeconds(15));

        HttpResponse<String> response = post("/api/heartbeat",
                "{\"messageType\":\"HEARTBEAT\",\"sessionId\":\"" + sessionId + "\"}");

        assertEquals(410, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"SESSION_EXPIRED\""));
    }

    @Test
    void requestBodyOverTwoMebibytesIsRejectedBeforeJsonDecoding() throws Exception {
        HttpResponse<String> response = post(
                "/api/register", "x".repeat(MAX_HTTP_REQUEST_BYTES + 1));

        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("\"code\":\"FILE_TOO_LARGE\""));
    }

    private HttpResponse<String> post(String path, String json)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static UUID sessionId(String json) {
        Matcher matcher = SESSION_ID.matcher(json);
        assertTrue(matcher.find(), () -> "No sessionId in response: " + json);
        return UUID.fromString(matcher.group(1));
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-05T00:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        synchronized void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return instant;
        }
    }
}
