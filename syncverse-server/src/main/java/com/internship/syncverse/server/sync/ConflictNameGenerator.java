package com.internship.syncverse.server.sync;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class ConflictNameGenerator {

    public String generate(String filename, String clientName, UUID operationId) {
        return generate(filename, clientName, operationId, 0);
    }

    public String generate(
            String filename, String clientName, UUID operationId, int uniquenessAttempt) {
        int extensionStart = filename.lastIndexOf('.');
        String base = extensionStart > 0 ? filename.substring(0, extensionStart) : filename;
        String extension = extensionStart > 0 ? filename.substring(extensionStart) : "";
        String operationToken = operationId.toString().replace("-", "");
        int prefixLength = Math.min(8 + (uniquenessAttempt * 4), operationToken.length());
        String collisionSuffix = uniquenessAttempt > 6
                ? "-" + (uniquenessAttempt - 6)
                : "";
        return base + ".conflict-" + clientName + "-"
                + operationToken.substring(0, prefixLength) + collisionSuffix + extension;
    }
}
