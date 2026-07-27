package com.sinx.platform.bootstrap.web;

import java.time.Clock;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final Clock clock;

    public HealthController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/health")
    ServiceStatus health() {
        return new ServiceStatus("ok", Instant.now(clock));
    }

    public record ServiceStatus(String status, Instant timestamp) {
    }
}
