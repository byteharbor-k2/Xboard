package com.sinx.platform.catalog.domain;

import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "service_plan_prices",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_service_plan_period",
        columnNames = {"plan_id", "billing_period"}
    )
)
public class ServicePlanPrice {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ServicePlan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", length = 24, nullable = false)
    private BillingPeriod billingPeriod;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(length = 3, nullable = false)
    private String currency;

    protected ServicePlanPrice() {
    }

    static ServicePlanPrice create(
        ServicePlan plan,
        BillingPeriod billingPeriod,
        long amountMinor,
        String currency
    ) {
        ServicePlanPrice price = new ServicePlanPrice();
        price.id = UUID.randomUUID();
        price.plan = plan;
        price.billingPeriod = billingPeriod;
        price.amountMinor = amountMinor;
        price.currency = currency.toUpperCase(Locale.ROOT);
        return price;
    }

    void update(long amountMinor, String currency) {
        this.amountMinor = amountMinor;
        this.currency = currency.toUpperCase(Locale.ROOT);
    }

    public BillingPeriod getBillingPeriod() {
        return billingPeriod;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }
}
