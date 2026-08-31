package com.sinx.platform.order.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;

public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, UUID> {

    @EntityGraph(attributePaths = {"user", "plan"})
    Optional<ServiceOrder> findByTradeNo(String tradeNo);

    @EntityGraph(attributePaths = "plan")
    List<ServiceOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByUserIdAndStatusIn(
        UUID userId,
        Collection<OrderStatus> statuses
    );

    long countByUserIdAndPlanIdAndStatusIn(
        UUID userId,
        UUID planId,
        Collection<OrderStatus> statuses
    );

    /**
     * The history the surplus calculation values: settled orders for periodic
     * plans, leaving out traffic resets and one-off packages.
     */
    @Query("""
        select o from ServiceOrder o
        where o.user.id = :userId
          and o.status = :status
          and o.period not in :excludedPeriods
        order by o.createdAt asc
        """)
    List<ServiceOrder> findSettledPeriodicOrders(
        @Param("userId") UUID userId,
        @Param("status") OrderStatus status,
        @Param("excludedPeriods") Collection<BillingPeriod> excludedPeriods
    );

    @Query("""
        select o from ServiceOrder o
        where o.user.id = :userId
          and o.status = :status
          and o.period = :period
        order by o.createdAt desc
        limit 1
        """)
    Optional<ServiceOrder> findLatestSettledForPeriod(
        @Param("userId") UUID userId,
        @Param("status") OrderStatus status,
        @Param("period") BillingPeriod period
    );

    @Query("""
        select o.id from ServiceOrder o
        where o.user.id = :userId
          and o.status = :status
          and o.period <> :excludedPeriod
        """)
    List<UUID> findSettledOrderIdsExcludingPeriod(
        @Param("userId") UUID userId,
        @Param("status") OrderStatus status,
        @Param("excludedPeriod") BillingPeriod excludedPeriod
    );
}
