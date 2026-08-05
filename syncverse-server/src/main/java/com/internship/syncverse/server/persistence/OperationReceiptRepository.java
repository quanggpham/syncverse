package com.internship.syncverse.server.persistence;

import com.internship.syncverse.common.protocol.ChangeOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OperationReceiptRepository {

    private static final String INSERT = """
            INSERT INTO operation_receipt
                (operation_id, outcome, requested_filename, accepted_filename,
                 global_version, file_version, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public OperationReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OperationReceipt> find(UUID operationId) {
        return jdbc.query("SELECT * FROM operation_receipt WHERE operation_id = ?",
                        OperationReceiptRepository::map, operationId)
                .stream()
                .findFirst();
    }

    public void insert(OperationReceipt receipt) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(INSERT);
            statement.setObject(1, receipt.operationId());
            statement.setString(2, receipt.outcome().name());
            statement.setString(3, receipt.requestedFilename());
            statement.setString(4, receipt.acceptedFilename());
            if (receipt.globalVersion() == null) {
                statement.setNull(5, Types.BIGINT);
            } else {
                statement.setLong(5, receipt.globalVersion());
            }
            statement.setLong(6, receipt.fileVersion());
            statement.setObject(7, receipt.createdAt().atOffset(ZoneOffset.UTC));
            return statement;
        });
    }

    public long count() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_receipt", Long.class);
        return count == null ? 0 : count;
    }

    private static OperationReceipt map(ResultSet result, int row) throws SQLException {
        long storedVersion = result.getLong("global_version");
        Long globalVersion = result.wasNull() ? null : storedVersion;
        return new OperationReceipt(
                result.getObject("operation_id", UUID.class),
                ChangeOutcome.valueOf(result.getString("outcome")),
                result.getString("requested_filename"),
                result.getString("accepted_filename"),
                globalVersion,
                result.getLong("file_version"),
                result.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
