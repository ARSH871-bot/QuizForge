package com.quizforge.platform.id;

import com.quizforge.platform.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeIdTest {

    @Test
    void rendersWithThePrefixAndRoundTrips() {
        UUID id = UuidV7.generate();

        String rendered = TypeId.render("acc", id);

        assertThat(rendered).startsWith("acc_");
        assertThat(TypeId.parse("acc", rendered)).isEqualTo(id);
    }

    @Test
    void rejectsAnIdRenderedForADifferentType() {
        String workspaceId = TypeId.render("wsp", UuidV7.generate());

        assertThatThrownBy(() -> TypeId.parse("acc", workspaceId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expected prefix 'acc'");
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> TypeId.parse("acc", "acc_not-a-uuid"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TypeId.parse("acc", "no-underscore"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TypeId.parse("acc", null))
                .isInstanceOf(ApiException.class);
    }
}
