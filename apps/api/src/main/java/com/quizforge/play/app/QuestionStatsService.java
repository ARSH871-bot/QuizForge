package com.quizforge.play.app;

import com.quizforge.content.QuestionAccess;
import com.quizforge.tournament.TournamentAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * How each question in a tournament went.
 *
 * <p>One aggregate query over attempts, the questions they drew and the
 * responses given. It runs under the request's row-level security, so it can
 * only ever count the caller's own workspace.
 */
@Service
public class QuestionStatsService {

    /** One question's numbers, with what an organiser needs to recognise it. */
    public record QuestionStat(UUID questionId, String type, String prompt,
                               int shown, int answered, int correct) {

        /** Correct as a share of answered, or null when nobody answered. */
        public Double correctRate() {
            return answered == 0 ? null : (double) correct / answered;
        }
    }

    private final JdbcTemplate jdbc;
    private final QuestionAccess questions;
    private final TournamentAccess tournaments;

    public QuestionStatsService(JdbcTemplate jdbc, QuestionAccess questions,
                                TournamentAccess tournaments) {
        this.jdbc = jdbc;
        this.questions = questions;
        this.tournaments = tournaments;
    }

    @Transactional(readOnly = true)
    public List<QuestionStat> forTournament(UUID tournamentId) {
        tournaments.requireVisible(tournamentId);

        // Finished attempts only: one in progress would count its questions
        // as shown but unanswered, and make every question look skipped.
        record Counts(UUID questionId, int shown, int answered, int correct) {
        }
        List<Counts> counts = jdbc.query("""
                SELECT aq.question_id,
                       COUNT(*)                                AS shown,
                       COUNT(r.id)                             AS answered,
                       COUNT(r.id) FILTER (WHERE r.correct)    AS correct
                FROM attempt a
                JOIN attempt_question aq ON aq.attempt_id = a.id
                LEFT JOIN response r
                       ON r.attempt_id = aq.attempt_id AND r.question_id = aq.question_id
                WHERE a.tournament_id = ? AND a.state <> 'STARTED'
                GROUP BY aq.question_id
                """, (rs, n) -> new Counts(rs.getObject(1, UUID.class),
                rs.getInt(2), rs.getInt(3), rs.getInt(4)), tournamentId);

        var described = questions.describe(counts.stream().map(Counts::questionId).toList());

        return counts.stream()
                .filter(c -> described.containsKey(c.questionId()))
                .map(c -> {
                    var q = described.get(c.questionId());
                    return new QuestionStat(c.questionId(), q.type(), q.prompt(),
                            c.shown(), c.answered(), c.correct());
                })
                // Hardest first; a question nobody answered is the least informative, so last.
                .sorted(Comparator.comparing(QuestionStat::correctRate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(QuestionStat::answered, Comparator.reverseOrder()))
                .toList();
    }
}
