package com.quizforge.content.web;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authoring, over HTTP.
 *
 * <p>The {@code content} module had no web layer at all until now: its services
 * have been complete since M2 and unreachable ever since, so a workspace could
 * be created but never filled.
 */
@AutoConfigureMockMvc
class ContentApiTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Author(Cookie cookie, String workspaceId) {
    }

    /** A signed-in account owning a workspace, entirely through the API. */
    private Author author() throws Exception {
        String email = "author-" + UUID.randomUUID() + "@example.test";

        mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Author"))))
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

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String bank(Author a, String name) throws Exception {
        return body(mvc.perform(post("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private static Map<String, Object> choice(String right, String wrong) {
        return Map.of("kind", "choice", "options", List.of(
                Map.of("text", right, "correct", true),
                Map.of("text", wrong, "correct", false)));
    }

    private JsonNode authorQuestion(Author a, String bankId, Map<String, Object> request)
            throws Exception {
        return body(mvc.perform(post("/v1/question-banks/" + bankId + "/questions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()));
    }

    // ----------------------------------------------------------------- banks

    @Test
    void aBankCanBeCreatedAndListed() throws Exception {
        var a = author();
        String id = bank(a, "Geography");

        assertThat(id).matches("^bnk_[0-9a-f]{32}$");

        mvc.perform(get("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Geography"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void anArchivedBankLeavesTheListingButStaysReadable() throws Exception {
        // Archived rather than deleted: a tournament that already drew from this
        // bank keeps working, and its results stay explicable.
        var a = author();
        String id = bank(a, "Geography");

        mvc.perform(delete("/v1/question-banks/" + id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(get("/v1/question-banks/" + id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void anotherWorkspacesBankIsNotFound() throws Exception {
        var mine = author();
        var stranger = author();
        String theirs = bank(stranger, "Theirs");

        mvc.perform(get("/v1/question-banks/" + theirs)
                        .cookie(mine.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, mine.workspaceId()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- questions

    @Test
    void aQuestionIsAuthoredAsVersionOne() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");

        var created = authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE",
                "prompt", "What is the capital of France?",
                "payload", choice("Paris", "Lyon"),
                "difficulty", "EASY"));

        assertThat(created.get("id").asText()).matches("^qst_[0-9a-f]{32}$");
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("lineageId").asText())
                .as("version 1 is its own lineage root")
                .isEqualTo(created.get("id").asText());
        assertThat(created.has("supersededBy")).isFalse();
        assertThat(created.get("payload").get("kind").asText()).isEqualTo("choice");
    }

    @Test
    void revisingInsertsANewVersionAndLeavesThePreviousOneByteIdentical() throws Exception {
        // The property the whole immutability rule exists for: a tournament pins
        // the exact version a player saw, so a revision published mid-tournament
        // cannot retroactively change a score.
        var a = author();
        String bankId = bank(a, "Geography");

        var v1 = authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE",
                "prompt", "What is the capital of France?",
                "payload", choice("Paris", "Lyon"),
                "difficulty", "EASY"));
        String v1Id = v1.get("id").asText();

        // Capture v1 exactly as the API renders it, before the revision.
        String v1Before = mvc.perform(get("/v1/questions/" + v1Id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var v2 = body(mvc.perform(patch("/v1/questions/" + v1Id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "prompt", "What is the capital city of France?",
                                "payload", choice("Paris", "Marseille"),
                                "difficulty", "MEDIUM"))))
                .andExpect(status().isOk()));

        String v2Id = v2.get("id").asText();
        assertThat(v2Id)
                .as("a revision is a new row, so it has a new identifier")
                .isNotEqualTo(v1Id);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("lineageId").asText())
                .as("the new version belongs to the same lineage")
                .isEqualTo(v1.get("lineageId").asText());

        String v1After = mvc.perform(get("/v1/questions/" + v1Id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Everything except the supersession pointer must be untouched. Compare
        // the rendered documents with that one field removed, so this asserts
        // byte-identity of the content rather than of a few sampled fields.
        var before = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(v1Before);
        var after = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(v1After);
        after.remove("supersededBy");

        assertThat(after)
                .as("revising must not alter the version it replaces")
                .isEqualTo(before);

        assertThat(json.readTree(v1After).get("supersededBy").asText())
                .as("the previous version points at its replacement")
                .isEqualTo(v2Id);
    }

    @Test
    void anAlreadySupersededVersionCannotBeRevised() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");
        String v1Id = authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                "payload", choice("Paris", "Lyon"))).get("id").asText();

        mvc.perform(patch("/v1/questions/" + v1Id)
                .cookie(a.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "prompt", "Capital city of France?",
                        "payload", choice("Paris", "Nice")))));

        mvc.perform(patch("/v1/questions/" + v1Id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "prompt", "Third go",
                                "payload", choice("Paris", "Nantes")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theVersionListingShowsTheWholeLineageWithOneCurrentVersion() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");
        String v1Id = authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                "payload", choice("Paris", "Lyon"))).get("id").asText();

        mvc.perform(patch("/v1/questions/" + v1Id)
                .cookie(a.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "prompt", "Capital city of France?",
                        "payload", choice("Paris", "Nice")))));

        // Any version's id reaches the lineage; they all share one.
        mvc.perform(get("/v1/questions/" + v1Id + "/versions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[1].version").value(2))
                .andExpect(jsonPath("$.data[0].supersededBy").exists())
                .andExpect(jsonPath("$.data[1].supersededBy").doesNotExist());
    }

    @Test
    void onlyCurrentVersionsAppearInTheBankListing() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");
        String v1Id = authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                "payload", choice("Paris", "Lyon"))).get("id").asText();

        mvc.perform(patch("/v1/questions/" + v1Id)
                .cookie(a.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "prompt", "Capital city of France?",
                        "payload", choice("Paris", "Nice")))));

        mvc.perform(get("/v1/question-banks/" + bankId + "/questions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value(2));
    }

    @Test
    void aDuplicateQuestionIsRefused() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");
        Map<String, Object> request = Map.of(
                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                "payload", choice("Paris", "Lyon"));

        authorQuestion(a, bankId, request);

        mvc.perform(post("/v1/question-banks/" + bankId + "/questions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));
    }

    // ---------------------------------------------------------- payload union

    @Test
    void allThreePayloadShapesRoundTripThroughTheDiscriminatedUnion() throws Exception {
        // Five question types, three payload shapes. Each shape must survive
        // deserialisation into the union and serialisation back out with its
        // discriminator intact, or a typed client cannot narrow on it.
        var a = author();
        String bankId = bank(a, "Geography");

        var single = authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                "payload", choice("Paris", "Lyon")));
        assertThat(single.get("payload").get("kind").asText()).isEqualTo("choice");
        assertThat(single.get("payload").get("options")).hasSize(2);

        var numeric = authorQuestion(a, bankId, Map.of(
                "type", "NUMERIC", "prompt", "Value of pi to two places?",
                "payload", Map.of("kind", "numeric", "value", 3.14, "tolerance", 0.01)));
        assertThat(numeric.get("payload").get("kind").asText()).isEqualTo("numeric");
        assertThat(numeric.get("payload").get("value").asDouble()).isEqualTo(3.14);
        assertThat(numeric.get("payload").get("tolerance").asDouble()).isEqualTo(0.01);

        var shortText = authorQuestion(a, bankId, Map.of(
                "type", "SHORT_TEXT", "prompt", "Name the capital of France.",
                "payload", Map.of("kind", "shortText",
                        "accepted", List.of("Paris"), "ignoreCase", true)));
        assertThat(shortText.get("payload").get("kind").asText()).isEqualTo("shortText");
        assertThat(shortText.get("payload").get("ignoreCase").asBoolean()).isTrue();

        var trueFalse = authorQuestion(a, bankId, Map.of(
                "type", "TRUE_FALSE", "prompt", "Paris is the capital of France.",
                "payload", choice("True", "False")));
        assertThat(trueFalse.get("payload").get("kind").asText())
                .as("TRUE_FALSE uses the choice shape, like SINGLE_CHOICE and MULTI_CHOICE")
                .isEqualTo("choice");
    }

    @Test
    void aPayloadOfTheWrongShapeForItsTypeIsRejected() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");

        mvc.perform(post("/v1/question-banks/" + bankId + "/questions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "type", "NUMERIC", "prompt", "Capital of France?",
                                "payload", choice("Paris", "Lyon")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aTrueFalseQuestionNeedsExactlyTwoOptions() throws Exception {
        // The per-type rules still apply even though three types share a shape.
        var a = author();
        String bankId = bank(a, "Geography");

        mvc.perform(post("/v1/question-banks/" + bankId + "/questions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "type", "TRUE_FALSE", "prompt", "Pick one.",
                                "payload", Map.of("kind", "choice", "options", List.of(
                                        Map.of("text", "True", "correct", true),
                                        Map.of("text", "False", "correct", false),
                                        Map.of("text", "Maybe", "correct", false)))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ----------------------------------------------------------------- import

    @Test
    void aCsvImportReportsPartialSuccessWithLineNumbersIntact() throws Exception {
        // Partial success is the normal case. An author fixing a typo on line 300
        // should not lose the 299 rows above it, and the response must point at
        // the offending row by number rather than in prose.
        var a = author();
        String bankId = bank(a, "Geography");

        String csv = """
                type,prompt,options,correct,difficulty
                SINGLE_CHOICE,Capital of France?,Paris|Lyon|Nice,Paris,EASY
                SINGLE_CHOICE,Broken row with one option,Paris,Paris,EASY
                SINGLE_CHOICE,Capital of Italy?,Rome|Milan,Rome,EASY
                """;

        var report = body(mvc.perform(post("/v1/question-banks/" + bankId + "/imports/csv")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType("text/csv")
                        .content(csv))
                .andExpect(status().isOk()));

        assertThat(report.get("imported").asInt())
                .as("good rows import even though a later row failed")
                .isEqualTo(2);
        assertThat(report.get("failed").asInt()).isEqualTo(1);

        var failures = report.get("failures");
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).get("line").asInt())
                .as("1-indexed including the header, so it matches a spreadsheet")
                .isEqualTo(3);
        assertThat(failures.get(0).get("message").asText()).isNotBlank();

        // And the good rows really are in the bank.
        mvc.perform(get("/v1/question-banks/" + bankId + "/questions")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void reImportingTheSameCsvSkipsRatherThanFails() throws Exception {
        // So retrying a partial upload is safe.
        var a = author();
        String bankId = bank(a, "Geography");

        String csv = """
                type,prompt,options,correct,difficulty
                SINGLE_CHOICE,Capital of France?,Paris|Lyon,Paris,EASY
                """;

        for (int attempt = 1; attempt <= 2; attempt++) {
            var report = body(mvc.perform(post("/v1/question-banks/" + bankId + "/imports/csv")
                            .cookie(a.cookie())
                            .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                            .with(csrf())
                            .contentType("text/csv")
                            .content(csv))
                    .andExpect(status().isOk()));

            assertThat(report.get("failed").asInt()).isZero();
            if (attempt == 1) {
                assertThat(report.get("imported").asInt()).isEqualTo(1);
            } else {
                assertThat(report.get("skipped").asInt())
                        .as("the second import recognises the row and skips it")
                        .isEqualTo(1);
                assertThat(report.get("imported").asInt()).isZero();
            }
        }
    }

    // ------------------------------------------------------------ authorization

    @Test
    void authoringRequiresAWorkspaceInScope() throws Exception {
        var a = author();

        mvc.perform(get("/v1/question-banks").cookie(a.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aQuestionIdOfTheWrongTypeIsABadRequest() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography");

        mvc.perform(get("/v1/questions/" + bankId)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBankIdentifierUsesItsOwnPrefix() throws Exception {
        var a = author();
        mvc.perform(post("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Prefixed " + UUID.randomUUID()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("^bnk_[0-9a-f]{32}$")));
    }

    @Test
    void aBankDescriptionIsStoredRatherThanSilentlyDiscarded() throws Exception {
        // Found by driving the API from a shell: the request carried a
        // description, the response was 201, and the value was gone. The column
        // and setter had existed since M2; the service simply never called it,
        // so a client got a success for data that was thrown away.
        var a = author();

        String id = body(mvc.perform(post("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Described " + UUID.randomUUID(),
                                "description", "Capitals, rivers and borders."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Capitals, rivers and borders.")))
                .get("id").asText();

        mvc.perform(get("/v1/question-banks/" + id)
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Capitals, rivers and borders."));
    }

    @Test
    void aDescriptionLongerThanTheColumnIsRejectedNotTruncated() throws Exception {
        // The contract caps this at 500 because the column is VARCHAR(500).
        // Without the cap a 501-character description reached the database and
        // failed there, turning a client mistake into a 500.
        var a = author();

        mvc.perform(post("/v1/question-banks")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Too wordy " + UUID.randomUUID(),
                                "description", "x".repeat(501)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ------------------------------------------------------------ API keys

    /**
     * The SDK holds an API key and nothing else, so a key that cannot read
     * content makes the whole content surface unreachable to an API client.
     * This was the state until the SDK tried to use it.
     */
    @Test
    void anApiKeyCanReadContentButNotAuthorIt() throws Exception {
        var a = author();
        String bankId = bank(a, "Geography " + UUID.randomUUID());
        authorQuestion(a, bankId, Map.of(
                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                "payload", choice("Paris", "Lyon")));

        String secret = body(mvc.perform(post("/v1/api-keys")
                        .cookie(a.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, a.workspaceId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "SDK"))))
                .andExpect(status().isCreated())).get("secret").asText();

        // Reads: the key names one workspace, which is all a read needs. No
        // workspace header - the key already says which one.
        mvc.perform(get("/v1/question-banks").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(bankId));

        mvc.perform(get("/v1/question-banks/" + bankId + "/questions")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].prompt").value("Capital of France?"));

        // Writes: a version records who authored it, and a key is a workspace
        // rather than a person. Refused until #98 answers the attribution.
        mvc.perform(post("/v1/question-banks")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "By key"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mvc.perform(post("/v1/question-banks/" + bankId + "/imports/csv")
                        .header("Authorization", "Bearer " + secret)
                        .contentType("text/csv")
                        .content("type,prompt,options,correct,difficulty\n"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }
}
