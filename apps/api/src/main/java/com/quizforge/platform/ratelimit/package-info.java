/**
 * Per-caller rate limiting.
 *
 * <p>Published so the security configuration can place the filter in the chain,
 * and so {@code identity} can answer which credential is being limited.
 */
@org.springframework.modulith.NamedInterface("ratelimit")
package com.quizforge.platform.ratelimit;
