package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class NodeDeviceStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:00:00Z");

    @Test
    void normalizesAndDeduplicatesACompleteNodeSnapshot() {
        Fixture fixture = fixture();

        fixture.service().replaceSnapshot(9, Map.of(
            101L, List.of(
                "198.51.100.1:443", "198.51.100.1", "[2001:db8::1]:8443",
                "not-an-ip"
            )
        ), NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(fixture.hashes()).putAll(eq("node:devices:user:101"), fields.capture());
        assertThat(fields.getValue()).containsExactly(
            Map.entry("9:198.51.100.1", NOW.getEpochSecond() + ""),
            Map.entry("9:2001:db8::1", NOW.getEpochSecond() + "")
        );
        verify(fixture.redis()).expire("node:devices:user:101", Duration.ofMinutes(5));
        verify(fixture.sets()).add("node:devices:index:9", "101");
    }

    @Test
    void aggregatesRecentAddressesAcrossNodesPerUser() {
        Fixture fixture = fixture();
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put("9:198.51.100.1", Long.toString(NOW.getEpochSecond()));
        values.put("10:198.51.100.1", Long.toString(NOW.getEpochSecond()));
        values.put("10:2001:db8::1", Long.toString(NOW.getEpochSecond()));
        values.put("11:203.0.113.1", Long.toString(NOW.minusSeconds(301).getEpochSecond()));
        when(fixture.hashes().entries("node:devices:user:101")).thenReturn(values);

        assertThat(fixture.service().snapshotForUsers(Set.of(101L), NOW))
            .containsEntry(101L, List.of("198.51.100.1", "2001:db8::1"));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members("node:devices:index:9")).thenReturn(Set.of());
        when(hashes.keys("node:devices:user:101")).thenReturn(Set.of());
        return new Fixture(new NodeDeviceStateService(redis), redis, hashes, sets);
    }

    private record Fixture(
        NodeDeviceStateService service,
        StringRedisTemplate redis,
        HashOperations<String, Object, Object> hashes,
        SetOperations<String, String> sets
    ) {
    }
}
