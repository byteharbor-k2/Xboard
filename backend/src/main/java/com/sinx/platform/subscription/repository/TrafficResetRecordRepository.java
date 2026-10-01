package com.sinx.platform.subscription.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.subscription.domain.TrafficResetRecord;

public interface TrafficResetRecordRepository
    extends JpaRepository<TrafficResetRecord, UUID> {

    /**
     * The most recent resets, optionally narrowed to one account.
     *
     * Read from the back of time: what the operator asks about is what has
     * just happened, and the ledger only ever grows in one direction.
     */
    List<TrafficResetRecord> findByOrderByResetAtDesc(Pageable pageable);

    List<TrafficResetRecord> findByUserIdOrderByResetAtDesc(
        UUID userId,
        Pageable pageable
    );
}
