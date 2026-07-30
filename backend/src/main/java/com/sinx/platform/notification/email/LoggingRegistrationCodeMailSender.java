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
public class LoggingRegistrationCodeMailSender
    implements RegistrationCodeMailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        LoggingRegistrationCodeMailSender.class
    );

    @Override
    public void sendRegistrationCode(String recipient, String code) {
        LOGGER.info(
            "Development registration code for {}: {}",
            recipient,
            code
        );
    }
}
