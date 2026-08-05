package com.internship.syncverse.server.sync;

public final class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(long size) {
        super("Decoded file exceeds 1,048,576 bytes: " + size);
    }
}
