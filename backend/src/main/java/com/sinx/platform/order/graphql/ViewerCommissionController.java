package com.sinx.platform.order.graphql;

import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.order.application.CommissionLogPage;
import com.sinx.platform.order.application.CommissionService;
import com.sinx.platform.order.application.CommissionSummaryView;

/** Referral details are private to the authenticated account that earned them. */
@Controller
public class ViewerCommissionController {

    private final CommissionService commissions;

    public ViewerCommissionController(CommissionService commissions) {
        this.commissions = commissions;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    public CommissionSummaryView viewerCommissionSummary(
        @AuthenticationPrincipal Jwt jwt
    ) {
        return commissions.summary(UUID.fromString(jwt.getSubject()));
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    public CommissionLogPage viewerCommissionLogs(
        @AuthenticationPrincipal Jwt jwt,
        @Argument Integer page,
        @Argument Integer limit
    ) {
        return commissions.logs(
            UUID.fromString(jwt.getSubject()),
            page == null ? 0 : page,
            limit == null ? 20 : limit
        );
    }
}
