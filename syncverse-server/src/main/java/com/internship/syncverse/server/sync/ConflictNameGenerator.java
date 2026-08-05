package com.internship.syncverse.server.sync;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class ConflictNameGenerator {

    public String generate(String filename, String clientName, UUID operationId) {
        int extensionStart = filename.lastIndexOf('.');
        String base = extensionStart > 0 ? filename.substring(0, extensionStart) : filename;
        String extension = extensionStart > 0 ? filename.substring(extensionStart) : "";
        String operationPrefix = operationId.toString().substring(0, 8);
        return base + ".conflict-" + clientName + "-" + operationPrefix + extension;
    }
}
