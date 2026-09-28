package com.sinx.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.node.websocket.NodeWebSocketSyncService;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
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
 * Entitlement changes must reach the nodes while they are still news.
 *
 * Between polls a node kernel serves whatever user list it was last handed,
 * so an order that opens a subscription and an administrator who corrects
 * one both have to push the corrected list to the nodes the account is
 * served through - and only to those: a node outside the account's group
 * learns nothing it could use. The push itself is asserted on the spy with
 * no kernel connected (the call returns false harmlessly); the
 * NodeUserEntitlementSyncListenerTest covers the node-selection logic on the
 * unit level, and AdminBanIntegrationTest the same technique for bans.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EntitlementSyncIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class EntitlementSyncIntegrationTest {

    private static final long TRANSFER_LIMIT = 1024L * 1024 * 1024;

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_entitlement_sync_test")
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
     * connected kernel cannot be faked cheaply in a MockMvc test, so the
     * push itself is asserted on the spy with no session present.
     */
    @MockitoSpyBean
    private NodeWebSocketSyncService webSocketSync;

    @Test
    void fulfillingAnOrderPushesTheUserListToTheServingNodesOnly()
        throws Exception {
        Fixture fixture = entitledUserOnServedNodes(
            "entitlement-sync@example.com");
        clearInvocations(webSocketSync);

        String tradeNo = placeMonthlyOrder(fixture.accessToken(), fixture.planId());
        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        // The serving node gets the fresh list; the node outside the plan's
        // group is not disturbed - it never served this account.
        verify(webSocketSync).pushUsers(fixture.servedNodeId());
        verify(webSocketSync, never()).pushUsers(fixture.otherNodeId());

        Long nodeUserId = jdbcTemplate.queryForObject(
            "SELECT node_user_id FROM users WHERE id = ?::uuid",
            Long.class,
            fixture.userId().toString()
        );
        assertThat(usersOf(fixture.machineId(), fixture.servedNodeId(),
            fixture.machineToken())).contains(nodeUserId.intValue());
        assertThat(usersOf(fixture.machineId(), fixture.otherNodeId(),
            fixture.machineToken())).isEmpty();
    }

    @Test
    void adminSubscriptionCorrectionsPushButOtherEditsDoNot() throws Exception {
        Fixture fixture = entitledUserOnServedNodes(
            "entitlement-sync-corrections@example.com");
        // The corrections below need a subscription to correct.
        String tradeNo = placeMonthlyOrder(fixture.accessToken(), fixture.planId());
        mockMvc.perform(post("/api/v2/admin/order/paid")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trade_no":"%s"}
                    """.formatted(tradeNo)))
            .andExpect(status().isOk());
        clearInvocations(webSocketSync);

        // A remark reaches nothing a node reads.
        mockMvc.perform(post("/api/v2/admin/user/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","remarks":"operator note"}
                    """.formatted(fixture.userId())))
            .andExpect(status().isOk());
        verify(webSocketSync, never()).pushUsers(anyLong());

        // The allowance decides when the entitlement exhausts, which decides
        // whether the node list carries the account, so it pushes.
        mockMvc.perform(post("/api/v2/admin/user/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","transfer_limit_bytes":%d}
                    """.formatted(fixture.userId(), 2 * TRANSFER_LIMIT)))
            .andExpect(status().isOk());
        verify(webSocketSync).pushUsers(fixture.servedNodeId());
        verify(webSocketSync, never()).pushUsers(fixture.otherNodeId());

        // A traffic reset can lift an exhausted account back into the node
        // list, so it pushes for the same reason.
        clearInvocations(webSocketSync);
        mockMvc.perform(post("/api/v2/admin/user/resetTraffic")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s"}
                    """.formatted(fixture.userId())))
            .andExpect(status().isOk());
        verify(webSocketSync).pushUsers(fixture.servedNodeId());
        verify(webSocketSync, never()).pushUsers(fixture.otherNodeId());
    }

    /**
     * One account, one plan whose access group only one node serves.
     *
     * The fixture drops no rows: the class shares one database and later
     * tests assert only on their own node ids, so leftovers from an earlier
     * test receiving a push are harmless.
     */
    private Fixture entitledUserOnServedNodes(String email) throws Exception {
        Registered account = register(email);
        long servedGroup = accessGroup("served");
        long otherGroup = accessGroup("other");
        UUID planId = seedMonthlyPlan("Sync Plan", 1200, servedGroup);

        MvcResult machine = mockMvc.perform(post("/api/v2/admin/server/machine/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Sync fixture %s","is_active":true}
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

        long servedNodeId = createNode(machineId, servedGroup);
        long otherNodeId = createNode(machineId, otherGroup);

        return new Fixture(
            account.accessToken(),
            account.userId(),
            planId,
            machineId,
            machineToken,
            servedNodeId,
            otherNodeId
        );
    }

    private long accessGroup(String name) {
        return jdbcTemplate.queryForObject(
            """
            INSERT INTO node_access_groups (name, created_at, updated_at)
            VALUES (?, now(), now())
            RETURNING id
            """,
            Long.class,
            name + "-" + UUID.randomUUID()
        );
    }

    private UUID seedMonthlyPlan(String name, long amountMinor, Long serverGroupId) {
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
                ?::uuid, ?, ?, ?, 200, 'MONTHLY_FROM_ACTIVATION', NULL,
                TRUE, TRUE, TRUE, 1, ?, ?, ?
            )
            """,
            planId.toString(),
            name,
            name + " plan",
            TRANSFER_LIMIT,
            serverGroupId,
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

    private long createNode(long machineId, long groupId) throws Exception {
        int port = 20000 + (int) (Math.random() * 20000);
        MvcResult node = mockMvc.perform(post("/api/v2/admin/server/manage/save")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type": "shadowsocks",
                      "name": "Sync fixture SS %d",
                      "machine_id": %d,
                      "group_ids": [%d],
                      "host": "node.example.test",
                      "port": %d,
                      "server_port": %d,
                      "rate": 1,
                      "protocol_settings": {
                        "network": "tcp",
                        "cipher": "2022-blake3-aes-128-gcm",
                        "server_key": "sync-fixture-key"
                      },
                      "show": true,
                      "enabled": true
                    }
                    """.formatted(port, machineId, groupId, port, port)))
            .andExpect(status().isOk())
            .andReturn();
        return ((Number) JsonPath.read(
            node.getResponse().getContentAsString(),
            "$.data.id"
        )).longValue();
    }

    private String placeMonthlyOrder(String accessToken, UUID planId)
        throws Exception {
        MvcResult placed = mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { placeOrder(planId: \\"%s\\", period: MONTHLY) { tradeNo status } }"}
                    """.formatted(planId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.placeOrder.status").value("PENDING"))
            .andReturn();
        return JsonPath.read(
            placed.getResponse().getContentAsString(),
            "$.data.placeOrder.tradeNo"
        );
    }

    private List<Integer> usersOf(
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

    private Registered register(String email) throws Exception {
        // The per-IP registration window is shared across the class's
        // methods; a fresh slate per registration keeps the fixture honest.
        redisTemplate.delete(redisTemplate.keys("identity:registration-ip:*"));
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
                      "password": "entitlement-sync-password",
                      "displayName": "Entitlement Sync",
                      "deviceLabel": "Sync Fixture Browser",
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
            )
        );
    }

    private record Registered(UUID userId, String accessToken) {
    }

    private record Fixture(
        String accessToken,
        UUID userId,
        UUID planId,
        long machineId,
        String machineToken,
        long servedNodeId,
        long otherNodeId
    ) {
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
