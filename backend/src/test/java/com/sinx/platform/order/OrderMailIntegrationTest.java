package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * A fulfilled order announces itself to the customer, once, in mail the
 * administrator can edit like every other template.
 *
 * The test profile delivers mail to the log sink, so the assertion runs on a
 * spy of the configured sender: exactly one {@code sendHtml} per fulfilment,
 * carrying the plan, the period and the expiry, and never carrying the
 * subscription token - the token is a credential that must not sit in a
 * mailbox. A traffic reset is a maintenance act, not a fulfilment, so it
 * stays silent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderMailIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OrderMailIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_order_mail_test")
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
    void aFulfilledOrderSendsExactlyOneMailWithoutTheSubscriptionToken()
        throws Exception {
        Registered account = register("mail-buyer@example.com");
        UUID planId = seedMonthlyPlan("Mail Plan", 1200);

        String firstTradeNo = placeOrder(
            account.accessToken(),
            planId,
            "MONTHLY"
        );
        settle(firstTradeNo);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender, times(1)).sendHtml(
            org.mockito.Mockito.eq(account.email()),
            subject.capture(),
            body.capture()
        );
        assertThat(subject.getValue()).contains("订阅已开通");
        assertThat(body.getValue())
            .contains("Mail Plan")
            .contains("月付 / Monthly")
            .contains("SinX Cloud")
            .doesNotContain("{{");
        // The token is a credential: it must never travel in a mail, not even
        // in the URL the template points at.
        String token = jdbcTemplate.queryForObject(
            "SELECT subscription_token FROM users WHERE id = ?::uuid",
            String.class,
            account.userId().toString()
        );
        assertThat(token).isNotBlank();
        assertThat(body.getValue())
            .doesNotContain(token)
            .doesNotContain("/sub/");

        // A traffic reset grants nothing new: no mail for it.
        clearInvocations(mailSender);
        String resetTradeNo = placeOrder(
            account.accessToken(),
            planId,
            "RESET_TRAFFIC"
        );
        settle(resetTradeNo);
        verify(mailSender, never()).sendHtml(
            anyString(),
            anyString(),
            anyString()
        );
    }

    private void settle(String tradeNo) throws Exception {
        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    private String placeOrder(
        String accessToken,
        UUID planId,
        String period
    ) throws Exception {
        MvcResult placed = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: %s) { tradeNo status } }"}
                    """.formatted(planId, period)))
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
                speed_limit_mbps, reset_policy, capacity_limit, resettable,
                published, sellable, renewable, sort_order,
                created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?, 200, 'MONTHLY_FROM_ACTIVATION', NULL, TRUE,
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
        jdbcTemplate.update(
            """
            INSERT INTO service_plan_prices (
                id, plan_id, billing_period, amount_minor, currency
            ) VALUES (?::uuid, ?::uuid, 'RESET_TRAFFIC', 500, 'CNY')
            """,
            UUID.randomUUID().toString(),
            planId.toString()
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
                      "password": "order-mail-password",
                      "displayName": "Mail Buyer",
                      "deviceLabel": "Mail Fixture Browser",
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
