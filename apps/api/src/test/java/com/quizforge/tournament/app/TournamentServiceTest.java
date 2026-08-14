package com.quizforge.tournament.app;

import com.quizforge.AbstractIntegrationTest;
import com.quizforge.content.app.QuestionBankService;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.identity.app.AccountService;
import com.quizforge.identity.app.WorkspaceService;
import com.quizforge.identity.domain.Role;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import com.quizforge.platform.tenancy.TenantContext;
import com.quizforge.tournament.ScoringPolicy;
import com.quizforge.tournament.domain.TournamentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TournamentServiceTest extends AbstractIntegrationTest {

    @Autowired private TournamentService tournaments;
    @Autowired private QuestionBankService banks;
    @Autowired private QuestionService questions;
    @Autowired private WorkspaceService workspaces;
    @Autowired private AccountService accounts;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID actor, UUID workspaceId, UUID bankId) {
    }

    /** A bank holding {@code count} distinct questions. */
    private Fixture fixture(int count) {
        UUID owner = accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Test User").getId();
        var workspace = workspaces.create(owner, "Acme " + UUID.randomUUID());
        TenantContext.set(workspace.getId());
        var bank = banks.create(workspace.getId(), owner, "Geography");

        for (int i = 0; i < count; i++) {
            questions.author(bank.getId(), owner, QuestionType.SINGLE_CHOICE,
                    "Question number " + i + "?",
                    new ChoicePayload(List.of(
                            new ChoicePayload.Option("right " + i, true),
                            new ChoicePayload.Option("wrong " + i, false))),
                    "EASY");
        }
        return new Fixture(owner, workspace.getId(), bank.getId());
    }

    private static TournamentDraft draft(UUID bankId, Instant opens, Instant closes, int count) {
        return new TournamentDraft("Spring Quiz", bankId, opens, closes, count,
                600, 1, ScoringPolicy.FIRST);
    }

    @Test
    void createsAScheduledTournament() {
        var f = fixture(5);
        Instant opens = Instant.now().plus(Duration.ofDays(1));

        var tournament = tournaments.create(f.workspaceId(), f.actor(),
                draft(f.bankId(), opens, opens.plus(Duration.ofDays(2)), 3));

        assertThat(tournament.getQuestionCount()).isEqualTo(3);
        assertThat(tournament.state(Instant.now())).isEqualTo(TournamentState.SCHEDULED);
    }

    @Test
    void stateIsDerivedFromTheClockRatherThanStored() {
        var f = fixture(5);
        Instant opens = Instant.now().minus(Duration.ofHours(1));
        Instant closes = Instant.now().plus(Duration.ofHours(1));

        var tournament = tournaments.create(f.workspaceId(), f.actor(),
                draft(f.bankId(), opens, closes, 3));

        // No scheduled job transitions these - the same row reports a
        // different state depending only on when you ask.
        assertThat(tournament.state(opens.minus(Duration.ofMinutes(1))))
                .isEqualTo(TournamentState.SCHEDULED);
        assertThat(tournament.state(Instant.now())).isEqualTo(TournamentState.OPEN);
        assertThat(tournament.state(closes.plus(Duration.ofMinutes(1))))
                .isEqualTo(TournamentState.CLOSED);
    }

    @Test
    void refusesToCloseBeforeItOpens() {
        var f = fixture(5);
        Instant opens = Instant.now().plus(Duration.ofDays(2));
        Instant closes = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> tournaments.create(f.workspaceId(), f.actor(),
                draft(f.bankId(), opens, closes, 3)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("close before it opens");
    }

    @Test
    void refusesMoreQuestionsThanTheBankHolds() {
        // Rejected at creation, not at play. A tournament that cannot be played
        // must never be schedulable - discovering it when the first player
        // arrives is far worse.
        var f = fixture(3);
        Instant opens = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> tournaments.create(f.workspaceId(), f.actor(),
                draft(f.bankId(), opens, opens.plus(Duration.ofDays(1)), 10)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only 3");
    }

    @Test
    void requiresTournamentManagementPermission() {
        var f = fixture(5);
        UUID viewer = accounts.register(
                "user-" + UUID.randomUUID() + "@example.test",
                "correct horse battery", "Viewer").getId();
        workspaces.addMember(f.workspaceId(), f.actor(), viewer, Role.VIEWER);

        Instant opens = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> tournaments.create(f.workspaceId(), viewer,
                draft(f.bankId(), opens, opens.plus(Duration.ofDays(1)), 3)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code())
                        .isEqualTo(ErrorCode.PERMISSION_DENIED));
    }

    @Test
    void rejectsANonPositiveQuestionCount() {
        var f = fixture(5);
        Instant opens = Instant.now().plus(Duration.ofDays(1));

        assertThatThrownBy(() -> tournaments.create(f.workspaceId(), f.actor(),
                draft(f.bankId(), opens, opens.plus(Duration.ofDays(1)), 0)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one question");
    }

    @Test
    void generatesAUniqueSlugWhenNamesCollide() {
        var f = fixture(5);
        Instant opens = Instant.now().plus(Duration.ofDays(1));
        Instant closes = opens.plus(Duration.ofDays(1));

        var first = tournaments.create(f.workspaceId(), f.actor(), draft(f.bankId(), opens, closes, 3));
        var second = tournaments.create(f.workspaceId(), f.actor(), draft(f.bankId(), opens, closes, 3));

        assertThat(first.getSlug()).isNotEqualTo(second.getSlug());
    }
}
