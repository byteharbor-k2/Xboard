package com.sinx.platform.identity.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.DeviceSession;

import jakarta.persistence.LockModeType;

public interface DeviceSessionRepository extends JpaRepository<DeviceSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select distinct session
        from DeviceSession session
        join fetch session.user user
        left join fetch user.roles
        where session.refreshTokenHash = :tokenHash
        """)
    Optional<DeviceSession> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DeviceSession session
        set session.revokedAt = :revokedAt,
            session.lastUsedAt = :revokedAt,
            session.version = session.version + 1
        where session.sessionFamilyId = :familyId
          and session.revokedAt is null
        """)
    int revokeActiveFamily(
        @Param("familyId") UUID familyId,
        @Param("revokedAt") java.time.Instant revokedAt
    );
}
