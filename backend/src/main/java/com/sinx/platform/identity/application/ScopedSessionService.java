package com.sinx.platform.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.identity.domain.DeviceSession;
import com.sinx.platform.identity.domain.SessionScope;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.security.IdentitySecurityProperties;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.identity.security.IdentityTokenService.AccessTokenGrant;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
public class ScopedSessionService {

    private final DeviceSessionRepository sessionRepository;
    private final IdentityTokenService tokenService;
    private final IdentitySecurityProperties properties;
    private final RefreshTokenReplayWindow replayWindow;
    private final Clock clock;

    public ScopedSessionService(
        DeviceSessionRepository sessionRepository,
        IdentityTokenService tokenService,
        IdentitySecurityProperties properties,
        RefreshTokenReplayWindow replayWindow,
        Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.tokenService = tokenService;
        this.properties = properties;
        this.replayWindow = replayWindow;
        this.clock = clock;
    }

    @Transactional
    public SessionGrant issue(
        UserAccount user,
        String deviceLabel,
        SessionScope scope,
        UUID existingFamilyId
    ) {
        Instant now = Instant.now(clock);
        String refreshToken = tokenService.newRefreshToken();
        Instant refreshExpiresAt = now.plus(
            scope == SessionScope.ADMIN
                ? properties.adminRefreshTokenTtl()
                : properties.refreshTokenTtl()
        );
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
            scope,
            now,
            refreshExpiresAt
        );
        sessionRepository.save(session);
        AccessTokenGrant accessToken = tokenService.issueAccessToken(
            user,
            sessionId,
            scope
        );
        return new SessionGrant(
            accessToken.token(),
            accessToken.expiresAt(),
            refreshToken,
            refreshExpiresAt,
            session.getId(),
            ViewerView.forScope(user, scope)
        );
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public SessionGrant refresh(
        String refreshToken,
        SessionScope expectedScope
    ) {
        String tokenHash = tokenService.hashRefreshToken(refreshToken);
        DeviceSession currentSession = sessionRepository
            .findForUpdateByTokenHash(tokenHash)
            .orElseThrow(this::invalidRefreshToken);
        Instant now = Instant.now(clock);

        if (currentSession.isRevoked()) {
            Optional<SessionGrant> concurrent =
                settleConcurrentRefresh(tokenHash, expectedScope, now);
            if (concurrent.isPresent()) {
                return concurrent.get();
            }
            replayWindow.forget(tokenHash);
            sessionRepository.revokeActiveFamily(
                currentSession.getSessionFamilyId(),
                now
            );
            throw invalidRefreshToken();
        }
        if (!currentSession.isActiveAt(now)
                || currentSession.getSessionScope() != expectedScope) {
            throw invalidRefreshToken();
        }
        if (currentSession.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ACCOUNT_SUSPENDED",
                "This account is not available"
            );
        }
        boolean entitled = currentSession.getUser().getRoles().stream()
            .map(Role::getCode)
            .anyMatch(expectedScope.name()::equals);
        if (!entitled) {
            currentSession.revoke(now, null);
            throw invalidRefreshToken();
        }

        SessionGrant replacement = issue(
            currentSession.getUser(),
            currentSession.getDeviceLabel(),
            expectedScope,
            currentSession.getSessionFamilyId()
        );
        currentSession.revoke(now, replacement.sessionId());
        replayWindow.remember(tokenHash, replacement.refreshToken());
        return replacement;
    }

    /**
     * Answers a refresh that lost a race against another refresh carrying the
     * same cookie - a reload on top of an in-flight call, a network retry.
     *
     * The loser gets back the very token the winner was issued rather than one
     * of its own, so the chain never forks: whichever holder rotates next
     * invalidates the other, and replay detection resumes immediately. Minting
     * a second token here would instead hand an attacker a parallel lineage
     * that survives alongside the victim's.
     *
     * Outside the window, or once the replacement itself is gone, this finds
     * nothing and the caller tears the family down as before.
     */
    private Optional<SessionGrant> settleConcurrentRefresh(
        String rotatedTokenHash,
        SessionScope expectedScope,
        Instant now
    ) {
        return replayWindow.recall(rotatedTokenHash).flatMap(replacementToken ->
            sessionRepository
                .findForUpdateByTokenHash(
                    tokenService.hashRefreshToken(replacementToken)
                )
                .filter(session -> session.isActiveAt(now))
                .filter(session -> session.getSessionScope() == expectedScope)
                .filter(session ->
                    session.getUser().getStatus() == UserStatus.ACTIVE
                )
                .map(session -> {
                    AccessTokenGrant accessToken = tokenService.issueAccessToken(
                        session.getUser(),
                        session.getId(),
                        expectedScope
                    );
                    return new SessionGrant(
                        accessToken.token(),
                        accessToken.expiresAt(),
                        replacementToken,
                        session.getExpiresAt(),
                        session.getId(),
                        ViewerView.forScope(session.getUser(), expectedScope)
                    );
                })
        );
    }

    @Transactional
    public void logout(String refreshToken, SessionScope expectedScope) {
        String tokenHash = tokenService.hashRefreshToken(refreshToken);
        sessionRepository.findForUpdateByTokenHash(tokenHash).ifPresent(session -> {
            Instant now = Instant.now(clock);
            if (session.getSessionScope() == expectedScope
                    && session.isActiveAt(now)) {
                session.revoke(now, null);
            }
        });
    }

    private ApiProblemException invalidRefreshToken() {
        return new ApiProblemException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_REFRESH_TOKEN",
            "The refresh session is invalid or expired"
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
