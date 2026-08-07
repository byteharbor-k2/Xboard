package com.sinx.platform.node.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.ProxyNode;

public interface ProxyNodeRepository extends JpaRepository<ProxyNode, Long> {

    List<ProxyNode> findAllByOrderBySortOrderAscIdAsc();

    List<ProxyNode> findByMachineIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long machineId);

    List<ProxyNode> findByMachineIdOrderBySortOrderAscIdAsc(Long machineId);

    Optional<ProxyNode> findFirstByCode(String code);

    long countByMachineId(Long machineId);
}
