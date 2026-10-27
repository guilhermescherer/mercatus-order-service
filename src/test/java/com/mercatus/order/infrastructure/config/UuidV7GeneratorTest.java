package com.mercatus.order.infrastructure.config;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.mercatus.order.support.OrderFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    @Test
    void generatesUniqueRfc9562IdentifiersUsingInjectedClock() {
        var generator = new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC));
        var ids = new HashSet<UUID>();

        for (int i = 0; i < 10000; i++) {
            var id = generator.get();

            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
            ids.add(id);
        }

        assertThat(ids).hasSize(10000);
    }
}
