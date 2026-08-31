package com.sinx.platform.identity.application;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sinx.platform.identity.security.IdentitySecurityProperties;

/**
 * Remembers, very briefly, which refresh token replaced which.
 *
 * Refresh tokens rotate on every use, and presenting a rotated one is normally
 * proof of theft. It is also what happens when a client sends two refreshes at
 * once - a reload landing on top of an in-flight refresh, or a network retry -
 * because both carry the same cookie. The server cannot tell those apart, so
 * this keeps the replacement around long enough to hand it back to the loser of
 * that race instead of tearing down the session family.
 */
@Component
public class RefreshTokenReplayWindow {

    private static final String KEY_PREFIX = "session:rotated:";

    private final StringRedisTemplate redis;
    private final IdentitySecurityProperties properties;

    public RefreshTokenReplayWindow(
        StringRedisTemplate redis,
        IdentitySecurityProperties properties
    ) {
        this.redis = redis;
        this.properties = properties;
    }

    void remember(String rotatedTokenHash, String replacementToken) {
        Duration window = properties.refreshReplayWindow();
        if (window.isZero() || window.isNegative()) {
            return;
        }
        redis.opsForValue().set(
            KEY_PREFIX + rotatedTokenHash,
            replacementToken,
            window
        );
    }

    Optional<String> recall(String rotatedTokenHash) {
        Duration window = properties.refreshReplayWindow();
        if (window.isZero() || window.isNegative()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
            redis.opsForValue().get(KEY_PREFIX + rotatedTokenHash)
        );
    }

    /**
     * Drops the record so a family that is being torn down cannot be revived
     * through the window.
     */
    void forget(String rotatedTokenHash) {
        redis.delete(KEY_PREFIX + rotatedTokenHash);
    }
}
