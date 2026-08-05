package com.internship.syncverse.server.delta;

import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import com.internship.syncverse.server.persistence.FileStateRepository;
import com.internship.syncverse.server.persistence.OperationReceipt;
import com.internship.syncverse.server.persistence.OperationReceiptRepository;
import com.internship.syncverse.server.session.ClientSession;
import com.internship.syncverse.server.session.SessionService;
import com.internship.syncverse.server.sync.ConflictNameGenerator;
import com.internship.syncverse.server.sync.FileChangeValidator;
import com.internship.syncverse.server.sync.GlobalMutationLock;
import com.internship.syncverse.server.sync.SyncService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaServiceIT {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private ChangeLogRepository changes;
    private JdbcTemplate jdbc;
    private FileStateRepository files;
    private ChangeNotifier notifier;
    private DeltaService service;
    private SessionService sessions;
    private ClientSession session;
    private TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        changes = new ChangeLogRepository(jdbc);
        files = new FileStateRepository(jdbc);
        notifier = new ChangeNotifier(changes);
        sessions = new SessionService(clock, Duration.ofMinutes(1));
        session = sessions.register("Alice_Node");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
    void rolledBackMutationDoesNotWakeBlockedPoll() throws Exception {
        OperationReceiptRepository failingReceipts = new OperationReceiptRepository(jdbc) {
            @Override
            public void insert(OperationReceipt receipt) {
                throw new IllegalStateException("injected receipt failure");
            }
        };
        SyncService failingSync = new SyncService(
                sessions, files, changes, failingReceipts,
                new FileChangeValidator(), new ConflictNameGenerator(),
                new GlobalMutationLock(), transactions, clock, notifier);
        byte[] content = new byte[]{7};
        FileChangeRequest request = new FileChangeRequest(
                MessageType.FILE_CHANGE,
                session.sessionId(),
                UUID.randomUUID(),
                "rollback.txt",
                FileOperation.CREATE,
                0,
                checksum(content),
                Base64.getEncoder().encodeToString(content));
        var executor = Executors.newSingleThreadExecutor();
        long started = System.nanoTime();
        try {
            var future = executor.submit(() -> service.poll(session.sessionId(), 0));

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, () -> failingSync.apply(request));
            DeltaResponse response = future.get(1, TimeUnit.SECONDS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(0, changes.count());
            assertTrue(files.find("rollback.txt").isEmpty());
            assertTrue(response.changes().isEmpty());
            assertTrue(elapsed.compareTo(Duration.ofMillis(70)) >= 0, elapsed::toString);
        } finally {
            executor.shutdownNow();
        }
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

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
