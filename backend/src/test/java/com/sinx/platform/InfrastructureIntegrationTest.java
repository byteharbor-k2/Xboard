package com.sinx.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
