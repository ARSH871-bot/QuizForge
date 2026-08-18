package com.quizforge.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token a client sends must be the token the cookie gave it.
 *
 * <p>This guards a defect that no MockMvc test could see. Spring Security 6
 * masks the CSRF token by default (BREACH protection): the {@code XSRF-TOKEN}
 * cookie carries the raw value while the header is expected to carry a masked
 * one, so a client that echoes the cookie back is rejected. Every write test in
 * the suite passes anyway, because {@code .with(csrf())} constructs a valid
 * masked token internally — it exercises a path no browser or curl can take.
 *
 * <p>The consequence was not subtle: <strong>no cookie-authenticated write was
 * possible for any real client</strong>, which meant a developer could not
 * create a workspace or mint an API key. Verified against a running server, not
 * inferred.
 */
class CsrfTokenHandlingTest {

    private static final String RAW = "6ba21470-5ed3-4cf9-83c4-3329576f0f97";

    private static MockHttpServletRequest requestSending(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-XSRF-TOKEN", headerValue);
        return request;
    }

    @Test
    void theConfiguredHandlerAcceptsTheRawCookieValue() {
        var token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", RAW);

        String resolved = SecurityConfig.plainCsrfTokenHandler()
                .resolveCsrfTokenValue(requestSending(RAW), token);

        assertThat(resolved)
                .as("a client echoing the XSRF-TOKEN cookie must be accepted")
                .isEqualTo(RAW);
    }

    @Test
    void theDefaultMaskingHandlerWouldRejectIt() {
        // Not a test of Spring, but of why the configuration deviates from its
        // default. If someone removes the override, this is what returns.
        var token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", RAW);

        String resolved = new XorCsrfTokenRequestAttributeHandler()
                .resolveCsrfTokenValue(requestSending(RAW), token);

        assertThat(resolved)
                .as("the default handler expects a masked token, so the raw cookie value fails")
                .isNotEqualTo(RAW);
    }
}
