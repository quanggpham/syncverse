package com.internship.syncverse.server.api;

import java.io.IOException;

final class RequestBodyTooLargeException extends IOException {

    RequestBodyTooLargeException() {
        super("HTTP request body exceeds 2,097,152 bytes");
    }
}
