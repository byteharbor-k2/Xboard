package com.sinx.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(SupportChatIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SupportChatIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_support_test")
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

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecordingMailSender mailSender;

    @BeforeEach
    void clearSupportFixtures() {
        jdbc.update("DELETE FROM support_messages");
        jdbc.update("DELETE FROM support_conversations");
    }

    @Test
    void userOwnsOnlyTheirMessagesAndReceivesAdminRepliesWithoutIdentityLeak() throws Exception {
        String firstUser = register("support-first@example.com");
        String otherUser = register("support-other@example.com");
        String firstUserId = jwtSubject(firstUser);

        graphql(firstUser, "mutation { sendSupportMessage(content: \"verify-chat-93841\") { sender content createdAt } }")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sendSupportMessage.sender").value("USER"))
            .andExpect(jsonPath("$.data.sendSupportMessage.content").value("verify-chat-93841"));

        graphql(otherUser, "{ viewerSupportMessages { sender content createdAt } }")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerSupportMessages").isEmpty());

        mockMvc.perform(get("/api/v2/admin/support/conversations").with(administrator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].userId").value(firstUserId))
            .andExpect(jsonPath("$.data[0].email").value("support-first@example.com"));

        mockMvc.perform(post("/api/v2/admin/support/conversations/{userId}/reply", firstUserId)
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"A human support reply\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sender").value("ADMIN"));

        MvcResult userTranscript = graphql(firstUser, "{ viewerSupportMessages { sender content createdAt } }")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerSupportMessages[1].sender").value("ADMIN"))
            .andExpect(jsonPath("$.data.viewerSupportMessages[1].content").value("A human support reply"))
            .andReturn();
        String body = userTranscript.getResponse().getContentAsString();
        assertThat(body).doesNotContain("support-first@example.com", "email", firstUserId);

        mockMvc.perform(get("/api/v2/admin/support/conversations"))
            .andExpect(status().is4xxClientError());
        graphql(firstUser, "{ supportEmail }")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.supportEmail").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());
    }

    @Test
    void blankChatMessagesAreRejected() throws Exception {
        String user = register("support-blank@example.com");
        String userId = jwtSubject(user);
        graphql(user, "mutation { sendSupportMessage(content: \"   \") { sender content createdAt } }")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sendSupportMessage").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM support_conversations WHERE user_id = ?::uuid",
            Integer.class,
            userId
        )).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions graphql(String token, String query) throws Exception {
        return mockMvc.perform(post("/gateway")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapperJson(query)));
    }

    private String objectMapperJson(String query) {
        return "{\"query\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private String register(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
            .andExpect(status().isAccepted());
        MvcResult registration = mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"support-test-password","displayName":"Support test","deviceLabel":"Support Browser","emailCode":"%s"}
                    """.formatted(email, mailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return JsonPath.read(registration.getResponse().getContentAsString(), "$.accessToken");
    }

    private String jwtSubject(String token) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return JsonPath.read(payload, "$.sub");
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    @TestConfiguration
    static class TestMailConfiguration {
        @Bean @Primary RecordingMailSender recordingMailSender() { return new RecordingMailSender(); }
    }

    static class RecordingMailSender implements RegistrationCodeMailSender {
        private final AtomicReference<String> code = new AtomicReference<>();
        @Override public void sendRegistrationCode(String recipient, String value) { code.set(value); }
        String latestCode() { return code.get(); }
    }
}
