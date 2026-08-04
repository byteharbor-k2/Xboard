package com.sinx.platform.subscription.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

public interface SubscriptionEntitlementRepository
    extends JpaRepository<SubscriptionEntitlement, UUID> {

    @EntityGraph(attributePaths = "plan")
    Optional<SubscriptionEntitlement> findByUserId(UUID userId);

    @Query("""
        select count(entitlement)
        from SubscriptionEntitlement entitlement
        where entitlement.plan.id = :planId
          and entitlement.canceledAt is null
          and (
            entitlement.expiresAt is null
            or entitlement.expiresAt > :now
          )
        """)
    long countActiveForPlan(
        @Param("planId") UUID planId,
        @Param("now") Instant now
    );

    @Query("""
        select count(entitlement)
        from SubscriptionEntitlement entitlement
        where entitlement.plan.id = :planId
        """)
    long countForPlan(@Param("planId") UUID planId);
}
