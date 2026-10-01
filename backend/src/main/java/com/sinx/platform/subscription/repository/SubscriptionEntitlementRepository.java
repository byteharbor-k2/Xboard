package com.sinx.platform.subscription.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

import jakarta.persistence.LockModeType;

public interface SubscriptionEntitlementRepository
    extends JpaRepository<SubscriptionEntitlement, UUID> {

    /**
     * The account's entitlement, with everything needed to judge and serve it.
     *
     * The user is fetched because neither the account's own availability nor the
     * group it resolves to is readable without it, and this panel runs with
     * {@code open-in-view} off - a caller that loaded this entity in one
     * transaction and read it in another would otherwise get a proxy that cannot
     * be initialised.
     */
    @EntityGraph(attributePaths = {"user", "plan"})
    Optional<SubscriptionEntitlement> findByUserId(UUID userId);

    /**
     * The entitlements behind a page of accounts, in one query. The admin list
     * needs a plan name and a usage figure per row, and asking per row would be
     * one query per account on every page.
     */
    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("""
        select entitlement from SubscriptionEntitlement entitlement
        where entitlement.user.id in :userIds
        """)
    List<SubscriptionEntitlement> findByUserIdIn(
        @Param("userIds") Collection<UUID> userIds
    );

    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("select entitlement from SubscriptionEntitlement entitlement")
    List<SubscriptionEntitlement> findAllWithUserAndPlan();

    /**
     * The accounts one node's user list is built from, filtered in SQL instead
     * of streamed across the whole table.
     *
     * Copies the eligibility NodeProtocolService applies when building the
     * node's user list: the effective group is the account override when
     * present and the plan's group otherwise, the account is active and
     * addressable by nodes, and the entitlement is not cancelled, not expired
     * and not exhausted at the instant given. The exhausted predicate is
     * written so it matches the entity's saturated {@code usedBytes()} exactly:
     * {@code uploaded + downloaded < limit} holds true if and only if both
     * single conditions hold. Ordered by node user id, which the in-memory
     * caller used to sort by.
     */
    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("""
        select entitlement from SubscriptionEntitlement entitlement
        where coalesce(entitlement.user.serverGroupId, entitlement.plan.serverGroupId)
              in :groupIds
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
        order by entitlement.user.nodeUserId asc
        """)
    List<SubscriptionEntitlement> findActiveForServerGroups(
        @Param("groupIds") Collection<Long> groupIds,
        @Param("activeStatus") UserStatus activeStatus,
        @Param("now") Instant now
    );

    /**
     * The entitlements whose account the daily reminder sweep must judge.
     *
     * This is a candidate pre-filter, deliberately wider than what gets sent:
     * the two reminders have different predicates, so they are OR-ed here and
     * the sender re-judges each one per account with the entity's own exact
     * arithmetic. The expiry branch is fully exact - an expiry within the
     * next day, not cancelled. The traffic branch compares against 80% of the
     * allowance, in floating point because a bigint sum of two counters could
     * overflow where the entity's saturated {@code usedBytes()} would not; the
     * double is exact far beyond any real allowance, and the exhaustion side
     * ({@code used >= limit}, no reminder) is re-checked exactly in the
     * sender. Only active accounts with at least one reminder switch on enter
     * the run.
     */
    @EntityGraph(attributePaths = {"user"})
    @Query("""
        select entitlement from SubscriptionEntitlement entitlement
        where entitlement.user.status = :activeStatus
          and (
            (
              entitlement.user.remindExpire = true
              and entitlement.canceledAt is null
              and entitlement.expiresAt > :now
              and entitlement.expiresAt < :expiresWithin
            )
            or
            (
              entitlement.user.remindTraffic = true
              and entitlement.transferLimitBytes > 0
              and cast(entitlement.uploadedBytes as Double)
                  + cast(entitlement.downloadedBytes as Double)
                  >= 0.8 * cast(entitlement.transferLimitBytes as Double)
            )
          )
        order by entitlement.user.id asc
        """)
    List<SubscriptionEntitlement> findReminderCandidates(
        @Param("activeStatus") UserStatus activeStatus,
        @Param("now") Instant now,
        @Param("expiresWithin") Instant expiresWithin
    );

    /** Whether an account holds the entitlement at all. */
    boolean existsByUserId(UUID userId);

    /** The account's entitlement for an update; safer on weigh-ins than reads. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("""
        select entitlement from SubscriptionEntitlement entitlement
        where entitlement.user.id = :userId
        """)
    Optional<SubscriptionEntitlement> findByUserIdForUpdate(
        @Param("userId") UUID userId
    );

    /**
     * The activations whose monthly cycle has caught up with today.
     *
     * A candidate pre-filter, deliberately id-only: the run locks each row
     * one at a time and re-judges the entity's own state under the lock, so
     * an administrator resetting the same account between the query and the
     * lock cannot bring the counters down twice.
     */
    @Query("""
        select entitlement.id from SubscriptionEntitlement entitlement
        where entitlement.resetPolicy = :policy
          and entitlement.canceledAt is null
          and (entitlement.expiresAt is null or entitlement.expiresAt > :now)
          and entitlement.nextResetAt is not null
          and entitlement.nextResetAt <= :now
        """)
    List<UUID> findIdsDueForMonthlyReset(
        @Param("policy") TrafficResetPolicy policy,
        @Param("now") Instant now
    );

    /**
     * The monthly entitlements granted before a cycle existed, whose
     * boundary still reads NULL. Only these, and only the ones with no other
     * reset policy: a traffic package and a NEVER plan live without a cycle.
     */
    @Query("""
        select entitlement.id from SubscriptionEntitlement entitlement
        where entitlement.resetPolicy = :policy
          and entitlement.canceledAt is null
          and entitlement.nextResetAt is null
        """)
    List<UUID> findIdsWithoutMonthlyBoundary(
        @Param("policy") TrafficResetPolicy policy
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("""
        select entitlement from SubscriptionEntitlement entitlement
        where entitlement.id = :id
        """)
    Optional<SubscriptionEntitlement> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Repoints the account's entitlement to another account, for an
     * administrator handing a customer's order - and, with it, his
     * subscription - to a different address. A direct update because the
     * entitlement is meant to keep everything but its owner; the caller locks
     * both account rows before invoking it.
     */
    @Modifying
    @Query(value = """
        update subscription_entitlements
        set user_id = :toUserId, updated_at = :now
        where user_id = :fromUserId
        """, nativeQuery = true)
    int moveOwnership(
        @Param("fromUserId") UUID fromUserId,
        @Param("toUserId") UUID toUserId,
        @Param("now") Instant now
    );

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
