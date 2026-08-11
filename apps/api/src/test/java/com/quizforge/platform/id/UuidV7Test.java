package com.quizforge.platform.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void generatesVersion7Uuids() {
        UUID id = UuidV7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void generatesIdsThatSortInCreationOrder() throws Exception {
        UUID first = UuidV7.generate();
        Thread.sleep(2);
        UUID second = UuidV7.generate();

        assertThat(first.toString()).isLessThan(second.toString());
    }

    @Test
    void generatesDistinctIdsWithinTheSameMillisecond() {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate());
        }
        assertThat(ids).hasSize(10_000);
    }
}
