package com.quizforge.content.importer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportReportTest {

    @Test
    void isImmutableAfterConstruction() {
        var mutable = new ArrayList<>(List.of("row 2: bad"));
        var report = new ImportReport(0, 0, 1, mutable);

        mutable.add("smuggled in afterwards");

        assertThat(report.messages())
                .as("a report must not change after the caller mutates the list it was given")
                .hasSize(1);
        assertThatThrownBy(() -> report.messages().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toleratesNullMessages() {
        assertThat(new ImportReport(1, 0, 0, null).messages()).isEmpty();
    }
}
