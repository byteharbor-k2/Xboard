package com.sinx.platform.order.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;

import jakarta.persistence.LockModeType;

public interface ServiceOrderRepository extends JpaRepository<ServiceOrder, UUID> {

    @EntityGraph(attributePaths = {"user", "plan"})
    Optional<ServiceOrder> findByTradeNo(String tradeNo);

    /**
     * Reads an order for settlement under a row lock, so two payment callbacks
     * arriving together cannot both provision it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "plan"})
    @Query("select o from ServiceOrder o where o.tradeNo = :tradeNo")
    Optional<ServiceOrder> findByTradeNoForUpdate(
        @Param("tradeNo") String tradeNo
    );

    /**
     * The earlier orders a later upgrade consumed, locked so their status is
     * flipped exactly once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ServiceOrder o where o.id in :ids")
    List<ServiceOrder> findAllForUpdateById(@Param("ids") Collection<UUID> ids);

    /**
     * Identifies orders still awaiting settlement that are older than
     * {@code before}. Only the trade numbers come back, so the sweep can cancel
     * each one in its own transaction.
     */
    @Query("""
        select o.tradeNo from ServiceOrder o
        where o.status = :status
          and o.createdAt < :before
        """)
    List<String> findTradeNosByStatusCreatedBefore(
        @Param("status") OrderStatus status,
        @Param("before") Instant before
    );

    @EntityGraph(attributePaths = "plan")
    List<ServiceOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @EntityGraph(attributePaths = {"user", "plan"})
    List<ServiceOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"user", "plan"})
    List<ServiceOrder> findByStatusOrderByCreatedAtDesc(
        OrderStatus status,
        Pageable pageable
    );

    boolean existsByUserIdAndStatusIn(
        UUID userId,
        Collection<OrderStatus> statuses
    );

    /** Whether a payment method has been used, and so has a history to keep. */
    boolean existsByPaymentMethodId(UUID paymentMethodId);

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
