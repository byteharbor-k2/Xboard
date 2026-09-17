package com.sinx.platform.order.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sinx.platform.order.config.OrderProperties;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.repository.ServiceOrderRepository;

/**
 * Calls off orders nobody ever paid for.
 *
 * Without this an abandoned checkout holds the account's only order slot for
 * good - a customer cannot start another purchase while an open order exists -
 * and the balance and coupon it reserved are never handed back. The original
 * reaches the same place from its order queue, which cancels anything still
 * pending after two hours.
 */
@Component
public class PendingOrderSweep {

    private static final Logger log =
        LoggerFactory.getLogger(PendingOrderSweep.class);

    private final ServiceOrderRepository orders;
    private final OrderService orderService;
    private final OrderProperties properties;
    private final Clock clock;

    public PendingOrderSweep(
        ServiceOrderRepository orders,
        OrderService orderService,
        OrderProperties properties,
        Clock clock
    ) {
        this.orders = orders;
        this.orderService = orderService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sinx.order.sweep-interval:PT10M}")
    public void cancelAbandonedOrders() {
        Instant cutoff = Instant.now(clock).minus(properties.pendingExpiry());
        for (String tradeNo
            : orders.findTradeNosByStatusCreatedBefore(
                OrderStatus.PENDING,
                cutoff
            )) {
            try {
                orderService.cancelExpired(tradeNo);
            } catch (RuntimeException exception) {
                // One order that cannot be released must not stop the sweep
                // from releasing the others.
                log.warn(
                    "Could not cancel abandoned order {}",
                    tradeNo,
                    exception
                );
            }
        }
    }
}
