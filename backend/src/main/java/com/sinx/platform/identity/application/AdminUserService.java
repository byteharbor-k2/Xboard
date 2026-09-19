package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.node.application.NodeDeviceStateService;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/**
 * What an administrator can do to a customer account.
 *
 * The traffic allowance is not stored on the account. It lives on the account's
 * subscription entitlement, which already carries the allowance, the counters
 * and the plan they came from; a second copy on the account would give two
 * answers to "how much is left" and would have to be kept in step on every
 * purchase. Corrections here therefore write the entitlement, not the account.
 *
 * Banning likewise reuses what is already enforced: {@code users.status} is
 * read by the sign-in path and by the subscription endpoint, so suspending an
 * account closes both doors without anything else having to be revoked.
 */
@Service
@Transactional(readOnly = true)
public class AdminUserService {

    /** Enough to fill a screen without the operator paging through noise. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final ServicePlanRepository plans;
    private final NodeDeviceStateService deviceStates;
    private final PasswordEncoder passwordEncoder;
    private final IdentityTokenService tokens;
    private final java.time.Clock clock;

    public AdminUserService(
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        ServicePlanRepository plans,
        NodeDeviceStateService deviceStates,
        PasswordEncoder passwordEncoder,
        IdentityTokenService tokens,
        java.time.Clock clock
    ) {
        this.users = users;
        this.entitlements = entitlements;
        this.plans = plans;
        this.deviceStates = deviceStates;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * One page of accounts, newest first.
     *
     * The device counts are read for the page in one call and joined here
     * rather than per row, so the list stays two queries whatever its size.
     */
    public AdminUserPage list(
        String search,
        UserStatus status,
        Integer page,
        Integer limit
    ) {
        Pageable pageable = PageRequest.of(
            Math.max(page == null ? 0 : page, 0),
            clampSize(limit)
        );
        String pattern = search == null || search.isBlank()
            ? "%"
            : "%" + search.trim().toLowerCase() + "%";
        Set<UserStatus> statuses = status == null
            ? EnumSet.allOf(UserStatus.class)
            : EnumSet.of(status);

        Page<UserAccount> found = users.adminSearch(pattern, statuses, pageable);
        List<UserAccount> accounts = found.getContent();
        Map<UUID, SubscriptionEntitlement> byUser = entitlementsByUser(accounts);
        Map<Long, Integer> devices = deviceCounts(accounts);

        return new AdminUserPage(
            accounts.stream()
                .map(account -> AdminUserView.of(
                    account,
                    byUser.get(account.getId()),
                    devices.getOrDefault(account.getNodeUserId(), 0),
                    clock.instant()
                ))
                .toList(),
            found.getTotalElements(),
            found.getNumber(),
            found.getSize()
        );
    }

    public AdminUserView detail(UUID userId) {
        UserAccount account = require(userId);
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(userId).orElse(null);
        Map<Long, Integer> devices = deviceCounts(List.of(account));
        return AdminUserView.of(
            account,
            entitlement,
            devices.getOrDefault(account.getNodeUserId(), 0),
            clock.instant()
        );
    }

    /**
     * Applies an operator's corrections.
     *
     * Only the fields actually supplied are touched, so a form that edits one
     * thing cannot silently blank another. A new allowance or expiry is written
     * to the entitlement; when the account has none yet, one is created so an
     * operator can grant a subscription by hand.
     */
    @Transactional
    public AdminUserView update(UUID userId, AdminUserUpdate update) {
        UserAccount account = requireForUpdate(userId);
        Instant now = clock.instant();

        if (update.email() != null && !update.email().isBlank()) {
            String email = update.email().trim();
            if (!email.equalsIgnoreCase(account.getEmail())
                    && users.existsByEmail(email)) {
                throw problem(
                    HttpStatus.CONFLICT,
                    "EMAIL_IN_USE",
                    "Another account already uses that email address"
                );
            }
            account.updateEmail(email, now);
        }
        if (update.password() != null && !update.password().isBlank()) {
            if (update.password().length() < 8) {
                throw problem(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_TOO_SHORT",
                    "A password must be at least 8 characters"
                );
            }
            account.changePassword(
                passwordEncoder.encode(update.password()),
                now
            );
        }
        if (update.remarks() != null) {
            account.updateRemarks(update.remarks(), now);
        }
        if (update.speedLimitMbps() != null) {
            account.updateSpeedLimitMbps(
                update.speedLimitMbps() <= 0 ? null : update.speedLimitMbps(),
                now
            );
        }
        if (update.banned() != null) {
            account.setSuspended(update.banned(), now);
        }

        boolean touchesSubscription = update.planId() != null
            || update.transferLimitBytes() != null
            || update.expiresAt() != null;
        if (touchesSubscription) {
            applySubscription(account, update, now);
        }

        return detail(userId);
    }

    /** Suspends or restores an account, on its own because it is its own act. */
    @Transactional
    public AdminUserView setBanned(UUID userId, boolean banned) {
        UserAccount account = requireForUpdate(userId);
        account.setSuspended(banned, clock.instant());
        return detail(userId);
    }

    /**
     * Retires the subscription link and answers with its replacement, for a
     * link that has been handed around.
     */
    @Transactional
    public String resetSubscriptionToken(UUID userId) {
        UserAccount account = requireForUpdate(userId);
        String token = tokens.newOpaqueToken();
        account.rotateSubscriptionToken(token, clock.instant());
        return token;
    }

    /** Zeroes the usage counters, leaving the allowance and expiry alone. */
    @Transactional
    public AdminUserView resetTraffic(UUID userId) {
        SubscriptionEntitlement entitlement = entitlements.findByUserId(userId)
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "SUBSCRIPTION_NOT_FOUND",
                "This account has no subscription to reset"
            ));
        entitlement.resetTraffic(clock.instant());
        return detail(userId);
    }

    /**
     * Deletes an account.
     *
     * Orders keep their history: the schema restricts the reference rather than
     * cascading, so an account with orders cannot be deleted and the operator is
     * told to suspend it instead. Losing a customer's payment record to a
     * mis-click is worse than refusing.
     */
    @Transactional
    public void delete(UUID userId) {
        UserAccount account = require(userId);
        try {
            users.delete(account);
            users.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw problem(
                HttpStatus.CONFLICT,
                "USER_HAS_HISTORY",
                "This account has orders or payments and cannot be deleted. "
                    + "Suspend it instead."
            );
        }
    }

    private void applySubscription(
        UserAccount account,
        AdminUserUpdate update,
        Instant now
    ) {
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(account.getId()).orElse(null);
        ServicePlan plan = update.planId() == null
            ? null
            : plans.findById(update.planId()).orElseThrow(() -> problem(
                HttpStatus.BAD_REQUEST,
                "PLAN_NOT_FOUND",
                "The selected plan does not exist"
            ));

        long allowance = update.transferLimitBytes() != null
            ? update.transferLimitBytes()
            : entitlement != null
                ? entitlement.getTransferLimitBytes()
                : plan != null ? plan.getTransferLimitBytes() : 0L;
        if (allowance <= 0) {
            throw problem(
                HttpStatus.BAD_REQUEST,
                "TRANSFER_LIMIT_REQUIRED",
                "A traffic allowance is required to grant a subscription"
            );
        }
        Instant expiresAt = update.expiresAt() != null
            ? update.expiresAt()
            : entitlement != null ? entitlement.getExpiresAt() : null;

        if (entitlement == null) {
            if (plan == null) {
                throw problem(
                    HttpStatus.BAD_REQUEST,
                    "PLAN_REQUIRED",
                    "A plan is required to grant a subscription"
                );
            }
            entitlement = SubscriptionEntitlement.grant(
                UUID.randomUUID(),
                account,
                plan,
                now,
                expiresAt,
                null,
                now
            );
        }
        entitlement.administrate(plan, allowance, expiresAt, now);
        entitlements.save(entitlement);
    }

    private Map<UUID, SubscriptionEntitlement> entitlementsByUser(
        List<UserAccount> accounts
    ) {
        if (accounts.isEmpty()) {
            return Map.of();
        }
        Collection<UUID> ids = accounts.stream()
            .map(UserAccount::getId)
            .toList();
        Map<UUID, SubscriptionEntitlement> byUser = new LinkedHashMap<>();
        for (SubscriptionEntitlement entitlement : entitlements.findByUserIdIn(ids)) {
            byUser.put(entitlement.getUser().getId(), entitlement);
        }
        return byUser;
    }

    private Map<Long, Integer> deviceCounts(List<UserAccount> accounts) {
        Set<Long> nodeUserIds = new java.util.LinkedHashSet<>();
        for (UserAccount account : accounts) {
            if (account.getNodeUserId() != null) {
                nodeUserIds.add(account.getNodeUserId());
            }
        }
        if (nodeUserIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        deviceStates.snapshotForUsers(nodeUserIds, clock.instant())
            .forEach((nodeUserId, addresses) -> {
                if (addresses != null && !addresses.isEmpty()) {
                    counts.put(nodeUserId, addresses.size());
                }
            });
        return counts;
    }

    private int clampSize(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private UserAccount require(UUID userId) {
        return users.findWithRolesById(userId).orElseThrow(() ->
            problem(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            )
        );
    }

    private UserAccount requireForUpdate(UUID userId) {
        return users.findByIdForUpdate(userId).orElseThrow(() ->
            problem(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            )
        );
    }

    private static ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }

    /** What an operator may change. A null field is left untouched. */
    public record AdminUserUpdate(
        String email,
        String password,
        String remarks,
        Integer speedLimitMbps,
        Boolean banned,
        UUID planId,
        Long transferLimitBytes,
        Instant expiresAt
    ) {
    }
}
