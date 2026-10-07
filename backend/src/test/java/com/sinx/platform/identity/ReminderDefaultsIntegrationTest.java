package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
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

/** Admin reminder defaults apply only to accounts registered after the change. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReminderDefaultsIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ReminderDefaultsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_reminder_defaults_test")
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

    @Autowired private MockMvc mvc;
    @Autowired private RecordingRegistrationCodeMailSender registrationMail;

    @Test
    void subscribeDefaultsAffectOnlyFutureAccountsAndLeaveMailMasterAlone()
        throws Exception {
        mvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "subscribe")
                .with(adminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.subscribe.default_remind_expire").value(true))
            .andExpect(jsonPath("$.data.subscribe.default_remind_traffic").value(true));

        String mailMasterBefore = mailMasterSetting();
        Registered earlier = register(uniqueEmail());
        viewerReminders(earlier, true, true);
        updateViewerReminders(earlier, false, true);

        saveSubscribeSetting("default_remind_expire", false);
        saveSubscribeSetting("default_remind_traffic", false);

        viewerReminders(earlier, false, true);
        Registered later = register(uniqueEmail());
        viewerReminders(later, false, false);
        assertThat(mailMasterSetting()).isEqualTo(mailMasterBefore);

        mvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "subscribe")
                .with(adminJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.subscribe.default_remind_expire").value(false))
            .andExpect(jsonPath("$.data.subscribe.default_remind_traffic").value(false));
    }

    private String mailMasterSetting() throws Exception {
        MvcResult result = mvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "email")
                .with(adminJwt()))
            .andExpect(status().isOk())
            .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(),
            "$.data.email.remind_mail_enable").toString();
    }

    private void saveSubscribeSetting(String name, boolean enabled) throws Exception {
        mvc.perform(post("/api/v2/admin/config/save")
                .param("key", "subscribe")
                .with(adminJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + name + "\":" + enabled + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    private Registered register(String email) throws Exception {
        mvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isAccepted());
        MvcResult response = mvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "reminder-default-password",
                      "displayName": "Reminder defaults",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationMail.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return new Registered(JsonPath.read(
            response.getResponse().getContentAsString(), "$.accessToken"));
    }

    private void viewerReminders(Registered user, boolean expire, boolean traffic)
        throws Exception {
        mvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewer { remindExpire remindTraffic } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewer.remindExpire").value(expire))
            .andExpect(jsonPath("$.data.viewer.remindTraffic").value(traffic));
    }

    private void updateViewerReminders(
        Registered user,
        boolean expire,
        boolean traffic
    ) throws Exception {
        mvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { updateViewerReminders(remindExpire: %s, remindTraffic: %s) { remindExpire remindTraffic } }"}
                    """.formatted(expire, traffic)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.updateViewerReminders.remindExpire").value(expire))
            .andExpect(jsonPath("$.data.updateViewerReminders.remindTraffic").value(traffic));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt().authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"),
            new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private String uniqueEmail() {
        return "reminder-default-" + UUID.randomUUID() + "@example.test";
    }

    private record Registered(String accessToken) { }

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
        private final AtomicReference<String> code = new AtomicReference<>();

        @Override
        public void sendRegistrationCode(String recipient, String code) {
            this.code.set(code);
        }

        String latestCode() {
            String latest = code.get();
            assertThat(latest).matches("\\d{6}");
            return latest;
        }
    }
}
