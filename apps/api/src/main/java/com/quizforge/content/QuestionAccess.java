package com.quizforge.content;

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

    /** Whether the bank exists and belongs to the given workspace. */
    boolean bankBelongsTo(UUID bankId, UUID workspaceId);
}
