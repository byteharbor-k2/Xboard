package com.sinx.platform.order.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import com.sinx.platform.subscription.repository.PaidTrafficResetClaimRepository;
import com.sinx.platform.subscription.repository.TrafficResetRecordRepository;

/**
 * Hands an order to another account on an administrator's authority - the
 * correction for a customer who bought under the wrong address.
 *
 * The owner's subscription and settled funding/reset history ride with the
 * assigned order, because the account has one entitlement and its valuation
 * depends on every paid period that built it. Both accounts and the entitlement
 * are row-locked so a reset or another assignment cannot split their ownership.
 */
@Service
public class OrderAssignmentService {

    private final ServiceOrderRepository orders;
    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final Clock clock;
    private ApplicationEventPublisher events;
    private PaidTrafficResetClaimRepository resetClaims;
    private TrafficResetRecordRepository resetRecords;

    public OrderAssignmentService(
        ServiceOrderRepository orders,
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        Clock clock
    ) {
        this.orders = orders;
        this.users = users;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSupportingRepositories(PaidTrafficResetClaimRepository resetClaims,
        TrafficResetRecordRepository resetRecords, ApplicationEventPublisher events) {
        this.resetClaims = resetClaims;
        this.resetRecords = resetRecords;
        this.events = events;
    }

    /**
     * Moves {@code tradeNo}'s order, and the subscription behind its account,
     * to {@code targetUserId}. Assigning an order to its own owner is accepted
     * as the no-op it is.
     */
    @Transactional
    public void assign(String tradeNo, UUID targetUserId) {
        Instant now = Instant.now(clock);
        // The row lock serialises conflicting writes to the order itself:
        // settlement, cancellation and a concurrent assignment all take it.
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo)
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "The order does not exist"
            ));
        UUID ownerId = order.getUser().getId();
        if (ownerId.equals(targetUserId)) {
            return;
        }
        UserAccount target = users.findByIdForUpdate(targetUserId)
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            ));
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw problem(
                HttpStatus.CONFLICT,
                "TARGET_ACCOUNT_SUSPENDED",
                "The target account is suspended"
            );
        }
        // The owner is locked too: whoever grants or changes his subscription
        // next will queue behind this transaction instead of writing an
        // entitlement against a user who is being moved out from under him.
        UserAccount owner = users.findByIdForUpdate(ownerId).orElseThrow(() ->
            problem(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            )
        );

        SubscriptionEntitlement entitlement =
            entitlements.findByUserIdForUpdate(owner.getId()).orElse(null);
        boolean ownerHasOtherOpenOrder = orders.existsByUserIdAndStatusInAndTradeNoNot(
            ownerId, java.util.Set.of(OrderStatus.PENDING, OrderStatus.PROCESSING), tradeNo);
        boolean ownerHasAnyOpenOrder = orders.existsByUserIdAndStatusIn(ownerId,
            java.util.Set.of(OrderStatus.PENDING, OrderStatus.PROCESSING));
        boolean targetHasOpenOrder = orders.existsByUserIdAndStatusIn(targetUserId,
            java.util.Set.of(OrderStatus.PENDING, OrderStatus.PROCESSING));
        // A standalone pending order can still be reassigned as a correction;
        // moving an entitlement with any pending dependency, or moving a second
        // open order into the target, would split its frozen funding snapshot.
        if (order.getStatus() == OrderStatus.PROCESSING
                || targetHasOpenOrder
                || order.isPending() && ownerHasOtherOpenOrder
                || entitlement != null && ownerHasAnyOpenOrder) {
            throw problem(HttpStatus.CONFLICT, "ACCOUNT_HAS_OPEN_ORDERS",
                "Cancel or settle dependent open orders before assigning the subscription.");
        }
        // An entitlement cannot share an account: a target who is already
        // subscribed cannot receive another one.
        if (entitlement != null) {
            if (entitlements.existsByUserId(targetUserId)) {
                throw problem(
                    HttpStatus.CONFLICT,
                    "TARGET_HAS_SUBSCRIPTION",
                    "The target account already holds a subscription"
                );
            }
            Long previousGroupId = entitlement.getEffectiveServerGroupId();
            Long targetGroupId = target.getServerGroupId() != null
                ? target.getServerGroupId() : entitlement.getPlanServerGroupId();
            entitlements.moveOwnership(ownerId, targetUserId, now);
            orders.moveSettledOwnership(ownerId, targetUserId);
            if (resetClaims != null) resetClaims.moveOwnership(ownerId, targetUserId);
            if (resetRecords != null) resetRecords.moveOwnership(ownerId, targetUserId);
            if (events != null) {
                events.publishEvent(new UserEntitlementChangedEvent(ownerId,
                    previousGroupId == null ? java.util.List.of()
                        : java.util.List.of(previousGroupId), now));
                events.publishEvent(new UserEntitlementChangedEvent(targetUserId,
                    targetGroupId == null ? java.util.List.of()
                        : java.util.List.of(targetGroupId), now));
            }
        }

        order.assignTo(target, now);
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }
}
