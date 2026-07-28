package com.sinx.platform.audit.domain;

import java.time.Instant;
import java.util.UUID;

import com.sinx.platform.identity.domain.UserAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private UserAccount actor;

    @Column(length = 120, nullable = false, updatable = false)
    private String action;

    @Column(name = "http_method", length = 12, nullable = false, updatable = false)
    private String httpMethod;

    @Column(name = "request_path", length = 255, nullable = false, updatable = false)
    private String requestPath;

    @Column(name = "response_status", nullable = false, updatable = false)
    private int responseStatus;

    @Column(length = 16, nullable = false, updatable = false)
    private String outcome;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    @Column(name = "request_id", length = 128, updatable = false)
    private String requestId;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AdminAuditLog() {
    }

    public static AdminAuditLog create(
        UUID id,
        UserAccount actor,
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
        AdminAuditLog log = new AdminAuditLog();
        log.id = id;
        log.actor = actor;
        log.action = action;
        log.httpMethod = httpMethod;
        log.requestPath = requestPath;
        log.responseStatus = responseStatus;
        log.outcome = outcome;
        log.durationMs = durationMs;
        log.requestId = requestId;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent;
        log.occurredAt = occurredAt;
        return log;
    }

    public UUID getId() {
        return id;
    }

    public UserAccount getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getOutcome() {
        return outcome;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
