package com.sinx.platform.order.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.order.domain.CommissionLog;

public interface CommissionLogRepository extends JpaRepository<CommissionLog, UUID> {

    @Query("select log from CommissionLog log where log.recipientUserId = :recipient order by log.createdAt desc, log.id desc")
    Page<CommissionLog> pageForRecipient(
        @Param("recipient") UUID recipient,
        Pageable pageable
    );

    @Query("select log from CommissionLog log where log.tradeNo = :tradeNo order by log.level asc")
    List<CommissionLog> findByTradeNoOrderByLevel(@Param("tradeNo") String tradeNo);

    @Query("select coalesce(sum(log.amountMinor), 0) from CommissionLog log where log.recipientUserId = :recipient")
    long sumEarnedForRecipient(@Param("recipient") UUID recipient);
}
