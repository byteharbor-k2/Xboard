package com.sinx.platform.identity.application;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class PasswordResetAttemptService {

    private static final long MAX_REQUESTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    PasswordResetAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    boolean consumeRequest(String identityHash) {
        String key = "identity:password-reset:" + identityHash;
        Long requests = redisTemplate.opsForValue().increment(key);
        if (requests != null && requests == 1) {
            redisTemplate.expire(key, WINDOW);
        }
        return requests == null || requests <= MAX_REQUESTS;
    }
}
