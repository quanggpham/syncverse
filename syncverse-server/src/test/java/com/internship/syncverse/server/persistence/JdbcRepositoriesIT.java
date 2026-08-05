package com.internship.syncverse.server.persistence;

import com.internship.syncverse.common.protocol.ChangeOutcome;
import com.internship.syncverse.common.protocol.FileOperation;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRepositoriesIT {

    private JdbcTemplate jdbc;
    private FileStateRepository files;
    private ChangeLogRepository changes;
    private OperationReceiptRepository receipts;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = dataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        initialize(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        files = new FileStateRepository(jdbc);
        changes = new ChangeLogRepository(jdbc);
        receipts = new OperationReceiptRepository(jdbc);
    }

    @Test
    void persistsFileStateAndChangeBytes() {
        byte[] bytes = "hello".getBytes();
        String checksum = "a".repeat(64);
        Instant now = Instant.parse("2026-08-05T00:00:00Z");

        long version = changes.append("config.json", FileOperation.CREATE,
                bytes, checksum, bytes.length, "Alice_Node", now);
        files.upsert(FileState.present("config.json", bytes, checksum, bytes.length,
                version, "Alice_Node", now));

        FileState state = files.find("config.json").orElseThrow();
        assertEquals(version, state.fileVersion());
        assertArrayEquals(bytes, state.content());
        assertArrayEquals(bytes, changes.findAfter(0, 20).get(0).content());
    }

    @Test
    void persistsTombstoneWithoutContent() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        files.upsert(FileState.tombstone("notes.txt", 7, "Alice_Node", now));

        FileState state = files.find("notes.txt").orElseThrow();

        assertTrue(state.deleted());
        assertNull(state.content());
        assertNull(state.checksum());
        assertEquals(0, state.sizeBytes());
    }

    @Test
    void changeQueryOrdersAcrossIdentityGapsAndLimitsToTwenty() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        for (int index = 0; index < 25; index++) {
            changes.append("file-" + index, FileOperation.CREATE,
                    new byte[]{(byte) index}, "a".repeat(64), 1, "Alice_Node", now);
        }
        jdbc.update("DELETE FROM change_log WHERE global_version = 5");

        List<ChangeRecord> page = changes.findAfter(0, 20);

        assertEquals(20, page.size());
        assertFalse(page.stream().anyMatch(change -> change.globalVersion() == 5));
        assertTrue(isStrictlyIncreasing(page));
    }

    @Test
    void receiptRoundTripsAndCounts() {
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        OperationReceipt receipt = new OperationReceipt(
                operationId, ChangeOutcome.APPLIED, "a.txt", "a.txt", 3L, 3, now);

        receipts.insert(receipt);

        assertEquals(receipt, receipts.find(operationId).orElseThrow());
        assertEquals(1, receipts.count());
    }

    @Test
    void reopeningSameFileDatabasePreservesMaximumVersion(@TempDir Path tempDir) {
        String path = tempDir.resolve("syncverse").toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + path;
        JdbcDataSource first = dataSource(url);
        initialize(first);
        ChangeLogRepository firstChanges = new ChangeLogRepository(new JdbcTemplate(first));
        long version = firstChanges.append("persisted.txt", FileOperation.CREATE,
                new byte[]{1}, "a".repeat(64), 1, "Alice_Node", Instant.now());

        JdbcDataSource reopened = dataSource(url);
        initialize(reopened);
        ChangeLogRepository reopenedChanges =
                new ChangeLogRepository(new JdbcTemplate(reopened));

        assertEquals(version, reopenedChanges.maxVersion());
        assertEquals("persisted.txt", reopenedChanges.findAfter(0, 20).get(0).filename());
    }

    private static boolean isStrictlyIncreasing(List<ChangeRecord> records) {
        for (int index = 1; index < records.size(); index++) {
            if (records.get(index - 1).globalVersion() >= records.get(index).globalVersion()) {
                return false;
            }
        }
        return true;
    }

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void initialize(JdbcDataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
                .execute(dataSource);
    }
}
