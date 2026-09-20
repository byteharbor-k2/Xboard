package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Paying for an order against a real database, end to end.
 *
 * An administrator configures an Epay method, a customer places an order and is
 * sent to the gateway, and the gateway's callback opens it. The callback is the
 * interesting part: it is anonymous, it is the only thing that can move money
 * into a subscription, and it has to be refused when it is not exactly right.
 *
 * Nothing here talks to a real Epay - the callback is built the way Epay builds
 * one, signed with the key the method was configured with. That proves the
 * protocol handling, not the merchant account; the live gateway can only be
 * exercised from the deployment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentCheckoutIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PaymentCheckoutIntegrationTest {

    private static final String KEY = "integration-communication-key";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_payment_test")
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
    private com.sinx.platform.payment.config.PaymentProperties paymentProperties;

    @Test
    void sendsACustomerToTheGatewayAndOpensTheOrderWhenItConfirmsPayment()
        throws Exception {
        String accessToken = register("payment-buyer@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);
        Method epay = configureEpay("微信支付", 100L, "2.5");

        String tradeNo = placeOrder(accessToken, planId);

        // Nothing is chargeable until a method is chosen, and the fee is priced
        // by the server for this particular order.
        mockMvc.perform(graphQl(accessToken, """
                {"query":"{ paymentOptions(tradeNo: \\"%s\\") { id name handlingFee payableAmount currency } }"}
                """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentOptions[0].name")
                .value("微信支付"))
            .andExpect(jsonPath("$.data.paymentOptions[0].handlingFee")
                .value("130"))
            .andExpect(jsonPath("$.data.paymentOptions[0].payableAmount")
                .value("1330"))
            .andExpect(jsonPath("$.data.paymentOptions[0].currency")
                .value("CNY"));

        MvcResult checkout = mockMvc.perform(graphQl(accessToken, """
                {"query":"mutation { checkoutOrder(tradeNo: \\"%s\\", paymentMethodId: \\"%s\\") { type data } }"}
                """.formatted(tradeNo, epay.id())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.checkoutOrder.type").value(1))
            .andReturn();
        String redirect = JsonPath.read(
            checkout.getResponse().getContentAsString(),
            "$.data.checkoutOrder.data"
        );
        assertThat(redirect).startsWith("https://pay.example.com/submit.php?");

        Map<String, String> sent = queryOf(redirect);
        assertThat(sent)
            .containsEntry("pid", "1000")
            .containsEntry("out_trade_no", tradeNo)
            .containsEntry("name", tradeNo)
            // 1330 minor units, printed the way PHP prints 1330/100.
            .containsEntry("money", "13.3")
            .containsEntry("type", "alipay")
            .containsEntry("notify_url", epay.notifyUrl())
            .containsEntry("return_url", epay.returnUrl(tradeNo))
            .containsEntry("sign_type", "MD5");

        // The order now records how it is being paid for, and what was added to
        // its own total to do so.
        Map<String, Object> order = orderRow(tradeNo);
        assertThat(((Number) order.get("handling_amount")).longValue())
            .isEqualTo(130);
        assertThat(order.get("gateway")).isEqualTo("EPay");
        assertThat(order.get("status")).isEqualTo("PENDING");

        // The callback arrives from the gateway, unauthenticated, carrying a
        // signature over the parameters it was given. Epay prints 1330 minor
        // units as 13.30 on its way back, where it printed 13.3 on the way out.
        Map<String, String> callback = callbackFor(epay, tradeNo, "13.30");

        notifyTheGateway(epay, callback)
            .andExpect(status().isOk())
            .andExpect(content().string("success"));

        order = orderRow(tradeNo);
        assertThat(order.get("status")).isEqualTo("COMPLETED");
        assertThat(order.get("callback_no")).isEqualTo("2026091722009876543210");
        assertThat(order.get("paid_at")).isNotNull();
        assertThat(entitlementCount(planId)).isEqualTo(1);

        // A gateway that sends the same confirmation twice must not be given a
        // second subscription for it.
        notifyTheGateway(epay, callback)
            .andExpect(status().isOk())
            .andExpect(content().string("success"));
        assertThat(entitlementCount(planId)).isEqualTo(1);
    }

    @Test
    void refusesCallbacksThatAreNotExactlyRight() throws Exception {
        String accessToken = register("payment-sceptic@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);
        Method epay = configureEpay("支付宝", null, null);

        String tradeNo = placeOrder(accessToken, planId);
        checkout(accessToken, tradeNo, epay.id());

        // The order costs 12.00 and the method adds nothing.
        Map<String, String> callback = callbackFor(epay, tradeNo, "12.00");

        // Unsigned.
        Map<String, String> unsigned = new LinkedHashMap<>(callback);
        unsigned.remove("sign");
        notifyTheGateway(epay, unsigned)
            .andExpect(status().isUnprocessableEntity());

        // Signed, but with a signature that does not match.
        Map<String, String> forged = new LinkedHashMap<>(callback);
        forged.put("sign", "0".repeat(32));
        notifyTheGateway(epay, forged)
            .andExpect(status().isUnprocessableEntity());

        // Signed correctly, but for a different merchant account.
        Map<String, String> otherMerchant = new LinkedHashMap<>(callback);
        otherMerchant.put("pid", "9999");
        resign(otherMerchant);
        notifyTheGateway(epay, otherMerchant)
            .andExpect(status().isUnprocessableEntity());

        // Signed, but reporting a trade that failed.
        Map<String, String> failed = new LinkedHashMap<>(callback);
        failed.put("trade_status", "TRADE_CLOSED");
        resign(failed);
        notifyTheGateway(epay, failed)
            .andExpect(status().isUnprocessableEntity());

        // Genuinely signed by the gateway, and genuinely for less than the order
        // costs. The original would open this order; it must not be opened here.
        notifyTheGateway(epay, callbackFor(epay, tradeNo, "1.00"))
            .andExpect(status().isUnprocessableEntity());

        assertThat(orderRow(tradeNo).get("status")).isEqualTo("PENDING");
        assertThat(entitlementCount(planId)).isZero();
    }

    @Test
    void refusesToTakeMoneyThroughAMethodThatWasSwitchedOff() throws Exception {
        String accessToken = register("payment-lapsed@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);
        Method epay = configureEpay("微信支付", null, null);

        String tradeNo = placeOrder(accessToken, planId);
        checkout(accessToken, tradeNo, epay.id());

        // An administrator switching a method off is saying no more money should
        // be taken through it - including the payment already in flight.
        mockMvc.perform(post("/api/v2/admin/payment/show")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s"}
                    """.formatted(epay.id())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(false));

        notifyTheGateway(epay, callbackFor(epay, tradeNo, "12.00"))
            .andExpect(status().isNotFound());
        assertThat(entitlementCount(planId)).isZero();
    }

    /**
     * A coupon that covers the whole order leaves nothing to collect, so the
     * order is opened without a gateway rather than parked pending with an
     * empty payment list. Nobody can reach this on their own: only an
     * administrator creates coupons, so a 100%-off one is a deliberate gift.
     */
    @Test
    void opensAnOrderACouponCoveredInFull() throws Exception {
        String accessToken = register("payment-freebie@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);

        // No payment method is configured: a fully discounted order needs none,
        // and adding one here would shift the method list other tests index
        // into.
        UUID couponId = seedFullDiscountCoupon("ALL");
        String tradeNo = placeOrder(accessToken, planId, "ALL", "COMPLETED");

        // Opened and granted, with nothing charged.
        assertThat(entitlementCount(planId)).isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT status, callback_no, total_amount, discount_amount FROM orders WHERE trade_no = ?",
            tradeNo
        );
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("callback_no")).isEqualTo("auto_settled");
        assertThat(((Number) row.get("total_amount")).longValue()).isZero();
        assertThat(((Number) row.get("discount_amount")).longValue())
            .isPositive();

        // And there is still nothing to pay, so no methods are offered.
        mockMvc.perform(graphQl(accessToken, """
                {"query":"{ paymentOptions(tradeNo: \\"%s\\") { id } }"}
                """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentOptions").isEmpty());

        // A settled order cannot then be checked out against a gateway. The
        // method is never consulted, so a freshly minted id is fine.
        mockMvc.perform(graphQl(accessToken, """
                {"query":"mutation { checkoutOrder(tradeNo: \\"%s\\", paymentMethodId: \\"%s\\") { type data } }"}
                """.formatted(tradeNo, UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.checkoutOrder").doesNotExist())
            .andExpect(jsonPath("$.errors[0].extensions.code")
                .value("ORDER_NOT_PENDING"));

        assertThat(couponId).isNotNull();
    }

    /**
     * The admin form is rendered from what the gateway says it needs, so the
     * descriptor has to arrive complete and - when a method is being edited -
     * with the stored credentials already in it. The merchant key is the reason
     * this matters: losing it on the way to the form means an administrator
     * silently re-saves a method that can no longer sign anything.
     */
    @Test
    void describesAGatewaysFieldsAndFillsInWhatIsAlreadyStored()
        throws Exception {
        Method epay = configureEpay("微信支付", null, null);

        mockMvc.perform(post("/api/v2/admin/payment/getPaymentForm")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"payment":"EPay","id":"%s"}
                    """.formatted(epay.id())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.url.required").value(true))
            .andExpect(jsonPath("$.data.url.value")
                .value("https://pay.example.com"))
            .andExpect(jsonPath("$.data.url.label['zh-CN']").isNotEmpty())
            .andExpect(jsonPath("$.data.url.label['en-US']").isNotEmpty())
            .andExpect(jsonPath("$.data.key.secret").value(true))
            .andExpect(jsonPath("$.data.key.value").value(KEY))
            .andExpect(jsonPath("$.data.pid.value").value("1000"))
            .andExpect(jsonPath("$.data.type.value").value("alipay"));

        // A method that does not exist yet has nothing to fill in.
        mockMvc.perform(post("/api/v2/admin/payment/getPaymentForm")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"payment":"EPay"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.key.value").value(""))
            .andExpect(jsonPath("$.data.url.required").value(true));
    }

    /** Registers an Epay method through the admin API and returns what it made. */
    private Method configureEpay(
        String name,
        Long handlingFeeFixed,
        String handlingFeePercent
    ) throws Exception {
        MvcResult saved = mockMvc.perform(post("/api/v2/admin/payment/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "payment":"EPay",
                      "name":"%s",
                      "icon":"💳",
                      "handling_fee_fixed":%s,
                      "handling_fee_percent":%s,
                      "config":{
                        "url":"https://pay.example.com",
                        "pid":"1000",
                        "key":"%s",
                        "type":"alipay"
                      }
                    }
                    """.formatted(
                        name,
                        handlingFeeFixed == null ? "null" : handlingFeeFixed,
                        handlingFeePercent == null ? "null" : handlingFeePercent,
                        KEY)))
            .andExpect(status().isOk())
            .andReturn();
        String id = JsonPath.read(
            saved.getResponse().getContentAsString(),
            "$.data"
        );

        MvcResult fetch = mockMvc.perform(get("/api/v2/admin/payment/fetch")
                .with(administrator()))
            .andExpect(status().isOk())
            .andReturn();
        Map<String, Object> row = listedMethod(fetch, id);

        // Configured but switched off: enabling is a separate, deliberate act.
        assertThat(row.get("enable")).isEqualTo(false);
        mockMvc.perform(post("/api/v2/admin/payment/show")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s"}
                    """.formatted(id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        return new Method(
            UUID.fromString(id),
            (String) row.get("uuid"),
            (String) row.get("payment"),
            (String) row.get("notify_url"),
            paymentProperties.publicBaseUrl()
        );
    }

    private Map<String, Object> listedMethod(MvcResult fetch, String id)
        throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(
            fetch.getResponse().getContentAsString(),
            "$.data"
        );
        return rows.stream()
            .filter((row) -> id.equals(row.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No method " + id + " in " + rows));
    }

    /**
     * Delivers a callback the way Epay would: a GET to the notify URL, with the
     * parameters signed using the merchant key.
     */
    private ResultActions notifyTheGateway(
        Method epay,
        Map<String, String> params
    ) throws Exception {
        var request = get(
            "/api/v1/guest/payment/notify/{gateway}/{uuid}",
            epay.gateway().toLowerCase(),
            epay.uuid()
        );
        params.forEach(request::param);
        return mockMvc.perform(request);
    }

    private Map<String, String> callbackFor(
        Method epay,
        String tradeNo,
        String money
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1000");
        params.put("trade_no", "2026091722009876543210");
        params.put("out_trade_no", tradeNo);
        params.put("type", "alipay");
        params.put("name", tradeNo);
        params.put("money", money);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("notify_url", epay.notifyUrl());
        params.put("return_url", epay.returnUrl(tradeNo));
        resign(params);
        return params;
    }

    /** Signs a callback the way a gateway holding the merchant key would. */
    private void resign(Map<String, String> params) {
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove("sign");
        signed.remove("sign_type");
        StringBuilder joined = new StringBuilder();
        signed.forEach((key, value) -> {
            if (joined.length() > 0) {
                joined.append('&');
            }
            joined.append(key).append('=').append(value.replace("\\", ""));
        });
        params.put("sign", md5(joined + KEY));
        params.put("sign_type", "MD5");
    }

    private String md5(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String checkout(String accessToken, String tradeNo, UUID methodId)
        throws Exception {
        MvcResult result = mockMvc.perform(graphQl(accessToken, """
                {"query":"mutation { checkoutOrder(tradeNo: \\"%s\\", paymentMethodId: \\"%s\\") { type data } }"}
                """.formatted(tradeNo, methodId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.checkoutOrder.data"
        );
    }

    private String placeOrder(String accessToken, UUID planId)
        throws Exception {
        return placeOrder(accessToken, planId, null);
    }

    private String placeOrder(
        String accessToken,
        UUID planId,
        String couponCode
    ) throws Exception {
        return placeOrder(accessToken, planId, couponCode, "PENDING");
    }

    private String placeOrder(
        String accessToken,
        UUID planId,
        String couponCode,
        String expectedStatus
    ) throws Exception {
        String coupon = couponCode == null
            ? ""
            : ", couponCode: \\\"" + couponCode + "\\\"";
        MvcResult placed = mockMvc.perform(graphQl(accessToken, """
                {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY%s) { tradeNo status } }"}
                """.formatted(planId, coupon)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.placeOrder.status").value(expectedStatus))
            .andReturn();
        return JsonPath.read(
            placed.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );
    }

    private MockHttpServletRequestBuilder graphQl(
        String accessToken,
        String body
    ) {
        return post("/gateway")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private Map<String, String> queryOf(String url) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int separator = pair.indexOf('=');
            query.put(
                URLDecoder.decode(
                    pair.substring(0, separator),
                    StandardCharsets.UTF_8
                ),
                URLDecoder.decode(
                    pair.substring(separator + 1),
                    StandardCharsets.UTF_8
                )
            );
        }
        return query;
    }

    /**
     * An order the customer's own balance covered in full is opened without
     * touching a gateway. The balance is money already received, so nothing is
     * left to collect; leaving such an order pending stranded it, because the
     * gateway list is empty for a zero total by design.
     */
    @Test
    void opensAnOrderTheBalanceCoveredInFull() throws Exception {
        String accessToken = register("payment-balance@example.com");
        UUID planId = seedMonthlyPlan("Starter", 1200);
        creditBalance("payment-balance@example.com", 5_000);

        // No payment method is configured: the balance covers the order, so
        // none is needed and none is offered.
        String tradeNo = placeOrder(accessToken, planId, null, "COMPLETED");

        assertThat(entitlementCount(planId)).isEqualTo(1);
        assertThat(balanceMinor("payment-balance@example.com"))
            .isEqualTo(3_800);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT status, callback_no FROM orders WHERE trade_no = ?",
            tradeNo
        );
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("callback_no")).isEqualTo("auto_settled");

        mockMvc.perform(graphQl(accessToken, """
                {"query":"{ paymentOptions(tradeNo: \\"%s\\") { id } }"}
                """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentOptions").isEmpty());
    }

    private void creditBalance(String email, long amountMinor) {
        jdbcTemplate.update(
            "UPDATE users SET balance_minor = ? WHERE email = ?",
            amountMinor,
            email
        );
    }

    private long balanceMinor(String email) {
        Long balance = jdbcTemplate.queryForObject(
            "SELECT balance_minor FROM users WHERE email = ?",
            Long.class,
            email
        );
        return balance == null ? 0 : balance;
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
                      "password":"payment-buyer-password",
                      "displayName":"Payment Buyer",
                      "deviceLabel":"Payment Browser",
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

    /** A coupon that takes the whole price off, however much it is. */
    private UUID seedFullDiscountCoupon(String code) {
        UUID couponId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO coupons (
                id, code, name, discount_type, discount_value,
                starts_at, ends_at, enabled, created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, 'PERCENTAGE', 100,
                ?, ?, TRUE, ?, ?
            )
            """,
            couponId.toString(),
            code,
            "All off",
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return couponId;
    }

    private Map<String, Object> orderRow(String tradeNo) {
        return jdbcTemplate.queryForMap(
            """
            SELECT status, callback_no, paid_at, handling_amount, gateway
            FROM orders WHERE trade_no = ?
            """,
            tradeNo
        );
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

    /**
     * What the admin API reported about a configured method, plus the site
     * address the URLs were built from - which is what the gateway is told, and
     * so what it signs back.
     */
    private record Method(
        UUID id,
        String uuid,
        String gateway,
        String notifyUrl,
        String publicBaseUrl
    ) {

        String returnUrl(String tradeNo) {
            return publicBaseUrl + "/account/orders/" + tradeNo;
        }
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
