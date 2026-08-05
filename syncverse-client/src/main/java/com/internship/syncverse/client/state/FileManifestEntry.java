package com.internship.syncverse.client.state;

public record FileManifestEntry(String checksum, long fileVersion, boolean deleted) {
}
