package com.sinx.platform.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.audit.domain.AdminAuditLog;
import com.sinx.platform.audit.repository.AdminAuditLogRepository;
import com.sinx.platform.identity.repository.UserAccountRepository;

@Service
public class AdminAuditService {

    private final AdminAuditLogRepository auditRepository;
    private final UserAccountRepository userRepository;
    private final Clock clock;

    public AdminAuditService(
        AdminAuditLogRepository auditRepository,
        UserAccountRepository userRepository,
        Clock clock
    ) {
        this.auditRepository = auditRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, AuditRecord record) {
        userRepository.findById(actorId).ifPresent(actor ->
            auditRepository.save(AdminAuditLog.create(
                UUID.randomUUID(),
                actor,
                truncate(record.action(), 120),
                truncate(record.httpMethod(), 12),
                truncate(record.requestPath(), 255),
                record.responseStatus(),
                record.responseStatus() < 400 ? "SUCCESS" : "FAILURE",
                Math.max(0, record.durationMs()),
                truncateNullable(record.requestId(), 128),
                truncateNullable(record.ipAddress(), 45),
                truncateNullable(record.userAgent(), 512),
                Instant.now(clock)
            ))
        );
    }

    @Transactional(readOnly = true)
    public List<AdminAuditView> recent(int requestedLimit) {
        int limit = Math.clamp(requestedLimit, 1, 200);
        return auditRepository
            .findAllByOrderByOccurredAtDesc(PageRequest.of(0, limit))
            .stream()
            .map(AdminAuditView::from)
            .toList();
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum
            ? value
            : value.substring(0, maximum);
    }

    private String truncateNullable(String value, int maximum) {
        return value == null ? null : truncate(value, maximum);
    }

    public record AuditRecord(
        String action,
        String httpMethod,
        String requestPath,
        int responseStatus,
        long durationMs,
        String requestId,
        String ipAddress,
        String userAgent
    ) {
    }
}
