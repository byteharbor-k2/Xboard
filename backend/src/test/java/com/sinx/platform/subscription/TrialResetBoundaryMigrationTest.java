package com.sinx.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** V32 removes deployed trial anchors without changing paid monthly cycles. */
@Testcontainers
class TrialResetBoundaryMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_trial_reset_migration_test")
            .withUsername("sinx")
            .withPassword("sinx_test");

    @Test
    void v32ClearsExistingTrialAnchorsAndPreservesPaidMonthlyAnchors()
        throws Exception {
        flyway(MigrationVersion.fromVersion("31")).migrate();

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant dueBoundary = now.minusSeconds(2L * 86400);
        UUID planId = UUID.randomUUID();
        UUID trialEntitlementId = UUID.randomUUID();
        UUID paidEntitlementId = UUID.randomUUID();
        insertPlan(planId, now);
        insertEntitlement(trialEntitlementId, planId, now, dueBoundary, true);
        insertEntitlement(paidEntitlementId, planId, now, dueBoundary, false);

        flyway(null).migrate();

        assertThat(nextResetAt(trialEntitlementId)).isNull();
        assertThat(nextResetAt(paidEntitlementId)).isEqualTo(dueBoundary);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
            );
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertPlan(UUID planId, Instant now) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO service_plans (
                     id, name, description, plan_type, transfer_limit_bytes,
                     speed_limit_mbps, reset_policy, resettable, published,
                     sellable, renewable, sort_order, created_at, updated_at
                 ) VALUES (?, 'Migration trial plan', '', 'SUBSCRIPTION',
                           4000000, 100, 'MONTHLY_FROM_ACTIVATION', FALSE,
                           TRUE, TRUE, TRUE, 0, ?, ?)
                 """)) {
            statement.setObject(1, planId);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void insertEntitlement(
        UUID entitlementId,
        UUID planId,
        Instant now,
        Instant boundary,
        boolean trial
    ) throws Exception {
        UUID userId = UUID.randomUUID();
        try (Connection connection = connection()) {
            try (PreparedStatement user = connection.prepareStatement("""
                INSERT INTO users (
                    id, email, password_hash, display_name, status,
                    subscription_token, created_at, updated_at
                ) VALUES (?, ?, 'migration-test', 'Migration test', 'ACTIVE',
                          ?, ?, ?)
                """)) {
                user.setObject(1, userId);
                user.setString(2, "migration-" + userId + "@example.test");
                user.setString(3, UUID.randomUUID().toString().replace("-", ""));
                user.setTimestamp(4, Timestamp.from(now));
                user.setTimestamp(5, Timestamp.from(now));
                user.executeUpdate();
            }
            try (PreparedStatement entitlement = connection.prepareStatement("""
                INSERT INTO subscription_entitlements (
                    id, user_id, plan_id, plan_name, transfer_limit_bytes,
                    uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                    reset_policy, starts_at, expires_at, next_reset_at,
                    is_trial, created_at, updated_at
                ) VALUES (?, ?, ?, 'Migration trial plan', 4000000,
                          111, 222, 100, 'MONTHLY_FROM_ACTIVATION', ?, ?, ?,
                          ?, ?, ?)
                """)) {
                entitlement.setObject(1, entitlementId);
                entitlement.setObject(2, userId);
                entitlement.setObject(3, planId);
                entitlement.setTimestamp(4, Timestamp.from(now.minusSeconds(3 * 86400)));
                entitlement.setTimestamp(5, Timestamp.from(now.plusSeconds(3600)));
                entitlement.setTimestamp(6, Timestamp.from(boundary));
                entitlement.setBoolean(7, trial);
                entitlement.setTimestamp(8, Timestamp.from(now));
                entitlement.setTimestamp(9, Timestamp.from(now));
                entitlement.executeUpdate();
            }
        }
    }

    private Instant nextResetAt(UUID entitlementId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT next_reset_at FROM subscription_entitlements WHERE id = ?"
             )) {
            statement.setObject(1, entitlementId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                Timestamp value = result.getTimestamp(1);
                return value == null ? null : value.toInstant();
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
    }
}
