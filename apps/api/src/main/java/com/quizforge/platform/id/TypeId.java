package com.quizforge.platform.id;

import com.quizforge.platform.error.ApiException;
import com.quizforge.platform.error.ErrorCode;

import java.util.UUID;

/**
 * Renders internal UUIDs as prefixed, opaque identifiers at the API boundary
 * ({@code acc_018f…}). The prefix makes a bare identifier self-describing in a
 * log or a bug report, and parsing rejects an identifier of the wrong type
 * outright — passing a workspace id where an account id belongs becomes a 400
 * rather than a confusing 404.
 *
 * <p>Only the UUID is persisted. The rendered form never reaches the database.
 */
public final class TypeId {

    private TypeId() {
    }

    public static String render(String prefix, UUID id) {
        return prefix + "_" + id.toString().replace("-", "");
    }

    public static UUID parse(String expectedPrefix, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "identifier is required");
        }

        int separator = value.indexOf('_');
        if (separator < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "malformed identifier '" + value + "'");
        }

        String prefix = value.substring(0, separator);
        if (!expectedPrefix.equals(prefix)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "identifier '" + value + "' has prefix '" + prefix
                            + "' but expected prefix '" + expectedPrefix + "'");
        }

        String hex = value.substring(separator + 1);
        if (hex.length() != 32) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "malformed identifier '" + value + "'");
        }

        try {
            return UUID.fromString(
                    hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "malformed identifier '" + value + "'", e);
        }
    }
}
