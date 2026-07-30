package com.sinx.platform.notification.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;

@Component
@ConditionalOnProperty(name = "sinx.mail.delivery", havingValue = "smtp")
public class SmtpRegistrationCodeMailSender
    implements RegistrationCodeMailSender {

    private final JavaMailSender mailSender;
    private final VerificationMailProperties properties;

    public SmtpRegistrationCodeMailSender(
        JavaMailSender mailSender,
        VerificationMailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendRegistrationCode(String recipient, String code) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                message,
                false,
                "UTF-8"
            );
            helper.setFrom(properties.from());
            helper.setTo(recipient);
            helper.setSubject("Your SinX Cloud registration code");
            helper.setText(
                """
                <p>Use the following code to complete registration:</p>
                <p style="font-size:24px;font-weight:700;letter-spacing:4px">%s</p>
                <p>This code expires in 5 minutes. If you did not request it, ignore this email.</p>
                """.formatted(code),
                true
            );
            mailSender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException(
                "Could not create registration code email",
                exception
            );
        }
    }
}
