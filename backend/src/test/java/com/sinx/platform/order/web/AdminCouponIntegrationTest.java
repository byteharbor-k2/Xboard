package com.sinx.platform.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.order.application.OrderService;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Coupon administration against a real database, and the one move the admin
 * panel makes across accounts: handing an order to a different customer.
 *
 * A coupon misdefined here is a customer refused at the checkout, so each
 * validation rule is driven over the same HTTP surface the page uses. The
 * quote at the end proves a coupon that passed all of it actually discounts a
 * priced order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminCouponIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminCouponIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_coupon_test")
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
    private OrderService orders;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @Test
    void createsACouponAndReportsItInTheAdminShape() throws Exception {
        UUID planId = seedMonthlyPlan("Couponable", 1200);
        Instant now = Instant.now();

        mockMvc.perform(post("/api/v2/admin/coupon/create")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "WELCOME50",
                      "name": "欢迎五折",
                      "discount_type": "PERCENTAGE",
                      "discount_value": 50,
                      "starts_at": %d,
                      "ends_at": %d,
                      "max_redemptions": 100,
                      "max_redemptions_per_user": 1,
                      "limited_plan_ids": ["%s"],
                      "limited_periods": ["MONTHLY"],
                      "enabled": true
                    }
                    """.formatted(
                        now.minusSeconds(60).getEpochSecond(),
                        now.plusSeconds(3600).getEpochSecond(),
                        planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.code").value("WELCOME50"))
            .andExpect(jsonPath("$.data.name").value("欢迎五折"))
            .andExpect(jsonPath("$.data.discount_type").value("PERCENTAGE"))
            .andExpect(jsonPath("$.data.discount_value").value(50))
            .andExpect(jsonPath("$.data.max_redemptions").value(100))
            .andExpect(jsonPath("$.data.redemptions_used").value(0))
            .andExpect(jsonPath("$.data.max_redemptions_per_user").value(1))
            .andExpect(jsonPath("$.data.limited_plan_ids[0]").value(planId.toString()))
            .andExpect(jsonPath("$.data.limited_periods[0]").value("MONTHLY"))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.created_at").isNumber());

        Map<String, Object> row = couponRowByCode("WELCOME50");
        assertThat(row.get("discount_type")).isEqualTo("PERCENTAGE");
        assertThat(row.get("limited_plan_ids")).isEqualTo("[\"%s\"]".formatted(planId));
    }

    @Test
    void refusesSecondCouponUsingTheSameCodeWhateverItsCase() throws Exception {
        createPercentageCoupon("TAKETEN", 10);

        mockMvc.perform(post("/api/v2/admin/coupon/create")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"takeTEN","name":"Case duplicate","discount_type":"PERCENTAGE","discount_value":10}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COUPON_CODE_TAKEN"));
    }

    @Test
    void refusesOutOfBoundsPercentages() throws Exception {
        for (long value : new long[] {0, 101}) {
            mockMvc.perform(post("/api/v2/admin/coupon/create")
                    .with(administrator())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"code":"BOUND%s","name":"Bound","discount_type":"PERCENTAGE","discount_value":%d}
                        """.formatted(value, value)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPON_DEFINITION_INVALID"));
        }
    }

    @Test
    void refusesAWindowThatEndsBeforeItStarts() throws Exception {
        Instant now = Instant.now();

        mockMvc.perform(post("/api/v2/admin/coupon/create")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"BACKWARDS",
                      "name":"Backwards window",
                      "discount_type":"FIXED_AMOUNT",
                      "discount_value":500,
                      "starts_at":%d,
                      "ends_at":%d
                    }
                    """.formatted(
                        now.plusSeconds(3600).getEpochSecond(),
                        now.getEpochSecond())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COUPON_DEFINITION_INVALID"));
    }

    @Test
    void refusesUnknownPlansAndUnknownPeriods() throws Exception {
        mockMvc.perform(post("/api/v2/admin/coupon/create")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"GHOSTPLAN","name":"Unknown plan","discount_type":"PERCENTAGE","discount_value":5,"limited_plan_ids":["%s"]}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COUPON_DEFINITION_INVALID"));

        mockMvc.perform(post("/api/v2/admin/coupon/create")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"code":"WEEKLY","name":"Unknown period","discount_type":"PERCENTAGE","discount_value":5,"limited_periods":["WEEKLY"]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COUPON_DEFINITION_INVALID"));
    }

    @Test
    void refusesCappingACouponBelowWhatItAlreadyRedeemed() throws Exception {
        UUID couponId = createPercentageCoupon("HALFUSED", 50);
        jdbcTemplate.update(
            "UPDATE coupons SET redemptions_used = 5 WHERE id = ?::uuid",
            couponId.toString()
        );

        mockMvc.perform(post("/api/v2/admin/coupon/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "%s",
                      "code": "HALFUSED",
                      "name": "Half used",
                      "discount_type": "PERCENTAGE",
                      "discount_value": 50,
                      "max_redemptions": 4
                    }
                    """.formatted(couponId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COUPON_LIMIT_CONFLICT"));

        // Raising or clearing the cap stays possible, and the used count is
        // nobody's to edit.
        mockMvc.perform(post("/api/v2/admin/coupon/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "%s",
                      "code": "HALFUSED",
                      "name": "Half used",
                      "discount_type": "PERCENTAGE",
                      "discount_value": 50,
                      "max_redemptions": null
                    }
                    """.formatted(couponId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true));

        assertThat(couponRow(couponId).get("redemptions_used")).isEqualTo(5);
    }

    @Test
    void listsCouponsNewestFirst() throws Exception {
        UUID older = createPercentageCoupon("OLDER", 20);
        UUID newer = createPercentageCoupon("NEWER", 30);
        jdbcTemplate.update(
            "UPDATE coupons SET created_at = ? WHERE id = ?::uuid",
            Timestamp.from(Instant.now().minusSeconds(60)),
            older.toString()
        );

        MvcResult listing = mockMvc.perform(get("/api/v2/admin/coupon/list")
                .with(administrator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andReturn();
        List<String> ids = JsonPath.read(
            listing.getResponse().getContentAsString(),
            "$.data[*].id"
        );
        // Every earlier test's coupons are still in the shared database, so
        // the order is asserted between the two created here, not by listing
        // position alone.
        assertThat(ids.indexOf(newer.toString()))
            .isLessThan(ids.indexOf(older.toString()));
    }

    @Test
    void switchesACouponOnAndOff() throws Exception {
        UUID couponId = createPercentageCoupon("TOGGLE", 30);

        mockMvc.perform(post("/api/v2/admin/coupon/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "%s",
                      "code": "TOGGLE",
                      "name": "Toggled",
                      "discount_type": "PERCENTAGE",
                      "discount_value": 30,
                      "enabled": false
                    }
                    """.formatted(couponId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.name").value("Toggled"));

        assertThat(couponRow(couponId).get("enabled")).isEqualTo(false);

        mockMvc.perform(post("/api/v2/admin/coupon/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "%s",
                      "code": "TOGGLE",
                      "name": "Toggled",
                      "discount_type": "PERCENTAGE",
                      "discount_value": 30,
                      "enabled": true
                    }
                    """.formatted(couponId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void deletesACouponSoItNoLongerLists() throws Exception {
        UUID couponId = createPercentageCoupon("EPHEMERAL", 100);

        mockMvc.perform(post("/api/v2/admin/coupon/delete")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s"}
                    """.formatted(couponId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM coupons WHERE id = ?::uuid",
            couponId.toString()
        );
        assertThat(rows).isEmpty();

        mockMvc.perform(post("/api/v2/admin/coupon/delete")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s"}
                    """.formatted(couponId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"));
    }

    /** The reason the coupons exist at all: the checkout price moves. */
    @Test
    void aCreatedCouponDiscountsARealQuote() throws Exception {
        String accessToken = register("coupon-shopper@example.com");
        UUID userId = userIdOf("coupon-shopper@example.com");
        UUID planId = seedMonthlyPlan("Couponable plan", 1200);
        createPercentageCoupon("HELFPOFF", 50);

        var quote = orders.quote(
            userId,
            planId,
            BillingPeriod.MONTHLY,
            "HELFPOFF"
        );

        assertThat(quote.breakdown().originalAmount()).isEqualTo(1200);
        assertThat(quote.breakdown().discountAmount()).isEqualTo(600);
        assertThat(quote.breakdown().totalAmount()).isEqualTo(600);
        assertThat(quote.couponCode()).isEqualTo("HELFPOFF");
    }

    @Test
    void handsAnOrderAndItsSubscriptionToAnotherAccount() throws Exception {
        String ownerToken = register("coupon-owner@example.com");
        String targetToken = register("coupon-receiver@example.com");
        UUID targetId = userIdOf("coupon-receiver@example.com");
        UUID planId = seedMonthlyPlan("Handover plan", 1200);

        MvcResult placed = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andReturn();
        String tradeNo = JsonPath.read(
            placed.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );

        // Opening the order grants the subscription to the old account; the
        // assignment below has to carry it across with the order.
        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v2/admin/order/assign")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s","user_id":"%s"}
                    """.formatted(tradeNo, targetId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        assertThat(jdbcTemplate.queryForMap(
            "SELECT user_id FROM orders WHERE trade_no = ?",
            tradeNo
        ).get("user_id").toString()).isEqualTo(targetId.toString());

        Map<String, Object> entitlement = jdbcTemplate.queryForMap(
            "SELECT user_id FROM subscription_entitlements WHERE plan_id = ?::uuid",
            planId.toString()
        );
        assertThat(entitlement.get("user_id").toString())
            .isEqualTo(targetId.toString());

        // The receiving customer now sees both the order history and the
        // subscription on his own account.
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + targetToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerOrders { tradeNo } viewerEntitlement { planName state } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerOrders[0].tradeNo").value(tradeNo))
            .andExpect(jsonPath("$.data.viewerEntitlement.planName")
                .value("Handover plan"))
            .andExpect(jsonPath("$.data.viewerEntitlement.state").value("ACTIVE"));

        // Repeating the call changes nothing the second time.
        mockMvc.perform(post("/api/v2/admin/order/assign")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s","user_id":"%s"}
                    """.formatted(tradeNo, targetId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(post("/api/v2/admin/order/assign")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"SX-UNKNOWN","user_id":"%s"}
                    """.formatted(targetId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void refusesToHandAnOrderToASuspendedAccount() throws Exception {
        String ownerToken = register("coupon-stuck@example.com");
        UUID planId = seedMonthlyPlan("No transfer", 1200);

        MvcResult placed = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andReturn();
        String tradeNo = JsonPath.read(
            placed.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );

        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"coupon-suspended@example.com"}
                    """))
            .andExpect(status().isAccepted());
        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"coupon-suspended@example.com",
                      "password":"coupon-suspended-password",
                      "displayName":"Suspended",
                      "deviceLabel":"Blocked Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated());
        jdbcTemplate.update(
            "UPDATE users SET status = 'SUSPENDED' WHERE email = ?",
            "coupon-suspended@example.com"
        );
        UUID suspendedId = userIdOf("coupon-suspended@example.com");

        mockMvc.perform(post("/api/v2/admin/order/assign")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s","user_id":"%s"}
                    """.formatted(tradeNo, suspendedId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TARGET_ACCOUNT_SUSPENDED"));

        // The order stayed where it was.
        Map<String, Object> order = jdbcTemplate.queryForMap(
            "SELECT user_id FROM orders WHERE trade_no = ?",
            tradeNo
        );
        assertThat(order.get("user_id").toString())
            .isEqualTo(userIdOf("coupon-stuck@example.com").toString());
    }

    private UUID createPercentageCoupon(String code, long percent)
        throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/admin/coupon/create")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code": "%s",
                      "name": "Coupon %s",
                      "discount_type": "PERCENTAGE",
                      "discount_value": %d
                    }
                    """.formatted(code, code, percent)))
            .andExpect(status().isOk())
            .andReturn();
        String id = JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.id"
        );
        return UUID.fromString(id);
    }

    private UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            UUID.class,
            email
        );
    }

    private Map<String, Object> couponRow(UUID couponId) {
        return jdbcTemplate.queryForMap(
            """
            SELECT discount_type, limited_plan_ids, limited_periods,
                   enabled, redemptions_used
            FROM coupons WHERE id = ?::uuid
            """,
            couponId.toString()
        );
    }

    private Map<String, Object> couponRowByCode(String code) {
        return jdbcTemplate.queryForMap(
            """
            SELECT discount_type, limited_plan_ids, limited_periods,
                   enabled, redemptions_used
            FROM coupons WHERE code = ?
            """,
            code
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
                      "password":"coupon-buyer-password",
                      "displayName":"Coupon Buyer",
                      "deviceLabel":"Coupon Browser",
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
