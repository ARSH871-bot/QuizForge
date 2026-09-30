package com.quizforge.platform.error;

/**
 * A {@code 429} raised from inside a request, rather than by the rate-limit
 * filter. Carries how long to wait, so the response has a {@code Retry-After}
 * like every other {@code 429} the API sends.
 */
public class RateLimitedException extends ApiException {

    private final int retryAfterSeconds;

    public RateLimitedException(String message, int retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
