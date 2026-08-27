package com.quizforge.platform.web;

import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * One page request: a validated size and a decoded cursor.
 *
 * <p>Exists so that every list endpoint validates {@code limit} the same way and
 * fills {@code nextCursor} by the same rule. Nine endpoints doing this
 * individually is nine chances to differ, and pagination bugs are the kind that
 * look like data problems rather than code problems.
 */
public final class PageWindow {

    /** The largest page any endpoint will return. */
    public static final int MAX_LIMIT = 100;

    /** What a client gets when it does not ask. */
    public static final int DEFAULT_LIMIT = 25;

    private final int limit;
    private final UUID after;

    private PageWindow(int limit, UUID after) {
        this.limit = limit;
        this.after = after;
    }

    /**
     * Validates a request's paging parameters.
     *
     * <p>An out-of-range limit is rejected rather than clamped. Answering a
     * request for 1000 with 100 rows is indistinguishable from a collection that
     * only holds 100, so a client concludes it has everything when it has a
     * prefix — the same silent-wrong-answer this API refuses elsewhere.
     */
    public static PageWindow of(Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        return new PageWindow(size, Cursor.decode(cursor));
    }

    /** The identifier the client last saw, or {@code null} for the first page. */
    public UUID after() {
        return after;
    }

    /**
     * One more row than asked for.
     *
     * <p>Fetching {@code limit + 1} is how the presence of a next page is known
     * without a second count query — and a count would be wrong anyway, since
     * rows can arrive between the two queries.
     */
    public Limit fetchSize() {
        return Limit.of(limit + 1);
    }

    /**
     * Trims the over-fetched row and derives the cursor.
     *
     * @param rows      what the repository returned, at most {@code limit + 1}
     * @param identity  how to read a row's identifier, which becomes the cursor
     * @return the page's rows, and the cursor for the next page or {@code null}
     */
    public <T> Slice<T> slice(List<T> rows, Function<T, UUID> identity) {
        boolean hasMore = rows.size() > limit;
        List<T> page = hasMore ? rows.subList(0, limit) : rows;

        // Null when this is the last page, so a client stops rather than asking
        // for a page it already knows is empty.
        String next = hasMore ? Cursor.encode(identity.apply(page.get(page.size() - 1))) : null;
        return new Slice<>(List.copyOf(page), next);
    }

    /** A page of rows and the cursor that follows it. */
    public record Slice<T>(List<T> data, String nextCursor) {

        /**
         * Defensively copies, so a caller cannot mutate a page after it is
         * built. Belt and braces: {@link #slice} already copies, but a record
         * that only holds its guarantee when constructed one particular way
         * does not really hold it.
         */
        public Slice {
            data = data == null ? List.of() : List.copyOf(data);
        }
    }
}
