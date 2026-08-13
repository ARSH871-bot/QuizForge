package com.quizforge.content.importer;

import java.util.List;

/**
 * Fetches questions from the Open Trivia Database.
 *
 * <p>An interface rather than a concrete class so the mapping in
 * {@link OpenTdbImporter} can be tested against fixtures with no network call.
 * The test suite must stay offline: a test that depends on a third-party API
 * fails for reasons that have nothing to do with the change being tested.
 */
public interface OpenTdbClient {

    /** One question as OpenTDB returns it, already base64-decoded. */
    record OpenTdbQuestion(String type, String difficulty, String question,
                           String correctAnswer, List<String> incorrectAnswers) {
    }

    /**
     * @param categoryId OpenTDB category id, or null for any
     * @param difficulty easy, medium, hard, or null for any
     * @param amount     how many to request, capped at 50 by OpenTDB
     * @throws com.quizforge.platform.error.ApiException when OpenTDB refuses
     *         the request or is rate limiting
     */
    List<OpenTdbQuestion> fetch(Integer categoryId, String difficulty, int amount);
}
