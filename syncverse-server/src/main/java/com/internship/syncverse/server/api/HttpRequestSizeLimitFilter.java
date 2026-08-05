package com.internship.syncverse.server.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpRequestSizeLimitFilter extends OncePerRequestFilter {

    static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;

    private final Clock clock;

    public HttpRequestSizeLimitFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            writeTooLarge(response);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request), response);
        } catch (RequestBodyTooLargeException exception) {
            if (response.isCommitted()) {
                throw exception;
            }
            response.reset();
            writeTooLarge(response);
        }
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"FILE_TOO_LARGE\","
                + "\"message\":\"HTTP request body exceeds 2,097,152 bytes\","
                + "\"requestId\":\"" + UUID.randomUUID() + "\","
                + "\"timestamp\":\"" + clock.instant() + "\"}");
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final ServletInputStream inputStream;

        private LimitedRequest(HttpServletRequest request) throws IOException {
            super(request);
            inputStream = new LimitedInputStream(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() {
            return inputStream;
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long bytesRead;

        private LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                recordBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                recordBytes(count);
            }
            return count;
        }

        private void recordBytes(int count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > MAX_REQUEST_BYTES) {
                throw new RequestBodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
