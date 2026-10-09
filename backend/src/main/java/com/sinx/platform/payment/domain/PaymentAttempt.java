package com.sinx.platform.payment.domain;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The immutable terms and merchant configuration used to create one cashier
 * link. A later checkout or admin edit must not change what that link means.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "trade_no", nullable = false, length = 32, updatable = false)
    private String tradeNo;

    @Column(name = "buyer_user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "method_id", nullable = false, updatable = false)
    private UUID methodId;

    @Column(name = "method_uuid", nullable = false, length = 32, updatable = false)
    private String methodUuid;

    @Column(name = "gateway_url", nullable = false, length = 512, updatable = false)
    private String gatewayUrl;

    @Column(name = "merchant_identity", nullable = false, length = 120, updatable = false)
    private String merchantIdentity;

    @Column(name = "method_name", nullable = false, length = 120, updatable = false)
    private String methodName;

    @Column(name = "method_icon", length = 255, updatable = false)
    private String methodIcon;

    @Column(nullable = false, length = 32, updatable = false)
    private String gateway;

    @Column(name = "merchant_config", nullable = false, columnDefinition = "text", updatable = false)
    private String merchantConfig;

    @Column(name = "fee_fixed_minor", updatable = false)
    private Long feeFixedMinor;

    @Column(name = "fee_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal feePercent;

    @Column(name = "order_amount_minor", nullable = false, updatable = false)
    private long orderAmountMinor;

    @Column(name = "handling_fee_minor", nullable = false, updatable = false)
    private long handlingFeeMinor;

    @Column(name = "payable_amount_minor", nullable = false, updatable = false)
    private long payableAmountMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAttempt() {
    }

    public static PaymentAttempt create(
        String tradeNo,
        UUID buyerUserId,
        PaymentMethod method,
        String gatewayUrl,
        String merchantIdentity,
        String merchantConfig,
        long orderAmountMinor,
        long handlingFeeMinor,
        String currency,
        Instant createdAt
    ) {
        if (orderAmountMinor <= 0 || handlingFeeMinor < 0) {
            throw new IllegalArgumentException("A payment attempt needs a positive payable amount");
        }
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = UUID.randomUUID();
        attempt.tradeNo = tradeNo;
        attempt.buyerUserId = buyerUserId;
        attempt.methodId = method.getId();
        attempt.methodUuid = method.getUuid();
        attempt.gatewayUrl = normalizeGatewayUrl(gatewayUrl, method.getUuid());
        attempt.merchantIdentity = normalizeMerchantIdentity(merchantIdentity);
        attempt.methodName = method.getName();
        attempt.methodIcon = method.getIcon();
        attempt.gateway = method.getGateway();
        attempt.merchantConfig = merchantConfig;
        attempt.feeFixedMinor = method.getHandlingFeeFixed();
        attempt.feePercent = method.getHandlingFeePercent();
        attempt.orderAmountMinor = orderAmountMinor;
        attempt.handlingFeeMinor = handlingFeeMinor;
        attempt.payableAmountMinor = Math.addExact(orderAmountMinor, handlingFeeMinor);
        attempt.currency = currency;
        attempt.createdAt = createdAt;
        return attempt;
    }

    public UUID getId() {
        return id;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public UUID getBuyerUserId() {
        return buyerUserId;
    }

    public UUID getMethodId() {
        return methodId;
    }

    public String getMethodUuid() {
        return methodUuid;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public String getMerchantIdentity() {
        return merchantIdentity;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodIcon() {
        return methodIcon;
    }

    public String getGateway() {
        return gateway;
    }

    public String getMerchantConfig() {
        return merchantConfig;
    }

    public long getOrderAmountMinor() {
        return orderAmountMinor;
    }

    public long getHandlingFeeMinor() {
        return handlingFeeMinor;
    }

    public long getPayableAmountMinor() {
        return payableAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    /** Gateway endpoint identity; missing legacy URLs are isolated by method token. */
    public static String normalizeGatewayUrl(String value, String methodUuid) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return legacyGatewayUrl(methodUuid);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            normalized = "https://" + normalized;
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException invalid) {
            return legacyGatewayUrl(methodUuid);
        }
        String scheme = uri.getScheme() == null
            ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return legacyGatewayUrl(methodUuid);
        }
        int port = uri.getPort();
        boolean defaultPort = port == 80 && scheme.equals("http")
            || port == 443 && scheme.equals("https");
        String path = uri.getRawPath() == null ? ""
            : uri.getRawPath().replaceFirst("/+$", "");
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return scheme + "://" + host.toLowerCase(Locale.ROOT)
            + (port < 0 || defaultPort ? "" : ":" + port)
            + path + query;
    }

    public static String legacyGatewayUrl(String methodUuid) {
        String token = methodUuid == null || methodUuid.isBlank()
            ? "unknown" : methodUuid.trim().toLowerCase(Locale.ROOT);
        return "legacy://unknown/" + token;
    }

    public static String normalizeMerchantIdentity(String value) {
        // Merchant identifiers are opaque provider values: trim form padding,
        // but preserve case in case the provider treats it as significant.
        return value == null ? "" : value.trim();
    }
}
