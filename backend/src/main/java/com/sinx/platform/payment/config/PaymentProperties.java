package com.sinx.platform.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "sinx.payment")
public record PaymentProperties(
    /**
     * The address the outside world reaches this site on, used to build the
     * notify and return URLs handed to a gateway. It has to be reachable from
     * the gateway's own servers: a value that only resolves inside this network
     * means payments are taken and never confirmed.
     */
    @NotBlank String publicBaseUrl
) {
}
