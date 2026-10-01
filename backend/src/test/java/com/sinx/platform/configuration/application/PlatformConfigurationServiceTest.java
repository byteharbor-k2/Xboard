package com.sinx.platform.configuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.databind.ObjectMapper;

class PlatformConfigurationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-08T00:00:00Z"),
        ZoneOffset.UTC
    );

    private final PlatformSettingRepository repository = mock(
        PlatformSettingRepository.class
    );
    private final ApplicationEventPublisher events = mock(
        ApplicationEventPublisher.class
    );
    private final Map<String, PlatformSetting> stored = new LinkedHashMap<>();
    private PlatformConfigurationService service;

    @BeforeEach
    void setUp() {
        when(repository.findById(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(stored.get(invocation.getArgument(0)))
        );
        when(repository.save(any(PlatformSetting.class))).thenAnswer(invocation -> {
            PlatformSetting setting = invocation.getArgument(0);
            stored.put(setting.key(), setting);
            return setting;
        });
        doAnswer(invocation -> {
            stored.remove(invocation.getArgument(0));
            return null;
        }).when(repository).deleteById(anyString());
        service = new PlatformConfigurationService(
            repository,
            CLOCK,
            events,
            new SubscriptionTemplates(new ObjectMapper()),
            "log"
        );
    }

    @Test
    void serverSettingsExposeSafeDefaultsForNodeCommunication() {
        PlatformConfigurationService.NodeCommunicationSettings settings =
            service.nodeCommunicationSettings();

        assertThat(settings.pullIntervalSeconds()).isEqualTo(60);
        assertThat(settings.pushIntervalSeconds()).isEqualTo(60);
        assertThat(settings.webSocketEnabled()).isTrue();
    }

    @Test
    void pollingIntervalsStoreTheSavedNumbersWithoutBounds() {
        service.saveSectionSettings(
            "server",
            Map.of("server_pull_interval", 30)
        );
        service.saveSectionSettings(
            "server",
            Map.of("server_push_interval", 10)
        );

        assertThat(service.nodeCommunicationSettings().pullIntervalSeconds())
            .isEqualTo(30);
        assertThat(service.nodeCommunicationSettings().pushIntervalSeconds())
            .isEqualTo(10);
        verify(events, never()).publishEvent(any());

        // One-time configuration carries no numeric bounds: whatever the
        // operator saved is read straight back.
        service.saveSectionSettings(
            "server",
            Map.of("server_pull_interval", 9)
        );
        assertThat(service.nodeCommunicationSettings().pullIntervalSeconds())
            .isEqualTo(9);

        // A value of the wrong shape stays the flat invalid-setting answer.
        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("server_pull_interval", "abc")
        )).isInstanceOf(ApiProblemException.class);
    }

    /**
     * The read side tolerates a corrupted row just like mailDelivery
     * ignores an unknown mode: junk number or junk switch reads as the
     * field's default instead of failing.
     */
    @Test
    void junkStoredValuesReadAsTheDefaults() {
        store("safe.register_limit_count", "abc");
        store("safe.register_limit_expire", "");
        store("safe.stop_register", "sometimes");
        store("safe.email_verify", "sometimes");
        store("safe.password_limit_enable", "sometimes");
        store("safe.password_limit_count", "abc");
        store("safe.password_limit_expire", "not-a-number");
        store("safe.captcha_enable", "sometimes");
        store("safe.email_whitelist_enable", "abc");
        store("safe.email_gmail_limit_enable", "maybe");
        store("server.server_pull_interval", "abc");
        store("server.server_ws_enable", "");

        assertThat(service.registrationIpLimit().limitCount()).isEqualTo(3);
        assertThat(service.registrationIpLimit().expireMinutes()).isEqualTo(60);
        assertThat(service.stopRegisterPolicy().stopped()).isFalse();
        assertThat(service.emailVerificationRequired()).isTrue();
        assertThat(service.loginAttemptPolicy().enabled()).isTrue();
        assertThat(service.loginAttemptPolicy().maxFailures())
            .isEqualTo(PlatformConfigurationService.DEFAULT_PASSWORD_LIMIT_COUNT);
        assertThat(service.loginAttemptPolicy().lockMinutes())
            .isEqualTo(
                PlatformConfigurationService.DEFAULT_PASSWORD_LIMIT_EXPIRE_MINUTES
            );
        assertThat(service.turnstilePolicy().enabled()).isFalse();
        assertThat(service.emailDomainPolicy().enabled()).isFalse();
        assertThat(service.gmailAliasPolicy().enabled()).isFalse();
        assertThat(service.nodeCommunicationSettings().pullIntervalSeconds())
            .isEqualTo(60);
        assertThat(service.nodeCommunicationSettings().webSocketEnabled())
            .isTrue();
    }

    @Test
    void legacyTokenRotationPublishesAfterCommitActionWithoutStaleReads() {
        service.saveSectionSettings(
            "server",
            Map.of("server_token", "0".repeat(64))
        );
        clearInvocations(events);

        service.saveSectionSettings(
            "server",
            Map.of("server_token", "f".repeat(64))
        );

        assertThat(service.nodeCommunicationSettings().legacyToken())
            .isEqualTo("f".repeat(64));
        ArgumentCaptor<NodeCommunicationSettingsChangedEvent> event =
            ArgumentCaptor.forClass(NodeCommunicationSettingsChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().legacyTokenChanged()).isTrue();
        assertThat(event.getValue().webSocketDisabled()).isFalse();
    }

    @Test
    void siteSettingsReadBackEverySavedSubscribeUrl() {
        service.saveSectionSettings(
            "site",
            Map.of(
                "subscribe_url",
                "https://a.example.com, https://b.example.com"
            )
        );

        Object readBack = service.sectionSettings("site").get("subscribe_url");
        assertThat(readBack)
            .isEqualTo("https://a.example.com,https://b.example.com");
        assertThat(service.subscribeUrls()).containsExactly(
            "https://a.example.com",
            "https://b.example.com"
        );

        // An editor resaving what it was handed back must not drop any entry.
        service.saveSectionSettings(
            "site",
            Map.of("subscribe_url", String.valueOf(readBack))
        );

        assertThat(service.sectionSettings("site").get("subscribe_url"))
            .isEqualTo("https://a.example.com,https://b.example.com");
    }

    /** Plants a raw settings row, as a pre-existing (possibly corrupt) DB state. */
    private void store(String key, String value) {
        stored.put(
            key,
            PlatformSetting.create(key, value, CLOCK.instant())
        );
    }

    @Test
    void legacyTokenRejectsWeakOrMalformedValues() {
        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("server_token", "simple-password")
        )).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("server_token", "G".repeat(64))
        )).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void disablingWebSocketPublishesDisconnectAction() {
        service.saveSectionSettings(
            "server",
            Map.of("server_ws_enable", false)
        );

        assertThat(service.nodeCommunicationSettings().webSocketEnabled())
            .isFalse();
        ArgumentCaptor<NodeCommunicationSettingsChangedEvent> event =
            ArgumentCaptor.forClass(NodeCommunicationSettingsChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().legacyTokenChanged()).isFalse();
        assertThat(event.getValue().webSocketDisabled()).isTrue();
    }
}
