package com.internship.syncverse.server.persistence;

import com.internship.syncverse.common.protocol.FileOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class ChangeLogRepository {

    private static final String INSERT = """
            INSERT INTO change_log
                (filename, operation, content, checksum, size_bytes, client_name, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public ChangeLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long append(
            String filename,
            FileOperation operation,
            byte[] content,
            String checksum,
            long sizeBytes,
            String clientName,
            Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    INSERT, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, filename);
            statement.setString(2, operation.name());
            statement.setBytes(3, content);
            statement.setString(4, checksum);
            statement.setLong(5, sizeBytes);
            statement.setString(6, clientName);
            statement.setObject(7, createdAt.atOffset(ZoneOffset.UTC));
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a global version");
        }
        return key.longValue();
    }

    public List<ChangeRecord> findAfter(long cursor, int limit) {
        if (cursor < 0 || limit < 1) {
            throw new IllegalArgumentException("Cursor must be non-negative and limit positive");
        }
        return jdbc.query("""
                        SELECT * FROM change_log
                        WHERE global_version > ?
                        ORDER BY global_version ASC
                        LIMIT ?
                        """,
                ChangeLogRepository::map, cursor, limit);
    }

    public long maxVersion() {
        Long maximum = jdbc.queryForObject(
                "SELECT COALESCE(MAX(global_version), 0) FROM change_log", Long.class);
        return maximum == null ? 0 : maximum;
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM change_log", Long.class);
        return count == null ? 0 : count;
    }

    private static ChangeRecord map(ResultSet result, int row) throws SQLException {
        return new ChangeRecord(
                result.getLong("global_version"),
                result.getString("filename"),
                FileOperation.valueOf(result.getString("operation")),
                result.getBytes("content"),
                result.getString("checksum"),
                result.getLong("size_bytes"),
                result.getString("client_name"),
                result.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
