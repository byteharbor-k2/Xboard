package com.sinx.platform.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One configured way to pay.
 *
 * A row is a gateway <em>instance</em>, not a gateway: the same Epay code can
 * appear several times over with different credentials, one endpoint for the
 * fiat channels and another pointing at a crypto upstream. The order a customer
 * places names the row they used, so a callback can be verified against the
 * credentials it was actually signed with.
 *
 * The gateway credentials live in {@link #config} as JSON, because which keys
 * exist is the gateway's business. They are never logged and never leave the
 * admin surface.
 */
@Entity
@Table(name = "payment_methods")
public class PaymentMethod {

    private static final long MAX_NAME_LENGTH = 120;

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 32, updatable = false)
    private String uuid;

    @Column(nullable = false, length = 32, updatable = false)
    private String gateway;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String icon;

    @Column(nullable = false, columnDefinition = "text")
    private String config = "{}";

    @Column(name = "notify_domain", length = 128)
    private String notifyDomain;

    @Column(name = "handling_fee_fixed")
    private Long handlingFeeFixed;

    @Column(name = "handling_fee_percent", precision = 5, scale = 2)
    private BigDecimal handlingFeePercent;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PaymentMethod() {
    }

    public static PaymentMethod create(
        String gateway,
        String name,
        Instant now
    ) {
        PaymentMethod method = new PaymentMethod();
        method.id = UUID.randomUUID();
        // The identifier in the notify URL. A random token rather than the
        // primary key, so the unauthenticated callback endpoint cannot be
        // walked by guessing one row after another.
        method.uuid = UUID.randomUUID().toString().replace("-", "");
        method.gateway = gateway;
        method.name = requireName(name);
        method.enabled = false;
        method.createdAt = now;
        method.updatedAt = now;
        return method;
    }

    /**
     * What an order was asked to pay on top of its own total.
     *
     * Rounded once, here, so the amount shown to the customer, the amount sent
     * to the gateway and the amount verified on the callback are the same
     * number - the original panel rounds at checkout and then trusts whatever
     * comes back.
     */
    public long handlingFeeFor(long amountMinor) {
        if (amountMinor <= 0) {
            return 0;
        }
        BigDecimal fee = BigDecimal.ZERO;
        if (handlingFeePercent != null) {
            fee = fee.add(
                BigDecimal.valueOf(amountMinor)
                    .multiply(handlingFeePercent)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            );
        }
        if (handlingFeeFixed != null) {
            fee = fee.add(BigDecimal.valueOf(handlingFeeFixed));
        }
        return fee.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public void rename(String name, Instant now) {
        this.name = requireName(name);
        updatedAt(now);
    }

    public void describe(
        String icon,
        String notifyDomain,
        Long handlingFeeFixed,
        BigDecimal handlingFeePercent,
        Instant now
    ) {
        this.icon = blankToNull(icon);
        this.notifyDomain = blankToNull(notifyDomain);
        this.handlingFeeFixed = handlingFeeFixed;
        this.handlingFeePercent = handlingFeePercent;
        updatedAt(now);
    }

    public void replaceConfig(String config, Instant now) {
        this.config = config == null || config.isBlank() ? "{}" : config;
        updatedAt(now);
    }

    public void toggle(Instant now) {
        enabled = !enabled;
        updatedAt(now);
    }

    public void moveTo(int sortOrder, Instant now) {
        this.sortOrder = sortOrder;
        updatedAt(now);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether this method belongs to a gateway, spelled however it is spelled.
     *
     * The column is also written by the original panel and by hand, so it holds
     * {@code EPay} and {@code epay} both; a callback arrives lower-cased. The
     * case is not part of the identity.
     */
    public boolean is(String gatewayCode) {
        return gatewayCode != null
            && gateway.toLowerCase(Locale.ROOT)
                .equals(gatewayCode.toLowerCase(Locale.ROOT));
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A payment method needs a name");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                "A payment method name cannot exceed " + MAX_NAME_LENGTH
                    + " characters"
            );
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void updatedAt(Instant now) {
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public String getGateway() {
        return gateway;
    }

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public String getConfig() {
        return config;
    }

    public String getNotifyDomain() {
        return notifyDomain;
    }

    public Long getHandlingFeeFixed() {
        return handlingFeeFixed;
    }

    public BigDecimal getHandlingFeePercent() {
        return handlingFeePercent;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
