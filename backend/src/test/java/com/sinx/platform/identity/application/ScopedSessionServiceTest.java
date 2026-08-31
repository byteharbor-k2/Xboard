package com.sinx.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sinx.platform.identity.domain.DeviceSession;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.SessionScope;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.DeviceSessionRepository;
import com.sinx.platform.identity.security.IdentitySecurityProperties;
import com.sinx.platform.identity.security.IdentityTokenService;
import com.sinx.platform.identity.security.IdentityTokenService.AccessTokenGrant;
import com.sinx.platform.shared.web.ApiProblemException;

class ScopedSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DeviceSessionRepository sessions =
        mock(DeviceSessionRepository.class);
    private final IdentityTokenService tokens =
        mock(IdentityTokenService.class);
    private final RefreshTokenReplayWindow window =
        mock(RefreshTokenReplayWindow.class);
    private final ScopedSessionService service = new ScopedSessionService(
        sessions,
        tokens,
        properties(),
        window,
        CLOCK
    );

    @Test
    void handsTheWinnersTokenBackToARefreshThatLostTheRace() {
        DeviceSession rotated = session("old-hash");
        rotated.revoke(NOW, UUID.randomUUID());
        DeviceSession replacement = session("new-hash");
        when(tokens.hashRefreshToken("old-token")).thenReturn("old-hash");
        when(tokens.hashRefreshToken("new-token")).thenReturn("new-hash");
        when(sessions.findForUpdateByTokenHash("old-hash"))
            .thenReturn(Optional.of(rotated));
        when(sessions.findForUpdateByTokenHash("new-hash"))
            .thenReturn(Optional.of(replacement));
        when(window.recall("old-hash")).thenReturn(Optional.of("new-token"));
        when(tokens.issueAccessToken(any(), any(), any()))
            .thenReturn(new AccessTokenGrant("access", NOW.plusSeconds(300)));

        ScopedSessionService.SessionGrant grant =
            service.refresh("old-token", SessionScope.USER);

        // The same token, not a second one: forking the chain would leave an
        // attacker with a lineage of their own.
        assertThat(grant.refreshToken()).isEqualTo("new-token");
        assertThat(grant.sessionId()).isEqualTo(replacement.getId());
        verify(sessions, never()).revokeActiveFamily(any(), any());
    }

    @Test
    void stillTearsDownTheFamilyWhenTheWindowHasNothingToOffer() {
        DeviceSession rotated = session("old-hash");
        rotated.revoke(NOW.minusSeconds(600), UUID.randomUUID());
        when(tokens.hashRefreshToken("old-token")).thenReturn("old-hash");
        when(sessions.findForUpdateByTokenHash("old-hash"))
            .thenReturn(Optional.of(rotated));
        when(window.recall("old-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old-token", SessionScope.USER))
            .isInstanceOf(ApiProblemException.class);

        verify(sessions).revokeActiveFamily(rotated.getSessionFamilyId(), NOW);
    }

    private static IdentitySecurityProperties properties() {
        return new IdentitySecurityProperties(
            "sinx-test",
            Duration.ofMinutes(10),
            Duration.ofDays(30),
            Duration.ofMinutes(5),
            Duration.ofHours(12),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofSeconds(10),
            "SinX Cloud",
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "test-only-jwt-secret-with-at-least-32-characters",
            "rt_session",
            "rt_admin",
            false
        );
    }

    private DeviceSession session(String tokenHash) {
        UserAccount user = mock(UserAccount.class);
        Role role = mock(Role.class);
        when(role.getCode()).thenReturn("USER");
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getRoles()).thenReturn(Set.of(role));
        return DeviceSession.create(
            UUID.randomUUID(),
            user,
            UUID.randomUUID(),
            tokenHash,
            "test-device",
            SessionScope.USER,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3600)
        );
    }
}
