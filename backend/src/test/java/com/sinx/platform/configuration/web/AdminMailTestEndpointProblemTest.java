package com.sinx.platform.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sinx.platform.notification.email.ConfiguredNotificationMailSender;

/**
 * The real production incident: an administrator pressed the test-mail
 * button with SMTP configured but an unverified sender domain, the relay
 * answered {@code 550 The dev.sinx.it.com domain is not verified}, and the
 * endpoint surfaced a bare 500 without that line. Reproduced here with a
 * fake SMTP relay that refuses the recipient, asserting what each admin
 * test-mail endpoint does with the refusal: the config test-mail has no
 * mapping of its own anymore, so the transport failure surfaces as the
 * shared 500 with the full text in the server log; the template test wraps
 * it into {@code MAIL_SEND_FAILED} carrying the relay's own line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AdminMailTestEndpointProblemTest {

    private static final UUID ADMIN_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000a001"
    );
    private static final String ADMIN_EMAIL = "test-mail-admin@example.com";
    private static final String REFUSAL =
        "550 The dev.sinx.it.com domain is not verified";

    @Container
    static final PostgreSQLContainer POSTGRES =
        new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("sinx_mail_refusal_test")
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

    @BeforeEach
    void createAdministrator() {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
            INSERT INTO users (
                id, email, password_hash, display_name, status,
                created_at, updated_at, subscription_token
            ) VALUES (?::uuid, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """,
            ADMIN_ID.toString(),
            ADMIN_EMAIL,
            "not-used-for-login",
            "Test Mail Admin",
            Timestamp.from(now),
            Timestamp.from(now),
            ADMIN_ID.toString()
        );
    }

    @AfterEach
    void clearMailSettings() {
        jdbcTemplate.update(
            "DELETE FROM users WHERE id = ?::uuid", ADMIN_ID.toString());
        jdbcTemplate.update(
            "DELETE FROM platform_settings WHERE setting_key IN "
                + "('email.email_host', 'email.email_port', "
                + "'email.email_username', 'email.email_password', "
                + "'email.email_encryption', 'email.email_from_address')"
        );
    }

    private RequestPostProcessor administrator() {
        return jwt()
            .jwt(jwt -> jwt.subject(ADMIN_ID.toString()))
            .authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_ADMIN")
            );
    }

    @Test
    void configTestMailSurfacesTheFailureWithoutItsOwnMapping()
        throws Exception {
        try (RefusingSmtpRelay relay = new RefusingSmtpRelay()) {
            saveSmtpSettings(relay.port());

            // The refusal propagates unhandled: the shared error handling
            // logs the full exception text and answers the generic problem.
            mockMvc.perform(post("/api/v2/admin/config/testSendMail")
                    .with(administrator()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        }
    }

    @Test
    void templateTestMailCarriesTheSameFailureProblem() throws Exception {
        try (RefusingSmtpRelay relay = new RefusingSmtpRelay()) {
            saveSmtpSettings(relay.port());

            mockMvc.perform(post("/api/v2/admin/mail/template/test")
                    .with(administrator())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"verify","email":"template-test@example.com"}
                        """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("MAIL_SEND_FAILED"))
                .andExpect(jsonPath("$.detail").value(
                    "The mail could not be sent: " + REFUSAL));
        }
    }

    /**
     * A bare {@code MailSendException} message (the "Failed messages: ..."
     * summary, which is multi-line) surfaces collapsed onto one line, and a
     * very long server answer is trimmed, so the problem detail stays
     * one-line toast text.
     */
    @Test
    void theFailureDetailStaysOneShortLine() {
        MailSendException multiLine = new MailSendException(
            "Failed messages: jakarta.mail.SendFailedException: 550 Blocked\n"
                + "for assistance see http://www.example.com/errors\n"
                + "and Your IP address has been rejected"
        );

        assertThat(ConfiguredNotificationMailSender
            .sendFailureDetail(multiLine))
        .doesNotContain("\n");
        assertThat(ConfiguredNotificationMailSender
            .sendFailureDetail(new MailSendException("550 " + "x".repeat(500))))
            .hasSize(241)
            .endsWith("…");
        assertThat(ConfiguredNotificationMailSender
            .sendFailureDetail(new IllegalStateException())).isNotBlank();
    }

    private void saveSmtpSettings(int port) {
        jdbcTemplate.update(
            "INSERT INTO platform_settings (setting_key, setting_value, "
                + "updated_at) VALUES (?, 'smtp', NOW()) "
                + "ON CONFLICT (setting_key) "
                + "DO UPDATE SET setting_value = EXCLUDED.setting_value",
            "email.email_delivery");
        // No encryption: the fake relay speaks plain SMTP, and the read
        // default ("ssl") would close the connection during the handshake
        // before the relay can refuse anything.
        jdbcTemplate.update(
            "INSERT INTO platform_settings (setting_key, setting_value, "
                + "updated_at) VALUES ('email.email_encryption', '', NOW()) "
                + "ON CONFLICT (setting_key) "
                + "DO UPDATE SET setting_value = EXCLUDED.setting_value");
        saveSetting("email.email_host", "127.0.0.1");
        saveSetting("email.email_port", String.valueOf(port));
        saveSetting("email.email_username", "resend@example.test");
        saveSetting("email.email_password", "app-password");
        saveSetting(
            "email.email_from_address", "no-reply@sinx.example.test");
    }

    private void saveSetting(String key, String value) {
        jdbcTemplate.update(
            "INSERT INTO platform_settings (setting_key, setting_value, "
                + "updated_at) VALUES (?, ?, NOW()) ON CONFLICT (setting_key) "
                + "DO UPDATE SET setting_value = EXCLUDED.setting_value",
            key, value);
    }

    /**
     * A one-shot SMTP relay that accepts the greeting, the envelope sender
     * and the DATA entrance, then refuses the recipient with the incident's
     * exact line - so {@code send} builds a real per-message refusal carrying
     * the remote server's text, not a stubbed exception.
     */
    static final class RefusingSmtpRelay implements AutoCloseable {

        private final ServerSocket server;
        private final Thread acceptor;
        private final int port;

        RefusingSmtpRelay() throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.port = server.getLocalPort();
            this.acceptor = Thread.ofVirtual().unstarted(this::serve);
            this.acceptor.start();
        }

        int port() {
            return port;
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                writer.write("220 relay ready\r\n");
                writer.flush();
                String line;
                while ((line = reader.readLine()) != null) {
                    String command = line.toLowerCase();
                    if (command.startsWith("ehlo")
                        || command.startsWith("helo")
                        || command.startsWith("mail from")
                        || command.startsWith("rcpt to")
                        || command.startsWith("rset")) {
                        respond(writer, "250 ok");
                    } else if (command.startsWith("data")) {
                        respond(writer, "354 go ahead");
                        // Read the message body up to the terminator dot.
                        while ((line = reader.readLine()) != null
                            && !line.equals(".")) {
                            // Discard.
                        }
                        respond(writer, REFUSAL);
                        return;
                    } else if (command.equals("quit")) {
                        respond(writer, "221 bye");
                        return;
                    }
                }
            } catch (IOException ignored) {
                // A refused SMTP exchange is expected to end abruptly.
            }
        }

        private void respond(BufferedWriter writer, String line)
            throws IOException {
            writer.write(line + "\r\n");
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                acceptor.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
