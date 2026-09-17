package com.sinx.platform.node.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.ProxyNode;

public interface ProxyNodeRepository extends JpaRepository<ProxyNode, Long> {

    List<ProxyNode> findAllByOrderBySortOrderAscIdAsc();

    /**
     * Every node a subscription may name.
     *
     * The whitelist is the panel's, not the node control plane's: a node that is
     * switched off or hidden must not be handed to clients even while the node
     * is still being pushed users. Which of these a given account may actually
     * use is decided by their group, which is a JSON column and therefore
     * filtered in Java.
     */
    List<ProxyNode> findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc();

    List<ProxyNode> findByMachineIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long machineId);

    List<ProxyNode> findByMachineIdOrderBySortOrderAscIdAsc(Long machineId);

    Optional<ProxyNode> findFirstByCode(String code);

    long countByMachineId(Long machineId);

    List<ProxyNode> findByMachineIdAndServerPort(Long machineId, int serverPort);
}
