package com.sinx.platform.configuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;

import tools.jackson.databind.ObjectMapper;

class CommissionConfigurationPolicyTest {

    private final PlatformSettingRepository repository = mock(PlatformSettingRepository.class);
    private final Map<String, PlatformSetting> settings = new LinkedHashMap<>();
    private PlatformConfigurationService configuration;

    @BeforeEach
    void setUp() {
        when(repository.findById(anyString())).thenAnswer(call ->
            Optional.ofNullable(settings.get(call.getArgument(0)))
        );
        when(repository.save(any(PlatformSetting.class))).thenAnswer(call -> {
            PlatformSetting value = call.getArgument(0);
            settings.put(value.key(), value);
            return value;
        });
        doAnswer(call -> {
            settings.remove(call.getArgument(0));
            return null;
        }).when(repository).deleteById(anyString());
        configuration = new PlatformConfigurationService(
            repository,
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC),
            mock(ApplicationEventPublisher.class),
            new SubscriptionTemplates(new ObjectMapper()),
            "log"
        );
    }

    @Test
    void commissionDefaultsMatchOriginalFirstPaymentDelayAndDisabledDistribution() {
        assertThat(configuration.invitationPolicy().commissionPercent()).isEqualTo(10);
        assertThat(configuration.commissionPolicy()).isEqualTo(
            new PlatformConfigurationService.CommissionPolicy(true, true, false, 0, 0, 0)
        );
        Map<String, Object> invite = configuration.sectionSettings("invite");
        assertThat(invite)
            .containsEntry("commission_first_time_enable", true)
            .containsEntry("commission_auto_check_enable", true)
            .containsEntry("commission_distribution_enable", false)
            .containsEntry("commission_distribution_l1", 0)
            .containsEntry("commission_distribution_l2", 0)
            .containsEntry("commission_distribution_l3", 0)
            .containsEntry("withdraw_close_enable", true);
    }

    @Test
    void savedCommissionSettingsChangeOnlyTheirEffectivePolicies() {
        configuration.saveSectionSettings("invite", Map.of("invite_commission", 18));
        configuration.saveSectionSettings("invite", Map.of("commission_first_time_enable", false));
        configuration.saveSectionSettings("invite", Map.of("commission_auto_check_enable", false));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_enable", true));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l1", 0));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l2", 45));
        configuration.saveSectionSettings("invite", Map.of("commission_distribution_l3", 20));
        // Compatibility field is read-only: payouts always credit site balance.
        configuration.saveSectionSettings("invite", Map.of("withdraw_close_enable", false));

        assertThat(configuration.invitationPolicy().commissionPercent()).isEqualTo(18);
        assertThat(configuration.commissionPolicy()).isEqualTo(
            new PlatformConfigurationService.CommissionPolicy(false, false, true, 0, 45, 20)
        );
        assertThat(configuration.sectionSettings("invite"))
            .containsEntry("withdraw_close_enable", true);
    }
}
