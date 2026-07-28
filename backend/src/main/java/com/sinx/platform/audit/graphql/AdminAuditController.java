package com.sinx.platform.audit.graphql;

import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import com.sinx.platform.audit.application.AdminAuditService;
import com.sinx.platform.audit.application.AdminAuditView;

@Controller
public class AdminAuditController {

    private final AdminAuditService auditService;

    public AdminAuditController(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<AdminAuditView> adminAuditLogs(
        @Argument Integer limit
    ) {
        return auditService.recent(limit == null ? 50 : limit);
    }
}
