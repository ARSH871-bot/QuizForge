package com.quizforge.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.AbstractIntegrationTest;
import com.quizforge.identity.security.SessionAuthFilter;
import com.quizforge.notify.Mailer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password reset. The link is a credential, so most of what is worth proving
 * is what it refuses: a second use, an old link, an expired one, and silence
 * for an address with no account.
 */
@AutoConfigureMockMvc
class PasswordResetTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private Mailer mailer;

    private static final String OLD = "correct horse battery";
    private static final String NEW = "a much better passphrase";
    private static final Pattern LINK = Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)");

    @BeforeEach
    void resetMailer() {
        clearInvocations(mailer);
    }

    private String register() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", OLD, "displayName", "Ada"))))
                .andExpect(status().isCreated());
        return email;
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private void requestReset(String email) throws Exception {
        mvc.perform(post("/v1/auth/request-password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
    }

    /** The token from the most recent email sent to this address. */
    private String tokenFromLastMail(String email) {
        ArgumentCaptor<Mailer.OutgoingMail> sent = ArgumentCaptor.forClass(Mailer.OutgoingMail.class);
        verify(mailer, org.mockito.Mockito.atLeastOnce()).send(sent.capture());
        Mailer.OutgoingMail mail = sent.getAllValues().getLast();
        assertThat(mail.to()).isEqualTo(email);
        Matcher link = LINK.matcher(mail.text());
        assertThat(link.find()).as("the email carries a reset link").isTrue();
        return link.group(1);
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/v1/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "newPassword", password))));
    }

    @Test
    void theLinkSetsANewPasswordAndSignsOutEverywhere() throws Exception {
        String email = register();
        Cookie before = login(email, OLD).andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionAuthFilter.COOKIE_NAME);

        requestReset(email);
        reset(tokenFromLastMail(email), NEW).andExpect(status().isNoContent());

        mvc.perform(get("/v1/auth/me").cookie(before))
                .andExpect(status().isUnauthorized());
        login(email, OLD).andExpect(status().isUnauthorized());
        login(email, NEW).andExpect(status().isOk());
    }

    @Test
    void theLinkWorksOnce() throws Exception {
        String email = register();
        requestReset(email);
        String token = tokenFromLastMail(email);

        reset(token, NEW).andExpect(status().isNoContent());
        reset(token, "yet another passphrase")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        login(email, NEW).andExpect(status().isOk());
    }

    @Test
    void anAddressWithNoAccountIsAnsweredTheSameAndSentNothing() throws Exception {
        requestReset("nobody-" + UUID.randomUUID() + "@example.test");
        verify(mailer, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aSecondRequestWithinTheCooldownSendsNothing() throws Exception {
        String email = register();
        requestReset(email);
        requestReset(email);
        verify(mailer, times(1)).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aNewerLinkReplacesTheOlderOne() throws Exception {
        String email = register();
        requestReset(email);
        String first = tokenFromLastMail(email);

        // Step past the cooldown rather than waiting for it.
        jdbc.update("UPDATE password_reset_token SET created_at = now() - interval '5 minutes' "
                + "WHERE account_id = (SELECT id FROM account WHERE email = ?)", email);
        requestReset(email);
        String second = tokenFromLastMail(email);
        assertThat(second).isNotEqualTo(first);

        reset(first, NEW).andExpect(status().isBadRequest());
        reset(second, NEW).andExpect(status().isNoContent());
    }

    @Test
    void anExpiredLinkIsRefused() throws Exception {
        String email = register();
        requestReset(email);
        String token = tokenFromLastMail(email);

        jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute' "
                + "WHERE account_id = (SELECT id FROM account WHERE email = ?)", email);

        reset(token, NEW).andExpect(status().isBadRequest());
        login(email, OLD).andExpect(status().isOk());
    }

    @Test
    void aResetLiftsALockoutFromFailedSignIns() throws Exception {
        String email = register();
        for (int i = 0; i < 10; i++) {
            login(email, "wrong password " + i);
        }
        login(email, OLD).andExpect(status().isUnauthorized());

        requestReset(email);
        reset(tokenFromLastMail(email), NEW).andExpect(status().isNoContent());
        login(email, NEW).andExpect(status().isOk());
    }

    @Test
    void aShortPasswordIsRefusedAndTheLinkSurvives() throws Exception {
        String email = register();
        requestReset(email);
        String token = tokenFromLastMail(email);

        reset(token, "too short").andExpect(status().isBadRequest());
        reset(token, NEW).andExpect(status().isNoContent());
    }
}
