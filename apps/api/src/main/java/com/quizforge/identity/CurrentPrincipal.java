package com.quizforge.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The principal behind the request being handled.
 *
 * <p>Exists because controllers now implement interfaces generated from
 * {@code openapi.yaml}, and a generated signature contains exactly the
 * parameters the contract describes. {@code @AuthenticationPrincipal} was an
 * extra parameter that no contract would ever mention, so it could not survive
 * the retrofit.
 *
 * <p>That is the right outcome rather than a workaround: who is calling is not
 * part of the HTTP contract, it is ambient to the request. Reading it from the
 * security context says so, and keeps the generated signatures honest.
 */
public final class CurrentPrincipal {

    private CurrentPrincipal() {
    }

    /** The current principal, or {@code null} if the request is anonymous. */
    public static Principal get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Principal p)) {
            return null;
        }
        return p;
    }
}
