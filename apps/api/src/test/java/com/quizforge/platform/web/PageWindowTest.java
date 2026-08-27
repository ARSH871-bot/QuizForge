package com.quizforge.platform.web;

import com.quizforge.platform.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The paging primitives, without a database or an HTTP request in sight. */
class PageWindowTest {

    @Test
    void aSliceCannotBeMutatedAfterConstruction() {
        // This test is what justifies the SpotBugs exclusion for this package.
        var mutable = new ArrayList<>(List.of("a"));
        var slice = new PageWindow.Slice<>(mutable, null);

        mutable.add("smuggled in afterwards");

        assertThat(slice.data())
                .as("a slice must not change when the caller mutates the list it was given")
                .containsExactly("a");
        assertThatThrownBy(() -> slice.data().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aCursorRoundTrips() {
        UUID id = UUID.randomUUID();
        assertThat(Cursor.decode(Cursor.encode(id))).isEqualTo(id);
    }

    @Test
    void anAbsentCursorMeansTheFirstPage() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode("")).isNull();
        assertThat(Cursor.encode(null)).isNull();
    }

    @Test
    void aCursorIsOpaqueRatherThanTheIdentifierItself() {
        // If the encoding were the raw identifier, clients would learn to
        // construct cursors and the format could never change.
        UUID id = UUID.randomUUID();
        assertThat(Cursor.encode(id)).isNotEqualTo(id.toString());
    }

    @Test
    void anUnreadableCursorIsRefusedRatherThanIgnored() {
        // Never "start from the beginning": that would silently restart a
        // caller's pagination instead of saying something is wrong.
        for (String bad : new String[]{"not-base64!!", "Zm9v", "djI6bm9wZQ"}) {
            assertThatThrownBy(() -> Cursor.decode(bad))
                    .as("cursor %s", bad)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cursor");
        }
    }

    @Test
    void anOutOfRangeLimitIsRefused() {
        for (Integer bad : new Integer[]{0, -1, 101, 1000}) {
            assertThatThrownBy(() -> PageWindow.of(bad, null))
                    .as("limit %s", bad)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void aWindowOverFetchesByOneToDetectTheNextPage() {
        // Without the extra row you need a count query to know whether more
        // exists, and a count taken separately can already be stale.
        var window = PageWindow.of(10, null);
        assertThat(window.fetchSize().max()).isEqualTo(11);
    }

    @Test
    void theCursorNamesTheLastRowOfThePageThatWasReturned() {
        var window = PageWindow.of(2, null);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        // Three rows come back for a page of two: the third only signals more.
        var slice = window.slice(List.of(a, b, c), id -> id);

        assertThat(slice.data()).containsExactly(a, b);
        assertThat(Cursor.decode(slice.nextCursor()))
                .as("the cursor is the last row served, not the one peeked at")
                .isEqualTo(b);
    }

    @Test
    void aShortPageCarriesNoCursor() {
        var window = PageWindow.of(5, null);
        var slice = window.slice(List.of(UUID.randomUUID()), id -> id);

        assertThat(slice.nextCursor())
                .as("a caller must be able to stop without asking for an empty page")
                .isNull();
    }
}
