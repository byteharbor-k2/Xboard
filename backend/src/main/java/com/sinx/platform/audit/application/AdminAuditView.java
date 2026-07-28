package com.sinx.platform.audit.application;

import java.time.Instant;
import java.util.UUID;

import com.sinx.platform.audit.domain.AdminAuditLog;

public record AdminAuditView(
    UUID id,
    UUID actorId,
    String actorEmail,
    String action,
    String httpMethod,
    String requestPath,
    int responseStatus,
    String outcome,
    long durationMs,
    String requestId,
    String ipAddress,
    String userAgent,
    Instant occurredAt
) {
    static AdminAuditView from(AdminAuditLog log) {
        return new AdminAuditView(
            log.getId(),
            log.getActor().getId(),
            log.getActor().getEmail(),
            log.getAction(),
            log.getHttpMethod(),
            log.getRequestPath(),
            log.getResponseStatus(),
            log.getOutcome(),
            log.getDurationMs(),
            log.getRequestId(),
            log.getIpAddress(),
            log.getUserAgent(),
            log.getOccurredAt()
        );
    }
}
