package com.sinx.platform.payment.domain;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Proves V38 backfills only real prior callbacks, preserving their amount and identity. */
@Testcontainers
class PaymentReceiptMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_payment_receipt_migration")
            .withUsername("sinx")
            .withPassword("sinx_test");

    @Test
    void v38BackfillsProcessedCallbacksButNotManualOrAutomaticSentinels()
        throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("37"))
            .load()
            .migrate();

        Seed seed;
        try (Connection connection = connection()) {
            seed = insertHistoricalFixture(connection);
        }

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("38"))
            .load()
            .migrate();

        try (Connection connection = connection()) {
            assertThat(scalar(connection,
                "SELECT count(*) FROM payment_attempts WHERE trade_no LIKE 'SX-MIG-%'"))
                .isEqualTo(9);
            assertThat(scalar(connection,
                "SELECT count(*) FROM payment_receipts WHERE trade_no LIKE 'SX-MIG-%'"))
                .isEqualTo(5);

            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT gateway_url, merchant_identity
                FROM payment_attempts WHERE trade_no = ?
                """)) {
                statement.setString(1, seed.completedTradeNo());
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("gateway_url"))
                        .isEqualTo("https://gateway.test/EPay/Base");
                    assertThat(row.getString("merchant_identity")).isEqualTo("Merchant-A");
                    assertThat(row.next()).isFalse();
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT gateway, gateway_url, merchant_identity, transaction_id,
                    amount_minor, outcome, received_at, attempt_id
                FROM payment_receipts WHERE trade_no = ?
                """)) {
                statement.setString(1, seed.completedTradeNo());
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("gateway")).isEqualTo("epay");
                    assertThat(row.getString("gateway_url"))
                        .isEqualTo("https://gateway.test/EPay/Base");
                    assertThat(row.getString("merchant_identity")).isEqualTo("Merchant-A");
                    assertThat(row.getString("transaction_id"))
                        .isEqualTo("GW-HISTORIC-COMPLETED");
                    assertThat(row.getLong("amount_minor")).isEqualTo(1300);
                    assertThat(row.getString("outcome")).isEqualTo("ORDER_SETTLED");
                    assertThat(row.getTimestamp("received_at")).isEqualTo(
                        Timestamp.from(seed.completedPaidAt()));
                    assertThat(row.getObject("attempt_id")).isNull();
                    assertThat(row.next()).isFalse();
                }
            }

            assertThat(scalar(connection, """
                SELECT count(*) FROM payment_receipts
                WHERE transaction_id IN ('manual_operation', 'auto_settled')
                """)).isZero();
            assertThat(scalar(connection, """
                SELECT count(*) FROM payment_receipts
                WHERE trade_no = ?
                """, seed.pendingTradeNo())).isZero();
            assertThat(scalar(connection, """
                SELECT count(*) FROM payment_receipts
                WHERE transaction_id = 'GW-HISTORIC-AMBIGUOUS'
                """)).isEqualTo(1);
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT gateway_url FROM payment_receipts
                WHERE transaction_id = 'GW-HISTORIC-UNKNOWN'
                """)) {
                try (ResultSet rows = statement.executeQuery()) {
                    java.util.List<String> identities = new java.util.ArrayList<>();
                    while (rows.next()) {
                        identities.add(rows.getString(1));
                    }
                    assertThat(identities).containsExactlyInAnyOrder(
                        "legacy://unknown/" + seed.unknownMethodOneUuid(),
                        "legacy://unknown/" + seed.unknownMethodTwoUuid());
                }
            }
        }
    }

    private Seed insertHistoricalFixture(Connection connection) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        String methodUuid = UUID.randomUUID().toString().replace("-", "");
        Instant createdAt = Instant.parse("2026-09-01T00:00:00Z");
        Instant completedPaidAt = Instant.parse("2026-09-02T03:04:05Z");
        insert(connection, """
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, proxy_uuid, created_at, updated_at
            ) VALUES (?, ?, 'hash', 'Migration user', 'ACTIVE', ?, ?, ?, ?)
            """, userId, "migration-" + userId + "@example.test",
            "sub-" + userId, userId, Timestamp.from(createdAt), Timestamp.from(createdAt));
        insert(connection, """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes, reset_policy,
                created_at, updated_at
            ) VALUES (?, 'Plan', '', 1073741824, 'MONTHLY_FROM_ACTIVATION', ?, ?)
            """, planId, Timestamp.from(createdAt), Timestamp.from(createdAt));
        insert(connection, """
            INSERT INTO payment_methods (
                id, uuid, gateway, name, config, notify_domain, enabled,
                sort_order, created_at, updated_at
            ) VALUES (?, ?, 'EPay', 'Legacy EPay',
                '{"url":"HTTPS://Gateway.Test:443/EPay/Base/","pid":"Merchant-A","key":"historic-test-key"}',
                'HTTPS://Legacy.Example.Test:443/path/', FALSE, 0, ?, ?)
            """, methodId, methodUuid, Timestamp.from(createdAt), Timestamp.from(createdAt));

        String completedTradeNo = "SX-MIG-COMPLETED-1";
        insertOrder(connection, userId, planId, methodId, completedTradeNo,
            "COMPLETED", "GW-HISTORIC-COMPLETED", completedPaidAt, "SERVICE_FULFILLED");
        insertOrder(connection, userId, planId, methodId, "SX-MIG-DISCOUNTED-1",
            "DISCOUNTED", "GW-HISTORIC-DISCOUNTED", completedPaidAt.plusSeconds(1),
            "SERVICE_FULFILLED");
        insertOrder(connection, userId, planId, methodId, "SX-MIG-AMBIGUOUS-1",
            "COMPLETED", "GW-HISTORIC-AMBIGUOUS", completedPaidAt.plusSeconds(4),
            "SERVICE_FULFILLED");
        insertOrder(connection, userId, planId, methodId, "SX-MIG-AMBIGUOUS-2",
            "COMPLETED", "GW-HISTORIC-AMBIGUOUS", completedPaidAt.plusSeconds(5),
            "SERVICE_FULFILLED");
        insertOrder(connection, userId, planId, methodId, "SX-MIG-MANUAL-1",
            "COMPLETED", "manual_operation", completedPaidAt.plusSeconds(2),
            "SERVICE_FULFILLED");
        insertOrder(connection, userId, planId, methodId, "SX-MIG-AUTO-1",
            "COMPLETED", "auto_settled", completedPaidAt.plusSeconds(3),
            "SERVICE_FULFILLED");
        String pendingTradeNo = "SX-MIG-PENDING-1";
        insertOrder(connection, userId, planId, methodId, pendingTradeNo,
            "PENDING", null, null, "PENDING");

        UUID unknownMethodOne = UUID.randomUUID();
        String unknownMethodOneUuid = UUID.randomUUID().toString().replace("-", "");
        UUID unknownMethodTwo = UUID.randomUUID();
        String unknownMethodTwoUuid = UUID.randomUUID().toString().replace("-", "");
        insert(connection, """
            INSERT INTO payment_methods (
                id, uuid, gateway, name, config, notify_domain, enabled,
                sort_order, created_at, updated_at
            ) VALUES (?, ?, 'EPay', 'Legacy missing URL one',
                '{"pid":"Merchant-A","key":"historic-test-key"}', NULL, FALSE, 0, ?, ?),
                   (?, ?, 'EPay', 'Legacy missing URL two',
                '{"pid":"Merchant-A","key":"historic-test-key"}', NULL, FALSE, 0, ?, ?)
            """, unknownMethodOne, unknownMethodOneUuid, Timestamp.from(createdAt),
            Timestamp.from(createdAt), unknownMethodTwo, unknownMethodTwoUuid,
            Timestamp.from(createdAt), Timestamp.from(createdAt));
        insertOrder(connection, userId, planId, unknownMethodOne,
            "SX-MIG-UNKNOWN-1", "COMPLETED", "GW-HISTORIC-UNKNOWN",
            completedPaidAt.plusSeconds(6), "SERVICE_FULFILLED");
        insertOrder(connection, userId, planId, unknownMethodTwo,
            "SX-MIG-UNKNOWN-2", "COMPLETED", "GW-HISTORIC-UNKNOWN",
            completedPaidAt.plusSeconds(7), "SERVICE_FULFILLED");
        return new Seed(completedTradeNo, pendingTradeNo, completedPaidAt,
            unknownMethodOneUuid, unknownMethodTwoUuid);
    }

    private void insertOrder(
        Connection connection,
        UUID userId,
        UUID planId,
        UUID methodId,
        String tradeNo,
        String status,
        String callbackNo,
        Instant paidAt,
        String outcome
    ) throws Exception {
        Instant createdAt = Instant.parse("2026-09-01T00:00:00Z");
        insert(connection, """
            INSERT INTO orders (
                id, trade_no, user_id, plan_id, plan_name, period, order_type,
                status, currency, original_amount, total_amount, payment_method_id,
                gateway, handling_amount, commission_buyer_user_id,
                commission_base, commission_balance, actual_commission_balance,
                surplus_order_ids, created_at, updated_at, paid_at, callback_no,
                settlement_outcome
            ) VALUES (?, ?, ?, ?, 'Plan', 'MONTHLY', 'NEW_PURCHASE', ?, 'CNY',
                1200, 1200, ?, 'EPay', 100, ?, 0, 0, 0, '[]', ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), tradeNo, userId, planId, status, methodId,
            userId, Timestamp.from(createdAt), Timestamp.from(createdAt),
            paidAt == null ? null : Timestamp.from(paidAt), callbackNo, outcome);
    }

    private void insert(Connection connection, String sql, Object... values)
        throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private long scalar(Connection connection, String sql, Object... values)
        throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record Seed(
        String completedTradeNo,
        String pendingTradeNo,
        Instant completedPaidAt,
        String unknownMethodOneUuid,
        String unknownMethodTwoUuid
    ) {
    }
}
