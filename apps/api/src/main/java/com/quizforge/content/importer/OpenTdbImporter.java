package com.quizforge.content.importer;

import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imports questions from the Open Trivia Database.
 *
 * <p>Replaces the legacy {@code OpenTDBService}. Differences that matter:
 *
 * <ul>
 *   <li>Maps into the versioned content model rather than the legacy entity
 *   <li>De-duplicates through the same content hash as every other import, so
 *       re-importing a category after OpenTDB adds questions imports only the
 *       new ones instead of failing or duplicating
 *   <li>Fails fast with a clear message when rate limited, rather than
 *       sleeping for six seconds per category the way the legacy code did
 * </ul>
 *
 * <p>The {@code source} argument is {@code categoryId:difficulty:amount}, for
 * example {@code 9:easy:10}. Any part may be blank to mean "any".
 */
@Component
public class OpenTdbImporter implements QuestionImporter {

    private static final int MAX_AMOUNT = 50;

    private final OpenTdbClient client;
    private final QuestionService questions;

    public OpenTdbImporter(OpenTdbClient client, QuestionService questions) {
        this.client = client;
        this.questions = questions;
    }

    @Override
    public ImportReport importInto(UUID bankId, UUID actorId, String source) {
        Request request = parse(source);

        List<OpenTdbClient.OpenTdbQuestion> fetched =
                client.fetch(request.categoryId(), request.difficulty(), request.amount());

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<String> messages = new ArrayList<>();

        for (OpenTdbClient.OpenTdbQuestion source1 : fetched) {
            try {
                if (importOne(bankId, actorId, source1, request.difficulty())) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (ApiException e) {
                failed++;
                messages.add(truncate(source1.question()) + ": " + e.getMessage());
            }
        }

        return new ImportReport(imported, skipped, failed, messages);
    }

    /** Returns true when a question was created, false when it already existed. */
    private boolean importOne(UUID bankId, UUID actorId,
                              OpenTdbClient.OpenTdbQuestion source, String difficulty) {
        QuestionType type = mapType(source.type());

        List<ChoicePayload.Option> options = new ArrayList<>();
        options.add(new ChoicePayload.Option(source.correctAnswer(), true));
        for (String incorrect : source.incorrectAnswers()) {
            options.add(new ChoicePayload.Option(incorrect, false));
        }

        // Deliberately not shuffled here. Option order is presentation, and
        // shuffling at import would make the content hash unstable, breaking
        // de-duplication. The play module shuffles per attempt instead.
        ChoicePayload payload = new ChoicePayload(options);

        String resolvedDifficulty = difficulty != null && !difficulty.isBlank()
                ? difficulty.toUpperCase()
                : normaliseDifficulty(source.difficulty());

        try {
            questions.author(bankId, actorId, type, source.question(),
                    payload, resolvedDifficulty);
            return true;
        } catch (ApiException e) {
            if (e.code() == ErrorCode.ALREADY_EXISTS) {
                return false;
            }
            throw e;
        }
    }

    private QuestionType mapType(String openTdbType) {
        return switch (openTdbType == null ? "" : openTdbType.trim().toLowerCase()) {
            case "multiple" -> QuestionType.SINGLE_CHOICE;
            case "boolean" -> QuestionType.TRUE_FALSE;
            default -> throw ApiException.invalid(
                    "unsupported OpenTDB question type '" + openTdbType + "'");
        };
    }

    private String normaliseDifficulty(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase()) {
            case "easy" -> "EASY";
            case "medium" -> "MEDIUM";
            case "hard" -> "HARD";
            default -> null;
        };
    }

    private record Request(Integer categoryId, String difficulty, int amount) {
    }

    private Request parse(String source) {
        if (source == null || source.isBlank()) {
            return new Request(null, null, 10);
        }

        String[] parts = source.split(":", -1);
        Integer categoryId = null;
        String difficulty = null;
        int amount = 10;

        try {
            if (parts.length > 0 && !parts[0].isBlank()) {
                categoryId = Integer.parseInt(parts[0].trim());
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                difficulty = parts[1].trim().toLowerCase();
            }
            if (parts.length > 2 && !parts[2].isBlank()) {
                amount = Integer.parseInt(parts[2].trim());
            }
        } catch (NumberFormatException e) {
            throw ApiException.invalid(
                    "expected categoryId:difficulty:amount, for example 9:easy:10");
        }

        if (amount < 1 || amount > MAX_AMOUNT) {
            throw ApiException.invalid("amount must be between 1 and " + MAX_AMOUNT);
        }

        return new Request(categoryId, difficulty, amount);
    }

    private String truncate(String prompt) {
        if (prompt == null) {
            return "(no prompt)";
        }
        return prompt.length() <= 60 ? prompt : prompt.substring(0, 57) + "...";
    }
}
