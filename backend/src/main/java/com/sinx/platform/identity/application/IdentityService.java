package com.sinx.platform.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.domain.DeviceSession;
import com.sinx.platform.identity.domain.EmailVerificationToken;
import com.sinx.platform.identity.domain.PasswordResetToken;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.repository.EmailVerificationTokenRepository;
import com.sinx.platform.identity.repository.PasswordResetTokenRepository;
import com.sinx.platform.identity.repository.RoleRepository;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.IdentitySecurityProperties;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.identity.security.IdentityTokenService.AccessTokenGrant;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class IdentityService {

    private static final String DEFAULT_ROLE = "USER";

    private final UserAccountRepository userRepository;
    private final RoleRepository roleRepository;
    private final DeviceSessionRepository sessionRepository;
    private final EmailVerificationTokenRepository emailVerificationRepository;
    private final PasswordResetTokenRepository passwordResetRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityTokenService tokenService;
    private final IdentitySecurityProperties securityProperties;
    private final LoginAttemptService loginAttempts;
    private final EmailVerificationAttemptService emailVerificationAttempts;
    private final PasswordResetAttemptService passwordResetAttempts;
    private final AdminMfaService adminMfaService;
    private final MfaChallengeService mfaChallenges;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final String dummyPasswordHash;

    public IdentityService(
        UserAccountRepository userRepository,
        RoleRepository roleRepository,
        DeviceSessionRepository sessionRepository,
        EmailVerificationTokenRepository emailVerificationRepository,
        PasswordResetTokenRepository passwordResetRepository,
        PasswordEncoder passwordEncoder,
        IdentityTokenService tokenService,
        IdentitySecurityProperties securityProperties,
        LoginAttemptService loginAttempts,
        EmailVerificationAttemptService emailVerificationAttempts,
        PasswordResetAttemptService passwordResetAttempts,
        AdminMfaService adminMfaService,
        MfaChallengeService mfaChallenges,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.sessionRepository = sessionRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.securityProperties = securityProperties;
        this.loginAttempts = loginAttempts;
        this.emailVerificationAttempts = emailVerificationAttempts;
        this.passwordResetAttempts = passwordResetAttempts;
        this.adminMfaService = adminMfaService;
        this.mfaChallenges = mfaChallenges;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(
            "not-a-real-user-password"
        );
    }

    @Transactional
    public SessionGrant register(
        String email,
        String password,
        String displayName,
        String deviceLabel
    ) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw emailAlreadyRegistered();
        }

        Role defaultRole = roleRepository.findById(DEFAULT_ROLE)
            .orElseThrow(() -> new IllegalStateException("Default role is missing"));
        Instant now = Instant.now(clock);
        UserAccount user = UserAccount.register(
            UUID.randomUUID(),
            normalizedEmail,
            passwordEncoder.encode(password),
            displayName.trim(),
            defaultRole,
            now
        );
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw emailAlreadyRegistered();
        }
        issueEmailVerification(user, now);
        return issueSession(user, normalizeDeviceLabel(deviceLabel), now, null);
    }

    @Transactional
    public LoginResult login(
        String email,
        String password,
        String deviceLabel
    ) {
        String normalizedEmail = normalizeEmail(email);
        if (loginAttempts.isBlocked(normalizedEmail)) {
            throw new ApiProblemException(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_RATE_LIMITED",
                "Too many failed login attempts"
            );
        }

        UserAccount user = userRepository.findWithRolesByEmail(normalizedEmail)
            .orElse(null);
        String storedHash = user == null
            ? dummyPasswordHash
            : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, storedHash);
        if (user == null || !passwordMatches) {
            loginAttempts.recordFailure(normalizedEmail);
            throw invalidCredentials();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ACCOUNT_SUSPENDED",
                "This account is not available"
            );
        }

        loginAttempts.reset(normalizedEmail);
        String normalizedDeviceLabel = normalizeDeviceLabel(deviceLabel);
        if (adminMfaService.isEnabled(user.getId())) {
            return LoginResult.challenge(
                mfaChallenges.issue(user.getId(), normalizedDeviceLabel)
            );
        }
        Instant now = Instant.now(clock);
        user.recordSuccessfulLogin(now);
        return LoginResult.session(
            issueSession(user, normalizedDeviceLabel, now, null)
        );
    }

    @Transactional
    public SessionGrant completeMfaLogin(String challengeToken, String code) {
        MfaChallengeService.ChallengeContext challenge =
            mfaChallenges.read(challengeToken);
        UserAccount user = userRepository.findWithRolesById(challenge.userId())
            .orElseThrow(this::sessionUserNotFound);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ACCOUNT_SUSPENDED",
                "This account is not available"
            );
        }
        try {
            adminMfaService.verifyLoginCode(user.getId(), code);
        } catch (ApiProblemException exception) {
            mfaChallenges.recordFailure(challengeToken);
            throw exception;
        }
        Instant now = Instant.now(clock);
        user.recordSuccessfulLogin(now);
        SessionGrant grant = issueSession(
            user,
            challenge.deviceLabel(),
            now,
            null
        );
        mfaChallenges.consume(challengeToken);
        return grant;
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public SessionGrant refresh(String refreshToken) {
        String tokenHash = tokenService.hashRefreshToken(refreshToken);
        DeviceSession currentSession = sessionRepository
            .findForUpdateByTokenHash(tokenHash)
            .orElseThrow(this::invalidRefreshToken);
        Instant now = Instant.now(clock);

        if (currentSession.isRevoked()) {
            sessionRepository.revokeActiveFamily(
                currentSession.getSessionFamilyId(),
                now
            );
            throw invalidRefreshToken();
        }
        if (!currentSession.isActiveAt(now)) {
            throw invalidRefreshToken();
        }
        if (currentSession.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ACCOUNT_SUSPENDED",
                "This account is not available"
            );
        }

        SessionGrant replacement = issueSession(
            currentSession.getUser(),
            currentSession.getDeviceLabel(),
            now,
            currentSession.getSessionFamilyId()
        );
        currentSession.revoke(now, replacement.sessionId());
        return replacement;
    }

    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = tokenService.hashRefreshToken(refreshToken);
        sessionRepository.findForUpdateByTokenHash(tokenHash).ifPresent(session -> {
            if (session.isActiveAt(Instant.now(clock))) {
                session.revoke(Instant.now(clock), null);
            }
        });
    }

    @Transactional(readOnly = true)
    public ViewerView viewer(UUID userId) {
        UserAccount user = userRepository.findWithRolesById(userId)
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "SESSION_USER_NOT_FOUND",
                "The authenticated user no longer exists"
            ));
        return ViewerView.from(user);
    }

    @Transactional
    public void requestEmailVerification(UUID userId) {
        UserAccount user = userRepository.findWithRolesById(userId)
            .orElseThrow(this::sessionUserNotFound);
        if (user.isEmailVerified()) {
            return;
        }
        emailVerificationAttempts.consumeRequest(userId);
        Instant now = Instant.now(clock);
        emailVerificationRepository.consumeActiveForUser(userId, now);
        issueEmailVerification(user, now);
    }

    @Transactional
    public void confirmEmailVerification(String rawToken) {
        String tokenHash = tokenService.hashOpaqueToken(rawToken);
        EmailVerificationToken token = emailVerificationRepository
            .findForUpdateByTokenHash(tokenHash)
            .orElseThrow(this::invalidEmailVerificationToken);
        Instant now = Instant.now(clock);
        if (!token.isUsableAt(now)) {
            throw invalidEmailVerificationToken();
        }
        token.consume(now);
        token.getUser().markEmailVerified(now);
        emailVerificationRepository.consumeActiveForUser(
            token.getUser().getId(),
            now
        );
    }

    @Transactional
    public ViewerView updateProfile(UUID userId, String displayName) {
        UserAccount user = userRepository.findWithRolesById(userId)
            .orElseThrow(this::sessionUserNotFound);
        user.updateDisplayName(displayName.trim(), Instant.now(clock));
        return ViewerView.from(user);
    }

    @Transactional
    public void changePassword(
        UUID userId,
        UUID currentSessionId,
        String currentPassword,
        String newPassword
    ) {
        UserAccount user = userRepository.findWithRolesById(userId)
            .orElseThrow(this::sessionUserNotFound);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "CURRENT_PASSWORD_INVALID",
                "The current password is incorrect"
            );
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiProblemException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PASSWORD_UNCHANGED",
                "The new password must be different"
            );
        }
        Instant now = Instant.now(clock);
        user.changePassword(passwordEncoder.encode(newPassword), now);
        sessionRepository.revokeOtherActiveForUser(
            userId,
            currentSessionId,
            now
        );
    }

    @Transactional
    public void requestPasswordReset(String email) {
        String normalizedEmail = normalizeEmail(email);
        String identityHash = tokenService.hashOpaqueToken(normalizedEmail);
        if (!passwordResetAttempts.consumeRequest(identityHash)) {
            return;
        }
        userRepository.findWithRolesByEmail(normalizedEmail).ifPresent(user -> {
            if (user.getStatus() != UserStatus.ACTIVE) {
                return;
            }
            Instant now = Instant.now(clock);
            passwordResetRepository.consumeActiveForUser(user.getId(), now);
            String rawToken = tokenService.newOpaqueToken();
            passwordResetRepository.save(PasswordResetToken.create(
                UUID.randomUUID(),
                user,
                tokenService.hashOpaqueToken(rawToken),
                now,
                now.plus(securityProperties.passwordResetTtl())
            ));
            eventPublisher.publishEvent(new PasswordResetRequested(
                user.getEmail(),
                user.getDisplayName(),
                rawToken
            ));
        });
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        String tokenHash = tokenService.hashOpaqueToken(rawToken);
        PasswordResetToken token = passwordResetRepository
            .findForUpdateByTokenHash(tokenHash)
            .orElseThrow(this::invalidPasswordResetToken);
        Instant now = Instant.now(clock);
        if (!token.isUsableAt(now)) {
            throw invalidPasswordResetToken();
        }
        token.consume(now);
        token.getUser().changePassword(
            passwordEncoder.encode(newPassword),
            now
        );
        sessionRepository.revokeAllActiveForUser(
            token.getUser().getId(),
            now
        );
    }

    @Transactional(readOnly = true)
    public List<DeviceSessionView> deviceSessions(
        UUID userId,
        UUID currentSessionId
    ) {
        return sessionRepository
            .findActiveByUserId(userId, Instant.now(clock))
            .stream()
            .map(session -> DeviceSessionView.from(
                session,
                currentSessionId
            ))
            .toList();
    }

    @Transactional
    public void revokeDeviceSession(UUID userId, UUID sessionId) {
        DeviceSession session = sessionRepository.findOwnedForUpdate(
            sessionId,
            userId
        ).orElseThrow(() -> new ApiProblemException(
            HttpStatus.NOT_FOUND,
            "DEVICE_SESSION_NOT_FOUND",
            "The device session does not exist"
        ));
        Instant now = Instant.now(clock);
        if (session.isActiveAt(now)) {
            session.revoke(now, null);
        }
    }

    private SessionGrant issueSession(
        UserAccount user,
        String deviceLabel,
        Instant now,
        UUID existingFamilyId
    ) {
        String refreshToken = tokenService.newRefreshToken();
        Instant refreshExpiresAt = now.plus(securityProperties.refreshTokenTtl());
        UUID sessionId = UUID.randomUUID();
        UUID sessionFamilyId = existingFamilyId == null
            ? sessionId
            : existingFamilyId;
        DeviceSession session = DeviceSession.create(
            sessionId,
            user,
            sessionFamilyId,
            tokenService.hashRefreshToken(refreshToken),
            deviceLabel,
            now,
            refreshExpiresAt
        );
        sessionRepository.save(session);
        AccessTokenGrant accessToken = tokenService.issueAccessToken(
            user,
            sessionId
        );
        return new SessionGrant(
            accessToken.token(),
            accessToken.expiresAt(),
            refreshToken,
            refreshExpiresAt,
            session.getId(),
            ViewerView.from(user)
        );
    }

    private void issueEmailVerification(UserAccount user, Instant now) {
        String rawToken = tokenService.newOpaqueToken();
        EmailVerificationToken token = EmailVerificationToken.create(
            UUID.randomUUID(),
            user,
            tokenService.hashOpaqueToken(rawToken),
            now,
            now.plus(securityProperties.emailVerificationTtl())
        );
        emailVerificationRepository.save(token);
        eventPublisher.publishEvent(new EmailVerificationRequested(
            user.getEmail(),
            user.getDisplayName(),
            rawToken
        ));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDeviceLabel(String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return "Browser";
        }
        return deviceLabel.trim();
    }

    private ApiProblemException invalidCredentials() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_CREDENTIALS",
            "The email or password is incorrect"
        );
    }

    private ApiProblemException emailAlreadyRegistered() {
        return new ApiProblemException(
            HttpStatus.CONFLICT,
            "EMAIL_ALREADY_REGISTERED",
            "An account already exists for this email"
        );
    }

    private ApiProblemException invalidRefreshToken() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_REFRESH_TOKEN",
            "The refresh session is invalid or expired"
        );
    }

    private ApiProblemException invalidEmailVerificationToken() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_EMAIL_VERIFICATION_TOKEN",
            "The email verification link is invalid or expired"
        );
    }

    private ApiProblemException invalidPasswordResetToken() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_PASSWORD_RESET_TOKEN",
            "The password reset link is invalid or expired"
        );
    }

    private ApiProblemException sessionUserNotFound() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "SESSION_USER_NOT_FOUND",
            "The authenticated user no longer exists"
        );
    }

    public record SessionGrant(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID sessionId,
        ViewerView viewer
    ) {
    }

    public record LoginResult(
        SessionGrant session,
        MfaChallengeService.IssuedChallenge challenge
    ) {
        public static LoginResult session(SessionGrant session) {
            return new LoginResult(session, null);
        }

        public static LoginResult challenge(
            MfaChallengeService.IssuedChallenge challenge
        ) {
            return new LoginResult(null, challenge);
        }

        public boolean requiresMfa() {
            return challenge != null;
        }
    }
}
