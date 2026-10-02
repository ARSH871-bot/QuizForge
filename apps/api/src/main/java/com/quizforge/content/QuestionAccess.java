package com.quizforge.content;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The content module's published API.
 *
 * <p>Other modules ask about questions through this interface rather than
 * reaching into {@code content.app} or {@code content.domain}. Spring Modulith
 * exposes only a module's root package, and widening
 * {@code allowedDependencies} to reach the internals would defeat the
 * boundary rather than respect it.
 *
 * <p>Kept deliberately narrow. It grows as consumers need it — M3's play
 * module adds question retrieval and grading here — rather than exposing the
 * whole of content up front.
 */
public interface QuestionAccess {

    /**
     * How many current, non-retired questions the bank holds.
     *
     * <p>Used when scheduling a tournament, so one that could never be played
     * is rejected at creation rather than discovered by the first player.
     */
    int currentQuestionCount(UUID bankId);

    /** What an organiser needs to recognise a question in a report. Never the answer. */
    record QuestionSummary(String type, String prompt) {
    }

    /** Summaries by question id. Questions not visible in the caller's workspace are absent. */
    Map<UUID, QuestionSummary> describe(Collection<UUID> questionIds);

    /** Whether the bank exists and belongs to the given workspace. */
    boolean bankBelongsTo(UUID bankId, UUID workspaceId);

    /**
     * A question as a player may see it.
     *
     * <p>Carries no correct answer by construction. This record crosses the
     * network during play, and a type that cannot represent the answer cannot
     * leak it — which is a stronger guarantee than remembering to strip it.
     */
    record PlayableQuestion(UUID questionId, String type, String prompt, List<String> options) {

        /** Defensively copies, so the option list cannot be mutated after construction. */
        public PlayableQuestion {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * Picks {@code count} current questions from the bank, deterministically
     * for a given seed.
     *
     * <p>Seeded rather than random so an attempt can be reconstructed exactly,
     * while two players still receive different questions.
     */
    List<UUID> selectQuestions(UUID bankId, int count, long seed);

    /** Renders a question for play, with options in the given order. */
    PlayableQuestion playable(UUID questionId, List<Integer> optionOrder);

    /** The canonical number of options, so a caller can build a shuffle order. */
    int optionCount(UUID questionId);

    /**
     * Grades one answer server-side. The correct answer never leaves this
     * module.
     */
    boolean grade(UUID questionId, String given);
}
