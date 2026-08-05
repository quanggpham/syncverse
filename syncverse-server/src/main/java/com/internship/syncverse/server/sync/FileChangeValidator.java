package com.internship.syncverse.server.sync;

import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
public final class FileChangeValidator {

    public static final int MAX_FILE_SIZE = 1_048_576;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public byte[] validate(FileChangeRequest request) {
        if (request == null || request.messageType() != MessageType.FILE_CHANGE) {
            throw new InvalidFileChangeException("Expected FILE_CHANGE request");
        }
        if (request.sessionId() == null || request.operationId() == null) {
            throw new InvalidFileChangeException("Session ID and operation ID are required");
        }
        if (request.operation() == null || request.baseFileVersion() < 0) {
            throw new InvalidFileChangeException(
                    "Operation is required and base version cannot be negative");
        }
        validateFilename(request.filename());

        if (request.operation() == FileOperation.DELETE) {
            if (request.checksum() != null || request.contentBase64() != null) {
                throw new InvalidFileChangeException(
                        "DELETE must not carry checksum or content");
            }
            return null;
        }

        if (request.checksum() == null || !SHA_256.matcher(request.checksum()).matches()) {
            throw new InvalidFileChangeException("Checksum must be lowercase SHA-256");
        }
        if (request.contentBase64() == null) {
            throw new InvalidFileChangeException("CREATE and UPDATE require content");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(request.contentBase64());
        } catch (IllegalArgumentException exception) {
            throw new InvalidFileChangeException("Content is not valid Base64", exception);
        }
        validateDecodedSize(decoded.length);
        if (!checksum(decoded).equals(request.checksum())) {
            throw new InvalidFileChangeException("Checksum does not match decoded content");
        }
        return decoded;
    }

    public void validateFilename(String filename) {
        if (filename == null
                || filename.isBlank()
                || filename.length() > 255
                || filename.contains("/")
                || filename.contains("\\")
                || filename.contains("..")
                || filename.indexOf('\0') >= 0) {
            throw new InvalidFileChangeException(
                    "Filename must be one flat base name without traversal");
        }
    }

    public void validateDecodedSize(long size) {
        if (size < 0) {
            throw new InvalidFileChangeException("Decoded size cannot be negative");
        }
        if (size > MAX_FILE_SIZE) {
            throw new FileTooLargeException(size);
        }
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
