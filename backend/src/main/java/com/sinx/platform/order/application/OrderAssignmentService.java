package com.sinx.platform.order.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/**
 * Hands an order to another account on an administrator's authority - the
 * correction for a customer who bought under the wrong address.
 *
 * The owner's subscription rides with the order, because the two belong
 * together: entitlements are one per account, so an order that stays behind
 * its subscription split the customer's history across two accounts. Both
 * accounts are row-locked first, the order already is, so two assignments
 * cannot interleave the same pair of rows.
 */
@Service
public class OrderAssignmentService {

    private final ServiceOrderRepository orders;
    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final Clock clock;

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
            entitlements.findByUserId(owner.getId()).orElse(null);
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
            entitlements.moveOwnership(ownerId, targetUserId, now);
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
