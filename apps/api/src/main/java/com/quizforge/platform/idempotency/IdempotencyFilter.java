package com.quizforge.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Makes a mutating request safe to retry.
 *
 * <p>A client that does not know whether its request arrived — a timeout, a
 * dropped connection, an HTTP library retrying on its own — otherwise has to
 * choose between doing the thing twice and not doing it at all. Sending an
 * {@code Idempotency-Key} removes the choice.
 *
 * <h2>How</h2>
 *
 * <p>The key is claimed by <em>inserting</em> a row before the request runs,
 * in its own committed transaction. A concurrent retry hits the primary key,
 * fails to insert, and finds the claim. The database decides the race, which is
 * the only participant that can decide it correctly.
 *
 * <p>When the request finishes, its status and body are written onto that row.
 * A later retry with the same key replays them, marked
 * {@code Idempotency-Replayed: true}, without touching the handler.
 *
 * <h2>What is not recorded</h2>
 *
 * <p>Server errors release the claim instead of recording it. A {@code 500}
 * says nothing about whether a retry would fail too, and replaying one for
 * twenty-four hours would turn a bad minute into a bad day.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String key = request.getHeader(HEADER);
        UUID workspaceId = TenantContext.current();

        // No key, not a mutation, or nothing to scope the record to: pass
        // through untouched. Idempotency is opt-in, because only the client
        // knows whether two requests are meant to be the same one.
        if (key == null || key.isBlank()
                || !MUTATING.contains(request.getMethod())
                || workspaceId == null) {
            chain.doFilter(request, response);
            return;
        }

        if (key.length() > MAX_KEY_LENGTH) {
            reject(request, response, ErrorCode.INVALID_REQUEST,
                    HEADER + " must be at most " + MAX_KEY_LENGTH + " characters");
            return;
        }

        // The body has to be read to hash it, and read again by the handler, so
        // it is buffered. Only requests that actually carry a key pay for this.
        CachedBodyRequest cachedRequest = CachedBodyRequest.of(request);
        String requestHash = hash(cachedRequest);

        IdempotencyStore.Claim claim;
        try {
            claim = store.claim(workspaceId, key, requestHash);
        } catch (ApiException e) {
            reject(request, response, e.code(), e.getMessage());
            return;
        }

        switch (claim) {
            case IdempotencyStore.Claim.Replay replay -> replay(response, replay.response());

            case IdempotencyStore.Claim.InFlight ignored -> reject(request, response,
                    ErrorCode.ALREADY_EXISTS,
                    "a request with that Idempotency-Key is already in progress");

            case IdempotencyStore.Claim.Granted ignored ->
                    execute(cachedRequest, response, chain, workspaceId, key);
        }
    }

    private void execute(CachedBodyRequest request, HttpServletResponse response,
                         FilterChain chain, UUID workspaceId, String key)
            throws ServletException, IOException {

        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        boolean recorded = false;

        try {
            chain.doFilter(request, cachedResponse);

            int status = cachedResponse.getStatus();
            if (status < 500) {
                // 4xx is recorded as well as 2xx: a client error is deterministic,
                // so replaying it is the same answer the handler would give again.
                store.complete(workspaceId, key, status,
                        new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
                recorded = true;
            }
        } finally {
            if (!recorded) {
                // Server error, or the handler threw. Let the client genuinely
                // retry rather than pinning it to a failure for a day.
                store.release(workspaceId, key);
            }
            cachedResponse.copyBodyToResponse();
        }
    }

    private void replay(HttpServletResponse response, IdempotencyStore.Recorded recorded)
            throws IOException {

        response.setStatus(recorded.status());
        response.setHeader(REPLAYED_HEADER, "true");

        if (recorded.body() != null && !recorded.body().isEmpty()) {
            // Problem responses were stored with their status; the media type is
            // inferred from it rather than stored, so the replay matches.
            response.setContentType(recorded.status() >= 400
                    ? MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    : MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(recorded.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Method, path and body together.
     *
     * <p>All three, because the same key against a different endpoint is as much
     * a mistake as the same key with a different body — and both are far more
     * likely to be a key that was reused than a deliberate retry.
     */
    private String hash(CachedBodyRequest request) {
        byte[] body = request.body();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        ErrorCode code, String detail) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
