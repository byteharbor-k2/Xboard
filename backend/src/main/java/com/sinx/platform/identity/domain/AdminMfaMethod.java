package com.sinx.platform.identity.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "admin_mfa_methods")
public class AdminMfaMethod {

    @Id
    @Column(nullable = false, updatable = false)
    private java.util.UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserAccount user;

    @Column(name = "encrypted_secret", nullable = false)
    private String encryptedSecret;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "last_used_time_step")
    private Long lastUsedTimeStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AdminMfaMethod() {
    }

    public static AdminMfaMethod pending(
        UserAccount user,
        String encryptedSecret,
        Instant now
    ) {
        AdminMfaMethod method = new AdminMfaMethod();
        method.id = java.util.UUID.randomUUID();
        method.user = user;
        method.encryptedSecret = encryptedSecret;
        method.createdAt = now;
        method.updatedAt = now;
        return method;
    }

    public void replacePendingSecret(String encryptedSecret, Instant now) {
        if (isEnabled()) {
            throw new IllegalStateException("Enabled MFA cannot be replaced");
        }
        this.encryptedSecret = encryptedSecret;
        this.updatedAt = now;
    }

    public void enable(Instant now, long confirmationTimeStep) {
        this.enabledAt = now;
        this.lastUsedTimeStep = confirmationTimeStep;
        this.updatedAt = now;
    }

    public boolean consumeTimeStep(long timeStep, Instant now) {
        if (lastUsedTimeStep != null && timeStep <= lastUsedTimeStep) {
            return false;
        }
        lastUsedTimeStep = timeStep;
        updatedAt = now;
        return true;
    }

    public boolean isEnabled() {
        return enabledAt != null;
    }

    public UserAccount getUser() {
        return user;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }
}
