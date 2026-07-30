package com.sinx.platform.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.sinx.platform.identity.security.IdentitySecurityProperties;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class MfaChallengeService {

    private static final String KEY_PREFIX = "identity:admin-challenge:";
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redis;
    private final IdentityTokenService tokenService;
    private final IdentitySecurityProperties properties;
    private final Clock clock;

    public MfaChallengeService(
        StringRedisTemplate redis,
        IdentityTokenService tokenService,
        IdentitySecurityProperties properties,
        Clock clock
    ) {
        this.redis = redis;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedChallenge issueLogin(UUID userId, String deviceLabel) {
        return issue(userId, deviceLabel, ChallengePurpose.LOGIN);
    }

    public IssuedChallenge issueEnrollment(
        UUID userId,
        String deviceLabel
    ) {
        return issue(userId, deviceLabel, ChallengePurpose.ENROLLMENT);
    }

    private IssuedChallenge issue(
        UUID userId,
        String deviceLabel,
        ChallengePurpose purpose
    ) {
        String rawToken = tokenService.newOpaqueToken();
        String key = key(rawToken);
        redis.opsForHash().putAll(key, Map.of(
            "userId", userId.toString(),
            "deviceLabel", deviceLabel,
            "purpose", purpose.name(),
            "attempts", "0"
        ));
        redis.expire(key, properties.mfaChallengeTtl());
        return new IssuedChallenge(
            rawToken,
            Instant.now(clock).plus(properties.mfaChallengeTtl())
        );
    }

    public ChallengeContext readLogin(String rawToken) {
        return read(rawToken, ChallengePurpose.LOGIN);
    }

    public ChallengeContext readEnrollment(String rawToken) {
        return read(rawToken, ChallengePurpose.ENROLLMENT);
    }

    private ChallengeContext read(
        String rawToken,
        ChallengePurpose expectedPurpose
    ) {
        String key = key(rawToken);
        Object userId = redis.opsForHash().get(key, "userId");
        Object deviceLabel = redis.opsForHash().get(key, "deviceLabel");
        Object purpose = redis.opsForHash().get(key, "purpose");
        if (userId == null || deviceLabel == null || purpose == null
                || !expectedPurpose.name().equals(purpose.toString())) {
            throw invalidChallenge();
        }
        return new ChallengeContext(
            UUID.fromString(userId.toString()),
            deviceLabel.toString()
        );
    }

    public void recordFailure(String rawToken) {
        String key = key(rawToken);
        Long attempts = redis.opsForHash().increment(key, "attempts", 1);
        if (attempts == null || attempts >= MAX_ATTEMPTS) {
            redis.delete(key);
        }
    }

    public void consume(String rawToken) {
        redis.delete(key(rawToken));
    }

    private String key(String rawToken) {
        return KEY_PREFIX + tokenService.hashOpaqueToken(rawToken);
    }

    private ApiProblemException invalidChallenge() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_MFA_CHALLENGE",
            "The MFA challenge is invalid or expired"
        );
    }

    public record IssuedChallenge(String token, Instant expiresAt) {
    }

    public record ChallengeContext(UUID userId, String deviceLabel) {
    }

    private enum ChallengePurpose {
        LOGIN,
        ENROLLMENT
    }
}
