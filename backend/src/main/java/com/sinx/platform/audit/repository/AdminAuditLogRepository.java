package com.sinx.platform.audit.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.audit.domain.AdminAuditLog;

public interface AdminAuditLogRepository
    extends JpaRepository<AdminAuditLog, UUID> {

    @EntityGraph(attributePaths = "actor")
    List<AdminAuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
