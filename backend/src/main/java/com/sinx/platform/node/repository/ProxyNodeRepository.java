package com.sinx.platform.node.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.ProxyNode;

public interface ProxyNodeRepository extends JpaRepository<ProxyNode, Long> {

    List<ProxyNode> findAllByOrderBySortOrderAscIdAsc();

    List<ProxyNode> findByMachineIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long machineId);

    List<ProxyNode> findByMachineIdOrderBySortOrderAscIdAsc(Long machineId);

    long countByMachineId(Long machineId);
}
