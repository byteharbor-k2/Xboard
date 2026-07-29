package com.sinx.platform.subscription.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

@Service
public class SubscriptionService {

    private final SubscriptionEntitlementRepository entitlementRepository;
    private final Clock clock;

    public SubscriptionService(
        SubscriptionEntitlementRepository entitlementRepository,
        Clock clock
    ) {
        this.entitlementRepository = entitlementRepository;
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
}
