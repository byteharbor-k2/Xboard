package com.sinx.platform.subscription.application;

import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Keeps the short subscription-fetch window shared by every application instance.
 *
 * The account UUID, rather than the bearer link, is the bucket identity. A
 * rotated link therefore continues the same account's window, while two
 * accounts remain independent. Redis supplies the timestamp inside the atomic
 * script so hosts with slightly different clocks cannot disagree about a slot.
 */
@Component
public class SubscriptionRequestRateLimiter {

    private static final String KEY_PREFIX = "subscription:request-window:";
    private static final String SCRIPT_SOURCE = """
        local time = redis.call('TIME')
        local now = time[1] * 1000000 + time[2]
        local cutoff = now - 1000000
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
        if redis.call('ZCARD', KEYS[1]) >= 2 then
            return 0
        end
        redis.call('ZADD', KEYS[1], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[1], 2000)
        return 1
        """;

    private static final DefaultRedisScript<Long> RECORD_REQUEST =
        new DefaultRedisScript<>(SCRIPT_SOURCE, Long.class);

    private final StringRedisTemplate redis;

    public SubscriptionRequestRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records one request if fewer than two requests for this account fall in
     * the preceding rolling second.
     *
     * @param userId stable account identity, never a subscription credential
     * @return whether the request may continue to render a config
     */
    public boolean tryAcquire(UUID userId) {
        Long accepted = redis.execute(
            RECORD_REQUEST,
            List.of(KEY_PREFIX + userId),
            UUID.randomUUID().toString()
        );
        return Long.valueOf(1).equals(accepted);
    }
}
