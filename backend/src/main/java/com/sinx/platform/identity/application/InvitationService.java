package com.sinx.platform.identity.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.InviteCode;
import com.sinx.platform.identity.repository.InviteCodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class InvitationService {

    private static final char[] CODE_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final InviteCodeRepository inviteCodes;
    private final PlatformConfigurationService configuration;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public InvitationService(
        InviteCodeRepository inviteCodes,
        PlatformConfigurationService configuration,
        Clock clock
    ) {
        this.inviteCodes = inviteCodes;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Transactional
    public Optional<UUID> consumeForRegistration(String rawCode) {
        PlatformConfigurationService.InvitationPolicy policy =
            configuration.invitationPolicy();
        String code = normalize(rawCode);
        if (code == null) {
            if (policy.required()) {
                throw invitationRequired();
            }
            return Optional.empty();
        }

        Optional<InviteCode> found = inviteCodes.findByCodeIgnoreCase(code);
        if (found.isEmpty() || found.get().getUsedAt() != null) {
            if (policy.required()) {
                throw invalidInvitation();
            }
            return Optional.empty();
        }

        InviteCode inviteCode = found.get();
        if (!policy.neverExpire()) {
            inviteCode.markUsed(Instant.now(clock));
            inviteCodes.save(inviteCode);
        }
        return Optional.of(inviteCode.getUserId());
    }

    @Transactional
    public InviteCode create(UUID userId) {
        int limit = configuration.invitationPolicy().generationLimit();
        if (
            limit <= 0
                || inviteCodes.countByUserIdAndUsedAtIsNull(userId) >= limit
        ) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "INVITATION_LIMIT_REACHED",
                "The maximum number of invitation codes has been reached"
            );
        }
        Instant now = Instant.now(clock);
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode();
            if (inviteCodes.findByCodeIgnoreCase(code).isEmpty()) {
                return inviteCodes.save(
                    InviteCode.create(UUID.randomUUID(), userId, code, now)
                );
            }
        }
        throw new IllegalStateException("Could not allocate an invitation code");
    }

    @Transactional(readOnly = true)
    public List<InvitationCodeView> available(UUID userId) {
        return inviteCodes
            .findByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(userId)
            .stream()
            .map(code -> new InvitationCodeView(
                code.getId(),
                code.getCode(),
                code.getCreatedAt()
            ))
            .toList();
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            code.append(
                CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]
            );
        }
        return code.toString();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private ApiProblemException invitationRequired() {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVITATION_REQUIRED",
            "An invitation code is required to register"
        );
    }

    private ApiProblemException invalidInvitation() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "INVITATION_INVALID",
            "The invitation code is invalid or has already been used"
        );
    }

    public record InvitationCodeView(
        UUID id,
        String code,
        Instant createdAt
    ) {
    }
}
