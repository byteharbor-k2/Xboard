package com.sinx.platform.identity.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(length = 320, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 80, nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "inviter_user_id")
    private UUID inviterUserId;

    @Column(name = "server_group_id")
    private Long serverGroupId;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "node_user_id", insertable = false, updatable = false)
    private Long nodeUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_code")
    )
    private Set<Role> roles = new LinkedHashSet<>();

    protected UserAccount() {
    }

    public static UserAccount register(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        Role defaultRole,
        Instant now
    ) {
        UserAccount user = new UserAccount();
        user.id = id;
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.status = UserStatus.ACTIVE;
        user.createdAt = now;
        user.updatedAt = now;
        user.roles.add(defaultRole);
        return user;
    }

    public void recordSuccessfulLogin(Instant now) {
        lastLoginAt = now;
        updatedAt = now;
    }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
            updatedAt = now;
        }
    }

    public void assignInviter(UUID inviterUserId, Instant now) {
        this.inviterUserId = inviterUserId;
        updatedAt = now;
    }

    public void updateDisplayName(String displayName, Instant now) {
        this.displayName = displayName;
        updatedAt = now;
    }

    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        updatedAt = now;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public UUID getInviterUserId() {
        return inviterUserId;
    }

    public Long getServerGroupId() {
        return serverGroupId;
    }

    public Long getNodeUserId() {
        return nodeUserId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    /**
     * Spends up to {@code requestedMinor} of the balance and reports what was
     * actually taken, so a caller cannot overdraw by reading then writing.
     */
    public long spendBalance(long requestedMinor, Instant now) {
        long spent = Math.min(Math.max(requestedMinor, 0), balanceMinor);
        if (spent == 0) {
            return 0;
        }
        balanceMinor -= spent;
        updatedAt = now;
        return spent;
    }

    public void creditBalance(long amountMinor, Instant now) {
        if (amountMinor <= 0) {
            return;
        }
        balanceMinor += amountMinor;
        updatedAt = now;
    }

    public void assignServerGroup(Long serverGroupId, Instant now) {
        this.serverGroupId = serverGroupId;
        this.updatedAt = now;
    }
}
