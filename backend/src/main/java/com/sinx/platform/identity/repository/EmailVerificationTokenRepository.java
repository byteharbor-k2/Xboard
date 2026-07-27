package com.sinx.platform.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.EmailVerificationToken;

import jakarta.persistence.LockModeType;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select token
        from EmailVerificationToken token
        join fetch token.user
        where token.tokenHash = :tokenHash
        """)
    Optional<EmailVerificationToken> findForUpdateByTokenHash(
        @Param("tokenHash") String tokenHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update EmailVerificationToken token
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
