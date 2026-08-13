package com.quizforge.content.importer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The real Open Trivia Database client.
 *
 * <p>Requests {@code encode=base64} rather than {@code url3986}. The legacy
 * code used URL encoding and then decoded it again, which double-decodes any
 * answer containing a literal percent sign — base64 has no such ambiguity.
 *
 * <p>Rate limiting (response code 5) fails immediately with a clear message.
 * The legacy implementation slept six seconds per category on the request
 * thread, which turned a rate limit into a thirty-second stall.
 */
@Component
public class HttpOpenTdbClient implements OpenTdbClient {

    private static final String BASE_URL = "https://opentdb.com/api.php";

    private final RestClient rest;

    public HttpOpenTdbClient(RestClient.Builder builder) {
        this.rest = builder.build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(@JsonProperty("response_code") int responseCode, List<Result> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(String type, String difficulty, String question,
                  @JsonProperty("correct_answer") String correctAnswer,
                  @JsonProperty("incorrect_answers") List<String> incorrectAnswers) {
    }

    @Override
    public List<OpenTdbQuestion> fetch(Integer categoryId, String difficulty, int amount) {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("?amount=").append(Math.min(amount, 50))
                .append("&encode=base64");

        if (categoryId != null && categoryId > 0) {
            url.append("&category=").append(categoryId);
        }
        if (difficulty != null && !difficulty.isBlank() && !"any".equalsIgnoreCase(difficulty)) {
            url.append("&difficulty=").append(difficulty.toLowerCase());
        }

        Response response;
        try {
            response = rest.get().uri(url.toString()).retrieve().body(Response.class);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.INTERNAL,
                    "could not reach the Open Trivia Database", e);
        }

        if (response == null) {
            throw new ApiException(ErrorCode.INTERNAL, "no response from the Open Trivia Database");
        }

        switch (response.responseCode()) {
            case 0 -> { /* success */ }
            case 1 -> throw ApiException.invalid(
                    "the Open Trivia Database does not have that many questions for this query");
            case 2 -> throw ApiException.invalid("invalid parameters for the Open Trivia Database");
            case 5 -> throw new ApiException(ErrorCode.RATE_LIMITED,
                    "the Open Trivia Database is rate limiting; retry in a few seconds");
            default -> throw new ApiException(ErrorCode.INTERNAL,
                    "unexpected response code " + response.responseCode()
                            + " from the Open Trivia Database");
        }

        return response.results().stream()
                .map(r -> new OpenTdbQuestion(
                        decode(r.type()), decode(r.difficulty()), decode(r.question()),
                        decode(r.correctAnswer()),
                        r.incorrectAnswers().stream().map(HttpOpenTdbClient::decode).toList()))
                .toList();
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not base64 - return as-is rather than losing the question.
            return value;
        }
    }
}
