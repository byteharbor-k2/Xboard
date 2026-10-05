package com.sinx.platform.subscription.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/**
 * Rotates both credentials that a customer may have shared: the panel's
 * subscription URL token and the UUID used by proxy nodes. The user's account
 * id and every relationship keyed by it remain unchanged.
 */
@Service
public class SubscriptionCredentialRotationService {

    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final IdentityTokenService tokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SubscriptionCredentialRotationService(
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        IdentityTokenService tokens,
        ApplicationEventPublisher events,
        Clock clock
    ) {
        this.users = users;
        this.entitlements = entitlements;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Rotates credentials under the account row lock and announces the node
     * identity change for synchronization after the transaction commits.
     */
    @Transactional
    public Optional<UserAccount> rotate(UUID userId) {
        return users.findByIdForUpdate(userId).map(user -> {
            Instant now = clock.instant();
            user.rotateSubscriptionToken(tokens.newOpaqueToken(), now);
            user.rotateProxyUuid(UUID.randomUUID(), now);

            List<Long> groupIds = entitlements.findByUserId(userId)
                .map(entitlement -> entitlement.getEffectiveServerGroupId())
                .filter(java.util.Objects::nonNull)
                .map(List::of)
                .orElseGet(List::of);
            events.publishEvent(new UserEntitlementChangedEvent(
                userId,
                groupIds,
                now
            ));
            return user;
        });
    }
}
