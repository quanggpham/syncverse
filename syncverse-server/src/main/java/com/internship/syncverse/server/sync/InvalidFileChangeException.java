package com.internship.syncverse.server.sync;

public final class InvalidFileChangeException extends RuntimeException {

    public InvalidFileChangeException(String message) {
        super(message);
    }

    public InvalidFileChangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
