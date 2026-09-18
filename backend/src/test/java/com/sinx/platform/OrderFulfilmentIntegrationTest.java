package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole life of an order against a real database: priced and placed over
 * GraphQL, opened by an administrator, and provisioned into a subscription.
 *
 * The unit tests cover each branch in isolation; this one is here to prove the
 * wiring holds together - that a placed order is a row, that opening it writes
 * an entitlement, and that opening it twice does not write a second one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderFulfilmentIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class OrderFulfilmentIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_order_test")
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

    @Test
    void opensAPlacedOrderIntoASubscriptionAndRefusesToOpenItTwice()
        throws Exception {
        String accessToken = register("order-buyer@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);

        MvcResult placed = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo status orderType totalAmount createdAt paidAt } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.placeOrder.status").value("PENDING"))
            .andExpect(jsonPath("$.data.placeOrder.orderType")
                .value("NEW_PURCHASE"))
            .andExpect(jsonPath("$.data.placeOrder.totalAmount").value("1200"))
            .andExpect(jsonPath("$.data.placeOrder.paidAt").doesNotExist())
            .andReturn();
        String tradeNo = JsonPath.read(
            placed.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );

        // Placing an order hands over nothing: the subscription is the
        // settlement's to grant.
        assertThat(entitlementCount(planId)).isZero();

        // What the administrator sees before settling: the original panel's
        // field names and epoch-second timestamps, with the order's user and
        // plan resolved from their lazy associations.
        MvcResult listing = mockMvc.perform(get("/api/v2/admin/order/fetch")
                .with(administrator())
                .param("status", "PENDING"))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> listed = listedOrder(listing, tradeNo);
        assertThat(listed)
            .containsEntry("email", "order-buyer@example.com")
            .containsEntry("plan_name", "Starter")
            .containsEntry("period", "MONTHLY")
            .containsEntry("order_type", "NEW_PURCHASE")
            .containsEntry("status", "PENDING")
            .containsEntry("currency", "CNY")
            .containsEntry("total_amount", 1200);
        assertThat(listed.get("created_at")).isInstanceOf(Number.class);
        // Nothing has settled it, so it carries no settlement at all.
        assertThat(listed.get("paid_at")).isNull();
        assertThat(listed.get("callback_no")).isNull();

        mockMvc.perform(post("/api/v2/admin/order/paid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        Map<String, Object> order = orderRow(tradeNo);
        assertThat(order.get("status")).isEqualTo("COMPLETED");
        assertThat(order.get("callback_no")).isEqualTo("manual_operation");
        assertThat(order.get("paid_at")).isNotNull();

        Map<String, Object> entitlement = entitlementRow(planId);
        assertThat(entitlement.get("plan_name")).isEqualTo("Starter");
        assertThat(((Number) entitlement.get("transfer_limit_bytes")).longValue())
            .isEqualTo(60L * 1024 * 1024 * 1024);
        assertThat(((Number) entitlement.get("uploaded_bytes")).longValue())
            .isZero();
        assertThat(((Number) entitlement.get("downloaded_bytes")).longValue())
            .isZero();
        // One month of coverage starting now, not at some instant in the past.
        assertThat(((Timestamp) entitlement.get("expires_at")).toInstant())
            .isBetween(
                Instant.now().plus(Duration.ofDays(28)),
                Instant.now().plus(Duration.ofDays(31))
            );

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerOrders { tradeNo status } viewerEntitlement { planName state } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerOrders[0].status")
                .value("COMPLETED"))
            .andExpect(jsonPath("$.data.viewerEntitlement.planName")
                .value("Starter"))
            .andExpect(jsonPath("$.data.viewerEntitlement.state")
                .value("ACTIVE"));

        // A second settlement is a conflict, not a second subscription.
        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_PENDING"));

        assertThat(entitlementCount(planId)).isEqualTo(1);
    }

    @Test
    void callsOffAnOrderNobodyPaidForSoTheAccountCanBuyAgain() throws Exception {
        String accessToken = register("order-abandoner@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);

        MvcResult first = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo status } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.placeOrder.status").value("PENDING"))
            .andReturn();
        String tradeNo = JsonPath.read(
            first.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );

        // The abandoned order holds the account's only slot until it is called
        // off, which is the whole reason an administrator can cancel it.
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.placeOrder").doesNotExist())
            .andExpect(jsonPath("$.errors[0].extensions.code")
                .value("ORDER_ALREADY_OPEN"));

        mockMvc.perform(post("/api/v2/admin/order/cancel")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        assertThat(orderRow(tradeNo).get("status")).isEqualTo("CANCELLED");
        assertThat(entitlementCount(planId)).isZero();

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { status } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.placeOrder.status").value("PENDING"));
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
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
                      "email":"%s",
                      "password":"order-buyer-password",
                      "displayName":"Order Buyer",
                      "deviceLabel":"Order Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(
                        email,
                        registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.accessToken"
        );
    }

    private UUID seedMonthlyPlan(String name, long amountMinor) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes,
                speed_limit_mbps, reset_policy,
                capacity_limit, published, sellable, renewable,
                sort_order, created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?, ?, ?, ?, TRUE, TRUE, TRUE,
                1, ?, ?
            )
            """,
            planId.toString(),
            name,
            name + " plan",
            60L * 1024 * 1024 * 1024,
            200,
            "MONTHLY_FROM_ACTIVATION",
            null,
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

    /**
     * Picks one order out of an admin listing. The test class shares its
     * database across methods, so the listing is matched on the trade number
     * rather than on position.
     */
    private Map<String, Object> listedOrder(MvcResult listing, String tradeNo)
        throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(
            listing.getResponse().getContentAsString(),
            "$.data"
        );
        return rows.stream()
            .filter((row) -> tradeNo.equals(row.get("trade_no")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No order " + tradeNo + " in " + rows
            ));
    }

    private Map<String, Object> orderRow(String tradeNo) {
        return jdbcTemplate.queryForMap(
            """
            SELECT status, callback_no, paid_at
            FROM orders WHERE trade_no = ?
            """,
            tradeNo
        );
    }

    private Map<String, Object> entitlementRow(UUID planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT plan_name, transfer_limit_bytes, uploaded_bytes,
                   downloaded_bytes, expires_at
            FROM subscription_entitlements WHERE plan_id = ?::uuid
            """,
            planId.toString()
        );
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private long entitlementCount(UUID planId) {
        Long count = jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM subscription_entitlements WHERE plan_id = ?::uuid
            """,
            Long.class,
            planId.toString()
        );
        return count == null ? 0 : count;
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
