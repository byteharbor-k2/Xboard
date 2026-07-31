package com.sinx.platform.notification.email;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.shared.web.ApiProblemException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Component
public class ConfiguredNotificationMailSender
    implements RegistrationCodeMailSender,
        VerificationMailSender,
        PasswordResetMailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        ConfiguredNotificationMailSender.class
    );

    private final PlatformConfigurationService configuration;
    private final String fallbackDelivery;

    public ConfiguredNotificationMailSender(
        PlatformConfigurationService configuration,
        @Value("${sinx.mail.delivery:log}") String fallbackDelivery
    ) {
        this.configuration = configuration;
        this.fallbackDelivery = fallbackDelivery;
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
    public void sendVerification(
        String recipient,
        String displayName,
        String verificationUrl
    ) {
        deliver(
            recipient,
            "Verify your email address",
            """
            <p>Hello %s,</p>
            <p>Confirm your email address to finish securing your account.</p>
            <p><a href="%s">Verify email address</a></p>
            <p>This link expires shortly. If you did not create this account, ignore this email.</p>
            """.formatted(
                escapeHtml(displayName),
                escapeHtml(verificationUrl)
            ),
            "verification link " + verificationUrl
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

    public void sendTestEmail(String recipient) {
        if (!configuration.mailSettings().configured()) {
            throw new ApiProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SMTP_NOT_CONFIGURED",
                "Complete the SMTP settings before sending a test email"
            );
        }
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

    private void deliver(
        String recipient,
        String subject,
        String html,
        String developmentContent
    ) {
        PlatformConfigurationService.MailSettings settings =
            configuration.mailSettings();
        if (!settings.configured()) {
            if ("log".equalsIgnoreCase(fallbackDelivery)) {
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
