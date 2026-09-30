package com.quizforge.platform.error;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's error codes and the contract's must be the same set, with the
 * same statuses.
 *
 * <p>The breaking-change gate cannot catch this. With {@code ErrorCode}
 * extensible, a code the server emits but the contract never lists is not a
 * contract change at all - it is a server quietly sending something clients
 * were never told about. So it is checked here, from both directions.
 */
class ErrorCodeContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorCodeSchema() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("../../openapi.yaml"))) {
            Map<String, Object> contract = new Yaml().load(in);
            var components = (Map<String, Object>) contract.get("components");
            var schemas = (Map<String, Object>) components.get("schemas");
            return (Map<String, Object>) schemas.get("ErrorCode");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCodeTheServerCanSendIsInTheContractAndNothingElseIs() throws IOException {
        List<String> documented = (List<String>) errorCodeSchema().get("x-extensible-enum");
        List<String> emitted = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();

        assertThat(documented)
                .as("x-extensible-enum in openapi.yaml must list exactly the server's ErrorCode values")
                .containsExactlyInAnyOrderElementsOf(emitted);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCodeHasTheStatusTheContractPromises() throws IOException {
        var described = (Map<String, Map<String, Object>>) errorCodeSchema().get("x-error-codes");

        Map<String, Integer> promised = new TreeMap<>(described.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (Integer) e.getValue().get("status"))));
        Map<String, Integer> actual = new TreeMap<>(Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name, c -> c.status().value())));

        assertThat(actual).as("each code's HTTP status must match x-error-codes").isEqualTo(promised);
    }
}
