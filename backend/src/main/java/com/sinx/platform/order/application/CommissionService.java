package com.sinx.platform.order.application;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.configuration.application.PlatformConfigurationService.CommissionPolicy;
import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.CommissionLog;
import com.sinx.platform.order.domain.CommissionEligibilityPolicy;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.CommissionLogRepository;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Confirms and pays the commission captured on an order. One locked order is
 * paid per transaction; recipient balances, durable logs, and the order's paid
 * marker therefore commit or roll back together.
 */
@Service
public class CommissionService {

    private static final Duration CONFIRMATION_DELAY = Duration.ofHours(72);
    private static final int MAX_LEVELS = 3;
    private final ServiceOrderRepository orders;
    private final UserAccountRepository users;
    private final CommissionLogRepository logs;
    private final PlatformConfigurationService configuration;
    private final Clock clock;
    private final BalanceLedgerService balanceLedger;

    public CommissionService(
        ServiceOrderRepository orders,
        UserAccountRepository users,
        CommissionLogRepository logs,
        PlatformConfigurationService configuration,
        Clock clock,
        BalanceLedgerService balanceLedger
    ) {
        this.orders = orders;
        this.users = users;
        this.logs = logs;
        this.configuration = configuration;
        this.clock = clock;
        this.balanceLedger = balanceLedger;
    }

    public CommissionSummaryView summary(UUID viewerId) {
        UserAccount viewer = users.findById(viewerId).orElseThrow(() ->
            new ApiProblemException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The account does not exist")
        );
        CommissionPolicy policy = configuration.commissionPolicy();
        int commissionType = validCommissionType(viewer.getCommissionType());
        int rate = viewer.getCommissionRate() != null && viewer.getCommissionRate() > 0
            ? viewer.getCommissionRate()
            : configuration.invitationPolicy().commissionPercent();
        long pending = sumProspective(orders.findByInviteUserIdAndCommissionStatusAndStatus(
            viewerId, 0, OrderStatus.COMPLETED
        ), policy);
        long confirmed = sumProspective(
            orders.findByInviteUserIdAndCommissionStatus(viewerId, 1),
            policy
        );
        return new CommissionSummaryView(
            rate,
            commissionType,
            CommissionEligibilityPolicy.isFirstPaymentOnly(
                commissionType,
                policy.firstPaymentOnly()
            ),
            Long.toString(pending),
            Long.toString(confirmed),
            Long.toString(logs.sumEarnedForRecipient(viewerId)),
            policy.autoConfirmEnabled(),
            policy.distributionEnabled(),
            policy.level1Percent(),
            policy.level2Percent(),
            policy.level3Percent(),
            "SITE_BALANCE"
        );
    }

    public CommissionLogPage logs(UUID viewerId, int requestedPage, int requestedLimit) {
        int pageNumber = Math.max(0, Math.min(requestedPage, 1_000_000));
        int limit = requestedLimit <= 0 ? 20 : Math.min(requestedLimit, 100);
        Page<CommissionLog> page = logs.pageForRecipient(
            viewerId,
            PageRequest.of(pageNumber, limit)
        );
        return new CommissionLogPage(
            page.getContent().stream().map(CommissionLogView::from).toList(),
            page.getTotalElements(),
            pageNumber,
            limit
        );
    }

    /** Called by the minute sweep after selecting eligible order numbers. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void confirmAndPay(String tradeNo, boolean mayAutoConfirm) {
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo).orElse(null);
        if (order == null || order.getCommissionStatus() == null
                || order.getCommissionStatus() == 2 || order.getCommissionStatus() == 3) {
            return;
        }
        if (order.getCommissionStatus() == 0) {
            if (!mayAutoConfirm || order.getStatus() != OrderStatus.COMPLETED
                    || order.getUpdatedAt().isAfter(
                        Instant.now(clock).minus(CONFIRMATION_DELAY)
                    )) {
                return;
            }
            order.setCommissionStatus(1, Instant.now(clock));
        }
        if (order.getCommissionStatus() != 1) {
            return;
        }
        payLockedOrder(order, Instant.now(clock));
    }

    @Transactional
    public void updateStatus(String tradeNo, int status) {
        if (status != 0 && status != 1 && status != 3) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "COMMISSION_STATUS_INVALID",
                "Commission status must be pending, confirmed, or invalid"
            );
        }
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo).orElseThrow(() ->
            new ApiProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "The order does not exist")
        );
        if ((status == 0 || status == 1)
                && (order.getInviteUserId() == null || order.getCommissionBalance() <= 0)) {
            throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "COMMISSION_NOT_ELIGIBLE",
                "This order has no payable commission"
            );
        }
        order.setCommissionStatus(status, Instant.now(clock));
    }

    public OrderAdminDetailView adminDetail(String tradeNo) {
        ServiceOrder order = orders.findByTradeNo(tradeNo).orElseThrow(() ->
            new ApiProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "The order does not exist")
        );
        return new OrderAdminDetailView(
            OrderAdminView.from(order),
            logs.findByTradeNoOrderByLevel(tradeNo).stream()
                .map(CommissionAdminLogView::from).toList()
        );
    }

    private void payLockedOrder(ServiceOrder order, Instant now) {
        CommissionPolicy policy = configuration.commissionPolicy();
        List<UUID> originalChain = discoverChain(order.getInviteUserId());
        List<UserAccount> locked = users.findAllForUpdateByIdOrderById(originalChain);
        Map<UUID, UserAccount> byId = new HashMap<>();
        locked.forEach(user -> byId.put(user.getId(), user));
        List<UserAccount> chain = new ArrayList<>();
        UUID cursor = order.getInviteUserId();
        Set<UUID> visited = new LinkedHashSet<>();
        while (cursor != null && chain.size() < MAX_LEVELS && visited.add(cursor)) {
            UserAccount recipient = byId.get(cursor);
            if (recipient == null) {
                if (users.existsById(cursor)) {
                    // An admin reparented a locked account after discovery.
                    // Never pay only the prefix: roll this order back and let
                    // the next minute's retry discover and lock the new chain.
                    throw new IllegalStateException(
                        "The commission inviter chain changed while recipients were locked"
                    );
                }
                break;
            }
            chain.add(recipient);
            cursor = recipient.getInviterUserId();
        }

        long actual = 0;
        for (int index = 0; index < Math.min(chain.size(), MAX_LEVELS); index++) {
            int percent = sharePercent(policy, index);
            long amount = percent <= 0 ? 0 : BigInteger.valueOf(order.getCommissionBalance())
                .multiply(BigInteger.valueOf(percent))
                .divide(BigInteger.valueOf(100))
                .longValueExact();
            if (amount <= 0) {
                // Traversal still advances: an absent/zero L1 share does not
                // suppress a configured L2 or L3 allocation.
                continue;
            }
            UserAccount recipient = chain.get(index);
            balanceLedger.credit(
                recipient.getId(),
                amount,
                BalanceLogType.COMMISSION_CREDIT,
                order.getTradeNo(),
                index + 1,
                now
            );
            logs.save(CommissionLog.create(
                recipient.getId(),
                order.getCommissionBuyerUserId(),
                order.getTradeNo(),
                order.getTotalAmount(),
                order.getCommissionBase(),
                amount,
                index + 1,
                now
            ));
            actual = Math.addExact(actual, amount);
        }
        order.markCommissionPaid(actual);
    }

    /** Reads enough of the live chain to lock every possible beneficiary. */
    private List<UUID> discoverChain(UUID directInviterId) {
        List<UUID> chain = new ArrayList<>();
        UUID cursor = directInviterId;
        while (cursor != null && chain.size() < MAX_LEVELS && !chain.contains(cursor)) {
            chain.add(cursor);
            cursor = users.findInviterUserIdById(cursor);
        }
        return chain.stream().distinct().sorted().toList();
    }

    private long sumProspective(List<ServiceOrder> eligible, CommissionPolicy policy) {
        int percent = policy.distributionEnabled()
            ? usablePercent(policy.level1Percent()) : 100;
        long sum = 0;
        for (ServiceOrder order : eligible) {
            sum = Math.addExact(sum, BigInteger.valueOf(order.getCommissionBalance())
                .multiply(BigInteger.valueOf(percent))
                .divide(BigInteger.valueOf(100)).longValueExact());
        }
        return sum;
    }

    private int sharePercent(CommissionPolicy policy, int index) {
        if (!policy.distributionEnabled()) {
            return index == 0 ? 100 : 0;
        }
        return usablePercent(switch (index) {
            case 0 -> policy.level1Percent();
            case 1 -> policy.level2Percent();
            default -> policy.level3Percent();
        });
    }

    private int usablePercent(int value) {
        return value >= 0 && value <= 100 ? value : 0;
    }

    private int validCommissionType(int value) {
        return value >= 0 && value <= 2 ? value : 0;
    }

    public record OrderAdminDetailView(
        @com.fasterxml.jackson.annotation.JsonUnwrapped OrderAdminView order,
        @com.fasterxml.jackson.annotation.JsonProperty("commission_log")
        List<CommissionAdminLogView> commissionLog
    ) {
        public OrderAdminDetailView {
            commissionLog = List.copyOf(commissionLog);
        }
    }

    public record CommissionAdminLogView(
        UUID id,
        @com.fasterxml.jackson.annotation.JsonProperty("invite_user_id") UUID inviteUserId,
        @com.fasterxml.jackson.annotation.JsonProperty("user_id") UUID userId,
        @com.fasterxml.jackson.annotation.JsonProperty("trade_no") String tradeNo,
        @com.fasterxml.jackson.annotation.JsonProperty("order_amount") long orderAmount,
        @com.fasterxml.jackson.annotation.JsonProperty("commission_base") long commissionBase,
        @com.fasterxml.jackson.annotation.JsonProperty("get_amount") long getAmount,
        int level,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") long createdAt
    ) {
        static CommissionAdminLogView from(CommissionLog log) {
            return new CommissionAdminLogView(
                log.getId(), log.getRecipientUserId(), log.getBuyerUserId(),
                log.getTradeNo(), log.getOrderAmountMinor(),
                log.getCommissionBaseMinor(), log.getAmountMinor(), log.getLevel(),
                log.getCreatedAt().getEpochSecond()
            );
        }
    }
}
