package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.application.TurnstileVerificationService;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.shared.web.ApiProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

/** Registration needs separate, single-use Turnstile proofs at both public endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TurnstileRegistrationIntegrationTest.TestBeans.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TurnstileRegistrationIntegrationTest {

    private static final String SITE_KEY = "integration-public-site-key";
    private static final String SECRET_KEY = "integration-private-secret";
    private static final String EMAIL_PROOF = "email-code-proof-once";
    private static final String REGISTRATION_PROOF = "registration-proof-once";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_turnstile_test")
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
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingRegistrationCodeMailSender registrationMail;

    @AfterEach
    void clearTurnstileState() {
        jdbc.update("DELETE FROM platform_settings WHERE setting_key LIKE 'safe.%'");
        redis.delete(redis.keys("identity:registration-code:*"));
        redis.delete(redis.keys("identity:registration-code-cooldown:*"));
        redis.delete(redis.keys("identity:registration-ip:*"));
    }

    @Test
    void publicSignupAndEmailCodeRequireFreshProofAndConfigNeverReturnsSecret()
        throws Exception {
        saveSafeSettings();

        MvcResult publicConfig = mockMvc.perform(get("/session/registration/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.turnstileEnabled").value(true))
            .andExpect(jsonPath("$.turnstileSiteKey").value(SITE_KEY))
            .andReturn();
        assertThat(publicConfig.getResponse().getContentAsString())
            .doesNotContain(SECRET_KEY);

        // Even a legacy unsupported stored provider value is normalized to
        // Turnstile and cannot bypass the enabled switch.
        MvcResult safeSettings = mockMvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "safe")
                .with(administrator()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.safe.captcha_type").value("turnstile"))
            .andExpect(jsonPath("$.data.safe.turnstile_secret_key").value(""))
            .andExpect(jsonPath("$.data.safe.turnstile_secret_key_configured")
                .value(true))
            .andReturn();
        assertThat(safeSettings.getResponse().getContentAsString())
            .doesNotContain(SECRET_KEY);

        String email = "turnstile-flow@example.com";
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(email)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TURNSTILE_INVALID"));

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(email, null, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TURNSTILE_INVALID"));

        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","turnstileToken":"%s"}
                    """.formatted(email, EMAIL_PROOF)))
            .andExpect(status().isAccepted());

        String emailCode = registrationMail.latestCode();
        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(email, emailCode, null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TURNSTILE_INVALID"));
        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(email, emailCode, EMAIL_PROOF)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TURNSTILE_INVALID"));

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(email, emailCode, REGISTRATION_PROOF)))
            .andExpect(status().isCreated());

        // The code endpoint remains proof-gated when email delivery of a code
        // is disabled, without changing the normal CAPTCHA-off behavior.
        saveSetting("email_verify", false);
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"code-disabled@example.com"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("TURNSTILE_INVALID"));
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"code-disabled@example.com",
                     "turnstileToken":"email-proof-without-code"}
                    """))
            .andExpect(status().isAccepted());
    }

    private void saveSafeSettings() throws Exception {
        saveSetting("email_verify", true);
        saveSetting("captcha_enable", true);
        saveSetting("captcha_type", "recaptcha");
        saveSetting("turnstile_site_key", SITE_KEY);
        saveSetting("turnstile_secret_key", SECRET_KEY);
    }

    private void saveSetting(String key, Object value) throws Exception {
        String encoded = value instanceof Boolean bool
            ? Boolean.toString(bool)
            : "\"" + value + "\"";
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "safe")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"%s\":%s}".formatted(key, encoded)))
            .andExpect(status().isOk());
    }

    private String registrationBody(
        String email,
        String emailCode,
        String turnstileToken
    ) {
        String codeField = emailCode == null
            ? ""
            : "\"emailCode\":\"" + emailCode + "\",";
        String proofField = turnstileToken == null
            ? ""
            : "\"turnstileToken\":\"" + turnstileToken + "\",";
        return """
            {
              "email": "%s",
              "password": "turnstile-integration-password",
              "displayName": "Turnstile test",
              %s%s
              "deviceLabel": "Integration browser"
            }
            """.formatted(email, codeField, proofField);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor
        administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        RecordingRegistrationCodeMailSender registrationCodeMailSender() {
            return new RecordingRegistrationCodeMailSender();
        }

        @Bean
        @Primary
        RecordingTurnstileVerificationService recordingTurnstileVerificationService(
            PlatformConfigurationService configuration
        ) {
            return new RecordingTurnstileVerificationService(configuration);
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

    static class RecordingTurnstileVerificationService
        extends TurnstileVerificationService {

        private final PlatformConfigurationService configuration;
        private final Set<String> consumedTokens = ConcurrentHashMap.newKeySet();

        RecordingTurnstileVerificationService(
            PlatformConfigurationService configuration
        ) {
            super(configuration);
            this.configuration = configuration;
        }

        @Override
        public void verify(String token, String remoteIp) {
            PlatformConfigurationService.TurnstilePolicy policy =
                configuration.turnstilePolicy();
            if (!policy.enabled()) return;
            if (policy.siteKey() == null || policy.secretKey() == null) {
                throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TURNSTILE_NOT_CONFIGURED",
                    "Registration verification is temporarily unavailable"
                );
            }
            if (token == null || token.isBlank() || !consumedTokens.add(token)) {
                throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "TURNSTILE_INVALID",
                    "Complete the human verification and try again"
                );
            }
        }
    }
}
