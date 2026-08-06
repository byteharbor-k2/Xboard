package com.sinx.platform.node.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.NodeAccessGroup;

public interface NodeAccessGroupRepository extends JpaRepository<NodeAccessGroup, Long> {

    List<NodeAccessGroup> findAllByOrderByIdDesc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
