package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sinx.platform.order.config.OrderProperties;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.repository.ServiceOrderRepository;

/** Releasing the orders nobody ever paid for. */
class PendingOrderSweepTest {

    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");

    private ServiceOrderRepository orders;
    private OrderService orderService;
    private PendingOrderSweep sweep;

    @BeforeEach
    void setUp() {
        orders = mock(ServiceOrderRepository.class);
        orderService = mock(OrderService.class);
        sweep = new PendingOrderSweep(
            orders,
            orderService,
            new OrderProperties(Duration.ofHours(2), Duration.ofMinutes(10)),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void releasesEveryOrderThatHasWaitedPastTheDeadline() {
        when(orders.findTradeNosByStatusCreatedBefore(any(), any()))
            .thenReturn(List.of("SX-1", "SX-2"));

        sweep.cancelAbandonedOrders();

        verify(orderService).cancelExpired("SX-1");
        verify(orderService).cancelExpired("SX-2");
    }

    @Test
    void asksForOrdersOlderThanTheConfiguredDeadline() {
        when(orders.findTradeNosByStatusCreatedBefore(any(), any()))
            .thenReturn(List.of());

        sweep.cancelAbandonedOrders();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(orders).findTradeNosByStatusCreatedBefore(
            eq(OrderStatus.PENDING),
            cutoff.capture()
        );
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(Duration.ofHours(2)));
    }

    @Test
    void keepsReleasingTheRestWhenOneOrderCannotBeReleased() {
        when(orders.findTradeNosByStatusCreatedBefore(any(), any()))
            .thenReturn(List.of("SX-1", "SX-2", "SX-3"));
        doThrow(new IllegalStateException("lock timeout"))
            .when(orderService).cancelExpired("SX-2");

        sweep.cancelAbandonedOrders();

        verify(orderService).cancelExpired("SX-1");
        verify(orderService).cancelExpired("SX-3");
    }

    @Test
    void doesNothingAtAllWhenNothingHasExpired() {
        when(orders.findTradeNosByStatusCreatedBefore(any(), any()))
            .thenReturn(List.of());

        sweep.cancelAbandonedOrders();

        verifyNoInteractions(orderService);
    }
}
