package com.quizforge.content.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizforge.content.domain.payload.ChoicePayload;
import com.quizforge.content.domain.payload.NumericPayload;
import com.quizforge.content.domain.payload.Payload;
import com.quizforge.content.domain.payload.ShortTextPayload;
import com.quizforge.platform.error.ApiException;

public enum QuestionType {

    SINGLE_CHOICE(ChoicePayload.class),
    MULTI_CHOICE(ChoicePayload.class),
    TRUE_FALSE(ChoicePayload.class),
    NUMERIC(NumericPayload.class),
    SHORT_TEXT(ShortTextPayload.class);

    // Static because payload conversion happens inside entities and value
    // objects that Spring does not manage. FAIL_ON_UNKNOWN_PROPERTIES stays on
    // so a payload of the wrong shape is rejected rather than silently
    // deserialised with every field null.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Class<? extends Payload> payloadType;

    QuestionType(Class<? extends Payload> payloadType) {
        this.payloadType = payloadType;
    }

    public Payload parsePayload(String json) {
        try {
            return MAPPER.readValue(json, payloadType);
        } catch (Exception e) {
            throw ApiException.invalid("payload is not valid for a " + name() + " question");
        }
    }

    public String writePayload(Payload payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw ApiException.invalid("payload could not be serialised");
        }
    }
}
