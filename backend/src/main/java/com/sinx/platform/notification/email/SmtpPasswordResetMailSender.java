package com.sinx.platform.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Component
@ConditionalOnProperty(name = "sinx.mail.delivery", havingValue = "smtp")
public class SmtpPasswordResetMailSender implements PasswordResetMailSender {

    private final JavaMailSender mailSender;
    private final VerificationMailProperties properties;

    public SmtpPasswordResetMailSender(
        JavaMailSender mailSender,
        VerificationMailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendPasswordReset(
        String recipient,
        String displayName,
        String resetUrl
    ) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                message,
                false,
                "UTF-8"
            );
            helper.setFrom(properties.from());
            helper.setTo(recipient);
            helper.setSubject("Reset your password");
            helper.setText(
                """
                <p>Hello %s,</p>
                <p>Use the link below to set a new password.</p>
                <p><a href="%s">Reset password</a></p>
                <p>If you did not request this change, ignore this email.</p>
                """.formatted(escapeHtml(displayName), resetUrl),
                true
            );
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException(
                "Could not create password reset email",
                exception
            );
        }
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
