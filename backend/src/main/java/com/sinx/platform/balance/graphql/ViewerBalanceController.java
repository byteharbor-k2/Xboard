package com.sinx.platform.balance.graphql;

import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.balance.application.BalanceLogPage;
import com.sinx.platform.balance.application.BalanceSummaryView;
import com.sinx.platform.balance.application.ViewerBalanceService;

/** Cash balances and transaction history are private to the current user. */
@Controller
public class ViewerBalanceController {

    private final ViewerBalanceService balances;

    public ViewerBalanceController(ViewerBalanceService balances) {
        this.balances = balances;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    public BalanceSummaryView viewerBalanceSummary(@AuthenticationPrincipal Jwt jwt) {
        return balances.summary(UUID.fromString(jwt.getSubject()));
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    public BalanceLogPage viewerBalanceLogs(
        @AuthenticationPrincipal Jwt jwt,
        @Argument Integer page,
        @Argument Integer limit
    ) {
        return balances.logs(
            UUID.fromString(jwt.getSubject()),
            page == null ? 0 : page,
            limit == null ? 20 : limit
        );
    }
}
