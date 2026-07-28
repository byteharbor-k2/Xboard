package com.sinx.platform.identity.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.AdminMfaRecoveryCode;

import jakarta.persistence.LockModeType;

public interface AdminMfaRecoveryCodeRepository
    extends JpaRepository<AdminMfaRecoveryCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select code
        from AdminMfaRecoveryCode code
        where code.user.id = :userId
          and code.usedAt is null
        """)
    List<AdminMfaRecoveryCode> findActiveForUpdate(
        @Param("userId") UUID userId
    );

    @Modifying(flushAutomatically = true)
    @Query("""
        delete from AdminMfaRecoveryCode code
        where code.user.id = :userId
        """)
    int deleteAllByUserId(@Param("userId") UUID userId);
}
