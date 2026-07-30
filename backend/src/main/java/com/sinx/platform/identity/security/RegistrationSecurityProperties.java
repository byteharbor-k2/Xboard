package com.sinx.platform.identity.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "sinx.registration")
public record RegistrationSecurityProperties(
    @NotNull Duration emailCodeTtl,
    @NotNull Duration emailCodeCooldown,
    @Min(1) int maxCodeAttempts,
    @Min(1) int maxRegistrationsPerIp,
    @NotNull Duration registrationWindow,
    boolean turnstileEnabled,
    String turnstileSiteKey,
    String turnstileSecretKey
) {
}
