package com.sinx.platform.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

import com.sinx.platform.identity.application.EmailVerificationRequested;

@Component
public class EmailVerificationNotificationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        EmailVerificationNotificationListener.class
    );

    private final VerificationMailSender mailSender;
    private final VerificationMailProperties properties;

    public EmailVerificationNotificationListener(
        VerificationMailSender mailSender,
        VerificationMailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(EmailVerificationRequested event) {
        String verificationUrl = UriComponentsBuilder
            .fromUriString(properties.publicBaseUrl())
            .path("/verify-email")
            .queryParam("token", event.rawToken())
            .build()
            .encode()
            .toUriString();
        try {
            mailSender.sendVerification(
                event.recipient(),
                event.displayName(),
                verificationUrl
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Email verification delivery failed for recipient hash context",
                exception
            );
        }
    }
}
