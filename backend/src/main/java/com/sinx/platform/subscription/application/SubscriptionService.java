package com.sinx.platform.subscription.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

@Service
public class SubscriptionService {

    private final SubscriptionEntitlementRepository entitlementRepository;
    private final UserAccountRepository users;
    private final SubscriptionLinkService links;
    private final IdentityTokenService tokens;
    private final Clock clock;

    public SubscriptionService(
        SubscriptionEntitlementRepository entitlementRepository,
        UserAccountRepository users,
        SubscriptionLinkService links,
        IdentityTokenService tokens,
        Clock clock
    ) {
        this.entitlementRepository = entitlementRepository;
        this.users = users;
        this.links = links;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<SubscriptionEntitlementView> currentEntitlement(
        UUID userId
    ) {
        Instant now = Instant.now(clock);
        return entitlementRepository.findByUserId(userId)
            .map(entitlement ->
                SubscriptionEntitlementView.from(entitlement, now)
            );
    }

    /**
     * The address this account's client should subscribe to.
     *
     * Empty for an account that has gone missing rather than an error: every
     * caller of this is reading a page about an account that was authenticated
     * moments ago, and the only way here is deletion racing that page.
     */
    @Transactional(readOnly = true)
    public Optional<String> subscriptionUrl(UUID userId) {
        return users.findById(userId).map(links::subscriptionUrl);
    }

    /**
     * Retires the current subscription link and answers with the new one.
     *
     * A customer asks for this when a link has leaked - it was pasted into a
     * chat, it is on a screenshot, a shared computer kept it - and the only
     * thing that makes that request worth anything is that the old address stops
     * working immediately. There is nothing to revoke besides the token itself:
     * no session, no config cached anywhere, so writing the new value is the
     * whole of it.
     */
    @Transactional
    public Optional<String> rotateSubscriptionCredential(UUID userId) {
        return users.findById(userId).map(user -> {
            user.rotateSubscriptionToken(
                tokens.newOpaqueToken(),
                Instant.now(clock)
            );
            return links.subscriptionUrl(user);
        });
    }
}
