package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.repository.BalanceLogRepository;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.application.OrderAssignmentService;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL regressions for refunds after order ownership is reassigned. */
@SpringBootTest
@Import(OrderBalancePayerAssignmentIntegrationTest.TestClockConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OrderBalancePayerAssignmentIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_order_balance_payer_test")
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
    @Autowired private OrderAssignmentService assignments;
    @Autowired private OrderFulfilmentService fulfilment;
    @Autowired private BalanceLedgerService balanceLedger;
    @Autowired private BalanceLogRepository balanceLogs;
    @Autowired private ServiceOrderRepository serviceOrders;
    @Autowired private MutableTestClock clock;

    @BeforeEach
    void setClockToPostgresPrecision() {
        clock.set(postgresMicros(Instant.now()));
    }

    @Test
    void cancelingAnAssignedBalanceFundedPendingOrderRefundsItsOriginalPayerOnce() {
        Buyer payer = buyer("assigned-cancel-payer");
        Buyer currentOwner = buyer("assigned-cancel-owner");
        UUID planId = plan("Assigned pending purchase", 2_000, false);
        balanceLedger.setBalanceTarget(payer.id(), 500, null);

        ServiceOrder pending = orders.place(payer.id(), planId, BillingPeriod.MONTHLY, null);
        assertThat(pending.getBalanceAmount()).isEqualTo(500);
        assignments.assign(pending.getTradeNo(), currentOwner.id());

        ServiceOrder cancelled = orders.cancel(currentOwner.id(), pending.getTradeNo());
        assertThat(cancelled.getStatus().name()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> orders.cancel(currentOwner.id(), pending.getTradeNo()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("no longer be cancelled");

        assertThat(orderUser(pending.getTradeNo())).isEqualTo(currentOwner.id());
        assertThat(balancePayer(pending.getTradeNo())).isEqualTo(payer.id());
        assertThat(balance(payer.id())).isEqualTo(500);
        assertThat(balance(currentOwner.id())).isZero();
        assertThat(ledgerAmount(payer.id(), pending.getTradeNo(), "ORDER_PAYMENT"))
            .isEqualTo(-500);
        assertThat(ledgerAmount(payer.id(), pending.getTradeNo(), "ORDER_REFUND"))
            .isEqualTo(500);
        assertThat(ledgerCount(payer.id(), pending.getTradeNo(), "ORDER_REFUND"))
            .isEqualTo(1);
        assertThat(ledgerCount(currentOwner.id(), pending.getTradeNo(), "ORDER_REFUND"))
            .isZero();
        assertThat(balanceLogs.findTradeNumbersOwnedByUser(payer.id(),
            List.of(pending.getTradeNo()))).isEmpty();
        assertThat(balanceLogs.findTradeNumbersOwnedByUser(currentOwner.id(),
            List.of(pending.getTradeNo()))).containsExactly(pending.getTradeNo());
    }

    @Test
    void staleCapturedResetRefundReturnsBalanceAndCashToTheOriginalPayer() {
        Buyer payer = buyer("assigned-reset-payer");
        Buyer currentOwner = buyer("assigned-reset-owner");
        UUID planId = plan("Assigned reset order", 2_000, true);
        balanceLedger.setBalanceTarget(payer.id(), 500, null);
        entitlement(payer.id(), planId);

        ServiceOrder pending = orders.place(payer.id(), planId,
            BillingPeriod.RESET_TRAFFIC, null);
        assertThat(pending.getBalanceAmount()).isEqualTo(500);
        // Model an entitlement disappearing while its already-issued cashier
        // order remains open; the administrator may then reassign that order.
        jdbc.update("delete from subscription_entitlements where user_id = ?::uuid",
            payer.id().toString());
        assignments.assign(pending.getTradeNo(), currentOwner.id());

        fulfilment.settle(pending.getTradeNo(), "captured-reset-payment");
        fulfilment.settle(pending.getTradeNo(), "duplicate-captured-reset-payment");

        long returned = pending.getTotalAmount() + pending.getBalanceAmount();
        assertThat(orderUser(pending.getTradeNo())).isEqualTo(currentOwner.id());
        assertThat(balancePayer(pending.getTradeNo())).isEqualTo(payer.id());
        assertThat(balance(payer.id())).isEqualTo(returned);
        assertThat(balance(currentOwner.id())).isZero();
        assertThat(ledgerAmount(payer.id(), pending.getTradeNo(), "ORDER_PAYMENT"))
            .isEqualTo(-500);
        assertThat(ledgerAmount(payer.id(), pending.getTradeNo(), "ORDER_REFUND"))
            .isEqualTo(returned);
        assertThat(ledgerCount(payer.id(), pending.getTradeNo(), "ORDER_REFUND"))
            .isEqualTo(1);
        assertThat(ledgerCount(currentOwner.id(), pending.getTradeNo(), "ORDER_REFUND"))
            .isZero();
        assertThat(serviceOrders.findByTradeNo(pending.getTradeNo()).orElseThrow()
            .getSettlementOutcome().name()).isEqualTo("BALANCE_RETURNED");
        assertThat(balanceLogs.findTradeNumbersOwnedByUser(payer.id(),
            List.of(pending.getTradeNo()))).isEmpty();
        assertThat(balanceLogs.findTradeNumbersOwnedByUser(currentOwner.id(),
            List.of(pending.getTradeNo()))).containsExactly(pending.getTradeNo());
    }

    private Buyer buyer(String prefix) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into users (id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at)
            values (?::uuid, ?, 'hash', ?, 'ACTIVE', ?, ?, ?)
            """, id.toString(), prefix + "-" + id.toString().substring(0, 8)
                + "@example.test", prefix,
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""), now, now);
        jdbc.update("insert into user_roles (user_id, role_code) values (?::uuid, 'USER')",
            id.toString());
        return new Buyer(id);
    }

    private UUID plan(String name, long priceMinor, boolean resettable) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into service_plans (id, name, description, plan_type,
                transfer_limit_bytes, speed_limit_mbps, reset_policy, resettable,
                published, sellable, renewable, sort_order, created_at, updated_at)
            values (?::uuid, ?, '', 'SUBSCRIPTION', 1000, 100,
                'MONTHLY_FROM_ACTIVATION', ?, true, true, true, 0, ?, ?)
            """, id.toString(), name, resettable, now, now);
        jdbc.update("""
            insert into service_plan_prices (id, plan_id, billing_period,
                amount_minor, currency) values (?::uuid, ?::uuid, 'MONTHLY', ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), priceMinor);
        if (resettable) {
            jdbc.update("""
                insert into service_plan_prices (id, plan_id, billing_period,
                    amount_minor, currency)
                values (?::uuid, ?::uuid, 'RESET_TRAFFIC', ?, 'CNY')
                """, UUID.randomUUID().toString(), id.toString(), priceMinor);
        }
        return id;
    }

    private void entitlement(UUID userId, UUID planId) {
        Instant startsAt = clock.instant().minusSeconds(10 * 24 * 60 * 60L);
        Instant cycleEnd = clock.instant().plusSeconds(20 * 24 * 60 * 60L);
        Instant expiresAt = clock.instant().plusSeconds(60 * 24 * 60 * 60L);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into subscription_entitlements (id, user_id, plan_id, plan_name,
                transfer_limit_bytes, uploaded_bytes, downloaded_bytes, reset_policy,
                starts_at, expires_at, next_reset_at, traffic_cycle_start,
                traffic_cycle_end, traffic_cycle_id, created_at, updated_at, is_trial)
            values (?::uuid, ?::uuid, ?::uuid, 'Assigned reset order', 1000, 0, 0,
                'MONTHLY_FROM_ACTIVATION', ?, ?, ?, ?, ?, ?::uuid, ?, ?, false)
            """, UUID.randomUUID().toString(), userId.toString(), planId.toString(),
            Timestamp.from(startsAt), Timestamp.from(expiresAt), Timestamp.from(cycleEnd),
            Timestamp.from(startsAt), Timestamp.from(cycleEnd), UUID.randomUUID().toString(),
            now, now);
    }

    private UUID orderUser(String tradeNo) {
        return jdbc.queryForObject("select user_id from orders where trade_no = ?",
            UUID.class, tradeNo);
    }

    private UUID balancePayer(String tradeNo) {
        return jdbc.queryForObject("select balance_payer_user_id from orders where trade_no = ?",
            UUID.class, tradeNo);
    }

    private long balance(UUID userId) {
        return jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, userId.toString());
    }

    private long ledgerAmount(UUID userId, String tradeNo, String type) {
        return jdbc.queryForObject("""
            select coalesce(sum(amount_minor), 0) from balance_logs
            where user_id = ?::uuid and trade_no = ? and type = ?
            """, Long.class, userId.toString(), tradeNo, type);
    }

    private long ledgerCount(UUID userId, String tradeNo, String type) {
        return jdbc.queryForObject("""
            select count(*) from balance_logs
            where user_id = ?::uuid and trade_no = ? and type = ?
            """, Long.class, userId.toString(), tradeNo, type);
    }

    private static Instant postgresMicros(Instant instant) {
        return Instant.ofEpochSecond(instant.getEpochSecond(),
            instant.getNano() / 1_000 * 1_000L);
    }

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(Instant.now(), ZoneOffset.UTC);
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

        void set(Instant instant) { current.set(postgresMicros(instant)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId value) { return new MutableTestClock(current, value); }
        @Override public Instant instant() { return current.get(); }
    }

    private record Buyer(UUID id) { }
}
