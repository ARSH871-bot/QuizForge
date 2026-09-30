package com.quizforge.tournament.web;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guests: a name instead of an account. The properties worth proving are the
 * refusals — the limit holds, account-only tournaments stay account-only, a
 * name cannot be taken twice, and a guest cannot organise.
 */
@AutoConfigureMockMvc
class GuestJoinTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    private static final String PASSWORD = "correct horse battery";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Setup(Cookie organiser, String workspaceId, String tournamentId) {
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private Cookie account(String displayName) throws Exception {
        String email = "guest-test-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "displayName", displayName))))
                .andExpect(status().isCreated());
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);
    }

    private Setup tournament(boolean allowGuests) throws Exception {
        Cookie organiser = account("Ms Harper");
        String workspaceId = body(mvc.perform(post("/v1/workspaces").cookie(organiser).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Club " + UUID.randomUUID())))))
                .get("id").asText();
        String bankId = body(mvc.perform(post("/v1/question-banks").cookie(organiser).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Bank " + UUID.randomUUID())))))
                .get("id").asText();
        mvc.perform(post("/v1/question-banks/" + bankId + "/questions").cookie(organiser).with(csrf())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "type", "SINGLE_CHOICE", "prompt", "Capital of France?",
                                "payload", Map.of("kind", "choice", "options", List.of(
                                        Map.of("text", "Paris", "correct", true),
                                        Map.of("text", "Lyon", "correct", false)))))))
                .andExpect(status().isCreated());

        Map<String, Object> draft = new HashMap<>();
        draft.put("name", "Friday Quiz");
        draft.put("bankId", bankId);
        draft.put("opensAt", Instant.now().minus(1, ChronoUnit.MINUTES).toString());
        draft.put("closesAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        draft.put("questions", 1);
        draft.put("maxAttempts", 1);
        draft.put("allowGuests", allowGuests);
        String tournamentId = body(mvc.perform(post("/v1/tournaments").cookie(organiser).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(draft)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowGuests").value(allowGuests)))
                .get("id").asText();
        return new Setup(organiser, workspaceId, tournamentId);
    }

    private MvcResult joinAsGuest(String tournamentId, String nickname) throws Exception {
        return mvc.perform(post("/v1/join/" + tournamentId + "/guest").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", nickname))))
                .andReturn();
    }

    private Cookie guestCookie(MvcResult result) {
        return result.getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);
    }

    private ResultActions start(Cookie cookie, Setup s) throws Exception {
        return mvc.perform(post("/v1/tournaments/" + s.tournamentId() + "/attempts").cookie(cookie).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()));
    }

    @Test
    void aGuestPlaysWithJustANameAndIsMarkedOnTheBoard() throws Exception {
        var s = tournament(true);

        mvc.perform(get("/v1/join/" + s.tournamentId()))
                .andExpect(jsonPath("$.allowGuests").value(true));

        MvcResult joined = joinAsGuest(s.tournamentId(), "  Aroha   Ngata ");
        assertThat(joined.getResponse().getStatus()).isEqualTo(200);
        Cookie guest = guestCookie(joined);
        assertThat(guest).as("joining as a guest signs the guest in").isNotNull();

        mvc.perform(get("/v1/auth/me").cookie(guest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.displayName").value("Aroha Ngata"))
                .andExpect(jsonPath("$.email").doesNotExist());

        String attemptId = body(start(guest, s).andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post("/v1/attempts/" + attemptId + "/questions/1/answer").cookie(guest).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("answer", "Paris"))));
        mvc.perform(post("/v1/attempts/" + attemptId + "/submit").cookie(guest).with(csrf())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/tournaments/" + s.tournamentId() + "/standings").cookie(s.organiser())
                        .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()))
                .andExpect(jsonPath("$.data[0].displayName").value("Aroha Ngata"))
                .andExpect(jsonPath("$.data[0].guest").value(true));
    }

    @Test
    void theAttemptLimitHoldsForAGuest() throws Exception {
        var s = tournament(true);
        Cookie guest = guestCookie(joinAsGuest(s.tournamentId(), "Once"));

        String attemptId = body(start(guest, s).andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post("/v1/attempts/" + attemptId + "/submit").cookie(guest).with(csrf())
                .header(SessionAuthFilter.WORKSPACE_HEADER, s.workspaceId()));

        start(guest, s).andExpect(status().isBadRequest());
    }

    @Test
    void aTournamentThatNeedsAnAccountRefusesGuests() throws Exception {
        var s = tournament(false);

        MvcResult refused = joinAsGuest(s.tournamentId(), "Aroha");
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(guestCookie(refused)).as("no session for a refused guest").isNull();
    }

    @Test
    void aNameAlreadyInUseCannotBeTaken() throws Exception {
        var s = tournament(true);
        assertThat(joinAsGuest(s.tournamentId(), "Aroha").getResponse().getStatus()).isEqualTo(200);

        MvcResult sameName = joinAsGuest(s.tournamentId(), "AROHA");
        assertThat(sameName.getResponse().getStatus()).as("names are compared ignoring case").isEqualTo(409);

        MvcResult organiserName = joinAsGuest(s.tournamentId(), "ms harper");
        assertThat(organiserName.getResponse().getStatus())
                .as("a guest cannot pass as the organiser").isEqualTo(409);
    }

    @Test
    void aGuestCannotRunTournaments() throws Exception {
        var s = tournament(true);
        Cookie guest = guestCookie(joinAsGuest(s.tournamentId(), "Visitor"));

        mvc.perform(post("/v1/workspaces").cookie(guest).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Mine"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void aSignedInPlayerJoinsAsThemselves() throws Exception {
        var s = tournament(true);
        Cookie player = account("Ada");

        mvc.perform(post("/v1/join/" + s.tournamentId() + "/guest").cookie(player).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "Someone Else"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace.role").value("PLAYER"));

        mvc.perform(get("/v1/auth/me").cookie(player))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andExpect(jsonPath("$.guest").value(false));
    }

    @Test
    void newGuestsAreLimitedPerNetworkAddress() throws Exception {
        var s = tournament(true);

        // A class of thirty behind one address all get in.
        for (int i = 1; i <= 30; i++) {
            assertThat(joinAsGuest(s.tournamentId(), "Student " + i).getResponse().getStatus())
                    .as("guest %s from the same address", i).isEqualTo(200);
        }

        mvc.perform(post("/v1/join/" + s.tournamentId() + "/guest").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "Student 31"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
