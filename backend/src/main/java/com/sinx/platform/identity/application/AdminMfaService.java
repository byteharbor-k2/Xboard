package com.sinx.platform.identity.application;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.domain.AdminMfaMethod;
import com.sinx.platform.identity.domain.AdminMfaRecoveryCode;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.AdminMfaMethodRepository;
import com.sinx.platform.identity.repository.AdminMfaRecoveryCodeRepository;
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.identity.security.MfaCryptography;
import com.sinx.platform.identity.security.TotpService;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class AdminMfaService {

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String RECOVERY_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_CODE_COUNT = 8;

    private final UserAccountRepository userRepository;
    private final AdminMfaMethodRepository methodRepository;
    private final AdminMfaRecoveryCodeRepository recoveryCodeRepository;
    private final DeviceSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final MfaCryptography cryptography;
    private final TotpService totpService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminMfaService(
        UserAccountRepository userRepository,
        AdminMfaMethodRepository methodRepository,
        AdminMfaRecoveryCodeRepository recoveryCodeRepository,
        DeviceSessionRepository sessionRepository,
        PasswordEncoder passwordEncoder,
        MfaCryptography cryptography,
        TotpService totpService,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.methodRepository = methodRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.cryptography = cryptography;
        this.totpService = totpService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return methodRepository.existsEnabledByUserId(userId);
    }

    @Transactional(readOnly = true)
    public MfaStatus status(UUID userId) {
        UserAccount user = requireAdmin(userId);
        return methodRepository.findByUserId(user.getId())
            .filter(AdminMfaMethod::isEnabled)
            .map(method -> new MfaStatus(true, method.getEnabledAt()))
            .orElseGet(() -> new MfaStatus(false, null));
    }

    @Transactional
    public EnrollmentStart startEnrollment(UUID userId) {
        UserAccount user = requireAdmin(userId);
        Instant now = Instant.now(clock);
        String secret = totpService.newSecret();
        String encryptedSecret = cryptography.encryptSecret(secret);
        AdminMfaMethod method = methodRepository.findForUpdateByUserId(userId)
            .orElse(null);
        if (method != null && method.isEnabled()) {
            throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "MFA_ALREADY_ENABLED",
                "MFA is already enabled for this administrator"
            );
        }
        if (method == null) {
            methodRepository.save(AdminMfaMethod.pending(
                user,
                encryptedSecret,
                now
            ));
        } else {
            method.replacePendingSecret(encryptedSecret, now);
        }
        return new EnrollmentStart(
            secret,
            totpService.otpauthUri(user.getEmail(), secret)
        );
    }

    @Transactional
    public EnrollmentComplete confirmEnrollment(
        UUID userId,
        String code
    ) {
        requireAdmin(userId);
        AdminMfaMethod method = methodRepository.findForUpdateByUserId(userId)
            .filter(candidate -> !candidate.isEnabled())
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.CONFLICT,
                "MFA_ENROLLMENT_NOT_STARTED",
                "Start MFA enrollment before confirming it"
            ));
        String secret = cryptography.decryptSecret(method.getEncryptedSecret());
        OptionalLong timeStep = totpService.matchingTimeStep(secret, code);
        if (timeStep.isEmpty()) {
            throw invalidCode();
        }

        Instant now = Instant.now(clock);
        recoveryCodeRepository.deleteAllByUserId(userId);
        List<String> rawCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<AdminMfaRecoveryCode> storedCodes =
            new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
            String rawCode = newRecoveryCode();
            rawCodes.add(rawCode);
            storedCodes.add(AdminMfaRecoveryCode.create(
                UUID.randomUUID(),
                method.getUser(),
                cryptography.hashRecoveryCode(rawCode),
                now
            ));
        }
        recoveryCodeRepository.saveAll(storedCodes);
        method.enable(now, timeStep.getAsLong());
        return new EnrollmentComplete(List.copyOf(rawCodes));
    }

    @Transactional
    public void verifyLoginCode(UUID userId, String code) {
        AdminMfaMethod method = methodRepository.findForUpdateByUserId(userId)
            .filter(AdminMfaMethod::isEnabled)
            .orElseThrow(this::invalidCode);
        String secret = cryptography.decryptSecret(method.getEncryptedSecret());
        OptionalLong timeStep = totpService.matchingTimeStep(secret, code);
        Instant now = Instant.now(clock);
        if (timeStep.isPresent()
                && method.consumeTimeStep(timeStep.getAsLong(), now)) {
            return;
        }

        String candidateHash = cryptography.hashRecoveryCode(code);
        for (AdminMfaRecoveryCode recovery :
                recoveryCodeRepository.findActiveForUpdate(userId)) {
            if (MessageDigest.isEqual(
                recovery.getCodeHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                candidateHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
            )) {
                recovery.use(now);
                return;
            }
        }
        throw invalidCode();
    }

    @Transactional
    public void disable(
        UUID userId,
        UUID currentSessionId,
        String password,
        String code
    ) {
        UserAccount user = requireAdmin(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "The password is incorrect"
            );
        }
        verifyLoginCode(userId, code);
        recoveryCodeRepository.deleteAllByUserId(userId);
        methodRepository.delete(
            methodRepository.findForUpdateByUserId(userId)
                .orElseThrow(this::invalidCode)
        );
        sessionRepository.revokeOtherActiveForUser(
            userId,
            currentSessionId,
            Instant.now(clock)
        );
    }

    private UserAccount requireAdmin(UUID userId) {
        UserAccount user = userRepository.findWithRolesById(userId)
            .orElseThrow(() -> new ApiProblemException(
                HttpStatus.UNAUTHORIZED,
                "SESSION_USER_NOT_FOUND",
                "The authenticated user no longer exists"
            ));
        boolean admin = user.getRoles().stream()
            .map(Role::getCode)
            .anyMatch(ADMIN_ROLE::equals);
        if (!admin) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ADMIN_REQUIRED",
                "Administrator access is required"
            );
        }
        return user;
    }

    private String newRecoveryCode() {
        StringBuilder code = new StringBuilder(11);
        for (int index = 0; index < 10; index++) {
            if (index == 5) {
                code.append('-');
            }
            code.append(RECOVERY_ALPHABET.charAt(
                secureRandom.nextInt(RECOVERY_ALPHABET.length())
            ));
        }
        return code.toString();
    }

    private ApiProblemException invalidCode() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_MFA_CODE",
            "The MFA code is invalid, expired, or already used"
        );
    }

    public record MfaStatus(boolean enabled, Instant enabledAt) {
    }

    public record EnrollmentStart(String secret, String otpauthUri) {
    }

    public record EnrollmentComplete(List<String> recoveryCodes) {
    }
}
