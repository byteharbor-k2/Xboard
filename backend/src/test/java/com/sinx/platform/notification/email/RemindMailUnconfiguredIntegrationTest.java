package com.sinx.platform.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * With the log sink switched off and no SMTP settings stored, the reminder
 * sweep has nowhere to deliver and must not even try: the run completes
 * quietly and the accounts behind it keep their subscriptions.
 *
 * This class runs in its own context because the delivery mode is a startup
 * property - the same split the fulfilment mail tests make.
 */
@SpringBootTest(
    properties = "sinx.mail.delivery=smtp"
)
@AutoConfigureMockMvc
@Import(RemindMailUnconfiguredIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RemindMailUnconfiguredIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_remind_unconfigured_test")
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
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @Autowired
    private RemindMailService remindMailService;

    /** The seam every notification mail leaves through. */
    @MockitoSpyBean
    private ConfiguredNotificationMailSender mailSender;

    @BeforeEach
    void resetFixture() {
        jdbcTemplate.update("DELETE FROM subscription_entitlements");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM service_plans");
        jdbcTemplate.update(
            "DELETE FROM platform_settings WHERE setting_key = 'email.remind_mail_enable'"
        );
    }

    @Test
    void anUnconfiguredMailHostEndsTheRunSilently() throws Exception {
        jdbcTemplate.update(
            """
            INSERT INTO platform_settings (setting_key, setting_value, updated_at)
            VALUES ('email.remind_mail_enable', 'true', now())
            """
        );
        String email = "unconfigured-remind@example.com";
        String userId = register(email);
        UUID planId = seedPlan("Quiet Reminder Plan");
        seedEntitlement(userId, planId, Instant.now().plusSeconds(23 * 3600));

        RemindMailService.ReminderRun run = remindMailService.sendDueReminders();

        // The sweep answered without an error and without a send: delivery
        // was impossible, so nothing was even attempted.
        assertThat(run.expireReminders()).isZero();
        assertThat(run.trafficReminders()).isZero();
        assertThat(run.failures()).isZero();
        verify(mailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    private UUID seedPlan(String name) {
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
                ?::uuid, ?, ?, ?, 200, 'MONTHLY_FROM_ACTIVATION', NULL,
                TRUE, TRUE, TRUE, 1, ?, ?
            )
            """,
            planId.toString(),
            name,
            name + " plan",
            1000L,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return planId;
    }

    private void seedEntitlement(
        String userId,
        UUID planId,
        Instant expiresAt
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                canceled_at, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Quiet Reminder Plan', 1000,
                0, 0, NULL,
                'MONTHLY_FROM_ACTIVATION', ?, ?, NULL,
                NULL, ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            userId,
            planId.toString(),
            Timestamp.from(now.minusSeconds(30 * 24 * 3600)),
            Timestamp.from(expiresAt),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private String register(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isAccepted());
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "unconfigured-remind-password",
                      "displayName": "Quiet Reminder",
                      "deviceLabel": "Quiet Reminder Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.viewer.id"
        );
    }

    /**
     * Registration needs a code the test can read back, so the registration
     * sender is replaced outright - which also keeps this test from tripping
     * over its own unconfigured SMTP.
     */
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

        private final AtomicReference<String> latestCode =
            new AtomicReference<>();

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
