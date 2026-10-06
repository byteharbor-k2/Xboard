package com.sinx.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.subscription.application.TrafficResetService;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Registration trials do not enter the paid monthly traffic reset cycle. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationTrialResetIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RegistrationTrialResetIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_trial_reset_test")
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
    private MockMvc mockMvc;

    @Autowired
    private TrafficResetService trafficResets;

    @Autowired
    private SubscriptionEntitlementRepository entitlements;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationMail;

    @Test
    void registrationTrialApiHasNoNextResetBoundary() throws Exception {
        UUID planId = createPlan("Trial plan " + UUID.randomUUID());
        saveSetting("new_user.try_out_plan_id", planId.toString());
        saveSetting("new_user.try_out_hour", "3");

        String email = "trial-reset-" + UUID.randomUUID() + "@example.test";
        String accessToken = register(email);

        MvcResult response = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerEntitlement { isTrial resetPolicy nextResetAt expiresAt } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerEntitlement.isTrial").value(true))
            .andExpect(jsonPath("$.data.viewerEntitlement.resetPolicy")
                .value("NEVER"))
            .andExpect(jsonPath("$.data.viewerEntitlement.expiresAt").isNotEmpty())
            .andReturn();
        Object nextResetAt = JsonPath.read(
            response.getResponse().getContentAsString(),
            "$.data.viewerEntitlement.nextResetAt"
        );
        assertThat(nextResetAt).isNull();

        String userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            String.class,
            email
        );
        assertThat(jdbc.queryForObject(
            "SELECT next_reset_at FROM subscription_entitlements WHERE user_id = ?::uuid",
            Timestamp.class,
            userId
        )).isNull();
    }

    @Test
    void expiredAndLegacyTrialsAreNotResetOrBackfilled() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID planId = createPlan("Legacy trial plan " + UUID.randomUUID());
        UUID activeLegacyTrial = seedTrial(
            planId,
            now.plusSeconds(3600),
            now.minusSeconds(45L * 86400)
        );
        UUID expiredNullBoundaryTrial = seedTrial(
            planId,
            now.minusSeconds(3600),
            null
        );

        assertThat(trafficResets.runMonthlyResets()).isZero();

        assertThat(counters(activeLegacyTrial)).containsExactly(111L, 222L);
        assertThat(nextResetAt(activeLegacyTrial))
            .isEqualTo(now.minusSeconds(45L * 86400));
        assertThat(counters(expiredNullBoundaryTrial)).containsExactly(333L, 444L);
        assertThat(nextResetAt(expiredNullBoundaryTrial)).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM traffic_reset_records WHERE entitlement_id IN (?::uuid, ?::uuid)",
            Integer.class,
            activeLegacyTrial.toString(),
            expiredNullBoundaryTrial.toString()
        )).isZero();
    }

    @Test
    void manualResetOfAnActiveTrialClearsUsageWithoutAddingACycle() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID planId = createPlan("Manual trial reset " + UUID.randomUUID());
        UUID trial = seedTrial(planId, now.plusSeconds(3600), null);

        transactions.executeWithoutResult(status ->
            trafficResets.recordManualReset(
                entitlements.findByIdForUpdate(trial).orElseThrow(),
                now
            )
        );

        assertThat(counters(trial)).containsExactly(0L, 0L);
        assertThat(nextResetAt(trial)).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM traffic_reset_records WHERE entitlement_id = ?",
            Integer.class,
            trial
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT uploaded_bytes_before + downloaded_bytes_before "
                + "FROM traffic_reset_records WHERE entitlement_id = ?",
            Long.class,
            trial
        )).isEqualTo(333L);
    }

    @Test
    void directAutomaticResetAndBackfillEntryPointsIgnoreAnActiveTrial() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID planId = createPlan("Direct trial reset " + UUID.randomUUID());
        UUID dueLegacyTrial = seedTrial(
            planId,
            now.plusSeconds(3600),
            now.minusSeconds(45L * 86400)
        );
        UUID trialWithoutBoundary = seedTrial(
            planId,
            now.plusSeconds(3600),
            null
        );

        assertThat(trafficResets.resetDueEntitlement(dueLegacyTrial)).isFalse();
        trafficResets.settleMissingBoundary(trialWithoutBoundary);

        assertThat(counters(dueLegacyTrial)).containsExactly(111L, 222L);
        assertThat(nextResetAt(dueLegacyTrial))
            .isEqualTo(now.minusSeconds(45L * 86400));
        assertThat(counters(trialWithoutBoundary)).containsExactly(111L, 222L);
        assertThat(nextResetAt(trialWithoutBoundary)).isNull();
    }

    private UUID createPlan(String name) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO service_plans (
                id, name, description, plan_type, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, resettable, published, sellable,
                renewable, sort_order, created_at, updated_at
            ) VALUES (?, ?, '', 'SUBSCRIPTION', 4000000, 100,
                      'MONTHLY_FROM_ACTIVATION', FALSE, TRUE, TRUE, TRUE, 0, ?, ?)
            """,
            planId,
            name,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return planId;
    }

    private UUID seedTrial(UUID planId, Instant expiresAt, Instant nextResetAt) {
        UUID userId = UUID.randomUUID();
        UUID entitlementId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at, version
            ) VALUES (?, ?, 'seed-hash', 'Legacy trial', 'ACTIVE', ?, ?, ?, 0)
            """,
            userId,
            "legacy-trial-" + userId + "@example.test",
            UUID.randomUUID().toString().replace("-", ""),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbc.update("""
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at, is_trial,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'Legacy trial', 4000000, 111, 222, 100,
                      'MONTHLY_FROM_ACTIVATION', ?, ?, ?, TRUE, ?, ?)
            """,
            entitlementId,
            userId,
            planId,
            Timestamp.from(now.minusSeconds(3 * 60 * 60)),
            expiresAt == null ? null : Timestamp.from(expiresAt),
            nextResetAt == null ? null : Timestamp.from(nextResetAt),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        if (expiresAt != null && expiresAt.isBefore(now)) {
            jdbc.update(
                "UPDATE subscription_entitlements SET uploaded_bytes = 333, downloaded_bytes = 444 WHERE id = ?",
                entitlementId
            );
        }
        return entitlementId;
    }

    private long[] counters(UUID entitlementId) {
        return jdbc.query(
            "SELECT uploaded_bytes, downloaded_bytes FROM subscription_entitlements WHERE id = ?",
            (result, rowNumber) -> new long[]{result.getLong(1), result.getLong(2)},
            entitlementId
        ).getFirst();
    }

    private Instant nextResetAt(UUID entitlementId) {
        return jdbc.query(
            "SELECT next_reset_at FROM subscription_entitlements WHERE id = ?",
            (result, rowNumber) -> result.getTimestamp(1) == null
                ? null
                : result.getTimestamp(1).toInstant(),
            entitlementId
        ).getFirst();
    }

    private void saveSetting(String key, String value) {
        jdbc.update("""
            INSERT INTO platform_settings (setting_key, setting_value, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (setting_key)
            DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = now()
            """, key, value);
    }

    private String register(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
            .andExpect(status().isAccepted());
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"%s",
                      "password":"trial-reset-password",
                      "displayName":"Trial Reset",
                      "emailCode":"%s"
                    }
                    """.formatted(email, registrationMail.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.accessToken"
        );
    }

    @TestConfiguration
    static class TestMailConfiguration {

        @Bean
        @Primary
        RecordingRegistrationCodeMailSender recordingRegistrationCodeMailSender() {
            return new RecordingRegistrationCodeMailSender();
        }
    }

    static class RecordingRegistrationCodeMailSender
        implements RegistrationCodeMailSender {

        private final AtomicReference<String> latestCode = new AtomicReference<>();

        @Override
        public void sendRegistrationCode(String recipient, String code) {
            latestCode.set(code);
        }

        String latestCode() {
            return latestCode.get();
        }
    }
}
