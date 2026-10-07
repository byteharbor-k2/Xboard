package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderQuoteView;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.OrderDeductionMode;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.payment.application.PaymentCheckoutService;
import com.sinx.platform.payment.domain.PaymentRedirect;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.application.TrafficResetService;
import com.sinx.platform.subscription.domain.MonthlyResetSchedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL checkout/callback regressions for reset snapshots and funded coverage. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CustomRenewalPaymentAndValuationIntegrationTest.TestClockConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CustomRenewalPaymentAndValuationIntegrationTest {

    private static final String EPAY_KEY = "custom-renewal-integration-key";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_custom_renewal_test")
            .withUsername("sinx")
            .withPassword("sinx_test");

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderService orders;
    @Autowired private OrderFulfilmentService fulfilment;
    @Autowired private PaymentCheckoutService checkout;
    @Autowired private TrafficResetService trafficResets;
    @Autowired private BalanceLedgerService balanceLedger;
    @Autowired private MockMvc mvc;
    @Autowired private MutableTestClock clock;

    @BeforeEach
    void setClockToPostgresPrecision() {
        clock.set(Instant.now());
    }

    @Test
    void staleResetIsRejectedBeforeCashierAndExpiredResetCannotStart() {
        Buyer stalePlanOwner = buyer("stale-plan");
        UUID stalePlan = plan("Stale-plan", 15_00, true);
        Instant cycleEnd = activeEntitlement(stalePlanOwner, stalePlan);
        ServiceOrder disabled = orders.place(stalePlanOwner.id(), stalePlan,
            BillingPeriod.RESET_TRAFFIC, null);
        jdbc.update("update service_plans set resettable = false where id = ?::uuid",
            stalePlan.toString());

        assertThatThrownBy(() -> checkout.options(stalePlanOwner.id(), disabled.getTradeNo()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("no longer available");
        assertThatThrownBy(() -> checkout.checkout(stalePlanOwner.id(),
                disabled.getTradeNo(), UUID.randomUUID()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("no longer available");
        assertThat(jdbc.queryForObject(
            "select payment_method_id from orders where trade_no = ?",
            UUID.class, disabled.getTradeNo())).isNull();

        orders.cancel(stalePlanOwner.id(), disabled.getTradeNo());
        jdbc.update("update service_plans set resettable = true where id = ?::uuid",
            stalePlan.toString());
        ServiceOrder expired = orders.place(stalePlanOwner.id(), stalePlan,
            BillingPeriod.RESET_TRAFFIC, null);
        jdbc.update("update subscription_entitlements set expires_at = ? "
            + "where user_id = ?::uuid", Timestamp.from(clock.instant().minusSeconds(1)),
            stalePlanOwner.id().toString());
        assertThatThrownBy(() -> checkout.checkout(stalePlanOwner.id(),
                expired.getTradeNo(), UUID.randomUUID()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("no longer available");
        assertThat(jdbc.queryForObject(
            "select count(*) from paid_traffic_reset_claims where user_id = ?::uuid",
            Long.class, stalePlanOwner.id().toString())).isZero();
        assertThat(cycleEnd).isAfter(clock.instant());
    }

    @Test
    void validCashierSnapshotSurvivesAdminPriceDisableAndConcurrentDuplicateCallbacks()
        throws Exception {
        Buyer buyer = buyer("snapshot-callback");
        UUID plan = plan("Snapshot plan", 1_500, true);
        Instant cycleEnd = activeEntitlement(buyer, plan);
        balanceLedger.credit(buyer.id(), 500, BalanceLogType.ORDER_REFUND,
            null, null, clock.instant());
        ServiceOrder order = orders.place(buyer.id(), plan,
            BillingPeriod.RESET_TRAFFIC, null);
        assertThat(order.getTotalAmount()).isEqualTo(1_000);
        assertThat(order.getBalanceAmount()).isEqualTo(500);
        assertThat(order.getResetCycleEnd()).isEqualTo(cycleEnd);

        Epay epay = epay(100);
        PaymentRedirect redirect = checkout.checkout(buyer.id(), order.getTradeNo(), epay.id());
        assertThat(redirect.data()).contains("/submit.php");
        jdbc.update("update service_plans set resettable = false where id = ?::uuid",
            plan.toString());
        jdbc.update("update service_plan_prices set amount_minor = 2500 "
            + "where plan_id = ?::uuid and billing_period = 'RESET_TRAFFIC'",
            plan.toString());

        Map<String, String> callback = signedCallback(epay.uuid(), order.getTradeNo(), "11");
        concurrentCallbacks(epay.uuid(), callback);

        Map<String, Object> row = orderRow(order.getTradeNo());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("settlement_outcome")).isEqualTo("SERVICE_FULFILLED");
        assertThat(row.get("returned_balance_minor")).isEqualTo(0L);
        assertThat(jdbc.queryForObject(
            "select next_reset_at from subscription_entitlements where user_id = ?::uuid",
            Timestamp.class, buyer.id().toString()).toInstant()).isEqualTo(cycleEnd);
        assertThat(jdbc.queryForObject(
            "select expires_at from subscription_entitlements where user_id = ?::uuid",
            Timestamp.class, buyer.id().toString())).isEqualTo(row.get("expires_at"));
        assertThat(jdbc.queryForObject(
            "select uploaded_bytes + downloaded_bytes from subscription_entitlements "
                + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from paid_traffic_reset_claims where trade_no = ?",
            Long.class, order.getTradeNo())).isEqualTo(1L);
        assertThat(balance(buyer.id())).isZero();
    }

    @Test
    void expiryAfterCashierStartReturnsGatewayFeeAndLaterManualSignalCannotRepeatIt()
        throws Exception {
        Buyer buyer = buyer("expired-after-cashier");
        UUID plan = plan("Expires during payment", 1_500, true);
        activeEntitlement(buyer, plan);
        ServiceOrder order = orders.place(buyer.id(), plan,
            BillingPeriod.RESET_TRAFFIC, null);
        Epay epay = epay(100);
        checkout.checkout(buyer.id(), order.getTradeNo(), epay.id());
        jdbc.update("update subscription_entitlements set expires_at = ? "
            + "where user_id = ?::uuid", Timestamp.from(clock.instant().minusSeconds(1)),
            buyer.id().toString());

        assertThat(notifyGateway(epay.uuid(), signedCallback(epay.uuid(),
            order.getTradeNo(), "16"))).isEqualTo("success");

        Map<String, Object> row = orderRow(order.getTradeNo());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("settlement_outcome")).isEqualTo("BALANCE_RETURNED");
        assertThat(row.get("returned_balance_minor")).isEqualTo(1_600L);
        assertThat(balance(buyer.id())).isEqualTo(1_600L);
        assertThat(jdbc.queryForObject(
            "select uploaded_bytes + downloaded_bytes from subscription_entitlements "
                + "where user_id = ?::uuid", Long.class, buyer.id().toString()))
            .isEqualTo(357L);
        assertThat(jdbc.queryForObject(
            "select count(*) from paid_traffic_reset_claims where trade_no = ?",
            Long.class, order.getTradeNo())).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from traffic_reset_records where user_id = ?::uuid",
            Long.class, buyer.id().toString())).isZero();
        assertThatThrownBy(() -> fulfilment.settleManually(order.getTradeNo()))
            .isInstanceOf(ApiProblemException.class);

        assertThat(notifyGateway(epay.uuid(), signedCallback(epay.uuid(),
            order.getTradeNo(), "16"))).isEqualTo("success");
        assertThat(balance(buyer.id())).isEqualTo(1_600L);
    }

    @Test
    void cycleRolloverDuringCashierReturnsCapturedFundsWithoutResetOrClaim() throws Exception {
        Buyer buyer = buyer("stale-paid-reset");
        UUID plan = plan("Rollover plan", 1_500, true);
        Instant cycleEnd = activeEntitlement(buyer, plan);
        balanceLedger.credit(buyer.id(), 500, BalanceLogType.ORDER_REFUND,
            null, null, clock.instant());
        ServiceOrder order = orders.place(buyer.id(), plan,
            BillingPeriod.RESET_TRAFFIC, null);
        Epay epay = epay(100);
        checkout.checkout(buyer.id(), order.getTradeNo(), epay.id());

        Instant overdue = clock.instant().minusSeconds(1);
        jdbc.update("update subscription_entitlements set next_reset_at = ?, "
            + "uploaded_bytes = 444, downloaded_bytes = 555 where user_id = ?::uuid",
            Timestamp.from(overdue), buyer.id().toString());
        Instant nextCycle = MonthlyResetSchedule.followingBoundary(
            com.sinx.platform.catalog.domain.TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            overdue, clock.instant());
        nextCycle = postgresMicros(nextCycle);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<?> autoReset = CompletableFuture.runAsync(() -> {
                await(start);
                trafficResets.resetDueEntitlement(entitlementId(buyer.id()));
            }, workers);
            CompletableFuture<?> callback = CompletableFuture.runAsync(() -> {
                await(start);
                notifyGateway(epay.uuid(), signedCallback(epay.uuid(),
                    order.getTradeNo(), "11"));
            }, workers);
            start.countDown();
            CompletableFuture.allOf(autoReset, callback).get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        Map<String, Object> row = orderRow(order.getTradeNo());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("settlement_outcome")).isEqualTo("BALANCE_RETURNED");
        assertThat(row.get("returned_balance_minor")).isEqualTo(1_600L);
        assertThat(balance(buyer.id())).isEqualTo(1_600L);
        assertThat(jdbc.queryForObject(
            "select next_reset_at from subscription_entitlements where user_id = ?::uuid",
            Timestamp.class, buyer.id().toString()).toInstant()).isEqualTo(nextCycle);
        assertThat(jdbc.queryForObject(
            "select count(*) from paid_traffic_reset_claims where user_id = ?::uuid",
            Long.class, buyer.id().toString())).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from traffic_reset_records where user_id = ?::uuid",
            Long.class, buyer.id().toString())).isEqualTo(1L);

        String retry = notifyGateway(epay.uuid(), signedCallback(epay.uuid(),
            order.getTradeNo(), "11"));
        assertThat(retry).isEqualTo("success");
        assertThat(balance(buyer.id())).isEqualTo(1_600L);
        assertThat(cycleEnd).isNotEqualTo(nextCycle);
    }

    @Test
    void validLegacySubTenGatewayCallbackStillFulfilsItsSnapshottedOrder() {
        Buyer buyer = buyer("legacy-minimum-callback");
        UUID plan = plan("Legacy small order", 900, false);
        Epay epay = epay(100);
        ServiceOrder order = orders.place(buyer.id(), plan, BillingPeriod.MONTHLY, null);
        // Represents a cashier session initiated before the online minimum was
        // introduced; its server-side pricing and fee are already snapshotted.
        jdbc.update("update orders set payment_method_id = ?::uuid, gateway = 'EPay', "
                + "handling_amount = 100 where trade_no = ?",
            epay.id().toString(), order.getTradeNo());

        assertThat(notifyGateway(epay.uuid(), signedCallback(epay.uuid(),
            order.getTradeNo(), "10"))).isEqualTo("success");

        Map<String, Object> row = orderRow(order.getTradeNo());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("settlement_outcome")).isEqualTo("SERVICE_FULFILLED");
        assertThat(jdbc.queryForObject("select count(*) from subscription_entitlements "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isEqualTo(1L);
    }

    @Test
    void periodicValuationUsesOnlyCurrentAndFutureFundedSegmentsAndPaysDeferredCreditOnce() {
        Buyer buyer = buyer("periodic-segments");
        UUID currentPlan = plan("Current periodic", 20_000, false);
        UUID targetPlan = plan("Target periodic", 5_000, false);
        Instant now = clock.instant();
        Instant oldStart = postgresMicros(now.minus(Duration.ofDays(100)));
        Instant currentStart = postgresMicros(now.minus(Duration.ofDays(15)));
        Instant currentEnd = postgresMicros(
            currentStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        Instant futureEnd = postgresMicros(
            currentEnd.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        activeEntitlement(buyer, currentPlan, currentStart, futureEnd,
            postgresMicros(now.plus(Duration.ofDays(8))), 0, 0);

        UUID expiredSource = seedPeriodicOrder(buyer.id(), currentPlan, 10_000,
            oldStart, null, null, "NEW_PURCHASE");
        UUID activeSource = seedPeriodicOrder(buyer.id(), currentPlan, 20_000,
            currentStart, currentStart, currentEnd, "NEW_PURCHASE");
        UUID futureSource = seedPeriodicOrder(buyer.id(), currentPlan, 30_000,
            currentStart, currentEnd, futureEnd, "RENEWAL");

        long activeRemainder = BigInteger.valueOf(20_000)
            .multiply(nanos(Duration.between(now, currentEnd)))
            .divide(nanos(Duration.between(currentStart, currentEnd)))
            .longValueExact();
        long expectedValue = activeRemainder + 30_000;
        OrderQuoteView standard = orders.quote(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.STANDARD);
        assertThat(standard.breakdown().surplusAmount())
            .isEqualTo(expectedValue);

        OrderQuoteView full = orders.quote(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.FULL_PAYMENT);
        assertThat(full.deferredSurplusCreditMinor())
            .isEqualTo(expectedValue);
        ServiceOrder cancelled = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.FULL_PAYMENT);
        assertThat(cancelled.getSurplusOrderIds())
            .contains(activeSource.toString()).contains(futureSource.toString())
            .doesNotContain(expiredSource.toString());
        assertThat(balance(buyer.id())).isZero();
        orders.cancel(buyer.id(), cancelled.getTradeNo());
        assertThat(jdbc.queryForObject(
            "select status from orders where id = ?::uuid", String.class,
            activeSource.toString())).isEqualTo("COMPLETED");
        assertThat(balance(buyer.id())).isZero();

        ServiceOrder settled = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.FULL_PAYMENT);
        long paidCoverageCredit = settled.getDeferredSurplusCreditMinor();
        assertThat(paidCoverageCredit).isEqualTo(expectedValue);
        fulfilment.settleManually(settled.getTradeNo());
        assertThat(balance(buyer.id())).isEqualTo(paidCoverageCredit);
        assertThat(jdbc.queryForObject(
            "select status from orders where id = ?::uuid", String.class,
            activeSource.toString())).isEqualTo("DISCOUNTED");
        assertThat(jdbc.queryForObject(
            "select status from orders where id = ?::uuid", String.class,
            futureSource.toString())).isEqualTo("DISCOUNTED");
        assertThat(jdbc.queryForObject(
            "select status from orders where id = ?::uuid", String.class,
            expiredSource.toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
            "select count(*) from balance_logs where trade_no = ? and type = 'SURPLUS_CREDIT'",
            Long.class, settled.getTradeNo())).isEqualTo(1L);
    }

    @Test
    void actualSettlementTimeStartsTheCoverageSegmentAfterALongPendingOrder() {
        Buyer buyer = buyer("delayed-monthly-payment");
        UUID plan = plan("Delayed first period", 10_000, false);
        ServiceOrder order = orders.place(buyer.id(), plan, BillingPeriod.MONTHLY, null);
        Instant createdAt = clock.instant().minus(Duration.ofDays(45));
        jdbc.update("update orders set created_at = ? where trade_no = ?",
            Timestamp.from(createdAt), order.getTradeNo());

        fulfilment.settleManually(order.getTradeNo());

        Map<String, Object> row = jdbc.queryForMap("""
            select o.paid_at, o.coverage_start, o.coverage_end, e.expires_at
            from orders o
            join subscription_entitlements e on o.user_id = e.user_id
            where o.trade_no = ?
            """, order.getTradeNo());
        Instant paidAt = ((Timestamp) row.get("paid_at")).toInstant();
        assertThat(paidAt).isAfter(createdAt);
        assertThat(((Timestamp) row.get("coverage_start")).toInstant()).isEqualTo(paidAt);
        assertThat(((Timestamp) row.get("coverage_end")).toInstant())
            .isEqualTo(paidAt.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        assertThat(((Timestamp) row.get("expires_at")).toInstant())
            .isEqualTo(((Timestamp) row.get("coverage_end")).toInstant());
    }

    private Buyer buyer(String name) {
        UUID id = UUID.randomUUID();
        String email = name + "-" + id.toString().substring(0, 8) + "@example.test";
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into users (id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at)
            values (?::uuid, ?, 'hash', ?, 'ACTIVE', ?, ?, ?)
            """, id.toString(), email, name,
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""), now, now);
        jdbc.update("insert into user_roles (user_id, role_code) values (?::uuid, 'USER')",
            id.toString());
        return new Buyer(id, email);
    }

    private UUID plan(String name, long amountMinor, boolean resettable) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into service_plans (id, name, description, plan_type,
                transfer_limit_bytes, speed_limit_mbps, reset_policy, resettable,
                published, sellable, renewable, sort_order, created_at, updated_at)
            values (?::uuid, ?, '', 'SUBSCRIPTION', 1073741824, 100,
                'MONTHLY_FROM_ACTIVATION', ?, true, true, true, 0, ?, ?)
            """, id.toString(), name, resettable, now, now);
        jdbc.update("""
            insert into service_plan_prices (id, plan_id, billing_period,
                amount_minor, currency) values (?::uuid, ?::uuid, 'MONTHLY', ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), amountMinor);
        if (resettable) {
            jdbc.update("""
                insert into service_plan_prices (id, plan_id, billing_period,
                    amount_minor, currency) values (?::uuid, ?::uuid, 'RESET_TRAFFIC', ?, 'CNY')
                """, UUID.randomUUID().toString(), id.toString(), amountMinor);
        }
        return id;
    }

    private Instant activeEntitlement(Buyer buyer, UUID planId) {
        Instant now = clock.instant();
        Instant startsAt = now.minus(Duration.ofDays(15));
        return activeEntitlement(buyer, planId, startsAt,
            now.plus(Duration.ofDays(75)),
            MonthlyResetSchedule.initialBoundary(startsAt), 123, 234);
    }

    private Instant activeEntitlement(Buyer buyer, UUID planId, Instant startsAt,
        Instant expiresAt, Instant cycleEnd, long uploaded, long downloaded) {
        Instant now = clock.instant();
        jdbc.update("""
            insert into subscription_entitlements (id, user_id, plan_id, plan_name,
                transfer_limit_bytes, uploaded_bytes, downloaded_bytes, reset_policy,
                starts_at, expires_at, next_reset_at, created_at, updated_at, is_trial)
            values (?::uuid, ?::uuid, ?::uuid, 'Plan', 1073741824, ?, ?,
                'MONTHLY_FROM_ACTIVATION', ?, ?, ?, ?, ?, false)
            """, UUID.randomUUID().toString(), buyer.id().toString(), planId.toString(),
            uploaded, downloaded, Timestamp.from(startsAt), Timestamp.from(expiresAt),
            Timestamp.from(cycleEnd), Timestamp.from(now), Timestamp.from(now));
        return cycleEnd;
    }

    private Epay epay(long feeMinor) {
        UUID id = UUID.randomUUID();
        String uuid = UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(clock.instant());
        String config = "{\"url\":\"https://pay.example.test\",\"pid\":\"1000\","
            + "\"key\":\"" + EPAY_KEY + "\",\"type\":\"alipay\"}";
        jdbc.update("""
            insert into payment_methods (id, uuid, gateway, name, config,
                handling_fee_fixed, enabled, sort_order, created_at, updated_at)
            values (?::uuid, ?, 'EPay', 'Test EPay', ?, ?, true, 0, ?, ?)
            """, id.toString(), uuid, config, feeMinor, now, now);
        return new Epay(id, uuid);
    }

    private void concurrentCallbacks(String uuid, Map<String, String> params)
        throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
                await(start);
                return notifyGateway(uuid, new LinkedHashMap<>(params));
            }, workers);
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                await(start);
                return notifyGateway(uuid, new LinkedHashMap<>(params));
            }, workers);
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("success");
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("success");
        } finally {
            workers.shutdownNow();
        }
    }

    private String notifyGateway(String uuid, Map<String, String> params) {
        try {
            MockHttpServletRequestBuilder request = get(
                "/api/v1/guest/payment/notify/epay/{uuid}", uuid);
            params.forEach(request::param);
            return mvc.perform(request).andReturn().getResponse().getContentAsString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, String> signedCallback(String uuid, String tradeNo,
        String amount) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1000");
        params.put("trade_no", "T" + UUID.randomUUID().toString().replace("-", ""));
        params.put("out_trade_no", tradeNo);
        params.put("type", "alipay");
        params.put("name", tradeNo);
        params.put("money", amount);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_url", "https://notify.example.test/" + uuid);
        params.put("return_url", "https://site.example.test/orders/" + tradeNo);
        TreeMap<String, String> signed = new TreeMap<>(params);
        StringBuilder canonical = new StringBuilder();
        signed.forEach((key, value) -> {
            if (canonical.length() > 0) canonical.append('&');
            canonical.append(key).append('=').append(value.replace("\\", ""));
        });
        params.put("sign", md5(canonical + EPAY_KEY));
        params.put("sign_type", "MD5");
        return params;
    }

    private static String md5(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID entitlementId(UUID userId) {
        return jdbc.queryForObject("select id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, userId.toString());
    }

    private Map<String, Object> orderRow(String tradeNo) {
        return jdbc.queryForMap("""
            select o.status, o.settlement_outcome, o.returned_balance_minor,
                   e.expires_at, o.balance_amount, o.total_amount, o.handling_amount
            from orders o join subscription_entitlements e on e.user_id = o.user_id
            where o.trade_no = ?
            """, tradeNo);
    }

    private long balance(UUID userId) {
        return jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, userId.toString());
    }

    private UUID seedPeriodicOrder(UUID userId, UUID plan, long amount,
        Instant paidAt, Instant coverageStart, Instant coverageEnd, String orderType) {
        UUID id = UUID.randomUUID();
        String tradeNo = "HIST" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 28);
        Timestamp created = Timestamp.from(paidAt.minus(Duration.ofDays(45)));
        jdbc.update("""
            insert into orders (id, trade_no, user_id, plan_id, plan_name, period,
                order_type, status, currency, original_amount, total_amount,
                created_at, updated_at, paid_at, coverage_start, coverage_end)
            values (?::uuid, ?, ?::uuid, ?::uuid, 'History', 'MONTHLY', ?, 'COMPLETED',
                'CNY', ?, ?, ?, ?, ?, ?, ?)
            """, id.toString(), tradeNo, userId.toString(), plan.toString(),
            orderType, amount, amount, created, created, Timestamp.from(paidAt),
            coverageStart == null ? null : Timestamp.from(coverageStart),
            coverageEnd == null ? null : Timestamp.from(coverageEnd));
        return id;
    }

    private static BigInteger nanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
            .multiply(BigInteger.valueOf(1_000_000_000L))
            .add(BigInteger.valueOf(duration.getNano()));
    }

    /** PostgreSQL timestamptz is stored at microsecond precision. */
    private static Instant postgresMicros(Instant instant) {
        long seconds = instant.getEpochSecond();
        int micros = (instant.getNano() + 500) / 1_000;
        if (micros == 1_000_000) {
            seconds++;
            micros = 0;
        }
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The concurrent callback gate timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(postgresMicros(Instant.now()), ZoneOffset.UTC);
        }
    }

    private static final class MutableTestClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableTestClock(Instant initial, ZoneId zone) {
            this(new AtomicReference<>(postgresMicros(initial)), zone);
        }

        private MutableTestClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void set(Instant value) {
            current.set(postgresMicros(value));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableTestClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    private record Buyer(UUID id, String email) { }
    private record Epay(UUID id, String uuid) { }
}
