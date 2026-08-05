package com.internship.syncverse.server.sync;

import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileChangeValidatorTest {

    private final FileChangeValidator validator = new FileChangeValidator();

    @ParameterizedTest
    @ValueSource(strings = {"../secret", "a/b.txt", "a\\b.txt", "..", "\u0000bad"})
    void rejectsNonFlatNames(String filename) {
        assertThrows(InvalidFileChangeException.class,
                () -> validator.validateFilename(filename));
    }

    @Test
    void enforcesDecodedLimit() {
        assertDoesNotThrow(() -> validator.validateDecodedSize(1_048_576));
        assertThrows(FileTooLargeException.class,
                () -> validator.validateDecodedSize(1_048_577));
    }

    @Test
    void rejectsInvalidBase64() {
        FileChangeRequest request = contentRequest("%%%", "a".repeat(64));

        assertThrows(InvalidFileChangeException.class,
                () -> validator.validate(request));
    }

    @Test
    void rejectsChecksumMismatch() {
        String content = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        FileChangeRequest request = contentRequest(content, "a".repeat(64));

        assertThrows(InvalidFileChangeException.class,
                () -> validator.validate(request));
    }

    @Test
    void returnsDecodedContentWhenChecksumMatches() throws Exception {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        FileChangeRequest request = contentRequest(
                Base64.getEncoder().encodeToString(bytes), checksum);

        assertArrayEquals(bytes, validator.validate(request));
    }

    @Test
    void deleteRequiresNullChecksumAndContent() {
        FileChangeRequest valid = new FileChangeRequest(
                MessageType.FILE_CHANGE, UUID.randomUUID(), UUID.randomUUID(),
                "notes.txt", FileOperation.DELETE, 1, null, null);
        FileChangeRequest invalid = new FileChangeRequest(
                MessageType.FILE_CHANGE, UUID.randomUUID(), UUID.randomUUID(),
                "notes.txt", FileOperation.DELETE, 1, "a".repeat(64), "");

        assertDoesNotThrow(() -> validator.validate(valid));
        assertThrows(InvalidFileChangeException.class,
                () -> validator.validate(invalid));
    }

    private static FileChangeRequest contentRequest(String content, String checksum) {
        return new FileChangeRequest(
                MessageType.FILE_CHANGE, UUID.randomUUID(), UUID.randomUUID(),
                "notes.txt", FileOperation.UPDATE, 1, checksum, content);
    }
}
