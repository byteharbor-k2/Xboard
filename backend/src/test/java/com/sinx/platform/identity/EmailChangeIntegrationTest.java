package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import com.sinx.platform.notification.email.EmailChangeCodeMailSender;
import com.sinx.platform.identity.security.IdentityTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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

/** Account email changes prove the target address without crossing identity or session boundaries. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmailChangeIntegrationTest.TestBeans.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class EmailChangeIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("73c47bb5-3759-4a8f-9264-68319c7f1cc1");
    private static final UUID OTHER_USER_ID = UUID.fromString("c93b3bd8-5639-4bcf-aeb6-92e2b125b611");
    private static final UUID CURRENT_SESSION_ID = UUID.fromString("90fbc31f-fd2a-4600-9e9e-a27b2f9896f1");
    private static final UUID OTHER_USER_DEVICE_SESSION_ID = UUID.fromString("ba86be53-44a3-49de-a624-1d3e895b23f7");
    private static final UUID ADMIN_SESSION_ID = UUID.fromString("2eb2350e-d4ed-4308-9fb4-47433411ed0e");
    private static final String PASSWORD = "email-change-integration-password";
    private static final String EMAIL = "profile-owner@example.com";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_email_change_test")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IdentityTokenService tokenService;
    @Autowired private RecordingEmailChangeCodeMailSender mailSender;

    @BeforeEach
    void seedAccountsAndSessions() {
        jdbc.update("DELETE FROM users WHERE id IN (?::uuid, ?::uuid)", USER_ID.toString(), OTHER_USER_ID.toString());
        Instant now = Instant.now();
        insertUser(USER_ID, EMAIL, "Profile owner");
        insertUser(OTHER_USER_ID, "second-owner@example.com", "Second owner");
        insertSession(CURRENT_SESSION_ID, USER_ID, "USER");
        insertSession(OTHER_USER_DEVICE_SESSION_ID, USER_ID, "USER");
        insertSession(ADMIN_SESSION_ID, USER_ID, "ADMIN");
        jdbc.update("""
            INSERT INTO password_reset_tokens
              (id, user_id, token_hash, created_at, expires_at, version)
            VALUES (?, ?, ?, ?, ?, 0)
            """,
            UUID.randomUUID(), USER_ID, "a".repeat(64), Timestamp.from(now),
            Timestamp.from(now.plusSeconds(1800))
        );
        mailSender.latestCode.set(null);
        mailSender.latestRecipient.set(null);
        mailSender.failDelivery = false;
    }

    @AfterEach
    void clearRedisChallenges() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_email_change_trigger ON users");
        jdbc.execute("DROP FUNCTION IF EXISTS reject_test_email_change()");
        redis.delete(redis.keys("identity:email-change-code:*") );
        redis.delete(redis.keys("identity:email-change-cooldown:*") );
        redis.delete(redis.keys("identity:email-change-target:*") );
        redis.delete(redis.keys("identity:registration-code:*") );
    }

    @Test
    void changeReturnsFullViewerCanonicalizesAddressAndKeepsOnlyCurrentUserSession()
        throws Exception {
        String target = "  New.Profile@Example.COM  ";
        redis.opsForHash().put("identity:registration-code:independent", "codeHash", "untouched");

        MvcResult requested = requestCode(target, PASSWORD, userRequest());
        assertThat(requested.getResponse().getStatus()).isEqualTo(202);
        assertThat(requested.getResponse().getContentAsString()).isEmpty();
        assertThat(mailSender.latestCode()).matches("\\d{6}");
        assertThat(mailSender.latestRecipient.get()).isEqualTo("new.profile@example.com");
        assertThat(redis.opsForHash().get("identity:registration-code:independent", "codeHash"))
            .isEqualTo("untouched");

        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody(target, "incorrect-password", mailSender.latestCode())))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        assertThat(redis.opsForHash().get("identity:email-change-code:" + USER_ID + ":" + emailHash("new.profile@example.com"), "claim"))
            .isNull();

        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody(target, PASSWORD, mailSender.latestCode())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(USER_ID.toString()))
            .andExpect(jsonPath("$.email").value("new.profile@example.com"))
            .andExpect(jsonPath("$.displayName").value("Profile owner"))
            .andExpect(jsonPath("$.emailVerified").value(true))
            .andExpect(jsonPath("$.roles").isArray())
            .andExpect(jsonPath("$.roles[0]").value("USER"))
            .andExpect(jsonPath("$.balanceMinor").value("1234"))
            .andExpect(jsonPath("$.remindExpire").value(false))
            .andExpect(jsonPath("$.remindTraffic").value(true))
            .andExpect(jsonPath("$.createdAt").exists());

        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, USER_ID))
            .isEqualTo("new.profile@example.com");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM password_reset_tokens WHERE user_id = ? AND consumed_at IS NULL",
            Integer.class,
            USER_ID
        )).isZero();
        assertThat(revokedAt(CURRENT_SESSION_ID)).isNull();
        assertThat(revokedAt(OTHER_USER_DEVICE_SESSION_ID)).isNotNull();
        assertThat(revokedAt(ADMIN_SESSION_ID)).isNull();
        assertThat(redis.opsForHash().get("identity:registration-code:independent", "codeHash"))
            .isEqualTo("untouched");
    }

    @Test
    void wrongPasswordDoesNotSendOrConsumeProofAndCodesAreBoundToUserAndTarget()
        throws Exception {
        mockMvc.perform(post("/session/email-change/code")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("next@example.com", "wrong-password")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        assertThat(mailSender.latestCode.get()).isNull();

        requestCode("next@example.com", PASSWORD, userRequest());
        String code = mailSender.latestCode();
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("different@example.com", PASSWORD, code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));
        mockMvc.perform(put("/session/email")
                .with(otherUserRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("next@example.com", PASSWORD, code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));

        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody(" NEXT@EXAMPLE.COM ", PASSWORD, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("next@example.com"));
    }

    @Test
    void invalidAndUnchangedEmailAddressesAreRejected() throws Exception {
        mockMvc.perform(post("/session/email-change/code")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("not-an-email", PASSWORD)))
            .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/session/email-change/code")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("  " + EMAIL.toUpperCase() + "  ", PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_UNCHANGED"));
        assertThat(mailSender.latestCode.get()).isNull();
    }

    @Test
    void duplicateAddressesAreRejectedBeforeSendingAndAdminScopeCannotUseUserEndpoints()
        throws Exception {
        mockMvc.perform(post("/session/email-change/code")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("SECOND-OWNER@EXAMPLE.COM", PASSWORD)))
            .andExpect(status().isConflict());
        assertThat(mailSender.latestCode.get()).isNull();

        requestCode("taken-after-request@example.com", PASSWORD, userRequest());
        String reservedCode = mailSender.latestCode();
        jdbc.update(
            "UPDATE users SET email = 'taken-after-request@example.com' WHERE id = ?",
            OTHER_USER_ID
        );
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("taken-after-request@example.com", PASSWORD, reservedCode)))
            .andExpect(status().isConflict());

        mockMvc.perform(post("/session/email-change/code")
                .with(adminRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("admin-target@example.com", PASSWORD)))
            .andExpect(status().isForbidden());
        mockMvc.perform(put("/session/email")
                .with(adminRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("admin-target@example.com", PASSWORD, "123456")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/session/email-change/code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("anonymous@example.com", PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void twoConcurrentConfirmationsCanCommitOnlyOnce() throws Exception {
        requestCode("single-use@example.com", PASSWORD, userRequest());
        String code = mailSender.latestCode();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = callers.submit(() -> confirm("single-use@example.com", code));
            Future<MvcResult> second = callers.submit(() -> confirm("single-use@example.com", code));
            int firstStatus = first.get().getResponse().getStatus();
            int secondStatus = second.get().getResponse().getStatus();
            assertThat(List.of(firstStatus, secondStatus))
                .containsExactlyInAnyOrder(200, 400);
            assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, USER_ID))
                .isEqualTo("single-use@example.com");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void staleWrongAndReusedProofsCannotChangeTheAddress()
        throws Exception {
        requestCode("proof-target@example.com", PASSWORD, userRequest());
        String code = mailSender.latestCode();
        String wrongCode = "000000".equals(code) ? "000001" : "000000";
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("proof-target@example.com", PASSWORD, wrongCode)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));

        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("proof-target@example.com", PASSWORD, code)))
            .andExpect(status().isOk());
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("proof-target@example.com", PASSWORD, code)))
            .andExpect(status().isBadRequest());

        requestCode("stale-target@example.com", PASSWORD, userRequest());
        String staleCode = mailSender.latestCode();
        redis.delete("identity:email-change-code:" + USER_ID + ":" + emailHash("stale-target@example.com"));
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("stale-target@example.com", PASSWORD, staleCode)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));
    }

    @Test
    void databaseRollbackReleasesTheSingleUseProofForRetry() throws Exception {
        jdbc.execute("""
            CREATE FUNCTION reject_test_email_change() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
              IF NEW.email = 'rollback-target@example.com' THEN
                RAISE EXCEPTION 'forced email update failure';
              END IF;
              RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER reject_test_email_change_trigger
            BEFORE UPDATE OF email ON users
            FOR EACH ROW EXECUTE FUNCTION reject_test_email_change()
            """);
        requestCode("rollback-target@example.com", PASSWORD, userRequest());
        String code = mailSender.latestCode();
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("rollback-target@example.com", PASSWORD, code)))
            .andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, USER_ID))
            .isEqualTo(EMAIL);

        jdbc.execute("DROP TRIGGER reject_test_email_change_trigger ON users");
        jdbc.execute("DROP FUNCTION reject_test_email_change()");
        mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody("rollback-target@example.com", PASSWORD, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("rollback-target@example.com"));
    }

    @Test
    void failedDeliveryClearsCooldownSoTheCustomerCanRetry() throws Exception {
        mailSender.failDelivery = true;
        mockMvc.perform(post("/session/email-change/code")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody("retry-mail@example.com", PASSWORD)))
            .andExpect(status().isServiceUnavailable());
        assertThat(redis.keys("identity:email-change-code:" + USER_ID + ":*")).isEmpty();

        mailSender.failDelivery = false;
        requestCode("retry-mail@example.com", PASSWORD, userRequest());
        assertThat(mailSender.latestCode()).matches("\\d{6}");
    }

    @Test
    void nicknameIsTrimmedAndRejectsBlankOrMoreThanEightyCharacters()
        throws Exception {
        mockMvc.perform(post("/gateway")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"mutation { updateViewerProfile(displayName: \\\"  New nickname  \\\") { displayName } }\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.updateViewerProfile.displayName").value("New nickname"));

        mockMvc.perform(post("/gateway")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"mutation { updateViewerProfile(displayName: \\\"   \\\") { displayName } }\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isArray());
        mockMvc.perform(post("/gateway")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"mutation { updateViewerProfile(displayName: \\\"%s\\\") { displayName } }\"}".formatted("n".repeat(81))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").isArray());
    }

    private MvcResult requestCode(
        String email,
        String password,
        RequestPostProcessor authentication
    ) throws Exception {
        return mockMvc.perform(post("/session/email-change/code")
                .with(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(codeRequestBody(email, password)))
            .andExpect(status().isAccepted())
            .andReturn();
    }

    private MvcResult confirm(String email, String code) throws Exception {
        return mockMvc.perform(put("/session/email")
                .with(userRequest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emailChangeBody(email, PASSWORD, code)))
            .andReturn();
    }

    private String codeRequestBody(String email, String password) {
        return "{\"email\":\"%s\",\"currentPassword\":\"%s\"}"
            .formatted(email, password);
    }

    private String emailChangeBody(String email, String password, String code) {
        return "{\"email\":\"%s\",\"currentPassword\":\"%s\",\"code\":\"%s\"}"
            .formatted(email, password, code);
    }

    private RequestPostProcessor userRequest() {
        return jwt().jwt(token -> token
                .subject(USER_ID.toString())
                .claim("sid", CURRENT_SESSION_ID.toString())
                .claim("scope", "USER")
                .claim("roles", List.of("USER")))
            .authorities(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("SCOPE_USER")
            );
    }

    private RequestPostProcessor otherUserRequest() {
        return jwt().jwt(token -> token
                .subject(OTHER_USER_ID.toString())
                .claim("sid", UUID.randomUUID().toString())
                .claim("scope", "USER")
                .claim("roles", List.of("USER")))
            .authorities(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("SCOPE_USER")
            );
    }

    private RequestPostProcessor adminRequest() {
        return jwt().jwt(token -> token
                .subject(USER_ID.toString())
                .claim("sid", ADMIN_SESSION_ID.toString())
                .claim("scope", "ADMIN")
                .claim("roles", List.of("ADMIN")))
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_ADMIN")
            );
    }

    private void insertUser(UUID id, String email, String displayName) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO users
              (id, email, password_hash, display_name, status, email_verified_at,
               created_at, updated_at, balance_minor, subscription_token,
               remind_expire, remind_traffic, proxy_uuid)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1234, ?, false, true, ?)
            """,
            id,
            email,
            passwordEncoder.encode(PASSWORD),
            displayName,
            Timestamp.from(now.minusSeconds(100)),
            Timestamp.from(now),
            Timestamp.from(now),
            UUID.randomUUID().toString().replace("-", ""),
            id
        );
        jdbc.update("INSERT INTO user_roles (user_id, role_code) VALUES (?, 'USER')", id);
    }

    private void insertSession(UUID id, UUID userId, String scope) {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO device_sessions
              (id, user_id, session_family_id, refresh_token_hash, device_label,
               session_scope, created_at, last_used_at, expires_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """,
            id,
            userId,
            id,
            UUID.randomUUID().toString().replace("-", "").repeat(2),
            "test device",
            scope,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(86400))
        );
    }

    private Instant revokedAt(UUID sessionId) {
        return jdbc.queryForObject(
            "SELECT revoked_at FROM device_sessions WHERE id = ?",
            (result, row) -> result.getTimestamp(1) == null
                ? null
                : result.getTimestamp(1).toInstant(),
            sessionId
        );
    }

    private String emailHash(String email) {
        return tokenService.hashOpaqueToken(email);
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        RecordingEmailChangeCodeMailSender recordingEmailChangeCodeMailSender() {
            return new RecordingEmailChangeCodeMailSender();
        }
    }

    static class RecordingEmailChangeCodeMailSender implements EmailChangeCodeMailSender {
        private final AtomicReference<String> latestCode = new AtomicReference<>();
        private final AtomicReference<String> latestRecipient = new AtomicReference<>();
        private volatile boolean failDelivery;

        @Override
        public void sendEmailChangeCode(String recipient, String code) {
            if (failDelivery) {
                throw new IllegalStateException("test mail delivery failure");
            }
            latestRecipient.set(recipient);
            latestCode.set(code);
        }

        String latestCode() {
            return latestCode.get();
        }
    }
}
