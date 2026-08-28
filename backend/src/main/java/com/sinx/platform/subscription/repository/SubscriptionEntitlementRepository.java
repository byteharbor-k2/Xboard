package com.sinx.platform.subscription.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

import jakarta.persistence.LockModeType;

public interface SubscriptionEntitlementRepository
    extends JpaRepository<SubscriptionEntitlement, UUID> {

    @EntityGraph(attributePaths = "plan")
    Optional<SubscriptionEntitlement> findByUserId(UUID userId);

    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("select entitlement from SubscriptionEntitlement entitlement")
    List<SubscriptionEntitlement> findAllWithUserAndPlan();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("""
        select entitlement
        from SubscriptionEntitlement entitlement
        where entitlement.user.nodeUserId = :nodeUserId
        """)
    Optional<SubscriptionEntitlement> findForTrafficReport(
        @Param("nodeUserId") Long nodeUserId
    );

    @Query("""
        select count(entitlement)
        from SubscriptionEntitlement entitlement
        where entitlement.plan.id = :planId
          and entitlement.canceledAt is null
          and (
            entitlement.expiresAt is null
            or entitlement.expiresAt > :now
          )
          and entitlement.uploadedBytes < entitlement.transferLimitBytes
          and entitlement.downloadedBytes
              < entitlement.transferLimitBytes - entitlement.uploadedBytes
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

    /**
     * Counts the users an access group actually serves. Node delivery resolves a
     * user's group from their entitlement - the user override when present, the
     * plan's group otherwise - so counting users.serverGroupId alone reports
     * zero for everyone who reached the group through a plan. Mirrors the
     * eligibility rules applied when building a node's user list.
     */
    @Query("""
        select count(entitlement)
        from SubscriptionEntitlement entitlement
        where coalesce(entitlement.user.serverGroupId, entitlement.plan.serverGroupId)
              = :groupId
          and entitlement.user.status = :activeStatus
          and entitlement.user.nodeUserId is not null
          and entitlement.canceledAt is null
          and (
            entitlement.expiresAt is null
            or entitlement.expiresAt > :now
          )
          and entitlement.uploadedBytes < entitlement.transferLimitBytes
          and entitlement.downloadedBytes
              < entitlement.transferLimitBytes - entitlement.uploadedBytes
        """)
    long countActiveForServerGroup(
        @Param("groupId") Long groupId,
        @Param("now") Instant now,
        @Param("activeStatus") UserStatus activeStatus
    );
}
