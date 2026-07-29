package com.sinx.platform.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.PasswordResetToken;

import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository
    extends JpaRepository<PasswordResetToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select token
        from PasswordResetToken token
        join fetch token.user user
        left join fetch user.roles
        where token.tokenHash = :tokenHash
        """)
    Optional<PasswordResetToken> findForUpdateByTokenHash(
        @Param("tokenHash") String tokenHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update PasswordResetToken token
        set token.consumedAt = :consumedAt,
            token.version = token.version + 1
        where token.user.id = :userId
          and token.consumedAt is null
        """)
    int consumeActiveForUser(
        @Param("userId") UUID userId,
        @Param("consumedAt") Instant consumedAt
    );
}
