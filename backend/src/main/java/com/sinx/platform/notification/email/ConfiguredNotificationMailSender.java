package com.sinx.platform.notification.email;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Component
public class ConfiguredNotificationMailSender
    implements RegistrationCodeMailSender,
        PasswordResetMailSender,
        EmailChangeCodeMailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ConfiguredNotificationMailSender.class
    );

    private final PlatformConfigurationService configuration;

    public ConfiguredNotificationMailSender(
        PlatformConfigurationService configuration
    ) {
        this.configuration = configuration;
    }

    @Override
    public void sendRegistrationCode(String recipient, String code) {
        deliver(
            recipient,
            "Your SinX Cloud registration code",
            """
            <p>Use the following code to complete registration:</p>
            <p style="font-size:24px;font-weight:700;letter-spacing:4px">%s</p>
            <p>This code expires in 5 minutes. If you did not request it, ignore this email.</p>
            """.formatted(escapeHtml(code)),
            "registration code " + code
        );
    }

    @Override
    public void sendEmailChangeCode(String recipient, String code) {
        deliver(
            recipient,
            "Your SinX Cloud email change code",
            """
            <p>Use the following code to confirm your new email address:</p>
            <p style="font-size:24px;font-weight:700;letter-spacing:4px">%s</p>
            <p>This code expires in 5 minutes. If you did not request this change, ignore this email.</p>
            """.formatted(escapeHtml(code)),
            "email change verification code " + code
        );
    }

    @Override
    public void sendPasswordReset(
        String recipient,
        String displayName,
        String resetUrl
    ) {
        deliver(
            recipient,
            "Reset your SinX Cloud password",
            """
            <p>Hello %s,</p>
            <p>Use the link below to set a new password.</p>
            <p><a href="%s">Reset password</a></p>
            <p>If you did not request this change, ignore this email.</p>
            """.formatted(escapeHtml(displayName), escapeHtml(resetUrl)),
            "password reset link " + resetUrl
        );
    }

    /**
     * Delivers a fully rendered message, the seam the admin mail-template
     * test endpoint uses: the subject and body already carry every
     * placeholder substitution, so this only transports.
     */
    public void sendHtml(String recipient, String subject, String html) {
        deliver(recipient, subject, html, subject);
    }

    /**
     * The settings page's SMTP test mail. Transported like any other admin
     * mail: log mode logs and succeeds, and a failing smtp transport raises
     * its own exception for the shared error handling to log - the operator
     * reads the logs; this endpoint carries no separate failure taxonomy.
     */
    public void sendTestEmail(String recipient) {
        deliver(
            recipient,
            "SinX Cloud SMTP test",
            """
            <p>Your SinX Cloud SMTP configuration is working.</p>
            <p>This message was requested from the administrator control center.</p>
            """,
            "SMTP test"
        );
    }

    /**
     * The one-line diagnosis of a failed delivery: the first failed
     * message's text when {@code send} collected per-message refusals
     * (that text is the remote server's response, e.g. Resend's
     * {@code 550 <domain> is not verified}), the exception message
     * otherwise. Nothing else travels - no stack trace and no credentials:
     * the transport never echoes the password back.
     */
    public static String sendFailureDetail(Exception exception) {
        String detail = exception.getMessage();
        if (exception instanceof MailSendException sendException
            && !sendException.getFailedMessages().isEmpty()) {
            detail = sendException.getFailedMessages().values()
                .iterator().next().getMessage();
        }
        if (detail == null || detail.isBlank()) {
            return "the SMTP transport rejected the message";
        }
        String compact = detail.replaceAll("\\s+", " ").trim();
        return compact.length() > 240
            ? compact.substring(0, 240) + "…"
            : compact;
    }

    private void deliver(
        String recipient,
        String subject,
        String html,
        String developmentContent
    ) {
        PlatformConfigurationService.MailSettings settings =
            configuration.mailSettings();
        if (!settings.configured()) {
            if ("log".equalsIgnoreCase(configuration.mailDelivery())) {
                LOGGER.info(
                    "Development mail for {}: {}",
                    recipient,
                    developmentContent
                );
                return;
            }
            throw new IllegalStateException(
                "SMTP settings are incomplete"
            );
        }

        JavaMailSenderImpl sender = sender(settings);
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                message,
                false,
                "UTF-8"
            );
            helper.setFrom(settings.fromAddress());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException(
                "Could not create email message",
                exception
            );
        }
    }

    private JavaMailSenderImpl sender(
        PlatformConfigurationService.MailSettings settings
    ) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.host());
        sender.setPort(settings.port());
        sender.setUsername(settings.username());
        sender.setPassword(settings.password());
        sender.setDefaultEncoding("UTF-8");

        Properties properties = sender.getJavaMailProperties();
        properties.put(
            "mail.smtp.auth",
            Boolean.toString(settings.username() != null)
        );
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        if ("ssl".equals(settings.encryption())) {
            properties.put("mail.smtp.ssl.enable", "true");
        } else if ("tls".equals(settings.encryption())) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        return sender;
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
