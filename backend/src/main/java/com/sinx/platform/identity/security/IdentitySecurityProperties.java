package com.sinx.platform.identity.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Validated
@ConfigurationProperties(prefix = "sinx.security")
public record IdentitySecurityProperties(
    @NotBlank String issuer,
    @NotNull Duration accessTokenTtl,
    @NotNull Duration refreshTokenTtl,
    @NotNull Duration emailVerificationTtl,
    @NotNull Duration passwordResetTtl,
    @NotNull Duration mfaChallengeTtl,
    @NotBlank String mfaIssuer,
    @NotBlank String mfaEncryptionKey,
    @NotBlank @Size(min = 32) String jwtSecret,
    @NotBlank String refreshCookieName,
    boolean secureCookies
) {
}
