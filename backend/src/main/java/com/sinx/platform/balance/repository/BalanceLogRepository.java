package com.sinx.platform.balance.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.balance.domain.BalanceLog;
import com.sinx.platform.balance.domain.BalanceLogType;

public interface BalanceLogRepository extends JpaRepository<BalanceLog, UUID> {

    @Query(
        value = "select log from BalanceLog log where log.userId = :userId "
            + "order by log.ledgerSequence desc",
        countQuery = "select count(log) from BalanceLog log where log.userId = :userId"
    )
    Page<BalanceLog> pageForUser(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
        select serviceOrder.tradeNo from ServiceOrder serviceOrder
        where serviceOrder.user.id = :userId
          and serviceOrder.tradeNo in :tradeNumbers
        """)
    List<String> findTradeNumbersOwnedByUser(
        @Param("userId") UUID userId,
        @Param("tradeNumbers") List<String> tradeNumbers
    );

    /** Removes only the cutover anchor after the owning user row has been locked for deletion. */
    @Modifying
    @Query("delete from BalanceLog log where log.userId = :userId and log.type = :type")
    int deleteOpeningAnchorForUser(
        @Param("userId") UUID userId,
        @Param("type") BalanceLogType type
    );

    /** A single statement gives the displayed cash and reconciliation totals one database snapshot. */
    @Query(value = """
        select u.balance_minor as \"balanceMinor\",
               coalesce(sum(case when b.type = 'OPENING_BALANCE'
                                 then b.amount_minor else 0 end), 0)
                   as \"openingBalanceMinor\",
               coalesce(sum(case when b.type <> 'OPENING_BALANCE'
                                      and b.amount_minor > 0
                                 then b.amount_minor else 0 end), 0)
                   as \"totalCreditsMinor\",
               coalesce(sum(case when b.type <> 'OPENING_BALANCE'
                                      and b.amount_minor < 0
                                 then -b.amount_minor else 0 end), 0)
                   as \"totalDebitsMinor\",
               min(b.created_at) as \"recordedSince\"
        from users u
        left join balance_logs b on b.user_id = u.id
        where u.id = :userId
        group by u.id, u.balance_minor
        """, nativeQuery = true)
    SummaryProjection summary(@Param("userId") UUID userId);

    interface SummaryProjection {
        long getBalanceMinor();
        long getOpeningBalanceMinor();
        long getTotalCreditsMinor();
        long getTotalDebitsMinor();
        java.time.Instant getRecordedSince();
    }
}
