package com.sinx.platform.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.catalog.domain.ServicePlan;

public interface ServicePlanRepository
    extends JpaRepository<ServicePlan, UUID> {

    List<ServicePlan>
        findAllByPublishedTrueAndSellableTrueOrderBySortOrderAscNameAsc();

    long countByServerGroupId(Long serverGroupId);
}
