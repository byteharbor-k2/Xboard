package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Registration trials grant the configured subscription inside account creation. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RegistrationTrialIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RegistrationTrialIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_registration_trial_test")
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
    private PlatformConfigurationService configuration;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationMail;

    @AfterEach
    void clearNewUserSettings() {
        jdbc.update("DELETE FROM platform_settings WHERE setting_key LIKE 'new_user.%'");
        jdbc.update(
            "DELETE FROM platform_settings WHERE setting_key IN "
                + "('site.try_out_plan_id', 'site.try_out_hour')"
        );
    }

    @Test
    void registrationImmediatelyReceivesConfiguredTrialWithoutAnOrder()
        throws Exception {
        long groupId = createGroup();
        UUID planId = createPlan("SUBSCRIPTION", groupId, 4_000_000L, 120);
        saveSetting("try_out_plan_id", planId.toString());
        saveSetting("try_out_hour", 3);

        String email = uniqueEmail("trial");
        Registered account = register(email);

        MvcResult entitlementResponse = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerEntitlement { planId planName state transferLimitBytes speedLimitMbps startsAt expiresAt } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerEntitlement.planId")
                .value(planId.toString()))
            .andExpect(jsonPath("$.data.viewerEntitlement.state")
                .value("ACTIVE"))
            .andExpect(jsonPath("$.data.viewerEntitlement.transferLimitBytes")
                .value("4000000"))
            .andExpect(jsonPath("$.data.viewerEntitlement.speedLimitMbps")
                .value(120))
            .andReturn();

        String userId = JsonPath.read(
            entitlementResponse.getResponse().getContentAsString(),
            "$.data.viewerEntitlement.planId"
        );
        assertThat(userId).isEqualTo(planId.toString());

        String subscription = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            String.class,
            email
        );
        TrialRow row = jdbc.queryForObject("""
            SELECT entitlement.is_trial, entitlement.transfer_limit_bytes,
                   entitlement.speed_limit_mbps, entitlement.starts_at,
                   entitlement.expires_at, plan.server_group_id
            FROM subscription_entitlements entitlement
            JOIN service_plans plan ON plan.id = entitlement.plan_id
            WHERE entitlement.user_id = ?::uuid
            """,
            (result, rowNumber) -> new TrialRow(
                result.getBoolean("is_trial"),
                result.getLong("transfer_limit_bytes"),
                result.getInt("speed_limit_mbps"),
                result.getTimestamp("starts_at").toInstant(),
                result.getTimestamp("expires_at").toInstant(),
                result.getLong("server_group_id")
            ),
            subscription
        );
        assertThat(row.trial()).isTrue();
        assertThat(row.transferLimitBytes()).isEqualTo(4_000_000L);
        assertThat(row.speedLimitMbps()).isEqualTo(120);
        assertThat(row.serverGroupId()).isEqualTo(groupId);
        assertThat(row.expiresAt()).isEqualTo(row.startsAt().plusSeconds(10_800));
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE user_id = ?::uuid",
            Integer.class,
            subscription
        )).isZero();
    }

    @Test
    void disabledTrialAndFailedSignupDoNotCreateEntitlements() throws Exception {
        String disabledEmail = uniqueEmail("trial-disabled");
        register(disabledEmail);
        assertEntitlementCount(disabledEmail, 0);

        String failedEmail = uniqueEmail("trial-failed");
        requestRegistrationCode(failedEmail);
        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "registration-trial-password",
                      "displayName": "Failed trial",
                      "emailCode": "000000"
                    }
                    """.formatted(failedEmail)))
            .andExpect(status().isBadRequest());
        assertEntitlementCount(failedEmail, 0);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?",
            Integer.class,
            failedEmail
        )).isZero();
    }

    @Test
    void existingSubscriberIsNotBackfilledWhenTrialIsConfigured() throws Exception {
        long groupId = createGroup();
        UUID paidPlanId = createPlan("SUBSCRIPTION", groupId, 8_000_000L, 80);
        String email = uniqueEmail("existing-subscriber");
        register(email);
        String userId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            String.class,
            email
        );
        Instant startsAt = Instant.now().minusSeconds(3600);
        Instant expiresAt = startsAt.plusSeconds(172_800);
        jdbc.update("""
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, created_at, updated_at
            ) SELECT ?, ?::uuid, plan.id, plan.name, plan.transfer_limit_bytes,
                     0, 0, plan.speed_limit_mbps, plan.reset_policy,
                     ?, ?, ?, ?
              FROM service_plans plan WHERE plan.id = ?
            """,
            UUID.randomUUID(),
            userId,
            Timestamp.from(startsAt),
            Timestamp.from(expiresAt),
            Timestamp.from(startsAt),
            Timestamp.from(startsAt),
            paidPlanId
        );

        UUID trialPlanId = createPlan("SUBSCRIPTION", groupId, 2_000_000L, 40);
        saveSetting("try_out_plan_id", trialPlanId.toString());
        saveSetting("try_out_hour", 3);

        assertThat(configuration.trialPlanId()).contains(trialPlanId);
        assertThat(jdbc.queryForObject(
            "SELECT plan_id FROM subscription_entitlements WHERE user_id = ?::uuid",
            String.class,
            userId
        )).isEqualTo(paidPlanId.toString());
        assertThat(jdbc.queryForObject(
            "SELECT is_trial FROM subscription_entitlements WHERE user_id = ?::uuid",
            Boolean.class,
            userId
        )).isFalse();
    }

    @Test
    void newUserSettingsSaveReadAndDefaultDurationRoundTrip() throws Exception {
        long groupId = createGroup();
        UUID trialPlanId = createPlan("SUBSCRIPTION", groupId, 3_000_000L, 90);
        UUID offerPlanId = createPlan("TRAFFIC_PACKAGE", groupId, 1_000_000L, null);
        jdbc.update("""
            INSERT INTO service_plan_prices (id, plan_id, billing_period,
                amount_minor, currency)
            VALUES (?, ?, 'ONETIME', 500, 'CNY')
            """, UUID.randomUUID(), offerPlanId);

        saveSetting("try_out_plan_id", trialPlanId.toString());
        saveSetting("try_out_hour", 6);
        saveSetting("new_user_offer_plan_id", offerPlanId.toString());

        MvcResult result = mockMvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "new_user")
                .with(administrator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.new_user.try_out_plan_id")
                .value(trialPlanId.toString()))
            .andExpect(jsonPath("$.data.new_user.try_out_hour").value(6))
            .andExpect(jsonPath("$.data.new_user.new_user_offer_plan_id")
                .value(offerPlanId.toString()))
            .andReturn();
        assertThat(result.getResponse().getContentAsString())
            .contains("new_user");
        assertThat(configuration.trialPlanId()).contains(trialPlanId);
        assertThat(configuration.trialHours()).isEqualTo(6);
        assertThat(configuration.newUserOfferPlanId()).contains(offerPlanId);

        jdbc.update("DELETE FROM platform_settings WHERE setting_key LIKE 'new_user.%'");
        assertThat(configuration.trialHours()).isEqualTo(3);
        assertThat(configuration.trialPlanId()).isEmpty();
        assertThat(configuration.newUserOfferPlanId()).isEmpty();
    }

    private Registered register(String email) throws Exception {
        requestRegistrationCode(email);
        MvcResult response = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "registration-trial-password",
                      "displayName": "Registration Trial",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationMail.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return new Registered(JsonPath.read(
            response.getResponse().getContentAsString(),
            "$.accessToken"
        ));
    }

    private void requestRegistrationCode(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isAccepted());
    }

    private long createGroup() {
        return jdbc.queryForObject("""
            INSERT INTO node_access_groups (name, created_at, updated_at)
            VALUES (?, NOW(), NOW()) RETURNING id
            """, Long.class, "Trial group " + UUID.randomUUID());
    }

    private UUID createPlan(
        String planType,
        long groupId,
        long transferLimit,
        Integer speedLimit
    ) {
        UUID id = UUID.randomUUID();
        String resetPolicy = "TRAFFIC_PACKAGE".equals(planType)
            ? "NEVER"
            : "MONTHLY_FROM_ACTIVATION";
        jdbc.update("""
            INSERT INTO service_plans (
                id, name, description, plan_type, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, capacity_limit, resettable,
                purchase_limit_per_user, published, sellable, renewable,
                sort_order, server_group_id, created_at, updated_at
            ) VALUES (?, ?, '', ?, ?, ?, ?, NULL, FALSE, NULL, TRUE, TRUE,
                      ?, 0, ?, NOW(), NOW())
            """,
            id,
            "Registration plan " + id,
            planType,
            transferLimit,
            speedLimit,
            resetPolicy,
            "SUBSCRIPTION".equals(planType),
            groupId
        );
        return id;
    }

    private void saveSetting(String key, Object value) throws Exception {
        String jsonValue = value instanceof Number
            ? value.toString()
            : "\"" + value + "\"";
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "new_user")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"%s\":%s}".formatted(key, jsonValue)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    private void assertEntitlementCount(String email, int expected) {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM subscription_entitlements entitlement
            JOIN users account ON account.id = entitlement.user_id
            WHERE account.email = ?
            """, Integer.class, email)).isEqualTo(expected);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
        administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.test";
    }

    private record Registered(String accessToken) {
    }

    private record TrialRow(
        boolean trial,
        long transferLimitBytes,
        int speedLimitMbps,
        Instant startsAt,
        Instant expiresAt,
        long serverGroupId
    ) {
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
            String code = latestCode.get();
            assertThat(code).matches("\\d{6}");
            return code;
        }
    }
}
