package com.internship.syncverse.client.fs;

public record FileSnapshot(String filename, long sizeBytes, String checksum, byte[] content) {

    public FileSnapshot {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
