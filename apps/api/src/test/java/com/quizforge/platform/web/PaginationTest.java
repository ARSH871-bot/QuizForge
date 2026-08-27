package com.quizforge.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.platform.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Keyset pagination, end to end.
 *
 * <p>The tests that matter here are the ones about concurrent writes. Paging is
 * easy to make look right on a static collection and easy to get wrong on a
 * moving one, and the wrongness never announces itself — a client silently sees
 * an item twice, or never sees one at all.
 */
@AutoConfigureMockMvc
class PaginationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Author(Cookie cookie, String workspaceId) {
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private Author author() throws Exception {
        String email = "page-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Pager"))))
                .andExpect(status().isCreated());

        Cookie cookie = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);

        String workspaceId = body(mvc.perform(post("/v1/workspaces")
                        .cookie(cookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme " + UUID.randomUUID()))))
                .andExpect(status().isCreated())).get("id").asText();

        return new Author(cookie, workspaceId);
    }

    private String createBank(Author a, String name) throws Exception {
        return body(mvc.perform(post("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private JsonNode page(Author a, String cursor, int limit) throws Exception {
        var request = get("/v1/question-banks")
                .cookie(a.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                .param("limit", String.valueOf(limit));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    /**
     * The next cursor, or {@code null} when there is none.
     *
     * <p>An exhausted page omits the field rather than serialising it as
     * {@code null} — Jackson drops nulls — so "absent" and "null" both mean
     * stop.
     */
    private static String nextCursor(JsonNode page) {
        JsonNode node = page.get("nextCursor");
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("data").forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    // -------------------------------------------------------------- the basics

    @Test
    void aPageStopsWhenThereIsNoNextCursor() throws Exception {
        var a = author();
        for (int i = 0; i < 3; i++) {
            createBank(a, "Bank " + i);
        }

        var first = page(a, null, 2);
        assertThat(ids(first)).hasSize(2);
        assertThat(nextCursor(first))
                .as("a full page with more behind it must carry a cursor")
                .isNotNull();

        var second = page(a, nextCursor(first), 2);
        assertThat(ids(second)).hasSize(1);
        assertThat(nextCursor(second))
                .as("the last page must not carry a cursor, so a client stops")
                .isNull();
    }

    @Test
    void everyItemIsSeenExactlyOnceAcrossPages() throws Exception {
        var a = author();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(createBank(a, "Bank " + i));
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var page = page(a, cursor, 2);
            seen.addAll(ids(page));
            cursor = nextCursor(page);
        } while (cursor != null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void pagesAreOrderedNewestFirst() throws Exception {
        var a = author();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add(createBank(a, "Bank " + i));
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var page = page(a, cursor, 2);
            seen.addAll(ids(page));
            cursor = nextCursor(page);
        } while (cursor != null);

        List<String> newestFirst = new ArrayList<>(created);
        java.util.Collections.reverse(newestFirst);
        assertThat(seen).containsExactlyElementsOf(newestFirst);
    }

    // ------------------------------------------------- the reason for keysets

    @Test
    void aRowInsertedMidPaginationNeitherDuplicatesNorHidesAnother() throws Exception {
        // The property offset pagination fails.
        //
        // With OFFSET, inserting a row above the window shifts everything down,
        // so the second page repeats an item the first page already showed. With
        // a keyset the cursor names a specific row, and "everything after that
        // row" does not move when something is inserted elsewhere.
        //
        // Ordering is newest-first, so a new row lands at the very top - the
        // worst case for offsets, and a no-op for cursors.
        var a = author();
        List<String> original = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            original.add(createBank(a, "Original " + i));
        }

        var first = page(a, null, 3);
        List<String> seen = new ArrayList<>(ids(first));

        // Three more arrive between the two requests.
        for (int i = 0; i < 3; i++) {
            createBank(a, "Inserted " + i);
        }

        String cursor = nextCursor(first);
        do {
            var next = page(a, cursor, 3);
            seen.addAll(ids(next));
            cursor = nextCursor(next);
        } while (cursor != null);

        assertThat(seen)
                .as("no row may be served twice, whatever arrived mid-pagination")
                .doesNotHaveDuplicates();

        assertThat(seen)
                .as("every row that existed when paging began must still be reached")
                .containsAll(original);
    }

    @Test
    void aRowDeletedMidPaginationDoesNotBreakTheCursor() throws Exception {
        // The cursor names a row that may since have gone. "Everything after
        // this identifier" is still answerable, because the comparison is
        // against the value rather than against the row's position.
        var a = author();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createBank(a, "Bank " + i));
        }

        var first = page(a, null, 2);
        String cursor = nextCursor(first);

        // Archive the very row the cursor names.
        String cursorRow = ids(first).get(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/question-banks/" + cursorRow)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        var second = page(a, cursor, 2);
        assertThat(ids(second))
                .as("paging continues from a cursor whose row is gone")
                .isNotEmpty()
                .doesNotContain(cursorRow);
    }

    // ------------------------------------------------------------- bad input

    @Test
    void aCursorThisApiDidNotIssueIsRejected() throws Exception {
        var a = author();
        createBank(a, "Bank");

        for (String bad : new String[]{"not-base64!!", "Zm9v", "", "djI6bm9wZQ"}) {
            var request = get("/v1/question-banks")
                    .cookie(a.cookie())
                    .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId());
            if (!bad.isEmpty()) {
                request = request.param("cursor", bad);
            }

            var result = mvc.perform(request).andReturn();
            if (bad.isEmpty()) {
                // An empty cursor is "no cursor", not a malformed one.
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
            } else {
                assertThat(result.getResponse().getStatus())
                        .as("cursor %s must be refused, not 500", bad)
                        .isEqualTo(400);
                assertThat(result.getResponse().getContentAsString())
                        .contains("INVALID_CURSOR");
            }
        }
    }

    @Test
    void anOutOfRangeLimitIsRejectedRatherThanClamped() throws Exception {
        var a = author();
        createBank(a, "Bank");

        for (String bad : new String[]{"0", "-1", "101", "1000"}) {
            mvc.perform(get("/v1/question-banks")
                            .cookie(a.cookie())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                            .param("limit", bad))
                    .andExpect(status().isBadRequest());
        }

        for (String ok : new String[]{"1", "100"}) {
            mvc.perform(get("/v1/question-banks")
                            .cookie(a.cookie())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                            .param("limit", ok))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void omittingLimitUsesTheDocumentedDefault() throws Exception {
        var a = author();
        for (int i = 0; i < 3; i++) {
            createBank(a, "Bank " + i);
        }

        mvc.perform(get("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    // --------------------------------------------------------- other surfaces

    @Test
    void everyPagedCollectionCarriesTheSameEnvelope() throws Exception {
        // One scheme across the API. A client that learns it once should not
        // meet a second one three endpoints later.
        var a = author();
        String bankId = createBank(a, "Geo");

        for (String path : new String[]{
                "/v1/workspaces",
                "/v1/members",
                "/v1/api-keys",
                "/v1/question-banks",
                "/v1/question-banks/" + bankId + "/questions"}) {

            mvc.perform(get(path)
                            .cookie(a.cookie())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Test
    void aCursorFromOneCollectionSimplyYieldsNothingInAnother() throws Exception {
        // Cursors are opaque and not collection-scoped, so this is accepted
        // rather than detected. Documented, and asserted so the behaviour is
        // deliberate rather than incidental.
        var a = author();
        for (int i = 0; i < 3; i++) {
            createBank(a, "Bank " + i);
        }

        String bankCursor = nextCursor(page(a, null, 1));

        mvc.perform(get("/v1/tournaments")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .param("cursor", bankCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
