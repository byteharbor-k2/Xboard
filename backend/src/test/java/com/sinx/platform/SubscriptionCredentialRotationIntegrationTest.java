package com.sinx.platform;

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
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.identity.repository.UserAccountRepository;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Credential rotation must invalidate both previously shared credentials while
 * leaving the stable account, session and subscription entitlement intact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SubscriptionCredentialRotationIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@RecordApplicationEvents
class SubscriptionCredentialRotationIntegrationTest {

    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_credential_rotation_test")
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
    private ApplicationEvents applicationEvents;

    @Autowired
    private UserAccountRepository users;

    @Test
    void userAndAdminRotationReplaceBothCredentialsWithoutChangingAccountState()
        throws Exception {
        String email = "proxy-credential-reset@example.com";
        String accessToken = register(email);
        UUID userId = userIdFor(email);
        String oldUrl = subscriptionUrl(accessToken);
        String oldToken = tokenOf(oldUrl);
        long groupId = seedEntitlementAndNode(userId, email);
        UUID oldProxyUuid = proxyUuid(userId);
        assertThat(oldProxyUuid).isEqualTo(userId);

        MvcResult originalConfig = fetch(oldToken, "singbox")
            .andExpect(status().isOk())
            .andReturn();
        assertThat(originalConfig.getResponse().getContentAsString())
            .contains(oldProxyUuid.toString());

        long nodeUserId = nodeUserId(userId);
        int sessionsBefore = sessionCount(userId);
        Map<String, Object> entitlementBefore = entitlement(userId);

        MvcResult userRotation = mockMvc.perform(graphQl(
                accessToken,
                "{\"query\":\"mutation { rotateSubscriptionCredential }\"}"
            ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        String newUrl = JsonPath.read(
            userRotation.getResponse().getContentAsString(),
            "$.data.rotateSubscriptionCredential"
        );
        String newToken = tokenOf(newUrl);
        UUID rotatedProxyUuid = proxyUuid(userId);

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(rotatedProxyUuid).isNotEqualTo(oldProxyUuid);
        assertThat(rotatedProxyUuid).isNotEqualTo(userId);
        fetch(oldToken, "singbox").andExpect(status().isNotFound());
        String updatedConfig = fetch(newToken, "singbox")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(updatedConfig)
            .contains(rotatedProxyUuid.toString())
            .doesNotContain(oldProxyUuid.toString());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE id = ?::uuid",
            UUID.class,
            userId.toString()
        )).isEqualTo(userId);
        assertThat(nodeUserId(userId)).isEqualTo(nodeUserId);
        assertThat(sessionCount(userId)).isEqualTo(sessionsBefore);
        assertThat(entitlement(userId)).isEqualTo(entitlementBefore);

        UserEntitlementChangedEvent syncEvent = applicationEvents.stream(
                UserEntitlementChangedEvent.class
            )
            .filter(event -> event.userId().equals(userId))
            .reduce((first, second) -> second)
            .orElseThrow();
        assertThat(syncEvent.groupIds()).containsExactly(groupId);

        MvcResult adminRotation = mockMvc.perform(post("/api/v2/admin/user/resetSecret")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"%s\"}".formatted(userId)))
            .andExpect(status().isOk())
            .andReturn();
        String adminUrl = JsonPath.read(
            adminRotation.getResponse().getContentAsString(),
            "$.data"
        );
        String adminToken = tokenOf(adminUrl);
        UUID adminProxyUuid = proxyUuid(userId);

        assertThat(adminToken).isNotEqualTo(newToken);
        assertThat(adminProxyUuid).isNotEqualTo(rotatedProxyUuid);
        fetch(newToken, "singbox").andExpect(status().isNotFound());
        String adminConfig = fetch(adminToken, "singbox")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(adminConfig)
            .contains(adminProxyUuid.toString())
            .doesNotContain(rotatedProxyUuid.toString());
        assertThat(sessionCount(userId)).isEqualTo(sessionsBefore);
        assertThat(entitlement(userId)).isEqualTo(entitlementBefore);
    }

    @Test
    void nullLegacyProxyUuidFallsBackToTheStableAccountUuid() throws Exception {
        String email = "proxy-credential-legacy@example.com";
        register(email);
        UUID userId = userIdFor(email);
        jdbcTemplate.update(
            "UPDATE users SET proxy_uuid = NULL WHERE id = ?::uuid",
            userId.toString()
        );

        assertThat(proxyUuid(userId)).isNull();
        assertThat(users.findById(userId).orElseThrow().getProxyUuid())
            .isEqualTo(userId);
    }

    private long seedEntitlementAndNode(UUID userId, String suffix) {
        Instant now = Instant.now();
        long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO node_access_groups (name, created_at, updated_at) "
                + "VALUES (?, ?, ?) RETURNING id",
            Long.class,
            "cred-" + UUID.randomUUID(),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes, speed_limit_mbps,
                reset_policy, capacity_limit, published, sellable, renewable,
                sort_order, server_group_id, created_at, updated_at
            ) VALUES (?::uuid, 'Credential plan', 'Test plan', ?, 100,
                'MONTHLY_FROM_ACTIVATION', NULL, TRUE, TRUE, TRUE, 1, ?, ?, ?)
            """,
            planId.toString(),
            TRANSFER_LIMIT,
            groupId,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name, transfer_limit_bytes,
                uploaded_bytes, downloaded_bytes, speed_limit_mbps, reset_policy,
                starts_at, expires_at, next_reset_at, created_at, updated_at
            ) VALUES (?::uuid, ?::uuid, ?::uuid, 'Credential plan', ?, 10, 20,
                100, 'MONTHLY_FROM_ACTIVATION', ?, ?, NULL, ?, ?)
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(86_400)),
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbcTemplate.update(
            """
            INSERT INTO proxy_nodes (
                type, group_ids, route_ids, name, rate, rate_time_enable,
                rate_time_ranges, transfer_enable, upload_bytes, download_bytes,
                tags, host, port, server_port, protocol_settings,
                custom_outbounds, custom_routes, is_show, is_enabled,
                sort_order, created_at, updated_at
            ) VALUES ('vmess', ?, '[]', 'Credential node', 1, FALSE, '[]',
                0, 0, 0, '[]', 'node.example.com', 443, 443,
                '{\"network\":\"tcp\",\"tls\":0}', '[]', '[]', TRUE, TRUE,
                1, ?, ?)
            """,
            "[" + groupId + "]",
            Timestamp.from(now),
            Timestamp.from(now)
        );
        return groupId;
    }

    private String register(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
            .andExpect(status().isAccepted());
        MvcResult result = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"%s",
                      "password":"subscriber-password",
                      "displayName":"Subscriber",
                      "deviceLabel":"Subscriber Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private UUID userIdFor(String email) {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            UUID.class,
            email
        );
    }

    private String subscriptionUrl(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(graphQl(accessToken,
                "{\"query\":\"{ viewerSubscriptionUrl }\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.viewerSubscriptionUrl"
        );
    }

    private ResultActions fetch(String token, String format) throws Exception {
        return mockMvc.perform(get("/sub/{token}", token).param("flag", format));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder graphQl(
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

    private UUID proxyUuid(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT proxy_uuid FROM users WHERE id = ?::uuid",
            UUID.class,
            userId.toString()
        );
    }

    private long nodeUserId(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT node_user_id FROM users WHERE id = ?::uuid",
            Long.class,
            userId.toString()
        );
    }

    private int sessionCount(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM device_sessions WHERE user_id = ?::uuid AND revoked_at IS NULL",
            Integer.class,
            userId.toString()
        );
    }

    private Map<String, Object> entitlement(UUID userId) {
        return jdbcTemplate.queryForMap(
            """
            SELECT plan_id, transfer_limit_bytes, uploaded_bytes, downloaded_bytes,
                   speed_limit_mbps, reset_policy, expires_at
            FROM subscription_entitlements WHERE user_id = ?::uuid
            """,
            userId.toString()
        );
    }

    private String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
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
