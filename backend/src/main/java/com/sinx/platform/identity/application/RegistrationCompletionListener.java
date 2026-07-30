package com.sinx.platform.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RegistrationCompletionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        RegistrationCompletionListener.class
    );

    private final RegistrationVerificationService registrationVerification;

    public RegistrationCompletionListener(
        RegistrationVerificationService registrationVerification
    ) {
        this.registrationVerification = registrationVerification;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void complete(RegistrationCompleted event) {
        try {
            registrationVerification.completeRegistration(
                event.email(),
                event.remoteIp()
            );
        } catch (RuntimeException exception) {
            LOGGER.error(
                "Registration verification cleanup failed after commit",
                exception
            );
        }
    }
}
