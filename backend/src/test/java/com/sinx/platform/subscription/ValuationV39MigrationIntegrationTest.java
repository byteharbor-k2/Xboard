package com.sinx.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** V39 backfills live-cycle consumption and prior claims from populated V38 history. */
@Testcontainers
class ValuationV39MigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_valuation_v39_migration_test")
            .withUsername("sinx")
            .withPassword("sinx_test");

    @Test
    void migratesV37HistoryWithoutRevivingStaleClaimsOrPendingResets() throws Exception {
        // Populate the actual V37 shape, then let Flyway apply both V38 and V39.
        flyway(MigrationVersion.fromVersion("37")).migrate();

        Instant now = postgresMicros(Instant.now());
        Instant cycleStart = postgresMicros(now.minus(Duration.ofDays(10)));
        Instant automaticBoundaryReset = postgresMicros(cycleStart.plus(Duration.ofMinutes(30)));
        Instant originalCycleEnd = postgresMicros(
            cycleStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        Instant adminReanchoredBoundary = postgresMicros(now.plus(Duration.ofDays(35)));
        UUID userId = UUID.randomUUID();
        UUID originalPayerId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID entitlementId = UUID.randomUUID();
        insertAccountAndPlan(userId, planId, now);
        insertAccount(originalPayerId, now);
        insertEntitlement(userId, planId, entitlementId, cycleStart,
            adminReanchoredBoundary, now);

        UUID reactivatedUserId = UUID.randomUUID();
        UUID reactivatedPlanId = UUID.randomUUID();
        UUID previousPlanId = UUID.randomUUID();
        UUID reactivatedEntitlementId = UUID.randomUUID();
        Instant reactivatedAt = postgresMicros(now.minus(Duration.ofDays(5)));
        Instant reactivatedCycleEnd = postgresMicros(
            reactivatedAt.atZone(ZoneOffset.UTC).plusYears(1).toInstant());
        Instant staleClaimedAt = postgresMicros(reactivatedAt.minus(Duration.ofDays(3)));
        Instant previousAutomaticAt = postgresMicros(staleClaimedAt.minus(Duration.ofDays(10)));
        Instant previousManualAt = postgresMicros(staleClaimedAt.minus(Duration.ofDays(2)));
        Instant previousCycleEnd = postgresMicros(
            previousAutomaticAt.minus(Duration.ofMinutes(30))
                .atZone(ZoneOffset.UTC).plusYears(1).toInstant());
        insertAccountAndPlan(reactivatedUserId, reactivatedPlanId, now);
        insertPlan(previousPlanId, now, "Previous activation plan");
        insertEntitlementAtActivation(reactivatedUserId, reactivatedPlanId,
            reactivatedEntitlementId, reactivatedAt, reactivatedCycleEnd, now);
        insertCompletedNewPurchase(reactivatedUserId, reactivatedPlanId,
            reactivatedAt, reactivatedCycleEnd);
        String staleClaimTradeNo = insertHistoricalResetOrder(reactivatedUserId,
            previousPlanId, previousCycleEnd, staleClaimedAt);
        insertClaim(reactivatedUserId, previousCycleEnd, staleClaimTradeNo,
            staleClaimedAt);
        insertResetRecord(reactivatedUserId, reactivatedEntitlementId,
            staleClaimedAt, 3_000, now);
        // The old automatic row is recognized from the previous paid order's
        // saved yearly boundary, not from this activation's next_reset_at.
        insertResetRecord(reactivatedUserId, reactivatedEntitlementId,
            previousAutomaticAt, 9_000, now);
        insertResetRecord(reactivatedUserId, reactivatedEntitlementId,
            previousManualAt, 2_000, now);

        UUID reanchoredUserId = UUID.randomUUID();
        UUID reanchoredPlanId = UUID.randomUUID();
        UUID reanchoredEntitlementId = UUID.randomUUID();
        Instant reanchoredStart = postgresMicros(now.minus(Duration.ofDays(20)));
        Instant actualCycleEnd = postgresMicros(now.plus(Duration.ofDays(35)));
        Instant stalePendingCycleEnd = postgresMicros(now.plus(Duration.ofDays(20)));
        insertAccountAndPlan(reanchoredUserId, reanchoredPlanId, now);
        insertEntitlementAtActivation(reanchoredUserId, reanchoredPlanId,
            reanchoredEntitlementId, reanchoredStart, actualCycleEnd, now);
        String stalePendingTradeNo = insertPendingLegacyResetOrder(reanchoredUserId,
            reanchoredPlanId, stalePendingCycleEnd, now.minus(Duration.ofDays(2)));

        String firstTradeNo = UUID.randomUUID().toString().replace("-", "");
        String secondTradeNo = UUID.randomUUID().toString().replace("-", "");
        UUID firstClaimId = UUID.randomUUID();
        UUID secondClaimId = UUID.randomUUID();
        Instant firstClaimedAt = postgresMicros(cycleStart.plus(Duration.ofDays(8)));
        Instant secondClaimedAt = postgresMicros(cycleStart.plus(Duration.ofDays(9)));
        Instant laterManualAt = postgresMicros(cycleStart.plus(Duration.ofDays(10)));
        insertResetOrders(userId, planId, now, List.of(
            new ResetOrder(firstTradeNo, originalCycleEnd),
            new ResetOrder(secondTradeNo, adminReanchoredBoundary)
        ));
        String assignedWalletTrade = insertAssignedWalletOrder(userId,
            originalPayerId, planId, now);
        insertClaims(userId, firstClaimId, secondClaimId, originalCycleEnd,
            adminReanchoredBoundary, firstTradeNo, secondTradeNo,
            firstClaimedAt, secondClaimedAt);
        insertResetRecords(userId, entitlementId, now, cycleStart,
            automaticBoundaryReset, firstClaimedAt, secondClaimedAt, laterManualAt);

        flyway(null).migrate();

        CycleState state = cycleState(entitlementId);
        assertThat(state.cycleStart()).isEqualTo(automaticBoundaryReset);
        assertThat(state.cycleEnd()).isEqualTo(originalCycleEnd);
        assertThat(state.cycleId()).isNotNull();
        // Only in-cycle manual/paid reset records count. The cycle-closing
        // automatic reset's 9,000 bytes and live counters are not in this field.
        assertThat(state.cycleConsumedBytes()).isEqualTo(3_900L);
        assertThat(state.cycleConsumedBytes() + state.uploadedBytes()
            + state.downloadedBytes()).isEqualTo(4_300L);
        assertThat(resetKind(entitlementId, automaticBoundaryReset)).isEqualTo("AUTOMATIC");
        assertThat(resetKind(entitlementId, firstClaimedAt)).isEqualTo("PAID");
        assertThat(resetRecordCycleId(entitlementId, firstClaimedAt))
            .isEqualTo(state.cycleId());
        assertThat(claimCycleId(firstTradeNo)).isEqualTo(state.cycleId());
        assertThat(claimCycleId(secondTradeNo)).isEqualTo(state.cycleId());
        assertThat(claimsInCycle(userId, state.cycleId())).isEqualTo(2L);
        assertThat(resetKind(entitlementId, secondClaimedAt)).isEqualTo("PAID");
        assertThat(resetKind(entitlementId, laterManualAt)).isEqualTo("MANUAL");
        assertThat(balancePayer(assignedWalletTrade)).isEqualTo(originalPayerId);

        CycleState reactivatedState = cycleState(reactivatedEntitlementId);
        assertThat(reactivatedState.cycleStart()).isEqualTo(reactivatedAt);
        assertThat(reactivatedState.cycleEnd()).isEqualTo(reactivatedCycleEnd);
        assertThat(resetKind(reactivatedEntitlementId, previousAutomaticAt))
            .isEqualTo("AUTOMATIC");
        assertThat(resetKind(reactivatedEntitlementId, previousManualAt))
            .isEqualTo("MANUAL");
        assertThat(resetKind(reactivatedEntitlementId, staleClaimedAt)).isEqualTo("PAID");
        assertThat(resetRecordCycleId(reactivatedEntitlementId, previousAutomaticAt))
            .isNotEqualTo(reactivatedState.cycleId());
        assertThat(resetRecordCycleId(reactivatedEntitlementId, previousManualAt))
            .isNotEqualTo(reactivatedState.cycleId());
        assertThat(claimCycleId(staleClaimTradeNo)).isNotEqualTo(reactivatedState.cycleId());
        assertThat(resetOrderCycleId(staleClaimTradeNo)).isNull();
        assertThat(resetRecordCycleId(reactivatedEntitlementId, staleClaimedAt))
            .isNotEqualTo(reactivatedState.cycleId());
        assertThat(reactivatedState.cycleConsumedBytes()).isZero();
        assertThat(claimsInCycle(reactivatedUserId, reactivatedState.cycleId())).isZero();
        assertThat(reactivatedState.uploadedBytes() + reactivatedState.downloadedBytes())
            .isEqualTo(100L);

        CycleState reanchoredState = cycleState(reanchoredEntitlementId);
        assertThat(reanchoredState.cycleEnd()).isEqualTo(actualCycleEnd);
        assertThat(resetOrderCycleId(stalePendingTradeNo)).isNull();
        assertThat(resetOrderCycleEnd(stalePendingTradeNo)).isEqualTo(stalePendingCycleEnd);
    }

    private void insertAccountAndPlan(UUID userId, UUID planId, Instant now)
        throws Exception {
        try (Connection connection = connection()) {
            try (PreparedStatement user = connection.prepareStatement("""
                INSERT INTO users (id, email, password_hash, display_name, status,
                    subscription_token, created_at, updated_at)
                VALUES (?, ?, 'migration-test', 'Migration test', 'ACTIVE', ?, ?, ?)
                """)) {
                user.setObject(1, userId);
                user.setString(2, "cycle-" + userId + "@example.test");
                user.setString(3, UUID.randomUUID().toString().replace("-", ""));
                user.setTimestamp(4, Timestamp.from(now));
                user.setTimestamp(5, Timestamp.from(now));
                user.executeUpdate();
            }
        }
        insertPlan(planId, now, "Migration cycle plan");
    }

    private void insertPlan(UUID planId, Instant now, String name) throws Exception {
        try (Connection connection = connection();
             PreparedStatement plan = connection.prepareStatement("""
                 INSERT INTO service_plans (id, name, description, plan_type,
                     transfer_limit_bytes, speed_limit_mbps, reset_policy,
                     resettable, published, sellable, renewable, sort_order,
                     created_at, updated_at)
                 VALUES (?, ?, '', 'SUBSCRIPTION', 10000, 50,
                     'YEARLY_FROM_ACTIVATION', TRUE, TRUE, TRUE, TRUE, 0, ?, ?)
                 """)) {
            plan.setObject(1, planId);
            plan.setString(2, name);
            plan.setTimestamp(3, Timestamp.from(now));
            plan.setTimestamp(4, Timestamp.from(now));
            plan.executeUpdate();
        }
    }

    private void insertAccount(UUID userId, Instant now) throws Exception {
        try (Connection connection = connection();
             PreparedStatement user = connection.prepareStatement("""
                 INSERT INTO users (id, email, password_hash, display_name, status,
                     subscription_token, created_at, updated_at)
                 VALUES (?, ?, 'migration-test', 'Migration payer', 'ACTIVE', ?, ?, ?)
                 """)) {
            user.setObject(1, userId);
            user.setString(2, "payer-" + userId + "@example.test");
            user.setString(3, UUID.randomUUID().toString().replace("-", ""));
            user.setTimestamp(4, Timestamp.from(now));
            user.setTimestamp(5, Timestamp.from(now));
            user.executeUpdate();
        }
    }

    private String insertAssignedWalletOrder(UUID currentOwnerId, UUID payerId,
        UUID planId, Instant now) throws Exception {
        String tradeNo = UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection()) {
            try (PreparedStatement order = connection.prepareStatement("""
                INSERT INTO orders (id, trade_no, user_id, plan_id, plan_name,
                    period, order_type, status, currency, original_amount,
                    balance_amount, total_amount, commission_buyer_user_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Assigned pending order', 'MONTHLY',
                    'NEW_PURCHASE', 'PENDING', 'CNY', 2000, 500, 1500, ?, ?, ?)
                """)) {
                order.setObject(1, UUID.randomUUID());
                order.setString(2, tradeNo);
                order.setObject(3, currentOwnerId);
                order.setObject(4, planId);
                order.setObject(5, payerId);
                order.setTimestamp(6, Timestamp.from(now));
                order.setTimestamp(7, Timestamp.from(now));
                order.executeUpdate();
            }
            try (PreparedStatement debit = connection.prepareStatement("""
                INSERT INTO balance_logs (id, user_id, type, amount_minor,
                    balance_after_minor, currency, trade_no, created_at)
                VALUES (?, ?, 'ORDER_PAYMENT', -500, 0, 'CNY', ?, ?)
                """)) {
                debit.setObject(1, UUID.randomUUID());
                debit.setObject(2, payerId);
                debit.setString(3, tradeNo);
                debit.setTimestamp(4, Timestamp.from(now));
                debit.executeUpdate();
            }
        }
        return tradeNo;
    }

    private void insertEntitlement(UUID userId, UUID planId, UUID entitlementId,
        Instant startsAt, Instant reanchoredBoundary, Instant now) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO subscription_entitlements (
                     id, user_id, plan_id, plan_name, transfer_limit_bytes,
                     uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                     reset_policy, starts_at, expires_at, next_reset_at,
                     is_trial, created_at, updated_at
                 ) VALUES (?, ?, ?, 'Migration cycle plan', 10000,
                           300, 100, 50, 'YEARLY_FROM_ACTIVATION', ?, ?, ?,
                           FALSE, ?, ?)
                 """)) {
            statement.setObject(1, entitlementId);
            statement.setObject(2, userId);
            statement.setObject(3, planId);
            statement.setTimestamp(4, Timestamp.from(startsAt.minus(Duration.ofDays(20))));
            statement.setTimestamp(5, Timestamp.from(now.plus(Duration.ofDays(90))));
            statement.setTimestamp(6, Timestamp.from(reanchoredBoundary));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void insertEntitlementAtActivation(UUID userId, UUID planId,
        UUID entitlementId, Instant startsAt, Instant nextResetAt, Instant now)
        throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO subscription_entitlements (
                     id, user_id, plan_id, plan_name, transfer_limit_bytes,
                     uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                     reset_policy, starts_at, expires_at, next_reset_at,
                     is_trial, created_at, updated_at
                 ) VALUES (?, ?, ?, 'Reactivated migration plan', 10000,
                           100, 0, 50, 'YEARLY_FROM_ACTIVATION', ?, ?, ?,
                           FALSE, ?, ?)
                 """)) {
            statement.setObject(1, entitlementId);
            statement.setObject(2, userId);
            statement.setObject(3, planId);
            statement.setTimestamp(4, Timestamp.from(startsAt));
            statement.setTimestamp(5, Timestamp.from(now.plus(Duration.ofDays(90))));
            statement.setTimestamp(6, Timestamp.from(nextResetAt));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void insertCompletedNewPurchase(UUID userId, UUID planId,
        Instant activatedAt, Instant coverageEnd) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO orders (id, trade_no, user_id, plan_id, plan_name,
                     period, order_type, status, currency, original_amount,
                     total_amount, created_at, updated_at, paid_at,
                     coverage_start, coverage_end)
                 VALUES (?, ?, ?, ?, 'Reactivated migration plan', 'YEARLY',
                     'NEW_PURCHASE', 'COMPLETED', 'CNY', 12000, 12000, ?, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, UUID.randomUUID().toString().replace("-", ""));
            statement.setObject(3, userId);
            statement.setObject(4, planId);
            statement.setTimestamp(5, Timestamp.from(activatedAt));
            statement.setTimestamp(6, Timestamp.from(activatedAt));
            statement.setTimestamp(7, Timestamp.from(activatedAt));
            statement.setTimestamp(8, Timestamp.from(activatedAt));
            statement.setTimestamp(9, Timestamp.from(coverageEnd));
            statement.executeUpdate();
        }
    }

    private String insertHistoricalResetOrder(UUID userId, UUID planId,
        Instant cycleEnd, Instant paidAt) throws Exception {
        String tradeNo = UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO orders (id, trade_no, user_id, plan_id, plan_name,
                     period, order_type, status, currency, original_amount,
                     total_amount, created_at, updated_at, paid_at, reset_cycle_end)
                 VALUES (?, ?, ?, ?, 'Reactivated migration plan', 'RESET_TRAFFIC',
                     'RESET_TRAFFIC', 'COMPLETED', 'CNY', 500, 500, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, tradeNo);
            statement.setObject(3, userId);
            statement.setObject(4, planId);
            statement.setTimestamp(5, Timestamp.from(paidAt));
            statement.setTimestamp(6, Timestamp.from(paidAt));
            statement.setTimestamp(7, Timestamp.from(paidAt));
            statement.setTimestamp(8, Timestamp.from(cycleEnd));
            statement.executeUpdate();
        }
        return tradeNo;
    }

    private String insertPendingLegacyResetOrder(UUID userId, UUID planId,
        Instant cycleEnd, Instant createdAt) throws Exception {
        String tradeNo = UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO orders (id, trade_no, user_id, plan_id, plan_name,
                     period, order_type, status, currency, original_amount,
                     total_amount, created_at, updated_at, reset_cycle_end)
                 VALUES (?, ?, ?, ?, 'Legacy pending reset', 'RESET_TRAFFIC',
                     'RESET_TRAFFIC', 'PENDING', 'CNY', 500, 500, ?, ?, ?)
                 """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, tradeNo);
            statement.setObject(3, userId);
            statement.setObject(4, planId);
            statement.setTimestamp(5, Timestamp.from(createdAt));
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setTimestamp(7, Timestamp.from(cycleEnd));
            statement.executeUpdate();
        }
        return tradeNo;
    }

    private void insertResetRecord(UUID userId, UUID entitlementId,
        Instant resetAt, long uploadedBytes, Instant createdAt) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO traffic_reset_records (id, user_id, entitlement_id,
                     reset_at, uploaded_bytes_before, downloaded_bytes_before,
                     created_at)
                 VALUES (?, ?, ?, ?, ?, 0, ?)
                 """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, userId);
            statement.setObject(3, entitlementId);
            statement.setTimestamp(4, Timestamp.from(resetAt));
            statement.setLong(5, uploadedBytes);
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private void insertClaim(UUID userId, Instant cycleEnd,
        String tradeNo, Instant claimedAt) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO paid_traffic_reset_claims
                     (id, user_id, cycle_end, trade_no, claimed_at)
                 VALUES (?, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, userId);
            statement.setTimestamp(3, Timestamp.from(cycleEnd));
            statement.setString(4, tradeNo);
            statement.setTimestamp(5, Timestamp.from(claimedAt));
            statement.executeUpdate();
        }
    }

    private void insertResetOrders(UUID userId, UUID planId, Instant now,
        List<ResetOrder> resetOrders) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO orders (id, trade_no, user_id, plan_id, plan_name,
                     period, order_type, status, currency, original_amount,
                     total_amount, created_at, updated_at, paid_at, reset_cycle_end)
                 VALUES (?, ?, ?, ?, 'Migration cycle plan', 'RESET_TRAFFIC',
                     'RESET_TRAFFIC', 'COMPLETED', 'CNY', 100, 100, ?, ?, ?, ?)
                 """)) {
            for (ResetOrder order : resetOrders) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, order.tradeNo());
                statement.setObject(3, userId);
                statement.setObject(4, planId);
                statement.setTimestamp(5, Timestamp.from(now));
                statement.setTimestamp(6, Timestamp.from(now));
                statement.setTimestamp(7, Timestamp.from(now));
                statement.setTimestamp(8, Timestamp.from(order.cycleEnd()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertClaims(UUID userId, UUID firstId, UUID secondId,
        Instant firstEnd, Instant secondEnd, String firstTradeNo,
        String secondTradeNo, Instant firstClaimedAt, Instant secondClaimedAt)
        throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO paid_traffic_reset_claims
                     (id, user_id, cycle_end, trade_no, claimed_at)
                 VALUES (?, ?, ?, ?, ?)
                 """)) {
            statement.setObject(1, firstId);
            statement.setObject(2, userId);
            statement.setTimestamp(3, Timestamp.from(firstEnd));
            statement.setString(4, firstTradeNo);
            statement.setTimestamp(5, Timestamp.from(firstClaimedAt));
            statement.executeUpdate();
            statement.setObject(1, secondId);
            statement.setObject(2, userId);
            statement.setTimestamp(3, Timestamp.from(secondEnd));
            statement.setString(4, secondTradeNo);
            statement.setTimestamp(5, Timestamp.from(secondClaimedAt));
            statement.executeUpdate();
        }
    }

    private void insertResetRecords(UUID userId, UUID entitlementId,
        Instant now, Instant cycleStart, Instant automaticBoundaryReset,
        Instant paidAt, Instant secondPaidAt, Instant laterManualAt)
        throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO traffic_reset_records (id, user_id, entitlement_id,
                     reset_at, uploaded_bytes_before, downloaded_bytes_before,
                     created_at)
                 VALUES (?, ?, ?, ?, ?, 0, ?)
                 """)) {
            addRecord(statement, userId, entitlementId, cycleStart.minus(Duration.ofDays(1)),
                7_777, now);
            addRecord(statement, userId, entitlementId, automaticBoundaryReset, 9_000, now);
            addRecord(statement, userId, entitlementId, cycleStart.plus(Duration.ofDays(4)),
                2_000, now);
            addRecord(statement, userId, entitlementId, paidAt, 1_000, now);
            addRecord(statement, userId, entitlementId, secondPaidAt, 500, now);
            addRecord(statement, userId, entitlementId, laterManualAt, 400, now);
            statement.executeBatch();
        }
    }

    private void addRecord(PreparedStatement statement, UUID userId,
        UUID entitlementId, Instant resetAt, long uploaded, Instant createdAt)
        throws Exception {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, userId);
        statement.setObject(3, entitlementId);
        statement.setTimestamp(4, Timestamp.from(resetAt));
        statement.setLong(5, uploaded);
        statement.setTimestamp(6, Timestamp.from(createdAt));
        statement.addBatch();
    }

    private CycleState cycleState(UUID entitlementId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT traffic_cycle_start, traffic_cycle_end, traffic_cycle_id,
                        cycle_consumed_bytes, uploaded_bytes, downloaded_bytes
                 FROM subscription_entitlements WHERE id = ?
                 """)) {
            statement.setObject(1, entitlementId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new CycleState(row.getTimestamp(1).toInstant(),
                    row.getTimestamp(2).toInstant(), row.getObject(3, UUID.class),
                    row.getLong(4), row.getLong(5), row.getLong(6));
            }
        }
    }

    private String resetKind(UUID entitlementId, Instant resetAt) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT reset_kind FROM traffic_reset_records
                 WHERE entitlement_id = ? AND reset_at = ?
                 """)) {
            statement.setObject(1, entitlementId);
            statement.setTimestamp(2, Timestamp.from(resetAt));
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private UUID resetRecordCycleId(UUID entitlementId, Instant resetAt) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT traffic_cycle_id FROM traffic_reset_records
                 WHERE entitlement_id = ? AND reset_at = ?
                 """)) {
            statement.setObject(1, entitlementId);
            statement.setTimestamp(2, Timestamp.from(resetAt));
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private UUID claimCycleId(String tradeNo) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT traffic_cycle_id FROM paid_traffic_reset_claims WHERE trade_no = ?")) {
            statement.setString(1, tradeNo);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private long claimsInCycle(UUID userId, UUID cycleId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT count(*) FROM paid_traffic_reset_claims
                 WHERE user_id = ? AND traffic_cycle_id = ?
                 """)) {
            statement.setObject(1, userId);
            statement.setObject(2, cycleId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private UUID balancePayer(String tradeNo) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT balance_payer_user_id FROM orders WHERE trade_no = ?")) {
            statement.setString(1, tradeNo);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private UUID resetOrderCycleId(String tradeNo) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT reset_cycle_id FROM orders WHERE trade_no = ?")) {
            statement.setString(1, tradeNo);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private Instant resetOrderCycleEnd(String tradeNo) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT reset_cycle_end FROM orders WHERE trade_no = ?")) {
            statement.setString(1, tradeNo);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getTimestamp(1).toInstant();
            }
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Instant postgresMicros(Instant instant) {
        return Instant.ofEpochSecond(instant.getEpochSecond(),
            instant.getNano() / 1_000 * 1_000L);
    }

    private record ResetOrder(String tradeNo, Instant cycleEnd) { }
    private record CycleState(Instant cycleStart, Instant cycleEnd, UUID cycleId,
        long cycleConsumedBytes, long uploadedBytes, long downloadedBytes) { }
}
