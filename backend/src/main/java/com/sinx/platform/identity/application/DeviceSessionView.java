package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.sinx.platform.identity.domain.DeviceSession;

public record DeviceSessionView(
    UUID id,
    String deviceLabel,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    boolean current
) {
    static DeviceSessionView from(
        DeviceSession session,
        UUID currentSessionId
    ) {
        return new DeviceSessionView(
            session.getId(),
            session.getDeviceLabel(),
            session.getCreatedAt(),
            session.getLastUsedAt(),
            session.getExpiresAt(),
            session.getId().equals(currentSessionId)
        );
    }
}
