package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * The account's own reminder switches, seen from the user side.
 *
 * The daily sweep reads these columns, so the customer must be able to see
 * and change them: they ride on the viewer the account page already loads,
 * and one mutation moves both at once. Anonymous callers reach neither -
 * the gateway exposes nothing of an account without its session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ViewerRemindersIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ViewerRemindersIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_viewer_reminders_test")
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
    private MockMvc mockMvc;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @Test
    void theViewerExposesBothSwitchesOnByDefault() throws Exception {
        Registered account = register("reminders-viewer@example.com");

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewer { remindExpire remindTraffic } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.viewer.remindExpire").value(true))
            .andExpect(jsonPath("$.data.viewer.remindTraffic").value(true));
    }

    @Test
    void theMutationTurnsBothSwitchesOffAndBackOn() throws Exception {
        Registered account = register("reminders-mutation@example.com");

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { updateViewerReminders(remindExpire: false, remindTraffic: false) { remindExpire remindTraffic } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.updateViewerReminders.remindExpire")
                .value(false))
            .andExpect(jsonPath("$.data.updateViewerReminders.remindTraffic")
                .value(false));

        // The answer is not the record: a fresh query reads the stored rows.
        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewer { remindExpire remindTraffic } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewer.remindExpire").value(false))
            .andExpect(jsonPath("$.data.viewer.remindTraffic").value(false));

        mockMvc.perform(post("/gateway")
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { updateViewerReminders(remindExpire: true, remindTraffic: false) { remindExpire remindTraffic } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updateViewerReminders.remindExpire")
                .value(true))
            .andExpect(jsonPath("$.data.updateViewerReminders.remindTraffic")
                .value(false));
    }

    @Test
    void anonymousCallersReachNeitherTheFieldsNorTheMutation()
        throws Exception {
        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewer { remindExpire remindTraffic } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewer").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());

        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { updateViewerReminders(remindExpire: false, remindTraffic: false) { remindExpire } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updateViewerReminders").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());
    }

    private Registered register(String email) throws Exception {
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
                      "password": "viewer-reminders-password",
                      "displayName": "Reminder Viewer",
                      "deviceLabel": "Reminder Viewer Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        return new Registered(
            JsonPath.read(
                registration.getResponse().getContentAsString(),
                "$.accessToken"
            )
        );
    }

    private record Registered(String accessToken) {
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
