package com.sinx.platform.notification.email;

public interface VerificationMailSender {

    void sendVerification(
        String recipient,
        String displayName,
        String verificationUrl
    );
}
