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
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.repository.EmailVerificationTokenRepository;
import com.sinx.platform.identity.repository.RoleRepository;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.domain.EmailVerificationToken;
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
    private final PasswordEncoder passwordEncoder;
    private final IdentityTokenService tokenService;
    private final IdentitySecurityProperties securityProperties;
    private final LoginAttemptService loginAttempts;
    private final EmailVerificationAttemptService emailVerificationAttempts;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final String dummyPasswordHash;

    public IdentityService(
        UserAccountRepository userRepository,
        RoleRepository roleRepository,
        DeviceSessionRepository sessionRepository,
        EmailVerificationTokenRepository emailVerificationRepository,
        PasswordEncoder passwordEncoder,
        IdentityTokenService tokenService,
        IdentitySecurityProperties securityProperties,
        LoginAttemptService loginAttempts,
        EmailVerificationAttemptService emailVerificationAttempts,
        ApplicationEventPublisher eventPublisher,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.sessionRepository = sessionRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.securityProperties = securityProperties;
        this.loginAttempts = loginAttempts;
        this.emailVerificationAttempts = emailVerificationAttempts;
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
    public SessionGrant login(
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
        Instant now = Instant.now(clock);
        user.recordSuccessfulLogin(now);
        return issueSession(user, normalizeDeviceLabel(deviceLabel), now, null);
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
}
