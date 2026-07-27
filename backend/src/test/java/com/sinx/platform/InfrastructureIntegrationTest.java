package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
                    {"query":"{ viewer { email displayName roles } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewer.email").value("identity.test@example.com"))
            .andExpect(jsonPath("$.data.viewer.displayName").value("Identity Test"));

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
        Cookie loginRefreshCookie = login.getResponse().getCookie("rt_session");
        assertThat(loginRefreshCookie).isNotNull();

        mockMvc.perform(delete("/session/current").cookie(loginRefreshCookie))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/session/refresh").cookie(loginRefreshCookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }
}
