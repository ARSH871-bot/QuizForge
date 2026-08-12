package com.quizforge.content.app;

import com.quizforge.content.domain.QuestionType;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.domain.payload.ShortTextPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * De-duplication key for imported and authored questions.
 *
 * <p>Hashes normalised content rather than raw text, because the same question
 * arrives from OpenTDB with different HTML entity encodings, casing and
 * spacing. Answers are sorted so option order does not change the hash.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String of(QuestionType type, String prompt, Payload payload) {
        String answers = switch (payload) {
            case ChoicePayload c -> c.options().stream()
                    .map(o -> normalise(o.text()) + ":" + o.correct())
                    .sorted()
                    .collect(Collectors.joining("|"));
            case ShortTextPayload s -> s.accepted().stream()
                    .map(ContentHash::normalise)
                    .sorted()
                    .collect(Collectors.joining("|"));
            default -> String.valueOf(payload);
        };

        return sha256(type.name() + " " + normalise(prompt) + " " + answers);
    }

    /** NFKC, collapsed whitespace, trimmed, lowercased. Also used by graders. */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
