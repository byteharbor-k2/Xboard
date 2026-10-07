package com.sinx.platform.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.SessionScope;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.repository.PasswordResetTokenRepository;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.identity.security.RegistrationSecurityProperties;
import com.sinx.platform.notification.email.EmailChangeCodeMailSender;
import com.sinx.platform.shared.web.ApiProblemException;

/** Owns the account-bound proof and session consequences of a customer email change. */
@Service
public class EmailChangeService {

    private static final String CODE_PREFIX = "identity:email-change-code:";
    private static final String COOLDOWN_PREFIX = "identity:email-change-cooldown:";
    private static final String TARGET_LOCK_PREFIX = "identity:email-change-target:";
    private static final Duration TARGET_LOCK_TTL = Duration.ofMinutes(2);

    private static final DefaultRedisScript<Long> CLAIM_CODE = new DefaultRedisScript<>(
        """
        local stored = redis.call('HGET', KEYS[1], 'codeHash')
        if not stored then return -1 end
        if redis.call('HEXISTS', KEYS[1], 'claim') == 1 then return -2 end
        if stored ~= ARGV[1] then
          local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
          if attempts >= tonumber(ARGV[3]) then redis.call('DEL', KEYS[1]) end
          return 0
        end
        redis.call('HSET', KEYS[1], 'claim', ARGV[2])
        return 1
        """,
        Long.class
    );
    private static final DefaultRedisScript<Long> RELEASE_CODE_CLAIM = new DefaultRedisScript<>(
        """
        if redis.call('HGET', KEYS[1], 'claim') == ARGV[1] then
          redis.call('HDEL', KEYS[1], 'claim')
          return 1
        end
        return 0
        """,
        Long.class
    );
    private static final DefaultRedisScript<Long> CONSUME_CODE_CLAIM = new DefaultRedisScript<>(
        """
        if redis.call('HGET', KEYS[1], 'claim') == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """,
        Long.class
    );
    private static final DefaultRedisScript<Long> RELEASE_OWNED_VALUE = new DefaultRedisScript<>(
        """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """,
        Long.class
    );

    private final UserAccountRepository users;
    private final DeviceSessionRepository sessions;
    private final PasswordResetTokenRepository passwordResetTokens;
    private final PasswordEncoder passwordEncoder;
    private final IdentityTokenService tokenService;
    private final RegistrationSecurityProperties codeProperties;
    private final StringRedisTemplate redis;
    private final EmailChangeCodeMailSender mailSender;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailChangeService(
        UserAccountRepository users,
        DeviceSessionRepository sessions,
        PasswordResetTokenRepository passwordResetTokens,
        PasswordEncoder passwordEncoder,
        IdentityTokenService tokenService,
        RegistrationSecurityProperties codeProperties,
        StringRedisTemplate redis,
        EmailChangeCodeMailSender mailSender,
        Clock clock
    ) {
        this.users = users;
        this.sessions = sessions;
        this.passwordResetTokens = passwordResetTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.codeProperties = codeProperties;
        this.redis = redis;
        this.mailSender = mailSender;
        this.clock = clock;
    }

    @Transactional
    public void requestCode(UUID userId, String newEmail, String currentPassword) {
        UserAccount user = requireUser(userId);
        verifyCurrentPassword(user, currentPassword);
        String canonicalEmail = normalizeEmail(newEmail);
        assertNewAddress(user, canonicalEmail);

        String codeKey = codeKey(userId, canonicalEmail);
        String cooldownKey = COOLDOWN_PREFIX + userId + ":" + emailHash(canonicalEmail);
        String reservation = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
            cooldownKey,
            reservation,
            codeProperties.emailCodeCooldown()
        );
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ApiProblemException(
                HttpStatus.TOO_MANY_REQUESTS,
                "EMAIL_CHANGE_CODE_RATE_LIMITED",
                "An email change code was sent recently"
            );
        }

        String code = Integer.toString(secureRandom.nextInt(900_000) + 100_000);
        try {
            redis.opsForHash().putAll(codeKey, Map.of(
                "codeHash", tokenService.hashOpaqueToken(code),
                "attempts", "0"
            ));
            if (!Boolean.TRUE.equals(redis.expire(
                codeKey,
                codeProperties.emailCodeTtl()
            ))) {
                throw new IllegalStateException("Could not expire email change code");
            }
        } catch (RuntimeException exception) {
            clearDeliveryReservation(codeKey, cooldownKey, reservation);
            throw new ApiProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EMAIL_CHANGE_UNAVAILABLE",
                "Email change is temporarily unavailable"
            );
        }
        try {
            mailSender.sendEmailChangeCode(canonicalEmail, code);
        } catch (RuntimeException exception) {
            clearDeliveryReservation(codeKey, cooldownKey, reservation);
            throw new ApiProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EMAIL_CHANGE_EMAIL_UNAVAILABLE",
                "The verification email could not be sent"
            );
        }
    }

    @Transactional
    public ViewerView confirm(
        UUID userId,
        UUID currentSessionId,
        String newEmail,
        String currentPassword,
        String code
    ) {
        UserAccount user = requireUser(userId);
        verifyCurrentPassword(user, currentPassword);
        String canonicalEmail = normalizeEmail(newEmail);
        assertNewAddress(user, canonicalEmail);

        String codeKey = codeKey(userId, canonicalEmail);
        String claim = UUID.randomUUID().toString();
        Long claimResult = redis.execute(
            CLAIM_CODE,
            List.of(codeKey),
            tokenService.hashOpaqueToken(code),
            claim,
            Integer.toString(codeProperties.maxCodeAttempts())
        );
        if (claimResult == null || claimResult != 1L) {
            throw invalidCode();
        }

        String targetLockKey = TARGET_LOCK_PREFIX + emailHash(canonicalEmail);
        String targetLock = UUID.randomUUID().toString();
        Boolean targetAcquired = redis.opsForValue().setIfAbsent(
            targetLockKey,
            targetLock,
            TARGET_LOCK_TTL
        );
        if (!Boolean.TRUE.equals(targetAcquired)) {
            releaseCodeClaim(codeKey, claim);
            throw emailAlreadyRegistered();
        }
        registerClaimCompletion(codeKey, claim, targetLockKey, targetLock);

        if (users.existsByCanonicalEmail(canonicalEmail)) {
            throw emailAlreadyRegistered();
        }

        Instant now = Instant.now(clock);
        user.changeEmailAndVerify(canonicalEmail, now);
        try {
            users.flush();
        } catch (DataIntegrityViolationException exception) {
            throw emailAlreadyRegistered();
        }
        passwordResetTokens.invalidateActiveForUser(userId, now);
        sessions.revokeOtherActiveForUserAndScope(
            userId,
            currentSessionId,
            SessionScope.USER,
            now
        );
        return ViewerView.forScope(user, SessionScope.USER);
    }

    private UserAccount requireUser(UUID userId) {
        UserAccount user = users.findWithRolesByIdForUpdate(userId)
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "SESSION_USER_NOT_FOUND",
                "The authenticated user no longer exists"
            ));
        boolean userRole = user.getRoles().stream()
            .map(Role::getCode)
            .anyMatch("USER"::equals);
        if (!userRole) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "USER_SESSION_REQUIRED",
                "A user session is required"
            );
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ACCOUNT_SUSPENDED",
                "This account is not available"
            );
        }
        return user;
    }

    private void verifyCurrentPassword(UserAccount user, String currentPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "CURRENT_PASSWORD_INVALID",
                "The current password is incorrect"
            );
        }
    }

    private void assertNewAddress(UserAccount user, String email) {
        if (email.equals(normalizeEmail(user.getEmail()))) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "EMAIL_UNCHANGED",
                "The new email address must be different"
            );
        }
        if (users.existsByCanonicalEmail(email)) {
            throw emailAlreadyRegistered();
        }
    }

    private void registerClaimCompletion(
        String codeKey,
        String claim,
        String targetLockKey,
        String targetLock
    ) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        redis.execute(
                            CONSUME_CODE_CLAIM,
                            List.of(codeKey),
                            claim
                        );
                    } catch (RuntimeException ignored) {
                        // The committed address change is authoritative; a
                        // Redis outage must not make the client retry a success.
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            releaseCodeClaim(codeKey, claim);
                        } catch (RuntimeException ignored) {
                            // The short-lived claim expires with the challenge.
                        }
                    }
                    try {
                        releaseTargetLock(targetLockKey, targetLock);
                    } catch (RuntimeException ignored) {
                        // The target lock has a short expiry as a fallback.
                    }
                }
            }
        );
    }

    private void releaseCodeClaim(String codeKey, String claim) {
        redis.execute(RELEASE_CODE_CLAIM, List.of(codeKey), claim);
    }

    private void releaseCooldown(String cooldownKey, String reservation) {
        redis.execute(RELEASE_OWNED_VALUE, List.of(cooldownKey), reservation);
    }

    private void clearDeliveryReservation(
        String codeKey,
        String cooldownKey,
        String reservation
    ) {
        try {
            redis.delete(codeKey);
        } catch (RuntimeException ignored) {
            // An undelivered code is still unusable without revealing it.
        }
        try {
            releaseCooldown(cooldownKey, reservation);
        } catch (RuntimeException ignored) {
            // Cooldown keys expire; delivery can be retried after that fallback.
        }
    }

    private void releaseTargetLock(String targetLockKey, String targetLock) {
        redis.execute(RELEASE_OWNED_VALUE, List.of(targetLockKey), targetLock);
    }

    private String codeKey(UUID userId, String email) {
        return CODE_PREFIX + userId + ":" + emailHash(email);
    }

    private String emailHash(String email) {
        return tokenService.hashOpaqueToken(email);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private ApiProblemException emailAlreadyRegistered() {
        return new ApiProblemException(
            HttpStatus.CONFLICT,
            "EMAIL_ALREADY_REGISTERED",
            "An account already exists for this email"
        );
    }

    private ApiProblemException invalidCode() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "EMAIL_CHANGE_CODE_INVALID",
            "The email change code is invalid or expired"
        );
    }
}
