package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.application.OrderService;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Exercises the new-user offer against the migrated schema and real transactions. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(NewUserOfferIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class NewUserOfferIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_new_user_offer_test")
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
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @Autowired
    private PlatformConfigurationService configuration;

    @Autowired
    private OrderFulfilmentService fulfilment;

    @Autowired
    private OrderService orders;

    @Autowired
    private ServiceOrderRepository orderRepository;

    @Autowired
    private UserAccountRepository users;

    @Test
    void trialUserCanCancelAndRetryOfferButACompletedOfferCannotBeBoughtAgain()
        throws Exception {
        UUID trialPlan = seedSubscriptionPlan("Trial " + UUID.randomUUID(), 900);
        UUID offerPlan = seedTrafficPackage("Welcome " + UUID.randomUUID(), 250);
        configureTrial(trialPlan);
        configureOffer(offerPlan);

        String email = email("offer-retry");
        String token = register(email);
        UUID userId = userId(email);
        jdbc.update(
            "UPDATE subscription_entitlements SET uploaded_bytes = 101, downloaded_bytes = 202 WHERE user_id = ?::uuid",
            userId.toString()
        );
        assertThat(jdbc.queryForObject(
            "SELECT is_trial FROM subscription_entitlements WHERE user_id = ?::uuid",
            Boolean.class,
            userId.toString()
        )).isTrue();
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(
                    "{ viewerEntitlement { isTrial expiresAt } }"
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewerEntitlement.isTrial").value(true))
            .andExpect(jsonPath("$.data.viewerEntitlement.expiresAt").isNotEmpty());

        // Trial grants have no order row, so the authenticated catalog marks
        // the selected package for this user while anonymous catalog access
        // keeps the promotional product private.
        List<Map<String, Object>> offers = catalog(token);
        assertThat(offers).anySatisfy(offer -> {
            if (offerPlan.toString().equals(offer.get("id"))) {
                assertThat(offer.get("newUserOffer")).isEqualTo(true);
                assertThat(offer.get("prices")).asList()
                    .containsExactly(Map.of("period", "ONETIME"));
            }
        });
        assertThat(offers).anyMatch(offer ->
            offerPlan.toString().equals(offer.get("id"))
                && Boolean.TRUE.equals(offer.get("newUserOffer"))
        );
        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest("{ offerCatalog { id } }")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.offerCatalog").isArray());
        assertThat(catalog(null)).noneMatch(offer ->
            offerPlan.toString().equals(offer.get("id"))
        );

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(
                    "{ orderQuote(planId: \"%s\", period: ONETIME) { surplusAmount totalAmount } }"
                        .formatted(offerPlan)
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderQuote.surplusAmount").value("0"))
            .andExpect(jsonPath("$.data.orderQuote.totalAmount").value("250"));

        String firstTradeNo = place(token, offerPlan, "ONETIME");
        assertThat(orderByTradeNo(firstTradeNo).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderByTradeNo(firstTradeNo).isNewUserOffer()).isTrue();
        assertThat(entitlementCount(userId)).isEqualTo(1);

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(
                    "mutation { cancelOrder(tradeNo: \"%s\") }"
                        .formatted(firstTradeNo)
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.cancelOrder").value(true));
        assertThat(orderByTradeNo(firstTradeNo).getStatus()).isEqualTo(OrderStatus.CANCELLED);

        String retryTradeNo = place(token, offerPlan, "ONETIME");
        // The invoice is valid once created: changing the setting before its
        // callback cannot invalidate it or rewrite its snapshot.
        UUID replacementOffer = seedTrafficPackage(
            "Replacement " + UUID.randomUUID(),
            350
        );
        configureOffer(replacementOffer);
        fulfilment.settle(retryTradeNo, "gateway-reference-1");
        fulfilment.settle(retryTradeNo, "gateway-reference-1");

        assertThat(orderByTradeNo(retryTradeNo).getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(orderByTradeNo(retryTradeNo).isNewUserOffer()).isTrue();
        assertThat(entitlementCount(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT is_trial FROM subscription_entitlements WHERE user_id = ?::uuid",
            Boolean.class,
            userId.toString()
        )).isFalse();
        assertThat(jdbc.queryForObject(
            "SELECT expires_at FROM subscription_entitlements WHERE user_id = ?::uuid",
            Timestamp.class,
            userId.toString()
        )).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT uploaded_bytes + downloaded_bytes FROM subscription_entitlements WHERE user_id = ?::uuid",
            Long.class,
            userId.toString()
        )).isZero();
        assertQuoteAndPlaceRejected(token, offerPlan, "ONETIME");
        assertQuoteAndPlaceRejected(token, replacementOffer, "ONETIME");
    }

    @Test
    void anyCompletedFormalPurchaseRemovesOfferEligibility() throws Exception {
        UUID offerPlan = seedTrafficPackage("Old buyer offer " + UUID.randomUUID(), 400);
        UUID ordinaryPlan = seedSubscriptionPlan("Ordinary " + UUID.randomUUID(), 1200);
        configureOffer(offerPlan);
        String email = email("offer-old-buyer");
        String token = register(email);
        UUID userId = userId(email);

        String regularTradeNo = place(token, ordinaryPlan, "MONTHLY");
        fulfilment.settle(regularTradeNo, "gateway-ordinary-1");

        assertThat(catalog(token)).noneMatch(offer ->
            offerPlan.toString().equals(offer.get("id"))
        );
        assertQuoteAndPlaceRejected(token, offerPlan, "ONETIME");
        assertThat(users.findById(userId)).isPresent();
    }

    @Test
    void concurrentOfferAttemptsCreateAtMostOneOpenInvoice() throws Exception {
        UUID offerPlan = seedTrafficPackage("Concurrent offer " + UUID.randomUUID(), 500);
        configureOffer(offerPlan);
        String email = email("offer-race");
        register(email);
        UUID userId = userId(email);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> placeConcurrently(
                userId, offerPlan, ready, start
            ));
            Future<Boolean> second = executor.submit(() -> placeConcurrently(
                userId, offerPlan, ready, start
            ));
            ready.await();
            start.countDown();
            assertThat(first.get() ^ second.get()).isTrue();
        }

        Long openCount = jdbc.queryForObject(
            "SELECT count(*) FROM orders WHERE user_id = ?::uuid AND is_new_user_offer = TRUE AND status IN ('PENDING', 'PROCESSING')",
            Long.class,
            userId.toString()
        );
        assertThat(openCount).isEqualTo(1L);
    }

    @Test
    void trialToPeriodicPurchaseStartsFreshPaidCoverageWithoutTrialSurplus()
        throws Exception {
        UUID trialPlan = seedSubscriptionPlan("Trial period " + UUID.randomUUID(), 5000);
        UUID paidPlan = seedSubscriptionPlan("Paid period " + UUID.randomUUID(), 1800);
        configureTrial(trialPlan);
        String email = email("trial-period");
        String token = register(email);
        UUID userId = userId(email);
        jdbc.update(
            "UPDATE subscription_entitlements SET uploaded_bytes = 101, downloaded_bytes = 202 WHERE user_id = ?::uuid",
            userId.toString()
        );

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(
                    "{ orderQuote(planId: \"%s\", period: MONTHLY) { orderType originalAmount surplusAmount totalAmount } }"
                        .formatted(paidPlan)
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.orderQuote.orderType").value("NEW_PURCHASE"))
            .andExpect(jsonPath("$.data.orderQuote.originalAmount").value("1800"))
            .andExpect(jsonPath("$.data.orderQuote.surplusAmount").value("0"))
            .andExpect(jsonPath("$.data.orderQuote.totalAmount").value("1800"));

        String tradeNo = place(token, paidPlan, "MONTHLY");
        Instant paidAt = Instant.now();
        fulfilment.settle(tradeNo, "gateway-period-1");

        Map<String, Object> entitlement = jdbc.queryForMap(
            "SELECT plan_id, starts_at, expires_at, uploaded_bytes, downloaded_bytes, is_trial FROM subscription_entitlements WHERE user_id = ?::uuid",
            userId.toString()
        );
        assertThat(entitlement.get("plan_id").toString()).isEqualTo(paidPlan.toString());
        assertThat(((Timestamp) entitlement.get("starts_at")).toInstant())
            .isBetween(paidAt.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(((Timestamp) entitlement.get("expires_at")).toInstant())
            .isBetween(Instant.now().plus(Duration.ofDays(28)), Instant.now().plus(Duration.ofDays(31)));
        assertThat(((Number) entitlement.get("uploaded_bytes")).longValue()).isZero();
        assertThat(((Number) entitlement.get("downloaded_bytes")).longValue()).isZero();
        assertThat(entitlement.get("is_trial")).isEqualTo(false);
    }

    private boolean placeConcurrently(
        UUID userId,
        UUID offerPlan,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        try {
            orders.place(userId, offerPlan,
                com.sinx.platform.catalog.domain.BillingPeriod.ONETIME, null);
            return true;
        } catch (ApiProblemException rejected) {
            return false;
        }
    }

    private void assertQuoteAndPlaceRejected(
        String token,
        UUID planId,
        String period
    ) throws Exception {
        String quote = "{ orderQuote(planId: \"" + planId + "\", period: "
            + period + ") { totalAmount } }";
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(quote)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[0].extensions.code").value("ORDER_REJECTED"));
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(
                    "mutation { placeOrder(planId: \"%s\", period: %s) { tradeNo } }"
                        .formatted(planId, period)
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors[0].extensions.code").value("ORDER_REJECTED"));
    }

    private String place(String token, UUID planId, String period)
        throws Exception {
        MvcResult result = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphqlRequest(
                    "mutation { placeOrder(planId: \"%s\", period: %s) { tradeNo status } }"
                        .formatted(planId, period)
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.placeOrder.status").value("PENDING"))
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo");
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
                      "password":"offer-test-password",
                      "displayName":"Offer Buyer",
                      "deviceLabel":"Offer Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(registration.getResponse().getContentAsString(),
            "$.accessToken");
    }

    private void configureTrial(UUID planId) {
        configuration.saveSectionSettings("new_user",
            Map.of("try_out_plan_id", planId.toString()));
        configuration.saveSectionSettings("new_user",
            Map.of("try_out_hour", 3));
    }

    private List<Map<String, Object>> catalog(String accessToken) throws Exception {
        var request = post("/gateway")
            .contentType(MediaType.APPLICATION_JSON)
            .content(graphqlRequest("{ offerCatalog { id newUserOffer prices { period } } }"));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        MvcResult result = mockMvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(),
            "$.data.offerCatalog");
    }

    private String graphqlRequest(String query) {
        return "{\"query\":\""
            + query.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"}";
    }

    private void configureOffer(UUID planId) {
        configuration.saveSectionSettings("new_user",
            Map.of("new_user_offer_plan_id", planId.toString()));
    }

    private UUID seedSubscriptionPlan(String name, long amountMinor) {
        return seedPlan(name, "SUBSCRIPTION", "MONTHLY_FROM_ACTIVATION",
            "MONTHLY", amountMinor);
    }

    private UUID seedTrafficPackage(String name, long amountMinor) {
        return seedPlan(name, "TRAFFIC_PACKAGE", "NEVER",
            "ONETIME", amountMinor);
    }

    private UUID seedPlan(
        String name,
        String planType,
        String resetPolicy,
        String period,
        long amountMinor
    ) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO service_plans (
                id, name, description, plan_type, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, published, sellable, renewable,
                resettable, sort_order, created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?, ?, 200, ?, TRUE, TRUE, ?, FALSE, 1, ?, ?
            )
            """, id.toString(), name, name + " plan", planType,
            10L * 1024 * 1024 * 1024, resetPolicy,
            "SUBSCRIPTION".equals(planType), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
            INSERT INTO service_plan_prices (
                id, plan_id, billing_period, amount_minor, currency
            ) VALUES (?::uuid, ?::uuid, ?, ?, 'CNY')
            """, UUID.randomUUID().toString(), id.toString(), period, amountMinor);
        return id;
    }

    private UUID userId(String email) {
        return jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            UUID.class,
            email
        );
    }

    private long entitlementCount(UUID userId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM subscription_entitlements WHERE user_id = ?::uuid",
            Long.class,
            userId.toString()
        );
        return count == null ? 0 : count;
    }

    private com.sinx.platform.order.domain.ServiceOrder orderByTradeNo(String tradeNo) {
        return orderRepository.findByTradeNo(tradeNo).orElseThrow();
    }

    private String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.test";
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
