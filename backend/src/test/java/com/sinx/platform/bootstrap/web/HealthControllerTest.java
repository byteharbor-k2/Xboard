package com.sinx.platform.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void reportsHealthyWithoutExposingFrameworkDetails() {
        Clock clock = Clock.fixed(
            Instant.parse("2026-07-27T00:00:00Z"),
            ZoneOffset.UTC
        );

        HealthController.ServiceStatus status = new HealthController(clock).health();

        assertThat(status.status()).isEqualTo("ok");
        assertThat(status.timestamp()).isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
    }
}
