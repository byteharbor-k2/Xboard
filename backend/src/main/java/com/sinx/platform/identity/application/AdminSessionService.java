package com.sinx.platform.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.application.AdminMfaService.EnrollmentComplete;
import com.sinx.platform.identity.application.AdminMfaService.EnrollmentStart;
import com.sinx.platform.identity.application.MfaChallengeService.IssuedChallenge;
import com.sinx.platform.identity.application.ScopedSessionService.SessionGrant;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.SessionScope;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class AdminSessionService {

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttempts;
    private final AdminMfaService mfaService;
    private final MfaChallengeService challenges;
    private final ScopedSessionService sessions;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminSessionService(
        UserAccountRepository userRepository,
        PasswordEncoder passwordEncoder,
        LoginAttemptService loginAttempts,
        AdminMfaService mfaService,
        MfaChallengeService challenges,
        ScopedSessionService sessions,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttempts = loginAttempts;
        this.mfaService = mfaService;
        this.challenges = challenges;
        this.sessions = sessions;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode(
            "not-a-real-admin-password"
        );
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
        if (user == null || !passwordMatches || !hasAdminRole(user)) {
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
        String label = normalizeDeviceLabel(deviceLabel);
        if (!mfaService.isEnabled(user.getId())) {
            return LoginResult.enrollment(
                challenges.issueEnrollment(user.getId(), label)
            );
        }
        return LoginResult.challenge(
            challenges.issueLogin(user.getId(), label)
        );
    }

    @Transactional
    public SessionGrant completeMfaLogin(
        String challengeToken,
        String code
    ) {
        MfaChallengeService.ChallengeContext challenge =
            challenges.readLogin(challengeToken);
        UserAccount user = requireActiveAdmin(challenge.userId());
        try {
            mfaService.verifyLoginCode(user.getId(), code);
        } catch (ApiProblemException exception) {
            challenges.recordFailure(challengeToken);
            throw exception;
        }
        Instant now = Instant.now(clock);
        user.recordSuccessfulLogin(now);
        SessionGrant grant = sessions.issue(
            user,
            challenge.deviceLabel(),
            SessionScope.ADMIN,
            null
        );
        challenges.consume(challengeToken);
        return grant;
    }

    @Transactional
    public EnrollmentStart startEnrollment(String enrollmentToken) {
        MfaChallengeService.ChallengeContext challenge =
            challenges.readEnrollment(enrollmentToken);
        requireActiveAdmin(challenge.userId());
        return mfaService.startEnrollment(challenge.userId());
    }

    @Transactional
    public EnrollmentComplete confirmEnrollment(
        String enrollmentToken,
        String code
    ) {
        MfaChallengeService.ChallengeContext challenge =
            challenges.readEnrollment(enrollmentToken);
        requireActiveAdmin(challenge.userId());
        try {
            EnrollmentComplete complete = mfaService.confirmEnrollment(
                challenge.userId(),
                code
            );
            challenges.consume(enrollmentToken);
            return complete;
        } catch (ApiProblemException exception) {
            challenges.recordFailure(enrollmentToken);
            throw exception;
        }
    }

    public SessionGrant refresh(String refreshToken) {
        return sessions.refresh(refreshToken, SessionScope.ADMIN);
    }

    public void logout(String refreshToken) {
        sessions.logout(refreshToken, SessionScope.ADMIN);
    }

    private UserAccount requireActiveAdmin(UUID userId) {
        UserAccount user = userRepository.findWithRolesById(userId)
            .orElseThrow(this::invalidCredentials);
        if (!hasAdminRole(user)) {
            throw invalidCredentials();
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

    private boolean hasAdminRole(UserAccount user) {
        return user.getRoles().stream()
            .map(Role::getCode)
            .anyMatch(ADMIN_ROLE::equals);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDeviceLabel(String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return "Admin Browser";
        }
        return deviceLabel.trim();
    }

    private ApiProblemException invalidCredentials() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_ADMIN_CREDENTIALS",
            "The administrator credentials are incorrect"
        );
    }

    public record LoginResult(
        IssuedChallenge mfaChallenge,
        IssuedChallenge enrollmentChallenge
    ) {
        public static LoginResult challenge(IssuedChallenge challenge) {
            return new LoginResult(challenge, null);
        }

        public static LoginResult enrollment(IssuedChallenge challenge) {
            return new LoginResult(null, challenge);
        }

        public boolean requiresMfa() {
            return mfaChallenge != null;
        }
    }
}
