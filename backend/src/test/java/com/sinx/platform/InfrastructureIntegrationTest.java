package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.VerificationMailSender;
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
    private RecordingVerificationMailSender verificationMailSender;

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
    void rotatesRefreshSessionsAndRejectsReplayAfterLogout() throws Exception {
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "Identity.Test@Example.com",
                      "password": "correct-horse-battery-staple",
                      "displayName": "Identity Test",
                      "deviceLabel": "Integration Test"
                    }
                    """))
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
        )).isFalse();
        String firstVerificationToken = verificationMailSender.latestToken();

        mockMvc.perform(post("/session/email-verification/request")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isAccepted());
        String replacementVerificationToken =
            verificationMailSender.latestToken();
        assertThat(replacementVerificationToken)
            .isNotEqualTo(firstVerificationToken);

        mockMvc.perform(post("/session/email-verification/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"%s"}
                    """.formatted(firstVerificationToken)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(
                "INVALID_EMAIL_VERIFICATION_TOKEN"
            ));

        mockMvc.perform(post("/session/email-verification/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"%s"}
                    """.formatted(replacementVerificationToken)))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "identity.test@example.com",
                      "password": "another-valid-password",
                      "displayName": "Duplicate"
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
    void recordsAdminRequestsWithoutExposingLogsToRegularUsers()
        throws Exception {
        MvcResult regularRegistration = mockMvc.perform(
                post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "audit-user@example.com",
                          "password": "audit-user-password",
                          "displayName": "Audit User"
                        }
                        """)
            )
            .andExpect(status().isCreated())
            .andReturn();
        String regularToken = JsonPath.read(
            regularRegistration.getResponse().getContentAsString(),
            "$.accessToken"
        );

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + regularToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"query ForbiddenAudit { adminAuditLogs { id } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isArray());

        MvcResult adminRegistration = mockMvc.perform(
                post("/session/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "email": "audit-admin@example.com",
                          "password": "audit-admin-password",
                          "displayName": "Audit Admin"
                        }
                        """)
            )
            .andExpect(status().isCreated())
            .andReturn();
        String adminBody = adminRegistration
            .getResponse()
            .getContentAsString();
        String adminId = JsonPath.read(adminBody, "$.viewer.id");
        jdbcTemplate.update(
            """
            INSERT INTO user_roles (user_id, role_code)
            VALUES (?::uuid, 'ADMIN')
            """,
            adminId
        );

        MvcResult adminLogin = mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "audit-admin@example.com",
                      "password": "audit-admin-password",
                      "deviceLabel": "Audit Test"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String adminToken = JsonPath.read(
            adminLogin.getResponse().getContentAsString(),
            "$.accessToken"
        );

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + adminToken)
                .header("User-Agent", "SinX-Audit-Test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"query AdminProbe { systemStatus { status } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.systemStatus.status").value("ok"));

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query":"query AdminAuditLogs { adminAuditLogs(limit: 10) { actorEmail action outcome responseStatus userAgent } }"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.data.adminAuditLogs[?(@.action == 'graphql.AdminProbe')].actorEmail"
            ).value("audit-admin@example.com"))
            .andExpect(jsonPath(
                "$.data.adminAuditLogs[?(@.action == 'graphql.AdminProbe')].outcome"
            ).value("SUCCESS"))
            .andExpect(jsonPath(
                "$.data.adminAuditLogs[?(@.action == 'graphql.AdminProbe')].userAgent"
            ).value("SinX-Audit-Test"));
    }

    @TestConfiguration
    static class TestMailConfiguration {

        @Bean
        @Primary
        RecordingVerificationMailSender recordingVerificationMailSender() {
            return new RecordingVerificationMailSender();
        }
    }

    static class RecordingVerificationMailSender
        implements VerificationMailSender {

        private final AtomicReference<String> latestUrl = new AtomicReference<>();

        @Override
        public void sendVerification(
            String recipient,
            String displayName,
            String verificationUrl
        ) {
            latestUrl.set(verificationUrl);
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
            throw new AssertionError("Verification URL has no token");
        }
    }
}
