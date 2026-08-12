package com.quizforge.content.importer;

import com.quizforge.content.app.ContentHash;
import com.quizforge.content.app.QuestionService;
import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Imports questions from a simple CSV.
 *
 * <p>Columns: {@code type,prompt,options,correct,difficulty}. {@code options}
 * is pipe-separated and empty for NUMERIC and SHORT_TEXT. {@code correct} is
 * the option text, the numeric value, or a pipe-separated list of accepted
 * answers.
 *
 * <p>Each row is imported in its own transaction so one failure rolls back
 * only that row. This is deliberate: an author fixing a typo on line 300 should
 * not have to re-import the 299 rows that were fine.
 *
 * <p>That isolation comes from {@code importInto} deliberately <em>not</em>
 * being {@code @Transactional}: each call to the transactional
 * {@code QuestionService.author} therefore opens and commits its own. Adding
 * {@code @Transactional} here would silently join them into one and lose the
 * property.
 */
@Component
public class CsvImporter implements QuestionImporter {

    private static final int COLUMNS = 5;

    private final QuestionService questions;

    public CsvImporter(QuestionService questions) {
        this.questions = questions;
    }

    @Override
    public ImportReport importInto(UUID bankId, UUID actorId, String source) {
        if (source == null || source.isBlank()) {
            return ImportReport.empty();
        }

        String[] lines = source.split("\\R");
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<String> messages = new ArrayList<>();

        // Start at 1 to skip the header. Row numbers reported to the user are
        // 1-indexed by file line including the header, so they match what a
        // human sees in a spreadsheet.
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }

            int rowNumber = i + 1;
            try {
                boolean wasNew = importRow(bankId, actorId, line);
                if (wasNew) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (ApiException e) {
                failed++;
                messages.add("row " + rowNumber + ": " + e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                messages.add("row " + rowNumber + ": could not be read");
            }
        }

        return new ImportReport(imported, skipped, failed, messages);
    }

    /** Returns true when a question was created, false when it already existed. */
    private boolean importRow(UUID bankId, UUID actorId, String line) {
        String[] cells = split(line);

        QuestionType type = parseType(cells[0]);
        String prompt = cells[1].trim();
        List<String> options = pipeSeparated(cells[2]);
        List<String> correct = pipeSeparated(cells[3]);
        String difficulty = cells[4].isBlank() ? null : cells[4].trim().toUpperCase();

        if (correct.isEmpty()) {
            throw ApiException.invalid("no correct answer given");
        }

        Payload payload = buildPayload(type, options, correct);

        try {
            questions.author(bankId, actorId, type, prompt, payload, difficulty);
            return true;
        } catch (ApiException e) {
            if (e.code() == ErrorCode.ALREADY_EXISTS) {
                return false;   // a duplicate is skipped, not failed
            }
            throw e;
        }
    }

    private Payload buildPayload(QuestionType type, List<String> options, List<String> correct) {
        return switch (type) {
            case SINGLE_CHOICE, MULTI_CHOICE, TRUE_FALSE -> {
                List<String> normalisedCorrect = correct.stream()
                        .map(ContentHash::normalise).toList();

                for (String answer : correct) {
                    boolean present = options.stream()
                            .map(ContentHash::normalise)
                            .anyMatch(o -> o.equals(ContentHash.normalise(answer)));
                    if (!present) {
                        throw ApiException.invalid(
                                "correct answer '" + answer + "' is not among the options");
                    }
                }

                yield new ChoicePayload(options.stream()
                        .map(o -> new ChoicePayload.Option(
                                o, normalisedCorrect.contains(ContentHash.normalise(o))))
                        .toList());
            }
            case NUMERIC -> {
                try {
                    yield new NumericPayload(Double.parseDouble(correct.get(0).trim()), 0.0);
                } catch (NumberFormatException e) {
                    throw ApiException.invalid(
                            "'" + correct.get(0) + "' is not a number");
                }
            }
            case SHORT_TEXT -> new ShortTextPayload(correct, true);
        };
    }

    private QuestionType parseType(String value) {
        try {
            return QuestionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("unknown question type '" + value.trim() + "'");
        }
    }

    /**
     * Splits on commas outside double quotes, so a prompt containing a comma
     * can be quoted. Deliberately minimal rather than a full CSV parser: the
     * format is ours, documented, and a dependency for five columns is not
     * worth the supply-chain surface.
     */
    private String[] split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());

        while (cells.size() < COLUMNS) {
            cells.add("");
        }
        if (cells.size() > COLUMNS) {
            throw ApiException.invalid("expected " + COLUMNS + " columns, found " + cells.size());
        }
        return cells.toArray(new String[0]);
    }

    private List<String> pipeSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
