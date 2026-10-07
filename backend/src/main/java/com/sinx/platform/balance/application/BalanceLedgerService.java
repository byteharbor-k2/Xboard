package com.sinx.platform.balance.application;

import java.time.Instant;
import java.time.Clock;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.balance.domain.BalanceLog;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.balance.repository.BalanceLogRepository;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/** Mutates a locked account and records its cash movement in the same transaction. */
@Service
public class BalanceLedgerService {

    private final UserAccountRepository users;
    private final BalanceLogRepository logs;
    private final Clock clock;

    public BalanceLedgerService(
        UserAccountRepository users,
        BalanceLogRepository logs,
        Clock clock
    ) {
        this.users = users;
        this.logs = logs;
        this.clock = clock;
    }

    /**
     * Debits the full amount; an order must never be recorded as partly
     * balance-paid. The legacy caller timestamp is deliberately ignored: cash
     * posting time is captured after the locked account row is acquired.
     */
    @Transactional
    public void debit(UUID userId, long amountMinor, String tradeNo, Instant now) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("A balance debit cannot be negative");
        }
        if (amountMinor == 0) {
            return;
        }
        UserAccount user = lockUser(userId);
        Instant postedAt = Instant.now(clock);
        long before = user.getBalanceMinor();
        long spent = user.spendBalance(amountMinor, postedAt);
        if (spent != amountMinor) {
            throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "BALANCE_CHANGED",
                "The account balance changed. Please review the order again."
            );
        }
        save(user, BalanceLogType.ORDER_PAYMENT, -amountMinor, before, tradeNo, null, null, postedAt);
    }

    /**
     * Credits refund, surplus or commission cash to its locked beneficiary.
     * The legacy caller timestamp is ignored for the same after-lock posting
     * time rule as {@link #debit}.
     */
    @Transactional
    public void credit(
        UUID userId,
        long amountMinor,
        BalanceLogType type,
        String tradeNo,
        Integer commissionLevel,
        Instant now
    ) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("A balance credit cannot be negative");
        }
        if (amountMinor == 0) {
            return;
        }
        if (type != BalanceLogType.ORDER_REFUND
                && type != BalanceLogType.SURPLUS_CREDIT
                && type != BalanceLogType.COMMISSION_CREDIT) {
            throw new IllegalArgumentException("The balance credit type is not a cash inflow");
        }
        UserAccount user = lockUser(userId);
        Instant postedAt = Instant.now(clock);
        long before = user.getBalanceMinor();
        Math.addExact(before, amountMinor);
        user.creditBalance(amountMinor, postedAt);
        save(user, type, amountMinor, before, tradeNo, commissionLevel, null, postedAt);
    }

    /** Sets ordinary cash to an absolute target and records only a real movement. */
    @Transactional
    public void setBalanceTarget(UUID userId, long targetMinor, String note) {
        if (targetMinor < 0) {
            throw new IllegalArgumentException("A balance target cannot be negative");
        }
        UserAccount user = lockUser(userId);
        long before = user.getBalanceMinor();
        if (before == targetMinor) {
            return;
        }
        Instant postedAt = Instant.now(clock);
        long delta = targetMinor - before;
        user.setBalanceTarget(targetMinor, postedAt);
        save(user, BalanceLogType.ADMIN_ADJUSTMENT, delta, before, null, null, note, postedAt);
    }

    /** Deletes only the migration anchor while the same user's row is locked. */
    @Transactional
    public void discardOpeningAnchorForAccountDeletion(UUID userId) {
        lockUser(userId);
        logs.deleteOpeningAnchorForUser(userId, BalanceLogType.OPENING_BALANCE);
    }

    private UserAccount lockUser(UUID userId) {
        return users.findByIdForUpdate(userId).orElseThrow(() ->
            new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND",
                "The account does not exist"
            )
        );
    }

    private void save(
        UserAccount user,
        BalanceLogType type,
        long amountMinor,
        long before,
        String tradeNo,
        Integer commissionLevel,
        String note,
        Instant now
    ) {
        long after = user.getBalanceMinor();
        if (Math.addExact(before, amountMinor) != after) {
            throw new IllegalStateException("The account balance did not match its ledger mutation");
        }
        logs.save(BalanceLog.create(
            user.getId(), type, amountMinor, after, "CNY", tradeNo, commissionLevel, note, now
        ));
    }
}
