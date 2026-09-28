package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
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
 * The fulfilment mail is a courtesy, never a step of the fulfilment.
 *
 * With the log sink switched off and no SMTP settings stored, the configured
 * sender refuses to build a message - and the fulfilment must not care. The
 * order still opens, the subscription still exists, and no error reaches the
 * customer or the administrator; the mail silently waits for a configured
 * host. This class runs in its own context because the delivery mode is a
 * startup property.
 */
@SpringBootTest(
    properties = "sinx.mail.delivery=smtp"
)
@AutoConfigureMockMvc
@Import(OrderMailUnconfiguredIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OrderMailUnconfiguredIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_order_mail_unconfigured_test")
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

    /** The seam every notification mail leaves through. */
    @MockitoSpyBean
    private ConfiguredNotificationMailSender mailSender;

    @Test
    void anUnconfiguredMailHostDoesNotKeepTheOrderFromOpening()
        throws Exception {
        Registered account = register("unconfigured-mail@example.com");
        UUID planId = seedMonthlyPlan("Quiet Plan", 1200);

        String tradeNo = placeOrder(account.accessToken(), planId);
        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        // The order was fulfilled for all the purposes that matter.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM orders WHERE trade_no = ?",
            String.class,
            tradeNo
        )).isEqualTo("COMPLETED");
        Long entitlements = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_entitlements WHERE plan_id = ?::uuid",
            Long.class,
            planId.toString()
        );
        assertThat(entitlements).isEqualTo(1L);

        // And the mail machinery, with nothing configured to talk to, sent
        // nothing - the failure was logged away instead of surfacing.
        verify(mailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    private String placeOrder(String accessToken, UUID planId) throws Exception {
        MvcResult placed = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo status } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.placeOrder.status").value("PENDING"))
            .andReturn();
        return JsonPath.read(
            placed.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );
    }

    private UUID seedMonthlyPlan(String name, long amountMinor) {
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
            60L * 1024 * 1024 * 1024,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbcTemplate.update(
            """
            INSERT INTO service_plan_prices (
                id, plan_id, billing_period, amount_minor, currency
            ) VALUES (?::uuid, ?::uuid, 'MONTHLY', ?, 'CNY')
            """,
            UUID.randomUUID().toString(),
            planId.toString(),
            amountMinor
        );
        return planId;
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private Registered register(String email) throws Exception {
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
                      "password": "unconfigured-mail-password",
                      "displayName": "Quiet Buyer",
                      "deviceLabel": "Quiet Fixture Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return new Registered(
            UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(),
                "$.viewer.id"
            )),
            JsonPath.read(
                registration.getResponse().getContentAsString(),
                "$.accessToken"
            ),
            email
        );
    }

    private record Registered(UUID userId, String accessToken, String email) {
    }

    /**
     * Registration needs a code the test can read back, so the registration
     * sender is replaced outright - which is also what keeps this test from
     * tripping over its own unconfigured SMTP: registration never touches
     * the real sender here, the fulfilment mail does.
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
