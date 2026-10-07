package com.sinx.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.OrderPricing;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.payment.repository.PaymentMethodRepository;
import com.sinx.platform.shared.web.ApiProblemException;

class PaymentMinimumGateTest {

    private static final Instant NOW = Instant.parse("2026-06-15T10:00:00Z");
    private final ServiceOrderRepository orders = mock(ServiceOrderRepository.class);
    private final PaymentMethodRepository methods = mock(PaymentMethodRepository.class);
    private final PaymentMethodService methodService = mock(PaymentMethodService.class);
    private final OrderFulfilmentService fulfilment = mock(OrderFulfilmentService.class);
    private final ServicePlan plan = mock(ServicePlan.class);
    private final UserAccount user = UserAccount.register(UUID.randomUUID(),
        "buyer@example.test", "hash", "Buyer", mock(Role.class), "token", NOW);
    private final ServiceOrder order = ServiceOrder.create("SXMIN999", user, plan,
        BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE, "CNY",
        new OrderPricing.Breakdown(999, 0, 0, 0, 0, 999), null, "[]", NOW);
    private PaymentCheckoutService checkout;

    @BeforeEach
    void setUp() {
        checkout = new PaymentCheckoutService(orders, methods, methodService,
            mock(PaymentGatewayRegistry.class), mock(PaymentConfigCodec.class),
            fulfilment, Clock.fixed(NOW, ZoneOffset.UTC));
        when(orders.findByTradeNo(order.getTradeNo())).thenReturn(Optional.of(order));
        when(orders.findByTradeNoForUpdate(order.getTradeNo())).thenReturn(Optional.of(order));
    }

    @Test
    void feesCannotLiftASubTenOrderAcrossTheServerSideMinimum() {
        assertThat(checkout.options(user.getId(), order.getTradeNo())).isEmpty();
        assertThatThrownBy(() -> checkout.checkout(user.getId(), order.getTradeNo(),
                UUID.randomUUID()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessage(OrderService.MINIMUM_PAYMENT_MESSAGE);
        verify(methodService, never()).requireEnabled(org.mockito.ArgumentMatchers.any());
        verify(fulfilment, never()).settleCovered(order.getTradeNo());
    }
}
