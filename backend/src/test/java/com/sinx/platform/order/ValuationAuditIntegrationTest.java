package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.order.application.OrderAssignmentService;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderQuoteView;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.application.SurplusValuation;
import com.sinx.platform.order.domain.OrderDeductionMode;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.application.TrafficResetService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;

/** Real PostgreSQL counterexamples for current-cycle valuation and entitlement ownership. */
@SpringBootTest
@Import(ValuationAuditIntegrationTest.TestClockConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ValuationAuditIntegrationTest {

    private static final long QUOTA = 1_000L;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_valuation_audit_test")
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
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderService orders;
    @Autowired private SurplusValuation surplusValuation;
    @Autowired private OrderFulfilmentService fulfilment;
    @Autowired private TrafficResetService trafficResets;
    @Autowired private OrderAssignmentService assignments;
    @Autowired private SubscriptionEntitlementRepository entitlements;
    @Autowired private ServiceOrderRepository serviceOrders;
    @Autowired private NodeProtocolService nodeProtocol;
    @Autowired private MutableTestClock clock;

    @BeforeEach
    void setClockToPostgresPrecision() {
        clock.set(postgresMicros(Instant.now()));
    }

    @Test
    void exhaustedCurrentCycleHasNoResidualButAnAnnualOrderKeepsFutureFundedValue() {
        Buyer exhausted = buyer("exhausted-cycle");
        UUID oldPlan = plan("Exhausted annual", 2_000, false, null);
        UUID newPlan = plan("Upgrade target", 1_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(15)));
        Instant cycleEnd = postgresMicros(now.plus(Duration.ofDays(15)));
        Instant annualEnd = postgresMicros(start.atZone(ZoneOffset.UTC).plusYears(1).toInstant());
        entitlement(exhausted, oldPlan, start, annualEnd, start, cycleEnd, QUOTA, 0, QUOTA);
        seedFunding(exhausted.id(), oldPlan, BillingPeriod.YEARLY, 12_000,
            start, annualEnd, "NEW_PURCHASE");

        OrderQuoteView quote = orders.quote(exhausted.id(), newPlan,
            BillingPeriod.MONTHLY, null);
        long futureExpected = BigInteger.valueOf(12_000)
            .multiply(nanos(Duration.between(cycleEnd, annualEnd)))
            .divide(nanos(Duration.between(start, annualEnd)))
            .longValueExact();
        assertThat(quote.breakdown().surplusAmount()).isEqualTo(futureExpected);

        Instant monthEnd = postgresMicros(start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        Buyer monthly = buyer("exhausted-month");
        UUID monthlyPlan = plan("Monthly exhausted", 2_000, false, null);
        Instant monthlyExpiry = postgresMicros(now.plus(Duration.ofDays(60)));
        entitlement(monthly, monthlyPlan, start, monthlyExpiry, start, monthEnd,
            QUOTA, 0, QUOTA);
        seedFunding(monthly.id(), monthlyPlan, BillingPeriod.MONTHLY, 4_000,
            start, monthEnd, "NEW_PURCHASE");
        assertThat(orders.quote(monthly.id(), newPlan, BillingPeriod.MONTHLY, null)
            .breakdown().surplusAmount()).isZero();
    }

    @Test
    void paidResetPreservesCycleConsumptionAndCannotRestoreCurrentCycleValue() {
        Buyer buyer = buyer("paid-reset-value");
        UUID oldPlan = plan("Paid reset source", 3_000, true, null);
        UUID targetPlan = plan("Paid reset target", 1_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(10)));
        Instant cycleEnd = postgresMicros(now.plus(Duration.ofDays(20)));
        Instant expires = postgresMicros(now.plus(Duration.ofDays(60)));
        entitlement(buyer, oldPlan, start, expires, start, cycleEnd, QUOTA, 0, QUOTA);
        seedFunding(buyer.id(), oldPlan, BillingPeriod.MONTHLY, 5_000,
            start, cycleEnd, "NEW_PURCHASE");

        ServiceOrder reset = orders.place(buyer.id(), oldPlan, BillingPeriod.RESET_TRAFFIC, null);
        fulfilment.settleManually(reset.getTradeNo());

        assertThat(jdbc.queryForObject("select uploaded_bytes + downloaded_bytes "
            + "from subscription_entitlements where user_id = ?::uuid", Long.class,
            buyer.id().toString())).isZero();
        assertThat(jdbc.queryForObject("select cycle_consumed_bytes "
            + "from subscription_entitlements where user_id = ?::uuid", Long.class,
            buyer.id().toString())).isEqualTo(QUOTA);
        assertThat(orders.quote(buyer.id(), targetPlan, BillingPeriod.MONTHLY, null)
            .breakdown().surplusAmount()).isZero();
    }

    @Test
    void paidResetClaimSurvivesManualReanchorAndResetPolicyChange() {
        Buyer buyer = buyer("stable-reset-identity");
        UUID plan = plan("Stable reset plan", 2_000, true, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant cycleEnd = postgresMicros(now.plus(Duration.ofDays(25)));
        entitlement(buyer, plan, start, postgresMicros(now.plus(Duration.ofDays(90))),
            start, cycleEnd, QUOTA, 0, 250);

        ServiceOrder reset = orders.place(buyer.id(), plan, BillingPeriod.RESET_TRAFFIC, null);
        fulfilment.settleManually(reset.getTradeNo());
        var active = entitlements.findByUserId(buyer.id()).orElseThrow();
        trafficResets.recordManualReset(active, clock.instant());
        jdbc.update("update service_plans set reset_policy = 'YEARLY_FROM_ACTIVATION' "
            + "where id = ?::uuid", plan.toString());
        trafficResets.synchronizePolicyForUser(buyer.id());

        assertThat(orders.trafficResetOffer(buyer.id()).alreadyReset()).isTrue();
        assertThat(jdbc.queryForObject("select traffic_cycle_start from "
            + "subscription_entitlements where user_id = ?::uuid", Timestamp.class,
            buyer.id().toString()).toInstant()).isEqualTo(start);
        assertThat(jdbc.queryForObject("select traffic_cycle_end from "
            + "subscription_entitlements where user_id = ?::uuid", Timestamp.class,
            buyer.id().toString()).toInstant()).isEqualTo(cycleEnd);
    }

    @Test
    void legacyPaidResetFromAnExpiredCycleReturnsPaymentInsteadOfResettingTheNewCycle() {
        Buyer buyer = buyer("legacy-stale-paid-reset");
        UUID plan = plan("Legacy stale reset plan", 2_000, true, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(10)));
        Instant oldCycleEnd = postgresMicros(now.plus(Duration.ofDays(20)));
        Instant expires = postgresMicros(now.plus(Duration.ofDays(60)));
        entitlement(buyer, plan, start, expires, start, oldCycleEnd, QUOTA, 100, 500);

        ServiceOrder pending = orders.place(buyer.id(), plan, BillingPeriod.RESET_TRAFFIC, null);
        jdbc.update("update orders set reset_cycle_start = null, reset_cycle_id = null "
            + "where trade_no = ?", pending.getTradeNo());

        Instant rolledAt = postgresMicros(oldCycleEnd.plusSeconds(1));
        clock.set(rolledAt);
        Instant newCycleEnd = postgresMicros(oldCycleEnd.plus(Duration.ofDays(30)));
        UUID newCycleId = UUID.randomUUID();
        jdbc.update("""
            update subscription_entitlements
            set traffic_cycle_start = ?, traffic_cycle_end = ?, next_reset_at = ?,
                traffic_cycle_id = ?, uploaded_bytes = 650, downloaded_bytes = 25
            where user_id = ?::uuid
            """, Timestamp.from(oldCycleEnd), Timestamp.from(newCycleEnd),
            Timestamp.from(newCycleEnd), newCycleId, buyer.id().toString());

        fulfilment.settleManually(pending.getTradeNo());

        assertThat(jdbc.queryForObject("select settlement_outcome from orders "
            + "where trade_no = ?", String.class, pending.getTradeNo()))
            .isEqualTo("BALANCE_RETURNED");
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isEqualTo(pending.getTotalAmount());
        assertThat(jdbc.queryForObject("select uploaded_bytes + downloaded_bytes "
            + "from subscription_entitlements where user_id = ?::uuid", Long.class,
            buyer.id().toString())).isEqualTo(675L);
        assertThat(jdbc.queryForObject("select traffic_cycle_id from "
            + "subscription_entitlements where user_id = ?::uuid", UUID.class,
            buyer.id().toString())).isEqualTo(newCycleId);
        assertThat(jdbc.queryForObject("select count(*) from paid_traffic_reset_claims "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isZero();
    }

    @Test
    void inFlightNodeReportIsAccountedAcrossStandardReservationAndCancellation() throws Exception {
        long groupId = accessGroup("reservation-group");
        Buyer buyer = buyer("reserved-node-user");
        jdbc.update("update users set server_group_id = ? where id = ?::uuid",
            groupId, buyer.id().toString());
        UUID oldPlan = plan("Reserved source", 10_000, false, groupId);
        UUID targetPlan = plan("Reserved target", 10_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant cycleEnd = postgresMicros(now.plus(Duration.ofDays(25)));
        Instant expires = postgresMicros(now.plus(Duration.ofDays(90)));
        entitlement(buyer, oldPlan, start, expires, start, cycleEnd, QUOTA, 0, 0);
        ServiceOrder source = seedFunding(buyer.id(), oldPlan, BillingPeriod.MONTHLY, 10_000,
            start, cycleEnd, "NEW_PURCHASE");

        assertThat(activeNodeUsers(groupId)).extracting(row -> row.getUser().getId())
            .contains(buyer.id());
        MachineNode node = machineNode(groupId);
        Long nodeUserId = jdbc.queryForObject("select node_user_id from users "
            + "where id = ?::uuid", Long.class, buyer.id().toString());
        enableEntitlementUpdateTrace();
        ServiceOrder pending = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null);
        // A report already authorized by the node remains billable after the
        // checkout reservation removes the user from later node snapshots.
        report(node, nodeUserId, 800);
        assertThat(jdbc.queryForObject("select surplus_reserved from subscription_entitlements "
            + "where user_id = ?::uuid", Boolean.class, buyer.id().toString())).isTrue();
        assertThat(activeNodeUsers(groupId)).extracting(row -> row.getUser().getId())
            .doesNotContain(buyer.id());
        assertThat(jdbc.queryForObject("select uploaded_bytes from subscription_entitlements "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isEqualTo(800L);
        assertThat(jdbc.queryForObject("select count(*) from traffic_daily "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isEqualTo(1L);

        orders.cancel(buyer.id(), pending.getTradeNo());

        assertThat(jdbc.queryForObject("select surplus_reserved from subscription_entitlements "
            + "where user_id = ?::uuid", Boolean.class, buyer.id().toString())).isFalse();
        assertThat(activeNodeUsers(groupId)).extracting(row -> row.getUser().getId())
            .contains(buyer.id());
        assertThat(orders.quote(buyer.id(), targetPlan, BillingPeriod.MONTHLY, null)
            .breakdown().surplusAmount()).isEqualTo(2_000L);

        ServiceOrder secondPending = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null);
        runReportSettlementRace(secondPending, node, nodeUserId, 100, true);

        assertThat(jdbc.queryForObject("select status from orders where trade_no = ?",
            String.class, secondPending.getTradeNo())).isEqualTo("COMPLETED");
        String outcome = jdbc.queryForObject("select settlement_outcome from orders "
            + "where trade_no = ?", String.class, secondPending.getTradeNo());
        assertThat(outcome).isEqualTo("SERVICE_FULFILLED");
        var afterRace = entitlements.findByUserId(buyer.id()).orElseThrow();
        assertThat(afterRace.stateAt(clock.instant())).isEqualTo(
            com.sinx.platform.subscription.domain.EntitlementState.ACTIVE);
        assertThat(afterRace.getEffectiveServerGroupId()).isEqualTo(groupId);
        assertThat(activeNodeUsers(groupId)).extracting(row -> row.getUser().getId())
            .contains(buyer.id());
        assertThat(jdbc.queryForObject("select sum(billed_bytes) from traffic_daily "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString()))
            .withFailMessage("Traffic ledger, entitlement, and update trace disagree: ledger=%s, entitlement=%s, trace=%s",
                jdbc.queryForObject("select sum(billed_bytes) from traffic_daily "
                    + "where user_id = ?::uuid", Long.class, buyer.id().toString()),
                jdbc.queryForObject("select uploaded_bytes from subscription_entitlements "
                    + "where user_id = ?::uuid", Long.class, buyer.id().toString()),
                entitlementUpdateTrace(buyer.id()))
            .isEqualTo(900L);
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isZero();
        assertThat(jdbc.queryForObject("select plan_id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, buyer.id().toString())).isEqualTo(targetPlan);
        long uploaded = jdbc.queryForObject("select uploaded_bytes from subscription_entitlements "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString());
        assertThat(uploaded).withFailMessage("Final upload counter %s; entitlement updates: %s",
            uploaded, entitlementUpdateTrace(buyer.id())).isEqualTo(100L);
        assertThat(entitlementUpdateTrace(buyer.id()))
            .filteredOn(row -> ((String) row.get("application_name")).startsWith("audit-"))
            .extracting(row -> row.get("new_upload"))
            .containsExactly(0L, 100L);
        assertThat(jdbc.queryForObject("select surplus_reserved from subscription_entitlements "
            + "where user_id = ?::uuid", Boolean.class, buyer.id().toString())).isFalse();
        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid",
            String.class, source.getId().toString())).isEqualTo("DISCOUNTED");
    }

    @Test
    void reportQueuedAheadOfSettlementReducesReservedValueAndReturnsPayment() throws Exception {
        long groupId = accessGroup("report-before-settlement-group");
        Buyer buyer = buyer("report-before-settlement-user");
        jdbc.update("update users set server_group_id = ? where id = ?::uuid",
            groupId, buyer.id().toString());
        UUID sourcePlan = plan("Report-first source", 10_000, false, groupId);
        UUID targetPlan = plan("Report-first target", 10_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant cycleEnd = postgresMicros(now.plus(Duration.ofDays(25)));
        entitlement(buyer, sourcePlan, start, postgresMicros(now.plus(Duration.ofDays(90))),
            start, cycleEnd, QUOTA, 0, 0);
        ServiceOrder source = seedFunding(buyer.id(), sourcePlan,
            BillingPeriod.MONTHLY, 10_000, start, cycleEnd, "NEW_PURCHASE");
        MachineNode node = machineNode(groupId);
        Long nodeUserId = jdbc.queryForObject("select node_user_id from users "
            + "where id = ?::uuid", Long.class, buyer.id().toString());
        enableEntitlementUpdateTrace();

        report(node, nodeUserId, 800);
        ServiceOrder pending = orders.place(buyer.id(), targetPlan,
            BillingPeriod.MONTHLY, null);
        assertThat(pending.getSurplusAmount()).isEqualTo(2_000L);
        runReportSettlementRace(pending, node, nodeUserId, 100, false);

        assertThat(jdbc.queryForObject("select settlement_outcome from orders "
            + "where trade_no = ?", String.class, pending.getTradeNo()))
            .isEqualTo("BALANCE_RETURNED");
        assertThat(jdbc.queryForObject("select plan_id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, buyer.id().toString()))
            .isEqualTo(sourcePlan);
        assertThat(jdbc.queryForObject("select uploaded_bytes from subscription_entitlements "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isEqualTo(900L);
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isEqualTo(pending.getTotalAmount());
        assertThat(jdbc.queryForObject("select sum(billed_bytes) from traffic_daily "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isEqualTo(900L);
        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid",
            String.class, source.getId().toString())).isEqualTo("COMPLETED");
    }

    @Test
    void fullPaymentUsesValueRemainingAtFulfilmentRatherThanAtCheckout() {
        Buyer buyer = buyer("full-deferred-value");
        UUID oldPlan = plan("Deferred source", 10_000, false, null);
        UUID newPlan = plan("Deferred target", 5_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant end = postgresMicros(now.plus(Duration.ofDays(25)));
        Instant expires = postgresMicros(now.plus(Duration.ofDays(90)));
        entitlement(buyer, oldPlan, start, expires, start, end, QUOTA, 0, 0);
        seedFunding(buyer.id(), oldPlan, BillingPeriod.MONTHLY, 10_000,
            start, end, "NEW_PURCHASE");

        ServiceOrder pending = orders.place(buyer.id(), newPlan, BillingPeriod.MONTHLY,
            null, OrderDeductionMode.FULL_PAYMENT);
        assertThat(pending.getDeferredSurplusCreditMinor()).isPositive();
        jdbc.update("update subscription_entitlements set uploaded_bytes = ? "
            + "where user_id = ?::uuid", QUOTA, buyer.id().toString());
        assertThat(jdbc.queryForObject("select uploaded_bytes from subscription_entitlements "
            + "where user_id = ?::uuid", Long.class, buyer.id().toString())).isEqualTo(QUOTA);
        assertThat(surplusValuation.valueOf(
            entitlements.findByUserId(buyer.id()).orElseThrow(), clock.instant()).amountMinor())
            .isZero();

        fulfilment.settleManually(pending.getTradeNo());

        assertThat(jdbc.queryForObject("select deferred_surplus_credit from orders "
            + "where trade_no = ?", Long.class, pending.getTradeNo())).isZero();
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isZero();
    }

    @Test
    void fullPaymentWithoutAnEligiblePriorEntitlementCreditsNothingAndDoesNotConsumeOtherSources() {
        Buyer buyer = buyer("full-without-prior");
        UUID oldPlan = plan("Removed full-payment source", 10_000, false, null);
        UUID newPlan = plan("Full-payment new purchase", 5_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant end = postgresMicros(now.plus(Duration.ofDays(25)));
        entitlement(buyer, oldPlan, start, postgresMicros(now.plus(Duration.ofDays(90))),
            start, end, QUOTA, 0, 0);
        ServiceOrder source = seedFunding(buyer.id(), oldPlan, BillingPeriod.MONTHLY,
            10_000, start, end, "NEW_PURCHASE");
        ServiceOrder pending = orders.place(buyer.id(), newPlan, BillingPeriod.MONTHLY,
            null, OrderDeductionMode.FULL_PAYMENT);
        assertThat(pending.getDeferredSurplusCreditMinor()).isPositive();

        jdbc.update("delete from subscription_entitlements where user_id = ?::uuid",
            buyer.id().toString());
        fulfilment.settleManually(pending.getTradeNo());

        assertThat(jdbc.queryForObject("select deferred_surplus_credit from orders "
            + "where trade_no = ?", Long.class, pending.getTradeNo())).isZero();
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isZero();
        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid",
            String.class, source.getId().toString())).isEqualTo("COMPLETED");
    }

    @Test
    void fullPaymentDoesNotCreditOrWriteOffASourceThatMovedToAnotherOwner() {
        Buyer buyer = buyer("full-source-owner");
        Buyer other = buyer("full-source-target");
        UUID oldPlan = plan("Moved full source", 10_000, false, null);
        UUID newPlan = plan("Moved full target", 5_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant end = postgresMicros(now.plus(Duration.ofDays(25)));
        entitlement(buyer, oldPlan, start, postgresMicros(now.plus(Duration.ofDays(90))),
            start, end, QUOTA, 0, 0);
        ServiceOrder source = seedFunding(buyer.id(), oldPlan, BillingPeriod.MONTHLY,
            10_000, start, end, "NEW_PURCHASE");
        ServiceOrder pending = orders.place(buyer.id(), newPlan, BillingPeriod.MONTHLY,
            null, OrderDeductionMode.FULL_PAYMENT);
        jdbc.update("update orders set user_id = ?::uuid where id = ?::uuid",
            other.id().toString(), source.getId().toString());

        fulfilment.settleManually(pending.getTradeNo());

        assertThat(jdbc.queryForObject("select deferred_surplus_credit from orders "
            + "where trade_no = ?", Long.class, pending.getTradeNo())).isZero();
        assertThat(jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, buyer.id().toString())).isZero();
        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid",
            String.class, source.getId().toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select user_id from orders where id = ?::uuid",
            UUID.class, source.getId().toString())).isEqualTo(other.id());
    }

    @Test
    void assignmentIsBlockedWhileAnOtherOpenOrderDependsOnTheOwnersEntitlement() {
        Buyer owner = buyer("assignment-open-order-owner");
        Buyer target = buyer("assignment-open-order-target");
        UUID oldPlan = plan("Assignment open source", 10_000, false, null);
        UUID upgradePlan = plan("Assignment open target", 8_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant end = postgresMicros(now.plus(Duration.ofDays(25)));
        entitlement(owner, oldPlan, start, postgresMicros(now.plus(Duration.ofDays(90))),
            start, end, QUOTA, 0, 0);
        ServiceOrder source = seedFunding(owner.id(), oldPlan, BillingPeriod.MONTHLY,
            10_000, start, end, "NEW_PURCHASE");
        ServiceOrder open = orders.place(owner.id(), upgradePlan, BillingPeriod.MONTHLY,
            null, OrderDeductionMode.FULL_PAYMENT);

        assertThatThrownBy(() -> assignments.assign(source.getTradeNo(), target.id()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("open orders");
        assertThatThrownBy(() -> assignments.assign(open.getTradeNo(), target.id()))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("open orders");

        assertThat(jdbc.queryForObject("select user_id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, owner.id().toString()))
            .isEqualTo(owner.id());
        assertThat(jdbc.queryForObject("select user_id from orders where trade_no = ?",
            UUID.class, open.getTradeNo())).isEqualTo(owner.id());
        assertThat(jdbc.queryForObject("select user_id from orders where id = ?::uuid",
            UUID.class, source.getId().toString())).isEqualTo(owner.id());
    }

    @Test
    void sameAndDifferentPlanAfterExpiryAreNewPurchasesWithFreshCountersAndTerms() {
        for (boolean samePlan : List.of(true, false)) {
            Buyer buyer = buyer(samePlan ? "expired-same" : "expired-other");
            UUID oldPlan = plan("Expired source " + samePlan, 4_000, false, null);
            UUID purchasePlan = samePlan ? oldPlan
                : plan("Expired target " + samePlan, 4_000, false, null);
            Instant now = clock.instant();
            Instant start = postgresMicros(now.minus(Duration.ofDays(40)));
            Instant oldEnd = postgresMicros(now.minus(Duration.ofDays(10)));
            entitlement(buyer, oldPlan, start, oldEnd, start,
                postgresMicros(now.minus(Duration.ofDays(10))), QUOTA, 0, 500);
            seedFunding(buyer.id(), oldPlan, BillingPeriod.MONTHLY, 4_000,
                start, oldEnd, "NEW_PURCHASE");

            ServiceOrder pending = orders.place(buyer.id(), purchasePlan,
                BillingPeriod.MONTHLY, null);
            assertThat(pending.getOrderType().name()).isEqualTo("NEW_PURCHASE");
            assertThat(pending.getSurplusAmount()).isZero();
            fulfilment.settleManually(pending.getTradeNo());

            assertThat(jdbc.queryForObject("select uploaded_bytes + downloaded_bytes "
                + "from subscription_entitlements where user_id = ?::uuid", Long.class,
                buyer.id().toString())).isZero();
            assertThat(jdbc.queryForObject("select expires_at from subscription_entitlements "
                + "where user_id = ?::uuid", Timestamp.class, buyer.id().toString()).toInstant())
                .isEqualTo(now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        }
    }

    @Test
    void adminExpiryClippingKeepsTheOriginalFundingDenominator() {
        Buyer buyer = buyer("expiry-clipping");
        UUID oldPlan = plan("Clipped annual source", 1_000, false, null);
        UUID targetPlan = plan("Clipping target", 1_000, false, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(30)));
        Instant annualEnd = postgresMicros(start.atZone(ZoneOffset.UTC).plusYears(1).toInstant());
        Instant clippedExpiry = postgresMicros(now.plus(Duration.ofDays(30)));
        Instant cycleEnd = annualEnd;
        entitlement(buyer, oldPlan, start, clippedExpiry, start, cycleEnd, QUOTA, 0, 0);
        seedFunding(buyer.id(), oldPlan, BillingPeriod.YEARLY, 12_000,
            start, annualEnd, "NEW_PURCHASE");

        long expected = BigInteger.valueOf(12_000)
            .multiply(nanos(Duration.between(now, clippedExpiry)))
            .divide(nanos(Duration.between(start, annualEnd)))
            .longValueExact();
        assertThat(orders.quote(buyer.id(), targetPlan, BillingPeriod.MONTHLY, null)
            .breakdown().surplusAmount()).isEqualTo(expected);
    }

    @Test
    void assignmentAndScheduledResetSerializeAndMoveAllCycleOwnership() throws Exception {
        Buyer owner = buyer("assignment-reset-owner");
        Buyer target = buyer("assignment-reset-target");
        UUID plan = plan("Assignment reset plan", 5_000, true, null);
        Instant now = clock.instant();
        Instant start = postgresMicros(now.minus(Duration.ofDays(30)));
        Instant overdue = postgresMicros(now.minus(Duration.ofSeconds(1)));
        entitlement(owner, plan, start, postgresMicros(now.plus(Duration.ofDays(60))),
            start, overdue, QUOTA, 0, 250);
        ServiceOrder funding = seedFunding(owner.id(), plan, BillingPeriod.MONTHLY,
            5_000, start, overdue, "NEW_PURCHASE");
        ServiceOrder futureFunding = seedFunding(owner.id(), plan, BillingPeriod.YEARLY,
            60_000, overdue, postgresMicros(overdue.atZone(ZoneOffset.UTC)
                .plusYears(1).toInstant()), "RENEWAL");
        UUID cycleId = jdbc.queryForObject("select traffic_cycle_id from "
            + "subscription_entitlements where user_id = ?::uuid", UUID.class,
            owner.id().toString());
        jdbc.update("insert into paid_traffic_reset_claims "
                + "(id, user_id, cycle_start, cycle_end, trade_no, claimed_at, traffic_cycle_id) "
                + "values (?::uuid, ?::uuid, ?, ?, ?, ?, ?::uuid)", UUID.randomUUID().toString(),
            owner.id().toString(), Timestamp.from(start), Timestamp.from(overdue),
            funding.getTradeNo(), Timestamp.from(now), cycleId.toString());
        UUID entitlementId = jdbc.queryForObject("select id from subscription_entitlements "
            + "where user_id = ?::uuid", UUID.class, owner.id().toString());

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            var assign = workers.submit(() -> runAfterGate(startGate, failure,
                () -> assignments.assign(funding.getTradeNo(), target.id())));
            var reset = workers.submit(() -> runAfterGate(startGate, failure,
                () -> trafficResets.resetDueEntitlement(entitlementId)));
            startGate.countDown();
            assign.get(30, TimeUnit.SECONDS);
            reset.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(30, TimeUnit.SECONDS);
        }

        if (failure.get() != null) throw new AssertionError("Concurrent operation failed", failure.get());
        assertThat(jdbc.queryForObject("select user_id from subscription_entitlements "
            + "where id = ?::uuid", UUID.class, entitlementId.toString())).isEqualTo(target.id());
        assertThat(jdbc.queryForObject("select user_id from orders where id = ?::uuid",
            UUID.class, funding.getId().toString())).isEqualTo(target.id());
        assertThat(jdbc.queryForObject("select user_id from orders where id = ?::uuid",
            UUID.class, futureFunding.getId().toString())).isEqualTo(target.id());
        assertThat(jdbc.queryForObject("select user_id from paid_traffic_reset_claims "
            + "where trade_no = ?", UUID.class, funding.getTradeNo())).isEqualTo(target.id());
        assertThat(jdbc.queryForObject("select count(*) from traffic_reset_records "
            + "where user_id = ?::uuid", Long.class, target.id().toString())).isEqualTo(1L);
    }

    private List<com.sinx.platform.subscription.domain.SubscriptionEntitlement> activeNodeUsers(
        long groupId) {
        return entitlements.findActiveForServerGroups(List.of(groupId), UserStatus.ACTIVE,
            clock.instant());
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

    private UUID plan(String name, long monthlyPrice, boolean resettable, Long groupId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            insert into service_plans (id, name, description, plan_type,
                transfer_limit_bytes, speed_limit_mbps, reset_policy, resettable,
                published, sellable, renewable, sort_order, server_group_id,
                created_at, updated_at)
            values (?::uuid, ?, '', 'SUBSCRIPTION', ?, 100,
                'MONTHLY_FROM_ACTIVATION', ?, true, true, true, 0, ?, ?, ?)
            """, id.toString(), name, QUOTA, resettable, groupId, now, now);
        jdbc.update("""
            insert into service_plan_prices (id, plan_id, billing_period,
                amount_minor, currency) values (?::uuid, ?::uuid, 'MONTHLY', ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), monthlyPrice);
        jdbc.update("""
            insert into service_plan_prices (id, plan_id, billing_period,
                amount_minor, currency) values (?::uuid, ?::uuid, 'YEARLY', ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), monthlyPrice * 12);
        if (resettable) {
            jdbc.update("""
                insert into service_plan_prices (id, plan_id, billing_period,
                    amount_minor, currency) values (?::uuid, ?::uuid, 'RESET_TRAFFIC', 500, 'CNY')
                """, UUID.randomUUID().toString(), id.toString());
        }
        return id;
    }

    private void entitlement(Buyer buyer, UUID planId, Instant startsAt, Instant expiresAt,
        Instant cycleStart, Instant cycleEnd, long quota, long cycleConsumed,
        long currentUsed) {
        Instant now = clock.instant();
        jdbc.update("""
            insert into subscription_entitlements (id, user_id, plan_id, plan_name,
                transfer_limit_bytes, uploaded_bytes, downloaded_bytes, reset_policy,
                starts_at, expires_at, next_reset_at, traffic_cycle_start,
                traffic_cycle_end, traffic_cycle_id, cycle_consumed_bytes,
                created_at, updated_at, is_trial)
            values (?::uuid, ?::uuid, ?::uuid, 'Test plan', ?, ?, 0,
                'MONTHLY_FROM_ACTIVATION', ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, false)
            """, UUID.randomUUID().toString(), buyer.id().toString(), planId.toString(),
            quota, currentUsed, Timestamp.from(startsAt), Timestamp.from(expiresAt),
            Timestamp.from(cycleEnd), Timestamp.from(cycleStart), Timestamp.from(cycleEnd),
            UUID.randomUUID().toString(), cycleConsumed, Timestamp.from(now), Timestamp.from(now));
    }

    private ServiceOrder seedFunding(UUID userId, UUID planId, BillingPeriod period,
        long amountMinor, Instant coverageStart, Instant coverageEnd, String orderType) {
        UUID id = UUID.randomUUID();
        String tradeNo = "HIST" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        Timestamp created = Timestamp.from(coverageStart.minus(Duration.ofDays(20)));
        jdbc.update("""
            insert into orders (id, trade_no, user_id, plan_id, plan_name, period,
                order_type, status, currency, original_amount, total_amount,
                created_at, updated_at, paid_at, coverage_start, coverage_end)
            values (?::uuid, ?, ?::uuid, ?::uuid, 'Funding history', ?, ?, 'COMPLETED',
                'CNY', ?, ?, ?, ?, ?, ?, ?)
            """, id.toString(), tradeNo, userId.toString(), planId.toString(),
            period.name(), orderType, amountMinor, amountMinor, created, created,
            Timestamp.from(coverageStart), Timestamp.from(coverageStart),
            Timestamp.from(coverageEnd));
        return serviceOrders.findByTradeNo(tradeNo).orElseThrow();
    }

    private long accessGroup(String name) {
        return jdbc.queryForObject("""
            insert into node_access_groups (name, created_at, updated_at)
            values (?, ?, ?) returning id
            """, Long.class, name, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    private MachineNode machineNode(long groupId) {
        long machineId = jdbc.queryForObject("""
            insert into node_machines (name, token, created_at, updated_at)
            values ('valuation test machine', ?, ?, ?) returning id
            """, Long.class, UUID.randomUUID().toString(),
            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        long nodeId = jdbc.queryForObject("""
            insert into proxy_nodes (type, machine_id, group_ids, name,
                server_port, created_at, updated_at)
            values ('shadowsocks', ?, ?, 'valuation test node', 12345, ?, ?)
            returning id
            """, Long.class, machineId, "[" + groupId + "]",
            Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        String token = jdbc.queryForObject("select token from node_machines where id = ?",
            String.class, machineId);
        return new MachineNode(machineId, nodeId, token);
    }

    private void enableEntitlementUpdateTrace() {
        jdbc.execute("""
            create table if not exists entitlement_update_trace (
                user_id uuid not null,
                application_name text,
                previous_upload bigint not null,
                new_upload bigint not null,
                previous_version bigint not null,
                new_version bigint not null
            )
            """);
        jdbc.execute("""
            create or replace function trace_entitlement_update() returns trigger
            language plpgsql as $$
            begin
                insert into entitlement_update_trace
                    (user_id, application_name, previous_upload, new_upload,
                     previous_version, new_version)
                values (new.user_id, current_setting('application_name', true),
                    old.uploaded_bytes, new.uploaded_bytes, old.version, new.version);
                return new;
            end;
            $$
            """);
        jdbc.execute("drop trigger if exists entitlement_update_trace_trigger "
            + "on subscription_entitlements");
        jdbc.execute("""
            create trigger entitlement_update_trace_trigger
            before update on subscription_entitlements
            for each row execute function trace_entitlement_update()
            """);
    }

    private List<Map<String, Object>> entitlementUpdateTrace(UUID userId) {
        return jdbc.queryForList("select application_name, previous_upload, new_upload, "
            + "previous_version, new_version from entitlement_update_trace "
            + "where user_id = ?::uuid order by ctid", userId.toString());
    }

    private void report(MachineNode node, Long nodeUserId, long uploadedBytes) {
        nodeProtocol.report(node.machineId(), node.nodeId(), node.token(),
            java.util.Map.of("traffic", java.util.Map.of(
                Long.toString(nodeUserId), List.of(uploadedBytes, 0L))));
    }

    /**
     * Holds the entitlement row while queuing both real service transactions.
     * The first waiter is proven to precede the second in PostgreSQL's row-lock
     * queue before the blocker is released, so each authorization order is
     * exercised deterministically rather than inferred from a start barrier.
     */
    private void runReportSettlementRace(ServiceOrder order, MachineNode node,
        Long nodeUserId, long lateBytes, boolean settlementFirst) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String settlementApp = "audit-settle-" + runId;
        String reportApp = "audit-report-" + runId;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (PreparedStatement pid = blocker.prepareStatement("select pg_backend_pid()")) {
                try (var result = pid.executeQuery()) {
                    result.next();
                    blockerPid = result.getInt(1);
                }
            }
            try (PreparedStatement lock = blocker.prepareStatement(
                    "select id from subscription_entitlements where user_id = ?::uuid for update")) {
                lock.setString(1, order.getUser().getId().toString());
                try (var result = lock.executeQuery()) {
                    if (!result.next()) throw new AssertionError("Entitlement row was not locked");
                }
            }

            Future<?> settlement;
            Future<?> report;
            int firstWaiter;
            int secondWaiter;
            if (settlementFirst) {
                settlement = workers.submit(() -> inNamedTransaction(settlementApp,
                    () -> fulfilment.settle(order.getTradeNo(),
                        "verified-gateway-transaction")));
                firstWaiter = awaitEntitlementLockWaiter(settlementApp);
                awaitBlockedBy(firstWaiter, blockerPid);
                report = workers.submit(() -> inNamedTransaction(reportApp,
                    () -> report(node, nodeUserId, lateBytes)));
                secondWaiter = awaitEntitlementLockWaiter(reportApp);
            } else {
                report = workers.submit(() -> inNamedTransaction(reportApp,
                    () -> report(node, nodeUserId, lateBytes)));
                firstWaiter = awaitEntitlementLockWaiter(reportApp);
                awaitBlockedBy(firstWaiter, blockerPid);
                settlement = workers.submit(() -> inNamedTransaction(settlementApp,
                    () -> fulfilment.settle(order.getTradeNo(),
                        "verified-gateway-transaction")));
                secondWaiter = awaitEntitlementLockWaiter(settlementApp);
            }
            awaitBlockedBy(secondWaiter, firstWaiter);

            blocker.commit();
            settlement.get(30, TimeUnit.SECONDS);
            report.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private int awaitEntitlementLockWaiter(String applicationName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Integer> waitingPids = jdbc.queryForList("""
                select pid from pg_stat_activity
                where application_name = ? and wait_event_type = 'Lock'
                """, Integer.class, applicationName);
            if (!waitingPids.isEmpty()) {
                return waitingPids.getFirst();
            }
            Thread.sleep(10);
        }
        List<Map<String, Object>> active = jdbc.queryForList("""
            select pid, application_name, wait_event_type, wait_event, query from pg_stat_activity
            where state = 'active'
            """);
        throw new AssertionError("Timed out waiting for backend " + applicationName
            + " to queue on the entitlement row; active statements: " + active);
    }

    private void inNamedTransaction(String applicationName, Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("select set_config('application_name', ?, true)",
                String.class, applicationName);
            operation.run();
        });
    }

    private void awaitBlockedBy(int waitingPid, int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Integer> blockers = jdbc.queryForList(
                "select unnest(pg_blocking_pids(?))", Integer.class, waitingPid);
            if (blockers.contains(blockerPid)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("PostgreSQL did not report backend " + blockerPid
            + " blocking queued backend " + waitingPid);
    }

    private static void runAfterGate(CountDownLatch gate,
        AtomicReference<Throwable> failure, Runnable operation) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent start gate timed out");
            }
            operation.run();
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            throw new IllegalStateException(error);
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent start gate timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static BigInteger nanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
            .multiply(BigInteger.valueOf(1_000_000_000L))
            .add(BigInteger.valueOf(duration.getNano()));
    }

    private static Instant postgresMicros(Instant instant) {
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
    private record MachineNode(long machineId, long nodeId, String token) { }
}
