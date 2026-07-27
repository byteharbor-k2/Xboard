package com.sinx.platform.identity.application;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.sinx.platform.shared.web.ApiProblemException;

@Component
class EmailVerificationAttemptService {

    private static final long MAX_REQUESTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    EmailVerificationAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    void consumeRequest(UUID userId) {
        String key = "identity:email-verification:" + userId;
        Long requests = redisTemplate.opsForValue().increment(key);
        if (requests != null && requests == 1) {
            redisTemplate.expire(key, WINDOW);
        }
        if (requests != null && requests > MAX_REQUESTS) {
            throw new ApiProblemException(
                HttpStatus.TOO_MANY_REQUESTS,
                "EMAIL_VERIFICATION_RATE_LIMITED",
                "Too many verification email requests"
            );
        }
    }
}
