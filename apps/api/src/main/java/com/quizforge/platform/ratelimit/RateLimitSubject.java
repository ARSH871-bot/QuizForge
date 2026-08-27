package com.quizforge.platform.ratelimit;

import java.util.UUID;

/**
 * Who is being rate limited.
 *
 * <p>An outbound port. The limiter needs to know which credential made the
 * request, and that lives in {@code identity} — which {@code platform} cannot
 * depend on, because every module depends on {@code platform}. So the question
 * is declared here and answered there.
 */
public interface RateLimitSubject {

    /**
     * The credential to charge, or {@code null} when the request is anonymous.
     *
     * <p>The <em>credential</em>, not the person: two API keys in one workspace
     * get separate allowances, so a runaway script does not starve the
     * dashboard sitting beside it.
     */
    UUID current();
}
