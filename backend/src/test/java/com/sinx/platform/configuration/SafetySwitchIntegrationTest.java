package com.sinx.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
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

/**
 * The safe-section switches of the legacy panel, re-wired: registration can
 * be stopped, per-IP registration throttled, login locked after wrong
 * passwords, and Gmail aliases refused - each one reading its threshold and
 * window from the saved configuration instead of a compiled-in value.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SafetySwitchIntegrationTest.TestBeans.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SafetySwitchIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_safety_test")
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
     * Each test saves and clears its own switches, so no state leaks into the
     * next: an unsaved configuration is exactly the legacy default set.
     */
    @AfterEach
    void clearSwitchState() {
        jdbcTemplate.update(
            "DELETE FROM platform_settings "
                + "WHERE setting_key LIKE 'safe.%' "
                + "OR setting_key = 'site.stop_register'"
        );
        redisTemplate.delete(
            redisTemplate.keys("identity:registration-ip:*")
        );
        redisTemplate.delete(
            redisTemplate.keys("identity:login-failures:*")
        );
    }

    @Test
    void safeSectionDefaultsMatchTheLegacyPanelWhenNothingWasSaved()
        throws Exception {
        String body = fetchSafe();

        expect(body, "$.data.safe.stop_register", false);
        expect(body, "$.data.safe.email_gmail_limit_enable", false);
        expect(body, "$.data.safe.captcha_type", "turnstile");
        expect(body, "$.data.safe.register_limit_by_ip_enable", false);
        expect(body, "$.data.safe.register_limit_count", 3);
        expect(body, "$.data.safe.register_limit_expire", 60);
        expect(body, "$.data.safe.password_limit_enable", true);
        expect(body, "$.data.safe.password_limit_count", 5);
        expect(body, "$.data.safe.password_limit_expire", 60);
    }

    @Test
    void safeSwitchesRoundTripThroughSaveAndFetch() throws Exception {
        saveSetting("stop_register", true);
        saveSetting("email_gmail_limit_enable", true);
        saveSetting("register_limit_by_ip_enable", true);
        saveSetting("register_limit_count", 7);
        saveSetting("register_limit_expire", 30);
        saveSetting("password_limit_enable", false);
        saveSetting("password_limit_count", 9);
        saveSetting("password_limit_expire", 45);

        String body = fetchSafe();

        expect(body, "$.data.safe.stop_register", true);
        expect(body, "$.data.safe.email_gmail_limit_enable", true);
        expect(body, "$.data.safe.register_limit_by_ip_enable", true);
        expect(body, "$.data.safe.register_limit_count", 7);
        expect(body, "$.data.safe.register_limit_expire", 30);
        expect(body, "$.data.safe.password_limit_enable", false);
        expect(body, "$.data.safe.password_limit_count", 9);
        expect(body, "$.data.safe.password_limit_expire", 45);
    }

    @Test
    void safetyValuesSaveAndStoreWhatTheOperatorWrote() throws Exception {
        // One-time configuration carries no bounds: the operator's number
        // is stored as written and read straight back.
        saveSetting("register_limit_count", 999);
        saveSetting("register_limit_expire", 30);

        String body = fetchSafe();
        expect(body, "$.data.safe.register_limit_count", 999);
        expect(body, "$.data.safe.register_limit_expire", 30);

        // A string that reads as an integer is coerced and stored the same
        // way, so a re-save of a JSON-editorized value never refuses.
        saveSetting("register_limit_expire", "30");
        expect(fetchSafe(), "$.data.safe.register_limit_expire", 30);

        // The provider switch stores whatever the operator chose; the
        // section keeps reporting the provider the platform actually runs
        // (turnstile) and an unknown stored type simply disables captcha.
        saveSetting("captcha_type", "recaptcha");
        saveSetting("captcha_type", "turnstile");
    }

    /**
     * A value of the wrong shape - one that cannot be read back as the
     * setting's type at all - is the single flat invalid-setting answer,
     * and never a special-cased taxonomy.
     */
    @Test
    void junkTypedValuesAnswerTheFlatValueProblem() throws Exception {
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "safe")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"register_limit_expire": "abc"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SETTING_VALUE_INVALID"));

        // A junk-typed save never left a row behind.
        expect(fetchSafe(), "$.data.safe.register_limit_expire", 60);
    }

    /**
     * A corrupted row (planted directly, the way an old bug could have left
     * it) is read as the field's default - never a failure of the fetch.
     */
    @Test
    void junkStoredRowsReadAsTheDefaults() throws Exception {
        insertSetting("safe.register_limit_count", "abc");
        insertSetting("safe.stop_register", "sometimes");
        insertSetting("safe.email_verify", "sometimes");
        insertSetting("safe.password_limit_count", "not-a-number");

        String body = fetchSafe();
        expect(body, "$.data.safe.register_limit_count", 3);
        expect(body, "$.data.safe.register_limit_expire", 60);
        expect(body, "$.data.safe.stop_register", false);
        expect(body, "$.data.safe.email_verify", true);
        expect(body, "$.data.safe.register_limit_by_ip_enable", false);
        expect(body, "$.data.safe.password_limit_enable", true);
        expect(body, "$.data.safe.password_limit_count", 5);
        expect(body, "$.data.safe.password_limit_expire", 60);
    }

    @Test
    void stopRegisterRefusesRegistrationOutright() throws Exception {
        saveSetting("stop_register", true);

        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "stopped@example.com",
                      "password": "stopped-password",
                      "displayName": "Stopped"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));

        // The code flow is refused too, so a closed site never mails out
        // preparations for an account it will not take.
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "stopped@example.com"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));

        // And reopening the switch restores registration.
        saveSetting("stop_register", false);
        assertThat(auditStatus(registerUser("reopened@example.com"))).isEqualTo(201);
    }

    /**
     * The per-IP counter is the same Redis registration counter the platform
     * already kept; only its threshold and window now come from the settings
     * (count 2, and a three minute window instead of the built-in hour).
     */
    @Test
    void registerLimitTripsAtTheConfiguredCountWithTheSettingsWindow()
        throws Exception {
        saveSetting("register_limit_by_ip_enable", true);
        saveSetting("register_limit_count", 2);
        saveSetting("register_limit_expire", 3);

        assertThat(auditStatus(registerUser("limit-one@example.com"))).isEqualTo(201);
        assertThat(auditStatus(registerUser("limit-two@example.com"))).isEqualTo(201);
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "limit-three@example.com"}
                    """))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("REGISTRATION_RATE_LIMITED"));

        // The window is the saved one: the counter lives three minutes, not
        // the sixty of the configuration default.
        String countKey = redisTemplate
            .keys("identity:registration-ip:*")
            .iterator()
            .next();
        Long ttlSeconds = redisTemplate.getExpire(countKey, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isBetween(0L, 180L);

        // With the switch off the counter neither checks nor fills.
        saveSetting("register_limit_by_ip_enable", false);
        assertThat(auditStatus(registerUser("limit-three@example.com"))).isEqualTo(201);
    }

    /**
     * Only the plus-tagged local part on the alias-capable Gmail domains is
     * refused; a dotted Gmail address is the same mailbox Gmail serves and
     * stays allowed, and other domains keep their addressing freedom.
     */
    @Test
    void gmailAliasSwitchRejectsThePlusTagOnlyOnGmailDomains() throws Exception {
        saveSetting("email_gmail_limit_enable", true);

        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "plus.tag+box@gmail.com"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("GMAIL_ALIAS_NOT_SUPPORTED"));
        mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "plus.tag+box@gmail.com",
                      "password": "alias-password",
                      "displayName": "Alias"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("GMAIL_ALIAS_NOT_SUPPORTED"));

        // Dots on a Gmail address remain allowed, unlike the original guard.
        assertThat(auditStatus(registerUser("real.name@gmail.com"))).isEqualTo(201);

        // And a plus tag on a non-Gmail domain never was an alias.
        assertThat(auditStatus(registerUser("other.box+tag@yahoo.com"))).isEqualTo(201);

        // With the switch off even the plus tag registers.
        saveSetting("email_gmail_limit_enable", false);
        assertThat(auditStatus(registerUser("plus.tag+box@gmail.com"))).isEqualTo(201);
    }

    /**
     * Wrong passwords fill the same failure counter the login attempt
     * recorder always kept, but its ceiling and window now come from the
     * saved switch; and the window expiring is what lets the account back
     * in without an operator touching it.
     */
    @Test
    void passwordLockoutTripsAtTheConfiguredFailuresAndRecovers()
        throws Exception {
        saveSetting("password_limit_count", 2);
        saveSetting("password_limit_expire", 1);

        String email = "locked-out@example.com";
        registerUser(email);

        // Two wrong answers are each an ordinary failure.
        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "%s", "password": "wrong-password"}
                    """.formatted(email)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "%s", "password": "wrong-password"}
                    """.formatted(email)))
            .andExpect(status().isUnauthorized());

        // The ceiling now refuses even the right password.
        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "%s", "password": "safety-password"}
                    """.formatted(email)))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));

        // A one minute window: the counter decays and the account unlocks.
        String failureKey = redisTemplate
            .keys("identity:login-failures:*")
            .iterator()
            .next();
        Long lockTtl = redisTemplate.getExpire(failureKey, TimeUnit.SECONDS);
        assertThat(lockTtl).isNotNull();
        assertThat(lockTtl).isBetween(0L, 60L);
        TimeUnit.SECONDS.sleep(61);

        mockMvc.perform(post("/session/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "%s", "password": "safety-password"}
                    """.formatted(email)))
            .andExpect(status().isOk());

        // With the switch off failures record nothing and never lock.
        saveSetting("password_limit_enable", false);
        for (int attempt = 0; attempt < 6; attempt++) {
            mockMvc.perform(post("/session/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"email": "%s", "password": "wrong-password"}
                        """.formatted(email)))
                .andExpect(status().isUnauthorized());
        }
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private void saveSetting(String key, Object value) throws Exception {
        String encoded = value instanceof Boolean bool
            ? String.valueOf(bool)
            : value instanceof Number number
                ? String.valueOf(number)
                : "\"" + value + "\"";
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "safe")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"%s\": %s}".formatted(key, encoded)))
            .andExpect(status().isOk());
    }

    /** Plants a raw row directly, disturbing the save path entirely. */
    private void insertSetting(String key, String value) {
        jdbcTemplate.update(
            "INSERT INTO platform_settings (setting_key, setting_value, "
                + "updated_at) VALUES (?, ?, NOW())",
            key, value
        );
    }

    private String fetchSafe() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "safe")
                .with(administrator()))
            .andExpect(status().isOk())
            .andReturn();
        return result.getResponse().getContentAsString();
    }

    private MvcResult registerUser(String email) throws Exception {
        mockMvc.perform(post("/session/registration/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email": "%s"}
                    """.formatted(email)))
            .andExpect(status().isAccepted());
        return mockMvc.perform(post("/session/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "password": "safety-password",
                      "displayName": "Safety Fixture",
                      "deviceLabel": "Safety Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andReturn();
    }

    private void expect(String body, String path, Object expected) {
        assertThat(JsonPath.<Object>read(body, path)).isEqualTo(expected);
    }

    private int auditStatus(MvcResult result) {
        return result.getResponse().getStatus();
    }

    @TestConfiguration
    static class TestBeans {

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
