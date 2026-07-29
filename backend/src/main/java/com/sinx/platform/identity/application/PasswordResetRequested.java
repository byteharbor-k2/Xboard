package com.sinx.platform.identity.application;

public record PasswordResetRequested(
    String recipient,
    String displayName,
    String rawToken
) {
}
