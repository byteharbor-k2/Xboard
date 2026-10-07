package com.sinx.platform.notification.email;

public interface EmailChangeCodeMailSender {

    void sendEmailChangeCode(String recipient, String code);
}
