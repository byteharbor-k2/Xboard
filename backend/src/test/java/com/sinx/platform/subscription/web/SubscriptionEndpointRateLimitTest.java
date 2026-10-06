package com.sinx.platform.subscription.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.subscription.application.SubscriptionLinkService;
import com.sinx.platform.subscription.application.SubscriptionRequestRateLimiter;
import com.sinx.platform.subscription.client.ClientConfigService;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class SubscriptionEndpointRateLimitTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final SubscriptionEntitlementRepository entitlements =
        mock(SubscriptionEntitlementRepository.class);
    private final ClientConfigService configs = mock(ClientConfigService.class);
    private final SubscriptionLinkService links = mock(SubscriptionLinkService.class);
    private final SubscriptionRequestRateLimiter limiter =
        mock(SubscriptionRequestRateLimiter.class);
    private final SubscriptionEndpointController controller =
        new SubscriptionEndpointController(
            users,
            entitlements,
            configs,
            links,
            limiter,
            Clock.systemUTC()
        );

    @Test
    void knownAccountThrottlingIsTokenAndFormatIndependentAndHasSafeResponse() {
        UUID userId = UUID.randomUUID();
        UserAccount user = mock(UserAccount.class);
        when(users.findBySubscriptionToken("old-token"))
            .thenReturn(Optional.of(user));
        when(users.findBySubscriptionToken("rotated-token"))
            .thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(userId);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        SubscriptionEntitlement expired = mock(SubscriptionEntitlement.class);
        when(entitlements.findByUserId(userId)).thenReturn(Optional.of(expired));
        when(expired.stateAt(org.mockito.ArgumentMatchers.any(Instant.class)))
            .thenReturn(EntitlementState.EXPIRED);
        when(limiter.tryAcquire(userId)).thenReturn(true, true, false);

        ResponseEntity<byte[]> first = controller.subscribe(
            "old-token", "clash", null, null, "clash-verge", "sub.example.com"
        );
        ResponseEntity<byte[]> second = controller.subscribe(
            "rotated-token", "sing-box", null, null, "sing-box/1.0", "sub.example.com"
        );
        ResponseEntity<byte[]> throttled = controller.subscribe(
            "rotated-token", "surge", null, null, "Surge/5", "sub.example.com"
        );

        assertThat(first.getStatusCode().value()).isEqualTo(403);
        assertThat(second.getStatusCode().value()).isEqualTo(403);
        assertThat(throttled.getStatusCode().value()).isEqualTo(429);
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
            .isEqualTo("1");
        assertThat(throttled.getHeaders().getContentType())
            .isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(throttled.getBody()).isNull();
        verify(limiter, org.mockito.Mockito.times(3)).tryAcquire(userId);
        verify(entitlements, org.mockito.Mockito.times(2)).findByUserId(userId);
        verify(configs, never()).render(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void unknownTokenDoesNotConsumeAnyAccountWindow() {
        when(users.findBySubscriptionToken("not-a-known-token"))
            .thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.subscribe(
            "not-a-known-token", null, null, null, null, null
        );

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
        verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.any());
    }
}
