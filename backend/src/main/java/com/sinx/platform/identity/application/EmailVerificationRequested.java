package com.sinx.platform.identity.application;

public record EmailVerificationRequested(
    String recipient,
    String displayName,
    String rawToken
) {
}
