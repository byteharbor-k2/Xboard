package com.sinx.platform.notification.email;

public interface RegistrationCodeMailSender {

    void sendRegistrationCode(String recipient, String code);
}
