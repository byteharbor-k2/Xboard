package com.sinx.platform.bootstrap.graphql;

import java.time.Clock;
import java.time.Instant;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class SystemStatusController {

    private final Clock clock;

    public SystemStatusController(Clock clock) {
        this.clock = clock;
    }

    @QueryMapping
    SystemStatus systemStatus() {
        return new SystemStatus("ok", Instant.now(clock).toString());
    }

    record SystemStatus(String status, String timestamp) {
    }
}
