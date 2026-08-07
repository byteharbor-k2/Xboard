package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Duration;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.notification.email.PasswordResetMailSender;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.identity.security.TotpService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfrastructureIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class InfrastructureIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_test")
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

    @Autowired
    private RecordingPasswordResetMailSender passwordResetMailSender;

    @Autowired
    private TotpService totpService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private PlatformConfigurationService platformConfiguration;

    @Test
    void startsWithMigratedPostgresAndReachableRedis() {
        String schemaVersion = jdbcTemplate.queryForObject(
            "SELECT metadata_value FROM platform_metadata WHERE metadata_key = ?",
            String.class,
            "schema_version"
        );

        redisTemplate.opsForValue().set("sinx:test:health", "ok", Duration.ofSeconds(10));

        assertThat(schemaVersion).isEqualTo("1");
        assertThat(redisTemplate.opsForValue().get("sinx:test:health")).isEqualTo("ok");
    }

    @Test
    void managesMachinesAndSpeaksTheXboardNodeMachineProtocol()
        throws Exception {
        String name = "Machine " + UUID.randomUUID();
        var administrator = jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );

        mockMvc.perform(get("/api/v2/admin/server/machine/fetch"))
            .andExpect(status().isUnauthorized());

        MvcResult created = mockMvc.perform(
                post("/api/v2/admin/server/machine/save")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name":"%s",
                          "notes":"xboard-node integration",
                          "is_active":true
                        }
                        """.formatted(name)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isNumber())
            .andExpect(jsonPath("$.data.token").isString())
            .andExpect(jsonPath("$.data.install_command").value(
                org.hamcrest.Matchers.containsString("--mode machine")
            ))
            .andReturn();

        Number machineIdNumber = JsonPath.read(
            created.getResponse().getContentAsString(),
            "$.data.id"
        );
        long machineId = machineIdNumber.longValue();
        String token = JsonPath.read(
            created.getResponse().getContentAsString(),
            "$.data.token"
        );

        mockMvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"wrong-token"}
                    """.formatted(machineId)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v2/server/handshake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"%s"}
                    """.formatted(machineId, token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.websocket.enabled").value(true))
            .andExpect(jsonPath("$.websocket.ws_url").value("ws://localhost/ws"))
            .andExpect(jsonPath("$.settings.push_interval").value(60));

        mockMvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"%s"}
                    """.formatted(machineId, token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes").isEmpty())
            .andExpect(jsonPath("$.base_config.pull_interval").value(60));

        mockMvc.perform(post("/api/v2/server/machine/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "machine_id":%d,
                      "token":"%s",
                      "cpu":12.5,
                      "mem":{"total":1024,"used":512},
                      "swap":{"total":256,"used":32},
                      "disk":{"total":4096,"used":1024},
                      "net":{"in_speed":123.5,"out_speed":456.5}
                    }
                    """.formatted(machineId, token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/v2/admin/server/machine/fetch")
                .with(administrator))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == %d)].name"
                .formatted(machineId)).value(name))
            .andExpect(jsonPath("$.data[?(@.id == %d)].load_status.cpu"
                .formatted(machineId)).value(12.5));

        mockMvc.perform(get("/api/v2/admin/server/machine/history")
                .param("machine_id", Long.toString(machineId))
                .with(administrator))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].cpu").value(12.5))
            .andExpect(jsonPath("$.data[0].net_out_speed").value(456.5));

        MvcResult rotated = mockMvc.perform(
                post("/api/v2/admin/server/machine/resetToken")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"id":%d}
                        """.formatted(machineId)))
            .andExpect(status().isOk())
            .andReturn();
        String rotatedToken = JsonPath.read(
            rotated.getResponse().getContentAsString(),
            "$.data.token"
        );
        assertThat(rotatedToken).isNotEqualTo(token);

        mockMvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"%s"}
                    """.formatted(machineId, token)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v2/admin/server/machine/save")
                .with(administrator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id":%d,
                      "name":"%s",
                      "notes":"disabled for test",
                      "is_active":false
                    }
                    """.formatted(machineId, name)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"%s"}
                    """.formatted(machineId, rotatedToken)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v2/admin/server/machine/drop")
                .with(administrator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":%d}
                    """.formatted(machineId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void managesNodesAndSpeaksTheXboardNodePerNodeProtocol() throws Exception {
        var administrator = jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
        MvcResult machineResult = mockMvc.perform(post("/api/v2/admin/server/machine/save")
                .with(administrator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Node host %s","is_active":true}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andReturn();
        long machineId = ((Number) JsonPath.read(
            machineResult.getResponse().getContentAsString(), "$.data.id"
        )).longValue();
        String token = JsonPath.read(machineResult.getResponse().getContentAsString(), "$.data.token");

        MvcResult otherMachineResult = mockMvc.perform(post("/api/v2/admin/server/machine/save")
                .with(administrator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Other host %s","is_active":true}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andReturn();
        long otherMachineId = ((Number) JsonPath.read(
            otherMachineResult.getResponse().getContentAsString(), "$.data.id"
        )).longValue();
        String otherToken = JsonPath.read(otherMachineResult.getResponse().getContentAsString(), "$.data.token");

        MvcResult nodeResult = mockMvc.perform(post("/api/v2/admin/server/manage/save")
                .with(administrator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type":"shadowsocks",
                      "name":"SS integration",
                      "machine_id":%d,
                      "host":"node.example.test",
                      "port":8443,
                      "server_port":8443,
                      "rate":1.5,
                      "transfer_enable":1099511627776,
                      "protocol_settings":{
                        "network":"tcp",
                        "cipher":"2022-blake3-aes-128-gcm",
                        "server_key":"integration-key"
                      },
                      "show":true,
                      "enabled":true
                    }
                    """.formatted(machineId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.type").value("shadowsocks"))
            .andExpect(jsonPath("$.data.machine_id").value(machineId))
            .andReturn();
        long nodeId = ((Number) JsonPath.read(
            nodeResult.getResponse().getContentAsString(), "$.data.id"
        )).longValue();

        mockMvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"%s"}
                    """.formatted(machineId, token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes[0].id").value(nodeId))
            .andExpect(jsonPath("$.nodes[0].type").value("shadowsocks"));

        mockMvc.perform(get("/api/v2/server/config")
                .param("machine_id", Long.toString(otherMachineId))
                .param("node_id", Long.toString(nodeId))
                .param("token", otherToken))
            .andExpect(status().isForbidden());

        MvcResult config = mockMvc.perform(get("/api/v2/server/config")
                .param("machine_id", Long.toString(machineId))
                .param("node_id", Long.toString(nodeId))
                .param("token", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.protocol").value("shadowsocks"))
            .andExpect(jsonPath("$.listen_ip").value("0.0.0.0"))
            .andExpect(jsonPath("$.server_port").value(8443))
            .andExpect(jsonPath("$.cipher").value("2022-blake3-aes-128-gcm"))
            .andExpect(jsonPath("$.base_config.pull_interval").value(60))
            .andReturn();
        String configEtag = config.getResponse().getHeader("ETag");
        assertThat(configEtag).isNotBlank();

        mockMvc.perform(get("/api/v2/server/config")
                .param("machine_id", Long.toString(machineId))
                .param("node_id", Long.toString(nodeId))
                .param("token", token)
                .header("If-None-Match", configEtag))
            .andExpect(status().isNotModified());

        MvcResult users = mockMvc.perform(get("/api/v2/server/user")
                .param("machine_id", Long.toString(machineId))
                .param("node_id", Long.toString(nodeId))
                .param("token", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.users").isEmpty())
            .andReturn();
        assertThat(users.getResponse().getHeader("ETag")).isNotBlank();

        mockMvc.perform(post("/api/v2/server/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "machine_id":%d,
                      "node_id":%d,
                      "token":"%s",
                      "traffic":{"101":[1024,2048]},
                      "alive":{"101":["198.51.100.10"]},
                      "online":{"101":2},
                      "status":{"cpu":15.5,"mem":{"total":1024,"used":256}},
                      "metrics":{"active_connections":2}
                    }
                    """.formatted(machineId, nodeId, token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/v2/admin/server/manage/getNodes").with(administrator))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == %d)].u".formatted(nodeId)).value(1024))
            .andExpect(jsonPath("$.data[?(@.id == %d)].d".formatted(nodeId)).value(2048))
            .andExpect(jsonPath("$.data[?(@.id == %d)].online_conn".formatted(nodeId)).value(2));

        mockMvc.perform(post("/api/v2/admin/server/manage/update")
                .with(administrator)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":%d,"enabled":false}
                    """.formatted(nodeId)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":%d,"token":"%s"}
                    """.formatted(machineId, token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes").isEmpty());

        mockMvc.perform(post("/api/v2/admin/server/manage/drop")
                .with(administrator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":%d}".formatted(nodeId)))
            .andExpect(status().isOk());
        for (long id : List.of(machineId, otherMachineId)) {
            mockMvc.perform(post("/api/v2/admin/server/machine/drop")
                    .with(administrator).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":%d}".formatted(id)))
                .andExpect(status().isOk());
        }
    }

    @Test
    void managesPlansThroughCustomControlPlaneAndFiltersPublicCatalog()
        throws Exception {
        String name = "Catalog " + UUID.randomUUID();
        String planId = null;
        var administrator = jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
        try {
            mockMvc.perform(get("/control/catalog/plans"))
                .andExpect(status().isUnauthorized());

            MvcResult created = mockMvc.perform(post("/control/catalog/plans")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name":"%s",
                          "description":"Managed catalog integration plan",
                          "planType":"SUBSCRIPTION",
                          "transferLimitBytes":"107374182400",
                          "speedLimitMbps":200,
                          "deviceLimit":5,
                          "resetPolicy":"MONTHLY_FROM_ACTIVATION",
                          "capacityLimit":20,
                          "resettable":true,
                          "purchaseLimitPerUser":null,
                          "published":false,
                          "sellable":true,
                          "renewable":true,
                          "sortOrder":10,
                          "tags":["integration","featured"],
                          "prices":[
                            {
                              "period":"MONTHLY",
                              "amountMinor":1999,
                              "currency":"cny"
                            },
                            {
                              "period":"RESET_TRAFFIC",
                              "amountMinor":699,
                              "currency":"cny"
                            }
                          ]
                        }
                        """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.planType").value("SUBSCRIPTION"))
                .andExpect(jsonPath("$.resettable").value(true))
                .andExpect(jsonPath("$.prices.length()").value(2))
                .andExpect(jsonPath("$.prices[0].currency").value("CNY"))
                .andReturn();
            planId = JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.id"
            );

            MvcResult hiddenCatalog = mockMvc.perform(post("/gateway")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"query":"{ offerCatalog { id name prices { period } } }"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
            List<String> hiddenNames = JsonPath.read(
                hiddenCatalog.getResponse().getContentAsString(),
                "$.data.offerCatalog[*].name"
            );
            assertThat(hiddenNames).doesNotContain(name);

            mockMvc.perform(put("/control/catalog/plans/{id}", planId)
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name":"%s",
                          "description":"Published integration plan",
                          "planType":"SUBSCRIPTION",
                          "transferLimitBytes":"107374182400",
                          "speedLimitMbps":200,
                          "deviceLimit":5,
                          "resetPolicy":"MONTHLY_FROM_ACTIVATION",
                          "capacityLimit":20,
                          "resettable":true,
                          "purchaseLimitPerUser":null,
                          "published":true,
                          "sellable":true,
                          "renewable":true,
                          "sortOrder":10,
                          "tags":["integration"],
                          "prices":[
                            {
                              "period":"MONTHLY",
                              "amountMinor":2199,
                              "currency":"CNY"
                            },
                            {
                              "period":"RESET_TRAFFIC",
                              "amountMinor":799,
                              "currency":"CNY"
                            }
                          ]
                        }
                        """.formatted(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.prices[0].amountMinor").value(2199));

            MvcResult publicCatalog = mockMvc.perform(post("/gateway")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "query":"{ offerCatalog { id name transferLimitBytes prices { period amountMinor currency } } }"
                        }
                        """))
                .andExpect(status().isOk())
                .andReturn();
            List<String> publicNames = JsonPath.read(
                publicCatalog.getResponse().getContentAsString(),
                "$.data.offerCatalog[*].name"
            );
            assertThat(publicNames).contains(name);

            mockMvc.perform(delete("/control/catalog/plans/{id}", planId)
                    .with(administrator))
                .andExpect(status().isNoContent());
            planId = null;
        } finally {
            if (planId != null) {
                jdbcTemplate.update(
                    "DELETE FROM service_plans WHERE id = ?::uuid",
                    planId
                );
            }
        }
    }

    @Test
    void managesNonExpiringTrafficPackagesWithResetAndPurchaseLimits()
        throws Exception {
        String name = "Traffic package " + UUID.randomUUID();
        String planId = null;
        var administrator = jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
        try {
            MvcResult created = mockMvc.perform(post("/control/catalog/plans")
                    .with(administrator)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name":"%s",
                          "description":"A non-expiring data package",
                          "planType":"TRAFFIC_PACKAGE",
                          "transferLimitBytes":"53687091200",
                          "speedLimitMbps":100,
                          "deviceLimit":3,
                          "resetPolicy":"MONTHLY_FROM_ACTIVATION",
                          "capacityLimit":100,
                          "resettable":true,
                          "purchaseLimitPerUser":2,
                          "published":true,
                          "sellable":true,
                          "renewable":true,
                          "sortOrder":20,
                          "tags":["package"],
                          "prices":[
                            {
                              "period":"ONETIME",
                              "amountMinor":1500,
                              "currency":"CNY"
                            },
                            {
                              "period":"RESET_TRAFFIC",
                              "amountMinor":500,
                              "currency":"CNY"
                            }
                          ]
                        }
                        """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planType").value("TRAFFIC_PACKAGE"))
                .andExpect(jsonPath("$.resetPolicy").value("NEVER"))
                .andExpect(jsonPath("$.resettable").value(true))
                .andExpect(jsonPath("$.purchaseLimitPerUser").value(2))
                .andExpect(jsonPath("$.renewable").value(false))
                .andExpect(jsonPath("$.prices.length()").value(2))
                .andReturn();
            planId = JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.id"
            );

            MvcResult catalog = mockMvc.perform(post("/gateway")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "query":"{ offerCatalog { name planType resettable purchaseLimitPerUser prices { period } } }"
                        }
                        """))
                .andExpect(status().isOk())
                .andReturn();
            List<String> names = JsonPath.read(
                catalog.getResponse().getContentAsString(),
                "$.data.offerCatalog[*].name"
            );
            assertThat(names).contains(name);

            mockMvc.perform(delete("/control/catalog/plans/{id}", planId)
                    .with(administrator))
                .andExpect(status().isNoContent());
            planId = null;
        } finally {
            if (planId != null) {
                jdbcTemplate.update(
                    "DELETE FROM service_plans WHERE id = ?::uuid",
                    planId
                );
            }
        }
    }

    @Test
    void exposesAdministratorConfiguredTermsUrlDuringRegistration()
        throws Exception {
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "site")
                .with(jwt().authorities(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_ADMIN")
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"tos_url":"https://www.sinx.it.com/legal/terms"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "site")
                .with(jwt().authorities(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_ADMIN")
                )))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.site.tos_url").value(
                "https://www.sinx.it.com/legal/terms"
            ));

        mockMvc.perform(get("/session/registration/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.termsUrl").value(
                "https://www.sinx.it.com/legal/terms"
            ));

        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "site")
                .with(jwt().authorities(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_ADMIN")
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"tos_url":"javascript:alert(1)"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TERMS_URL_INVALID"));
    }

    @Test
    void enforcesAdministratorConfiguredRegistrationEmailDomains()
        throws Exception {
        try {
            mockMvc.perform(post("/api/v2/admin/config/save")
                    .param("key", "safe")
                    .with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_ADMIN")
                    ))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email_whitelist_suffix": [
                            "gmail.com",
                            "@QQ.COM",
                            "gmail.com"
                          ]
                        }
                        """))
                .andExpect(status().isOk());

            mockMvc.perform(post("/api/v2/admin/config/save")
                    .param("key", "safe")
                    .with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_ADMIN")
                    ))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email_whitelist_enable":true}
                        """))
                .andExpect(status().isOk());

            mockMvc.perform(get("/api/v2/admin/config/fetch")
                    .param("key", "safe")
                    .with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_ADMIN")
                    )))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.data.safe.email_whitelist_enable"
                ).value(true))
                .andExpect(jsonPath(
                    "$.data.safe.email_whitelist_suffix[0]"
                ).value("gmail.com"))
                .andExpect(jsonPath(
                    "$.data.safe.email_whitelist_suffix[1]"
                ).value("qq.com"))
                .andExpect(jsonPath(
                    "$.data.safe.email_whitelist_suffix.length()"
                ).value(2));

            mockMvc.perform(get("/session/registration/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.emailDomainAllowlistEnabled"
                ).value(true))
                .andExpect(jsonPath(
                    "$.allowedEmailDomains[0]"
                ).value("gmail.com"))
                .andExpect(jsonPath(
                    "$.allowedEmailDomains[1]"
                ).value("qq.com"));

            mockMvc.perform(post("/session/registration/email-code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"blocked@example.net"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                    "EMAIL_DOMAIN_NOT_ALLOWED"
                ));

            mockMvc.perform(
                    post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email":"blocked@example.net",
                          "password":"blocked-domain-password",
                          "displayName":"Blocked Domain",
                          "emailCode":"000000"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                    "EMAIL_DOMAIN_NOT_ALLOWED"
                ));
        } finally {
            jdbcTemplate.update(
                "DELETE FROM platform_settings WHERE setting_key LIKE 'safe.%'"
            );
        }
    }

    @Test
    void configuresEmailVerificationAndTurnstileFromAdministratorSettings()
        throws Exception {
        try {
            mockMvc.perform(post("/api/v2/admin/config/save")
                    .param("key", "safe")
                    .with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_ADMIN")
                    ))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email_verify":false}
                        """))
                .andExpect(status().isOk());

            mockMvc.perform(get("/session/registration/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.emailVerificationRequired"
                ).value(false));

            mockMvc.perform(post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email":"optional-verification@example.com",
                          "password":"optional-verification-password",
                          "displayName":"Optional Verification"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewer.emailVerified").value(false));

            saveSafeSetting("captcha_type", "\"turnstile\"");
            saveSafeSetting(
                "turnstile_site_key",
                "\"0x4AAAAAA-test-site-key\""
            );
            saveSafeSetting(
                "turnstile_secret_key",
                "\"0x4AAAAAA-test-secret-key\""
            );
            saveSafeSetting("captcha_enable", "true");
            saveSafeSetting("email_verify", "true");

            mockMvc.perform(get("/api/v2/admin/config/fetch")
                    .param("key", "safe")
                    .with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_ADMIN")
                    )))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.data.safe.captcha_enable"
                ).value(true))
                .andExpect(jsonPath(
                    "$.data.safe.captcha_type"
                ).value("turnstile"))
                .andExpect(jsonPath(
                    "$.data.safe.turnstile_site_key"
                ).value("0x4AAAAAA-test-site-key"))
                .andExpect(jsonPath(
                    "$.data.safe.turnstile_secret_key"
                ).value(""));

            mockMvc.perform(get("/session/registration/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnstileEnabled").value(true))
                .andExpect(jsonPath("$.turnstileSiteKey").value(
                    "0x4AAAAAA-test-site-key"
                ))
                .andExpect(jsonPath("$.turnstileSecretKey").doesNotExist());

            mockMvc.perform(post("/session/registration/email-code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email":"turnstile-required@example.com"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TURNSTILE_INVALID"));
        } finally {
            jdbcTemplate.update(
                "DELETE FROM platform_settings WHERE setting_key LIKE 'safe.%'"
            );
        }
    }

    @Test
    void appliesOriginalXboardInvitationRulesDuringRegistration()
        throws Exception {
        UUID inviterId = UUID.randomUUID();
        UUID inviteCodeId = UUID.randomUUID();
        Instant now = Instant.now();
        redisTemplate.delete(
            redisTemplate.keys("identity:registration-ip:*")
        );
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                created_at, updated_at
            ) VALUES (?::uuid, ?, ?, ?, 'ACTIVE', ?, ?)
            """,
            inviterId.toString(),
            "inviter@example.com",
            "not-used-for-login",
            "Inviter",
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbcTemplate.update(
            """
            INSERT INTO invite_codes (
                id, user_id, code, created_at, updated_at
            ) VALUES (?::uuid, ?::uuid, ?, ?, ?)
            """,
            inviteCodeId.toString(),
            inviterId.toString(),
            "SINXTEST",
            Timestamp.from(now),
            Timestamp.from(now)
        );

        try {
            saveSetting("safe", "email_verify", "false");
            saveSetting("invite", "invite_force", "true");
            saveSetting("invite", "invite_never_expire", "false");

            mockMvc.perform(get("/session/registration/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invitationRequired").value(true));

            mockMvc.perform(post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email":"invite-required@example.com",
                          "password":"invitation-required-password",
                          "displayName":"Missing Invitation"
                        }
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVITATION_REQUIRED"));

            MvcResult invitedRegistration = mockMvc.perform(post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email":"invited@example.com",
                          "password":"invited-account-password",
                          "displayName":"Invited Account",
                          "inviteCode":"sinxtest"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();
            String invitedAccessToken = JsonPath.read(
                invitedRegistration.getResponse().getContentAsString(),
                "$.accessToken"
            );

            assertThat(jdbcTemplate.queryForObject(
                "SELECT inviter_user_id FROM users WHERE email = ?",
                UUID.class,
                "invited@example.com"
            )).isEqualTo(inviterId);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT used_at FROM invite_codes WHERE id = ?::uuid",
                Timestamp.class,
                inviteCodeId.toString()
            )).isNotNull();

            mockMvc.perform(post("/session/invitations")
                    .header(
                        "Authorization",
                        "Bearer " + invitedAccessToken
                    ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString());
            mockMvc.perform(get("/session/invitations")
                    .header(
                        "Authorization",
                        "Bearer " + invitedAccessToken
                    ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").isString());

            mockMvc.perform(post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email":"reused-invite@example.com",
                          "password":"reused-invitation-password",
                          "displayName":"Reused Invitation",
                          "inviteCode":"SINXTEST"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));

            saveSetting("invite", "invite_force", "false");
            mockMvc.perform(post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email":"optional-invalid-invite@example.com",
                          "password":"optional-invitation-password",
                          "displayName":"Optional Invitation",
                          "inviteCode":"DOES-NOT-EXIST"
                        }
                        """))
                .andExpect(status().isCreated());
        } finally {
            jdbcTemplate.update(
                """
                DELETE FROM users
                WHERE email IN (
                    'invite-required@example.com',
                    'invited@example.com',
                    'reused-invite@example.com',
                    'optional-invalid-invite@example.com',
                    'inviter@example.com'
                )
                """
            );
            jdbcTemplate.update(
                "DELETE FROM platform_settings WHERE setting_key LIKE 'invite.%'"
            );
            jdbcTemplate.update(
                "DELETE FROM platform_settings WHERE setting_key = 'safe.email_verify'"
            );
            redisTemplate.delete(
                redisTemplate.keys("identity:registration-ip:*")
            );
        }
    }

    @Test
    void storesSmtpConfigurationWithoutReturningThePassword()
        throws Exception {
        try {
            saveSetting("email", "email_host", "\"smtp.resend.com\"");
            saveSetting("email", "email_port", "587");
            saveSetting("email", "email_encryption", "\"tls\"");
            saveSetting("email", "email_username", "\"resend\"");
            saveSetting(
                "email",
                "email_password",
                "\"smtp-password-secret\""
            );
            saveSetting(
                "email",
                "email_from_address",
                "\"noreply@app.sinx.it.com\""
            );

            mockMvc.perform(get("/api/v2/admin/config/fetch")
                    .param("key", "email")
                    .with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_ADMIN")
                    )))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                    "$.data.email.email_host"
                ).value("smtp.resend.com"))
                .andExpect(jsonPath("$.data.email.email_port").value(587))
                .andExpect(jsonPath(
                    "$.data.email.email_encryption"
                ).value("tls"))
                .andExpect(jsonPath(
                    "$.data.email.email_password"
                ).value(""));

            assertThat(platformConfiguration.mailSettings().password())
                .isEqualTo("smtp-password-secret");
            saveSetting("email", "email_password", "\"\"");
            assertThat(platformConfiguration.mailSettings().password())
                .isEqualTo("smtp-password-secret");
        } finally {
            jdbcTemplate.update(
                "DELETE FROM platform_settings WHERE setting_key LIKE 'email.%'"
            );
        }
    }

    @Test
    void refusesToCreateAnAccountBeforeEmailCodeVerification()
        throws Exception {
        String email = "registration-guard@example.com";
        String code = requestRegistrationCode(email);

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"registration-guard@example.com",
                      "password":"registration-guard-password",
                      "displayName":"Registration Guard",
                      "emailCode":"000000"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(
                "REGISTRATION_EMAIL_CODE_INVALID"
            ));
        Integer countBeforeVerification = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?",
            Integer.class,
            email
        );
        assertThat(countBeforeVerification).isZero();

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"registration-guard@example.com",
                      "password":"registration-guard-password",
                      "displayName":"Registration Guard",
                      "emailCode":"%s"
                    }
                    """.formatted(code)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.viewer.emailVerified").value(true));
    }

    @Test
    void rotatesRefreshSessionsAndRejectsReplayAfterLogout() throws Exception {
        String registrationCode = requestRegistrationCode(
            "Identity.Test@Example.com"
        );
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "Identity.Test@Example.com",
                      "password": "correct-horse-battery-staple",
                      "displayName": "Identity Test",
                      "deviceLabel": "Integration Test",
                      "emailCode": "%s"
                    }
                    """.formatted(registrationCode)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.viewer.email").value("identity.test@example.com"))
            .andExpect(jsonPath("$.viewer.roles[0]").value("USER"))
            .andReturn();

        String registrationBody = registration.getResponse().getContentAsString();
        String accessToken = JsonPath.read(registrationBody, "$.accessToken");
        Cookie originalRefreshCookie = registration.getResponse().getCookie("rt_session");
        assertThat(originalRefreshCookie).isNotNull();
        assertThat(originalRefreshCookie.isHttpOnly()).isTrue();
        assertThat(JsonPath.<Boolean>read(
            registrationBody,
            "$.viewer.emailVerified"
        )).isTrue();

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "identity.test@example.com",
                      "password": "another-valid-password",
                      "displayName": "Duplicate",
                      "emailCode": "000000"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "identity.test@example.com",
                      "password": "incorrect-password"
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewer { email displayName emailVerified roles } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewer.email").value("identity.test@example.com"))
            .andExpect(jsonPath("$.data.viewer.displayName").value("Identity Test"))
            .andExpect(jsonPath("$.data.viewer.emailVerified").value(true));

        MvcResult refresh = mockMvc.perform(post("/session/refresh")
                .cookie(originalRefreshCookie))
            .andExpect(status().isOk())
            .andReturn();
        Cookie rotatedRefreshCookie = refresh.getResponse().getCookie("rt_session");
        assertThat(rotatedRefreshCookie).isNotNull();
        assertThat(rotatedRefreshCookie.getValue())
            .isNotEqualTo(originalRefreshCookie.getValue());

        mockMvc.perform(post("/session/refresh").cookie(originalRefreshCookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/session/refresh").cookie(rotatedRefreshCookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        MvcResult login = mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "identity.test@example.com",
                      "password": "correct-horse-battery-staple",
                      "deviceLabel": "Second Session"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String loginBody = login.getResponse().getContentAsString();
        String secondSessionId = JsonPath.read(loginBody, "$.sessionId");
        Cookie secondRefreshCookie = login.getResponse().getCookie("rt_session");
        assertThat(secondRefreshCookie).isNotNull();

        MvcResult currentLogin = mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "identity.test@example.com",
                      "password": "correct-horse-battery-staple",
                      "deviceLabel": "Current Browser"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String currentLoginBody = currentLogin.getResponse().getContentAsString();
        String currentAccessToken = JsonPath.read(
            currentLoginBody,
            "$.accessToken"
        );
        Cookie currentRefreshCookie = currentLogin
            .getResponse()
            .getCookie("rt_session");
        assertThat(currentRefreshCookie).isNotNull();

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + currentAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ deviceSessions { id deviceLabel current } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deviceSessions.length()").value(2))
            .andExpect(jsonPath(
                "$.data.deviceSessions[?(@.deviceLabel == 'Current Browser')].current"
            ).value(true));

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + currentAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { revokeDeviceSession(id: \\"%s\\") }"}
                    """.formatted(secondSessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.data.revokeDeviceSession"
            ).value(true));

        mockMvc.perform(post("/session/refresh").cookie(secondRefreshCookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(delete("/session/current").cookie(currentRefreshCookie))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/session/refresh").cookie(currentRefreshCookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void updatesProfileChangesPasswordAndCompletesPasswordReset()
        throws Exception {
        String registrationCode = requestRegistrationCode(
            "account-settings@example.com"
        );
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "account-settings@example.com",
                      "password": "initial-password-123",
                      "displayName": "Account Settings",
                      "deviceLabel": "Account Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(registrationCode)))
            .andExpect(status().isCreated())
            .andReturn();
        String registrationBody =
            registration.getResponse().getContentAsString();
        String accessToken = JsonPath.read(
            registrationBody,
            "$.accessToken"
        );

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query":"mutation($displayName:String!){ updateViewerProfile(displayName:$displayName){ displayName email } }",
                      "variables":{"displayName":"Updated Account"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.data.updateViewerProfile.displayName"
            ).value("Updated Account"));

        mockMvc.perform(put("/session/password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "currentPassword":"initial-password-123",
                      "newPassword":"changed-password-456"
                    }
                    """))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"account-settings@example.com",
                      "password":"initial-password-123"
                    }
                    """))
            .andExpect(status().isUnauthorized());

        MvcResult changedLogin = mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"account-settings@example.com",
                      "password":"changed-password-456",
                      "deviceLabel":"Password Reset Browser"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        Cookie refreshBeforeReset =
            changedLogin.getResponse().getCookie("rt_session");
        assertThat(refreshBeforeReset).isNotNull();

        mockMvc.perform(post("/session/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"account-settings@example.com"}
                    """))
            .andExpect(status().isAccepted());
        String resetToken = passwordResetMailSender.latestToken();

        mockMvc.perform(post("/session/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"unknown-account@example.com"}
                    """))
            .andExpect(status().isAccepted());

        mockMvc.perform(post("/session/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "token":"%s",
                      "newPassword":"reset-password-789"
                    }
                    """.formatted(resetToken)))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/session/refresh").cookie(refreshBeforeReset))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"account-settings@example.com",
                      "password":"reset-password-789"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void exposesAvailablePlansAndTheUsersSubscriptionEntitlement()
        throws Exception {
        String registrationCode = requestRegistrationCode(
            "catalog-member@example.com"
        );
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email":"catalog-member@example.com",
                      "password":"catalog-member-password",
                      "displayName":"Catalog Member",
                      "deviceLabel":"Catalog Browser",
                      "emailCode":"%s"
                    }
                    """.formatted(registrationCode)))
            .andExpect(status().isCreated())
            .andReturn();
        String registrationBody =
            registration.getResponse().getContentAsString();
        String userId = JsonPath.read(registrationBody, "$.viewer.id");
        String accessToken = JsonPath.read(
            registrationBody,
            "$.accessToken"
        );

        UUID planId = UUID.randomUUID();
        UUID entitlementId = UUID.randomUUID();
        Instant now = Instant.now();
        long transferLimit = 100L * 1024 * 1024 * 1024;
        long uploaded = 8L * 1024 * 1024 * 1024;
        long downloaded = 12L * 1024 * 1024 * 1024;
        jdbcTemplate.update(
            """
            INSERT INTO service_plans (
                id, name, description, transfer_limit_bytes,
                speed_limit_mbps, device_limit, reset_policy,
                capacity_limit, published, sellable, renewable,
                sort_order, created_at, updated_at
            ) VALUES (
                ?::uuid, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, TRUE,
                1, ?, ?
            )
            """,
            planId.toString(),
            "Basic",
            "Suitable for everyday browsing.",
            transferLimit,
            200,
            5,
            "MONTHLY_FROM_ACTIVATION",
            10,
            Timestamp.from(now),
            Timestamp.from(now)
        );
        jdbcTemplate.update(
            """
            INSERT INTO service_plan_tags (plan_id, position, label)
            VALUES (?::uuid, 0, 'Popular')
            """,
            planId.toString()
        );
        jdbcTemplate.update(
            """
            INSERT INTO service_plan_prices (
                id, plan_id, billing_period, amount_minor, currency
            ) VALUES (?::uuid, ?::uuid, 'MONTHLY', 1200, 'CNY')
            """,
            UUID.randomUUID().toString(),
            planId.toString()
        );
        jdbcTemplate.update(
            """
            INSERT INTO subscription_entitlements (
                id, user_id, plan_id, plan_name,
                transfer_limit_bytes, uploaded_bytes, downloaded_bytes,
                speed_limit_mbps, device_limit, reset_policy,
                starts_at, expires_at, next_reset_at,
                created_at, updated_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?
            )
            """,
            entitlementId.toString(),
            userId,
            planId.toString(),
            "Basic",
            transferLimit,
            uploaded,
            downloaded,
            200,
            5,
            "MONTHLY_FROM_ACTIVATION",
            Timestamp.from(now.minus(Duration.ofDays(3))),
            Timestamp.from(now.plus(Duration.ofDays(27))),
            Timestamp.from(now.plus(Duration.ofDays(27))),
            Timestamp.from(now),
            Timestamp.from(now)
        );

        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query":"{ offerCatalog { id name tags transferLimitBytes capacityRemaining prices { period amountMinor currency monthCount } } }"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.offerCatalog.length()").value(1))
            .andExpect(jsonPath("$.data.offerCatalog[0].name").value("Basic"))
            .andExpect(jsonPath(
                "$.data.offerCatalog[0].capacityRemaining"
            ).value(9))
            .andExpect(jsonPath(
                "$.data.offerCatalog[0].prices[0].amountMinor"
            ).value("1200"));

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query":"{ viewerEntitlement { planName state transferLimitBytes uploadedBytes downloadedBytes usedBytes remainingBytes usagePercent speedLimitMbps deviceLimit resetPolicy expiresAt nextResetAt } }"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.data.viewerEntitlement.planName"
            ).value("Basic"))
            .andExpect(jsonPath(
                "$.data.viewerEntitlement.state"
            ).value("ACTIVE"))
            .andExpect(jsonPath(
                "$.data.viewerEntitlement.usedBytes"
            ).value(Long.toString(uploaded + downloaded)))
            .andExpect(jsonPath(
                "$.data.viewerEntitlement.remainingBytes"
            ).value(Long.toString(
                transferLimit - uploaded - downloaded
            )))
            .andExpect(jsonPath(
                "$.data.viewerEntitlement.usagePercent"
            ).value(20.0));
    }

    @Test
    void separatesUserAndAdministratorSessionsAndRequiresAdminMfa()
        throws Exception {
        String registrationCode = requestRegistrationCode(
            "mfa-admin@example.com"
        );
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "mfa-admin@example.com",
                      "password": "mfa-admin-password",
                      "displayName": "MFA Admin",
                      "deviceLabel": "User Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(registrationCode)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.viewer.roles[0]").value("USER"))
            .andReturn();
        String userId = JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.viewer.id"
        );
        jdbcTemplate.update(
            """
            INSERT INTO user_roles (user_id, role_code)
            VALUES (?::uuid, 'ADMIN')
            """,
            userId
        );

        MvcResult userLogin = mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "mfa-admin@example.com",
                      "password": "mfa-admin-password",
                      "deviceLabel": "Second User Browser"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewer.roles.length()").value(1))
            .andExpect(jsonPath("$.viewer.roles[0]").value("USER"))
            .andReturn();
        String userAccessToken = JsonPath.read(
            userLogin.getResponse().getContentAsString(),
            "$.accessToken"
        );
        Cookie userRefreshCookie = userLogin.getResponse()
            .getCookie("rt_session");
        assertThat(userRefreshCookie).isNotNull();
        assertThat(jwtDecoder.decode(userAccessToken).getAudience())
            .containsExactly("sinx-web");
        assertThat(jwtDecoder.decode(userAccessToken).getClaimAsString("scope"))
            .isEqualTo("USER");

        mockMvc.perform(get("/admin-session/mfa")
                .header("Authorization", "Bearer " + userAccessToken))
            .andExpect(status().isForbidden());

        MvcResult enrollmentRequired = mockMvc.perform(
                post("/admin-session/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "mfa-admin@example.com",
                          "password": "mfa-admin-password",
                          "deviceLabel": "Admin Browser"
                        }
                        """)
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true))
            .andExpect(jsonPath("$.mfaRequired").value(false))
            .andReturn();
        assertThat(enrollmentRequired.getResponse().getCookie("rt_admin"))
            .isNull();
        String enrollmentToken = JsonPath.read(
            enrollmentRequired.getResponse().getContentAsString(),
            "$.enrollmentToken"
        );

        MvcResult enrollment = mockMvc.perform(
                post("/admin-session/enrollment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"enrollmentToken":"%s"}
                        """.formatted(enrollmentToken))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.otpauthUri").value(
                org.hamcrest.Matchers.startsWith("otpauth://totp/")
            ))
            .andReturn();
        String secret = JsonPath.read(
            enrollment.getResponse().getContentAsString(),
            "$.secret"
        );
        String confirmationCode = totpService.currentCode(secret);

        MvcResult confirmation = mockMvc.perform(
                post("/admin-session/enrollment/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "enrollmentToken":"%s",
                          "code":"%s"
                        }
                        """.formatted(
                            enrollmentToken,
                            confirmationCode
                        ))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recoveryCodes.length()").value(8))
            .andReturn();
        List<String> recoveryCodes = JsonPath.read(
            confirmation.getResponse().getContentAsString(),
            "$.recoveryCodes"
        );

        MvcResult challengedLogin = mockMvc.perform(
                post("/admin-session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "mfa-admin@example.com",
                      "password": "mfa-admin-password",
                      "deviceLabel": "MFA Browser"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.mfaRequired").value(true))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn();
        assertThat(challengedLogin.getResponse().getCookie("rt_session"))
            .isNull();
        assertThat(challengedLogin.getResponse().getCookie("rt_admin"))
            .isNull();
        String challengeToken = JsonPath.read(
            challengedLogin.getResponse().getContentAsString(),
            "$.challengeToken"
        );

        MvcResult completedLogin = mockMvc.perform(
                post("/admin-session/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "challengeToken":"%s",
                      "code":"%s"
                    }
                    """.formatted(challengeToken, recoveryCodes.getFirst())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.viewer.roles.length()").value(1))
            .andExpect(jsonPath("$.viewer.roles[0]").value("ADMIN"))
            .andReturn();
        Cookie adminRefreshCookie = completedLogin.getResponse()
            .getCookie("rt_admin");
        assertThat(adminRefreshCookie).isNotNull();
        assertThat(completedLogin.getResponse().getCookie("rt_session"))
            .isNull();
        String mfaAccessToken = JsonPath.read(
            completedLogin.getResponse().getContentAsString(),
            "$.accessToken"
        );
        assertThat(jwtDecoder.decode(mfaAccessToken).getAudience())
            .containsExactly("sinx-admin");
        assertThat(jwtDecoder.decode(mfaAccessToken).getClaimAsString("scope"))
            .isEqualTo("ADMIN");

        Cookie renamedAdminCookie = new Cookie(
            "rt_session",
            adminRefreshCookie.getValue()
        );
        mockMvc.perform(post("/session/refresh").cookie(renamedAdminCookie))
            .andExpect(status().isUnauthorized());
        Cookie renamedUserCookie = new Cookie(
            "rt_admin",
            userRefreshCookie.getValue()
        );
        mockMvc.perform(post("/admin-session/refresh")
                .cookie(renamedUserCookie))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + mfaAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewer { email roles } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewer").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + userAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ deviceSessions { deviceLabel } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.deviceSessions.length()").value(2));

        MvcResult secondChallenge = mockMvc.perform(
                post("/admin-session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "mfa-admin@example.com",
                      "password": "mfa-admin-password"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        String secondChallengeToken = JsonPath.read(
            secondChallenge.getResponse().getContentAsString(),
            "$.challengeToken"
        );
        mockMvc.perform(post("/admin-session/login/mfa")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "challengeToken":"%s",
                      "code":"%s"
                    }
                    """.formatted(
                        secondChallengeToken,
                        recoveryCodes.getFirst()
                    )))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_MFA_CODE"));

        mockMvc.perform(delete("/admin-session/mfa")
                .header("Authorization", "Bearer " + mfaAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "password":"mfa-admin-password",
                      "code":"%s"
                    }
                    """.formatted(recoveryCodes.get(1))))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/admin-session/refresh")
                .cookie(adminRefreshCookie))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin-session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "mfa-admin@example.com",
                      "password": "mfa-admin-password"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true))
            .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    private void saveSafeSetting(String key, String jsonValue)
        throws Exception {
        saveSetting("safe", key, jsonValue);
    }

    private void saveSetting(
        String section,
        String key,
        String jsonValue
    ) throws Exception {
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", section)
                .with(jwt().authorities(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_ADMIN")
                ))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"%s":%s}
                    """.formatted(key, jsonValue)))
            .andExpect(status().isOk());
    }

    private String requestRegistrationCode(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isAccepted());
        return registrationCodeMailSender.latestCode();
    }

    @TestConfiguration
    static class TestMailConfiguration {

        @Bean
        @Primary
        RecordingRegistrationCodeMailSender
            recordingRegistrationCodeMailSender() {
            return new RecordingRegistrationCodeMailSender();
        }

        @Bean
        @Primary
        RecordingPasswordResetMailSender recordingPasswordResetMailSender() {
            return new RecordingPasswordResetMailSender();
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
            String code = latestCode.get();
            assertThat(code).matches("\\d{6}");
            return code;
        }
    }

    static class RecordingPasswordResetMailSender
        implements PasswordResetMailSender {

        private final AtomicReference<String> latestUrl = new AtomicReference<>();

        @Override
        public void sendPasswordReset(
            String recipient,
            String displayName,
            String resetUrl
        ) {
            latestUrl.set(resetUrl);
        }

        String latestToken() {
            String url = latestUrl.get();
            assertThat(url).isNotBlank();
            String query = URI.create(url).getRawQuery();
            for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", 2);
                if ("token".equals(pair[0]) && pair.length == 2) {
                    return URLDecoder.decode(
                        pair[1],
                        StandardCharsets.UTF_8
                    );
                }
            }
            throw new AssertionError("Password reset URL has no token");
        }
    }
}
