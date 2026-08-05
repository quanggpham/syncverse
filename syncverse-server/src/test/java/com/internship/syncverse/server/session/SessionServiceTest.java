package com.internship.syncverse.server.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionServiceTest {

    private static final Duration EXPIRY = Duration.ofSeconds(15);

    @Test
    void sessionExpiresAtConfiguredBoundary() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-08-05T00:00:00Z"));
        SessionService service = new SessionService(clock, EXPIRY);
        ClientSession session = service.register("Alice_Node");

        clock.advance(EXPIRY);

        assertThrows(SessionExpiredException.class,
                () -> service.requireActive(session.sessionId()));
    }

    @Test
    void heartbeatRefreshesSessionLiveness() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-08-05T00:00:00Z"));
        SessionService service = new SessionService(clock, EXPIRY);
        ClientSession session = service.register("Alice_Node");

        clock.advance(Duration.ofSeconds(10));
        service.heartbeat(session.sessionId());
        clock.advance(Duration.ofSeconds(10));

        assertDoesNotThrow(() -> service.requireActive(session.sessionId()));
    }

    @Test
    void reconnectIssuesANewSession() {
        SessionService service = new SessionService(Clock.systemUTC(), EXPIRY);
        ClientSession original = service.register("Alice_Node");

        ClientSession reconnected = service.reconnect("Alice_Node", 0);

        assertNotEquals(original.sessionId(), reconnected.sessionId());
        assertThrows(SessionExpiredException.class,
                () -> service.requireActive(original.sessionId()));
    }

    @Test
    void duplicateClientNameInvalidatesPreviousSession() {
        SessionService service = new SessionService(Clock.systemUTC(), EXPIRY);
        ClientSession original = service.register("Alice_Node");

        ClientSession replacement = service.register("Alice_Node");

        assertNotEquals(original.sessionId(), replacement.sessionId());
        assertThrows(SessionExpiredException.class,
                () -> service.requireActive(original.sessionId()));
        assertDoesNotThrow(() -> service.requireActive(replacement.sessionId()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        static MutableClock at(Instant instant) {
            return new MutableClock(instant);
        }

        void advance(Duration duration) {
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
        public Instant instant() {
            return instant;
        }
    }
}
