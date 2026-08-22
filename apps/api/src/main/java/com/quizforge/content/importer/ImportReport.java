package com.quizforge.content.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of an import. Partial success is the normal case: one malformed
 * row must not discard the other four hundred, and the caller needs to know
 * precisely which rows failed and why.
 *
 * <p>{@code skipped} counts duplicates, which are not failures — re-importing
 * the same file is a no-op, not an error.
 */
public record ImportReport(int imported, int skipped, int failed,
                           List<ImportFailure> failures) {

    public ImportReport {
        failures = failures == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(failures));
    }

    public static ImportReport empty() {
        return new ImportReport(0, 0, 0, List.of());
    }
}
