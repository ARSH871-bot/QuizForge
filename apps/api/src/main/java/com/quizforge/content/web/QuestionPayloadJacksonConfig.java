package com.quizforge.content.web;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.quizforge.api.model.ChoicePayload;
import com.quizforge.api.model.NumericPayload;
import com.quizforge.api.model.QuestionPayload;
import com.quizforge.api.model.ShortTextPayload;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Teaches Jackson how to read the {@code QuestionPayload} union.
 *
 * <p>This is hand-written rather than generated, for a reason worth recording.
 *
 * <p>The contract describes the union with {@code oneOf} plus a {@code const}
 * discriminator on each member, which is what makes
 * {@code openapi-typescript} emit {@code kind: "choice"} as a <em>literal</em>
 * type — the thing that lets a TypeScript client narrow the union
 * exhaustively, and the whole point of modelling it this way.
 *
 * <p>Adding OpenAPI's {@code discriminator} keyword as well would make
 * openapi-generator emit the Jackson annotations automatically, but it also
 * makes it declare {@code String getKind()} on the interface while each member
 * declares its own single-value enum — which does not compile. Removing the
 * keyword compiles and keeps the TypeScript narrowing, at the cost of the
 * Jackson annotations.
 *
 * <p>So the annotations are supplied here instead, through a mixin. Twenty
 * lines of configuration buys a union that is exhaustive for TypeScript clients
 * <em>and</em> deserialisable in Java, which neither generator setting achieved
 * on its own.
 *
 * <p>{@code visible = true} matters: without it Jackson consumes {@code kind}
 * while routing and the field arrives null on the model, so a payload that came
 * in as {@code choice} would go back out with no discriminator at all.
 */
@Configuration
public class QuestionPayloadJacksonConfig {

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "kind",
            visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ChoicePayload.class, name = "choice"),
            @JsonSubTypes.Type(value = NumericPayload.class, name = "numeric"),
            @JsonSubTypes.Type(value = ShortTextPayload.class, name = "shortText")
    })
    private interface QuestionPayloadMixin {
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer questionPayloadSubtypes() {
        return builder -> builder.mixIn(QuestionPayload.class, QuestionPayloadMixin.class);
    }
}
