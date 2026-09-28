package com.sinx.platform.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;

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
 * The daily traffic ledger and the statistics that read it.
 *
 * Three guarantees are checked against a real database: a node report charges
 * an eligible account and its daily row - twice in the same day, both
 * accumulating; a report about an account that is not eligible for the node
 * leaves no row behind; and the admin dashboard plus the account's own
 * gateway field read those numbers back the way the pages show them.
 *
 * The node side is driven over the legacy UniProxy surface, so the exercise
 * covers the whole chain the panel ships to xboard-node, not just the
 * repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TrafficStatsIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TrafficStatsIntegrationTest {

    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;
    private static final String LEGACY_TOKEN = "b".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_statstest")
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

    private long groupId;
    private UUID planId;
    private long nodeId;

    @BeforeEach
    void seedBase() {
        // The statistics are class-wide aggregates, so every test starts from
        // an empty slate - the DB container is shared by the whole class.
        jdbcTemplate.update(
            """
            TRUNCATE traffic_daily, orders, subscription_entitlements,
                     proxy_nodes, node_access_groups, service_plans, users,
                     platform_settings
            RESTART IDENTITY CASCADE
            """
        );
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO platform_settings (setting_key, setting_value, updated_at)
            VALUES ('server.server_token', ?, ?)
            ON CONFLICT (setting_key) DO UPDATE
            SET setting_value = EXCLUDED.setting_value,
                updated_at = EXCLUDED.updated_at
            """,
            LEGACY_TOKEN,
            Timestamp.from(now)
        );
        groupId = seedGroup("served-" + UUID.randomUUID());
        planId = seedPlan("Starter", groupId);
        nodeId = seedNode("香港 01", "vmess", "hk1.example.com", groupId);
    }

    @Test
    void accumulatesEveryReportOfTheSameDayInTheEligibleAccountsRow()
        throws Exception {
        UUID userId = seedUser("charger@example.com", "ACTIVE");
        seedEntitlement(userId, planId);
        long nodeUserId = nodeUserIdOf(userId);

        pushTraffic(nodeId, nodeUserId, 100L, 200L);
        pushTraffic(nodeId, nodeUserId, 30L, 40L);

        // Raw deltas accumulate across reports; with rate 1 the billed column
        // equals the same bytes and reconciles with the entitlement counters.
        assertThat(dailyRow(userId, nodeId)).containsEntry(
            "upload_bytes", 130L
        ).containsEntry("download_bytes", 240L).containsEntry(
            "billed_bytes", 370L
        );
        assertThat(entitlementCounters(userId))
            .containsEntry("uploaded_bytes", 130L)
            .containsEntry("downloaded_bytes", 240L);
    }

    @Test
    void recordsNothingForAnAccountTheNodeDoesNotServe() throws Exception {
        UUID ineligible = seedUser("outsider@example.com", "ACTIVE");
        UUID outsiderPlanId = seedPlan(
            "Other plan", seedGroup("elsewhere-" + UUID.randomUUID()));
        seedEntitlement(ineligible, outsiderPlanId);
        long nodeUserId = nodeUserIdOf(ineligible);

        pushTraffic(nodeId, nodeUserId, 1000L, 2000L);

        assertThat(rowsFor(ineligible, nodeId)).isZero();
        // Yet the node itself carried the bytes, so its own counters grow.
        Long upload = jdbcTemplate.queryForObject(
            "SELECT upload_bytes FROM proxy_nodes WHERE id = ?",
            Long.class,
            nodeId
        );
        assertThat(upload).isEqualTo(1000L);
    }

    @Test
    void dashboardReadsTheLedgerAndThePaidOrders() throws Exception {
        UUID userId = seedUser("reader@example.com", "ACTIVE");
        seedEntitlement(userId, planId);
        long nodeUserId = nodeUserIdOf(userId);
        pushTraffic(nodeId, nodeUserId, 1024L, 2048L);
        seedPaidOrder(userId, 123_000);

        MvcResult summaryResult = mockMvc.perform(
                get("/api/v2/admin/stat/getStats").with(administrator()))
            .andExpect(status().isOk())
            .andReturn();
        String summaryBody = summaryResult.getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
        assertThat(((Number) JsonPath.read(summaryBody, "$.todayIncome"))
            .doubleValue()).isEqualTo(1230.0);
        assertThat(((Number) JsonPath.read(summaryBody, "$.monthlyIncome"))
            .doubleValue()).isEqualTo(1230.0);
        assertThat(JsonPath.<Integer>read(summaryBody, "$.totalUsers"))
            .isEqualTo(1);
        assertThat(JsonPath.<Integer>read(summaryBody, "$.monthlyUploadBytes"))
            .isEqualTo(1024);
        assertThat(JsonPath.<Integer>read(summaryBody, "$.monthlyDownloadBytes"))
            .isEqualTo(2048);
    }

    @Test
    void trafficRankingNamesTheNodesThatCarriedTraffic() throws Exception {
        UUID userId = seedUser("ranker@example.com", "ACTIVE");
        seedEntitlement(userId, planId);
        pushTraffic(nodeId, nodeUserIdOf(userId), 2048L, 4096L);

        String body = mockMvc.perform(get("/api/v2/admin/stat/getServerLastRank")
                .with(administrator())
                .param("period", "30d"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Object>>read(body, "$[*].label"))
            .containsExactly("香港 01");
        assertThat(((Number) JsonPath.read(body, "$[0].bytes")).longValue())
            .isEqualTo(6144L);
    }

    @Test
    void accountReadsItsOwnLedgerAndAnonymousRequestsAreRefused()
        throws Exception {
        String accessToken = register("ledger-owner@example.com");
        UUID userId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE LOWER(email) = LOWER(?)",
            UUID.class,
            "ledger-owner@example.com"
        );
        UUID registeredPlanId = seedPlan("Member plan", groupId);
        seedEntitlement(userId, registeredPlanId);
        long otherNodeId = seedNode(
            "新加坡 01", "vmess", "sg1.example.com", groupId
        );
        pushTraffic(nodeId, nodeUserIdOf(userId), 500L, 600L);
        pushTraffic(otherNodeId, nodeUserIdOf(userId), 70L, 80L);

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerTrafficDaily { day nodeName uploadBytes downloadBytes billedBytes } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.errors").doesNotExist()
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.data.viewerTrafficDaily.length()")
                    .value(2)
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.data.viewerTrafficDaily[0].nodeName")
                    .value("香港 01")
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.data.viewerTrafficDaily[0].billedBytes")
                    .value("1100")
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.data.viewerTrafficDaily[1].nodeName")
                    .value("新加坡 01")
            )
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.data.viewerTrafficDaily[1].billedBytes")
                    .value("150")
            );

        // The same field to an anonymous caller must never answer rows.
        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"{ viewerTrafficDaily { day } }\"}"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.errors").isNotEmpty()
            );
    }

    // ------------------------------------------------------------------
    // Driving the node report
    // ------------------------------------------------------------------

    /**
     * A report as xboard-node pushes it in legacy mode: the traffic entries
     * travel at the top level of the body, and the controller turns every
     * entry except token and node id into one report payload - so the account
     * id is a literal body key.
     */
    private void pushTraffic(
        long nodeId,
        long nodeUserId,
        long uploaded,
        long downloaded
    ) throws Exception {
        mockMvc.perform(post("/api/v1/server/UniProxy/push")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "node_id": %d,
                      "token": "%s",
                      "%d": [%d, %d]
                    }
                    """.formatted(
                        nodeId,
                        LEGACY_TOKEN,
                        nodeUserId,
                        uploaded,
                        downloaded
                    )))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Map<String, Object> dailyRow(UUID userId, long node) {
        return jdbcTemplate.queryForMap(
            """
            SELECT upload_bytes, download_bytes, billed_bytes
            FROM traffic_daily
            WHERE user_id = ?::uuid AND node_id = ?
              AND day = date(now() AT TIME ZONE 'Asia/Shanghai')
            """,
            userId.toString(),
            node
        );
    }

    private long rowsFor(UUID userId, long node) {
        Long rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM traffic_daily WHERE user_id = ?::uuid AND node_id = ?",
            Long.class,
            userId.toString(),
            node
        );
        return rows == null ? 0 : rows;
    }

    private Map<String, Object> entitlementCounters(UUID userId) {
        return jdbcTemplate.queryForMap(
            "SELECT uploaded_bytes, downloaded_bytes "
                + "FROM subscription_entitlements WHERE user_id = ?::uuid",
            userId.toString()
        );
    }

    private void seedPaidOrder(UUID userId, long totalAmount) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO orders (
                id, trade_no, user_id, plan_id, plan_name, period, order_type,
                status, currency, original_amount, surplus_order_ids,
                total_amount, created_at, updated_at, paid_at
            ) VALUES (
                ?::uuid, ?, ?::uuid, ?::uuid, 'Seed plan', 'MONTHLY',
                'NEW_PURCHASE', 'COMPLETED', 'CNY', ?, '[]',
                ?, ?, ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            "SX-" + UUID.randomUUID().toString().substring(0, 8),
            userId.toString(),
            planId.toString(),
            totalAmount,
            totalAmount,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private void seedEntitlement(UUID userId, UUID planId) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Seed plan', ?,
                0, 0, 100,
                'MONTHLY_FROM_ACTIVATION', ?, NULL, NULL,
                ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now),
            Timestamp.from(now)
        );
    }

    private UUID seedUser(String email, String status) {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, created_at, updated_at, version
            ) VALUES (?::uuid, ?, 'seed-hash', ?, ?, ?, ?, ?, 0)
            """,
            userId.toString(),
            email,
            email,
            status,
            UUID.randomUUID().toString().replace("-", ""),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return userId;
    }

    private long nodeUserIdOf(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT node_user_id FROM users WHERE id = ?::uuid",
            Long.class,
            userId.toString()
        );
    }

    private long seedGroup(String name) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO node_access_groups (name, created_at, updated_at)
            VALUES (?, now(), now())
            RETURNING id
            """,
            Long.class,
            name
        );
    }

    private UUID seedPlan(String name, long serverGroupId) {
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes,
                speed_limit_mbps, reset_policy,
                capacity_limit, published, sellable, renewable,
                sort_order, server_group_id, created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?,
                100, 'MONTHLY_FROM_ACTIVATION',
                NULL, TRUE, TRUE, TRUE,
                1, ?, ?, ?
            )
            """,
            planId.toString(),
            name,
            name + " description",
            TRANSFER_LIMIT,
            serverGroupId,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return planId;
    }

    private long seedNode(
        String name,
        String type,
        String host,
        long groupId
    ) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO proxy_nodes (
                type, group_ids, route_ids, name, rate, rate_time_enable,
                rate_time_ranges, transfer_enable, upload_bytes, download_bytes,
                tags, host, port, server_port, protocol_settings,
                custom_outbounds, custom_routes, is_show, is_enabled,
                sort_order, created_at, updated_at
            ) VALUES (
                ?, ?, '[]', ?, 1, FALSE,
                '[]', 0, 0, 0,
                '[]', ?, 443, 443, '{"network":"tcp"}',
                '[]', '[]', TRUE, TRUE,
                1, now(), now()
            )
            RETURNING id
            """,
            Long.class,
            type,
            "[" + groupId + "]",
            name,
            host
        );
    }

    // ------------------------------------------------------------------
    // Sign-in plumbing (only the ledger-owner test needs a user session)
    // ------------------------------------------------------------------

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
                      "password":"ledger-owner-password",
                      "displayName":"Ledger Owner",
                      "deviceLabel":"Stats Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.accessToken"
        );
    }

    private RequestPostProcessor administrator() {
        return org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_ADMIN")
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
            assertThat(latestCode.get()).matches("\\d{6}");
            return latestCode.get();
        }
    }
}
