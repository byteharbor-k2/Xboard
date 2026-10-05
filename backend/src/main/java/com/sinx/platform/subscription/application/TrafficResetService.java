package com.sinx.platform.subscription.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.subscription.domain.MonthlyResetSchedule;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.domain.TrafficResetRecord;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import com.sinx.platform.subscription.repository.TrafficResetRecordRepository;

/**
 * Runs the monthly traffic cycle plans promise.
 *
 * A {@code MONTHLY_FROM_ACTIVATION} entitlement renews its allowance on the
 * anniversary of the day it was activated; up to now, only a message the
 * administrator pressed by hand made that happen - a scene of thousands of
 * clicks, once every month, forever. This service does it for them: the
 * boundary comes due, the counters drop to zero, the boundary steps one
 * further, and a record keeps what was spent before the release.
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
    private final TrafficResetRecordRepository records;
    private final com.sinx.platform.identity.repository.UserAccountRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public TrafficResetService(
        SubscriptionEntitlementRepository entitlements,
        TrafficResetRecordRepository records,
        com.sinx.platform.identity.repository.UserAccountRepository users,
        ApplicationEventPublisher events,
        Clock clock,
        org.springframework.transaction.support.TransactionTemplate transactions
    ) {
        this.entitlements = entitlements;
        this.records = records;
        this.users = users;
        this.events = events;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * The scheduled pass: seeds boundaries that were granted without one,
     * then resets every entitlement whose boundary has caught up with today.
     *
     * @return how many entitlements were reset
     */
    public int runMonthlyResets() {
        settleMissingBoundaries();
        int resets = 0;
        for (UUID entitlementId : entitlements.findIdsDueForMonthlyReset(
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            Instant.now(clock)
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
                // A single account that cannot be reset must not strand the
                // increments of everyone else's next cycle behind it.
                log.warn(
                    "Traffic reset skipped for entitlement {}",
                    entitlementId,
                    exception
                );
            }
        }
        return resets;
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
        if (entitlement == null || !isDue(entitlement, now)) {
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
        entitlement.resetTrafficInCycle(
            now,
            MonthlyResetSchedule.followingBoundary(now, now)
        );
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

    private void resetAndRecord(SubscriptionEntitlement entitlement, Instant now) {
        UUID userId = entitlement.getUser().getId();
        long uploadedBytesBefore = entitlement.getUploadedBytes();
        long downloadedBytesBefore = entitlement.getDownloadedBytes();
        entitlement.resetTrafficInCycle(
            now,
            MonthlyResetSchedule.followingBoundary(
                entitlement.getNextResetAt(),
                now
            )
        );
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
        return entitlement.getResetPolicy()
            == TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
            && !entitlement.isTrial()
            && entitlement.getNextResetAt() != null
            && !entitlement.getNextResetAt().isAfter(now)
            && entitlement.getCanceledAt() == null
            && (entitlement.getExpiresAt() == null
                || entitlement.getExpiresAt().isAfter(now));
    }

    private void settleMissingBoundaries() {
        for (UUID entitlementId : entitlements.findIdsWithoutMonthlyBoundary(
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
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
        if (entitlement == null
            || entitlement.isTrial()
            || entitlement.getNextResetAt() != null
            || entitlement.getResetPolicy()
                != TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
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
