package com.quizforge.platform.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.platform.id.TypeId;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retrying a mutating request safely.
 *
 * <p>The test that matters most is the concurrent one. A replay after the fact
 * is easy; two requests arriving at once is where a check-then-insert quietly
 * does the work twice, and the duplicate looks like a user mistake rather than
 * a bug.
 */
@AutoConfigureMockMvc
class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private IdempotencyStore store;
    @Autowired private IdempotencyPurge purge;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Actor(Cookie cookie, String workspaceId) {
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Actor actor() throws Exception {
        String email = "idem-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Idem"))))
                .andExpect(status().isCreated());

        Cookie cookie = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);

        String workspaceId = json.readTree(mvc.perform(post("/v1/workspaces")
                        .cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme " + UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        return new Actor(cookie, workspaceId);
    }

    /** Creates a bank, optionally with an idempotency key. */
    private MvcResult createBank(Actor a, String name, String key) throws Exception {
        var request = post("/v1/question-banks")
                .cookie(a.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name)));
        if (key != null) {
            request = request.header(IdempotencyFilter.HEADER, key);
        }
        return mvc.perform(request).andReturn();
    }

    private int bankCount(Actor a) throws Exception {
        return json.readTree(mvc.perform(get("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .param("limit", "100"))
                .andReturn().getResponse().getContentAsString()).get("data").size();
    }

    // ---------------------------------------------------------------- replay

    @Test
    void aRetryWithTheSameKeyReplaysTheOriginalResponse() throws Exception {
        var a = actor();
        String key = UUID.randomUUID().toString();
        String name = "Geography " + UUID.randomUUID();

        var first = createBank(a, name, key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader(IdempotencyFilter.REPLAYED_HEADER)).isNull();

        var second = createBank(a, name, key);

        assertThat(second.getResponse().getStatus())
                .as("the replay carries the original status, not a fresh 201")
                .isEqualTo(201);
        assertThat(second.getResponse().getHeader(IdempotencyFilter.REPLAYED_HEADER))
                .as("a client must be able to tell a replay from a first attempt")
                .isEqualTo("true");
        assertThat(body(second).get("id").asText())
                .as("the same bank, not a second one")
                .isEqualTo(body(first).get("id").asText());

        assertThat(bankCount(a))
                .as("the work happened exactly once")
                .isEqualTo(1);
    }

    @Test
    void withoutAKeyARetryDoesTheWorkTwice() throws Exception {
        // Idempotency is opt-in, and this is what opting out means. Asserted so
        // the default is a decision rather than an oversight.
        var a = actor();

        createBank(a, "First " + UUID.randomUUID(), null);
        createBank(a, "Second " + UUID.randomUUID(), null);

        assertThat(bankCount(a)).isEqualTo(2);
    }

    @Test
    void aRecordedClientErrorIsReplayedToo() throws Exception {
        // A 4xx is deterministic: the handler would say the same thing again.
        var a = actor();
        String name = "Duplicate " + UUID.randomUUID();
        createBank(a, name, null);

        String key = UUID.randomUUID().toString();
        var first = createBank(a, name, key);
        assertThat(first.getResponse().getStatus()).isEqualTo(409);

        var second = createBank(a, name, key);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getHeader(IdempotencyFilter.REPLAYED_HEADER))
                .isEqualTo("true");
    }

    // ------------------------------------------------------------- misuse

    @Test
    void theSameKeyWithADifferentRequestIsRefused() throws Exception {
        // Nearly always a key that was reused rather than regenerated. Replaying
        // the earlier response would answer a question nobody asked.
        var a = actor();
        String key = UUID.randomUUID().toString();

        assertThat(createBank(a, "One " + UUID.randomUUID(), key).getResponse().getStatus())
                .isEqualTo(201);

        var reused = createBank(a, "Two " + UUID.randomUUID(), key);

        assertThat(reused.getResponse().getStatus()).isEqualTo(422);
        assertThat(reused.getResponse().getContentAsString())
                .contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(bankCount(a))
                .as("the second request must not have created anything")
                .isEqualTo(1);
    }

    @Test
    void theSameKeyOnADifferentEndpointIsAlsoRefused() throws Exception {
        // The hash covers method and path, not just the body.
        var a = actor();
        String key = UUID.randomUUID().toString();

        createBank(a, "Bank " + UUID.randomUUID(), key);

        mvc.perform(post("/v1/api-keys")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .header(IdempotencyFilter.HEADER, key)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "CI"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void aKeyIsScopedToItsWorkspace() throws Exception {
        // Two workspaces choosing the same key must not collide.
        var mine = actor();
        var theirs = actor();
        String key = "shared-" + UUID.randomUUID();

        assertThat(createBank(mine, "Mine " + UUID.randomUUID(), key)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(createBank(theirs, "Theirs " + UUID.randomUUID(), key)
                .getResponse().getStatus())
                .as("the other workspace's key is not this workspace's key")
                .isEqualTo(201);
    }

    @Test
    void aKeyLongerThanTheLimitIsRefused() throws Exception {
        var a = actor();

        var result = createBank(a, "Bank", "x".repeat(256));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("INVALID_REQUEST");
    }

    @Test
    void readsIgnoreTheHeaderEntirely() throws Exception {
        // A GET is already safe to repeat, so a key there is meaningless rather
        // than an error - and it must not consume the key.
        var a = actor();
        String key = UUID.randomUUID().toString();

        mvc.perform(get("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .header(IdempotencyFilter.HEADER, key))
                .andExpect(status().isOk());

        TenantContext.set(TypeId.parse("wsp", a.workspaceId()));
        assertThat(store.countFor(TypeId.parse("wsp", a.workspaceId())))
                .as("a read must not record a key")
                .isZero();
    }

    // --------------------------------------------------------- concurrency

    @Test
    void twoConcurrentRequestsSharingAKeyProduceOneRow() throws Exception {
        // The reason the claim is an INSERT rather than a check-then-insert.
        //
        // Both threads start together on a barrier. Exactly one wins the primary
        // key; the other finds the claim and is answered without running the
        // handler - either replayed, if the first had finished, or refused as
        // in-flight if it had not. Either way the work happens once.
        var a = actor();
        String key = UUID.randomUUID().toString();
        String name = "Concurrent " + UUID.randomUUID();

        var barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<Integer> attempt = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return createBank(a, name, key).getResponse().getStatus();
        };

        try {
            List<Future<Integer>> futures = pool.invokeAll(List.of(attempt, attempt));
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());

            assertThat(statuses)
                    .as("one request does the work; the other is replayed (201) or "
                            + "refused as in-flight (409). Never two creations.")
                    .allMatch(s -> s == 201 || s == 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(bankCount(a))
                .as("exactly one bank, whatever the interleaving")
                .isEqualTo(1);

        TenantContext.set(TypeId.parse("wsp", a.workspaceId()));
        assertThat(store.countFor(TypeId.parse("wsp", a.workspaceId())))
                .as("exactly one idempotency row, not two")
                .isEqualTo(1);
    }

    // --------------------------------------------------------------- purge

    @Test
    void keysPastTheReplayWindowArePurged() throws Exception {
        var a = actor();
        String key = UUID.randomUUID().toString();
        createBank(a, "Ageing " + UUID.randomUUID(), key);

        UUID workspaceId = TypeId.parse("wsp", a.workspaceId());
        TenantContext.set(workspaceId);
        assertThat(store.countFor(workspaceId)).isEqualTo(1);
        TenantContext.clear();

        // Drag it past the window, the way the sweep test ages an attempt.
        jdbc.update("UPDATE idempotency_key SET created_at = now() - interval '25 hours' "
                + "WHERE workspace_id = ?", workspaceId);

        assertThat(purge.run())
                .as("the purge removes what the contract no longer honours")
                .isGreaterThanOrEqualTo(1);

        TenantContext.set(workspaceId);
        assertThat(store.countFor(workspaceId)).isZero();
    }

    @Test
    void aKeyInsideTheWindowSurvivesThePurge() throws Exception {
        var a = actor();
        createBank(a, "Fresh " + UUID.randomUUID(), UUID.randomUUID().toString());

        UUID workspaceId = TypeId.parse("wsp", a.workspaceId());
        purge.run();

        TenantContext.set(workspaceId);
        assertThat(store.countFor(workspaceId)).isEqualTo(1);
    }
}
