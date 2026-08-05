package com.internship.syncverse.server.session;

public final class SessionExpiredException extends RuntimeException {

    public SessionExpiredException() {
        super("Session is unknown or expired");
    }
}
