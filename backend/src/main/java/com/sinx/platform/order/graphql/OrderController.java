package com.sinx.platform.order.graphql;

import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.catalog.application.CatalogService;
import com.sinx.platform.catalog.application.PlanOfferView;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.OrderDeductionMode;

@Controller
public class OrderController {

    private final OrderService orderService;
    private final CatalogService catalogService;

    public OrderController(
        OrderService orderService,
        CatalogService catalogService
    ) {
        this.orderService = orderService;
        this.catalogService = catalogService;
    }

    @QueryMapping
    PlanOfferView planOffer(
        @AuthenticationPrincipal(errorOnInvalidType = false) Jwt jwt,
        @Argument UUID id
    ) {
        UUID viewerId = isUserSession(jwt) ? UUID.fromString(jwt.getSubject()) : null;
        return catalogService.availableOffer(id, viewerId).orElse(null);
    }

    private boolean isUserSession(Jwt jwt) {
        return jwt != null && jwt.getAudience().contains("sinx-web")
            && jwt.getClaimAsStringList("roles") != null
            && jwt.getClaimAsStringList("roles").contains("USER");
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    OrderQuotePayload orderQuote(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID planId,
        @Argument BillingPeriod period,
        @Argument String couponCode,
        @Argument OrderDeductionMode deductionMode
    ) {
        return OrderQuotePayload.from(orderService.quote(
            UUID.fromString(jwt.getSubject()),
            planId,
            period,
            couponCode,
            deductionMode
        ));
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<ServiceOrderPayload> viewerOrders(@AuthenticationPrincipal Jwt jwt) {
        return orderService.history(UUID.fromString(jwt.getSubject()))
            .stream()
            .map(ServiceOrderPayload::from)
            .toList();
    }

    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    OrderService.ViewerTrafficResetOffer viewerTrafficResetOffer(
        @AuthenticationPrincipal Jwt jwt
    ) {
        return orderService.trafficResetOffer(UUID.fromString(jwt.getSubject()));
    }

    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    ServiceOrderPayload placeOrder(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID planId,
        @Argument BillingPeriod period,
        @Argument String couponCode,
        @Argument OrderDeductionMode deductionMode
    ) {
        return ServiceOrderPayload.from(orderService.place(
            UUID.fromString(jwt.getSubject()),
            planId,
            period,
            couponCode,
            deductionMode
        ));
    }

    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    boolean cancelOrder(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String tradeNo
    ) {
        orderService.cancel(UUID.fromString(jwt.getSubject()), tradeNo);
        return true;
    }
}
