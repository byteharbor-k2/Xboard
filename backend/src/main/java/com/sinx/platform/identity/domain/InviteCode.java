package com.sinx.platform.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "invite_codes")
public class InviteCode {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(length = 32, nullable = false, updatable = false)
    private String code;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected InviteCode() {
    }

    public static InviteCode create(
        UUID id,
        UUID userId,
        String code,
        Instant now
    ) {
        InviteCode inviteCode = new InviteCode();
        inviteCode.id = id;
        inviteCode.userId = userId;
        inviteCode.code = code;
        inviteCode.createdAt = now;
        inviteCode.updatedAt = now;
        return inviteCode;
    }

    public void markUsed(Instant now) {
        usedAt = now;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCode() {
        return code;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
