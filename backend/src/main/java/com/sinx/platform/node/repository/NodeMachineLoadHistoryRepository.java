package com.sinx.platform.node.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.NodeMachineLoadHistory;

public interface NodeMachineLoadHistoryRepository
    extends JpaRepository<NodeMachineLoadHistory, Long> {

    List<NodeMachineLoadHistory> findByMachineIdOrderByRecordedAtDesc(
        Long machineId,
        Pageable pageable
    );

    List<NodeMachineLoadHistory> findByMachineIdAndRecordedAtGreaterThanEqualOrderByRecordedAtDesc(
        Long machineId,
        Instant recordedAt,
        Pageable pageable
    );

    void deleteByMachineIdAndRecordedAtBefore(Long machineId, Instant cutoff);
}
