package com.sinx.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.sinx.platform.payment.application.PaymentCheckoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises signed payment receipts against PostgreSQL, including old cashier
 * credentials, duplicate delivery and receipts arriving after an order moved on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PaymentReceiptReconciliationIntegrationTest {

    private static final String OLD_KEY = "old-integration-key";
    private static final String NEW_KEY = "new-integration-key";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_payment_receipts")
            .withUsername("sinx")
            .withPassword("sinx_test");

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentCheckoutService checkout;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void oldCashierConfigAndFeeRemainAuthoritativeAfterRecheckoutAndDisable()
        throws Exception {
        Fixture fixture = fixture(OLD_KEY);
        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());

        jdbc.update(
            "UPDATE payment_methods SET config = ?, handling_fee_fixed = 100 WHERE id = ?",
            config(NEW_KEY, fixture.merchantId(), fixture.gatewayUrl()), fixture.methodId());
        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());
        jdbc.update("UPDATE payment_methods SET enabled = FALSE WHERE id = ?", fixture.methodId());

        notify(fixture, OLD_KEY, "12.00", "EPAY-OLD-RECEIPT")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        assertThat(orderStatus(fixture.tradeNo())).isEqualTo("COMPLETED");
        assertThat(count("payment_attempts", fixture.tradeNo())).isEqualTo(2);
        assertThat(count("payment_receipts", fixture.tradeNo())).isEqualTo(1);
        assertThat(userBalance(fixture.userId())).isZero();
    }

    @Test
    void canceledAndManuallyCompletedOrdersCreditEachReceiptOnce() throws Exception {
        Fixture canceled = fixture(OLD_KEY);
        checkout.checkout(canceled.userId(), canceled.tradeNo(), canceled.methodId());
        jdbc.update(
            "UPDATE orders SET status = 'CANCELLED', canceled_at = CURRENT_TIMESTAMP WHERE trade_no = ?",
            canceled.tradeNo());

        notify(canceled, OLD_KEY, "12.00", "EPAY-CANCELLED-RECEIPT")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));
        notify(canceled, OLD_KEY, "12.00", "EPAY-CANCELLED-RECEIPT")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        assertThat(orderStatus(canceled.tradeNo())).isEqualTo("CANCELLED");
        assertThat(userBalance(canceled.userId())).isEqualTo(1200);
        assertThat(refundLedgerRows(canceled.userId())).isEqualTo(1);

        Fixture manuallyPaid = fixture(OLD_KEY);
        checkout.checkout(manuallyPaid.userId(), manuallyPaid.tradeNo(), manuallyPaid.methodId());
        jdbc.update("""
            UPDATE orders SET status = 'COMPLETED', callback_no = 'manual_operation',
                paid_at = CURRENT_TIMESTAMP, settlement_outcome = 'SERVICE_FULFILLED'
            WHERE trade_no = ?
            """, manuallyPaid.tradeNo());

        notify(manuallyPaid, OLD_KEY, "12.00", "EPAY-MANUAL-RECEIPT")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        assertThat(orderStatus(manuallyPaid.tradeNo())).isEqualTo("COMPLETED");
        assertThat(userBalance(manuallyPaid.userId())).isEqualTo(1200);
        assertThat(refundLedgerRows(manuallyPaid.userId())).isEqualTo(1);
    }

    @Test
    void aSignedButMismatchedCaptureIsRecordedAndCreditedRatherThanLost()
        throws Exception {
        Fixture fixture = fixture(OLD_KEY);
        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());

        notify(fixture, OLD_KEY, "5.00", "EPAY-SHORT-RECEIPT")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        assertThat(orderStatus(fixture.tradeNo())).isEqualTo("PENDING");
        assertThat(userBalance(fixture.userId())).isEqualTo(500);
        assertThat(count("payment_receipts", fixture.tradeNo())).isEqualTo(1);
        assertThat(refundLedgerRows(fixture.userId())).isEqualTo(1);
    }

    @Test
    void simultaneousDeliveryOfOneReceiptSettlesAndCreditsOnlyOnce()
        throws Exception {
        Fixture fixture = fixture(OLD_KEY);
        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> notify(
                fixture, OLD_KEY, "12.00", "EPAY-CONCURRENT-RECEIPT")
                .andReturn().getResponse().getStatus());
            Future<Integer> second = workers.submit(() -> notify(
                fixture, OLD_KEY, "12.00", "EPAY-CONCURRENT-RECEIPT")
                .andReturn().getResponse().getStatus());

            assertThat(first.get()).isEqualTo(200);
            assertThat(second.get()).isEqualTo(200);
        } finally {
            workers.shutdownNow();
        }

        assertThat(orderStatus(fixture.tradeNo())).isEqualTo("COMPLETED");
        assertThat(count("payment_receipts", fixture.tradeNo())).isEqualTo(1);
        assertThat(userBalance(fixture.userId())).isZero();
    }

    @Test
    void identicalEpayTransactionIdsAreScopedByGatewayUrlAndMerchant()
        throws Exception {
        // The cashier callback host is the same panel origin in all three cases;
        // merchant endpoint URL and account identity define independent receipts.
        Fixture firstGateway = fixture(
            OLD_KEY, "merchant-100", "https://one.gateway.example.test");
        Fixture secondGateway = fixture(
            OLD_KEY, "merchant-100", "https://two.gateway.example.test");
        Fixture secondMerchant = fixture(
            OLD_KEY, "merchant-200", "https://one.gateway.example.test");
        for (Fixture fixture : new Fixture[] {firstGateway, secondGateway, secondMerchant}) {
            checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());
            notify(fixture, OLD_KEY, "12.00", "EPAY-SHARED-TRANSACTION")
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
        }

        assertThat(orderStatus(firstGateway.tradeNo())).isEqualTo("COMPLETED");
        assertThat(orderStatus(secondGateway.tradeNo())).isEqualTo("COMPLETED");
        assertThat(orderStatus(secondMerchant.tradeNo())).isEqualTo("COMPLETED");
        assertThat(receiptsForTransaction("EPAY-SHARED-TRANSACTION")).isEqualTo(3);
        assertThat(receiptIdentity(firstGateway.tradeNo()))
            .containsEntry("gateway_url", "https://one.gateway.example.test")
            .containsEntry("merchant_identity", "merchant-100");
        assertThat(receiptIdentity(secondGateway.tradeNo()))
            .containsEntry("gateway_url", "https://two.gateway.example.test")
            .containsEntry("merchant_identity", "merchant-100");
        assertThat(receiptIdentity(secondMerchant.tradeNo()))
            .containsEntry("gateway_url", "https://one.gateway.example.test")
            .containsEntry("merchant_identity", "merchant-200");
    }

    @Test
    void oneMerchantTransactionCannotBeAppliedToTwoDifferentOrders()
        throws Exception {
        Fixture first = fixture(OLD_KEY, "merchant-100", "https://one.gateway.example.test");
        Fixture second = fixture(OLD_KEY, "merchant-100", "https://one.gateway.example.test");
        checkout.checkout(first.userId(), first.tradeNo(), first.methodId());
        checkout.checkout(second.userId(), second.tradeNo(), second.methodId());

        notify(first, OLD_KEY, "12.00", "EPAY-REPLAYED-TRANSACTION")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));
        notify(second, OLD_KEY, "12.00", "EPAY-REPLAYED-TRANSACTION")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().string("failed"));

        assertThat(orderStatus(first.tradeNo())).isEqualTo("COMPLETED");
        assertThat(orderStatus(second.tradeNo())).isEqualTo("PENDING");
        assertThat(userBalance(first.userId())).isZero();
        assertThat(userBalance(second.userId())).isZero();
        assertThat(receiptsForTransaction("EPAY-REPLAYED-TRANSACTION")).isEqualTo(1);
    }

    @Test
    void aPendingOrderAssignedAfterCheckoutCreditsItsOriginalBuyer()
        throws Exception {
        Fixture fixture = fixture(OLD_KEY);
        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());
        UUID newOwner = createUser();
        jdbc.update("UPDATE orders SET user_id = ? WHERE trade_no = ?",
            newOwner, fixture.tradeNo());

        notify(fixture, OLD_KEY, "12.00", "EPAY-ASSIGNED-ORDER")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        assertThat(orderStatus(fixture.tradeNo())).isEqualTo("PENDING");
        assertThat(userBalance(fixture.userId())).isEqualTo(1200);
        assertThat(userBalance(newOwner)).isZero();
        assertThat(count("payment_receipts", fixture.tradeNo())).isEqualTo(1);
    }

    @Test
    void staleResetRefundUsesTheHandlingFeeOfThePaidAttempt()
        throws Exception {
        Fixture fixture = fixture(OLD_KEY);
        Instant now = Instant.now();
        Instant cycleStart = now.minusSeconds(30L * 24 * 60 * 60);
        Instant cycleEnd = now.plusSeconds(20L * 24 * 60 * 60);
        Instant expiresAt = now.plusSeconds(90L * 24 * 60 * 60);
        UUID cycleId = UUID.randomUUID();
        jdbc.update("UPDATE service_plans SET resettable = TRUE WHERE id = ?", fixture.planId());
        jdbc.update("""
            INSERT INTO service_plan_prices (id, plan_id, billing_period, amount_minor, currency)
            VALUES (?, ?, 'RESET_TRAFFIC', 1200, 'CNY')
            """, UUID.randomUUID(), fixture.planId());
        jdbc.update("""
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, reset_policy, starts_at,
                expires_at, next_reset_at, traffic_cycle_id, is_trial,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'Receipt plan', ?, 0, 0, 'MONTHLY_FROM_ACTIVATION',
                ?, ?, ?, ?, FALSE, ?, ?)
            """, UUID.randomUUID(), fixture.userId(), fixture.planId(),
            10L * 1024 * 1024 * 1024, Timestamp.from(cycleStart),
            Timestamp.from(expiresAt), Timestamp.from(cycleEnd), cycleId,
            Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
            UPDATE orders SET period = 'RESET_TRAFFIC', order_type = 'RESET_TRAFFIC',
                reset_cycle_start = ?, reset_cycle_end = ?, reset_cycle_id = ?
            WHERE trade_no = ?
            """, Timestamp.from(cycleStart), Timestamp.from(cycleEnd), cycleId,
            fixture.tradeNo());

        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());
        jdbc.update("UPDATE payment_methods SET handling_fee_fixed = 100 WHERE id = ?",
            fixture.methodId());
        checkout.checkout(fixture.userId(), fixture.tradeNo(), fixture.methodId());
        // This is a separate current-cycle change after both real cashier links
        // were issued; the older link can still have captured its snapshotted 12.
        jdbc.update("UPDATE subscription_entitlements SET traffic_cycle_id = ? WHERE user_id = ?",
            UUID.randomUUID(), fixture.userId());

        notify(fixture, OLD_KEY, "12.00", "EPAY-STALE-RESET")
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        Map<String, Object> order = jdbc.queryForMap("""
            SELECT status, settlement_outcome, returned_balance_minor,
                handling_amount, payment_method_id, gateway
            FROM orders WHERE trade_no = ?
            """, fixture.tradeNo());
        assertThat(order.get("status")).isEqualTo("COMPLETED");
        assertThat(order.get("settlement_outcome")).isEqualTo("BALANCE_RETURNED");
        assertThat(order.get("returned_balance_minor")).isEqualTo(1200L);
        assertThat(order.get("handling_amount")).isEqualTo(0L);
        assertThat(order.get("payment_method_id")).isEqualTo(fixture.methodId());
        assertThat(order.get("gateway")).isEqualTo("EPay");
        assertThat(userBalance(fixture.userId())).isEqualTo(1200);
        assertThat(jdbc.queryForObject(
            "SELECT outcome FROM payment_receipts WHERE transaction_id = ?",
            String.class,
            "EPAY-STALE-RESET")).isEqualTo("BALANCE_CREDITED");
    }

    private Fixture fixture(String key) {
        return fixture(key, "1000", "https://gateway.example.test");
    }

    private Fixture fixture(String key, String merchantId, String gatewayUrl) {
        Instant now = Instant.now();
        UUID userId = createUser();
        UUID planId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        String methodUuid = UUID.randomUUID().toString().replace("-", "");
        String tradeNo = "SX" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        jdbc.update("""
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes, speed_limit_mbps,
                reset_policy, published, sellable, renewable, sort_order,
                created_at, updated_at
            ) VALUES (?, 'Receipt plan', 'Integration plan', ?, 100,
                'MONTHLY_FROM_ACTIVATION', TRUE, TRUE, TRUE, 1, ?, ?)
            """, planId, 10L * 1024 * 1024 * 1024,
            Timestamp.from(now), Timestamp.from(now));

        jdbc.update("""
            INSERT INTO orders (
                id, trade_no, user_id, plan_id, plan_name, period, order_type,
                status, currency, original_amount, discount_amount, surplus_amount,
                surplus_credit, balance_amount, total_amount, commission_buyer_user_id,
                commission_base, commission_balance, actual_commission_balance,
                surplus_order_ids, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'Receipt plan', 'MONTHLY', 'NEW_PURCHASE',
                'PENDING', 'CNY', 1200, 0, 0, 0, 0, 1200, ?, 0, 0, 0, '[]', ?, ?)
            """, UUID.randomUUID(), tradeNo, userId, planId, userId,
            Timestamp.from(now), Timestamp.from(now));

        jdbc.update("""
            INSERT INTO payment_methods (
                id, uuid, gateway, name, config, notify_domain, enabled, sort_order,
                created_at, updated_at
            ) VALUES (?, ?, 'EPay', 'Receipt EPay', ?, 'https://panel.example.test', TRUE, 0, ?, ?)
            """, methodId, methodUuid, config(key, merchantId, gatewayUrl),
            Timestamp.from(now), Timestamp.from(now));
        return new Fixture(userId, planId, methodId, methodUuid, tradeNo,
            merchantId, gatewayUrl);
    }

    private UUID createUser() {
        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, subscription_token,
                proxy_uuid, created_at, updated_at
            ) VALUES (?, ?, 'test-hash', 'Receipt buyer', 'ACTIVE', ?, ?, ?, ?)
            """, userId, "receipt-" + userId + "@example.test",
            "sub-" + userId, userId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'USER')", userId);
        return userId;
    }

    private org.springframework.test.web.servlet.ResultActions notify(
        Fixture fixture,
        String key,
        String amount,
        String transactionId
    ) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", fixture.merchantId());
        params.put("out_trade_no", fixture.tradeNo());
        params.put("trade_no", transactionId);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("money", amount);
        params.put("type", "alipay");
        params.put("sign_type", "MD5");
        params.put("sign", signature(params, key));
        var request = get("/api/v1/guest/payment/notify/epay/{uuid}", fixture.methodUuid());
        params.forEach(request::param);
        return mockMvc.perform(request);
    }

    private String signature(Map<String, String> params, String key) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("sign");
        signed.remove("sign_type");
        return PhpCompat.md5(PhpCompat.signedString(signed) + key);
    }

    private String config(String key, String merchantId, String gatewayUrl) {
        return "{\"url\":\"" + gatewayUrl + "\","
            + "\"pid\":\"" + merchantId + "\",\"key\":\"" + key + "\","
            + "\"type\":\"alipay\"}";
    }

    private String orderStatus(String tradeNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE trade_no = ?", String.class, tradeNo);
    }

    private long userBalance(UUID userId) {
        Long balance = jdbc.queryForObject("SELECT balance_minor FROM users WHERE id = ?", Long.class, userId);
        return balance == null ? 0 : balance;
    }

    private long count(String table, String tradeNo) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE trade_no = ?", Long.class, tradeNo);
    }

    private long refundLedgerRows(UUID userId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM balance_logs WHERE user_id = ? AND type = 'ORDER_REFUND'",
            Long.class,
            userId);
    }

    private long receiptsForTransaction(String transactionId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM payment_receipts WHERE transaction_id = ?",
            Long.class,
            transactionId);
    }

    private Map<String, Object> receiptIdentity(String tradeNo) {
        return jdbc.queryForMap(
            "SELECT gateway_url, merchant_identity FROM payment_receipts WHERE trade_no = ?",
            tradeNo);
    }

    private record Fixture(
        UUID userId,
        UUID planId,
        UUID methodId,
        String methodUuid,
        String tradeNo,
        String merchantId,
        String gatewayUrl
    ) {
    }
}
