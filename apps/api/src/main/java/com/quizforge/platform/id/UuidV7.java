package com.quizforge.platform.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 UUIDs: 48 bits of Unix milliseconds followed by
 * random bits. Time-ordered, so they cluster well in a B-tree index instead of
 * scattering writes the way UUIDv4 does.
 *
 * <p>Java 21 has no built-in v7 generator, and the implementation is small
 * enough that a dependency is not worth the supply-chain surface.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long most = (timestamp & 0xFFFFFFFFFFFFL) << 16;
        most |= 0x7000L;                                  // version 7
        most |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL);

        long least = 0;
        for (int i = 2; i < 10; i++) {
            least = (least << 8) | (random[i] & 0xFFL);
        }
        least &= 0x3FFFFFFFFFFFFFFFL;
        least |= 0x8000000000000000L;                     // variant 2 (RFC 4122)

        return new UUID(most, least);
    }
}
