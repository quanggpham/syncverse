package com.internship.syncverse.client.http;

public final class ServerApiException extends Exception {

    public enum Kind {
        RETRYABLE,
        SESSION_EXPIRED,
        PERMANENT
    }

    private final Kind kind;

    private ServerApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static ServerApiException retryable(String message) {
        return new ServerApiException(Kind.RETRYABLE, message, null);
    }

    public static ServerApiException retryable(String message, Throwable cause) {
        return new ServerApiException(Kind.RETRYABLE, message, cause);
    }

    public static ServerApiException sessionExpired(String message) {
        return new ServerApiException(Kind.SESSION_EXPIRED, message, null);
    }

    public static ServerApiException permanent(String message) {
        return new ServerApiException(Kind.PERMANENT, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
