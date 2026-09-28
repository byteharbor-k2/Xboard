package com.sinx.platform.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

/**
 * The password attempt lockout, driven by the password_limit_* switches of
 * the legacy panel (enabled by default, five failures, a one hour window)
 * instead of the fixed values the recorder used to carry.
 */
@Component
class LoginAttemptService {

    private final StringRedisTemplate redisTemplate;
    private final PlatformConfigurationService configuration;

    LoginAttemptService(
        StringRedisTemplate redisTemplate,
        PlatformConfigurationService configuration
    ) {
        this.redisTemplate = redisTemplate;
        this.configuration = configuration;
    }

    boolean isBlocked(String email) {
        PlatformConfigurationService.LoginAttemptPolicy policy =
            configuration.loginAttemptPolicy();
        if (!policy.enabled()) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(key(email));
        return value != null
            && Long.parseLong(value) >= policy.maxFailures();
    }

    void recordFailure(String email) {
        PlatformConfigurationService.LoginAttemptPolicy policy =
            configuration.loginAttemptPolicy();
        if (!policy.enabled()) {
            return;
        }
        String key = key(email);
        Long failures = redisTemplate.opsForValue().increment(key);
        if (failures != null && failures == 1) {
            redisTemplate.expire(
                key,
                Duration.ofMinutes(policy.lockMinutes())
            );
        }
    }

    void reset(String email) {
        redisTemplate.delete(key(email));
    }

    private String key(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(
                digest.digest(email.getBytes(StandardCharsets.UTF_8))
            );
            return "identity:login-failures:" + hash;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
