package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/**
 * The node user list, served from the SQL-filtered query instead of a full
 * table stream.
 *
 * The list is the credential boundary of the node: one extra row hands a real
 * customer's identity to a node he does not pay for, one missing row makes a
 * paying customer unable to connect. Each eligibility rule is therefore
 * exercised as its own account, and the served body and ETag are asserted as
 * byte-shape equals against the eligibility rules as the entity still defines
 * them - so a drift between the SQL predicates and {@code stateAt()} cannot
 * slip by unnoticed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class NodeUsersQueryTest {

    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;
    private static final String LEGACY_TOKEN = "a".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_nodeusers_test")
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

    private long groupId;
    private long otherGroupId;
    private UUID planId;
    private long nodeId;

    @BeforeEach
    void seedBase() {
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
        otherGroupId = seedGroup("unrelated-" + UUID.randomUUID());
        planId = seedPlan("Base plan", groupId);
        nodeId = seedNode("香港 01", "vmess", "hk1.example.com", groupId);
    }

    @Test
    void servesEligibleUsersOnlyWithAnEtagMatchingTheirPayload() throws Exception {
        UUID includedPlanId = seedPlan("Plan A", groupId);
        UUID otherGroupPlanId = seedPlan("Plan B", otherGroupId);
        UUID overrideUserId = seedUser("override@example.com", "ACTIVE");
        UUID plainUserId = seedUser("plain@example.com", "ACTIVE");
        UUID overridePlanId = seedPlan("Plan C", otherGroupId);

        // Reached the node's group through the plan.
        seedEntitlement(plainUserId, includedPlanId);
        // Reached the same group through the account-level group override.
        jdbcTemplate.update(
            "UPDATE users SET server_group_id = ? WHERE id = ?::uuid",
            groupId,
            overrideUserId.toString()
        );
        seedEntitlement(overrideUserId, overridePlanId);
        // Same age, wrong group: must stay out.
        UUID wrongGroupUser = seedUser("wrong-group@example.com", "ACTIVE");
        seedEntitlement(wrongGroupUser, otherGroupPlanId);
        // Suspended accounts never get delivered, whatever the plan says.
        UUID suspendedUser = seedUser("suspended@example.com", "SUSPENDED");
        seedEntitlement(suspendedUser, planId);

        FetchResult served = fetchUsers();
        // The SQL keeps the same ascending node-user-id order the in-memory
        // sort used to produce.
        assertThat(served.ids()).isSorted().containsExactlyInAnyOrder(
            nodeUserIdOf(plainUserId),
            nodeUserIdOf(overrideUserId)
        );
        List<LinkedHashMap<String, Object>> expected = expectedPayload(List.of(
            eligibleUser(plainUserId),
            eligibleUser(overrideUserId)
        ));
        assertThat(served.etag()).isEqualTo(etag(expected));
        // A second call is byte for byte stable - the etag is the node cache.
        assertThat(fetchUsers().etag()).isEqualTo(served.etag());
    }

    @Test
    void omitsAccountsWhoseEntitlementIsExpiredCancelledOrExhausted() throws Exception {
        Instant now = Instant.now();
        UUID liveUser = seedUser("live@example.com", "ACTIVE");
        UUID expiredUser = seedUser("expired@example.com", "ACTIVE");
        UUID cancelledUser = seedUser("cancelled@example.com", "ACTIVE");
        UUID exhaustedUser = seedUser("exhausted@example.com", "ACTIVE");

        seedEntitlement(liveUser, planId);
        // Expired: past the last instant the entitlement still covers.
        seedEntitlement(expiredUser, planId, 0, 0,
            now.minusSeconds(600), now.minusSeconds(60));
        // Cancelled but not yet expired.
        seedEntitlement(cancelledUser, planId, 0, 0, now, null,
            now.minusSeconds(30));
        // Fully spent allowance: neither the byte nor the billed report path
        // may serve the account again.
        seedEntitlement(exhaustedUser, planId, TRANSFER_LIMIT, 0, now, null);

        FetchResult served = fetchUsers();
        assertThat(served.ids()).containsExactly(nodeUserIdOf(liveUser));
        assertThat(served.etag()).isEqualTo(etag(
            expectedPayload(List.of(eligibleUser(liveUser)))
        ));
    }

    /**
     * Every user already carries the node address derived automatically, so the
     * addressable rule is vacuous at runtime; what still matters is that an
     * empty group list answers an empty list in one shot, with no query at all.
     */
    @Test
    void answersAnEmptyListForANodeWithoutGroups() throws Exception {
        long orphanNode = seedNode("孤儿 01", "vmess", "orphan.example.com", -1);
        jdbcTemplate.update(
            "UPDATE proxy_nodes SET group_ids = '[]' WHERE id = ?",
            orphanNode
        );

        MvcResult result = mockMvc.perform(get("/api/v1/server/UniProxy/user")
                .param("node_id", Long.toString(orphanNode))
                .param("token", LEGACY_TOKEN))
            .andExpect(status().isOk())
            .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<Object>>read(body, "$.users")).isEmpty();
        assertThat(result.getResponse().getHeader("ETag"))
            .isEqualTo(etag(List.of()));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record EligibleUser(long nodeUserId, UUID id, Integer speedLimitMbps) {
    }

    private EligibleUser eligibleUser(UUID userId) {
        Integer speedLimit = jdbcTemplate.queryForObject(
            "SELECT speed_limit_mbps FROM subscription_entitlements "
                + "WHERE user_id = ?::uuid",
            Integer.class,
            userId.toString()
        );
        return new EligibleUser(
            nodeUserIdOf(userId),
            userId,
            speedLimit == null ? 0 : speedLimit
        );
    }

    private void seedEntitlement(UUID userId, UUID planId) {
        Instant now = Instant.now();
        seedEntitlement(userId, planId, 0, 0, now, null, null);
    }

    private void seedEntitlement(
        UUID userId,
        UUID planId,
        long uploadedBytes,
        long downloadedBytes,
        Instant startsAt,
        Instant expiresAt
    ) {
        seedEntitlement(userId, planId, uploadedBytes, downloadedBytes,
            startsAt, expiresAt, null);
    }

    private void seedEntitlement(
        UUID userId,
        UUID planId,
        long uploadedBytes,
        long downloadedBytes,
        Instant startsAt,
        Instant expiresAt,
        Instant canceledAt
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                canceled_at, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Seed plan', ?,
                ?, ?, 200,
                'MONTHLY_FROM_ACTIVATION', ?, ?, NULL,
                ?, ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            uploadedBytes,
            downloadedBytes,
            Timestamp.from(startsAt.isBefore(Instant.now())
                ? startsAt : Instant.now()),
            expiresAt == null ? null : Timestamp.from(expiresAt),
            canceledAt == null ? null : Timestamp.from(canceledAt),
            Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now())
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

    /** Node as the admin API writes it: group list is the audience. */
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
    // Driving the endpoint
    // ------------------------------------------------------------------

    private record FetchResult(List<Long> ids, String etag) {

        static FetchResult of(String body, String etag) {
            return new FetchResult(
                JsonPath.<List<Number>>read(body, "$.users[*].id").stream()
                    .map(Number::longValue)
                    .toList(),
                etag
            );
        }
    }

    private FetchResult fetchUsers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/server/UniProxy/user")
                .param("node_id", Long.toString(nodeId))
                .param("token", LEGACY_TOKEN))
            .andExpect(status().isOk())
            .andReturn();
        return FetchResult.of(
            result.getResponse().getContentAsString(StandardCharsets.UTF_8),
            result.getResponse().getHeader("ETag")
        );
    }

    /**
     * The payload the entity's own rules say the node must be served, built in
     * exactly the shape the service builds it, and the ETag over it.
     */
    private List<LinkedHashMap<String, Object>> expectedPayload(
        List<EligibleUser> users
    ) {
        return users.stream()
            .sorted(java.util.Comparator.comparingLong(EligibleUser::nodeUserId))
            .map(user -> {
                LinkedHashMap<String, Object> value = new LinkedHashMap<>();
                value.put("id", user.nodeUserId());
                value.put("uuid", user.id().toString());
                value.put("speed_limit", user.speedLimitMbps());
                value.put("device_limit", 0);
                return value;
            })
            .toList();
    }

    private String etag(Object payload) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
            new tools.jackson.databind.ObjectMapper()
                .writeValueAsBytes(payload)
        );
        return "\"" + HexFormat.of().formatHex(digest) + "\"";
    }
}
