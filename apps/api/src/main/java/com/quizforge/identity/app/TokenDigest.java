package com.quizforge.identity.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and digests bearer credentials.
 *
 * <p>SHA-256 rather than a password hash is deliberate: these tokens carry 256
 * bits of entropy from a CSPRNG, so they are not brute-forceable and the
 * slowness of Argon2 would only add latency to every authenticated request.
 * Password hashing exists to compensate for low-entropy human input, which
 * does not apply here.
 */
final class TokenDigest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenDigest() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String digest(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
