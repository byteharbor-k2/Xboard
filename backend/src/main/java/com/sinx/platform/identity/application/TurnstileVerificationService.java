package com.sinx.platform.identity.application;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import com.sinx.platform.identity.security.RegistrationSecurityProperties;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class TurnstileVerificationService {

    private static final String VERIFY_URL =
        "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final RestClient restClient;
    private final RegistrationSecurityProperties properties;

    public TurnstileVerificationService(
        RegistrationSecurityProperties properties
    ) {
        SimpleClientHttpRequestFactory requestFactory =
            new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
            .requestFactory(requestFactory)
            .build();
        this.properties = properties;
    }

    public void verify(String token, String remoteIp) {
        if (!properties.turnstileEnabled()) {
            return;
        }
        if (properties.turnstileSecretKey() == null
                || properties.turnstileSecretKey().isBlank()) {
            throw new ApiProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TURNSTILE_NOT_CONFIGURED",
                "Registration verification is temporarily unavailable"
            );
        }
        if (token == null || token.isBlank()) {
            throw invalidTurnstile();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.turnstileSecretKey());
        form.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank()) {
            form.add("remoteip", remoteIp);
        }
        try {
            TurnstileResponse result = restClient.post()
                .uri(VERIFY_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TurnstileResponse.class);
            if (result == null || !result.success()) {
                throw invalidTurnstile();
            }
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ApiProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TURNSTILE_UNAVAILABLE",
                "Registration verification is temporarily unavailable"
            );
        }
    }

    private ApiProblemException invalidTurnstile() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "TURNSTILE_INVALID",
            "Complete the human verification and try again"
        );
    }

    private record TurnstileResponse(boolean success) {
    }
}
