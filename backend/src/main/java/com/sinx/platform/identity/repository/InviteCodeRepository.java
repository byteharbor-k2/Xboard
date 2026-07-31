package com.sinx.platform.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.sinx.platform.identity.domain.InviteCode;

import jakarta.persistence.LockModeType;

public interface InviteCodeRepository
    extends JpaRepository<InviteCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InviteCode> findByCodeIgnoreCase(String code);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    List<InviteCode> findByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(
        UUID userId
    );
}
