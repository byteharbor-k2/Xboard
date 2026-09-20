package com.sinx.platform.order.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a settled order into the service the customer bought.
 *
 * This is the second half of an order's life: {@link OrderService} prices it
 * and takes the money, this provisions the entitlement and closes the order
 * out. It mirrors the original panel's {@code paid()} and {@code open()}.
 *
 * An order with nothing left to pay settles itself here instead of waiting for
 * a gateway. Whatever covered it - the account balance, an upgrade's surplus,
 * or a coupon - there is nothing for a gateway to collect, and the payment
 * method list is empty for a zero total by design. Parking such an order as
 * pending stranded it: the customer could neither pay it nor have it opened.
 */
@Service
public class OrderFulfilmentService {

    /** Recorded on orders an administrator settled by hand, as the original does. */
    public static final String MANUAL_CALLBACK_NO = "manual_operation";

    /**
     * Recorded on orders that had nothing left to pay, whatever covered them.
     * Kept distinct from {@link #MANUAL_CALLBACK_NO} so an operator can tell an
     * order the system opened from one they opened by hand.
     */
    public static final String AUTO_SETTLED_CALLBACK_NO = "auto_settled";

    private static final TypeReference<List<String>> STRING_LIST =
        new TypeReference<>() {
        };

    private final ServiceOrderRepository orders;
    private final SubscriptionEntitlementRepository entitlements;
    private final UserAccountRepository users;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderFulfilmentService(
        ServiceOrderRepository orders,
        SubscriptionEntitlementRepository entitlements,
        UserAccountRepository users,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.orders = orders;
        this.entitlements = entitlements;
        this.users = users;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Settles an order and provisions it.
     *
     * Idempotent, as the original's {@code paid()} is: an order that is no
     * longer awaiting payment is returned untouched, so a payment callback that
     * arrives twice cannot hand out a second subscription.
     */
    @Transactional
    public ServiceOrder settle(String tradeNo, String callbackNo) {
        ServiceOrder order = requireOrderForUpdate(tradeNo);
        if (!order.isPending()) {
            return order;
        }
        return settle(order, callbackNo);
    }

    /**
     * Settles an order on an administrator's authority, with no payment behind
     * it. This is the only way an order can be opened while payment is not
     * built, and it stays available afterwards for wire transfers and
     * corrections.
     */
    @Transactional
    public ServiceOrder settleManually(String tradeNo) {
        ServiceOrder order = requireOrderForUpdate(tradeNo);
        if (!order.isPending()) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_NOT_PENDING",
                "Only an order awaiting payment can be opened"
            );
        }
        return settle(order, MANUAL_CALLBACK_NO);
    }

    /**
     * Settles an order that has nothing left to pay.
     *
     * Nothing is collected because nothing is owed: the balance was debited and
     * the surplus applied when the order was placed, and a coupon was spent on
     * it. Any of those can bring the total to zero, and none of them leaves a
     * gateway anything to do - so the order is opened rather than left pending
     * with an empty payment list.
     *
     * Returns the order untouched when it is no longer pending, so a retry
     * after a successful settlement is harmless.
     */
    @Transactional
    public ServiceOrder settleCovered(String tradeNo) {
        ServiceOrder order = requireOrderForUpdate(tradeNo);
        if (!order.isPending()) {
            return order;
        }
        return settle(order, AUTO_SETTLED_CALLBACK_NO);
    }

    private ServiceOrder settle(ServiceOrder order, String callbackNo) {
        Instant now = Instant.now(clock);
        order.markPaid(callbackNo, now);
        open(order, now);
        return order;
    }

    /**
     * The original's {@code open()}: give back the surplus the order earned,
     * write off the orders it consumed, grant the plan, and close the order.
     *
     * Everything runs in one transaction. A failure leaves the order pending,
     * so a settlement that cannot be provisioned is retried rather than
     * half-applied - the customer is never charged for nothing.
     */
    private void open(ServiceOrder order, Instant now) {
        UserAccount user = users.findByIdForUpdate(order.getUser().getId())
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            ));

        // Value left over when the old plan was worth more than the new one.
        // It is credited here rather than at checkout, as the original does.
        if (order.getSurplusCredit() > 0) {
            user.creditBalance(order.getSurplusCredit(), now);
        }

        writeOffConsumedOrders(order, now);
        entitlements.save(applyPlan(order, user, now));
        order.complete(now);
    }

    /** Marks the earlier orders a later upgrade spent as discounted. */
    private void writeOffConsumedOrders(ServiceOrder order, Instant now) {
        List<UUID> consumed = decodeOrderIds(order.getSurplusOrderIds());
        if (consumed.isEmpty()) {
            return;
        }
        for (ServiceOrder source : orders.findAllForUpdateById(consumed)) {
            source.markDiscounted(now);
        }
    }

    private SubscriptionEntitlement applyPlan(
        ServiceOrder order,
        UserAccount user,
        Instant now
    ) {
        BillingPeriod period = order.getPeriod();
        ServicePlan plan = order.getPlan();
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(user.getId()).orElse(null);

        if (period == BillingPeriod.RESET_TRAFFIC) {
            if (entitlement == null) {
                throw inconsistent(
                    order,
                    "a traffic reset needs a subscription to reset"
                );
            }
            entitlement.resetTraffic(now);
            return entitlement;
        }

        if (period == BillingPeriod.ONETIME) {
            if (entitlement == null) {
                return SubscriptionEntitlement.grant(
                    UUID.randomUUID(),
                    user,
                    plan,
                    now,
                    null,
                    null,
                    now
                );
            }
            entitlement.provisionPackage(plan, now);
            return entitlement;
        }

        Instant expiresAt = coverageUntil(order, entitlement, now);
        if (entitlement == null) {
            return SubscriptionEntitlement.grant(
                UUID.randomUUID(),
                user,
                plan,
                now,
                expiresAt,
                null,
                now
            );
        }
        entitlement.provisionPeriodic(
            plan,
            expiresAt,
            startsTrafficFresh(order, entitlement),
            now
        );
        return entitlement;
    }

    /**
     * When the new coverage ends.
     *
     * A renewal stacks onto whatever time was left, so a customer who renews
     * early loses nothing. An upgrade does not: the value of the remaining time
     * was already returned through the surplus deduction, and stacking it on
     * would pay for it twice.
     */
    private Instant coverageUntil(
        ServiceOrder order,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        Integer months = order.getPeriod().getMonthCount();
        if (months == null) {
            throw inconsistent(order, "the billing period has no length");
        }
        Instant base = order.getOrderType() == OrderType.UPGRADE
            ? now
            : latestOf(now, entitlement == null ? null : entitlement.getExpiresAt());
        return ZonedDateTime.ofInstant(base, ZoneOffset.UTC)
            .plusMonths(months)
            .toInstant();
    }

    /**
     * Whether the traffic counters start over when the plan goes on.
     *
     * True for a first purchase and for a move off a non-expiring package, false
     * for a renewal or an upgrade of a running subscription.
     *
     * The original's comment states the same rule - "reset the traffic when
     * converting from one-time to periodic, or on a new purchase" - but its code
     * stamps {@code expired_at = now} on an upgrade before testing it for null,
     * so the one-time-to-periodic upgrade keeps its used traffic even though its
     * remaining traffic has just been refunded as surplus. Following the
     * comment rather than the code, so a refunded package cannot also be spent.
     */
    private boolean startsTrafficFresh(
        ServiceOrder order,
        SubscriptionEntitlement entitlement
    ) {
        if (entitlement.getExpiresAt() == null) {
            return true;
        }
        return order.getOrderType() == OrderType.NEW_PURCHASE;
    }

    private Instant latestOf(Instant now, Instant candidate) {
        return candidate != null && candidate.isAfter(now) ? candidate : now;
    }

    private ServiceOrder requireOrderForUpdate(String tradeNo) {
        return orders.findByTradeNoForUpdate(tradeNo).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "ORDER_NOT_FOUND",
            "The order does not exist"
        ));
    }

    private List<UUID> decodeOrderIds(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(encoded, STRING_LIST);
            if (values == null) {
                return List.of();
            }
            return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(UUID::fromString)
                .toList();
        } catch (RuntimeException exception) {
            // Treating an unreadable list as empty would leave consumed orders
            // looking settled, and their value could be cashed in a second time.
            throw new IllegalStateException(
                "The consumed-order list of " + encoded + " could not be read",
                exception
            );
        }
    }

    private IllegalStateException inconsistent(
        ServiceOrder order,
        String detail
    ) {
        return new IllegalStateException(
            "Order " + order.getTradeNo() + " cannot be opened: " + detail
        );
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }
}
