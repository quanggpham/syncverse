package com.internship.syncverse.server.delta;

import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import com.internship.syncverse.server.session.ClientSession;
import com.internship.syncverse.server.session.SessionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaServiceIT {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private ChangeLogRepository changes;
    private ChangeNotifier notifier;
    private DeltaService service;
    private ClientSession session;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(dataSource);
        changes = new ChangeLogRepository(new JdbcTemplate(dataSource));
        notifier = new ChangeNotifier(changes);
        SessionService sessions = new SessionService(Clock.systemUTC(), Duration.ofMinutes(1));
        session = sessions.register("Alice_Node");
        service = new DeltaService(sessions, changes, notifier, TIMEOUT);
    }

    @Test
    void existingChangesReturnImmediatelyInOrder() {
        append("one.txt", new byte[]{1});
        append("two.txt", new byte[]{2});
        long started = System.nanoTime();

        DeltaResponse response = service.poll(session.sessionId(), 0);

        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(TIMEOUT) < 0);
        assertEquals(java.util.List.of("one.txt", "two.txt"),
                response.changes().stream().map(change -> change.filename()).toList());
    }

    @Test
    void emptyDatabaseReturnsEmptyNearConfiguredTimeout() {
        long started = System.nanoTime();

        DeltaResponse response = service.poll(session.sessionId(), 0);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(response.changes().isEmpty());
        assertTrue(elapsed.compareTo(Duration.ofMillis(70)) >= 0, elapsed::toString);
        assertTrue(elapsed.compareTo(Duration.ofMillis(500)) < 0, elapsed::toString);
    }

    @Test
    void committedVersionWakesBlockedPoll() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> service.poll(session.sessionId(), 0));
            long version = append("wake.txt", new byte[]{3});
            notifier.signalCommitted(version);

            DeltaResponse response = future.get(1, TimeUnit.SECONDS);

            assertEquals(1, response.changes().size());
            assertEquals("wake.txt", response.changes().get(0).filename());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonAdvancingSignalDoesNotWakePollAsIfRollbackCommitted() {
        long started = System.nanoTime();
        notifier.signalCommitted(0);

        DeltaResponse response = service.poll(session.sessionId(), 0);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(response.changes().isEmpty());
        assertTrue(elapsed.compareTo(Duration.ofMillis(70)) >= 0, elapsed::toString);
    }

    @Test
    void responseIsLimitedToTwentyChanges() {
        for (int index = 0; index < 25; index++) {
            append("file-" + index + ".txt", new byte[]{(byte) index});
        }

        DeltaResponse response = service.poll(session.sessionId(), 0);

        assertEquals(20, response.changes().size());
        assertEquals(response.changes().get(19).globalVersion(), response.latestGlobalVersion());
    }

    private long append(String filename, byte[] content) {
        return changes.append(filename, FileOperation.CREATE, content,
                "a".repeat(64), content.length, "Alice_Node", Instant.now());
    }
}
