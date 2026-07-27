package com.sinx.platform.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "sinx.mail")
public record VerificationMailProperties(
    @NotBlank String publicBaseUrl,
    @NotBlank String from
) {
}
