package com.sinx.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.subscription.application.TrafficResetService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The monthly traffic cycle, users' allowances and the ledger that reports
 * it.
 *
 * Three guarantees are exercised against a real database: a due entitlement
 * empties together with a boundary that marches on and a record row carrying
 * what was spent, and no second run repeats that; an account outside the
 * cycle - not yet due, not monthly, cancelled, expired - is left exactly as
 * it was; and two callers racing the same row bring it down once, with one
 * record and no lost allowance. The records endpoint is judged from the
 * admin surface outward, anonymous and with the admin scope.
 *
 * Rows are seeded straight into the schema, the way a database migrated by
 * V26 already holds them: counters standing, an anchor boundary set or not
 * set. The sweep is driven through {@link TrafficResetService}, the cron
 * wrapper around it being the only thing between it and the clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TrafficResetIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TrafficResetIntegrationTest {

    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_traffic_reset_test")
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TrafficResetService trafficResets;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @BeforeEach
    void truncateSlate() {
        jdbcTemplate.update(
            """
            TRUNCATE traffic_reset_records, subscription_entitlements,
                     service_plans, users
            RE-start IDENTITY CASCADE
            """.replace("RE-start", "RESTART")
        );
    }

    @Test
    void aDueEntitlementIsEmptiedOnTheBoundaryAndHistoryKeepsTheSpend() {
        UUID userId = seedUser("cycle@example.com");
        UUID entitlementId = seedEntitlement(
            userId, "MONTHLY_FROM_ACTIVATION",
            1111L, 2222L,
            lastMonthlyAnchor(),
            null
        );

        int resets = trafficResets.runMonthlyResets();

        assertThat(resets).isEqualTo(1);
        assertThat(countersOf(entitlementId)).contains(0L, 0L);
        // The boundary marches on: the next due instant lies a month away
        // from the chain anchor, firmly in the future.
        Instant nextBoundary = nextBoundaryOf(entitlementId);
        assertThat(nextBoundary).isAfter(Instant.now());
        assertThat(nextBoundary).isBefore(Instant.now().plusSeconds(32L * 86400));

        List<Map<String, Object>> rows = recordsOf(userId);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("uploaded_bytes_before"))
            .longValue()).isEqualTo(1111L);
        assertThat(((Number) rows.get(0).get("downloaded_bytes_before"))
            .longValue()).isEqualTo(2222L);
        assertThat(rows.get(0).get("entitlement_id").toString())
            .isEqualTo(entitlementId.toString());

        // Idempotent: the same pass run again walks over an empty ledger.
        int repeats = trafficResets.runMonthlyResets();
        assertThat(repeats).isZero();
        assertThat(recordsOf(userId)).hasSize(1);
        assertThat(countersOf(entitlementId)).contains(0L, 0L);
    }

    @Test
    void anEntitlementNotYetDueIsLeftExactlyAsItStands() {
        UUID userId = seedUser("early@example.com");
        UUID entitlementId = seedEntitlement(
            userId, "MONTHLY_FROM_ACTIVATION",
            50L, 60L,
            Instant.now().plusSeconds(5L * 86400),
            null
        );

        int resets = trafficResets.runMonthlyResets();

        assertThat(resets).isZero();
        assertThat(countersOf(entitlementId)).contains(50L, 60L);
        assertThat(recordsOf(userId)).isEmpty();
    }

    @Test
    void aNeverPolicyIsNeverCycled() {
        UUID userId = seedUser("perpetual@example.com");
        UUID entitlementId = seedEntitlement(
            userId, "NEVER", 7L, 8L, lastMonthlyAnchor(), null
        );

        int resets = trafficResets.runMonthlyResets();

        assertThat(resets).isZero();
        assertThat(countersOf(entitlementId)).contains(7L, 8L);
        assertThat(recordsOf(userId)).isEmpty();
    }

    @Test
    void aCancelledOrExpiredEntitlementSitsOutsideTheCycle() {
        UUID cancelledUser = seedUser("cancelled@example.com");
        UUID cancelled = seedEntitlement(
            cancelledUser, "MONTHLY_FROM_ACTIVATION",
            9L, 10L, lastMonthlyAnchor(),
            Timestamp.from(Instant.now().minusSeconds(3600))
        );
        UUID expiredUser = seedUser("expired@example.com");
        UUID expired = seedEntitlement(
            expiredUser, "MONTHLY_FROM_ACTIVATION",
            11L, 12L, lastMonthlyAnchor(),
            null,
            Timestamp.from(Instant.now().minusSeconds(100L * 86400))
        );
        jdbcTemplate.update(
            "UPDATE subscription_entitlements SET expires_at = ?::timestamptz "
                + "WHERE id = ?::uuid",
            Timestamp.from(Instant.now().minusSeconds(1L * 86400)),
            expired.toString()
        );

        int resets = trafficResets.runMonthlyResets();

        assertThat(resets).isZero();
        assertThat(countersOf(cancelled)).contains(9L, 10L);
        assertThat(countersOf(expired)).contains(11L, 12L);
        assertThat(recordsOf(cancelledUser)).isEmpty();
    }

    @Test
    void aCycleGrantedWithoutABoundaryIsSeededFromItsActivation() {
        // A row migrated before V26 still reads a NULL boundary; the sweep
        // anchors it at its activation and resets nothing here - the due
        // pass that follows walks the chain forward.
        UUID userId = seedUser("moved-over@example.com");
        Instant activated = Instant.now().minusSeconds(10L * 86400);
        UUID entitlementId = seedEntitlement(
            userId, "MONTHLY_FROM_ACTIVATION", 0L, 0L, null, null,
            Timestamp.from(activated)
        );

        int resets = trafficResets.runMonthlyResets();

        assertThat(resets).isZero();
        assertThat(countersOf(entitlementId))
            .contains(0L, 0L);
        assertThat(recordsOf(userId)).isEmpty();
        // Anchored at the activation, one calendar month along it - firmly
        // in the future, since the activation is ten days recent.
        Instant seeded = nextBoundaryOf(entitlementId);
        assertThat(seeded).isNotNull();
        assertThat(seeded).isAfter(Instant.now());
        assertThat(seeded).isBefore(activated.plusSeconds(35L * 86400));
    }

    @Test
    void twoRacesOnTheSameRowBringItDownExactlyOnce() throws Exception {
        UUID userId = seedUser("raced@example.com");
        UUID entitlementId = seedEntitlement(
            userId, "MONTHLY_FROM_ACTIVATION",
            8_888L, 9_999L,
            lastMonthlyAnchor(),
            null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            Future<Integer> first = executor.submit(() -> {
                ready.countDown();
                return trafficResets.resetDueEntitlement(entitlementId) ? 1 : 0;
            });
            Future<Integer> second = executor.submit(() -> {
                ready.countDown();
                return trafficResets.resetDueEntitlement(entitlementId) ? 1 : 0;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            // Exactly one of the two racers actually reset: the pessimistic
            // row lock lets them in one at a time and the second finds the
            // boundary already pushed.
            assertThat(first.get(30, TimeUnit.SECONDS)
                + second.get(30, TimeUnit.SECONDS))
                .isEqualTo(1);

            long recordCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM traffic_reset_records WHERE user_id = "
                    + "?::uuid", Long.class, userId.toString());
            assertThat(recordCount).isEqualTo(1);
            assertThat(countersOf(entitlementId))
                .contains(0L, 0L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aManualResetRecordsAndReanchorsWithoutTheCycle() {
        UUID userId = seedUser("manual@example.com");
        UUID entitlementId = seedEntitlement(
            userId, "MONTHLY_FROM_ACTIVATION",
            500L, 600L,
            Instant.now().plusSeconds(10L * 86400),
            null
        );

        Instant now = Instant.now();
        // The manual path: the administrator's service has already opened
        // its transaction, loaded the locked entitlement and is about to
        // push the change to the nodes after it. Reproduced here in the
        // same shape - one transaction wrapping load, record and reset.
        transactionTemplate.executeWithoutResult(status -> {
            trafficResets.recordManualReset(
                entitlementRepository.findByIdForUpdate(entitlementId)
                    .orElseThrow(),
                now
            );
        });

        assertThat(countersOf(entitlementId))
            .contains(0L, 0L);
        // Re-anchored at the manual reset: the next cycle is a month from
        // this button press, not the subscription's original anniversary.
        Instant nextBoundary = nextBoundaryOf(entitlementId);
        assertThat(nextBoundary).isAfter(now);
        // A month and a half: a re-anchor is a calendar month, and a
        // boundary landing late this hour cannot be claimed as a slip.
        assertThat(nextBoundary).isBefore(now.plusSeconds(33L * 86400));

        List<Map<String, Object>> rows = recordsOf(userId);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("uploaded_bytes_before"))
            .longValue()).isEqualTo(500L);
        assertThat(((Number) rows.get(0).get("downloaded_bytes_before"))
            .longValue()).isEqualTo(600L);
    }

    @Test
    void theRecordsEndpointServesTheLedgerToAdministratorsOnly()
        throws Exception {
        UUID listedUser = seedUser("ledger@example.com");
        UUID otherUser = seedUser("other@example.com");
        UUID oldTriple = UUID.randomUUID();
        Instant old = Instant.now().minusSeconds(3L * 86400);
        jdbcTemplate.update(
            "INSERT INTO traffic_reset_records (id, user_id, entitlement_id, "
                + "reset_at, uploaded_bytes_before, downloaded_bytes_before, "
                + "created_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, "
                + "1, 2, ?::timestamptz)",
            oldTriple.toString(), listedUser.toString(),
            UUID.randomUUID().toString(),
            Timestamp.from(old), Timestamp.from(old)
        );
        UUID newer = UUID.randomUUID();
        Instant recent = Instant.now();
        jdbcTemplate.update(
            "INSERT INTO traffic_reset_records (id, user_id, entitlement_id, "
                + "reset_at, uploaded_bytes_before, downloaded_bytes_before, "
                + "created_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, "
                + "3, 4, ?::timestamptz)",
            newer.toString(), listedUser.toString(),
            UUID.randomUUID().toString(),
            Timestamp.from(recent), Timestamp.from(recent)
        );
        jdbcTemplate.update(
            "INSERT INTO traffic_reset_records (id, user_id, entitlement_id, "
                + "reset_at, uploaded_bytes_before, downloaded_bytes_before, "
                + "created_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, "
                + "5, 6, ?::timestamptz)",
            UUID.randomUUID().toString(), otherUser.toString(),
            UUID.randomUUID().toString(),
            Timestamp.from(recent), Timestamp.from(recent)
        );

        // Anonymous: not one byte of the ledger reaches an unauthenticated
        // caller.
        mockMvc.perform(get("/api/v2/admin/traffic-reset/records"))
            .andExpect(status().isUnauthorized());

        // Administrators read the newest first.
        mockMvc.perform(get("/api/v2/admin/traffic-reset/records")
                .with(administrator()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        // The other user's row is excluded by an exact id filter.
        String body = mockMvc.perform(get("/api/v2/admin/traffic-reset/records")
                .with(administrator())
                .param("user_id", listedUser.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data[*].user_id");
        assertThat(ids).containsExactly(
            listedUser.toString(), listedUser.toString());
        // Newest first: the two lines for one account run back of time.
        List<Number> resetMoments = JsonPath.read(body, "$.data[*].reset_at");
        assertThat(resetMoments.get(0).longValue()).isGreaterThanOrEqualTo(
            resetMoments.get(1).longValue());
        // The account's who is joined in, so a page needn't.
        String email = JsonPath.read(body, "$.data[0].email");
        assertThat(email).isEqualTo("ledger@example.com");
        List<Number> uploads = JsonPath.read(body, "$.data[*].uploaded_bytes_before");
        assertThat(uploads.get(0).intValue()).isEqualTo(3);
        assertThat(uploads.get(1).intValue()).isEqualTo(1);
    }

    private RequestPostProcessor administrator() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .jwt()
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_ADMIN")
            );
    }

    /** The shared due anchor: a month and a day ago, the shape of a monthly chain. */
    private static Instant lastMonthlyAnchor() {
        return Instant.now().minusSeconds(31L * 86400);
    }

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at, version
            ) VALUES (?::uuid, ?, 'seed-hash', ?, 'ACTIVE', ?, ?, ?, 0)
            """,
            userId.toString(),
            email,
            email,
            UUID.randomUUID().toString().replace("-", ""),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return userId;
    }

    private UUID seedEntitlement(
        UUID userId,
        String resetPolicy,
        long uploadedBytes,
        long downloadedBytes,
        Instant nextResetAt,
        Timestamp canceledAt
    ) {
        return seedEntitlement(
            userId, resetPolicy, uploadedBytes, downloadedBytes,
            nextResetAt, canceledAt, Timestamp.from(Instant.now())
        );
    }

    private UUID seedEntitlement(
        UUID userId,
        String resetPolicy,
        long uploadedBytes,
        long downloadedBytes,
        Instant nextResetAt,
        Timestamp canceledAt,
        Timestamp startsAt
    ) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, capacity_limit,
                published, sellable, renewable, sort_order,
                created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?, 100, ?, NULL, TRUE, TRUE, TRUE, 1, ?, ?
            )
            """,
            planId.toString(),
            "Reset plan",
            "Reset plan",
            TRANSFER_LIMIT,
            resetPolicy,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        UUID entitlementId = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                canceled_at, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Reset plan', ?,
                ?, ?, 100, ?, ?, NULL, ?, ?, ?, ?
            )
            """,
            entitlementId.toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            uploadedBytes,
            downloadedBytes,
            resetPolicy,
            startsAt,
            nextResetAt == null
                ? null
                : Timestamp.from(nextResetAt),
            canceledAt,
            startsAt,
            startsAt
        );
        return entitlementId;
    }

    /** Counters read straight from the schema, entity caches aside. */
    private long[] countersOf(UUID entitlementId) {
        return jdbcTemplate.query(
            "SELECT uploaded_bytes, downloaded_bytes "
                + "FROM subscription_entitlements WHERE id = ?::uuid",
            (rs, rowNum) -> new long[]{rs.getLong(1), rs.getLong(2)},
            entitlementId.toString()
        ).get(0);
    }

    private Instant nextBoundaryOf(UUID entitlementId) {
        return jdbcTemplate.query(
            "SELECT next_reset_at FROM subscription_entitlements "
                + "WHERE id = ?::uuid",
            (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
            entitlementId.toString()
        ).get(0);
    }

    private List<Map<String, Object>> recordsOf(UUID userId) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM traffic_reset_records WHERE user_id = ?::uuid",
            userId.toString()
        );
    }

    /**
     * The synced instance of one entitlement row, the way the administrator's
     * reset button reaches it.
     */

    @Autowired
    private SubscriptionEntitlementRepository entitlementRepository;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate
        transactionTemplate;

    @TestConfiguration
    static class TestMailConfiguration {

        @Bean
        @Primary
        RecordingRegistrationCodeMailSender
            recordingRegistrationCodeMailSender() {
            return new RecordingRegistrationCodeMailSender();
        }
    }

    static class RecordingRegistrationCodeMailSender
        implements RegistrationCodeMailSender {

        private final java.util.concurrent.atomic.AtomicReference<String>
            latestCode = new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public void sendRegistrationCode(String recipient, String code) {
            latestCode.set(code);
        }

        String latestCode() {
            return latestCode.get();
        }
    }
}
