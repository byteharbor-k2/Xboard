package com.sinx.platform.order.application;

import java.security.SecureRandom;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.ServicePlanPrice;
import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.Coupon;
import com.sinx.platform.order.domain.CouponRedemption;
import com.sinx.platform.order.domain.CommissionEligibilityPolicy;
import com.sinx.platform.order.domain.OrderPricing;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.OrderDeductionMode;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.CouponRedemptionRepository;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import com.sinx.platform.subscription.repository.PaidTrafficResetClaimRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Places orders and answers what one would cost.
 *
 * A quote and a real order run the same pipeline over the same inputs, so the
 * total a customer is shown is the total they are charged. Only placing an
 * order writes anything: the quote path never debits a balance or burns a
 * coupon redemption.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    public static final String MINIMUM_PAYMENT_MESSAGE =
        "受支付系统限制，最小付款金额不得小于10CNY，此笔支付无法使用剩余价值或余额折抵，请选择折抵后大于10CNY的套餐或不使用折抵全额支付，折抵金额会进入您的余额，下次可以使用";
    public static final long MINIMUM_ONLINE_PAYMENT_MINOR = 1000;

    private static final Set<OrderStatus> OPEN_STATUSES = Set.of(
        OrderStatus.PENDING,
        OrderStatus.PROCESSING
    );
    private static final Set<OrderStatus> COUNTED_TOWARDS_LIMIT = Set.of(
        OrderStatus.PENDING,
        OrderStatus.PROCESSING,
        OrderStatus.COMPLETED,
        OrderStatus.DISCOUNTED
    );
    private static final Set<OrderStatus> EXCLUDED_FROM_FIRST_PAYMENT = Set.of(
        OrderStatus.PENDING,
        OrderStatus.CANCELLED
    );
    private static final DateTimeFormatter TRADE_NO_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ServicePlanRepository plans;
    private final ServiceOrderRepository orders;
    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final CouponEvaluator couponEvaluator;
    private final CouponRedemptionRepository redemptions;
    private final CouponRepository coupons;
    private final SurplusValuation surplusValuation;
    private final OrderFulfilmentService fulfilment;
    private final ObjectMapper objectMapper;
    private final PlatformConfigurationService configuration;
    private final Clock clock;
    private final BalanceLedgerService balanceLedger;
    private final SecureRandom random = new SecureRandom();
    private PaidTrafficResetClaimRepository resetClaims;

    @org.springframework.beans.factory.annotation.Autowired
    void setResetClaims(PaidTrafficResetClaimRepository resetClaims) {
        this.resetClaims = resetClaims;
    }

    public OrderService(
        ServicePlanRepository plans,
        ServiceOrderRepository orders,
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        CouponEvaluator couponEvaluator,
        CouponRedemptionRepository redemptions,
        CouponRepository coupons,
        SurplusValuation surplusValuation,
        OrderFulfilmentService fulfilment,
        ObjectMapper objectMapper,
        PlatformConfigurationService configuration,
        Clock clock,
        BalanceLedgerService balanceLedger
    ) {
        this.plans = plans;
        this.orders = orders;
        this.users = users;
        this.entitlements = entitlements;
        this.couponEvaluator = couponEvaluator;
        this.redemptions = redemptions;
        this.coupons = coupons;
        this.surplusValuation = surplusValuation;
        this.fulfilment = fulfilment;
        this.objectMapper = objectMapper;
        this.configuration = configuration;
        this.clock = clock;
        this.balanceLedger = balanceLedger;
    }

    public OrderQuoteView quote(
        UUID userId,
        UUID planId,
        BillingPeriod period,
        String couponCode
    ) {
        return quote(userId, planId, period, couponCode, OrderDeductionMode.STANDARD);
    }

    public OrderQuoteView quote(UUID userId, UUID planId, BillingPeriod period,
        String couponCode, OrderDeductionMode requestedMode) {
        OrderDeductionMode mode = requestedMode == null
            ? OrderDeductionMode.STANDARD : requestedMode;
        Instant now = Instant.now(clock);
        UserAccount user = requireUser(userId);
        ServicePlan plan = requirePlan(planId);
        ServicePlanPrice price = requirePrice(plan, period);
        if (period == BillingPeriod.RESET_TRAFFIC && price.getAmountMinor() <= 0) {
            throw rejected("A paid traffic reset requires a positive configured price");
        }
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(userId).orElse(null);

        boolean newUserOffer = validateNewUserOffer(userId, plan, period);
        validatePurchasable(user, plan, period, entitlement, now);

        OrderType type = classify(plan, period, entitlement, now);
        assertPlanChangeAllowed(type);
        Optional<CouponEvaluator.Applied> coupon = couponEvaluator.evaluate(
            couponCode,
            userId,
            planId,
            period,
            price.getAmountMinor(),
            now
        );
        SurplusValuation.Surplus surplus = canValueSurplus(entitlement, period, type)
            ? surplusValuation.valueOf(entitlement, now)
            : new SurplusValuation.Surplus(0, List.of());
        boolean fullPayment = mode == OrderDeductionMode.FULL_PAYMENT;
        OrderPricing.Breakdown breakdown = OrderPricing.compute(
            new OrderPricing.Inputs(
                price.getAmountMinor(),
                coupon.map(CouponEvaluator.Applied::discountMinor).orElse(0L),
                fullPayment ? 0 : surplus.amountMinor(),
                fullPayment ? 0 : user.getBalanceMinor()
            )
        );

        return new OrderQuoteView(
            plan.getId(),
            plan.getName(),
            period,
            type,
            price.getCurrency(),
            breakdown,
            coupon.map(applied -> applied.coupon().getCode()).orElse(null),
            coupon.map(applied -> applied.coupon().getName()).orElse(null),
            user.getBalanceMinor(),
            mode,
            fullPayment ? surplus.amountMinor() : 0,
            blockedByMinimum(breakdown.totalAmount()),
            blockedByMinimum(breakdown.totalAmount())
                ? MINIMUM_PAYMENT_MESSAGE : null
        );
    }

    @Transactional
    public ServiceOrder place(
        UUID userId,
        UUID planId,
        BillingPeriod period,
        String couponCode
    ) {
        return place(userId, planId, period, couponCode, OrderDeductionMode.STANDARD);
    }

    @Transactional
    public ServiceOrder place(UUID userId, UUID planId, BillingPeriod period,
        String couponCode, OrderDeductionMode requestedMode) {
        OrderDeductionMode mode = requestedMode == null
            ? OrderDeductionMode.STANDARD : requestedMode;
        Instant now = Instant.now(clock);
        // The user row is the serialization point for new-user promotions. It
        // prevents two simultaneous placements from both observing an eligible
        // account before either has committed its open order.
        UserAccount user = users.findByIdForUpdate(userId).orElseThrow(() ->
            problem(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            )
        );
        ServicePlan plan = requirePlan(planId);
        ServicePlanPrice price = requirePrice(plan, period);
        if (period == BillingPeriod.RESET_TRAFFIC && price.getAmountMinor() <= 0) {
            throw rejected("A paid traffic reset requires a positive configured price");
        }
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(userId).orElse(null);

        if (orders.existsByUserIdAndStatusIn(userId, OPEN_STATUSES)) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_ALREADY_OPEN",
                "An unpaid order is already open. Pay or cancel it first."
            );
        }
        boolean newUserOffer = validateNewUserOffer(userId, plan, period);
        validatePurchasable(user, plan, period, entitlement, now);

        OrderType type = classify(plan, period, entitlement, now);
        assertPlanChangeAllowed(type);
        Optional<CouponEvaluator.Applied> coupon = couponEvaluator.evaluate(
            couponCode,
            userId,
            planId,
            period,
            price.getAmountMinor(),
            now,
            true
        );
        if (period == BillingPeriod.RESET_TRAFFIC
                && orders.existsByUserIdAndPeriodAndStatus(
                    userId, BillingPeriod.RESET_TRAFFIC, OrderStatus.PENDING)) {
            throw rejected("A traffic reset order is already pending");
        }
        SurplusValuation.Surplus surplus = canValueSurplus(entitlement, period, type)
            ? surplusValuation.valueOf(entitlement, now)
            : new SurplusValuation.Surplus(0, List.of());
        boolean fullPayment = mode == OrderDeductionMode.FULL_PAYMENT;
        OrderPricing.Breakdown breakdown = OrderPricing.compute(
            new OrderPricing.Inputs(
                price.getAmountMinor(),
                coupon.map(CouponEvaluator.Applied::discountMinor).orElse(0L),
                fullPayment ? 0 : surplus.amountMinor(),
                fullPayment ? 0 : user.getBalanceMinor()
            )
        );

        String tradeNo = nextTradeNo(now);
        ServiceOrder order = ServiceOrder.create(
            tradeNo,
            user,
            plan,
            period,
            type,
            price.getCurrency(),
            breakdown,
            coupon.map(applied -> applied.coupon().getId()).orElse(null),
            encode(surplus.consumedOrderIds()),
            newUserOffer,
            mode,
            fullPayment ? surplus.amountMinor() : 0,
            period == BillingPeriod.RESET_TRAFFIC && entitlement != null
                ? currentResetCycleEnd(entitlement, now) : null,
            now
        );
        CommissionSnapshot commission = commissionSnapshot(user, breakdown);
        order.snapshotCommission(
            commission.inviterId(),
            commission.baseMinor(),
            commission.poolMinor(),
            commission.eligible()
        );
        ServiceOrder placedOrder = orders.save(order);
        // The ledger references the persisted order trade number. Both writes
        // remain in this transaction, so a failed debit record rolls the order
        // and every accompanying checkout write back together.
        orders.flush();
        balanceLedger.debit(userId, breakdown.balanceAmount(), tradeNo, now);

        coupon.ifPresent(applied -> {
            Coupon redeemed = applied.coupon();
            redeemed.recordRedemption(now);
            redemptions.save(CouponRedemption.create(
                redeemed.getId(),
                userId,
                placedOrder.getId(),
                now
            ));
        });

        // Nothing is left to pay, so there is nothing for a gateway to collect
        // and the customer should not be left holding an order they cannot act
        // on. Whichever deduction emptied it - coupon, upgrade surplus or
        // balance - it is opened here rather than left pending.
        if (placedOrder.getTotalAmount() <= 0) {
            return fulfilment.settleCovered(placedOrder.getTradeNo());
        }

        return placedOrder;
    }

    private boolean canValueSurplus(SubscriptionEntitlement entitlement,
        BillingPeriod period, OrderType type) {
        if (!subscriptionPolicy().surplusEnabled() || entitlement == null
                || entitlement.isTrial() || period == BillingPeriod.RESET_TRAFFIC) {
            return false;
        }
        return type == OrderType.UPGRADE
            || entitlement.getPlanType() == PlanType.TRAFFIC_PACKAGE
                && period == BillingPeriod.ONETIME;
    }

    public record ViewerTrafficResetOffer(boolean canPurchase,
        boolean alreadyReset, String priceMinor, String cycleEndsAt,
        String pendingTradeNo, String reason) { }

    public ViewerTrafficResetOffer trafficResetOffer(UUID userId) {
        Instant now = Instant.now(clock);
        SubscriptionEntitlement entitlement = entitlements.findByUserId(userId).orElse(null);
        String pending = orders.findTradeNoByUserIdAndPeriodAndStatus(userId,
            BillingPeriod.RESET_TRAFFIC, OrderStatus.PENDING).orElse(null);
        if (pending != null) {
            Instant pendingCycleEnd = entitlement == null
                ? null : currentResetCycleEnd(entitlement, now);
            return new ViewerTrafficResetOffer(false, false, null,
                pendingCycleEnd == null ? null : pendingCycleEnd.toString(), pending,
                "A traffic reset order is already pending");
        }
        String reason = null;
        boolean eligible = entitlement != null && !entitlement.isTrial()
            && entitlement.getPlanType() == PlanType.SUBSCRIPTION
            && entitlement.getCanceledAt() == null
            && entitlement.getExpiresAt() != null
            && entitlement.getExpiresAt().isAfter(now)
            && entitlement.getNextResetAt() != null
            && entitlement.getResetPolicy()
                != com.sinx.platform.catalog.domain.TrafficResetPolicy.NEVER;
        ServicePlan plan = eligible ? plans.findById(entitlement.getPlanId()).orElse(null) : null;
        ServicePlanPrice price = plan == null ? null : plan.getPrices().stream()
            .filter(item -> item.getBillingPeriod() == BillingPeriod.RESET_TRAFFIC)
            .findFirst().orElse(null);
        if (!eligible || plan == null || !plan.isResettable() || price == null
                || price.getAmountMinor() <= 0) {
            eligible = false;
            reason = "An active periodic plan with a configured reset price is required";
        }
        boolean already = eligible && resetClaims != null
            && resetClaims.existsByUserIdAndCycleEnd(userId,
                currentResetCycleEnd(entitlement, now));
        if (already) {
            eligible = false;
            reason = "Traffic has already been reset in this cycle";
        }
        Instant cycleEnd = entitlement == null
            ? null : currentResetCycleEnd(entitlement, now);
        return new ViewerTrafficResetOffer(eligible, already,
            price == null ? null : Long.toString(price.getAmountMinor()),
            cycleEnd == null ? null : cycleEnd.toString(), null, reason);
    }

    private Instant currentResetCycleEnd(SubscriptionEntitlement entitlement, Instant now) {
        Instant boundary = entitlement.getNextResetAt();
        if (boundary == null || boundary.isAfter(now)) return boundary;
        return com.sinx.platform.subscription.domain.MonthlyResetSchedule.followingBoundary(
            entitlement.getResetPolicy(), boundary, now);
    }

    /** Rejects a stale pending paid-reset order before a cashier URL is issued. */
    @Transactional
    public void validateTrafficResetBeforeGateway(ServiceOrder order) {
        if (order.getPeriod() != BillingPeriod.RESET_TRAFFIC) {
            return;
        }
        Instant now = Instant.now(clock);
        SubscriptionEntitlement entitlement = entitlements
            .findByUserIdForUpdate(order.getUser().getId()).orElse(null);
        ServicePlan plan = entitlement == null
            ? null : plans.findById(entitlement.getPlanId()).orElse(null);
        ServicePlanPrice currentResetPrice = plan == null ? null
            : plan.getPrices().stream()
                .filter(price -> price.getBillingPeriod() == BillingPeriod.RESET_TRAFFIC)
                .findFirst().orElse(null);
        boolean eligible = order.getOriginalAmount() > 0
            && order.getResetCycleEnd() != null
            && entitlement != null
            && !entitlement.isTrial()
            && entitlement.getPlanType() == PlanType.SUBSCRIPTION
            && entitlement.getPlanId().equals(order.getPlan().getId())
            && entitlement.getCanceledAt() == null
            && entitlement.getExpiresAt() != null
            && entitlement.getExpiresAt().isAfter(now)
            && currentResetCycleEnd(entitlement, now) != null
            && order.getResetCycleEnd().equals(currentResetCycleEnd(entitlement, now))
            && plan != null
            && plan.isResettable()
            && currentResetPrice != null
            && currentResetPrice.getAmountMinor() > 0
            && (resetClaims == null || !resetClaims.existsByUserIdAndCycleEnd(
                order.getUser().getId(), order.getResetCycleEnd()));
        if (!eligible) {
            throw problem(HttpStatus.CONFLICT, "TRAFFIC_RESET_NOT_AVAILABLE",
                "This paid traffic reset is no longer available in the current cycle. Cancel it and place a new order.");
        }
    }

    private static boolean blockedByMinimum(long amount) {
        return amount > 0 && amount < MINIMUM_ONLINE_PAYMENT_MINOR;
    }

    /**
     * Freezes the direct inviter and commission pool at order placement. The
     * base is what the buyer pays or consumes after coupon/surplus deductions,
     * before the buyer's balance deduction; it excludes gateway handling fees.
     * Whole-cent percentages truncate down, matching the original integer
     * minor-unit storage (no rounding fraction is carried to another order).
     */
    private CommissionSnapshot commissionSnapshot(
        UserAccount buyer,
        OrderPricing.Breakdown breakdown
    ) {
        UUID inviterId = buyer.getInviterUserId();
        long base = Math.addExact(breakdown.totalAmount(), breakdown.balanceAmount());
        if (inviterId == null) {
            return new CommissionSnapshot(null, base, 0, false);
        }
        UserAccount inviter = users.findById(inviterId).orElse(null);
        if (inviter == null || base <= 0) {
            return new CommissionSnapshot(inviterId, base, 0, false);
        }

        int type = inviter.getCommissionType();
        boolean globalFirstPaymentOnly = configuration.commissionPolicy()
            .firstPaymentOnly();
        boolean firstOnly = CommissionEligibilityPolicy.isFirstPaymentOnly(
            type,
            globalFirstPaymentOnly
        );
        // The original first-order test excluded only unpaid and cancelled
        // history. PROCESSING, COMPLETED, DISCOUNTED, and zero-value rows count.
        boolean hasEligibleHistory = firstOnly
            && orders.countFirstPaymentHistory(
                buyer.getId(), EXCLUDED_FROM_FIRST_PAYMENT
            ) > 0;
        boolean eligible = CommissionEligibilityPolicy.isEligible(
            type,
            globalFirstPaymentOnly,
            hasEligibleHistory
        );
        int rate = inviter.getCommissionRate() != null
                && inviter.getCommissionRate() > 0
            ? inviter.getCommissionRate()
            : configuration.invitationPolicy().commissionPercent();
        long pool = eligible && rate > 0
            ? BigInteger.valueOf(base).multiply(BigInteger.valueOf(rate))
                .divide(BigInteger.valueOf(100)).longValueExact()
            : 0;
        return new CommissionSnapshot(inviterId, base, pool, eligible);
    }

    private record CommissionSnapshot(
        UUID inviterId,
        long baseMinor,
        long poolMinor,
        boolean eligible
    ) {
    }

    @Transactional
    public ServiceOrder cancel(UUID userId, String tradeNo) {
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo)
            .filter(candidate -> candidate.getUser().getId().equals(userId))
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "The order does not exist"
            ));
        return releaseOrder(order);
    }

    /** Calls off an order on an administrator's authority. */
    @Transactional
    public ServiceOrder cancelManually(String tradeNo) {
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo).orElseThrow(() ->
            problem(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "The order does not exist"
            )
        );
        return releaseOrder(order);
    }

    /**
     * Calls off one order the sweep found, in its own transaction so a single
     * bad record cannot hold up the rest of the batch.
     *
     * Silently does nothing if the order is gone or was settled between the
     * sweep listing it and this call.
     */
    @Transactional
    public void cancelExpired(String tradeNo) {
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo).orElse(null);
        if (order == null || !order.isPending()) {
            return;
        }
        releaseOrder(order);
    }

    private ServiceOrder releaseOrder(ServiceOrder order) {
        // Only an order nobody has settled can be called off, as in the
        // original. A completed order has already handed over a subscription,
        // and a discounted one has been spent by a later upgrade.
        if (!order.isPending()) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_NOT_CANCELLABLE",
                "This order can no longer be cancelled"
            );
        }
        Instant now = Instant.now(clock);
        // Give back whatever the order had taken from the balance, and release
        // the coupon use so a cancelled order does not consume an allowance.
        balanceLedger.credit(
            order.getUser().getId(),
            order.getBalanceAmount(),
            BalanceLogType.ORDER_REFUND,
            order.getTradeNo(),
            null,
            now
        );
        releaseCoupon(order, now);
        order.cancel(now);
        return order;
    }

    private void releaseCoupon(ServiceOrder order, Instant now) {
        UUID couponId = order.getCouponId();
        if (couponId == null) {
            return;
        }
        redemptions.deleteByOrderId(order.getId());
        coupons.findById(couponId)
            .ifPresent(coupon -> coupon.releaseRedemption(now));
    }

    public List<ServiceOrder> history(UUID userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * The newest orders, for the admin list an operator settles from. Newest
     * first, capped at what the caller asked for.
     */
    public List<OrderAdminView> adminList(
        OrderStatus status,
        int limit,
        Boolean commissionOnly,
        Integer commissionStatus
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        PageRequest page = PageRequest.of(0, safeLimit);
        List<ServiceOrder> found = orders.adminCommissionSearch(
            status,
            Boolean.TRUE.equals(commissionOnly),
            Set.of(OrderStatus.PENDING, OrderStatus.CANCELLED),
            commissionStatus,
            page
        );
        return found.stream().map(OrderAdminView::from).toList();
    }

    /** Retained for existing admin callers that do not request commission filters. */
    public List<OrderAdminView> adminList(OrderStatus status, int limit) {
        return adminList(status, limit, null, null);
    }

    /**
     * Classifies the purchase exactly as the original panel does: a reset
     * package is its own kind, switching plans while still covered is an
     * upgrade, buying the plan already held is a renewal, anything else is new.
     */
    private OrderType classify(
        ServicePlan plan,
        BillingPeriod period,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        if (period == BillingPeriod.RESET_TRAFFIC) {
            return OrderType.RESET_TRAFFIC;
        }
        if (entitlement != null && entitlement.isTrial()) {
            return OrderType.NEW_PURCHASE;
        }
        if (entitlement == null || entitlement.getCanceledAt() != null) {
            return OrderType.NEW_PURCHASE;
        }
        if (entitlement.getPlanId().equals(plan.getId())) {
            return OrderType.RENEWAL;
        }
        return stillCovered(entitlement, now)
            ? OrderType.UPGRADE : OrderType.NEW_PURCHASE;
    }

    private boolean stillCovered(
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        Instant expiresAt = entitlement.getExpiresAt();
        return entitlement.getCanceledAt() == null
            && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** The legacy switch blocks only a live paid cross-plan change. */
    private void assertPlanChangeAllowed(OrderType type) {
        if (type == OrderType.UPGRADE
                && !subscriptionPolicy().planChangeEnabled()) {
            throw rejected("Changing an active subscription plan is disabled");
        }
    }

    private PlatformConfigurationService.SubscriptionPolicy subscriptionPolicy() {
        PlatformConfigurationService.SubscriptionPolicy policy =
            configuration.subscriptionPolicy();
        return policy == null
            ? new PlatformConfigurationService.SubscriptionPolicy(true, true, 1)
            : policy;
    }

    private void validatePurchasable(
        UserAccount user,
        ServicePlan plan,
        BillingPeriod period,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        if (period == BillingPeriod.RESET_TRAFFIC) {
            if (!plan.isResettable()
                    || plan.getPlanType() != PlanType.SUBSCRIPTION) {
                throw rejected("This plan does not offer traffic resets");
            }
            if (entitlement == null
                    || entitlement.isTrial()
                    || entitlement.getPlanType() != PlanType.SUBSCRIPTION
                    || !entitlement.getPlanId().equals(plan.getId())
                    || entitlement.getCanceledAt() != null
                    || entitlement.getExpiresAt() == null
                    || !entitlement.getExpiresAt().isAfter(now)
                    || entitlement.getNextResetAt() == null
                    || entitlement.getResetPolicy()
                        == com.sinx.platform.catalog.domain.TrafficResetPolicy.NEVER) {
                throw rejected(
                    "A paid traffic reset requires an active periodic subscription"
                );
            }
            if (resetClaims != null && resetClaims.existsByUserIdAndCycleEnd(
                    user.getId(), currentResetCycleEnd(entitlement, now))) {
                throw rejected("Traffic has already been reset in this cycle");
            }
            return;
        }

        boolean holdsThisPlan = entitlement != null
            && !entitlement.isTrial()
            && entitlement.getCanceledAt() == null
            && entitlement.getPlanId().equals(plan.getId());

        if (holdsThisPlan) {
            if (!plan.isRenewable()) {
                throw rejected("This plan cannot be renewed");
            }
            return;
        }

        if (!plan.isPublished() || !plan.isSellable()) {
            throw rejected("This plan is not on sale");
        }
        Integer capacity = plan.getCapacityLimit();
        if (capacity != null
                && entitlements.countActiveForPlan(plan.getId(), now)
                    >= capacity) {
            throw rejected("This plan is sold out");
        }
        Integer perUser = plan.getPurchaseLimitPerUser();
        if (perUser != null
                && orders.countByUserIdAndPlanIdAndStatusIn(
                    user.getId(),
                    plan.getId(),
                    COUNTED_TOWARDS_LIMIT
                ) >= perUser) {
            throw rejected("You have reached the purchase limit for this plan");
        }
    }

    /**
     * Enforces the configured offer at both quote and placement boundaries.
     * The configured plan is a dedicated ONETIME traffic package; the promo
     * classification is persisted on the resulting order rather than inferred
     * later from mutable settings.
     */
    private boolean validateNewUserOffer(
        UUID userId,
        ServicePlan plan,
        BillingPeriod period
    ) {
        UUID configuredPlanId = configuration.newUserOfferPlanId().orElse(null);
        if (!plan.getId().equals(configuredPlanId)) {
            return false;
        }
        if (plan.getPlanType() != PlanType.TRAFFIC_PACKAGE
                || period != BillingPeriod.ONETIME
                || !plan.isPublished()
                || !plan.isSellable()
                || plan.getPrices().stream().noneMatch(price ->
                    price.getBillingPeriod() == BillingPeriod.ONETIME
                )) {
            throw rejected("This new-user traffic package is not available");
        }
        if (orders.existsByUserIdAndStatusIn(userId, Set.of(
                OrderStatus.COMPLETED,
                OrderStatus.DISCOUNTED
            ))) {
            throw rejected(
                "The new-user traffic package can only be purchased once"
            );
        }
        return true;
    }

    private UserAccount requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "USER_NOT_FOUND",
            "The account does not exist"
        ));
    }

    private ServicePlan requirePlan(UUID planId) {
        return plans.findById(planId).orElseThrow(() -> problem(
            HttpStatus.NOT_FOUND,
            "PLAN_NOT_FOUND",
            "The plan does not exist"
        ));
    }

    private ServicePlanPrice requirePrice(
        ServicePlan plan,
        BillingPeriod period
    ) {
        return plan.getPrices().stream()
            .filter(price -> price.getBillingPeriod() == period)
            .findFirst()
            .orElseThrow(() -> rejected(
                "This billing period is not available for the plan"
            ));
    }

    private String encode(List<UUID> ids) {
        return objectMapper.writeValueAsString(
            ids.stream().map(UUID::toString).toList()
        );
    }

    private String nextTradeNo(Instant now) {
        return "SX" + TRADE_NO_STAMP.format(now)
            + String.format("%04d", random.nextInt(10_000));
    }

    private ApiProblemException rejected(String detail) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "ORDER_REJECTED", detail);
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }
}
