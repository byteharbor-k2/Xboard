package com.sinx.platform.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "sinx.mail.delivery",
    havingValue = "log",
    matchIfMissing = true
)
public class LoggingPasswordResetMailSender
    implements PasswordResetMailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        LoggingPasswordResetMailSender.class
    );

    @Override
    public void sendPasswordReset(
        String recipient,
        String displayName,
        String resetUrl
    ) {
        LOGGER.info(
            "Development password reset mail for {} ({}): {}",
            displayName,
            recipient,
            resetUrl
        );
    }
}
