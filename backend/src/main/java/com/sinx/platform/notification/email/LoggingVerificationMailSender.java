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
public class LoggingVerificationMailSender implements VerificationMailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        LoggingVerificationMailSender.class
    );

    @Override
    public void sendVerification(
        String recipient,
        String displayName,
        String verificationUrl
    ) {
        LOGGER.info(
            "Development verification mail for {} ({}): {}",
            displayName,
            recipient,
            verificationUrl
        );
    }
}
