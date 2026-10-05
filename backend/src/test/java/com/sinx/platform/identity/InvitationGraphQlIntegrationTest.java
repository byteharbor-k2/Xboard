package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;
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
 * The invitation codes belong to the signed-in account; registration makes
 * the inviter relationship, while the saved policy determines whether a code
 * is consumed after that registration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InvitationGraphQlIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class InvitationGraphQlIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_invitations_test")
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
    private PlatformSettingRepository settings;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationCodeMailSender;

    @Test
    void codesArePrivateAndGenerationLimitIsApplied() throws Exception {
        saveSetting("invite.invite_force", "false");
        saveSetting("invite.invite_gen_limit", "1");
        Registered owner = register("invite-owner@example.com");
        Registered other = register("invite-other@example.com");

        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"{ viewerInvitations { availableCodeCount } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.viewerInvitations").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());

        mockMvc.perform(post("/gateway")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"query":"mutation { createInvitationCode { code } }"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createInvitationCode").doesNotExist())
            .andExpect(jsonPath("$.errors[0]").exists());

        MvcResult first = graphQl(owner.accessToken(), """
            mutation { createInvitationCode { id code createdAt } }
            """).andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        String code = JsonPath.read(
            first.getResponse().getContentAsString(),
            "$.data.createInvitationCode.code"
        );
        assertThat(code).matches("[A-Z2-9]{8}");

        graphQl(owner.accessToken(), """
            mutation { createInvitationCode { id code createdAt } }
            """).andExpect(jsonPath("$.data.createInvitationCode").doesNotExist())
            .andExpect(jsonPath("$.errors[0].message").exists());

        graphQl(owner.accessToken(), """
            { viewerInvitations {
                availableCodeCount invitedUserCount generationLimit neverExpire
                codes { id code createdAt }
            } }
            """)
            .andExpect(jsonPath("$.data.viewerInvitations.availableCodeCount")
                .value(1))
            .andExpect(jsonPath("$.data.viewerInvitations.invitedUserCount")
                .value(0))
            .andExpect(jsonPath("$.data.viewerInvitations.generationLimit")
                .value(1))
            .andExpect(jsonPath("$.data.viewerInvitations.neverExpire")
                .value(false))
            .andExpect(jsonPath("$.data.viewerInvitations.codes[0].code")
                .value(code));

        graphQl(other.accessToken(), """
            { viewerInvitations { availableCodeCount codes { code } } }
            """)
            .andExpect(jsonPath("$.data.viewerInvitations.availableCodeCount")
                .value(0))
            .andExpect(jsonPath("$.data.viewerInvitations.codes").isEmpty());
    }

    @Test
    void failedRegistrationDoesNotConsumeCodeAndSuccessfulSignupIsCounted()
        throws Exception {
        saveSetting("invite.invite_force", "false");
        saveSetting("invite.invite_never_expire", "false");
        Registered owner = register("invite-registration-owner@example.com");
        saveSetting("invite.invite_force", "true");
        String code = createCode(owner.accessToken());

        String invitedEmail = "invite-registration-child@example.com";
        requestRegistrationCode(invitedEmail);
        String validCode = registrationCodeMailSender.latestCode();
        String wrongCode = "000000".equals(validCode) ? "111111" : "000000";
        registerRequest(invitedEmail, wrongCode, code)
            .andExpect(status().isBadRequest());

        graphQl(owner.accessToken(), """
            { viewerInvitations { availableCodeCount codes { code } } }
            """)
            .andExpect(jsonPath("$.data.viewerInvitations.availableCodeCount")
                .value(1))
            .andExpect(jsonPath("$.data.viewerInvitations.codes[0].code")
                .value(code));

        registerRequest(invitedEmail, validCode, code)
            .andExpect(status().isCreated());

        graphQl(owner.accessToken(), """
            { viewerInvitations { availableCodeCount invitedUserCount codes { code } } }
            """)
            .andExpect(jsonPath("$.data.viewerInvitations.availableCodeCount")
                .value(0))
            .andExpect(jsonPath("$.data.viewerInvitations.invitedUserCount")
                .value(1))
            .andExpect(jsonPath("$.data.viewerInvitations.codes").isEmpty());
    }

    @Test
    void neverExpireCodesRemainAvailableAndCanCreateMultipleInvitedUsers()
        throws Exception {
        saveSetting("invite.invite_force", "false");
        saveSetting("invite.invite_never_expire", "true");
        saveSetting("invite.invite_gen_limit", "1");
        Registered owner = register("invite-never-expire-owner@example.com");
        String code = createCode(owner.accessToken());

        register("invite-never-expire-child-one@example.com", code);
        register("invite-never-expire-child-two@example.com", code);

        graphQl(owner.accessToken(), """
            { viewerInvitations {
                availableCodeCount invitedUserCount neverExpire codes { code }
            } }
            """)
            .andExpect(jsonPath("$.data.viewerInvitations.availableCodeCount")
                .value(1))
            .andExpect(jsonPath("$.data.viewerInvitations.invitedUserCount")
                .value(2))
            .andExpect(jsonPath("$.data.viewerInvitations.neverExpire")
                .value(true))
            .andExpect(jsonPath("$.data.viewerInvitations.codes[0].code")
                .value(code));
    }

    private String createCode(String accessToken) throws Exception {
        MvcResult result = graphQl(accessToken, """
            mutation { createInvitationCode { id code createdAt } }
            """).andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
        return JsonPath.read(
            result.getResponse().getContentAsString(),
            "$.data.createInvitationCode.code"
        );
    }

    private Registered register(String email) throws Exception {
        requestRegistrationCode(email);
        MvcResult registration = registerRequest(
            email,
            registrationCodeMailSender.latestCode(),
            null
        ).andExpect(status().isCreated()).andReturn();
        return new Registered(JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.accessToken"
        ));
    }

    private void register(String email, String inviteCode) throws Exception {
        requestRegistrationCode(email);
        registerRequest(
            email,
            registrationCodeMailSender.latestCode(),
            inviteCode
        ).andExpect(status().isCreated());
    }

    private void requestRegistrationCode(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isAccepted());
    }

    private org.springframework.test.web.servlet.ResultActions registerRequest(
        String email,
        String emailCode,
        String inviteCode
    ) throws Exception {
        return mockMvc.perform(post("/session/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "password": "invitation-test-password",
                  "displayName": "Invitation Tester",
                  "deviceLabel": "Invitation Test Browser",
                  "emailCode": "%s",
                  "inviteCode": %s
                }
                """.formatted(
                    email,
                    emailCode,
                    inviteCode == null ? "null" : "\"" + inviteCode + "\""
                )));
    }

    private org.springframework.test.web.servlet.ResultActions graphQl(
        String accessToken,
        String query
    ) throws Exception {
        return mockMvc.perform(post("/gateway")
            .header("Authorization", "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"query":"%s"}
                """.formatted(query.replace("\n", " ").replace("\"", "\\\""))));
    }

    private void saveSetting(String key, String value) {
        PlatformSetting setting = settings.findById(key)
            .orElseGet(() -> PlatformSetting.create(key, value, Instant.now()));
        setting.update(value, Instant.now());
        settings.save(setting);
    }

    private record Registered(String accessToken) {
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
            String code = latestCode.get();
            assertThat(code).matches("\\d{6}");
            return code;
        }
    }
}
