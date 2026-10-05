package com.sinx.platform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The account dashboard's view of the nodes its own entitlement can use.
 *
 * This drives the user GraphQL boundary against the real repositories and
 * schema, with an isolated PostgreSQL database. The test clock makes report
 * freshness and scheduled traffic rates repeatable rather than dependent on
 * when a test happens to run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ViewerNodesIntegrationTest.FixedTimeConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ViewerNodesIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-20T14:00:00Z");
    private static final long TRANSFER_LIMIT = 1_000_000L;
    private static final String QUERY = """
        { viewerNodes {
            id name protocol trafficRate onlineStatus lastSeenAt tags
        } }
        """;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_viewer_nodes_test")
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

    @Test
    void onlyUsableNodesInTheActiveAccountsGroupAreReturnedWithoutCredentials()
        throws Exception {
        UUID userId = seedUser("visible-nodes");
        long groupId = seedGroup("visible-nodes");
        grant(userId, groupId, null, null, 0);

        seedNode("Allowed", groupId, true, true, "node.example.com", 443,
            NOW.minusSeconds(30), BigDecimalRate.BASE, "[]", false, "[\"edge\"]");
        seedNode("Hidden", groupId, true, false, "hidden.example.com", 443,
            null, BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("Disabled", groupId, false, true, "disabled.example.com", 443,
            null, BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("Other group", groupId + 1000, true, true, "other.example.com", 443,
            null, BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("Missing host", groupId, true, true, null, 443,
            null, BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("Invalid port", groupId, true, true, "invalid.example.com", 70_000,
            null, BigDecimalRate.BASE, "[]", false, "[]");

        String response = viewerNodes(userId);
        assertThat(JsonPath.<List<String>>read(response, "$.data.viewerNodes[*].name"))
            .containsExactly("Allowed");
        assertThat(response)
            .contains("edge")
            .doesNotContain("node.example.com", "NO_LEAK_PRIVATE", "NO_LEAK_SERVER")
            .doesNotContain("host", "credential", "protocolSettings", "uuid");
    }

    @Test
    void missingExpiredExhaustedAndCanceledEntitlementsReturnNoNodes()
        throws Exception {
        long groupId = seedGroup("unavailable-entitlements");
        seedNode("Not entitled", groupId, true, true, "node.example.com", 443,
            NOW, BigDecimalRate.BASE, "[]", false, "[]");

        UUID noEntitlement = seedUser("no-entitlement");
        UUID expired = seedUser("expired");
        UUID exhausted = seedUser("exhausted");
        UUID canceled = seedUser("canceled");
        grant(expired, groupId, NOW.minusSeconds(1), null, 0);
        grant(exhausted, groupId, null, null, TRANSFER_LIMIT);
        grant(canceled, groupId, null, NOW.minusSeconds(1), 0);

        assertNoNodes(noEntitlement);
        assertNoNodes(expired);
        assertNoNodes(exhausted);
        assertNoNodes(canceled);
    }

    @Test
    void reportStatusesAndScheduledTrafficRateUseTheControlledClock()
        throws Exception {
        UUID userId = seedUser("report-status");
        long groupId = seedGroup("report-status");
        grant(userId, groupId, null, null, 0);

        seedNode("Recent report", groupId, true, true, "recent.example.com", 443,
            NOW.minusSeconds(30), BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("Stale report", groupId, true, true, "stale.example.com", 443,
            NOW.minusSeconds(121), BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("No report", groupId, true, true, "unknown.example.com", 443,
            null, BigDecimalRate.BASE, "[]", false, "[]");
        seedNode("Scheduled rate", groupId, true, true, "rate.example.com", 443,
            NOW, BigDecimalRate.BASE,
            "[{\"start\":\"22:00\",\"end\":\"23:59\",\"rate\":\"2.5\"}]",
            true, "[]");

        List<Map<String, Object>> nodes = JsonPath.read(viewerNodes(userId), "$.data.viewerNodes");
        assertThat(nodes).hasSize(4);
        assertThat(node(nodes, "Recent report").get("onlineStatus")).isEqualTo("ONLINE");
        assertThat(node(nodes, "Stale report").get("onlineStatus")).isEqualTo("OFFLINE");
        assertThat(node(nodes, "No report").get("onlineStatus")).isEqualTo("UNKNOWN");
        assertThat(node(nodes, "No report").get("lastSeenAt")).isNull();
        assertThat(node(nodes, "Scheduled rate").get("trafficRate")).isEqualTo("2.5");
        assertThat(node(nodes, "Recent report").get("lastSeenAt"))
            .isEqualTo(NOW.minusSeconds(30).toString());
    }

    @Test
    void anonymousGraphqlCannotReadViewerNodes() throws Exception {
        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + QUERY.replace("\n", " ") + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    private String viewerNodes(UUID userId) throws Exception {
        return mockMvc.perform(post("/gateway")
                .with(jwt().jwt(token -> token.subject(userId.toString()))
                    .authorities(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("SCOPE_USER")
                    ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + QUERY.replace("\n", " ") + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn().getResponse().getContentAsString();
    }

    private void assertNoNodes(UUID userId) throws Exception {
        assertThat(JsonPath.<List<Object>>read(viewerNodes(userId), "$.data.viewerNodes"))
            .isEmpty();
    }

    private UUID seedUser(String label) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                subscription_token, proxy_uuid, created_at, updated_at
            ) VALUES (?::uuid, ?, 'test-hash', ?, 'ACTIVE', ?, ?::uuid, ?, ?)
            """,
            id.toString(),
            label + "-" + id + "@example.test",
            label,
            "subscription-" + id,
            id.toString(),
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
        return id;
    }

    private long seedGroup(String label) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO node_access_groups (name, created_at, updated_at)
            VALUES (?, ?, ?) RETURNING id
            """,
            Long.class,
            "vg-" + UUID.randomUUID(),
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
    }

    private void grant(
        UUID userId,
        long groupId,
        Instant expiresAt,
        Instant canceledAt,
        long usedBytes
    ) {
        UUID planId = UUID.randomUUID();
        UUID entitlementId = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, transfer_limit_bytes, reset_policy,
                server_group_id, created_at, updated_at
            ) VALUES (?::uuid, 'Viewer node test', ?, 'NEVER', ?, ?, ?)
            """,
            planId.toString(),
            TRANSFER_LIMIT,
            groupId,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, reset_policy, starts_at,
                expires_at, canceled_at, created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Viewer node test', ?, 0, ?,
                'NEVER', ?, ?, ?, ?, ?
            )
            """,
            entitlementId.toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            usedBytes,
            java.sql.Timestamp.from(NOW.minusSeconds(60)),
            timestamp(expiresAt),
            timestamp(canceledAt),
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
    }

    private void seedNode(
        String name,
        long groupId,
        boolean enabled,
        boolean shown,
        String host,
        int port,
        Instant lastReport,
        java.math.BigDecimal rate,
        String rateRanges,
        boolean rateTimeEnabled,
        String tags
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO proxy_nodes (
                type, group_ids, route_ids, name, rate, rate_time_enable,
                rate_time_ranges, transfer_enable, upload_bytes, download_bytes,
                tags, host, port, server_port, protocol_settings,
                custom_outbounds, custom_routes, is_show, is_enabled,
                sort_order, created_at, updated_at, last_check_at, last_push_at
            ) VALUES (
                'vless', ?, '[]', ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, 443,
                '{\"private_key\":\"NO_LEAK_PRIVATE\",\"server_key\":\"NO_LEAK_SERVER\"}',
                '[]', '[]', ?, ?, ?, ?, ?, ?, ?
            )
            """,
            "[" + groupId + "]",
            name,
            rate,
            rateTimeEnabled,
            rateRanges,
            tags,
            host,
            port,
            shown,
            enabled,
            name.hashCode(),
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW),
            timestamp(lastReport),
            timestamp(lastReport)
        );
    }

    private java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private Map<String, Object> node(List<Map<String, Object>> nodes, String name) {
        return nodes.stream()
            .filter(value -> name.equals(value.get("name")))
            .findFirst()
            .orElseThrow();
    }

    private static class BigDecimalRate {
        private static final java.math.BigDecimal BASE = new java.math.BigDecimal("1.0000");
    }

    @TestConfiguration
    static class FixedTimeConfiguration {

        @Bean
        @Primary
        Clock viewerNodesTestClock() {
            return Clock.fixed(NOW, ZoneId.of("UTC"));
        }
    }
}
