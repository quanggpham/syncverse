package com.internship.syncverse.server.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class FileStateRepository {

    private static final String UPSERT = """
            MERGE INTO file_state
                (filename, content, checksum, size_bytes, file_version,
                 deleted, modified_by, modified_at)
            KEY(filename)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public FileStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FileState> find(String filename) {
        return jdbc.query("SELECT * FROM file_state WHERE filename = ?",
                        FileStateRepository::map, filename)
                .stream()
                .findFirst();
    }

    public void upsert(FileState state) {
        jdbc.update(UPSERT,
                state.filename(),
                state.content(),
                state.checksum(),
                state.sizeBytes(),
                state.fileVersion(),
                state.deleted(),
                state.modifiedBy(),
                state.modifiedAt().atOffset(ZoneOffset.UTC));
    }

    private static FileState map(ResultSet result, int row) throws SQLException {
        return new FileState(
                result.getString("filename"),
                result.getBytes("content"),
                result.getString("checksum"),
                result.getLong("size_bytes"),
                result.getLong("file_version"),
                result.getBoolean("deleted"),
                result.getString("modified_by"),
                result.getObject("modified_at", OffsetDateTime.class).toInstant());
    }
}
