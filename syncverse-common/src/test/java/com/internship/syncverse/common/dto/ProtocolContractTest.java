package com.internship.syncverse.common.dto;

import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.common.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProtocolContractTest {

    @Test
    void updateCarriesIdempotencyAndConcurrencyFields() {
        UUID session = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var request = new FileChangeRequest(MessageType.FILE_CHANGE, session, operation,
                "config.json", FileOperation.UPDATE, 37L,
                "a".repeat(64), Base64.getEncoder().encodeToString(new byte[]{1}));

        assertAll(
                () -> assertEquals(session, request.sessionId()),
                () -> assertEquals(operation, request.operationId()),
                () -> assertEquals(37L, request.baseFileVersion()));
    }

    @Test
    void deleteCarriesNoChecksumOrContent() {
        var request = new FileChangeRequest(MessageType.FILE_CHANGE, UUID.randomUUID(),
                UUID.randomUUID(), "notes.txt", FileOperation.DELETE, 12L, null, null);

        assertNull(request.checksum());
        assertNull(request.contentBase64());
    }
}
