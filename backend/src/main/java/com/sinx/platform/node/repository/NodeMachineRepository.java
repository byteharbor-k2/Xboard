package com.sinx.platform.node.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.NodeMachine;

public interface NodeMachineRepository extends JpaRepository<NodeMachine, Long> {

    List<NodeMachine> findAllByOrderByIdAsc();

    Optional<NodeMachine> findByIdAndToken(Long id, String token);
}
