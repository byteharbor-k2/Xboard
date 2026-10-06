package com.sinx.platform.order.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.repository.ServiceOrderRepository;

/** Confirms mature commissions and pays the confirmed queue once a minute. */
@Component
public class CommissionSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(CommissionSettlementJob.class);
    private final ServiceOrderRepository orders;
    private final CommissionService commissions;
    private final PlatformConfigurationService configuration;
    private final Clock clock;

    public CommissionSettlementJob(
        ServiceOrderRepository orders,
        CommissionService commissions,
        PlatformConfigurationService configuration,
        Clock clock
    ) {
        this.orders = orders;
        this.commissions = commissions;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *")
    public void settleCommissions() {
        boolean autoConfirm = configuration.commissionPolicy().autoConfirmEnabled();
        if (autoConfirm) {
            Instant cutoff = Instant.now(clock).minus(Duration.ofHours(72));
            process(orders.findCommissionTradeNosForConfirmation(
                0, OrderStatus.COMPLETED, cutoff
            ), true);
        }
        process(orders.findCommissionTradeNosByStatus(1), false);
    }

    private void process(Iterable<String> tradeNumbers, boolean mayAutoConfirm) {
        for (String tradeNo : tradeNumbers) {
            try {
                commissions.confirmAndPay(tradeNo, mayAutoConfirm);
            } catch (RuntimeException exception) {
                // Every order is isolated so one inconsistent row cannot stall
                // another account's payout.
                log.warn("Could not settle commission for order {}", tradeNo, exception);
            }
        }
    }
}
