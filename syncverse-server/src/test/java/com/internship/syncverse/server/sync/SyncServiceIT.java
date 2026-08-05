package com.internship.syncverse.server.sync;

import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.protocol.ChangeOutcome;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import com.internship.syncverse.server.persistence.FileState;
import com.internship.syncverse.server.persistence.FileStateRepository;
import com.internship.syncverse.server.persistence.OperationReceipt;
import com.internship.syncverse.server.persistence.OperationReceiptRepository;
import com.internship.syncverse.server.session.ClientSession;
import com.internship.syncverse.server.session.SessionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncServiceIT {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private FileStateRepository files;
    private ChangeLogRepository changes;
    private OperationReceiptRepository receipts;
    private SessionService sessions;
    private ClientSession alice;
    private ClientSession bob;
    private SyncService service;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        files = new FileStateRepository(jdbc);
        changes = new ChangeLogRepository(jdbc);
        receipts = new OperationReceiptRepository(jdbc);
        sessions = new SessionService(clock, Duration.ofSeconds(15));
        alice = sessions.register("Alice_Node");
        bob = sessions.register("Bob_Node");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = service(receipts);
    }

    @Test
    void tenRetriesReturnOriginalResponseAndWriteOnce() {
        FileChangeRequest request = request(
                alice, UUID.randomUUID(), "config.json", FileOperation.CREATE, 0, bytes("one"));

        FileChangeResponse first = service.apply(request);
        for (int retry = 0; retry < 10; retry++) {
            assertEquals(first, service.apply(request));
        }

        assertEquals(1, changes.count());
        assertEquals(1, receipts.count());
    }

    @Test
    void appliesCreateUpdateAndDeleteAsVersionedTombstone() {
        FileChangeResponse created = service.apply(request(
                alice, UUID.randomUUID(), "notes.txt", FileOperation.CREATE, 0, bytes("one")));
        FileChangeResponse updated = service.apply(request(
                alice, UUID.randomUUID(), "notes.txt", FileOperation.UPDATE,
                created.fileVersion(), bytes("two")));
        FileChangeResponse deleted = service.apply(deleteRequest(
                alice, UUID.randomUUID(), "notes.txt", updated.fileVersion()));

        FileState state = files.find("notes.txt").orElseThrow();
        assertTrue(state.deleted());
        assertEquals(deleted.fileVersion(), state.fileVersion());
        assertEquals(3, changes.count());
    }

    @Test
    void staleUpdatePreservesCanonicalAndCreatesDeterministicConflictCopy() {
        FileChangeResponse created = service.apply(request(
                alice, UUID.randomUUID(), "config.json", FileOperation.CREATE, 0, bytes("base")));
        service.apply(request(alice, UUID.randomUUID(), "config.json", FileOperation.UPDATE,
                created.fileVersion(), bytes("alice-new")));
        UUID bobOperation = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000000");

        FileChangeResponse conflict = service.apply(request(
                bob, bobOperation, "config.json", FileOperation.UPDATE,
                created.fileVersion(), bytes("bob-new")));

        assertEquals(ChangeOutcome.CONFLICT_COPY_CREATED, conflict.outcome());
        assertEquals("config.conflict-Bob_Node-a1b2c3d4.json", conflict.acceptedFilename());
        assertArrayEquals(bytes("alice-new"), files.find("config.json").orElseThrow().content());
        assertArrayEquals(bytes("bob-new"),
                files.find(conflict.acceptedFilename()).orElseThrow().content());
    }

    @Test
    void conflictCopyDoesNotOverwriteAnExistingConflictFilename() {
        FileChangeResponse created = service.apply(request(
                alice, UUID.randomUUID(), "config.json", FileOperation.CREATE, 0, bytes("base")));
        service.apply(request(alice, UUID.randomUUID(), "config.json", FileOperation.UPDATE,
                created.fileVersion(), bytes("alice-new")));
        String occupiedName = "config.conflict-Bob_Node-a1b2c3d4.json";
        service.apply(request(
                alice, UUID.randomUUID(), occupiedName, FileOperation.CREATE, 0, bytes("keep-me")));

        FileChangeResponse conflict = service.apply(request(
                bob,
                UUID.fromString("a1b2c3d4-1111-2222-3333-444444444444"),
                "config.json",
                FileOperation.UPDATE,
                created.fileVersion(),
                bytes("bob-new")));

        assertEquals(ChangeOutcome.CONFLICT_COPY_CREATED, conflict.outcome());
        assertEquals("config.conflict-Bob_Node-a1b2c3d41111.json", conflict.acceptedFilename());
        assertArrayEquals(bytes("keep-me"), files.find(occupiedName).orElseThrow().content());
        assertArrayEquals(bytes("bob-new"),
                files.find(conflict.acceptedFilename()).orElseThrow().content());
    }

    @Test
    void staleDeletePreservesCanonicalAndAddsNoChangeRow() {
        FileChangeResponse created = service.apply(request(
                alice, UUID.randomUUID(), "config.json", FileOperation.CREATE, 0, bytes("base")));
        service.apply(request(alice, UUID.randomUUID(), "config.json", FileOperation.UPDATE,
                created.fileVersion(), bytes("newer")));
        long before = changes.count();

        FileChangeResponse rejected = service.apply(deleteRequest(
                bob, UUID.randomUUID(), "config.json", created.fileVersion()));

        assertEquals(ChangeOutcome.CONFLICT_REJECTED, rejected.outcome());
        assertNull(rejected.globalVersion());
        assertEquals(before, changes.count());
        assertArrayEquals(bytes("newer"), files.find("config.json").orElseThrow().content());
    }

    @Test
    void receiptFailureRollsBackChangeAndFileState() {
        OperationReceiptRepository failingReceipts = new OperationReceiptRepository(jdbc) {
            @Override
            public void insert(OperationReceipt receipt) {
                throw new IllegalStateException("injected receipt failure");
            }
        };
        SyncService failingService = service(failingReceipts);

        assertThrows(IllegalStateException.class, () -> failingService.apply(request(
                alice, UUID.randomUUID(), "rollback.txt", FileOperation.CREATE, 0, bytes("data"))));

        assertEquals(0, changes.count());
        assertTrue(files.find("rollback.txt").isEmpty());
        assertEquals(0, receipts.count());
    }

    @Test
    void concurrentMutationsReceiveUniqueIncreasingVersions() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<FileChangeResponse>> tasks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int file = index;
                tasks.add(() -> service.apply(request(
                        alice, UUID.randomUUID(), "file-" + file + ".txt",
                        FileOperation.CREATE, 0, bytes("content-" + file))));
            }

            List<Long> versions = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get().globalVersion();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .sorted()
                    .toList();

            assertEquals(20, new HashSet<>(versions).size());
            assertEquals(20, changes.count());
            assertEquals(20, receipts.count());
            assertEquals(versions, changes.findAfter(0, 20).stream()
                    .map(change -> change.globalVersion()).toList());
        } finally {
            executor.shutdownNow();
        }
    }

    private SyncService service(OperationReceiptRepository receiptRepository) {
        return new SyncService(
                sessions,
                files,
                changes,
                receiptRepository,
                new FileChangeValidator(),
                new ConflictNameGenerator(),
                new GlobalMutationLock(),
                transactions,
                clock);
    }

    private static FileChangeRequest request(
            ClientSession session,
            UUID operationId,
            String filename,
            FileOperation operation,
            long baseVersion,
            byte[] content) {
        return new FileChangeRequest(
                MessageType.FILE_CHANGE,
                session.sessionId(),
                operationId,
                filename,
                operation,
                baseVersion,
                checksum(content),
                Base64.getEncoder().encodeToString(content));
    }

    private static FileChangeRequest deleteRequest(
            ClientSession session, UUID operationId, String filename, long baseVersion) {
        return new FileChangeRequest(
                MessageType.FILE_CHANGE, session.sessionId(), operationId,
                filename, FileOperation.DELETE, baseVersion, null, null);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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
