package com.sinx.platform.subscription.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicyResolver;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.subscription.domain.MonthlyResetSchedule;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.domain.TrafficResetRecord;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import com.sinx.platform.subscription.repository.TrafficResetRecordRepository;

/**
 * Runs the traffic reset cycles the plans promise.
 *
 * Each of the four periodic methods is scheduled on its promised calendar or
 * activation boundary. When one comes due, the counters drop to zero, its
 * boundary advances beyond now, and a record keeps what was spent before the
 * release. A late sweep catches up in one reset rather than replaying missed
 * cycles or crediting them repeatedly.
 *
 * Every reset is its own transaction and its own row lock, because a reset is
 * nothing to fail in bulk: one account in trouble must not strand the others,
 * and two callers reaching one account - a scheduled run racing an operator's
 * button - must see each other's work. The pessimistic lock answers the race:
 * whoever waits in line second finds the boundary already pushed, so it
 * judges the account not due and walks away.
 */
@Service
public class TrafficResetService {

    private static final Logger log =
        LoggerFactory.getLogger(TrafficResetService.class);

    private final SubscriptionEntitlementRepository entitlements;
    private final ServicePlanRepository plans;
    private final PlatformConfigurationService configuration;
    private final TrafficResetRecordRepository records;
    private final com.sinx.platform.identity.repository.UserAccountRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    public TrafficResetService(
        SubscriptionEntitlementRepository entitlements,
        ServicePlanRepository plans,
        PlatformConfigurationService configuration,
        TrafficResetRecordRepository records,
        com.sinx.platform.identity.repository.UserAccountRepository users,
        ApplicationEventPublisher events,
        Clock clock,
        org.springframework.transaction.support.TransactionTemplate transactions
    ) {
        this.entitlements = entitlements;
        this.plans = plans;
        this.configuration = configuration;
        this.records = records;
        this.users = users;
        this.events = events;
        this.clock = clock;
        this.transactions = transactions;
    }

    /** Compatibility constructor for focused tests of reset-record behavior. */
    public TrafficResetService(
        SubscriptionEntitlementRepository entitlements,
        TrafficResetRecordRepository records,
        com.sinx.platform.identity.repository.UserAccountRepository users,
        ApplicationEventPublisher events,
        Clock clock,
        org.springframework.transaction.support.TransactionTemplate transactions
    ) {
        this(entitlements, null, null, records, users, events, clock, transactions);
    }

    /**
     * The scheduled pass: seeds boundaries that were granted without one,
     * then resets every periodic entitlement whose boundary has come due.
     *
     * @return how many entitlements were reset
     */
    public int runMonthlyResets() {
        settleMissingBoundaries();
        int resets = 0;
        Instant now = Instant.now(clock);
        for (TrafficResetPolicy policy : TrafficResetPolicy.values()) {
            if (policy == TrafficResetPolicy.NEVER) {
                continue;
            }
            for (UUID entitlementId : entitlements.findIdsDueForMonthlyReset(
                policy, now
            )) {
                try {
                    // One transaction per entitlement: a row that cannot be
                    // reset leaves the others untouched.
                    if (Boolean.TRUE.equals(transactions.execute(
                        status -> resetDueOnce(entitlementId)
                    ))) {
                        resets++;
                    }
                } catch (RuntimeException exception) {
                    log.warn(
                        "Traffic reset skipped for entitlement {}",
                        entitlementId,
                        exception
                    );
                }
            }
        }
        return resets;
    }

    /** Resolve a plan into the policy copied onto an entitlement snapshot. */
    public TrafficResetPolicy effectivePolicy(ServicePlan plan) {
        return TrafficResetPolicyResolver.effective(
            plan,
            configuration == null
                ? TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
                : configuration.globalTrafficResetPolicy()
        );
    }

    /** Reconcile snapshots after the global default or an individual plan changed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int synchronizePoliciesForPlan(UUID planId) {
        return synchronizePolicyCandidates(entitlements.findIdsForPlan(planId));
    }

    /** Recheck one committed entitlement against the current source of policy. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean synchronizePolicyForUser(UUID userId) {
        SubscriptionEntitlement entitlement = entitlements
            .findByUserIdForUpdate(userId)
            .orElse(null);
        if (entitlement == null) {
            return false;
        }
        return synchronizePolicy(entitlement, Instant.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int synchronizeInheritedPolicies() {
        return synchronizePolicyCandidates(
            entitlements.findIdsInheritingResetPolicy()
        );
    }

    private int synchronizePolicyCandidates(List<UUID> ids) {
        int updated = 0;
        for (UUID id : ids.stream().sorted().toList()) {
            SubscriptionEntitlement entitlement = entitlements.findByIdForUpdate(id)
                .orElse(null);
            if (entitlement == null) {
                continue;
            }
            if (synchronizePolicy(entitlement, Instant.now(clock))) {
                updated++;
            }
        }
        return updated;
    }

    private boolean synchronizePolicy(
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        ServicePlan plan = entitlementPlan(entitlement);
        return plan != null
            && entitlement.synchronizeResetPolicy(effectivePolicy(plan), now);
    }

    private ServicePlan entitlementPlan(SubscriptionEntitlement entitlement) {
        return plans.findById(entitlement.getPlanId()).orElse(null);
    }

    /**
     * One due reset, on one locked row, in one transaction. The row is
     * re-judged under the lock: a racier caller - the button pressed while
     * the sweep stood on the same account - may already have pushed the
     * boundary by the time the lock answers, and the second caller then
     * finds nothing moot here.
     *
     * Called through the template wrapper and freely by callers that need it
     * as one transaction of its own.
     */
    @Transactional
    public boolean resetDueEntitlement(UUID entitlementId) {
        return resetDueOnce(entitlementId);
    }

    private boolean resetDueOnce(UUID entitlementId) {
        SubscriptionEntitlement entitlement =
            entitlements.findByIdForUpdate(entitlementId).orElse(null);
        Instant now = Instant.now(clock);
        if (entitlement == null) {
            return false;
        }
        synchronizePolicy(entitlement, now);
        if (!isDue(entitlement, now)) {
            return false;
        }
        resetAndRecord(entitlement, now);
        return true;
    }

    /**
     * The administrator's reset button, dressed with what the cycle needs:
     * a history row and a boundary re-anchored at this reset.
     *
     * The reasons an operator resets by hand are the accounts the rhythm did
     * not serve; re-anchoring gives each of them a full month from the reset
     * they just pressed, instead of the old anniversary - and the cycle
     * carries on from the new anchor. The caller keeps announcing the change
     * to the nodes itself, as it already does.
     *
     * Must run inside the caller's transaction, on the entitlement row it
     * holds under lock, with the counters still standing: the "before"
     * figures of the record are read here, before the reset zeroes them.
     */
    @Transactional
    public void recordManualReset(
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        UUID userId = entitlement.getUser().getId();
        long uploadedBytesBefore = entitlement.getUploadedBytes();
        long downloadedBytesBefore = entitlement.getDownloadedBytes();
        boolean active = entitlement.getCanceledAt() == null
            && (entitlement.getExpiresAt() == null
                || entitlement.getExpiresAt().isAfter(now));
        Instant nextBoundary = active
            ? MonthlyResetSchedule.nextBoundary(
                entitlement.getResetPolicy(), now, now
            )
            : null;
        entitlement.resetTrafficInCycle(now, nextBoundary);
        records.save(TrafficResetRecord.create(
            userId,
            entitlement.getId(),
            now,
            uploadedBytesBefore,
            downloadedBytesBefore,
            now
        ));
        entitlements.save(entitlement);
    }

    /** Records a paid viewer reset without altering the scheduled cycle anchor. */
    @Transactional
    public void recordPaidReset(SubscriptionEntitlement entitlement, Instant now,
        Instant nextBoundary) {
        UUID userId = entitlement.getUser().getId();
        long uploadedBytesBefore = entitlement.getUploadedBytes();
        long downloadedBytesBefore = entitlement.getDownloadedBytes();
        entitlement.resetTrafficWithoutReanchoring(now, nextBoundary);
        records.save(TrafficResetRecord.create(userId, entitlement.getId(), now,
            uploadedBytesBefore, downloadedBytesBefore, now));
        entitlements.save(entitlement);
    }

    private void resetAndRecord(SubscriptionEntitlement entitlement, Instant now) {
        UUID userId = entitlement.getUser().getId();
        long uploadedBytesBefore = entitlement.getUploadedBytes();
        long downloadedBytesBefore = entitlement.getDownloadedBytes();
        entitlement.resetTrafficInCycle(now,
            MonthlyResetSchedule.followingBoundary(
                entitlement.getResetPolicy(), entitlement.getNextResetAt(), now
            ));
        records.save(TrafficResetRecord.create(
            userId,
            entitlement.getId(),
            now,
            uploadedBytesBefore,
            downloadedBytesBefore,
            now
        ));
        entitlements.save(entitlement);
        Long groupId = entitlement.getEffectiveServerGroupId();
        events.publishEvent(new UserEntitlementChangedEvent(
            userId,
            groupId == null ? List.of() : List.of(groupId),
            now
        ));
    }

    /** Judged under the row lock against the entity's own arithmetic. */
    private boolean isDue(SubscriptionEntitlement entitlement, Instant now) {
        return entitlement.getResetPolicy() != TrafficResetPolicy.NEVER
            && entitlement.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.SUBSCRIPTION
            && !entitlement.isTrial()
            && entitlement.getNextResetAt() != null
            && !entitlement.getNextResetAt().isAfter(now)
            && entitlement.getCanceledAt() == null
            && (entitlement.getExpiresAt() == null
                || entitlement.getExpiresAt().isAfter(now));
    }

    private void settleMissingBoundaries() {
        for (TrafficResetPolicy policy : TrafficResetPolicy.values()) {
            if (policy == TrafficResetPolicy.NEVER) {
                continue;
            }
            for (UUID entitlementId : entitlements.findIdsWithoutMonthlyBoundary(
                policy, Instant.now(clock)
            )) {
                try {
                    transactions.executeWithoutResult(status ->
                        settleMissingBoundary(entitlementId)
                    );
                } catch (RuntimeException exception) {
                    log.warn(
                        "Traffic reset boundary seeding skipped for {}",
                        entitlementId,
                        exception
                    );
                }
            }
        }
    }

    /**
     * Gives one entitlement granted without a boundary its chain anchor.
     *
     * Only entitlements activated before the cycle existed lack one: their
     * anchor becomes their activation instant, and the first due pass walks
     * the chain from there. Nothing is reset and nothing is recorded here -
     * the walk on the due pass is what catches the cycle up.
     */
    @Transactional
    public void settleMissingBoundary(UUID entitlementId) {
        SubscriptionEntitlement entitlement =
            entitlements.findByIdForUpdate(entitlementId).orElse(null);
        if (entitlement == null) {
            return;
        }
        Instant now = Instant.now(clock);
        synchronizePolicy(entitlement, now);
        if (entitlement.isTrial()
            || entitlement.getPlanType()
                != com.sinx.platform.catalog.domain.PlanType.SUBSCRIPTION
            || entitlement.getNextResetAt() != null
            || entitlement.getResetPolicy() == TrafficResetPolicy.NEVER
        ) {
            return;
        }
        seedPeriodicBoundary(entitlement);
    }

    /** The seeding transaction body itself; the caller wraps it. */
    private void seedPeriodicBoundary(SubscriptionEntitlement entitlement) {
        entitlement.seedPeriodicBoundary();
        entitlements.save(entitlement);
    }

    /**
     * The reset ledger as the admin surface shows it: the newest resets
     * first, optionally narrowed to one account.
     *
     * The rows name no plan and read no name back, so the page resolves the
     * account's who from the accounts the resets belong to; the user filter
     * is exact - narrowing a ledger to a mistyped id answers nothing, and
     * the operator has a menu of ids on the user list beside it.
     */
    @Transactional(readOnly = true)
    public List<AdminTrafficResetView> adminRecords(UUID userId, int limit) {
        Pageable page = PageRequest.of(0, Math.clamp(limit, 1, 1000));
        List<TrafficResetRecord> ledger = userId == null
            ? records.findByOrderByResetAtDesc(page)
            : records.findByUserIdOrderByResetAtDesc(userId, page);
        Map<UUID, AccountIdentity> identities = identitiesFor(ledger);
        return ledger.stream()
            .map(record -> AdminTrafficResetView.from(record, identities))
            .toList();
    }

    record AccountIdentity(String email, Long nodeUserId) {
    }

    private Map<UUID, AccountIdentity> identitiesFor(
        List<TrafficResetRecord> ledger
    ) {
        Map<UUID, AccountIdentity> identities = new HashMap<>();
        users.findAllById(
            ledger.stream().map(TrafficResetRecord::getUserId).distinct().toList()
        ).forEach(account -> identities.put(
            account.getId(),
            new AccountIdentity(account.getEmail(), account.getNodeUserId())
        ));
        return identities;
    }
}
