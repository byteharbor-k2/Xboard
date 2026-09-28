package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.node.websocket.NodeWebSocketSyncService;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * Banning has to actually cut an account off.
 *
 * The status change alone stops sign-in and the subscription endpoint, but a
 * ban must also revoke every device session the account still holds, and it
 * must reach the nodes: their kernels carry the account in their own user
 * list until they re-read it. These tests drive the admin ban endpoint the
 * account, the node and the protocol are set up through the fixtures the
 * node integration tests already use.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminBanIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminBanIntegrationTest {

    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_ban_test")
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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    /**
     * The after-commit listener pushes user lists through this service. A
     * connected kernel cannot be faked cheaply in a MockMvc test, so the push
     * itself is asserted on the spy with no session present (the call returns
     * false harmlessly); the accompanying NodeUserSuspensionSyncListenerTest
     * covers the node-selection logic on the unit level.
     */
    @MockitoSpyBean
    private NodeWebSocketSyncService webSocketSync;

    @Test
    void banningRevokesEveryDeviceSessionAndRefusesLoginAndRefresh()
        throws Exception {
        String email = "ban-victim@example.com";
        UUID userId = register(email);
        MvcResult firstLogin = mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "victim-account-password",
                      "deviceLabel": "First Browser"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andReturn();
        Cookie firstRefresh =
            firstLogin.getResponse().getCookie("rt_session");
        assertThat(firstRefresh).isNotNull();
        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "victim-account-password",
                      "deviceLabel": "Second Browser"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk());

        // Are we actually counting sessions that the ban has to revoke?
        // The registration itself already issues one; two logins follow.
        Long sessionCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM device_sessions
            WHERE user_id = ?::uuid AND revoked_at IS NULL
            """,
            Long.class,
            userId.toString()
        );
        assertThat(sessionCount).isEqualTo(3L);

        mockMvc.perform(post("/api/v2/admin/user/ban")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","banned":true}
                    """.formatted(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.banned").value(true));

        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM device_sessions
            WHERE user_id = ?::uuid AND revoked_at IS NULL
            """,
            Long.class,
            userId.toString()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM device_sessions WHERE user_id = ?::uuid",
            Long.class,
            userId.toString()
        )).isEqualTo(3L);

        // Sign-in is refused, and so is the refresh token the account was
        // still holding: a revoked session no longer even reaches the status
        // check, so the answer is the token refusal, not the suspension one.
        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "victim-account-password"
                    }
                    """.formatted(email)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));

        mockMvc.perform(post("/session/refresh").cookie(firstRefresh))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v2/admin/user/ban")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","banned":false}
                    """.formatted(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.banned").value(false));

        // Unbanning revokes nothing: the account can sign in again fully.
        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "victim-account-password",
                      "deviceLabel": "Back After Unban"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk());
        clearInvocations(webSocketSync);
    }

    /**
     * Banning is not an update field: it lives on its own {@code /ban}
     * endpoint. An update payload that still carries {@code banned} is
     * ignored - Jackson drops the unknown property - so a status toggle can
     * never hide inside an ordinary form save and skip the session revocation
     * and node push a real ban performs.
     */
    @Test
    void banningThroughTheUpdatePayloadIsIgnored() throws Exception {
        UUID userId = register("update-ban@example.com");

        mockMvc.perform(post("/api/v2/admin/user/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","banned":true,"remarks":"renamed note"}
                    """.formatted(userId)))
            .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM users WHERE id = ?::uuid",
            String.class,
            userId.toString()
        )).isEqualTo("ACTIVE");
    }

    /**
     * The node user list is what xboard-node's kernel serves, so a banned
     * account stops being listed for the node whose access group serves it -
     * and comes back the moment the ban is lifted - without any migration of
     * roles or entitlements. The machine and token exist here because the
     * user endpoint is node-facing, not because the test is about machines.
     */
    @Test
    void theNodeUserListFollowsTheSuspension() throws Exception {
        String email = "node-banned@example.com";
        UUID userId = register(email);
        long groupId = jdbcTemplate.queryForObject(
            """
            INSERT INTO node_access_groups (name, created_at, updated_at)
            VALUES (?, now(), now())
            RETURNING id
            """,
            Long.class,
            "ban-group-" + email
        );
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes,
                speed_limit_mbps, reset_policy, capacity_limit,
                published, sellable, renewable, sort_order,
                server_group_id, created_at, updated_at
            ) VALUES (
                ?::uuid, 'Banned Plan', 'Node exclusion test', ?,
                200, 'MONTHLY_FROM_ACTIVATION', NULL,
                TRUE, TRUE, TRUE, 1, ?, ?, ?
            )
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
                uploaded_bytes, downloaded_bytes, speed_limit_mbps,
                reset_policy, starts_at, expires_at, next_reset_at,
                created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, 'Banned Plan', ?,
                0, 0, 200,
                'MONTHLY_FROM_ACTIVATION', ?, ?, NULL, ?, ?
            )
            """,
            UUID.randomUUID().toString(),
            userId.toString(),
            planId.toString(),
            TRANSFER_LIMIT,
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(30L * 86400)),
            Timestamp.from(now),
            Timestamp.from(now)
        );

        MvcResult machine = mockMvc.perform(post("/api/v2/admin/server/machine/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Ban fixture %s","is_active":true}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andReturn();
        long machineId = ((Number) JsonPath.read(
            machine.getResponse().getContentAsString(),
            "$.data.id"
        )).longValue();
        String machineToken = JsonPath.read(
            machine.getResponse().getContentAsString(),
            "$.data.token"
        );

        int port = 20000 + (int) (Math.random() * 20000);
        MvcResult node = mockMvc.perform(post("/api/v2/admin/server/manage/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type": "shadowsocks",
                      "name": "Ban fixture SS",
                      "machine_id": %d,
                      "group_ids": [%d],
                      "host": "node.example.test",
                      "port": %d,
                      "server_port": %d,
                      "rate": 1,
                      "protocol_settings": {
                        "network": "tcp",
                        "cipher": "2022-blake3-aes-128-gcm",
                        "server_key": "ban-fixture-key"
                      },
                      "show": true,
                      "enabled": true
                    }
                    """.formatted(machineId, groupId, port, port)))
            .andExpect(status().isOk())
            .andReturn();
        long nodeId = ((Number) JsonPath.read(
            node.getResponse().getContentAsString(),
            "$.data.id"
        )).longValue();

        Long nodeUserId = jdbcTemplate.queryForObject(
            "SELECT node_user_id FROM users WHERE id = ?::uuid",
            Long.class,
            userId.toString()
        );

        assertThat(usersOf(machineId, nodeId, machineToken))
            .contains(nodeUserId.intValue());

        clearInvocations(webSocketSync);
        mockMvc.perform(post("/api/v2/admin/user/ban")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","banned":true}
                    """.formatted(userId)))
            .andExpect(status().isOk());

        assertThat(usersOf(machineId, nodeId, machineToken)).isEmpty();
        verify(webSocketSync).pushUsers(nodeId);

        clearInvocations(webSocketSync);
        mockMvc.perform(post("/api/v2/admin/user/ban")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","banned":false}
                    """.formatted(userId)))
            .andExpect(status().isOk());

        assertThat(usersOf(machineId, nodeId, machineToken))
            .contains(nodeUserId.intValue());
        verify(webSocketSync).pushUsers(nodeId);

        // The toggles are their own cleanup: an unbanned account is active.
        mockMvc.perform(post("/api/v2/admin/server/manage/drop")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":%d}".formatted(nodeId)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/admin/server/machine/drop")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":%d}".formatted(machineId)))
            .andExpect(status().isOk());
    }

    private java.util.List<Integer> usersOf(
        long machineId,
        long nodeId,
        String token
    ) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/server/user")
                .param("machine_id", Long.toString(machineId))
                .param("node_id", Long.toString(nodeId))
                .param("token", token))
            .andExpect(status().isOk())
            .andReturn();
        return JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.users[*].id"
        );
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    /** Registers and returns the account id; its sessions stay for (a). */
    private UUID register(String email) throws Exception {
        clearRegistrationLimits();
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
                      "password": "victim-account-password",
                      "displayName": "Ban Fixture",
                      "deviceLabel": "Ban Fixture Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.viewer.id"
        );
        return UUID.fromString(id);
    }

    private void clearRegistrationLimits() {
        redisTemplate.delete(
            redisTemplate.keys("identity:registration-ip:*")
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
