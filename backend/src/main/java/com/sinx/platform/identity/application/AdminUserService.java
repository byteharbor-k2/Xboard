package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
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
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.node.application.NodeDeviceStateService;
import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.application.SubscriptionLinkService;
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
 * account closes both doors. It then does the rest of what a real ban means:
 * every still-active device session and refresh token is revoked, and an
 * after-commit event pushes the new user list to the nodes serving the
 * account, so xboard-node drops it at once rather than on its next poll.
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
    private final SubscriptionLinkService subscriptionLinks;
    private final DeviceSessionRepository deviceSessions;
    private final ConfiguredNotificationMailSender mail;
    private final ApplicationEventPublisher events;
    private final java.time.Clock clock;

    public AdminUserService(
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        ServicePlanRepository plans,
        NodeDeviceStateService deviceStates,
        PasswordEncoder passwordEncoder,
        IdentityTokenService tokens,
        SubscriptionLinkService subscriptionLinks,
        DeviceSessionRepository deviceSessions,
        ConfiguredNotificationMailSender mail,
        ApplicationEventPublisher events,
        java.time.Clock clock
    ) {
        this.users = users;
        this.entitlements = entitlements;
        this.plans = plans;
        this.deviceStates = deviceStates;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.subscriptionLinks = subscriptionLinks;
        this.deviceSessions = deviceSessions;
        this.mail = mail;
        this.events = events;
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
     *
     * Clearing the expiry is the one field that "set it to nothing" is an
     * intent, and a null {@code expiresAt} means "leave it alone" - so the
     * intent travels in its own flag, {@code clearExpiry}.
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

        boolean touchesSubscription = update.planId() != null
            || update.transferLimitBytes() != null
            || update.expiresAt() != null
            || Boolean.TRUE.equals(update.clearExpiry());
        if (touchesSubscription) {
            applySubscription(account, update, now);
        }

        return detail(userId);
    }

    /**
     * Suspends or restores an account, on its own because it is its own act.
     *
     * The status change alone stops sign-in and the subscription endpoint, but
     * device sessions would go on minting fresh access tokens until their
     * refresh token expired, and the nodes would carry the account in their
     * user lists until their next poll. A ban therefore also revokes every
     * still-active device session and publishes an after-commit event so the
     * serving nodes get the corrected list pushed to them; lifting the ban
     * revokes nothing, it only pushes so the nodes let the account back in.
     */
    @Transactional
    public AdminUserView setBanned(UUID userId, boolean banned) {
        UserAccount account = requireForUpdate(userId);
        Instant now = clock.instant();
        account.setSuspended(banned, now);
        if (banned) {
            deviceSessions.revokeAllActiveForUser(userId, now);
        }
        SubscriptionEntitlement entitlement =
            entitlements.findByUserId(userId).orElse(null);
        events.publishEvent(new UserSuspensionChangedEvent(
            userId,
            entitlement == null
                || entitlement.getEffectiveServerGroupId() == null
                ? List.of()
                : List.of(entitlement.getEffectiveServerGroupId()),
            banned,
            now
        ));
        return detail(userId);
    }

    /**
     * Retires the subscription link and answers with its replacement, for a
     * link that has been handed around.
     *
     * The answer is the full address, not the bare credential: the operator
     * pastes it back to the customer, and only an address pastes into a client.
     */
    @Transactional
    public String resetSubscriptionToken(UUID userId) {
        UserAccount account = requireForUpdate(userId);
        String token = tokens.newOpaqueToken();
        account.rotateSubscriptionToken(token, clock.instant());
        return subscriptionLinks.subscriptionUrl(token);
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
        Instant now = clock.instant();
        entitlement.resetTraffic(now);
        publishEntitlementChanged(userId, entitlement, now);
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

    /**
     * The whole customer base as CSV, one row per account in the shape of an
     * {@code AdminUserView} row.
     *
     * Cells that could be read as a formula by spreadsheet software are
     * prefixed with an apostrophe, so a harmless remark cannot become a
     * command executed on the operator's desktop the moment the export is
     * opened - the guard the original's own export lacked is applied to
     * every column here.
     */
    public String exportCsv() {
        List<UserAccount> accounts = users.adminSearch(
            "%",
            EnumSet.allOf(UserStatus.class),
            Pageable.unpaged()
        ).getContent();
        Map<UUID, SubscriptionEntitlement> byUser = entitlementsByUser(accounts);
        Map<Long, Integer> devices = deviceCounts(accounts);
        Instant now = clock.instant();
        StringBuilder csv = new StringBuilder()
            .append(HEADER).append('\n');
        for (UserAccount account : accounts) {
            csv.append(csvRow(AdminUserView.of(
                account,
                byUser.get(account.getId()),
                devices.getOrDefault(account.getNodeUserId(), 0),
                now
            ))).append('\n');
        }
        return csv.toString();
    }

    /**
     * Sends one mail an administrator wrote, straight to the account's
     * address. Nothing is stored: the mail template machinery stays in its
     * own section, and a one-off letter is not correspondence history.
     *
     * The delivery is the underlying transport's own business: in log mode
     * it drops the letter to the log and succeeds against no SMTP settings
     * at all, and in smtp mode a failing transport raises its own exception,
     * which the shared error handling logs in full for the operator to read.
     */
    public void sendMail(UUID userId, String subject, String body) {
        if (subject == null || subject.isBlank()) {
            throw problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "MAIL_SUBJECT_REQUIRED",
                "A subject is required to send mail"
            );
        }
        if (body == null || body.isBlank()) {
            throw problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "MAIL_BODY_REQUIRED",
                "A body is required to send mail"
            );
        }
        UserAccount account = require(userId);
        mail.sendHtml(account.getEmail(), subject, body);
    }

    /**
     * Assigns or clears the account's inviter.
     *
     * The relation is the single {@code inviter_user_id} column an
     * invitation-filled registration also writes, so there is nothing to
     * create - only the value to place. Refusals mirror what registration
     * could never produce: an inviter that does not exist, the account
     * naming itself, and a chain that would loop (which the bare foreign key
     * would allow).
     *
     * When an inviter is set, both rows are locked in one deterministic order
     * (UUID-sorted, so concurrent opposite-direction assignments cannot
     * deadlock), and the cycle walk runs over the re-read locked rows with a
     * visited guard, so a chain that already loops refuses instead of
     * spinning.
     */
    @Transactional
    public AdminUserView assignInviter(UUID userId, UUID inviterUserId) {
        Instant now = clock.instant();
        if (inviterUserId == null) {
            // Clearing is a first-class intent: an assignment can be a
            // mistake and the column is nullable for exactly that reason.
            UserAccount account = requireForUpdate(userId);
            account.assignInviter(null, now);
            return detail(userId);
        }
        if (inviterUserId.equals(userId)) {
            throw problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVITER_INVALID",
                "An account cannot be its own inviter"
            );
        }
        if (!users.existsById(inviterUserId)) {
            throw problem(
                HttpStatus.NOT_FOUND,
                "INVITER_NOT_FOUND",
                "The inviter account does not exist"
            );
        }
        // Lock both rows in one order regardless of which direction this
        // call walks the edge, so two concurrent inverse assignments order
        // their locks identically instead of deadlocking.
        boolean subjectFirst = userId.compareTo(inviterUserId) < 0;
        UserAccount firstLocked = requireForUpdate(
            subjectFirst ? userId : inviterUserId
        );
        UserAccount secondLocked = requireForUpdate(
            subjectFirst ? inviterUserId : userId
        );
        UserAccount account = subjectFirst ? firstLocked : secondLocked;
        UserAccount inviter = subjectFirst ? secondLocked : firstLocked;

        Set<UUID> visited = new HashSet<>();
        UUID hop = inviter.getInviterUserId();
        while (hop != null) {
            if (hop.equals(userId)) {
                throw problem(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVITER_CYCLE",
                    "Inviter chains may not form a cycle"
                );
            }
            if (!visited.add(hop)) {
                // The stored chain already loops before this assignment
                // touches it; without the guard the walk below would spin.
                throw problem(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVITER_CYCLE",
                    "Inviter chains may not form a cycle"
                );
            }
            hop = users.findById(hop)
                .map(UserAccount::getInviterUserId)
                .orElse(null);
        }
        account.assignInviter(inviterUserId, now);
        return detail(userId);
    }

    private static final String HEADER =
        "id,node_user_id,email,status,banned,email_verified,remarks,"
            + "speed_limit_mbps,balance,plan_id,plan_name,"
            + "transfer_limit_bytes,used_bytes,online_devices,expires_at,"
            + "last_login_at,created_at";

    private String csvRow(AdminUserView view) {
        return new StringBuilder()
            .append(cell(view.id().toString())).append(',')
            .append(cell(view.nodeUserId() == null
                ? "" : view.nodeUserId().toString())).append(',')
            .append(cell(view.email())).append(',')
            .append(cell(view.status().name())).append(',')
            .append(cell(Boolean.toString(view.banned()))).append(',')
            .append(cell(Boolean.toString(view.emailVerified()))).append(',')
            .append(cell(view.remarks() == null ? "" : view.remarks())).append(',')
            .append(cell(view.speedLimitMbps() == null
                ? "" : view.speedLimitMbps().toString())).append(',')
            .append(cell(Long.toString(view.balance()))).append(',')
            .append(cell(view.planId() == null ? "" : view.planId().toString())).append(',')
            .append(cell(view.planName() == null ? "" : view.planName())).append(',')
            .append(cell(view.transferLimitBytes())).append(',')
            .append(cell(view.usedBytes())).append(',')
            .append(cell(Integer.toString(view.onlineDevices()))).append(',')
            .append(cell(view.expiresAt() == null
                ? "" : view.expiresAt().toString())).append(',')
            .append(cell(view.lastLoginAt() == null
                ? "" : view.lastLoginAt().toString())).append(',')
            .append(cell(Long.toString(view.createdAt())))
            .toString();
    }

    private String cell(String raw) {
        String guarded = raw;
        if (!guarded.isEmpty() && "=+-@".indexOf(guarded.charAt(0)) >= 0) {
            guarded = "'" + guarded;
        }
        if (guarded.indexOf(',') >= 0 || guarded.indexOf('"') >= 0
            || guarded.indexOf('\n') >= 0 || guarded.indexOf('\r') >= 0) {
            return '"' + guarded.replace("\"", "\"\"") + '"';
        }
        return guarded;
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
        // Clearing wins over a supplied value when both arrive; the form
        // cannot produce that, and a contradictory API call should resolve in
        // the direction of the explicit act.
        Instant expiresAt = Boolean.TRUE.equals(update.clearExpiry())
            ? null
            : update.expiresAt() != null
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
        publishEntitlementChanged(account.getId(), entitlement, now);
    }

    /**
     * Announces a correction the nodes can see, the way a paid order already
     * does.
     *
     * The node user list filters on expiry and on whether the traffic is used
     * up, and the wire payload carries the plan's speed limit, so allowance,
     * expiry and plan edits all change what xboard-node serves; resetting
     * traffic can lift an exhausted account back into the list. A remarks,
     * password or per-account speed edit reaches nothing a node reads and
     * stays silent - the node's speed limit comes from the plan behind the
     * entitlement, not from the account's own field.
     */
    private void publishEntitlementChanged(
        UUID userId,
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        Long groupId = entitlement.getEffectiveServerGroupId();
        events.publishEvent(new UserEntitlementChangedEvent(
            userId,
            groupId == null ? List.of() : List.of(groupId),
            now
        ));
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

    /**
     * What an operator may change. A null field is left untouched, except
     * {@code clearExpiry}: a null expiry is not an intent, so the intent to
     * make the account permanent rides this flag instead.
     */
    public record AdminUserUpdate(
        String email,
        String password,
        String remarks,
        Integer speedLimitMbps,
        UUID planId,
        Long transferLimitBytes,
        Instant expiresAt,
        Boolean clearExpiry
    ) {
    }
}
