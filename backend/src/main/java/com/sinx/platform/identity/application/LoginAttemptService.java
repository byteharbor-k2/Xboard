package com.sinx.platform.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class LoginAttemptService {

    private static final long MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    boolean isBlocked(String email) {
        String value = redisTemplate.opsForValue().get(key(email));
        return value != null && Long.parseLong(value) >= MAX_FAILURES;
    }

    void recordFailure(String email) {
        String key = key(email);
        Long failures = redisTemplate.opsForValue().increment(key);
        if (failures != null && failures == 1) {
            redisTemplate.expire(key, WINDOW);
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
