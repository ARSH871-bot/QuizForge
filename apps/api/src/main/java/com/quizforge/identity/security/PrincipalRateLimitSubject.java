package com.quizforge.identity.security;

import com.quizforge.identity.CurrentPrincipal;
import com.quizforge.identity.Principal;
import com.quizforge.platform.ratelimit.RateLimitSubject;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Answers {@link RateLimitSubject} from the authenticated principal.
 *
 * <p>Lives here because this is where the principal is. The interface lives in
 * {@code platform} because that is where the question is asked — which keeps
 * the dependency running {@code identity} to {@code platform}, the direction it
 * already runs.
 */
@Component
public class PrincipalRateLimitSubject implements RateLimitSubject {

    @Override
    public UUID current() {
        Principal principal = CurrentPrincipal.get();
        return principal == null ? null : principal.credentialId();
    }
}
