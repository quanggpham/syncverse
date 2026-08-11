package com.internship.syncverse.server.api;

import com.internship.syncverse.common.dto.ApiError;
import com.internship.syncverse.server.session.InvalidClientNameException;
import com.internship.syncverse.server.session.SessionExpiredException;
import com.internship.syncverse.server.sync.FileTooLargeException;
import com.internship.syncverse.server.sync.InvalidFileChangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
                LOGGER.warn("Rejected request: payload size exceeds max limit (2MB HTTP cap)");
                return error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", cause.getMessage());
            }
            cause = cause.getCause();
        }
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed JSON request body");
    }

    @ExceptionHandler(FileTooLargeException.class)
    ResponseEntity<ApiError> fileTooLarge(FileTooLargeException exception) {
        LOGGER.warn("Rejected file upload exceeding 1MB cap: {}", exception.getMessage());
        return error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(SessionExpiredException.class)
    ResponseEntity<ApiError> sessionExpired(SessionExpiredException exception) {
        return error(HttpStatus.GONE, "SESSION_EXPIRED", exception.getMessage());
    }

    @ExceptionHandler(StaleDeleteException.class)
    ResponseEntity<ApiError> staleDelete(StaleDeleteException exception) {
        return error(HttpStatus.CONFLICT, "STALE_DELETE", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        LOGGER.error("Unexpected server error: {}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR",
                "Unexpected server error; use requestId for support");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        ApiError error = new ApiError(code, message, requestId(), clock.instant());
        return ResponseEntity.status(status).body(error);
    }

    private static String requestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "unavailable" : requestId;
    }
}
