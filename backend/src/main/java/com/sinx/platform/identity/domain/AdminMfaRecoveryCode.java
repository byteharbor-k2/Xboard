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

@Entity
@Table(name = "admin_mfa_recovery_codes")
public class AdminMfaRecoveryCode {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserAccount user;

    @Column(name = "code_hash", length = 64, nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AdminMfaRecoveryCode() {
    }

    public static AdminMfaRecoveryCode create(
        UUID id,
        UserAccount user,
        String codeHash,
        Instant now
    ) {
        AdminMfaRecoveryCode code = new AdminMfaRecoveryCode();
        code.id = id;
        code.user = user;
        code.codeHash = codeHash;
        code.createdAt = now;
        return code;
    }

    public void use(Instant now) {
        usedAt = now;
    }

    public String getCodeHash() {
        return codeHash;
    }
}
