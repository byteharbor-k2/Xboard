package com.sinx.platform.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "device_sessions")
public class DeviceSession {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "session_family_id", nullable = false, updatable = false)
    private UUID sessionFamilyId;

    @Column(name = "refresh_token_hash", length = 64, nullable = false, unique = true)
    private String refreshTokenHash;

    @Column(name = "device_label", length = 120, nullable = false)
    private String deviceLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Version
    @Column(nullable = false)
    private long version;

    protected DeviceSession() {
    }

    public static DeviceSession create(
        UUID id,
        UserAccount user,
        UUID sessionFamilyId,
        String refreshTokenHash,
        String deviceLabel,
        Instant now,
        Instant expiresAt
    ) {
        DeviceSession session = new DeviceSession();
        session.id = id;
        session.user = user;
        session.sessionFamilyId = sessionFamilyId;
        session.refreshTokenHash = refreshTokenHash;
        session.deviceLabel = deviceLabel;
        session.createdAt = now;
        session.lastUsedAt = now;
        session.expiresAt = expiresAt;
        return session;
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(Instant now, UUID replacementId) {
        revokedAt = now;
        replacedById = replacementId;
        lastUsedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public UUID getSessionFamilyId() {
        return sessionFamilyId;
    }

    public String getDeviceLabel() {
        return deviceLabel;
    }
}
