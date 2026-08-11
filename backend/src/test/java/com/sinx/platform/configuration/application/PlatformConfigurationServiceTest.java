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
        service = new PlatformConfigurationService(repository, CLOCK, events);
    }

    @Test
    void serverSettingsExposeSafeDefaultsIncludingRelaxedDeviceMode() {
        PlatformConfigurationService.NodeCommunicationSettings settings =
            service.nodeCommunicationSettings();

        assertThat(settings.pullIntervalSeconds()).isEqualTo(60);
        assertThat(settings.pushIntervalSeconds()).isEqualTo(60);
        assertThat(settings.deviceLimitMode()).isZero();
        assertThat(settings.webSocketEnabled()).isTrue();
        assertThat(service.sectionSettings("server"))
            .containsEntry("device_limit_mode", 0);
    }

    @Test
    void deviceLimitModeRoundTripsImmediatelyAndRejectsUnknownModes() {
        service.saveSectionSettings(
            "server",
            Map.of("device_limit_mode", 1)
        );

        assertThat(service.nodeCommunicationSettings().deviceLimitMode())
            .isEqualTo(1);
        assertThat(service.sectionSettings("server"))
            .containsEntry("device_limit_mode", 1);
        verify(events, never()).publishEvent(any());

        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("device_limit_mode", -1)
        )).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("device_limit_mode", 2)
        )).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("device_limit_mode", 0.5)
        )).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void pollingIntervalsUseTheMachineModeCompatibleBounds() {
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

        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("server_pull_interval", 29)
        )).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.saveSectionSettings(
            "server",
            Map.of("server_push_interval", 9)
        )).isInstanceOf(ApiProblemException.class);
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
