package com.quizforge.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.app.AuditService;
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
 * The credential-issuing surface, end to end over HTTP.
 *
 * <p>Everything here goes through the API rather than the service layer,
 * because the gap this closes was never that the services did not work — they
 * have worked since M1 — it was that nothing exposed them.
 */
@AutoConfigureMockMvc
class WorkspaceApiTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AuditService audit;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Session(Cookie cookie, String email, String accountId, String csrfToken) {
    }

    /** Registers and signs in, without creating a workspace. */
    private Session signedIn() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.test";

        var registered = mvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", "Test User"))))
                .andExpect(status().isCreated())
                .andReturn();
        String accountId = json.readTree(registered.getResponse().getContentAsString())
                .get("id").asText();

        var login = mvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        // The CSRF token is issued on the login response, which is the first
        // thing any client does. Read from the raw header: the repository writes
        // it directly so it can set attributes MockMvc's cookie accessor drops.
        String csrf = login.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("XSRF-TOKEN="))
                .map(h -> h.substring("XSRF-TOKEN=".length(), h.indexOf(';')))
                .findFirst()
                .orElse(null);

        return new Session(login.getResponse().getCookie(SessionAuthFilter.COOKIE_NAME),
                email, accountId, csrf);
    }

    private String createWorkspace(Session session, String name) throws Exception {
        var created = mvc.perform(post("/v1/workspaces")
                        .cookie(session.cookie())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    // ------------------------------------------------------------ workspaces

    @Test
    void aNewAccountCanCreateItsFirstWorkspaceWithoutAlreadyHavingOne() throws Exception {
        // The bootstrap case. A fresh account has no workspace, so if this
        // required one in scope it could never obtain one, and the account would
        // be permanently unable to use the API.
        var session = signedIn();

        mvc.perform(post("/v1/workspaces")
                        .cookie(session.cookie())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme Training"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("^wsp_[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.name").value("Acme Training"))
                // Prefix, not equality: slugs are unique system-wide, so another
                // test creating this name first legitimately yields
                // "acme-training-2". Asserting equality would make this pass or
                // fail on test ordering.
                .andExpect(jsonPath("$.slug").value(matchesPattern("^acme-training(-[0-9]+)?$")))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void listingWorkspacesShowsOnlyYourOwn() throws Exception {
        var mine = signedIn();
        var stranger = signedIn();
        createWorkspace(mine, "Mine");
        createWorkspace(stranger, "Theirs");

        mvc.perform(get("/v1/workspaces").cookie(mine.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Mine"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void aSubPathOfWorkspacesStillRequiresAWorkspaceInScope() throws Exception {
        // /v1/workspaces is exempt from the workspace requirement by *exact*
        // path. If that exemption were a prefix match it would also exempt
        // everything below it, which is the cross-tenant hole the filter exists
        // to close.
        var session = signedIn();
        createWorkspace(session, "Acme");

        mvc.perform(get("/v1/workspaces/current").cookie(session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theCurrentWorkspaceReflectsTheSelectedOne() throws Exception {
        var session = signedIn();
        String workspaceId = createWorkspace(session, "Acme");

        mvc.perform(get("/v1/workspaces/current")
                        .cookie(session.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workspaceId))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void renamingChangesTheNameButNotTheSlug() throws Exception {
        // The slug was unique at creation and anything already referring to it
        // would break. A rename is cosmetic, not a re-identification.
        var session = signedIn();
        String workspaceId = createWorkspace(session, "Acme Training");

        // Read the slug rather than assuming it: slugs are made unique across
        // the whole system, so another test creating the same name first would
        // legitimately produce "acme-training-2". What matters is that renaming
        // does not change whatever it was.
        String slugBefore = json.readTree(mvc.perform(get("/v1/workspaces/current")
                        .cookie(session.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId))
                .andReturn().getResponse().getContentAsString()).get("slug").asText();

        mvc.perform(patch("/v1/workspaces/current")
                        .cookie(session.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Acme EMEA"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme EMEA"))
                .andExpect(jsonPath("$.slug").value(slugBefore));
    }

    // --------------------------------------------------------------- members

    @Test
    void aMemberCanBeAddedByEmailAndAppearsInTheList() throws Exception {
        var owner = signedIn();
        var invitee = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(post("/v1/members")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", invitee.email(), "role", "EDITOR"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(invitee.accountId()))
                .andExpect(jsonPath("$.role").value("EDITOR"));

        mvc.perform(get("/v1/members")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void addingAnUnregisteredAddressIsNotFound() throws Exception {
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(post("/v1/members")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", "nobody@example.invalid", "role", "VIEWER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theLastOwnerCannotBeDemoted() throws Exception {
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(patch("/v1/members/" + owner.accountId())
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theLastOwnerCannotBeRemoved() throws Exception {
        // A workspace with no owner can never issue a key, manage members or be
        // renamed again, and nothing in the API could undo it.
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(delete("/v1/members/" + owner.accountId())
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void anOwnerCanBeDemotedOnceThereIsAnother() throws Exception {
        var owner = signedIn();
        var second = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(post("/v1/members")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", second.email(), "role", "OWNER"))))
                .andExpect(status().isCreated());

        mvc.perform(patch("/v1/members/" + owner.accountId())
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void aViewerCannotManageMembers() throws Exception {
        var owner = signedIn();
        var viewer = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(post("/v1/members")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", viewer.email(), "role", "VIEWER"))))
                .andExpect(status().isCreated());

        mvc.perform(delete("/v1/members/" + owner.accountId())
                        .cookie(viewer.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    // -------------------------------------------------------------- api keys

    @Test
    void aKeySecretIsReturnedExactlyOnceAndNeverAgain() throws Exception {
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "CI pipeline"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("^key_[0-9a-f]{32}$")))
                .andReturn();

        String secret = json.readTree(created.getResponse().getContentAsString())
                .get("secret").asText();
        assertThat(secret).startsWith("qf_live_");

        // Every other response about this key must not contain it.
        String listed = mvc.perform(get("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listed)
                .as("the secret must never appear again, in any response")
                .doesNotContain(secret);
        assertThat(listed).contains("lastFour");
    }

    @Test
    void aTestKeyIsMintedWithTheTestPrefix() throws Exception {
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Sandbox", "environment", "test"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.environment").value("test"))
                .andReturn();

        assertThat(json.readTree(created.getResponse().getContentAsString())
                .get("secret").asText()).startsWith("qf_test_");
    }

    @Test
    void aMintedKeyAuthenticatesAndStopsTheRequestAfterItIsRevoked() throws Exception {
        // The property the whole task exists for: a key obtained over the API
        // actually works, and revocation takes effect on the very next request.
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "CI pipeline"))))
                .andExpect(status().isCreated())
                .andReturn();

        var body = json.readTree(created.getResponse().getContentAsString());
        String secret = body.get("secret").asText();
        String keyId = body.get("id").asText();

        // The key authenticates on its own, with no cookie and no header - the
        // workspace comes from the key itself.
        mvc.perform(get("/v1/tournaments").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk());

        mvc.perform(delete("/v1/api-keys/" + keyId)
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/tournaments").header("Authorization", "Bearer " + secret))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void anApiKeyCannotMintAnotherApiKey() throws Exception {
        // Otherwise a leaked credential could extend its own foothold and
        // outlive the revocation of the key that created it.
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "CI pipeline"))))
                .andReturn();
        String secret = json.readTree(created.getResponse().getContentAsString())
                .get("secret").asText();

        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Second key"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void aKeyBelongingToAnotherWorkspaceIsNotFound() throws Exception {
        var owner = signedIn();
        var stranger = signedIn();
        String ourWorkspace = createWorkspace(owner, "Ours");
        String theirWorkspace = createWorkspace(stranger, "Theirs");

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(stranger.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, theirWorkspace)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Theirs"))))
                .andReturn();
        String theirKeyId = json.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        // 404 rather than 403: its existence elsewhere is not disclosed.
        mvc.perform(delete("/v1/api-keys/" + theirKeyId)
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, ourWorkspace)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------------- audit

    @Test
    void everyPrivilegedWriteIsAudited() throws Exception {
        // Until this task the audit log had no writers at all: AuditService was
        // complete and tested, and nothing called it. A tested mechanism with no
        // callers records nothing.
        var owner = signedIn();
        var invitee = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        mvc.perform(post("/v1/members")
                .cookie(owner.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "email", invitee.email(), "role", "EDITOR"))));

        mvc.perform(patch("/v1/members/" + invitee.accountId())
                .cookie(owner.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "VIEWER"))));

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "CI"))))
                .andReturn();
        String keyId = json.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        mvc.perform(delete("/v1/api-keys/" + keyId)
                .cookie(owner.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .with(csrf()));

        mvc.perform(patch("/v1/workspaces/current")
                .cookie(owner.cookie())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Acme EMEA"))));

        TenantContext.set(com.quizforge.platform.id.TypeId.parse("wsp", workspaceId));
        List<String> actions = audit.recentFor(
                        com.quizforge.platform.id.TypeId.parse("wsp", workspaceId)).stream()
                .map(com.quizforge.identity.domain.AuditEvent::getAction)
                .toList();

        assertThat(actions).contains(
                "workspace.created", "member.added", "member.role_changed",
                "api_key.created", "api_key.revoked", "workspace.renamed");
    }

    @Test
    void theAuditLogNeverRecordsAKeySecret() throws Exception {
        // An audit log is read by more people than the table it describes.
        var owner = signedIn();
        String workspaceId = createWorkspace(owner, "Acme");

        var created = mvc.perform(post("/v1/api-keys")
                        .cookie(owner.cookie())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "CI"))))
                .andReturn();
        String secret = json.readTree(created.getResponse().getContentAsString())
                .get("secret").asText();

        TenantContext.set(com.quizforge.platform.id.TypeId.parse("wsp", workspaceId));
        String recorded = audit.recentFor(
                        com.quizforge.platform.id.TypeId.parse("wsp", workspaceId)).stream()
                .map(com.quizforge.identity.domain.AuditEvent::getDetail)
                .reduce("", String::concat);

        assertThat(recorded).doesNotContain(secret);
    }

    @Test
    void aCookieAuthenticatedWriteWithoutTheTokenIsRefused() throws Exception {
        // The other half: the protection still protects.
        var session = signedIn();

        mvc.perform(post("/v1/workspaces")
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "No token"))))
                .andExpect(status().is4xxClientError());
    }
}
