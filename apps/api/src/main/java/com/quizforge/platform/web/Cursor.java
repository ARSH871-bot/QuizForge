package com.quizforge.platform.web;

import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursors.
 *
 * <p>A cursor names the last row a client saw. The next page is everything
 * ordered after it — never "skip the first N", which is the property that makes
 * this correct under concurrent writes. An offset shifts when a row is inserted
 * above it, so a client paging through a growing collection sees an item twice
 * or misses one entirely, and neither shows up as an error.
 *
 * <h2>Why the key is an identifier and not a timestamp</h2>
 *
 * <p>Every identifier in this system is a UUIDv7, which embeds its creation
 * time in its leading bits and therefore <em>sorts chronologically</em> in the
 * byte order PostgreSQL compares {@code uuid} values in. So ordering by
 * primary key is ordering by creation time, and the key is unique by
 * definition.
 *
 * <p>That matters more than it sounds. A cursor over {@code (created_at, id)}
 * needs the pair precisely because a timestamp can tie; a cursor over a UUIDv7
 * primary key cannot tie, so it needs one column, one index, and no tuple
 * comparison. Same ordering, fewer ways to be wrong.
 *
 * <h2>Opaque means opaque</h2>
 *
 * <p>The encoding is base64url of {@code v1:<uuid>}. The version prefix exists
 * so the format can change without a client that stored a cursor getting a
 * confusing result — an old cursor will fail to parse and say so, rather than
 * being read as something it is not.
 *
 * <p>None of that is a contract. Clients are told to treat the value as opaque,
 * and a client that decodes one and finds a UUID has learned something it must
 * not rely on.
 */
public final class Cursor {

    private static final String VERSION = "v1:";

    private Cursor() {
    }

    /** Encodes the last row on a page, for the client to send back. */
    public static String encode(UUID lastSeen) {
        if (lastSeen == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (VERSION + lastSeen).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor a client sent back.
     *
     * <p>Anything unreadable is {@code 400 INVALID_CURSOR} — never a 500, and
     * never silently treated as "start from the beginning", which would quietly
     * restart a client's pagination instead of telling it something is wrong.
     *
     * <p>A cursor from a different collection decodes fine and simply yields
     * rows after that identifier, which is usually an empty page. That is
     * accepted rather than detected: the alternative is embedding the
     * collection in the cursor and rejecting mismatches, which turns an opaque
     * token into something with a checkable structure clients would learn to
     * construct.
     */
    public static UUID decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }

        if (!decoded.startsWith(VERSION)) {
            throw invalid();
        }

        try {
            return UUID.fromString(decoded.substring(VERSION.length()));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_CURSOR,
                "that cursor is not one this API issued; start again without one");
    }
}
