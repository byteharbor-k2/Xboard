package com.sinx.platform.order.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.ServicePlanPrice;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.Coupon;
import com.sinx.platform.order.domain.CouponRedemption;
import com.sinx.platform.order.domain.OrderPricing;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.CouponRedemptionRepository;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

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
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OrderService(
        ServicePlanRepository plans,
        ServiceOrderRepository orders,
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        CouponEvaluator couponEvaluator,
        CouponRedemptionRepository redemptions,
        CouponRepository coupons,
        SurplusValuation surplusValuation,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.plans = plans;
        this.orders = orders;
        this.users = users;
        this.entitlements = entitlements;
        this.couponEvaluator = couponEvaluator;
        this.redemptions = redemptions;
        this.coupons = coupons;
        this.surplusValuation = surplusValuation;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OrderQuoteView quote(
        UUID userId,
        UUID planId,
        BillingPeriod period,
        String couponCode
    ) {
        Instant now = Instant.now(clock);
        UserAccount user = requireUser(userId);
        ServicePlan plan = requirePlan(planId);
        ServicePlanPrice price = requirePrice(plan, period);
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(userId).orElse(null);

        validatePurchasable(user, plan, period, entitlement, now);

        OrderType type = classify(plan, period, entitlement, now);
        Optional<CouponEvaluator.Applied> coupon = couponEvaluator.evaluate(
            couponCode,
            userId,
            planId,
            period,
            price.getAmountMinor(),
            now
        );
        SurplusValuation.Surplus surplus = type == OrderType.UPGRADE
            ? surplusValuation.valueOf(entitlement, now)
            : new SurplusValuation.Surplus(0, List.of());

        OrderPricing.Breakdown breakdown = OrderPricing.compute(
            new OrderPricing.Inputs(
                price.getAmountMinor(),
                coupon.map(CouponEvaluator.Applied::discountMinor).orElse(0L),
                surplus.amountMinor(),
                user.getBalanceMinor()
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
            user.getBalanceMinor()
        );
    }

    @Transactional
    public ServiceOrder place(
        UUID userId,
        UUID planId,
        BillingPeriod period,
        String couponCode
    ) {
        Instant now = Instant.now(clock);
        UserAccount user = requireUser(userId);
        ServicePlan plan = requirePlan(planId);
        ServicePlanPrice price = requirePrice(plan, period);
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(userId).orElse(null);

        if (orders.existsByUserIdAndStatusIn(userId, OPEN_STATUSES)) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_ALREADY_OPEN",
                "An unpaid order is already open. Pay or cancel it first."
            );
        }
        validatePurchasable(user, plan, period, entitlement, now);

        OrderType type = classify(plan, period, entitlement, now);
        Optional<CouponEvaluator.Applied> coupon = couponEvaluator.evaluate(
            couponCode,
            userId,
            planId,
            period,
            price.getAmountMinor(),
            now,
            true
        );
        SurplusValuation.Surplus surplus = type == OrderType.UPGRADE
            ? surplusValuation.valueOf(entitlement, now)
            : new SurplusValuation.Surplus(0, List.of());

        OrderPricing.Breakdown breakdown = OrderPricing.compute(
            new OrderPricing.Inputs(
                price.getAmountMinor(),
                coupon.map(CouponEvaluator.Applied::discountMinor).orElse(0L),
                surplus.amountMinor(),
                user.getBalanceMinor()
            )
        );

        // Take the balance through the entity so a concurrent order cannot
        // spend the same cents twice; the row is version-checked on commit.
        long spent = user.spendBalance(breakdown.balanceAmount(), now);
        if (spent != breakdown.balanceAmount()) {
            throw problem(
                HttpStatus.CONFLICT,
                "BALANCE_CHANGED",
                "The account balance changed. Please review the order again."
            );
        }

        ServiceOrder order = orders.save(ServiceOrder.create(
            nextTradeNo(now),
            user,
            plan,
            period,
            type,
            price.getCurrency(),
            breakdown,
            coupon.map(applied -> applied.coupon().getId()).orElse(null),
            encode(surplus.consumedOrderIds()),
            now
        ));

        coupon.ifPresent(applied -> {
            Coupon redeemed = applied.coupon();
            redeemed.recordRedemption(now);
            redemptions.save(CouponRedemption.create(
                redeemed.getId(),
                userId,
                order.getId(),
                now
            ));
        });

        return order;
    }

    @Transactional
    public ServiceOrder cancel(UUID userId, String tradeNo) {
        ServiceOrder order = orders.findByTradeNo(tradeNo)
            .filter(candidate -> candidate.getUser().getId().equals(userId))
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "The order does not exist"
            ));
        if (!order.isRevocable()) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_NOT_CANCELLABLE",
                "This order can no longer be cancelled"
            );
        }
        Instant now = Instant.now(clock);
        // Give back whatever the order had taken from the balance, and release
        // the coupon use so a cancelled order does not consume an allowance.
        order.getUser().creditBalance(order.getBalanceAmount(), now);
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
        if (entitlement == null || !stillCovered(entitlement, now)) {
            return OrderType.NEW_PURCHASE;
        }
        return entitlement.getPlanId().equals(plan.getId())
            ? OrderType.RENEWAL
            : OrderType.UPGRADE;
    }

    private boolean stillCovered(
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        Instant expiresAt = entitlement.getExpiresAt();
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private void validatePurchasable(
        UserAccount user,
        ServicePlan plan,
        BillingPeriod period,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        if (period == BillingPeriod.RESET_TRAFFIC) {
            if (!plan.isResettable()) {
                throw rejected("This plan does not offer traffic resets");
            }
            if (entitlement == null
                    || !entitlement.getPlanId().equals(plan.getId())) {
                throw rejected(
                    "A traffic reset can only be bought for the active plan"
                );
            }
            return;
        }

        boolean holdsThisPlan = entitlement != null
            && entitlement.getPlanId().equals(plan.getId())
            && stillCovered(entitlement, now);

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
