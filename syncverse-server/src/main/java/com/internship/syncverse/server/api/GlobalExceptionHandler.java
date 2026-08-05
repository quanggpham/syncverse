package com.internship.syncverse.server.api;

import com.internship.syncverse.common.dto.ApiError;
import com.internship.syncverse.server.session.InvalidClientNameException;
import com.internship.syncverse.server.session.SessionExpiredException;
import com.internship.syncverse.server.sync.FileTooLargeException;
import com.internship.syncverse.server.sync.InvalidFileChangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.util.UUID;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler({
            InvalidClientNameException.class,
            InvalidRequestException.class,
            InvalidFileChangeException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableRequest(HttpMessageNotReadableException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof RequestBodyTooLargeException) {
                return error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", cause.getMessage());
            }
            cause = cause.getCause();
        }
        return invalidRequest(exception);
    }

    @ExceptionHandler(FileTooLargeException.class)
    ResponseEntity<ApiError> fileTooLarge(FileTooLargeException exception) {
        return error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(SessionExpiredException.class)
    ResponseEntity<ApiError> sessionExpired(SessionExpiredException exception) {
        return error(HttpStatus.GONE, "SESSION_EXPIRED", exception.getMessage());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        ApiError error = new ApiError(code, message, UUID.randomUUID().toString(), clock.instant());
        return ResponseEntity.status(status).body(error);
    }
}
