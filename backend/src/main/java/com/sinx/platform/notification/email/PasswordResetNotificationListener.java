package com.sinx.platform.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

import com.sinx.platform.identity.application.PasswordResetRequested;

@Component
public class PasswordResetNotificationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        PasswordResetNotificationListener.class
    );

    private final PasswordResetMailSender mailSender;
    private final VerificationMailProperties properties;

    public PasswordResetNotificationListener(
        PasswordResetMailSender mailSender,
        VerificationMailProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(PasswordResetRequested event) {
        String resetUrl = UriComponentsBuilder
            .fromUriString(properties.publicBaseUrl())
            .path("/reset-password")
            .queryParam("token", event.rawToken())
            .build()
            .encode()
            .toUriString();
        try {
            mailSender.sendPasswordReset(
                event.recipient(),
                event.displayName(),
                resetUrl
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Password reset delivery failed", exception);
        }
    }
}
