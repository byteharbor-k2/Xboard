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
import org.springframework.context.ApplicationEventPublisher;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.OrderDeductionMode;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.application.TrafficResetService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import com.sinx.platform.subscription.repository.PaidTrafficResetClaimRepository;
import com.sinx.platform.subscription.domain.PaidTrafficResetClaim;

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
    private final TrafficResetService trafficResets;
    private final UserAccountRepository users;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final BalanceLedgerService balanceLedger;
    private PaidTrafficResetClaimRepository resetClaims;
    private SurplusValuation surplusValuation;

    @org.springframework.beans.factory.annotation.Autowired
    void setResetClaims(PaidTrafficResetClaimRepository resetClaims) {
        this.resetClaims = resetClaims;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSurplusValuation(SurplusValuation surplusValuation) {
        this.surplusValuation = surplusValuation;
    }

    public OrderFulfilmentService(
        ServiceOrderRepository orders,
        SubscriptionEntitlementRepository entitlements,
        TrafficResetService trafficResets,
        UserAccountRepository users,
        ApplicationEventPublisher events,
        ObjectMapper objectMapper,
        Clock clock,
        BalanceLedgerService balanceLedger
    ) {
        this.orders = orders;
        this.entitlements = entitlements;
        this.trafficResets = trafficResets;
        this.users = users;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.balanceLedger = balanceLedger;
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

        if (order.getPeriod() == BillingPeriod.RESET_TRAFFIC) {
            openPaidTrafficReset(order, user, now);
            return;
        }

        SubscriptionEntitlement prior = entitlements.findByUserIdForUpdate(user.getId())
            .orElse(null);
        List<ServiceOrder> consumedOrders = lockConsumedOrders(order);
        boolean sourceOwnershipMatches = sourceOrdersBelongTo(user, order,
            consumedOrders);
        boolean eligiblePrior = eligibleSurplusSource(order, prior)
            && sourceOwnershipMatches
            && sourcesMatchEntitlement(prior, consumedOrders);
        if (order.getDeductionMode() == OrderDeductionMode.FULL_PAYMENT
                && order.getDeferredSurplusCreditMinor() > 0) {
            long currentValue = eligiblePrior && surplusValuation != null
                ? surplusValuation.valueOf(prior, now).amountMinor() : 0;
            order.setDeferredSurplusCreditMinor(currentValue);
        }
        if (order.getDeductionMode() == OrderDeductionMode.STANDARD
                && order.getSurplusAmount() > 0 && surplusValuation != null) {
            // Checkout reserves this quote, so elapsed time alone cannot reduce
            // it. The locked entitlement still supplies current usage and any
            // administrator changes that may invalidate the reserved value.
            long currentValue = eligiblePrior
                ? surplusValuation.valueOf(prior, order.getCreatedAt()).amountMinor() : 0;
            if (currentValue < order.getSurplusAmount()) {
                returnUnsafeStandardOrderToBalance(order, user, prior, now);
                return;
            }
        }

        // Value left over when the old plan was worth more than the new one.
        // It is credited here rather than at checkout, as the original does.
        if (order.getSurplusCredit() > 0) {
            balanceLedger.credit(
                user.getId(),
                order.getSurplusCredit(),
                BalanceLogType.SURPLUS_CREDIT,
                order.getTradeNo(),
                null,
                now
            );
        }
        if (order.getDeferredSurplusCreditMinor() > 0) {
            balanceLedger.credit(user.getId(), order.getDeferredSurplusCreditMinor(),
                BalanceLogType.SURPLUS_CREDIT, order.getTradeNo(), null, now);
        }

        writeOffConsumedOrders(consumedOrders,
            eligiblePrior && sourceOwnershipMatches, now);
        retireReplacedActivationSources(order, user, prior, now);
        if (order.getPeriod().getMonthCount() != null) {
            Instant start = coverageStart(order, prior, now);
            order.snapshotCoverage(start, coverageUntil(order, prior, now));
        }
        SubscriptionEntitlement entitlement = applyPlan(order, user, now);
        entitlements.save(entitlement);
        order.complete(now);
        announceEntitlementChange(user.getId(), entitlement, now);
        if (order.getPeriod() != BillingPeriod.RESET_TRAFFIC) {
            announceFulfilment(order, user, entitlement, now);
        }
    }

    /** Claims a paid reset's snapshotted cycle or returns the captured payment to balance. */
    private void openPaidTrafficReset(ServiceOrder order, UserAccount user, Instant now) {
        SubscriptionEntitlement entitlement = entitlements
            .findByUserIdForUpdate(user.getId()).orElse(null);
        UUID currentCycleId = entitlement == null
            ? null : entitlement.ensureTrafficCycleIdentity();
        Instant snapshottedCycleEnd = order.getResetCycleEnd();
        boolean sameCycle = entitlement != null
            && snapshottedCycleEnd != null
            && snapshottedCycleEnd.equals(entitlement.getTrafficCycleEnd())
            && (order.getResetCycleId() == null
                ? order.getCreatedAt() != null
                    && !order.getCreatedAt().isBefore(entitlement.getStartsAt())
                : order.getResetCycleId().equals(currentCycleId));
        Instant snapshottedCycleStart = order.getResetCycleStart() == null
            && entitlement != null ? currentResetCycleStart(entitlement)
            : order.getResetCycleStart();
        boolean current = entitlement != null
            && order.getOriginalAmount() > 0
            && snapshottedCycleEnd != null
            && entitlement.getPlanId().equals(order.getPlan().getId())
            && !entitlement.isTrial()
            && entitlement.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.SUBSCRIPTION
            && entitlement.getCanceledAt() == null
            && entitlement.getExpiresAt() != null
            && entitlement.getExpiresAt().isAfter(now)
            && entitlement.getNextResetAt() != null
            && entitlement.getNextResetAt().isAfter(now)
            && currentCycleId != null
            && sameCycle;
        boolean alreadyClaimed = !current || resetClaims == null
            || resetClaims.existsByUserIdAndCycleId(user.getId(), currentCycleId);
        if (!current || alreadyClaimed) {
            long paidHandling = MANUAL_CALLBACK_NO.equals(order.getCallbackNo())
                    || AUTO_SETTLED_CALLBACK_NO.equals(order.getCallbackNo())
                ? 0 : order.getHandlingAmount();
            long returnAmount = Math.addExact(
                Math.addExact(order.getTotalAmount(), order.getBalanceAmount()),
                paidHandling
            );
            // Assignment changes who receives the service, not who funded the
            // order. Return both its balance deduction and captured payment to
            // the original payer, just as cancellation does for an unpaid order.
            balanceLedger.credit(order.getBalancePayerUserId(), returnAmount,
                BalanceLogType.ORDER_REFUND, order.getTradeNo(), null, now);
            order.returnCapturedPaymentToBalance(returnAmount, now);
            return;
        }

        // The gateway-start snapshot remains authoritative if plan reset flags
        // or prices were edited after the customer was sent to the cashier.
        resetClaims.saveAndFlush(PaidTrafficResetClaim.create(
            user.getId(), currentCycleId, snapshottedCycleStart, snapshottedCycleEnd,
            order.getTradeNo(), now));
        trafficResets.recordPaidReset(entitlement, now, snapshottedCycleEnd);
        entitlements.save(entitlement);
        order.complete(now);
        announceEntitlementChange(user.getId(), entitlement, now);
    }

    private Instant currentResetCycleStart(SubscriptionEntitlement entitlement) {
        return entitlement.getTrafficCycleStart() == null
            ? entitlement.getStartsAt() : entitlement.getTrafficCycleStart();
    }

    /**
     * Tells the nodes the account's subscription changed.
     *
     * A settlement can alter everything a node's user list is built from: the
     * plan (and with it the group the account is served through and the speed
     * limit on the wire), the allowance (an exhausted account drops out of the
     * list, so a renewal that resets the counters puts it back), and the
     * expiry. Even a traffic reset alone can flip an exhausted account back to
     * active. The listener pushes after commit, so a settlement that fails
     * never announces anything.
     */
    private void announceEntitlementChange(
        UUID userId,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        Long groupId = entitlement.getEffectiveServerGroupId();
        events.publishEvent(new UserEntitlementChangedEvent(
            userId,
            groupId == null ? List.of() : List.of(groupId),
            now
        ));
    }

    /**
     * Announces the fulfilment to the customer: the mail that says the
     * subscription they paid for is on. A traffic reset is deliberately not
     * announced - it grants nothing new, so the "subscription opened" mail
     * would be a lie.
     */
    private void announceFulfilment(
        ServiceOrder order,
        UserAccount user,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        events.publishEvent(new OrderFulfilledEvent(
            order.getTradeNo(),
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            entitlement.getPlanName(),
            periodLabel(order.getPeriod()),
            entitlement.getExpiresAt(),
            now
        ));
    }

    /** The billing period as the customer reads it, in both languages. */
    private static String periodLabel(BillingPeriod period) {
        return switch (period) {
            case MONTHLY -> "月付 / Monthly";
            case QUARTERLY -> "季付 / Quarterly";
            case HALF_YEARLY -> "半年付 / Half-yearly";
            case YEARLY -> "年付 / Yearly";
            case TWO_YEARLY -> "两年付 / Two years";
            case THREE_YEARLY -> "三年付 / Three years";
            case ONETIME -> "流量包 / Traffic package";
            case RESET_TRAFFIC -> "流量重置 / Traffic reset";
        };
    }

    /** Locks surplus sources and verifies that they still belong to the buyer. */
    private List<ServiceOrder> lockConsumedOrders(ServiceOrder order) {
        List<UUID> consumed = decodeOrderIds(order.getSurplusOrderIds());
        if (consumed.isEmpty()) {
            return List.of();
        }
        return orders.findAllForUpdateById(consumed);
    }

    private boolean sourceOrdersBelongTo(UserAccount user, ServiceOrder order,
        List<ServiceOrder> sources) {
        List<UUID> consumed = decodeOrderIds(order.getSurplusOrderIds());
        if (consumed.isEmpty()) {
            return order.getSurplusAmount() == 0
                && order.getDeferredSurplusCreditMinor() == 0;
        }
        return sources.size() == consumed.size()
            && sources.stream().allMatch(source ->
                source.getUser().getId().equals(user.getId()));
    }

    private boolean eligibleSurplusSource(ServiceOrder order,
        SubscriptionEntitlement prior) {
        if (prior == null || prior.isTrial() || prior.getCanceledAt() != null) {
            return false;
        }
        return order.getOrderType() == OrderType.UPGRADE
            || prior.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
                && order.getPeriod() == BillingPeriod.ONETIME;
    }

    private boolean sourcesMatchEntitlement(SubscriptionEntitlement prior,
        List<ServiceOrder> sources) {
        if (sources.isEmpty()) {
            return true;
        }
        if (prior == null) {
            return false;
        }
        return sources.stream().allMatch(source ->
            source.getPlan().getId().equals(prior.getPlanId())
                && (prior.getPlanType()
                        != com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
                    || source.getPeriod() == BillingPeriod.ONETIME));
    }

    /** Keeps a stale STANDARD cashier from exchanging already-consumed value for service. */
    private void returnUnsafeStandardOrderToBalance(ServiceOrder order,
        UserAccount user, SubscriptionEntitlement prior, Instant now) {
        if (MANUAL_CALLBACK_NO.equals(order.getCallbackNo())
                || AUTO_SETTLED_CALLBACK_NO.equals(order.getCallbackNo())) {
            throw problem(HttpStatus.CONFLICT, "ORDER_SURPLUS_CHANGED",
                "The reserved entitlement changed while this order was pending. Cancel and place a new order.");
        }
        long returned = Math.addExact(
            Math.addExact(order.getTotalAmount(), order.getBalanceAmount()),
            order.getHandlingAmount());
        balanceLedger.credit(order.getBalancePayerUserId(), returned, BalanceLogType.ORDER_REFUND,
            order.getTradeNo(), null, now);
        order.returnCapturedPaymentToBalance(returned, now);
        if (prior != null) {
            prior.releaseSurplusReservation(now);
            entitlements.save(prior);
            announceEntitlementChange(user.getId(), prior, now);
        }
    }

    /** Marks the still-owned source orders a later upgrade actually consumes. */
    private void writeOffConsumedOrders(List<ServiceOrder> sources,
        boolean sourceOwnershipMatches, Instant now) {
        if (!sourceOwnershipMatches) {
            return;
        }
        for (ServiceOrder source : sources) {
            source.markDiscounted(now);
        }
    }

    /**
     * Retires every paid source behind an activation when a new activation
     * replaces it, even if the old activation was already exhausted and had no
     * surplus order ids to attach to the checkout. A continuous same-plan
     * renewal is the exception: its future funded coverage remains available.
     */
    private void retireReplacedActivationSources(ServiceOrder order,
        UserAccount user, SubscriptionEntitlement prior, Instant now) {
        boolean continuousPeriodicRenewal = prior != null
            && prior.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.SUBSCRIPTION
            && order.getOrderType() == OrderType.RENEWAL
            && order.getPlan().getId().equals(prior.getPlanId())
            && order.getPeriod().getMonthCount() != null;
        if (prior == null || prior.isTrial()
                || continuousPeriodicRenewal) {
            return;
        }
        for (ServiceOrder source : orders.findCompletedFundingSourcesForUpdate(
                user.getId(), prior.getPlanId(), BillingPeriod.RESET_TRAFFIC)) {
            if (fundedByActivation(source, prior)) {
                source.markDiscounted(now);
            }
        }
    }

    private boolean fundedByActivation(ServiceOrder source,
        SubscriptionEntitlement prior) {
        Instant activationStart = prior.getStartsAt();
        Instant activationEnd = prior.getExpiresAt();
        if (source.getCoverageStart() != null && source.getCoverageEnd() != null) {
            return activationStart != null
                && source.getCoverageEnd().isAfter(activationStart)
                && (activationEnd == null
                    || source.getCoverageStart().isBefore(activationEnd));
        }
        Instant paidAt = source.getPaidAt() == null
            ? source.getCreatedAt() : source.getPaidAt();
        return activationStart != null
            && paidAt != null
            && !paidAt.isBefore(activationStart)
            && (activationEnd == null || paidAt.isBefore(activationEnd));
    }

    private SubscriptionEntitlement applyPlan(
        ServiceOrder order,
        UserAccount user,
        Instant now
    ) {
        BillingPeriod period = order.getPeriod();
        ServicePlan plan = order.getPlan();
        SubscriptionEntitlement entitlement = entitlements.findByUserId(user.getId())
            .orElse(null);

        if (period == BillingPeriod.ONETIME) {
            if (entitlement == null) {
                return SubscriptionEntitlement.grant(
                    UUID.randomUUID(),
                    user,
                    plan,
                    now,
                    null,
                    null,
                    trafficResets.effectivePolicy(plan),
                    now
                );
            }
            entitlement.provisionPackage(plan, now);
            entitlement.markPurchased(now);
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
                trafficResets.effectivePolicy(plan),
                now
            );
        }
        entitlement.provisionPeriodic(
            plan,
            expiresAt,
            startsTrafficFresh(order, entitlement),
            trafficResets.effectivePolicy(plan),
            now
        );
        entitlement.markPurchased(now);
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
        Instant base = coverageStart(order, entitlement, now);
        return ZonedDateTime.ofInstant(base, ZoneOffset.UTC)
            .plusMonths(months)
            .toInstant();
    }

    /** The interval a successful order actually funds, not the time it was created. */
    private Instant coverageStart(
        ServiceOrder order,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        return order.getOrderType() == OrderType.RENEWAL
                && entitlement != null && !entitlement.isTrial()
            ? latestOf(now, entitlement.getExpiresAt())
            : now;
    }

    /**
     * Whether the traffic counters start over when the plan goes on.
     *
     * True for a first purchase, a cross-plan change and a move off a
     * non-expiring package; false for a same-plan periodic renewal. Cross-plan
     * remaining value was already consumed by the new purchase.
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
        return entitlement.getExpiresAt() == null
            || order.getOrderType() != OrderType.RENEWAL;
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
