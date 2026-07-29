package com.sinx.platform.notification.email;

public interface PasswordResetMailSender {

    void sendPasswordReset(
        String recipient,
        String displayName,
        String resetUrl
    );
}
