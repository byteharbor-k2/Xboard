package com.sinx.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
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
import org.flywaydb.core.Flyway;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.application.PlanManagementService;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.subscription.application.TrafficResetService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/** The complete reset policy matrix against migrated PostgreSQL state and row locks. */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TrafficResetPolicyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_reset_policy_test")
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
    private TrafficResetService resets;

    @Autowired
    private PlatformConfigurationService configuration;

    @Autowired
    private SubscriptionEntitlementRepository entitlements;

    @Autowired
    private PlanManagementService planManagement;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private ServicePlanRepository plans;

    @Autowired
    private UserAccountRepository users;

    @Autowired
    private ApplicationEventPublisher events;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("TRUNCATE platform_settings, traffic_reset_records, "
            + "subscription_entitlements, service_plan_tags, service_plan_prices, "
            + "service_plans, orders, users RESTART IDENTITY CASCADE");
    }

    @Test
    void schedulerRunsAllFourAutomaticModesOnceAndSkipsNeverTrialsAndPackages() {
        Instant now = Instant.now();
        List<TrafficResetPolicy> policies = List.of(
            TrafficResetPolicy.FIRST_DAY_OF_MONTH,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            TrafficResetPolicy.FIRST_DAY_OF_YEAR,
            TrafficResetPolicy.YEARLY_FROM_ACTIVATION
        );
        List<UUID> paidRows = policies.stream().map(policy -> seedEntitlement(
            policy.name(), "SUBSCRIPTION", false, now.minusSeconds(3600),
            now.plusSeconds(86_400), now.minusSeconds(30 * 86_400L), null
        )).toList();
        UUID never = seedEntitlement(
            "NEVER", "SUBSCRIPTION", false, now.minusSeconds(3600),
            now.plusSeconds(86_400), now.minusSeconds(30 * 86_400L), null
        );
        UUID trial = seedEntitlement(
            "MONTHLY_FROM_ACTIVATION", "SUBSCRIPTION", true,
            now.minusSeconds(3600), now.plusSeconds(86_400),
            now.minusSeconds(30 * 86_400L), null
        );
        UUID packageRow = seedEntitlement(
            "NEVER", "TRAFFIC_PACKAGE", false, now.minusSeconds(3600),
            null, now.minusSeconds(30 * 86_400L), null
        );

        assertThat(resets.runMonthlyResets()).isEqualTo(4);
        for (UUID id : paidRows) {
            assertThat(counters(id)).containsExactly(0L, 0L);
            assertThat(nextReset(id)).isAfter(now);
        }
        assertThat(counters(never)).containsExactly(100L, 200L);
        assertThat(counters(trial)).containsExactly(100L, 200L);
        assertThat(counters(packageRow)).containsExactly(100L, 200L);
        assertThat(resetCount()).isEqualTo(4);
        assertThat(resets.runMonthlyResets()).isZero();
        assertThat(resetCount()).isEqualTo(4);
    }

    @Test
    void globalChangesReanchorOnlyInheritedPaidRowsWithoutClearingUsage() {
        Instant now = Instant.now();
        UUID inherited = seedEntitlement(
            "MONTHLY_FROM_ACTIVATION", "SUBSCRIPTION", false,
            now.minusSeconds(30 * 86_400L), now.plusSeconds(90 * 86_400L),
            now.minusSeconds(86_400), true
        );
        UUID explicit = seedEntitlement(
            "YEARLY_FROM_ACTIVATION", "SUBSCRIPTION", false,
            now.minusSeconds(30 * 86_400L), now.plusSeconds(90 * 86_400L),
            now.plusSeconds(300 * 86_400L), false
        );
        UUID trial = seedEntitlement(
            "NEVER", "SUBSCRIPTION", true,
            now.minusSeconds(30 * 86_400L), now.plusSeconds(90 * 86_400L),
            null, true
        );
        Instant explicitAnchor = nextReset(explicit);

        configuration.saveSectionSettings(
            "subscribe", java.util.Map.of("globalreset_traffic_method", 2)
        );

        assertThat(counters(inherited)).containsExactly(100L, 200L);
        assertThat(policyOf(inherited)).isEqualTo("NEVER");
        assertThat(nextReset(inherited)).isNull();
        assertThat(policyOf(explicit)).isEqualTo("YEARLY_FROM_ACTIVATION");
        assertThat(nextReset(explicit)).isEqualTo(explicitAnchor);
        assertThat(policyOf(trial)).isEqualTo("NEVER");
        assertThat(nextReset(trial)).isNull();

        configuration.saveSectionSettings(
            "subscribe", java.util.Map.of("globalreset_traffic_method", 1)
        );
        assertThat(counters(inherited)).containsExactly(100L, 200L);
        assertThat(policyOf(inherited)).isEqualTo("MONTHLY_FROM_ACTIVATION");
        assertThat(nextReset(inherited)).isAfter(Instant.now());
        assertThat(nextReset(explicit)).isEqualTo(explicitAnchor);
    }

    @Test
    void manualResetAlwaysRecordsButNeverCreatesANonResettingAnchor() {
        Instant now = Instant.now();
        UUID never = seedEntitlement(
            "NEVER", "TRAFFIC_PACKAGE", false,
            now.minusSeconds(3600), null, now.minusSeconds(30 * 86_400L), null
        );
        UUID trial = seedEntitlement(
            "NEVER", "SUBSCRIPTION", true,
            now.minusSeconds(3600), now.plusSeconds(86_400), null, null
        );
        transactions.executeWithoutResult(status -> {
            var locked = entitlements.findByIdForUpdate(never).orElseThrow();
            resets.recordManualReset(locked, Instant.now());
            var trialLocked = entitlements.findByIdForUpdate(trial).orElseThrow();
            resets.recordManualReset(trialLocked, Instant.now());
        });

        assertThat(counters(never)).containsExactly(0L, 0L);
        assertThat(nextReset(never)).isNull();
        assertThat(counters(trial)).containsExactly(0L, 0L);
        assertThat(nextReset(trial)).isNull();
        assertThat(resetCount()).isEqualTo(2);
    }

    @Test
    void editingAnExplicitPlanSynchronizesItsEntitlementBoundaryWithoutUsageReset() {
        Instant now = Instant.now();
        UUID entitlement = seedEntitlement(
            "YEARLY_FROM_ACTIVATION", "SUBSCRIPTION", false,
            now.minusSeconds(30 * 86_400L), now.plusSeconds(90 * 86_400L),
            now.plusSeconds(180 * 86_400L), false
        );
        UUID planId = jdbc.queryForObject(
            "SELECT plan_id FROM subscription_entitlements WHERE id = ?::uuid",
            UUID.class, entitlement.toString()
        );

        planManagement.update(planId, new PlanManagementService.PlanDraft(
            "Policy plan", "", com.sinx.platform.catalog.domain.PlanType.SUBSCRIPTION,
            "1000000", 100, TrafficResetPolicy.NEVER, null, false, null,
            true, true, true, 1, List.of(),
            List.of(new PlanManagementService.PriceDraft(
                BillingPeriod.MONTHLY, 1000, "CNY"
            )), null
        ));

        assertThat(counters(entitlement)).containsExactly(100L, 200L);
        assertThat(policyOf(entitlement)).isEqualTo("NEVER");
        assertThat(nextReset(entitlement)).isNull();
    }

    @Test
    void v34TurnsOnlyTheHistoricalMonthlyPlanDefaultIntoInheritance() throws Exception {
        String schema = "reset_migration_" + UUID.randomUUID()
            .toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            Flyway migration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target("33")
                .load();
            migration.migrate();

            Instant now = Instant.now();
            insertMigrationPlan(connection, schema, UUID.randomUUID(),
                "MONTHLY_FROM_ACTIVATION", "SUBSCRIPTION", now);
            insertMigrationPlan(connection, schema, UUID.randomUUID(),
                "FIRST_DAY_OF_YEAR", "SUBSCRIPTION", now);
            insertMigrationPlan(connection, schema, UUID.randomUUID(),
                "NEVER", "TRAFFIC_PACKAGE", now);

            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            assertThat(readPolicies(connection, schema))
                .containsExactlyInAnyOrder("<inherit>", "FIRST_DAY_OF_YEAR", "NEVER");
        } finally {
            try (Connection cleanup = dataSource.getConnection();
                 Statement statement = cleanup.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void grantCommittedAfterGlobalReconciliationRechecksPolicyAfterCommit() throws Exception {
        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        seedAccountAndInheritedPlan(userId, planId, now);
        CountDownLatch policyCaptured = new CountDownLatch(1);
        CountDownLatch continueGrant = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var grant = executor.submit(() -> transactions.execute(status -> {
                UserAccount account = users.findById(userId).orElseThrow();
                ServicePlan plan = plans.findById(planId).orElseThrow();
                TrafficResetPolicy observedPolicy = resets.effectivePolicy(plan);
                assertThat(observedPolicy)
                    .isEqualTo(TrafficResetPolicy.MONTHLY_FROM_ACTIVATION);
                policyCaptured.countDown();
                await(continueGrant);

                UUID entitlementId = UUID.randomUUID();
                var staleGrant = com.sinx.platform.subscription.domain.SubscriptionEntitlement
                    .grant(
                        entitlementId,
                        account,
                        plan,
                        now.minusSeconds(30 * 86_400L),
                        now.plusSeconds(30 * 86_400L),
                        now.minusSeconds(60),
                        observedPolicy,
                        now
                    );
                staleGrant.recordUsage(123L, 456L, now);
                entitlements.save(staleGrant);
                events.publishEvent(new UserEntitlementChangedEvent(
                    userId, List.of(), now
                ));
                return entitlementId;
            }));

            assertThat(policyCaptured.await(10, TimeUnit.SECONDS)).isTrue();
            configuration.saveSectionSettings(
                "subscribe", java.util.Map.of("globalreset_traffic_method", 2)
            );
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscription_entitlements WHERE user_id = ?::uuid",
                Integer.class, userId.toString()
            )).isZero();

            continueGrant.countDown();
            UUID entitlementId = grant.get(10, TimeUnit.SECONDS);

            assertThat(policyOf(entitlementId)).isEqualTo("NEVER");
            assertThat(nextReset(entitlementId)).isNull();
            assertThat(counters(entitlementId)).containsExactly(123L, 456L);
            assertThat(resets.resetDueEntitlement(entitlementId)).isFalse();
            assertThat(counters(entitlementId)).containsExactly(123L, 456L);
            assertThat(resetCount()).isZero();
        } finally {
            continueGrant.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void grantCommittedAfterPlanPolicyReconciliationRechecksExplicitPolicy() throws Exception {
        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        seedAccountAndExplicitPlan(userId, planId, now);
        CountDownLatch policyCaptured = new CountDownLatch(1);
        CountDownLatch continueGrant = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var grant = executor.submit(() -> transactions.execute(status -> {
                UserAccount account = users.findById(userId).orElseThrow();
                ServicePlan plan = plans.findById(planId).orElseThrow();
                TrafficResetPolicy observedPolicy = resets.effectivePolicy(plan);
                assertThat(observedPolicy)
                    .isEqualTo(TrafficResetPolicy.MONTHLY_FROM_ACTIVATION);
                policyCaptured.countDown();
                await(continueGrant);

                UUID entitlementId = UUID.randomUUID();
                var staleGrant = com.sinx.platform.subscription.domain.SubscriptionEntitlement
                    .grant(
                        entitlementId, account, plan,
                        now.minusSeconds(30 * 86_400L),
                        now.plusSeconds(30 * 86_400L),
                        now.minusSeconds(60), observedPolicy, now
                    );
                staleGrant.recordUsage(234L, 567L, now);
                entitlements.save(staleGrant);
                events.publishEvent(new UserEntitlementChangedEvent(
                    userId, List.of(), now
                ));
                return entitlementId;
            }));

            assertThat(policyCaptured.await(10, TimeUnit.SECONDS)).isTrue();
            planManagement.update(planId, new PlanManagementService.PlanDraft(
                "Racing explicit plan", "", com.sinx.platform.catalog.domain.PlanType.SUBSCRIPTION,
                "1000000", 100, TrafficResetPolicy.NEVER, null, false, null,
                true, true, true, 0, List.of(),
                List.of(new PlanManagementService.PriceDraft(
                    BillingPeriod.MONTHLY, 1000, "CNY"
                )), null
            ));
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscription_entitlements WHERE user_id = ?::uuid",
                Integer.class, userId.toString()
            )).isZero();

            continueGrant.countDown();
            UUID entitlementId = grant.get(10, TimeUnit.SECONDS);

            assertThat(policyOf(entitlementId)).isEqualTo("NEVER");
            assertThat(nextReset(entitlementId)).isNull();
            assertThat(counters(entitlementId)).containsExactly(234L, 567L);
        } finally {
            continueGrant.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void directResetRechecksTheSourcePolicyInsteadOfTrustingAStaleSnapshot() {
        Instant now = Instant.now();
        UUID staleSnapshot = seedEntitlement(
            "MONTHLY_FROM_ACTIVATION", "SUBSCRIPTION", false,
            now.minusSeconds(30 * 86_400L), now.plusSeconds(30 * 86_400L),
            now.minusSeconds(60), true
        );
        jdbc.update("""
            INSERT INTO platform_settings (setting_key, setting_value, updated_at)
            VALUES ('subscribe.globalreset_traffic_method', '2', ?)
            """, Timestamp.from(now));

        assertThat(resets.resetDueEntitlement(staleSnapshot)).isFalse();
        assertThat(policyOf(staleSnapshot)).isEqualTo("NEVER");
        assertThat(nextReset(staleSnapshot)).isNull();
        assertThat(counters(staleSnapshot)).containsExactly(100L, 200L);
        assertThat(resetCount()).isZero();
    }

    private UUID seedEntitlement(
        String entitlementPolicy,
        String planType,
        boolean trial,
        Instant startsAt,
        Instant expiresAt,
        Instant nextResetAt,
        Boolean inherited
    ) {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID entitlementId = UUID.randomUUID();
        Instant now = Instant.now();
        String planPolicy = inherited == null
            ? entitlementPolicy
            : inherited ? "MONTHLY_FROM_ACTIVATION" : "YEARLY_FROM_ACTIVATION";
        if ("TRAFFIC_PACKAGE".equals(planType)) {
            planPolicy = "NEVER";
        }
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at, version
            ) VALUES (?::uuid, ?, 'seed-hash', 'Policy test', 'ACTIVE', ?, ?, ?, 0)
            """, userId.toString(), userId + "@example.test",
            userId.toString().replace("-", ""), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
            INSERT INTO service_plans (
                id, name, description, plan_type, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, capacity_limit, resettable,
                published, sellable, renewable, sort_order, created_at, updated_at
            ) VALUES (?::uuid, 'Policy plan', '', ?, 1000000, 100, ?, NULL,
                      FALSE, TRUE, TRUE, ?, 1, ?, ?)
        """, planId.toString(), planType,
            inherited != null && inherited ? null : planPolicy,
            !"TRAFFIC_PACKAGE".equals(planType), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps, reset_policy,
                starts_at, expires_at, next_reset_at, canceled_at, is_trial,
                created_at, updated_at, version
            ) VALUES (?::uuid, ?::uuid, ?::uuid, 'Policy plan', 1000000,
                      100, 200, 100, ?, ?, ?, ?, NULL, ?, ?, ?, 0)
            """, entitlementId.toString(), userId.toString(), planId.toString(),
            entitlementPolicy, Timestamp.from(startsAt), timestamp(expiresAt),
            timestamp(nextResetAt), trial, Timestamp.from(now), Timestamp.from(now));
        return entitlementId;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private long[] counters(UUID id) {
        return jdbc.query("SELECT uploaded_bytes, downloaded_bytes "
                + "FROM subscription_entitlements WHERE id = ?::uuid",
            (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, id.toString())
            .get(0);
    }

    private Instant nextReset(UUID id) {
        return jdbc.queryForObject("SELECT next_reset_at FROM subscription_entitlements "
            + "WHERE id = ?::uuid", (rs, row) -> {
                Timestamp value = rs.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }, id.toString());
    }

    private String policyOf(UUID id) {
        return jdbc.queryForObject("SELECT reset_policy FROM subscription_entitlements "
            + "WHERE id = ?::uuid", String.class, id.toString());
    }

    private int resetCount() {
        return jdbc.queryForObject("SELECT count(*) FROM traffic_reset_records", Integer.class);
    }

    private void insertMigrationPlan(
        Connection connection,
        String schema,
        UUID id,
        String policy,
        String planType,
        Instant now
    ) throws Exception {
        try (var statement = connection.prepareStatement("""
            INSERT INTO %s.service_plans (
                id, name, description, transfer_limit_bytes, speed_limit_mbps,
                reset_policy, capacity_limit, published, sellable, renewable,
                sort_order, created_at, updated_at, version, plan_type, resettable
            ) VALUES (?, 'Migration plan', '', 1000, 10, ?, NULL, TRUE, TRUE,
                      ?, 0, ?, ?, 0, ?, FALSE)
            """.formatted(schema))) {
            statement.setObject(1, id);
            statement.setString(2, policy);
            statement.setBoolean(3, "SUBSCRIPTION".equals(planType));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setString(6, planType);
            statement.executeUpdate();
        }
    }

    private void seedAccountAndInheritedPlan(
        UUID userId,
        UUID planId,
        Instant now
    ) {
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at, version
            ) VALUES (?::uuid, ?, 'seed-hash', 'Race test', 'ACTIVE', ?, ?, ?, 0)
            """, userId.toString(), userId + "@example.test",
            userId.toString().replace("-", ""), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
            INSERT INTO service_plans (
                id, name, description, plan_type, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, capacity_limit, resettable,
                published, sellable, renewable, sort_order, created_at, updated_at
            ) VALUES (?::uuid, 'Racing inherited plan', '', 'SUBSCRIPTION',
                      1000000, 100, NULL, NULL, FALSE, TRUE, TRUE, TRUE, 0, ?, ?)
            """, planId.toString(), Timestamp.from(now), Timestamp.from(now));
    }

    private void seedAccountAndExplicitPlan(
        UUID userId,
        UUID planId,
        Instant now
    ) {
        seedAccountAndInheritedPlan(userId, planId, now);
        jdbc.update(
            "UPDATE service_plans SET reset_policy = 'MONTHLY_FROM_ACTIVATION' "
                + "WHERE id = ?::uuid",
            planId.toString()
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for race barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Race barrier was interrupted", exception);
        }
    }

    private List<String> readPolicies(Connection connection, String schema)
        throws Exception {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(
                 "SELECT reset_policy FROM " + schema + ".service_plans"
             )) {
            java.util.ArrayList<String> policies = new java.util.ArrayList<>();
            while (result.next()) {
                String policy = result.getString(1);
                policies.add(policy == null ? "<inherit>" : policy);
            }
            return policies;
        }
    }
}
