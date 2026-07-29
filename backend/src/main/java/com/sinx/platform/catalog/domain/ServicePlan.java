package com.sinx.platform.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "service_plans")
public class ServicePlan {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "transfer_limit_bytes", nullable = false)
    private long transferLimitBytes;

    @Column(name = "speed_limit_mbps")
    private Integer speedLimitMbps;

    @Column(name = "device_limit")
    private Integer deviceLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_policy", length = 32, nullable = false)
    private TrafficResetPolicy resetPolicy;

    @Column(name = "capacity_limit")
    private Integer capacityLimit;

    @Column(nullable = false)
    private boolean published;

    @Column(nullable = false)
    private boolean sellable;

    @Column(nullable = false)
    private boolean renewable;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "service_plan_tags",
        joinColumns = @JoinColumn(name = "plan_id")
    )
    @OrderColumn(name = "position")
    @Column(name = "label", length = 48, nullable = false)
    private List<String> tags = new ArrayList<>();

    @OneToMany(
        mappedBy = "plan",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    private Set<ServicePlanPrice> prices = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ServicePlan() {
    }

    public static ServicePlan create(
        UUID id,
        String name,
        String description,
        long transferLimitBytes,
        Integer speedLimitMbps,
        Integer deviceLimit,
        TrafficResetPolicy resetPolicy,
        Integer capacityLimit,
        boolean published,
        boolean sellable,
        boolean renewable,
        int sortOrder,
        List<String> tags,
        Instant now
    ) {
        ServicePlan plan = new ServicePlan();
        plan.id = id;
        plan.name = name;
        plan.description = description;
        plan.transferLimitBytes = transferLimitBytes;
        plan.speedLimitMbps = speedLimitMbps;
        plan.deviceLimit = deviceLimit;
        plan.resetPolicy = resetPolicy;
        plan.capacityLimit = capacityLimit;
        plan.published = published;
        plan.sellable = sellable;
        plan.renewable = renewable;
        plan.sortOrder = sortOrder;
        plan.tags.addAll(tags);
        plan.createdAt = now;
        plan.updatedAt = now;
        return plan;
    }

    public void addPrice(
        BillingPeriod billingPeriod,
        long amountMinor,
        String currency
    ) {
        prices.removeIf(price -> price.getBillingPeriod() == billingPeriod);
        prices.add(ServicePlanPrice.create(
            this,
            billingPeriod,
            amountMinor,
            currency
        ));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getTransferLimitBytes() {
        return transferLimitBytes;
    }

    public Integer getSpeedLimitMbps() {
        return speedLimitMbps;
    }

    public Integer getDeviceLimit() {
        return deviceLimit;
    }

    public TrafficResetPolicy getResetPolicy() {
        return resetPolicy;
    }

    public Integer getCapacityLimit() {
        return capacityLimit;
    }

    public boolean isPublished() {
        return published;
    }

    public boolean isSellable() {
        return sellable;
    }

    public boolean isRenewable() {
        return renewable;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public Set<ServicePlanPrice> getPrices() {
        return Set.copyOf(prices);
    }
}
