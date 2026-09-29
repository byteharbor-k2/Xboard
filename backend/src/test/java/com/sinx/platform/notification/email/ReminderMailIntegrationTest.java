package com.sinx.platform.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The daily reminder sweep judges accounts the way the original panel's
 * {@code send:remindMail} did, one scenario at a time.
 *
 * The sweep is called directly rather than through its cron: the schedule is
 * a property of the job wrapper, while everything worth asserting - the
 * gates, the 24-hour expiry window, the 80% traffic window, the per-account
 * switches - lives in the service. Mail leaves through the one configured
 * sender, so a spy on it counts the sends; the test profile delivers to the
 * log sink, which counts as configured.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReminderMailIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ReminderMailIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_remind_mail_test")
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
        // Tests share one database, so each starts from an empty panel:
        // accounts from an earlier scenario must not be judged again here.
        jdbcTemplate.update("DELETE FROM subscription_entitlements");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM service_plans");
        jdbcTemplate.update(
            "DELETE FROM platform_settings WHERE setting_key = 'email.remind_mail_enable'"
        );
        clearInvocations(mailSender);
    }

    @Test
    void withRemindersDisabledTheRunCompletesAndSendsNothing() throws Exception {
        seedEntitledUser("gate-off@example.com", null, 0, 0);

        RemindMailService.ReminderRun run = remindMailService.sendDueReminders();

        assertThat(run.expireReminders()).isZero();
        assertThat(run.trafficReminders()).isZero();
        assertThat(run.failures()).isZero();
        verify(mailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void anExpiryWithinADaySendsExactlyOneExpiryReminder() throws Exception {
        enableReminders();
        String email = "expiring-soon@example.com";
        seedEntitledUser(email, Instant.now().plusSeconds(23 * 3600), 0, 0);

        RemindMailService.ReminderRun run = remindMailService.sendDueReminders();

        assertThat(run.expireReminders()).isEqualTo(1);
        assertThat(run.trafficReminders()).isZero();
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(1)).sendHtml(
            org.mockito.Mockito.eq(email),
            subject.capture(),
            body.capture()
        );
        // The catalog's default subject carries the site name, and the body
        // is the bundled template with every placeholder substituted.
        assertThat(subject.getValue()).contains("服务即将到期");
        assertThat(body.getValue())
            .contains("24小时")
            .contains("renew in time")
            .doesNotContain("{{");
    }

    @Test
    void anExpiryBeyondTheWindowSendsNothing() throws Exception {
        enableReminders();
        seedEntitledUser("expiring-later@example.com",
            Instant.now().plusSeconds(72 * 3600), 0, 0);

        remindMailService.sendDueReminders();

        verify(mailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void heavyUsageSendsTheTrafficReminderAndLightUsageDoesNot()
        throws Exception {
        enableReminders();
        String heavy = "heavy-usage@example.com";
        // 850 of 1000 bytes: 85%, inside the original's 80%..100% window.
        seedEntitledUser(heavy, null, 500, 350);
        String light = "light-usage@example.com";
        // 699 of 1000: just under the 80% line.
        seedEntitledUser(light, null, 350, 349);

        RemindMailService.ReminderRun run = remindMailService.sendDueReminders();

        assertThat(run.trafficReminders()).isEqualTo(1);
        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(1)).sendHtml(
            recipient.capture(),
            org.mockito.Mockito.anyString(),
            body.capture()
        );
        assertThat(recipient.getValue()).isEqualTo(heavy);
        assertThat(body.getValue())
            .contains("已达到80%")
            .contains("reached 80%")
            .doesNotContain("{{");
    }

    @Test
    void accountsThatOptedOutAreSkipped() throws Exception {
        enableReminders();
        String email = "opted-out@example.com";
        String userId = seedEntitledUser(
            email,
            Instant.now().plusSeconds(23 * 3600),
            500,
            350
        );
        jdbcTemplate.update(
            "UPDATE users SET remind_expire = FALSE, remind_traffic = FALSE "
                + "WHERE id = ?::uuid",
            userId
        );

        RemindMailService.ReminderRun run = remindMailService.sendDueReminders();

        assertThat(run.expireReminders()).isZero();
        assertThat(run.trafficReminders()).isZero();
        verify(mailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void suspendedAccountsAreSkipped() throws Exception {
        enableReminders();
        String email = "suspended@example.com";
        String userId = seedEntitledUser(
            email,
            Instant.now().plusSeconds(23 * 3600),
            500,
            350
        );
        jdbcTemplate.update(
            "UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid",
            userId
        );

        remindMailService.sendDueReminders();

        verify(mailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    private void enableReminders() {
        jdbcTemplate.update(
            """
            INSERT INTO platform_settings (setting_key, setting_value, updated_at)
            VALUES ('email.remind_mail_enable', 'true', now())
            ON CONFLICT (setting_key)
            DO UPDATE SET setting_value = 'true', updated_at = now()
            """
        );
    }

    private UUID seedPlan(String name, long transferLimitBytes) {
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
            transferLimitBytes,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return planId;
    }

    /**
     * Registers an account through the real session flow and grants it one
     * entitlement shaped for the scenario: the expiry instant (null for a
     * non-expiring package) and the usage counters. Returns the user id.
     */
    private String seedEntitledUser(
        String email,
        Instant expiresAt,
        long uploadedBytes,
        long downloadedBytes
    ) throws Exception {
        String userId = register(email);
        UUID planId = seedPlan("Reminder Plan", 1000);
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                canceled_at, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Reminder Plan', 1000,
                ?, ?, NULL,
                'MONTHLY_FROM_ACTIVATION', ?, ?, NULL,
                NULL, ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            userId,
            planId.toString(),
            uploadedBytes,
            downloadedBytes,
            Timestamp.from(now.minusSeconds(30 * 24 * 3600)),
            expiresAt == null ? null : Timestamp.from(expiresAt),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return userId;
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
                      "password": "remind-mail-password",
                      "displayName": "Reminder Fixture",
                      "deviceLabel": "Reminder Fixture Browser",
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
