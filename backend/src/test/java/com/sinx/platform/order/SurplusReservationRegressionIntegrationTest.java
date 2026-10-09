package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderQuoteView;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.application.SurplusValuation;
import com.sinx.platform.order.domain.OrderDeductionMode;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/** Regression coverage for deferred STANDARD valuation and retired funding sources. */
@SpringBootTest
@Import(SurplusReservationRegressionIntegrationTest.TestClockConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SurplusReservationRegressionIntegrationTest {

    private static final long QUOTA = 1_000L;
    private static final long SOURCE_PRICE = 10_000L;
    private static final long TARGET_PRICE = 10_000L;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_surplus_reservation_test")
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
    @Autowired private SurplusValuation surplusValuation;
    @Autowired private SubscriptionEntitlementRepository entitlements;
    @Autowired private ServiceOrderRepository serviceOrders;
    @Autowired private MutableTestClock clock;

    @BeforeEach
    void setClockToPostgresPrecision() {
        clock.set(Instant.now());
    }

    @Test
    void standardSettlementFreezesCheckoutTimeForPaymentAndManualSuccess() {
        for (boolean manual : List.of(false, true)) {
            Buyer buyer = buyer("standard-time-" + manual);
            UUID sourcePlan = plan("Standard source " + manual,
                PlanType.SUBSCRIPTION, SOURCE_PRICE, BillingPeriod.MONTHLY);
            UUID targetPlan = plan("Standard target " + manual,
                PlanType.SUBSCRIPTION, TARGET_PRICE, BillingPeriod.MONTHLY);
            Instant now = clock.instant();
            Instant coverageStart = micros(now.minus(Duration.ofDays(25)));
            Instant cycleEnd = micros(now.plus(Duration.ofDays(5)));
            Instant expiresAt = micros(now.plus(Duration.ofDays(60)));
            entitlement(buyer, sourcePlan, PlanType.SUBSCRIPTION, coverageStart,
                expiresAt, coverageStart, cycleEnd, 0);
            seedFunding(buyer.id(), sourcePlan, BillingPeriod.MONTHLY,
                SOURCE_PRICE, coverageStart, cycleEnd);

            ServiceOrder pending = orders.place(buyer.id(), targetPlan,
                BillingPeriod.MONTHLY, null, OrderDeductionMode.STANDARD);
            assertThat(pending.getSurplusAmount()).isPositive();
            long checkoutSurplus = pending.getSurplusAmount();

            clock.set(now.plus(Duration.ofDays(4)));
            long currentValue = surplusValuation.valueOf(
                entitlements.findByUserId(buyer.id()).orElseThrow(), clock.instant())
                .amountMinor();
            assertThat(currentValue).isLessThan(checkoutSurplus);

            if (manual) {
                fulfilment.settleManually(pending.getTradeNo());
            } else {
                fulfilment.settle(pending.getTradeNo(), "verified-gateway-transaction");
            }

            assertThat(status(pending)).isEqualTo("COMPLETED");
            assertThat(outcome(pending)).isEqualTo("SERVICE_FULFILLED");
            assertThat(jdbc.queryForObject("select plan_id from subscription_entitlements "
                + "where user_id = ?::uuid", UUID.class, buyer.id().toString()))
                .isEqualTo(targetPlan);
        }
    }

    @Test
    void settlementStillReturnsPaymentWhenUsageArrivesAfterStandardReservation() {
        Buyer buyer = buyer("standard-inflight-refund");
        UUID sourcePlan = plan("In-flight source", PlanType.SUBSCRIPTION,
            SOURCE_PRICE, BillingPeriod.MONTHLY);
        UUID targetPlan = plan("In-flight target", PlanType.SUBSCRIPTION,
            TARGET_PRICE, BillingPeriod.MONTHLY);
        Instant now = clock.instant();
        Instant coverageStart = micros(now.minus(Duration.ofDays(25)));
        Instant cycleEnd = micros(now.plus(Duration.ofDays(5)));
        entitlement(buyer, sourcePlan, PlanType.SUBSCRIPTION, coverageStart,
            micros(now.plus(Duration.ofDays(60))), coverageStart, cycleEnd, 0);
        seedFunding(buyer.id(), sourcePlan, BillingPeriod.MONTHLY,
            SOURCE_PRICE, coverageStart, cycleEnd);

        ServiceOrder pending = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.STANDARD);
        assertThat(pending.getSurplusAmount()).isPositive();

        // A node report already in flight can arrive after checkout removed the
        // entitlement from the next node-user snapshot.
        jdbc.update("update subscription_entitlements set uploaded_bytes = ? "
            + "where user_id = ?::uuid", QUOTA, buyer.id().toString());
        clock.set(now.plus(Duration.ofDays(4)));
        fulfilment.settle(pending.getTradeNo(), "verified-gateway-transaction");

        assertThat(status(pending)).isEqualTo("COMPLETED");
        assertThat(outcome(pending)).isEqualTo("BALANCE_RETURNED");
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isEqualTo(pending.getTotalAmount());
        assertThat(jdbc.queryForObject("select plan_id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, buyer.id().toString()))
            .isEqualTo(sourcePlan);
        assertThat(jdbc.queryForObject("select surplus_reserved from subscription_entitlements "
            + "where user_id = ?::uuid", Boolean.class, buyer.id().toString())).isFalse();
    }

    @Test
    void settlementStillReturnsPaymentWhenAnAdminReducesTheFundedExpiry() {
        Buyer buyer = buyer("standard-admin-change-refund");
        UUID sourcePlan = plan("Admin-change source", PlanType.SUBSCRIPTION,
            SOURCE_PRICE, BillingPeriod.MONTHLY);
        UUID targetPlan = plan("Admin-change target", PlanType.SUBSCRIPTION,
            TARGET_PRICE, BillingPeriod.MONTHLY);
        Instant now = clock.instant();
        Instant coverageStart = micros(now.minus(Duration.ofDays(25)));
        Instant cycleEnd = micros(now.plus(Duration.ofDays(5)));
        entitlement(buyer, sourcePlan, PlanType.SUBSCRIPTION, coverageStart,
            micros(now.plus(Duration.ofDays(60))), coverageStart, cycleEnd, 0);
        seedFunding(buyer.id(), sourcePlan, BillingPeriod.MONTHLY,
            SOURCE_PRICE, coverageStart, cycleEnd);

        ServiceOrder pending = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.STANDARD);
        assertThat(pending.getSurplusAmount()).isPositive();

        jdbc.update("update subscription_entitlements set expires_at = ? "
            + "where user_id = ?::uuid", Timestamp.from(now.plus(Duration.ofDays(1))),
            buyer.id().toString());
        clock.set(now.plus(Duration.ofDays(4)));
        fulfilment.settle(pending.getTradeNo(), "verified-gateway-transaction");

        assertThat(outcome(pending)).isEqualTo("BALANCE_RETURNED");
        assertThat(jdbc.queryForObject("select plan_id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, buyer.id().toString()))
            .isEqualTo(sourcePlan);
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isEqualTo(pending.getTotalAmount());
    }

    @Test
    void fullPaymentStillCreditsValueAtFulfilmentTime() {
        Buyer buyer = buyer("full-payment-current-time");
        UUID sourcePlan = plan("Full-time source", PlanType.SUBSCRIPTION,
            SOURCE_PRICE, BillingPeriod.MONTHLY);
        UUID targetPlan = plan("Full-time target", PlanType.SUBSCRIPTION,
            TARGET_PRICE, BillingPeriod.MONTHLY);
        Instant now = clock.instant();
        Instant coverageStart = micros(now.minus(Duration.ofDays(25)));
        Instant cycleEnd = micros(now.plus(Duration.ofDays(5)));
        entitlement(buyer, sourcePlan, PlanType.SUBSCRIPTION, coverageStart,
            micros(now.plus(Duration.ofDays(60))), coverageStart, cycleEnd, 0);
        seedFunding(buyer.id(), sourcePlan, BillingPeriod.MONTHLY,
            SOURCE_PRICE, coverageStart, cycleEnd);

        ServiceOrder pending = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.FULL_PAYMENT);
        long checkoutValue = pending.getDeferredSurplusCreditMinor();
        clock.set(now.plus(Duration.ofDays(4)));
        long valueAtSettlement = surplusValuation.valueOf(
            entitlements.findByUserId(buyer.id()).orElseThrow(), clock.instant())
            .amountMinor();
        assertThat(valueAtSettlement).isLessThan(checkoutValue);

        fulfilment.settleManually(pending.getTradeNo());

        assertThat(jdbc.queryForObject("select deferred_surplus_credit from orders "
            + "where trade_no = ?", Long.class, pending.getTradeNo()))
            .isEqualTo(valueAtSettlement);
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isEqualTo(valueAtSettlement);
    }

    @Test
    void exhaustedReplacedActivationSourcesAreRetiredForBothModesAndPlanTypes() {
        for (PlanType type : List.of(PlanType.SUBSCRIPTION, PlanType.TRAFFIC_PACKAGE)) {
            for (OrderDeductionMode mode : List.of(
                    OrderDeductionMode.STANDARD, OrderDeductionMode.FULL_PAYMENT)) {
                String name = type.name().toLowerCase() + "-" + mode.name().toLowerCase();
                Buyer buyer = buyer("replacement-" + name);
                BillingPeriod period = type == PlanType.SUBSCRIPTION
                    ? BillingPeriod.MONTHLY : BillingPeriod.ONETIME;
                UUID planA = plan("Plan A " + name, type, 1_000, period);
                UUID planB = plan("Plan B " + name, type, 1_000, period);
                UUID planC = plan("Plan C " + name, type, 1_000, period);
                Instant now = clock.instant();
                Instant start = micros(now.minus(Duration.ofDays(5)));
                Instant end = micros(now.plus(Duration.ofDays(25)));
                Instant expiresAt = type == PlanType.SUBSCRIPTION
                    ? micros(now.plus(Duration.ofDays(60))) : null;
                entitlement(buyer, planA, type, start, expiresAt,
                    type == PlanType.SUBSCRIPTION ? start : null,
                    type == PlanType.SUBSCRIPTION ? end : null, QUOTA);
                ServiceOrder originalFunding = seedFunding(buyer.id(), planA,
                    period, 1_000, type == PlanType.SUBSCRIPTION ? start : null,
                    type == PlanType.SUBSCRIPTION ? end : null);

                ServiceOrder toB = orders.place(buyer.id(), planB, period, null, mode);
                assertThat(toB.getSurplusAmount()).isZero();
                fulfilment.settleManually(toB.getTradeNo());
                assertThat(status(originalFunding)).isEqualTo("DISCOUNTED");

                ServiceOrder backToA = orders.place(buyer.id(), planA, period, null, mode);
                if (backToA.isPending()) {
                    fulfilment.settleManually(backToA.getTradeNo());
                }
                assertThat(status(originalFunding)).isEqualTo("DISCOUNTED");
                assertThat(status(backToA)).isEqualTo("COMPLETED");

                // The new A activation has its own fresh allowance. Once that
                // allowance is exhausted, the retired first A order must not
                // be revived merely because its old coverage dates still match.
                jdbc.update("update subscription_entitlements set uploaded_bytes = ? "
                    + "where user_id = ?::uuid", QUOTA, buyer.id().toString());
                OrderQuoteView nextChange = orders.quote(buyer.id(), planC, period, null);
                assertThat(nextChange.breakdown().surplusAmount()).isZero();
            }
        }
    }

    @Test
    void continuousSamePlanRenewalKeepsItsFutureFundedSource() {
        Buyer buyer = buyer("continuous-renewal-source");
        UUID sourcePlan = plan("Renewed source", PlanType.SUBSCRIPTION,
            SOURCE_PRICE, BillingPeriod.MONTHLY);
        UUID targetPlan = plan("Renewal change target", PlanType.SUBSCRIPTION,
            TARGET_PRICE, BillingPeriod.MONTHLY);
        Instant now = clock.instant();
        Instant start = micros(now.minus(Duration.ofDays(5)));
        Instant cycleEnd = micros(now.plus(Duration.ofDays(25)));
        Instant coverageEnd = micros(start.atZone(ZoneOffset.UTC).plusYears(1).toInstant());
        entitlement(buyer, sourcePlan, PlanType.SUBSCRIPTION, start,
            micros(now.plus(Duration.ofDays(360))), start, cycleEnd, 0);
        ServiceOrder source = seedFunding(buyer.id(), sourcePlan,
            BillingPeriod.YEARLY, SOURCE_PRICE * 12, start, coverageEnd);

        ServiceOrder renewal = orders.place(buyer.id(), sourcePlan,
            BillingPeriod.MONTHLY, null, OrderDeductionMode.STANDARD);
        fulfilment.settleManually(renewal.getTradeNo());

        assertThat(status(source)).isEqualTo("COMPLETED");
        assertThat(orders.quote(buyer.id(), targetPlan, BillingPeriod.MONTHLY, null)
            .breakdown().surplusAmount()).isPositive();
    }

    private Buyer buyer(String name) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        String email = name + "-" + id.toString().substring(0, 8) + "@example.test";
        jdbc.update("""
            insert into users (id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at)
            values (?::uuid, ?, 'hash', ?, 'ACTIVE', ?, ?, ?)
            """, id.toString(), email, name,
            UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""), now, now);
        jdbc.update("insert into user_roles (user_id, role_code) values (?::uuid, 'USER')",
            id.toString());
        return new Buyer(id);
    }

    private UUID plan(String name, PlanType type, long price, BillingPeriod period) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        String policy = type == PlanType.TRAFFIC_PACKAGE
            ? "NEVER" : "MONTHLY_FROM_ACTIVATION";
        jdbc.update("""
            insert into service_plans (id, name, description, plan_type,
                transfer_limit_bytes, speed_limit_mbps, reset_policy, resettable,
                published, sellable, renewable, sort_order, created_at, updated_at)
            values (?::uuid, ?, '', ?, ?, 100, ?, false, true, true, ?, 0, ?, ?)
            """, id.toString(), name, type.name(), QUOTA, policy,
            type == PlanType.SUBSCRIPTION, now, now);
        jdbc.update("""
            insert into service_plan_prices (id, plan_id, billing_period,
                amount_minor, currency) values (?::uuid, ?::uuid, ?, ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), period.name(), price);
        return id;
    }

    private void entitlement(Buyer buyer, UUID planId, PlanType type,
        Instant startsAt, Instant expiresAt, Instant cycleStart, Instant cycleEnd,
        long usedBytes) {
        Instant now = clock.instant();
        jdbc.update("""
            insert into subscription_entitlements (id, user_id, plan_id, plan_name,
                transfer_limit_bytes, uploaded_bytes, downloaded_bytes, reset_policy,
                starts_at, expires_at, next_reset_at, traffic_cycle_start,
                traffic_cycle_end, traffic_cycle_id, cycle_consumed_bytes,
                surplus_reserved, created_at, updated_at, is_trial)
            values (?::uuid, ?::uuid, ?::uuid, 'Test entitlement', ?, ?, 0, ?,
                ?, ?, ?, ?, ?, ?::uuid, 0, false, ?, ?, false)
            """, UUID.randomUUID().toString(), buyer.id().toString(), planId.toString(),
            QUOTA, usedBytes, type == PlanType.TRAFFIC_PACKAGE
                ? "NEVER" : "MONTHLY_FROM_ACTIVATION",
            Timestamp.from(startsAt), timestamp(expiresAt), timestamp(cycleEnd),
            timestamp(cycleStart), timestamp(cycleEnd), UUID.randomUUID().toString(),
            Timestamp.from(now), Timestamp.from(now));
    }

    private ServiceOrder seedFunding(UUID userId, UUID planId, BillingPeriod period,
        long amountMinor, Instant coverageStart, Instant coverageEnd) {
        UUID id = UUID.randomUUID();
        String tradeNo = "HIST" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        Instant created = coverageStart == null ? clock.instant()
            : coverageStart.minus(Duration.ofDays(20));
        jdbc.update("""
            insert into orders (id, trade_no, user_id, plan_id, plan_name, period,
                order_type, status, currency, original_amount, total_amount,
                created_at, updated_at, paid_at, coverage_start, coverage_end)
            values (?::uuid, ?, ?::uuid, ?::uuid, 'Funding history', ?, ?, 'COMPLETED',
                'CNY', ?, ?, ?, ?, ?, ?, ?)
            """, id.toString(), tradeNo, userId.toString(), planId.toString(),
            period.name(), "NEW_PURCHASE", amountMinor, amountMinor,
            Timestamp.from(created), Timestamp.from(created), Timestamp.from(created),
            timestamp(coverageStart), timestamp(coverageEnd));
        return serviceOrders.findByTradeNo(tradeNo).orElseThrow();
    }

    private String status(ServiceOrder order) {
        return jdbc.queryForObject("select status from orders where id = ?::uuid",
            String.class, order.getId().toString());
    }

    private String outcome(ServiceOrder order) {
        return jdbc.queryForObject("select settlement_outcome from orders where id = ?::uuid",
            String.class, order.getId().toString());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant micros(Instant instant) {
        long seconds = instant.getEpochSecond();
        int micros = (instant.getNano() + 500) / 1_000;
        if (micros == 1_000_000) {
            seconds++;
            micros = 0;
        }
        return Instant.ofEpochSecond(seconds, micros * 1_000L);
    }

    @TestConfiguration
    static class TestClockConfiguration {
        @Bean @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(Instant.now(), ZoneOffset.UTC);
        }
    }

    private static final class MutableTestClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableTestClock(Instant initial, ZoneId zone) {
            this(new AtomicReference<>(micros(initial)), zone);
        }

        private MutableTestClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void set(Instant instant) { current.set(micros(instant)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId value) {
            return new MutableTestClock(current, value);
        }
        @Override public Instant instant() { return current.get(); }
    }

    private record Buyer(UUID id) { }
}
