package com.sinx.platform.subscription.graphql;

import java.util.UUID;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.subscription.application.SubscriptionEntitlementView;
import com.sinx.platform.subscription.application.SubscriptionService;

@Controller
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    SubscriptionEntitlementView viewerEntitlement(
        @AuthenticationPrincipal Jwt jwt
    ) {
        return subscriptionService
            .currentEntitlement(UUID.fromString(jwt.getSubject()))
            .orElse(null);
    }
}
