package com.sinx.platform.node.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.node.domain.NodeRouteRule;

public interface NodeRouteRuleRepository extends JpaRepository<NodeRouteRule, Long> {

    List<NodeRouteRule> findAllByOrderByIdAsc();
}
