package com.internship.syncverse.server.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

public final class SessionService {

    private static final Pattern CLIENT_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Clock clock;
    private final Duration expiry;
    private final ConcurrentMap<UUID, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> sessionsByClient = new ConcurrentHashMap<>();

    public SessionService(Clock clock, Duration expiry) {
        if (expiry.isZero() || expiry.isNegative()) {
            throw new IllegalArgumentException("Session expiry must be positive");
        }
        this.clock = clock;
        this.expiry = expiry;
    }

    public synchronized ClientSession register(String clientName) {
        validateClientName(clientName);
        UUID sessionId = UUID.randomUUID();
        ClientSession session = new ClientSession(clientName, sessionId, clock.instant());
        UUID previousId = sessionsByClient.put(clientName, sessionId);
        if (previousId != null) {
            sessions.remove(previousId);
        }
        sessions.put(sessionId, session);
        return session;
    }

    public ClientSession reconnect(String clientName, long lastSeenGlobalVersion) {
        if (lastSeenGlobalVersion < 0) {
            throw new IllegalArgumentException("Last seen global version cannot be negative");
        }
        return register(clientName);
    }

    public ClientSession heartbeat(UUID sessionId) {
        ClientSession active = requireActive(sessionId);
        ClientSession refreshed = new ClientSession(
                active.clientName(), active.sessionId(), clock.instant());
        sessions.replace(sessionId, active, refreshed);
        return requireActive(sessionId);
    }

    public ClientSession requireActive(UUID sessionId) {
        if (sessionId == null) {
            throw new SessionExpiredException();
        }
        ClientSession session = sessions.get(sessionId);
        if (session == null || isExpired(session)) {
            if (session != null) {
                sessions.remove(sessionId, session);
                sessionsByClient.remove(session.clientName(), sessionId);
            }
            throw new SessionExpiredException();
        }
        return session;
    }

    private boolean isExpired(ClientSession session) {
        Instant expiresAt = session.lastSeenAt().plus(expiry);
        return !clock.instant().isBefore(expiresAt);
    }

    private static void validateClientName(String clientName) {
        if (clientName == null || !CLIENT_NAME.matcher(clientName).matches()) {
            throw new InvalidClientNameException();
        }
    }
}
