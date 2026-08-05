package com.internship.syncverse.server.session;

public final class InvalidClientNameException extends RuntimeException {

    public InvalidClientNameException() {
        super("Client name must match [A-Za-z0-9_-]{1,64}");
    }
}
