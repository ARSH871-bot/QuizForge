package com.quizforge.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes. These are part of the public API contract:
 * clients switch on them, so an existing constant's name and status may never
 * change. Add new constants rather than repurposing old ones.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    ALREADY_EXISTS(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Stable URI used as the Problem Details {@code type}. */
    public String type() {
        return "https://quizforge.dev/errors/" + name().toLowerCase().replace('_', '-');
    }
}
