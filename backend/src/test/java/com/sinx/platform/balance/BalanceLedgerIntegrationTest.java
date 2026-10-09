package com.sinx.platform.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
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

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.application.ViewerBalanceService;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.order.application.CommissionService;
import com.sinx.platform.order.application.OrderAssignmentService;
import com.sinx.platform.order.application.OrderService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

/** Verifies balance-ledger cutover, cash mutations, rollback, concurrency, and viewer privacy. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class BalanceLedgerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_balance_test")
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
    @Autowired private OrderService orders;
    @Autowired private ViewerBalanceService balances;
    @Autowired private BalanceLedgerService ledger;
    @Autowired private OrderAssignmentService orderAssignments;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void v35AnchorsExistingCashWithoutChangingItOrInventingHistoricalCredits() throws Exception {
        String schema = "balance_cutover_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("34"))
                .load()
                .migrate();
            UUID existingUser = UUID.randomUUID();
            UUID zeroBalanceUser = UUID.randomUUID();
            Timestamp cutover = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.prepareStatement("""
                    insert into %s.users (id, email, password_hash, display_name, status,
                        subscription_token, balance_minor, created_at, updated_at)
                    values (?, ?, 'hash', 'legacy', 'ACTIVE', ?, 12345, ?, ?)
                    """.formatted(schema))) {
                statement.setObject(1, existingUser);
                statement.setString(2, "legacy-" + existingUser + "@example.test");
                statement.setString(3, UUID.randomUUID().toString().replace("-", ""));
                statement.setTimestamp(4, cutover);
                statement.setTimestamp(5, cutover);
                statement.executeUpdate();
            }
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.prepareStatement("""
                    insert into %s.users (id, email, password_hash, display_name, status,
                        subscription_token, balance_minor, created_at, updated_at)
                    values (?, ?, 'hash', 'legacy zero', 'ACTIVE', ?, 0, ?, ?)
                    """.formatted(schema))) {
                statement.setObject(1, zeroBalanceUser);
                statement.setString(2, "legacy-zero-" + zeroBalanceUser + "@example.test");
                statement.setString(3, UUID.randomUUID().toString().replace("-", ""));
                statement.setTimestamp(4, cutover);
                statement.setTimestamp(5, cutover);
                statement.executeUpdate();
            }
            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                    select u.balance_minor, count(b.id), min(b.amount_minor),
                           max(b.balance_after_minor), count(*) filter (where b.type <> 'OPENING_BALANCE')
                    from %s.users u join %s.balance_logs b on b.user_id = u.id
                    where u.id = '%s'::uuid group by u.balance_minor
                    """.formatted(schema, schema, existingUser))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(12_345);
                assertThat(result.getLong(2)).isEqualTo(1);
                assertThat(result.getLong(3)).isEqualTo(12_345);
                assertThat(result.getLong(4)).isEqualTo(12_345);
                assertThat(result.getLong(5)).isZero();
            }
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("""
                    select type, amount_minor, balance_after_minor
                    from %s.balance_logs where user_id = '%s'::uuid
                    """.formatted(schema, zeroBalanceUser))) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("OPENING_BALANCE");
                assertThat(result.getLong(2)).isZero();
                assertThat(result.getLong(3)).isZero();
                assertThat(result.next()).isFalse();
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + schema + " cascade");
            }
        }
    }

    @Test
    void commissionCashFundsPartialGatewayPurchaseAndCancellationRestoresExactlyOnce()
        throws Exception {
        User recipient = user("balance-owner");
        User buyer = user("commission-buyer");
        setInviter(buyer, recipient);
        UUID earningPlan = plan("Commission purchase", 10_000);
        String earningTrade = place(buyer, earningPlan);
        settle(earningTrade);
        commissions.updateStatus(earningTrade, 1);
        installFailureTrigger("COMMISSION_CREDIT");
        try {
            assertThatThrownBy(() -> commissions.confirmAndPay(earningTrade, false))
                .isInstanceOf(RuntimeException.class);
        } finally {
            dropFailureTrigger();
        }
        assertThat(balance(recipient)).isZero();
        assertThat(jdbc.queryForObject("""
            select commission_status from orders where trade_no = ?
            """, Integer.class, earningTrade)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from commission_logs where trade_no = ?
            """, Long.class, earningTrade)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where trade_no = ?
              and type = 'COMMISSION_CREDIT'
            """, Long.class, earningTrade)).isZero();

        // The still-confirmed payout can be retried after the ledger recovers.
        commissions.confirmAndPay(earningTrade, false);
        assertThat(balance(recipient)).isEqualTo(1_000);
        assertThat(jdbc.queryForList("""
            select type from balance_logs where trade_no = ? order by type
            """, String.class, earningTrade)).containsExactly("COMMISSION_CREDIT");
        assertThat(jdbc.queryForObject("""
            select user_id from balance_logs where trade_no = ? and type = 'COMMISSION_CREDIT'
            """, UUID.class, earningTrade)).isEqualTo(recipient.id());

        UUID partialPlan = plan("Part balance purchase", 1_500);
        String firstTrade = place(recipient, partialPlan);
        Map<String, Object> firstOrder = order(firstTrade);
        assertThat(number(firstOrder, "balance_amount")).isEqualTo(1_000);
        assertThat(number(firstOrder, "total_amount")).isEqualTo(500);
        assertThat(firstOrder.get("status")).isEqualTo("PENDING");
        assertThat(balance(recipient)).isZero();
        assertThat(ledgerAmount(firstTrade, "ORDER_PAYMENT")).isEqualTo(-1_000);

        orders.cancel(recipient.id(), firstTrade);
        assertThat(balance(recipient)).isEqualTo(1_000);
        assertThat(order(firstTrade).get("status")).isEqualTo("CANCELLED");
        assertThat(ledgerAmount(firstTrade, "ORDER_REFUND")).isEqualTo(1_000);
        assertThatThrownBy(() -> orders.cancel(recipient.id(), firstTrade))
            .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where trade_no = ? and type = 'ORDER_REFUND'
            """, Long.class, firstTrade)).isEqualTo(1);

        String secondTrade = place(recipient, partialPlan);
        assertThat(number(order(secondTrade), "balance_amount")).isEqualTo(1_000);
        assertThat(balance(recipient)).isZero();
        settle(secondTrade);
        assertThat(order(secondTrade).get("status")).isEqualTo("COMPLETED");
        assertThat(ledgerAmount(secondTrade, "ORDER_PAYMENT")).isEqualTo(-1_000);
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where trade_no = ? and type = 'ORDER_PAYMENT'
            """, Long.class, secondTrade)).isEqualTo(1);

        String ownerQuery = """
            { viewerBalanceSummary { balanceMinor openingBalanceMinor totalCreditsMinor
                totalDebitsMinor recordedSince }
              viewerBalanceLogs(page: 0, limit: 2) {
                totalCount page limit items {
                  id type amountMinor balanceAfterMinor currency tradeNo createdAt canViewOrder
                }
              } }
            """;
        MvcResult own = mvc.perform(post("/gateway")
                .with(userJwt(recipient.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql(ownerQuery)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerBalanceSummary.balanceMinor").value("0"))
            .andExpect(jsonPath("$.data.viewerBalanceSummary.openingBalanceMinor").value("0"))
            .andExpect(jsonPath("$.data.viewerBalanceSummary.totalCreditsMinor").value("2000"))
            .andExpect(jsonPath("$.data.viewerBalanceSummary.totalDebitsMinor").value("2000"))
            .andExpect(jsonPath("$.data.viewerBalanceLogs.totalCount").value(4))
            .andExpect(jsonPath("$.data.viewerBalanceLogs.limit").value(2))
            .andExpect(jsonPath("$.data.viewerBalanceLogs.items[0].canViewOrder").value(true))
            .andExpect(jsonPath("$.data.viewerBalanceLogs.items[1].canViewOrder").value(true))
            .andReturn();
        assertThat(own.getResponse().getContentAsString())
            .doesNotContain(buyer.email())
            .doesNotContain("password_hash")
            .doesNotContain("subscription_token");

        mvc.perform(post("/gateway")
                .with(userJwt(buyer.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ viewerBalanceSummary { balanceMinor } viewerBalanceLogs { totalCount } }")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerBalanceSummary.balanceMinor").value("0"))
            .andExpect(jsonPath("$.data.viewerBalanceLogs.totalCount").value(0));
        assertThat(balances.summary(buyer.id()).recordedSince()).isNull();

        mvc.perform(post("/gateway")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ viewerBalanceSummary { balanceMinor } }")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").exists());
        mvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("{ viewerBalanceSummary { balanceMinor } }")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").exists());

        var summary = balances.summary(recipient.id());
        assertThat(summary.balanceMinor()).isEqualTo("0");
        assertThat(summary.openingBalanceMinor()).isEqualTo("0");
        assertThat(summary.totalCreditsMinor()).isEqualTo("2000");
        assertThat(summary.totalDebitsMinor()).isEqualTo("2000");

        List<com.sinx.platform.balance.application.BalanceLogView> ownerRows =
            balances.logs(recipient.id(), 0, 100).items();
        assertThat(ownerRows.stream()
            .filter(row -> earningTrade.equals(row.tradeNo()))
            .findFirst().orElseThrow().canViewOrder()).isFalse();
        assertThat(ownerRows.stream()
            .filter(row -> secondTrade.equals(row.tradeNo())
                && row.type().equals("ORDER_PAYMENT"))
            .findFirst().orElseThrow().canViewOrder()).isTrue();

        User transferSource = user("transferred-order-source");
        User transferBuyer = user("transferred-order-buyer");
        setInviter(transferBuyer, transferSource);
        String transferEarningTrade = place(
            transferBuyer,
            plan("Transfer owner earns commission", 10_000)
        );
        settle(transferEarningTrade);
        commissions.updateStatus(transferEarningTrade, 1);
        commissions.confirmAndPay(transferEarningTrade, false);
        String transferredTrade = place(transferSource, partialPlan);
        User transferTarget = user("transferred-order-owner");
        assertThat(balance(transferSource)).isZero();
        assertThat(balance(transferTarget)).isZero();
        orderAssignments.assign(transferredTrade, transferTarget.id());
        assertThat(order(transferredTrade).get("user_id")).isEqualTo(transferTarget.id());
        assertThat(orders.history(transferTarget.id()).stream()
            .map(com.sinx.platform.order.domain.ServiceOrder::getTradeNo))
            .contains(transferredTrade);
        assertThat(orders.history(transferSource.id()).stream()
            .map(com.sinx.platform.order.domain.ServiceOrder::getTradeNo))
            .doesNotContain(transferredTrade);
        assertThat(balances.logs(transferSource.id(), 0, 100).items().stream()
            .filter(row -> transferredTrade.equals(row.tradeNo())
                && row.type().equals("ORDER_PAYMENT"))
            .findFirst().orElseThrow().canViewOrder()).isFalse();

        // The order's service owner changed, but the original wallet payer owns
        // its refund if the reassigned pending order is called off.
        orders.cancel(transferTarget.id(), transferredTrade);
        assertThat(balance(transferSource)).isEqualTo(1_000);
        assertThat(balance(transferTarget)).isZero();
        assertThat(ledgerAmount(transferredTrade, "ORDER_REFUND")).isEqualTo(1_000);
        assertThat(jdbc.queryForObject("""
            select user_id from balance_logs
            where trade_no = ? and type = 'ORDER_REFUND'
            """, UUID.class, transferredTrade)).isEqualTo(transferSource.id());
    }

    @Test
    void surplusCreditIsRecordedOnFulfilmentAndTwoRecipientsCreditsDoNotLoseUpdates()
        throws Exception {
        User surplusOwner = user("surplus-owner");
        UUID oldPlan = plan("Old expensive plan", 10_000);
        String oldTrade = place(surplusOwner, oldPlan);
        settle(oldTrade);
        UUID replacementPlan = plan("Cheaper replacement", 1_000);
        String upgradeTrade = place(surplusOwner, replacementPlan);
        // The old subscription's unused value completely covers the cheaper
        // replacement, so this zero-total upgrade is opened by placement.
        long surplusCredit = number(order(upgradeTrade), "surplus_credit");
        assertThat(surplusCredit).isPositive();
        assertThat(balance(surplusOwner)).isEqualTo(surplusCredit);
        assertThat(ledgerAmount(upgradeTrade, "SURPLUS_CREDIT")).isEqualTo(surplusCredit);
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where trade_no = ? and type = 'SURPLUS_CREDIT'
            """, Long.class, upgradeTrade)).isEqualTo(1);

        User sharedRecipient = user("concurrent-recipient");
        User buyerA = user("concurrent-buyer-a");
        User buyerB = user("concurrent-buyer-b");
        setInviter(buyerA, sharedRecipient);
        setInviter(buyerB, sharedRecipient);
        UUID earningPlan = plan("Parallel commission order", 10_000);
        String tradeA = place(buyerA, earningPlan);
        String tradeB = place(buyerB, earningPlan);
        settle(tradeA);
        settle(tradeB);
        commissions.updateStatus(tradeA, 1);
        commissions.updateStatus(tradeB, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calls = List.of(
                pool.submit(() -> commissions.confirmAndPay(tradeA, false)),
                pool.submit(() -> commissions.confirmAndPay(tradeB, false))
            );
            for (Future<?> call : calls) call.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(sharedRecipient)).isEqualTo(2_000);
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where user_id = ?::uuid
              and type = 'COMMISSION_CREDIT'
            """, Long.class, sharedRecipient.id().toString())).isEqualTo(2);
        assertThat(balances.summary(sharedRecipient.id()).totalCreditsMinor()).isEqualTo("2000");
    }

    @Test
    void failedLedgerInsertRollsBackOrderBalanceAndCancellationStatus() throws Exception {
        User recipient = user("rollback-recipient");
        User buyer = user("rollback-commission-buyer");
        setInviter(buyer, recipient);
        UUID earningPlan = plan("Rollback commission plan", 10_000);
        String earningTrade = place(buyer, earningPlan);
        settle(earningTrade);
        commissions.updateStatus(earningTrade, 1);
        commissions.confirmAndPay(earningTrade, false);
        assertThat(balance(recipient)).isEqualTo(1_000);

        UUID purchasePlan = plan("Rollback purchase", 1_500);
        String pendingTrade = place(recipient, purchasePlan);
        assertThat(balance(recipient)).isZero();
        installFailureTrigger("ORDER_REFUND");
        try {
            assertThatThrownBy(() -> orders.cancel(recipient.id(), pendingTrade))
                .isInstanceOf(RuntimeException.class);
        } finally {
            dropFailureTrigger();
        }
        assertThat(balance(recipient)).isZero();
        assertThat(order(pendingTrade).get("status")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where trade_no = ? and type = 'ORDER_REFUND'
            """, Long.class, pendingTrade)).isZero();
        orders.cancel(recipient.id(), pendingTrade);
        assertThat(balance(recipient)).isEqualTo(1_000);

        // The order exists and has consumed cash; failing the debit insert must
        // roll back both writes, leaving that cash and the order untouched.
        installFailureTrigger("ORDER_PAYMENT");
        long orderCountBefore = jdbc.queryForObject(
            "select count(*) from orders where user_id = ?::uuid",
            Long.class,
            recipient.id().toString()
        );
        try {
            assertThatThrownBy(() -> orders.place(
                recipient.id(), purchasePlan, com.sinx.platform.catalog.domain.BillingPeriod.MONTHLY, null
            )).isInstanceOf(RuntimeException.class);
        } finally {
            dropFailureTrigger();
        }
        assertThat(balance(recipient)).isEqualTo(1_000);
        assertThat(jdbc.queryForObject(
            "select count(*) from orders where user_id = ?::uuid",
            Long.class,
            recipient.id().toString()
        )).isEqualTo(orderCountBefore);
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where type = 'ORDER_PAYMENT'
              and user_id = ?::uuid
            """, Long.class, recipient.id().toString())).isEqualTo(1);
    }

    @Test
    void serializedCashMutationsUsePostLockTimeAndSequenceEvenForTimestampTies()
        throws Exception {
        User account = user("ledger-sequence");
        Instant firstRequestAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant secondRequestAt = firstRequestAt.plusSeconds(1);
        CountDownLatch secondCreditWritten = new CountDownLatch(1);
        CountDownLatch letSecondCommit = new CountDownLatch(1);
        CountDownLatch firstCreditStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> second = pool.submit(() -> transaction.execute(status -> {
                ledger.credit(
                    account.id(), 1_000, BalanceLogType.SURPLUS_CREDIT,
                    null, null, secondRequestAt
                );
                secondCreditWritten.countDown();
                await(letSecondCommit);
                return null;
            }));
            assertThat(secondCreditWritten.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> first = pool.submit(() -> {
                firstCreditStarted.countDown();
                ledger.credit(
                    account.id(), 1_000, BalanceLogType.SURPLUS_CREDIT,
                    null, null, firstRequestAt
                );
            });
            assertThat(firstCreditStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(150);
            assertThat(first.isDone())
                .as("the earlier caller is queued behind the account lock")
                .isFalse();

            letSecondCommit.countDown();
            second.get(5, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
        } finally {
            letSecondCommit.countDown();
            pool.shutdownNow();
        }

        assertThat(balance(account)).isEqualTo(2_000);
        assertThat(balances.summary(account.id()).totalCreditsMinor()).isEqualTo("2000");
        List<com.sinx.platform.balance.application.BalanceLogView> ordered =
            balances.logs(account.id(), 0, 20).items();
        assertThat(ordered).extracting(
            com.sinx.platform.balance.application.BalanceLogView::balanceAfterMinor
        ).containsExactly("2000", "1000");
        assertThat(ordered).extracting(
            com.sinx.platform.balance.application.BalanceLogView::canViewOrder
        ).containsExactly(false, false);
        Instant laterPostedAt = Instant.parse(ordered.get(0).createdAt());
        Instant earlierPostedAt = Instant.parse(ordered.get(1).createdAt());
        assertThat(laterPostedAt).isAfter(earlierPostedAt);
        assertThat(earlierPostedAt).isAfter(secondRequestAt);

        // If timestamps tie (as with a fixed clock), sequence order remains the
        // authoritative posting order instead of UUID tie-breaking.
        jdbc.update("update balance_logs set created_at = ? where user_id = ?::uuid",
            Timestamp.from(Instant.parse("2026-10-06T00:00:00Z")), account.id().toString());
        assertThat(balances.logs(account.id(), 0, 20).items()).extracting(
            com.sinx.platform.balance.application.BalanceLogView::balanceAfterMinor
        ).containsExactly("2000", "1000");
    }

    @Test
    void accountDeletionRemovesOnlyTheOpeningAnchorAndRollsItBackForRealHistory()
        throws Exception {
        User emptyAccount = user("opening-only-account");
        insertOpeningAnchor(emptyAccount);
        destroyAccount(emptyAccount, 200);
        assertThat(userExists(emptyAccount)).isFalse();
        assertThat(balanceLogCount(emptyAccount)).isZero();

        User orderAccount = user("opening-and-order-account");
        insertOpeningAnchor(orderAccount);
        String pendingOrder = place(orderAccount, plan("Delete blocked by order", 1_000));
        destroyAccount(orderAccount, 409);
        assertThat(userExists(orderAccount)).isTrue();
        assertThat(order(pendingOrder).get("status")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where user_id = ?::uuid
              and type = 'OPENING_BALANCE'
            """, Long.class, orderAccount.id().toString())).isEqualTo(1);

        User financialAccount = user("opening-and-financial-account");
        insertOpeningAnchor(financialAccount);
        User buyer = user("financial-account-invited-buyer");
        String commissionTrade = place(buyer, plan("Account deletion commission", 10_000));
        settle(commissionTrade);
        ledger.credit(
            financialAccount.id(),
            1_000,
            BalanceLogType.COMMISSION_CREDIT,
            commissionTrade,
            1,
            Instant.now()
        );
        assertThat(balance(financialAccount)).isEqualTo(1_000);

        destroyAccount(financialAccount, 409);
        assertThat(userExists(financialAccount)).isTrue();
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where user_id = ?::uuid
              and type = 'OPENING_BALANCE'
            """, Long.class, financialAccount.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from balance_logs where user_id = ?::uuid
              and type = 'COMMISSION_CREDIT'
            """, Long.class, financialAccount.id().toString())).isEqualTo(1);
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

    private void setInviter(User buyer, User inviter) {
        jdbc.update("update users set inviter_user_id = ?::uuid where id = ?::uuid",
            inviter.id().toString(), buyer.id().toString());
        jdbc.update("update users set commission_type = 1 where id = ?::uuid", inviter.id().toString());
    }

    private UUID plan(String name, long monthlyAmount) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into service_plans (
                id, name, description, transfer_limit_bytes, speed_limit_mbps,
                reset_policy, published, sellable, renewable, sort_order,
                created_at, updated_at
            ) values (?::uuid, ?, '', 1073741824, 100, 'MONTHLY_FROM_ACTIVATION',
                      true, true, true, 0, ?, ?)
            """, id.toString(), name, now, now);
        jdbc.update("""
            insert into service_plan_prices (id, plan_id, billing_period, amount_minor, currency)
            values (?::uuid, ?::uuid, 'MONTHLY', ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), monthlyAmount);
        return id;
    }

    private String place(User buyer, UUID plan) throws Exception {
        String query = "mutation { placeOrder(planId: \"" + plan
            + "\", period: MONTHLY) { tradeNo status } }";
        MvcResult result = mvc.perform(post("/gateway")
                .with(userJwt(buyer.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql(query)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.placeOrder.tradeNo");
    }

    private void settle(String tradeNo) throws Exception {
        mvc.perform(post("/api/v2/admin/order/paid")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trade_no\":\"" + tradeNo + "\"}"))
            .andExpect(status().isOk());
    }

    private Map<String, Object> order(String tradeNo) {
        return jdbc.queryForMap("select * from orders where trade_no = ?", tradeNo);
    }

    private long balance(User user) {
        return jdbc.queryForObject("select balance_minor from users where id = ?::uuid",
            Long.class, user.id().toString());
    }

    private long ledgerAmount(String tradeNo, String type) {
        return jdbc.queryForObject("""
            select amount_minor from balance_logs where trade_no = ? and type = ?
            """, Long.class, tradeNo, type);
    }

    private void insertOpeningAnchor(User user) {
        long current = balance(user);
        jdbc.update("""
            insert into balance_logs (
                id, user_id, type, amount_minor, balance_after_minor, currency, created_at
            ) values (?::uuid, ?::uuid, 'OPENING_BALANCE', ?, ?, 'CNY', current_timestamp)
            """, UUID.randomUUID().toString(), user.id().toString(), current, current);
    }

    private void destroyAccount(User user, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v2/admin/user/destroy")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + user.id() + "\"}"))
            .andExpect(status().is(expectedStatus));
    }

    private boolean userExists(User user) {
        return jdbc.queryForObject("select exists(select 1 from users where id = ?::uuid)",
            Boolean.class, user.id().toString());
    }

    private long balanceLogCount(User user) {
        return jdbc.queryForObject("select count(*) from balance_logs where user_id = ?::uuid",
            Long.class, user.id().toString());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The serialized balance test timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The serialized balance test was interrupted", exception);
        }
    }

    private long number(Map<String, Object> row, String name) {
        Object value = row.get(name);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private void installFailureTrigger(String type) {
        jdbc.execute("""
            create function balance_test_failure() returns trigger language plpgsql as $$
            begin
                if new.type = '%s' then raise exception 'balance log failure'; end if;
                return new;
            end $$
            """.formatted(type));
        jdbc.execute("""
            create trigger balance_test_failure_trigger before insert on balance_logs
            for each row execute function balance_test_failure()
            """);
    }

    private void dropFailureTrigger() {
        jdbc.execute("drop trigger balance_test_failure_trigger on balance_logs");
        jdbc.execute("drop function balance_test_failure()");
    }

    private String graphql(String query) {
        return "{\"query\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + "\"}";
    }

    private record User(UUID id, String email) { }
}
