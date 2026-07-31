package com.sinx.platform.identity.application;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.identity.security.RegistrationSecurityProperties;
import com.sinx.platform.notification.email.RegistrationCodeMailSender;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class RegistrationVerificationService {

    private static final String CODE_PREFIX = "identity:registration-code:";
    private static final String COOLDOWN_PREFIX =
        "identity:registration-code-cooldown:";
    private static final String IP_PREFIX = "identity:registration-ip:";

    private final StringRedisTemplate redis;
    private final IdentityTokenService tokenService;
    private final RegistrationSecurityProperties properties;
    private final TurnstileVerificationService turnstile;
    private final RegistrationCodeMailSender mailSender;
    private final UserAccountRepository userRepository;
    private final PlatformConfigurationService configuration;
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistrationVerificationService(
        StringRedisTemplate redis,
        IdentityTokenService tokenService,
        RegistrationSecurityProperties properties,
        TurnstileVerificationService turnstile,
        RegistrationCodeMailSender mailSender,
        UserAccountRepository userRepository,
        PlatformConfigurationService configuration
    ) {
        this.redis = redis;
        this.tokenService = tokenService;
        this.properties = properties;
        this.turnstile = turnstile;
        this.mailSender = mailSender;
        this.userRepository = userRepository;
        this.configuration = configuration;
    }

    public RegistrationConfig config() {
        PlatformConfigurationService.EmailDomainPolicy emailPolicy =
            configuration.emailDomainPolicy();
        PlatformConfigurationService.TurnstilePolicy turnstilePolicy =
            configuration.turnstilePolicy();
        return new RegistrationConfig(
            configuration.emailVerificationRequired(),
            turnstilePolicy.enabled(),
            turnstilePolicy.enabled() ? turnstilePolicy.siteKey() : null,
            configuration.termsUrl().orElse(null),
            emailPolicy.enabled(),
            emailPolicy.domains()
        );
    }

    public void requestCode(
        String email,
        String turnstileToken,
        String remoteIp
    ) {
        String normalizedEmail = normalizeEmail(email);
        configuration.assertEmailDomainAllowed(normalizedEmail);
        if (!configuration.emailVerificationRequired()) {
            return;
        }
        turnstile.verify(turnstileToken, remoteIp);
        assertRegistrationAllowed(remoteIp);

        String emailHash = tokenService.hashOpaqueToken(normalizedEmail);
        String cooldownKey = COOLDOWN_PREFIX + emailHash;
        Boolean acquired = redis.opsForValue().setIfAbsent(
            cooldownKey,
            "1",
            properties.emailCodeCooldown()
        );
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ApiProblemException(
                HttpStatus.TOO_MANY_REQUESTS,
                "REGISTRATION_CODE_RATE_LIMITED",
                "A verification code was sent recently"
            );
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            return;
        }

        String code = Integer.toString(
            secureRandom.nextInt(900_000) + 100_000
        );
        String codeKey = CODE_PREFIX + emailHash;
        redis.opsForHash().put(codeKey, "codeHash", hashCode(code));
        redis.opsForHash().put(codeKey, "attempts", "0");
        redis.expire(codeKey, properties.emailCodeTtl());
        try {
            mailSender.sendRegistrationCode(normalizedEmail, code);
        } catch (RuntimeException exception) {
            redis.delete(codeKey);
            redis.delete(cooldownKey);
            throw new ApiProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "REGISTRATION_EMAIL_UNAVAILABLE",
                "The verification email could not be sent"
            );
        }
    }

    public boolean verifyRegistration(
        String email,
        String code,
        String turnstileToken,
        String remoteIp
    ) {
        configuration.assertEmailDomainAllowed(normalizeEmail(email));
        turnstile.verify(turnstileToken, remoteIp);
        assertRegistrationAllowed(remoteIp);
        if (!configuration.emailVerificationRequired()) {
            return false;
        }
        String codeKey = codeKey(email);
        Object storedHash = redis.opsForHash().get(codeKey, "codeHash");
        if (storedHash == null) {
            throw invalidCode();
        }
        if (!MessageDigest.isEqual(
            storedHash.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            hashCode(code).getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) {
            Long attempts = redis.opsForHash().increment(
                codeKey,
                "attempts",
                1
            );
            if (attempts == null || attempts >= properties.maxCodeAttempts()) {
                redis.delete(codeKey);
            }
            throw invalidCode();
        }
        return true;
    }

    public void completeRegistration(String email, String remoteIp) {
        redis.delete(codeKey(email));
        String key = IP_PREFIX + tokenService.hashOpaqueToken(
            normalizeRemoteIp(remoteIp)
        );
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, properties.registrationWindow());
        }
    }

    private void assertRegistrationAllowed(String remoteIp) {
        String key = IP_PREFIX + tokenService.hashOpaqueToken(
            normalizeRemoteIp(remoteIp)
        );
        String value = redis.opsForValue().get(key);
        int count = value == null ? 0 : Integer.parseInt(value);
        if (count >= properties.maxRegistrationsPerIp()) {
            throw new ApiProblemException(
                HttpStatus.TOO_MANY_REQUESTS,
                "REGISTRATION_RATE_LIMITED",
                "Too many accounts were registered from this network"
            );
        }
    }

    private String codeKey(String email) {
        return CODE_PREFIX + tokenService.hashOpaqueToken(
            normalizeEmail(email)
        );
    }

    private String hashCode(String code) {
        return tokenService.hashOpaqueToken(code);
    }

    private String normalizeRemoteIp(String remoteIp) {
        return remoteIp == null || remoteIp.isBlank()
            ? "unknown"
            : remoteIp;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private ApiProblemException invalidCode() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "REGISTRATION_EMAIL_CODE_INVALID",
            "The email verification code is invalid or expired"
        );
    }

    public record RegistrationConfig(
        boolean emailVerificationRequired,
        boolean turnstileEnabled,
        String turnstileSiteKey,
        String termsUrl,
        boolean emailDomainAllowlistEnabled,
        List<String> allowedEmailDomains
    ) {
    }
}
