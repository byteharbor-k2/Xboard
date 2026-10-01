package com.sinx.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;
import com.sinx.platform.shared.web.ApiProblemException;
import org.mockito.Mockito;

/**
 * The mail transport mode is an administrator choice, not a deployment
 * constant: "log" drops every mail to the log so a development box needs no
 * credentials, "smtp" routes through the stored SMTP settings. A saved value
 * outranks the sinx.mail.delivery property - which keeps this no-saved-row
 * context (whose property says "smtp") exercising exactly the precedence the
 * settings page promises.
 */
@SpringBootTest(
    properties = "sinx.mail.delivery=smtp"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DeliveryModeIntegrationTest {

    private static final String DELIVERY_KEY = "email.email_delivery";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_delivery_mode_test")
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
    private MockMvc mockMvc;

    /** The seam every notification mail leaves through. */
    @MockitoSpyBean
    private ConfiguredNotificationMailSender mailSender;

    /**
     * Each test starts from the deployment property again: one row decides,
     * so every test owns its full save/delete cycle.
     */
    @AfterEach
    void clearDeliverySettings() {
        jdbcTemplate.update(
            "DELETE FROM platform_settings WHERE setting_key IN (?, "
                + "'email.email_host', 'email.email_port', "
                + "'email.email_username', 'email.email_password', "
                + "'email.email_from_address')",
            DELIVERY_KEY
        );
    }

    @Test
    void emailSectionShowsTheDeploymentModeAsTheUnsavedDefault()
        throws Exception {
        String body = fetchEmail();

        // Nothing was saved, so the property (smtp in this context) is what
        // the section shows - which is also what the sender obeys here.
        expect(body, "$.data.email.email_delivery", "smtp");
        // The write-only sender password stays masked on read.
        expect(body, "$.data.email.email_password", "");
    }

    @Test
    void deliveryModeRoundTripsThroughSaveAndFetch() throws Exception {
        saveSetting("email_delivery", "log");
        expect(fetchEmail(), "$.data.email.email_delivery", "log");

        saveSetting("email_delivery", "smtp");
        expect(fetchEmail(), "$.data.email.email_delivery", "smtp");
    }

    @Test
    void unknownDeliveryValuesAreRefusedWithAValueProblem() throws Exception {
        // A supported key with a bad value is a value problem - never a
        // section problem.
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "email")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email_delivery":"resend"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SETTING_VALUE_INVALID"));

        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "email")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email_delivery":true}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SETTING_VALUE_INVALID"));

        // The saved state never overwrote any existing value along the way.
        saveSetting("email_delivery", "log");
        expect(fetchEmail(), "$.data.email.email_delivery", "log");
    }

    @Test
    void theDeploymentPropertyAppliesUntilTheAdministratorSaves()
        throws Exception {
        // No saved row, environmental property smtp, SMTP settings absent:
        // the test mail answers 503 instead of a fake success.
        expectUnconfiguredTestMailIsRefused();

        // Once "log" is saved it outranks the property: the very same call
        // completes quietly by logging, in "smtp" mode by environment.
        saveSetting("email_delivery", "log");
        mailSender.sendTestEmail(
            "delivery-precedence@example.com"
        );

        // Deleting the saved row hands the decision back to the property.
        jdbcTemplate.update(
            "DELETE FROM platform_settings WHERE setting_key = ?",
            DELIVERY_KEY
        );
        expectUnconfiguredTestMailIsRefused();
    }

    @Test
    void smtpModeWithCompleteSettingsSendsWhileLogModeStaysSilent()
        throws Exception {
        saveSettings(
            "email_delivery", "smtp",
            "email_host", "localhost",
            "email_port", 587,
            "email_encryption", "tls",
            "email_username", "resend@example.test",
            "email_password", "app-password",
            "email_from_address", "no-reply@example.test"
        );

        // The complete settings pass the completeness gate for a test-mail,
        // and anyway the section must show the tls option it accepts - the
        // Resend-recommended STARTTLS setup.
        expect(fetchEmail(), "$.data.email.email_delivery", "smtp");
        expect(fetchEmail(), "$.data.email.email_encryption", "tls");

        // What "the send goes out" means here: the sender turns the stored
        // settings into a JavaMailSenderImpl and opens a socket to them. The
        // listener accepts so the exchange is observable, then closes so the
        // protocol exchange fails fast, which javamail surfaces as a send
        // failure rather than the 503 of an incomplete configuration.
        try (TestSmtpListener listener = new TestSmtpListener()) {
            saveSetting("email_port", listener.port());

            assertThatThrownByIsASocketSend(mailSender, listener);
        }

        Mockito.reset(mailSender);

        // Log mode with complete settings still sends for real (log is the
        // fallback for an unconfigured host, not an override of working
        // settings). Its silence shows where it actually applies: with the
        // settings gone, the same call drops to the log instead of failing.
        saveSetting("email_delivery", "log");
        jdbcTemplate.update(
            "DELETE FROM platform_settings WHERE setting_key IN "
                + "('email.email_host', 'email.email_port', "
                + "'email.email_username', 'email.email_password')"
        );

        mailSender.sendTestEmail("delivery-log@example.com");
    }

    @Test
    void theTemplateTestDeliversThroughSendHtmlInCompleteSmtpMode()
        throws Exception {
        saveSettings(
            "email_delivery", "smtp",
            "email_host", "localhost",
            "email_port", 465,
            "email_encryption", "ssl",
            "email_username", "resend@example.test",
            "email_password", "app-password",
            "email_from_address", "no-reply@example.test"
        );

        AtomicReference<String> recipient = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            recipient.set(invocation.getArgument(0));
            return null;
        }).when(mailSender).sendHtml(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString()
        );

        try {
            mockMvc.perform(post("/api/v2/admin/mail/template/test")
                    .with(administrator())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"verify","email":"template@delivery.test"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

            assertThat(recipient.get())
                .isEqualTo("template@delivery.test");
        } finally {
            Mockito.doCallRealMethod().when(mailSender).sendHtml(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyString()
            );
        }
    }

    private void expectUnconfiguredTestMailIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            mailSender.sendTestEmail("delivery-precedence@example.com")
        ).isInstanceOfSatisfying(ApiProblemException.class, exception -> {
            assertThat(exception.getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exception.getCode()).isEqualTo("SMTP_NOT_CONFIGURED");
        });
    }

    private void assertThatThrownByIsASocketSend(
        ConfiguredNotificationMailSender sender,
        TestSmtpListener listener
    ) throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            sender.sendTestEmail("delivery-socket@example.com")
        ).isInstanceOf(MailException.class);
        // The refused exchange really opened a socket to the stored host.
        assertThat(listener.accepted().get(5, TimeUnit.SECONDS)).isNotNull();
    }

    private String fetchEmail() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/admin/config/fetch")
                .param("key", "email")
                .with(administrator()))
            .andExpect(status().isOk())
            .andReturn();
        return result.getResponse().getContentAsString();
    }

    private void saveSetting(String key, Object value) throws Exception {
        String encoded = value instanceof Boolean bool
            ? String.valueOf(bool)
            : value instanceof Number number
                ? String.valueOf(number)
                : '"' + String.valueOf(value) + '"';
        mockMvc.perform(post("/api/v2/admin/config/save")
                .param("key", "email")
                .with(administrator())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"%s":%s}
                    """.formatted(key, encoded)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
    }

    /**
     * The admin surface takes exactly one setting per save, so a multi-key
     * fixture is a loop of single saves.
     */
    private void saveSettings(Object... keyValues) throws Exception {
        for (int index = 0; index < keyValues.length; index += 2) {
            saveSetting((String) keyValues[index], keyValues[index + 1]);
        }
    }

    private void expect(String body, String path, Object expected) {
        assertThat(JsonPath.<Object>read(body, path)).isEqualTo(expected);
    }

    private RequestPostProcessor administrator() {
        return jwt().authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("SCOPE_ADMIN")
        );
    }

    /**
     * A socket the SMTP client connects to. Accepts asynchronously and
     * closes the connection immediately, so the client fails its protocol
     * exchange in milliseconds instead of waiting out a remote timeout.
     */
    private static final class TestSmtpListener implements AutoCloseable {

        private final ServerSocket socket;
        private final CompletableFuture<Object> accepted =
            new CompletableFuture<>();

        TestSmtpListener() throws Exception {
            this.socket = new ServerSocket(
                0,
                1,
                InetAddress.getLoopbackAddress()
            );
            CompletableFuture.runAsync(() -> {
                try (socket) {
                    accepted.complete(socket.accept());
                } catch (Exception exception) {
                    accepted.completeExceptionally(exception);
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        /** The connection the mail sender opened, once it is in. */
        CompletableFuture<Object> accepted() {
            return accepted;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }
}
