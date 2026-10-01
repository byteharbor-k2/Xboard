package com.sinx.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * The remaining user-management actions: the CSV export, the one-off mail,
 * and the inviter assignment the invitation-filled registration also writes.
 *
 * The actions share the same rule: nothing is remembered about them. The
 * export streams the rows the list already serves; the mail is transported
 * and forgotten; the inviter is the single column registration writes, so
 * assigning it only refuses what registration could never produce - an
 * unknown inviter, the account naming itself, a looping chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminUserActionsIntegrationTest.TestMailConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminUserActionsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_actions_test")
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

    @Test
    void exportCsvCarriesTheHeaderARowAndTheInjectionGuard() throws Exception {
        String email = "csv-export@example.com";
        UUID userId = register(email);
        // An operator note is the one free-text column, so it has to survive
        // the export as text and not as a formula for the desktop to run.
        mockMvc.perform(post("/api/v2/admin/user/update")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id":"%s","remarks":"=HYPERLINK(\\"http://evil\\",\\"click\\")"}
                    """.formatted(userId)))
            .andExpect(status().isOk());

        MvcResult export = mockMvc.perform(get("/api/v2/admin/user/exportCsv")
                .with(administrator()))
            .andExpect(status().isOk())
            .andExpect(header().string(
                org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.containsString("attachment")))
            .andReturn();
        String csv = export.getResponse().getContentAsString();

        assertThat(csv.split("\n")[0]).isEqualTo(
            "id,node_user_id,email,status,banned,email_verified,remarks,"
                + "speed_limit_mbps,balance,plan_id,plan_name,"
                + "transfer_limit_bytes,used_bytes,online_devices,expires_at,"
                + "last_login_at,created_at"
        );
        // The account is in there, with the normal status and node identity.
        assertThat(csv).contains(email);
        assertThat(csv).contains(",ACTIVE,");

        // The remark's leading = is the one that must not reach a formula.
        // The cell carries a comma, so it is also fully quoted.
        String guardedRow = java.util.Arrays.stream(csv.split("\n"))
            .filter(line -> line.contains(email))
            .findFirst()
            .orElseThrow();
        assertThat(guardedRow).contains(
            "\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\""
        );
    }

    @Test
    void sendMailAnswersOnceAndStoresNothing() throws Exception {
        String email = "mail-victim@example.com";
        UUID userId = register(email);

        mockMvc.perform(post("/api/v2/admin/user/sendMail")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "user_id": "%s",
                      "subject": "A letter from the panel",
                      "body": "<p>Hello again.</p>"
                    }
                    """.formatted(userId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        // An unknown account is refused, not silently skipped.
        mockMvc.perform(post("/api/v2/admin/user/sendMail")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "user_id": "%s",
                      "subject": "No one",
                      "body": "No one"
                    }
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        // The sent mail left no state behind: the account is untouched.
        Integer userCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?::uuid",
            Integer.class,
            userId.toString()
        );
        assertThat(userCount).isEqualTo(1);
    }

    /**
     * In smtp delivery with the settings still missing, the send attempt
     * reaches the transport and its own complaint surfaces - the request
     * fails (5xx) and the underlying error is logged for the operator;
     * there is no separate "settings incomplete" answer of its own.
     */
    @Test
    void sendMailInSmtpModeWithoutSettingsFailsToTheUnderlyingError()
        throws Exception {
        String email = "mail-smtp@example.com";
        UUID userId = register(email);
        jdbcTemplate.update(
            "INSERT INTO platform_settings (setting_key, setting_value, "
                + "updated_at) VALUES ('email.email_delivery', 'smtp', NOW()) "
                + "ON CONFLICT (setting_key) "
                + "DO UPDATE SET setting_value = EXCLUDED.setting_value"
        );
        try {
            mockMvc.perform(post("/api/v2/admin/user/sendMail")
                    .with(administrator())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "user_id": "%s",
                          "subject": "Cannot travel",
                          "body": "<p>No transport.</p>"
                        }
                        """.formatted(userId)))
                .andExpect(status().isInternalServerError());
        } finally {
            jdbcTemplate.update(
                "DELETE FROM platform_settings "
                    + "WHERE setting_key = 'email.email_delivery'"
            );
        }
    }

    @Test
    void assignInviterWiresTheExistingColumnAndRefusesBadEdges()
        throws Exception {
        String inviterEmail = "chain-inviter@example.com";
        UUID inviterUserId = register(inviterEmail);
        String memberEmail = "chain-member@example.com";
        UUID memberUserId = register(memberEmail);
        String middleEmail = "chain-middle@example.com";
        UUID middleUserId = register(middleEmail);

        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(memberUserId, inviterUserId)))
            .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT inviter_user_id FROM users WHERE id = ?::uuid",
            UUID.class,
            memberUserId.toString()
        )).isEqualTo(inviterUserId);

        // The member's own record cannot be rewritten by a second link.
        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":null}
                    """.formatted(memberUserId)))
            .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT inviter_user_id FROM users WHERE id = ?::uuid",
            UUID.class,
            memberUserId.toString()
        )).isNull();

        // Restoring it, then trying to make the inviter point back at the
        // member through the middle account, would close a loop: refused.
        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(memberUserId, inviterUserId)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(inviterUserId, memberUserId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVITER_CYCLE"));

        // Self-naming and unknown inviters are refused the same way.
        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(memberUserId, memberUserId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVITER_INVALID"));
        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(memberUserId, UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INVITER_NOT_FOUND"));

        // The refusals above left the inviter exactly as it was.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT inviter_user_id FROM users WHERE id = ?::uuid",
            UUID.class,
            memberUserId.toString()
        )).isEqualTo(inviterUserId);
    }

    /**
     * A chain that already loops (written here directly, the way a
     * half-finished manual edit or an old bug could have left it) must fail
     * the next assignment promptly: the walk guards with a visited set
     * instead of spinning on the stored cycle.
     */
    @Test
    void assignInviterRefusesAPreexistingCycleInsteadOfHanging()
        throws Exception {
        String emailA = "cycle-a@example.com";
        UUID userA = register(emailA);
        String emailB = "cycle-b@example.com";
        UUID userB = register(emailB);

        jdbcTemplate.update(
            "UPDATE users SET inviter_user_id = ?::uuid WHERE id = ?::uuid",
            userB.toString(),
            userA.toString()
        );
        jdbcTemplate.update(
            "UPDATE users SET inviter_user_id = ?::uuid WHERE id = ?::uuid",
            userA.toString(),
            userB.toString()
        );

        String emailC = "cycle-c@example.com";
        UUID userC = register(emailC);

        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(userC, userA)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVITER_CYCLE"));

        // And closing the loop from the other side is refused the same way.
        mockMvc.perform(post("/api/v2/admin/user/assignInviter")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"user_id":"%s","inviter_user_id":"%s"}
                    """.formatted(userA, userB)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVITER_CYCLE"));
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    private UUID register(String email) throws Exception {
        redisTemplate.delete(
            redisTemplate.keys("identity:registration-ip:*")
        );
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
                      "password": "actions-password",
                      "displayName": "Actions Fixture",
                      "deviceLabel": "Actions Browser",
                      "emailCode": "%s"
                    }
                    """.formatted(email, registrationCodeMailSender.latestCode())))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonPath.read(
            registration.getResponse().getContentAsString(),
            "$.viewer.id"
        );
        return UUID.fromString(id);
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
