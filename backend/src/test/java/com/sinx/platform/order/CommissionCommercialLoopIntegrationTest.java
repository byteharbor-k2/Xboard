package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.order.application.CommissionService;
import com.sinx.platform.order.application.CommissionSettlementJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Exercises checkout snapshots, the three-day queue, atomic payouts, and reuse of paid balance. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CommissionCommercialLoopIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_commission_test")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private CommissionService commissions;
    @Autowired private CommissionSettlementJob commissionJob;
    @Autowired private PlatformConfigurationService configuration;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void resetGlobalCommissionSettings() {
        jdbc.update("delete from platform_settings where setting_key like 'invite.commission_%' or setting_key = 'invite.invite_commission'");
    }

    @Test
    void orderSnapshotUsesCouponAndSurplusValueBeforeBalanceAndNeverCountsHandlingFee()
        throws Exception {
        User recipient = user("coupon-recipient");
        User buyer = user("coupon-buyer");
        setInviter(buyer, recipient, 1, null);
        jdbc.update("update users set balance_minor = 2000 where id = ?::uuid", buyer.id().toString());

        UUID plan = plan("Coupon and balance plan", 10_000, false);
        seedFixedCoupon("CUT10", 1_000);
        String trade = place(buyer, plan, "CUT10");
        Map<String, Object> row = order(trade);
        assertThat(number(row, "discount_amount")).isEqualTo(1_000);
        assertThat(number(row, "balance_amount")).isEqualTo(2_000);
        assertThat(number(row, "total_amount")).isEqualTo(7_000);
        assertThat(number(row, "commission_base")).isEqualTo(9_000);
        assertThat(number(row, "commission_balance")).isEqualTo(900);
        assertThat(row.get("invite_user_id").toString()).isEqualTo(recipient.id().toString());

        // Checkout's optional gateway surcharge is downstream of commission
        // snapshotting and cannot inflate the eligible order base.
        jdbc.update("update orders set handling_amount = 375 where trade_no = ?", trade);
        assertThat(number(order(trade), "commission_base")).isEqualTo(9_000);

        // A surplus-funded upgrade also snapshots exactly total+balance after
        // its coupon/surplus deductions, rather than the plan list price.
        User upgradeBuyer = user("upgrade-buyer");
        User upgradeRecipient = user("upgrade-recipient");
        setInviter(upgradeBuyer, upgradeRecipient, 1, null);
        UUID oldPlan = plan("Old plan", 1_000, false);
        UUID newPlan = plan("Replacement plan", 5_000, false);
        String oldTrade = place(upgradeBuyer, oldPlan, null);
        settle(oldTrade);
        String upgradeTrade = place(upgradeBuyer, newPlan, null);
        Map<String, Object> upgrade = order(upgradeTrade);
        assertThat(number(upgrade, "surplus_amount")).isPositive();
        assertThat(number(upgrade, "commission_base")).isEqualTo(
            number(upgrade, "total_amount") + number(upgrade, "balance_amount")
        );
        assertThat(number(upgrade, "commission_balance")).isEqualTo(
            number(upgrade, "commission_base") / 10
        );

        // A balance-only order retains a positive pre-balance commission base.
        User balanceOnlyInviter = user("balance-only-inviter");
        User balanceOnlyBuyer = user("balance-only-buyer");
        setInviter(balanceOnlyBuyer, balanceOnlyInviter, 1, null);
        UUID balanceOnlyPlan = plan("Balance-only plan", 1_000, false);
        jdbc.update("update users set balance_minor = 1000 where id = ?::uuid",
            balanceOnlyBuyer.id().toString());
        String balanceOnlyTrade = place(balanceOnlyBuyer, balanceOnlyPlan, null);
        Map<String, Object> balanceOnly = order(balanceOnlyTrade);
        assertThat(balanceOnly.get("status")).isEqualTo("COMPLETED");
        assertThat(number(balanceOnly, "commission_base")).isEqualTo(1_000);
        assertThat(number(balanceOnly, "commission_balance")).isEqualTo(100);
        assertThat(number(balanceOnly, "commission_status")).isZero();
    }

    @Test
    void adminCanSetAndExplicitlyClearPerInviterCommissionOverride() throws Exception {
        User inviter = user("admin-edit-commission");
        mvc.perform(post("/api/v2/admin/user/update")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + inviter.id()
                    + "\",\"commission_type\":2,\"commission_rate\":17}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commission_type").value(2))
            .andExpect(jsonPath("$.data.commission_rate").value(17));

        mvc.perform(post("/api/v2/admin/user/update")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + inviter.id()
                    + "\",\"clear_commission_rate\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.commission_type").value(2))
            .andExpect(jsonPath("$.data.commission_rate").value(
                org.hamcrest.Matchers.nullValue()
            ));
    }

    @Test
    void systemFirstOnlyAndPeriodOverridesRespectOrderHistoryNotTrialOrZeroPrice()
        throws Exception {
        User systemInviter = user("system-inviter");
        User firstBuyer = user("system-first-buyer");
        setInviter(firstBuyer, systemInviter, 0, 0);
        UUID plan = plan("First-only plan", 1_000, false);
        String first = place(firstBuyer, plan, null);
        assertThat(number(order(first), "commission_balance")).isEqualTo(100);
        settle(first);
        String next = place(firstBuyer, plan, null);
        assertThat(number(order(next), "commission_balance")).isZero();
        assertThat(number(order(next), "commission_status")).isEqualTo(3);

        User periodInviter = user("period-inviter");
        User periodBuyer = user("period-buyer");
        setInviter(periodBuyer, periodInviter, 1, 25);
        assertThat(jdbc.queryForObject("select commission_rate from users where id = ?::uuid", Integer.class, periodInviter.id().toString())).isEqualTo(25);
        UUID periodPlan = plan("Period plan", 999, false);
        String p1 = place(periodBuyer, periodPlan, null);
        assertThat(number(order(p1), "commission_balance")).isEqualTo(249);
        assertThat(firstPaymentOnly(periodInviter)).isFalse();
        settle(p1);
        String p2 = place(periodBuyer, periodPlan, null);
        assertThat(number(order(p2), "commission_balance")).isEqualTo(249);

        // A buyer with a trial entitlement but no purchase history remains a
        // first eligible customer, including on a reset purchase period.
        User trialBuyer = user("trial-without-order");
        User oneTimeInviter = user("one-time-inviter");
        setInviter(trialBuyer, oneTimeInviter, 2, null);
        UUID resetPlan = plan("Reset plan", 700, true);
        seedTrialEntitlement(trialBuyer, resetPlan);
        String resetTrade = place(trialBuyer, resetPlan, null, "RESET_TRAFFIC");
        assertThat(number(order(resetTrade), "commission_base")).isEqualTo(700);
        assertThat(number(order(resetTrade), "commission_balance")).isEqualTo(70);

        configuration.saveSectionSettings("invite", Map.of("commission_first_time_enable", false));
        assertThat(firstPaymentOnly(oneTimeInviter)).isTrue();
        settle(resetTrade);
        String repeatResetTrade = place(trialBuyer, resetPlan, null, "RESET_TRAFFIC");
        assertThat(number(order(repeatResetTrade), "commission_balance")).isZero();
        assertThat(number(order(repeatResetTrade), "commission_status")).isEqualTo(3);

        User systemPeriodInviter = user("system-period-inviter");
        User systemPeriodBuyer = user("system-period-buyer");
        setInviter(systemPeriodBuyer, systemPeriodInviter, 0, null);
        UUID systemPeriodPlan = plan("System period plan", 1_000, false);
        String systemP1 = place(systemPeriodBuyer, systemPeriodPlan, null);
        assertThat(number(order(systemP1), "commission_balance")).isEqualTo(100);
        settle(systemP1);
        String systemP2 = place(systemPeriodBuyer, systemPeriodPlan, null);
        assertThat(number(order(systemP2), "commission_balance")).isEqualTo(100);

        // Even a zero-value historical purchase consumes first-payment state;
        // no trial record or current entitlement is used as a proxy for this.
        User historyBuyer = user("discounted-history-buyer");
        User historyInviter = user("history-inviter");
        setInviter(historyBuyer, historyInviter, 2, null);
        UUID historyPlan = plan("Historical plan", 1_000, false);
        seedHistoryOrder(historyBuyer, historyPlan, "DISCOUNTED", 0);
        String afterHistory = place(historyBuyer, historyPlan, null);
        assertThat(number(order(afterHistory), "commission_balance")).isZero();
        assertThat(number(order(afterHistory), "commission_status")).isEqualTo(3);
    }

    @Test
    void maturityAutoCheckManualQueueDistributionRollbackAndRepeatsAreAtomic()
        throws Exception {
        User l3 = user("layer-three");
        User l2 = user("layer-two");
        User l1 = user("layer-one");
        User buyer = user("layers-buyer");
        setInviter(l2, l3, 1, null);
        setInviter(l1, l2, 1, null);
        setInviter(buyer, l1, 1, null);
        UUID plan = plan("Layer plan", 10_000, false);
        String trade = place(buyer, plan, null);
        settle(trade);
        jdbc.update("update orders set updated_at = current_timestamp - interval '73 hours' where trade_no = ?", trade);

        // Auto-confirm uses completed order updated_at; with the switch off the
        // aged status-0 order remains pending, but manually queued status 1 pays.
        configuration.saveSectionSettings("invite", Map.of("commission_auto_check_enable", false));
        commissionJob.settleCommissions();
        assertThat(number(order(trade), "commission_status")).isZero();
        commissions.updateStatus(trade, 1);

        // Distribution is read at settlement time. A zero L1 share still
        // advances to L2 and L3; each share is floor(pool * percentage/100).
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_enable", true));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l1", 0));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l2", 50));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l3", 25));
        commissionJob.settleCommissions();
        assertThat(balance(l1)).isZero();
        assertThat(balance(l2)).isEqualTo(500);
        assertThat(balance(l3)).isEqualTo(250);
        assertThat(jdbc.queryForList("select level from commission_logs where trade_no = ? order by level", Integer.class, trade))
            .containsExactly(2, 3);
        assertThat(number(order(trade), "actual_commission_balance")).isEqualTo(750);
        assertThat(number(order(trade), "commission_status")).isEqualTo(2);
        assertThatThrownBy(() -> commissions.updateStatus(trade, 1))
            .isInstanceOf(IllegalStateException.class);

        // A fresh order exercises the auto-enabled default three-day confirmation.
        User matureBuyer = user("mature-buyer");
        User matureInviter = user("mature-inviter");
        setInviter(matureBuyer, matureInviter, 1, null);
        UUID maturePlan = plan("Maturity plan", 1_000, false);
        String matureTrade = place(matureBuyer, maturePlan, null);
        settle(matureTrade);
        jdbc.update("update orders set updated_at = current_timestamp - interval '73 hours' where trade_no = ?", matureTrade);
        configuration.saveSectionSettings("invite", Map.of("commission_auto_check_enable", true));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_enable", false));
        commissionJob.settleCommissions();
        assertThat(number(order(matureTrade), "commission_status")).isEqualTo(2);
        assertThat(balance(matureInviter)).isEqualTo(100);

        // A database-side failure while inserting the ledger rolls back the
        // credit and state flip together, leaving the status-1 order retryable.
        User rollbackBuyer = user("rollback-buyer");
        User rollbackInviter = user("rollback-inviter");
        setInviter(rollbackBuyer, rollbackInviter, 1, null);
        UUID rollbackPlan = plan("Rollback plan", 1_000, false);
        String rollbackTrade = place(rollbackBuyer, rollbackPlan, null);
        commissions.updateStatus(rollbackTrade, 1);
        jdbc.execute("CREATE FUNCTION commission_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'commission log failure'; END $$");
        jdbc.execute("CREATE TRIGGER commission_test_failure_trigger BEFORE INSERT ON commission_logs FOR EACH ROW EXECUTE FUNCTION commission_test_failure()");
        try {
            assertThatThrownBy(() -> commissions.confirmAndPay(rollbackTrade, false))
                .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER commission_test_failure_trigger ON commission_logs");
            jdbc.execute("DROP FUNCTION commission_test_failure()");
        }
        assertThat(number(order(rollbackTrade), "commission_status")).isEqualTo(1);
        assertThat(balance(rollbackInviter)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from commission_logs where trade_no = ?", Long.class, rollbackTrade)).isZero();

        // Two overlapping retries serialize on the order row; only the first
        // one may credit the user's ordinary balance and write the log.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = List.of(
                pool.submit(() -> commissions.confirmAndPay(rollbackTrade, false)),
                pool.submit(() -> commissions.confirmAndPay(rollbackTrade, false))
            );
            for (Future<?> call : calls) call.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(number(order(rollbackTrade), "commission_status")).isEqualTo(2);
        assertThat(balance(rollbackInviter)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from commission_logs where trade_no = ?", Long.class, rollbackTrade)).isEqualTo(1);

        // Distinct orders paying the same recipient at once cannot lose a
        // balance increment: all recipient rows are pessimistically locked.
        User sharedRecipient = user("shared-recipient");
        User concurrentBuyerA = user("concurrent-buyer-a");
        User concurrentBuyerB = user("concurrent-buyer-b");
        setInviter(concurrentBuyerA, sharedRecipient, 1, null);
        setInviter(concurrentBuyerB, sharedRecipient, 1, null);
        UUID concurrentPlan = plan("Concurrent payout plan", 1_000, false);
        String tradeA = place(concurrentBuyerA, concurrentPlan, null);
        String tradeB = place(concurrentBuyerB, concurrentPlan, null);
        commissions.updateStatus(tradeA, 1);
        commissions.updateStatus(tradeB, 1);
        ExecutorService sharedPool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = List.of(
                sharedPool.submit(() -> commissions.confirmAndPay(tradeA, false)),
                sharedPool.submit(() -> commissions.confirmAndPay(tradeB, false))
            );
            for (Future<?> call : calls) call.get();
        } finally {
            sharedPool.shutdownNow();
        }
        assertThat(balance(sharedRecipient)).isEqualTo(200);
        assertThat(jdbc.queryForObject(
            "select count(*) from commission_logs where invite_user_id = ?::uuid",
            Long.class, sharedRecipient.id().toString()
        )).isEqualTo(2);
    }

    @Test
    void aReparentedChainRollsBackAndRetriesAgainstTheEntireCurrentChain()
        throws Exception {
        User oldLevel3 = user("old-level-three");
        User oldLevel2 = user("old-level-two");
        User directInviter = user("reparent-direct");
        User newLevel3 = user("new-level-three");
        User newLevel2 = user("new-level-two");
        User buyer = user("reparent-buyer");
        setInviter(oldLevel2, oldLevel3, 1, null);
        setInviter(directInviter, oldLevel2, 1, null);
        setInviter(newLevel2, newLevel3, 1, null);
        setInviter(buyer, directInviter, 1, null);

        UUID plan = plan("Reparent retry plan", 10_000, false);
        String trade = place(buyer, plan, null);
        commissions.updateStatus(trade, 1);
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_enable", true));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l1", 25));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l2", 50));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l3", 25));

        ExecutorService payoutThread = Executors.newSingleThreadExecutor();
        Future<?> payout;
        try (Connection adminUpdate = dataSource.getConnection()) {
            adminUpdate.setAutoCommit(false);
            try (PreparedStatement update = adminUpdate.prepareStatement(
                    "update users set inviter_user_id = ? where id = ?")) {
                update.setObject(1, newLevel2.id());
                update.setObject(2, directInviter.id());
                update.executeUpdate();
            }
            payout = payoutThread.submit(() -> commissions.confirmAndPay(trade, false));
            assertThat(awaitUserRowLockWait()).isTrue();
            // The payout's unlocked chain projection sees A/B/C, then its lock
            // query blocks behind this committed admin reparent to D/E.
            adminUpdate.commit();
            assertThatThrownBy(payout::get)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                    "The commission inviter chain changed while recipients were locked"
                );
        } finally {
            payoutThread.shutdownNow();
        }

        assertThat(number(order(trade), "commission_status")).isEqualTo(1);
        assertThat(number(order(trade), "actual_commission_balance")).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from commission_logs where trade_no = ?", Long.class, trade
        )).isZero();
        assertThat(balance(directInviter)).isZero();
        assertThat(balance(oldLevel2)).isZero();
        assertThat(balance(oldLevel3)).isZero();
        assertThat(balance(newLevel2)).isZero();
        assertThat(balance(newLevel3)).isZero();

        // The regular queue retry rediscovers A/D/E and pays all three levels.
        commissionJob.settleCommissions();
        assertThat(number(order(trade), "commission_status")).isEqualTo(2);
        assertThat(number(order(trade), "actual_commission_balance")).isEqualTo(1_000);
        assertThat(balance(directInviter)).isEqualTo(250);
        assertThat(balance(newLevel2)).isEqualTo(500);
        assertThat(balance(newLevel3)).isEqualTo(250);
        assertThat(balance(oldLevel2)).isZero();
        assertThat(balance(oldLevel3)).isZero();
        assertThat(jdbc.queryForList(
            "select level from commission_logs where trade_no = ? order by level",
            Integer.class,
            trade
        )).containsExactly(1, 2, 3);
    }

    @Test
    void paidCommissionIsVisibleOnlyToItsRecipientAndCanFundANormalBalanceOrder()
        throws Exception {
        long pendingBefore = pendingCommissionCount();
        User inviter = user("closed-loop-inviter");
        User buyer = user("closed-loop-buyer");
        setInviter(buyer, inviter, 1, null);
        UUID purchasePlan = plan("Commission earning plan", 10_000, false);
        String trade = place(buyer, purchasePlan, null);
        settle(trade);
        assertThat(pendingCommissionCount()).isEqualTo(pendingBefore + 1);
        User assignedOrderOwner = user("assigned-order-owner");
        jdbc.update("update orders set user_id = ?::uuid where trade_no = ?",
            assignedOrderOwner.id().toString(), trade);
        commissions.updateStatus(trade, 1);
        commissions.confirmAndPay(trade, false);
        assertThat(pendingCommissionCount()).isEqualTo(pendingBefore);
        assertThat(balance(inviter)).isEqualTo(1_000);
        assertThat(jdbc.queryForObject(
            "select user_id from commission_logs where trade_no = ?", UUID.class, trade
        )).isEqualTo(buyer.id());

        String ownQuery = "{ viewerCommissionSummary { effectiveRatePercent commissionType firstPaymentOnly pendingMinor confirmedPendingMinor earnedMinor autoConfirmEnabled distributionEnabled payoutDestination } viewerCommissionLogs(page: 0, limit: 20) { totalCount page limit items { id tradeNo orderAmountMinor commissionBaseMinor amountMinor level createdAt } } }";
        MvcResult own = mvc.perform(post("/gateway")
                .with(userJwt(inviter.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + quote(ownQuery) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerCommissionSummary.payoutDestination").value("SITE_BALANCE"))
            .andExpect(jsonPath("$.data.viewerCommissionSummary.earnedMinor").value("1000"))
            .andExpect(jsonPath("$.data.viewerCommissionLogs.totalCount").value(1))
            .andExpect(jsonPath("$.data.viewerCommissionLogs.items[0].amountMinor").value("1000"))
            .andReturn();
        assertThat(own.getResponse().getContentAsString()).doesNotContain("closed-loop-buyer@example.test");

        String otherQuery = "{ viewerCommissionLogs { totalCount items { tradeNo amountMinor } } }";
        mvc.perform(post("/gateway")
                .with(userJwt(buyer.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + quote(otherQuery) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerCommissionLogs.totalCount").value(0));

        mvc.perform(post("/api/v2/admin/order/detail")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trade_no\":\"" + trade + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.trade_no").value(trade))
            .andExpect(jsonPath("$.data.user_id").value(assignedOrderOwner.id().toString()))
            .andExpect(jsonPath("$.data.invite_user_id").value(inviter.id().toString()))
            .andExpect(jsonPath("$.data.commission_log[0].user_id").value(buyer.id().toString()))
            .andExpect(jsonPath("$.data.commission_log[0].get_amount").value(1_000));

        MvcResult commissionOrders = mvc.perform(get("/api/v2/admin/order/fetch")
                .with(adminJwt())
                .param("is_commission", "true")
                .param("commission_status", "2"))
            .andExpect(status().isOk())
            .andReturn();
        List<String> listedTradeNumbers = JsonPath.read(
            commissionOrders.getResponse().getContentAsString(),
            "$.data[*].trade_no"
        );
        assertThat(listedTradeNumbers).contains(trade);

        UUID spendPlan = plan("Balance-funded purchase", 1_000, false);
        String spentTrade = place(inviter, spendPlan, null);
        Map<String, Object> spent = order(spentTrade);
        assertThat(number(spent, "balance_amount")).isEqualTo(1_000);
        assertThat(number(spent, "total_amount")).isZero();
        assertThat(spent.get("status")).isEqualTo("COMPLETED");
        assertThat(balance(inviter)).isZero();
    }

    private RequestPostProcessor userJwt(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString())).authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"),
            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_USER")
        );
    }

    private RequestPostProcessor adminJwt() {
        return jwt().authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"),
            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private User user(String label) {
        UUID id = UUID.randomUUID();
        String email = label + "-" + id.toString().substring(0, 8) + "@example.test";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into users (id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at)
            values (?::uuid, ?, 'hash', ?, 'ACTIVE', ?, ?, ?)
            """, id.toString(), email, label,
            UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
            now, now);
        jdbc.update("insert into user_roles (user_id, role_code) values (?::uuid, 'USER')", id.toString());
        return new User(id, email);
    }

    private void setInviter(User buyer, User inviter, int type, Integer rate) {
        jdbc.update("update users set inviter_user_id = ?::uuid where id = ?::uuid",
            inviter.id().toString(), buyer.id().toString());
        jdbc.update("update users set commission_type = ?, commission_rate = ? where id = ?::uuid",
            type, rate, inviter.id().toString());
    }

    private UUID plan(String name, long monthlyAmount, boolean resettable) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into service_plans (
                id, name, description, transfer_limit_bytes, speed_limit_mbps,
                reset_policy, published, sellable, renewable, sort_order,
                created_at, updated_at, resettable
            ) values (?::uuid, ?, '', 1073741824, 100, 'MONTHLY_FROM_ACTIVATION',
                      true, true, true, 0, ?, ?, ?)
            """, id.toString(), name, now, now, resettable);
        seedPrice(id, "MONTHLY", monthlyAmount);
        if (resettable) seedPrice(id, "RESET_TRAFFIC", monthlyAmount);
        return id;
    }

    private void seedPrice(UUID planId, String period, long amount) {
        jdbc.update("insert into service_plan_prices (id, plan_id, billing_period, amount_minor, currency) values (?::uuid, ?::uuid, ?, ?, 'CNY')",
            UUID.randomUUID().toString(), planId.toString(), period, amount);
    }

    private void seedFixedCoupon(String code, long amount) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into coupons (id, code, name, discount_type, discount_value,
                redemptions_used, limited_plan_ids, limited_periods, enabled,
                created_at, updated_at)
            values (?::uuid, ?, ?, 'FIXED_AMOUNT', ?, 0, '[]', '[]', true, ?, ?)
            """, UUID.randomUUID().toString(), code, code, amount, now, now);
    }

    private void seedTrialEntitlement(User buyer, UUID plan) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                reset_policy, starts_at, created_at, updated_at, is_trial
            ) values (?::uuid, ?::uuid, ?::uuid, 'Trial reset plan', 1073741824,
                      'MONTHLY_FROM_ACTIVATION', ?, ?, ?, true)
            """, UUID.randomUUID().toString(), buyer.id().toString(), plan.toString(), now, now, now);
    }

    private void seedHistoryOrder(User buyer, UUID plan, String status, long total) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into orders (id, trade_no, user_id, plan_id, plan_name, period,
                order_type, status, currency, original_amount, discount_amount,
                surplus_amount, surplus_credit, balance_amount, total_amount,
                surplus_order_ids, created_at, updated_at)
            values (?::uuid, ?, ?::uuid, ?::uuid, 'Historical', 'ONETIME',
                'NEW_PURCHASE', ?, 'CNY', ?, 0, 0, 0, 0, ?, '[]', ?, ?)
            """, UUID.randomUUID().toString(), "HIST" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
            buyer.id().toString(), plan.toString(), status, total, total, now, now);
    }

    private String place(User buyer, UUID plan, String coupon) throws Exception {
        return place(buyer, plan, coupon, "MONTHLY");
    }

    private String place(User buyer, UUID plan, String coupon, String period) throws Exception {
        String couponArg = coupon == null ? "" : ", couponCode: \"" + coupon + "\"";
        String graphql = "mutation { placeOrder(planId: \"" + plan
            + "\", period: " + period + couponArg + ") { tradeNo status } }";
        MvcResult result = mvc.perform(post("/gateway")
                .with(userJwt(buyer.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + quote(graphql) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.placeOrder.tradeNo");
    }

    private void settle(String trade) throws Exception {
        mvc.perform(post("/api/v2/admin/order/paid")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trade_no\":\"" + trade + "\"}"))
            .andExpect(status().isOk());
    }

    private Map<String, Object> order(String trade) {
        return jdbc.queryForMap("select * from orders where trade_no = ?", trade);
    }

    private long balance(User user) {
        Long value = jdbc.queryForObject("select balance_minor from users where id = ?::uuid", Long.class, user.id().toString());
        return value == null ? 0 : value;
    }

    private long pendingCommissionCount() throws Exception {
        MvcResult result = mvc.perform(get("/api/v2/admin/stat/getStats")
                .with(adminJwt()))
            .andExpect(status().isOk())
            .andReturn();
        Number count = JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.pendingCommission"
        );
        return count.longValue();
    }

    private boolean firstPaymentOnly(User inviter) throws Exception {
        String query = "{ viewerCommissionSummary { commissionType firstPaymentOnly } }";
        MvcResult result = mvc.perform(post("/gateway")
                .with(userJwt(inviter.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + quote(query) + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.viewerCommissionSummary.firstPaymentOnly"
        );
    }

    private boolean awaitUserRowLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long blocked = jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database()
                  and wait_event_type = 'Lock'
                  and query ilike '%users%'
                """, Long.class);
            if (blocked != null && blocked > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record User(UUID id, String email) { }
}
