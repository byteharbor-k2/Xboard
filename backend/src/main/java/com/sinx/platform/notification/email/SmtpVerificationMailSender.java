package com.sinx.platform.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Component
@ConditionalOnProperty(name = "sinx.mail.delivery", havingValue = "smtp")
public class SmtpVerificationMailSender implements VerificationMailSender {

    private final JavaMailSender mailSender;
    private final VerificationMailProperties properties;

    public SmtpVerificationMailSender(
        JavaMailSender mailSender,
        VerificationMailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendVerification(
        String recipient,
        String displayName,
        String verificationUrl
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
            helper.setSubject("Verify your email address");
            helper.setText(
                """
                <p>Hello %s,</p>
                <p>Confirm your email address to finish securing your account.</p>
                <p><a href="%s">Verify email address</a></p>
                <p>This link expires shortly. If you did not create this account, ignore this email.</p>
                """.formatted(escapeHtml(displayName), verificationUrl),
                true
            );
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException(
                "Could not create verification email",
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
