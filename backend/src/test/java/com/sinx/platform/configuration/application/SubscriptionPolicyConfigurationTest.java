package com.sinx.platform.configuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;

class SubscriptionPolicyConfigurationTest {

    @Test
    void subscriptionSectionReadsAndWritesLegacySettingNamesAndDefaults() {
        PlatformSettingRepository settings = mock(PlatformSettingRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Map<String, PlatformSetting> persisted = new HashMap<>();
        when(settings.findById(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> Optional.ofNullable(
                persisted.get(call.getArgument(0))
            ));
        when(settings.save(org.mockito.ArgumentMatchers.any(PlatformSetting.class)))
            .thenAnswer(call -> {
                PlatformSetting setting = call.getArgument(0);
                persisted.put(setting.key(), setting);
                return setting;
            });
        PlatformConfigurationService service = new PlatformConfigurationService(
            settings,
            Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC),
            events,
            mock(SubscriptionTemplates.class),
            "log"
        );

        assertThat(service.sectionSettings("subscribe"))
            .containsEntry("plan_change_enable", true)
            .containsEntry("surplus_enable", true)
            .containsEntry("globalreset_traffic_method", 1);

        service.saveSectionSettings("subscribe", Map.of("plan_change_enable", false));
        service.saveSectionSettings("subscribe", Map.of("surplus_enable", false));
        service.saveSectionSettings(
            "subscribe", Map.of("globalreset_traffic_method", 4)
        );

        assertThat(persisted.keySet()).containsExactlyInAnyOrder(
            "subscribe.plan_change_enable",
            "subscribe.surplus_enable",
            "subscribe.globalreset_traffic_method"
        );
        assertThat(service.sectionSettings("subscribe"))
            .containsEntry("plan_change_enable", false)
            .containsEntry("surplus_enable", false)
            .containsEntry("globalreset_traffic_method", 4);
        assertThat(service.globalTrafficResetPolicy())
            .isEqualTo(TrafficResetPolicy.YEARLY_FROM_ACTIVATION);
        verify(events).publishEvent(
            org.mockito.ArgumentMatchers.any(TrafficResetPolicyChangedEvent.class)
        );
    }

    @Test
    void invalidStoredResetMethodsFallBackToThePreservedMonthlyDefault() {
        PlatformSettingRepository settings = mock(PlatformSettingRepository.class);
        when(settings.findById("subscribe.globalreset_traffic_method"))
            .thenReturn(Optional.of(PlatformSetting.create(
                "subscribe.globalreset_traffic_method", "99", Instant.EPOCH
            )));
        PlatformConfigurationService service = new PlatformConfigurationService(
            settings,
            Clock.systemUTC(),
            mock(ApplicationEventPublisher.class),
            mock(SubscriptionTemplates.class),
            "log"
        );

        assertThat(service.subscriptionPolicy().globalResetMethod()).isEqualTo(1);
        assertThat(service.globalTrafficResetPolicy())
            .isEqualTo(TrafficResetPolicy.MONTHLY_FROM_ACTIVATION);
    }
}
