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

class TurnstileConfigurationPolicyTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-10-04T00:00:00Z"),
        ZoneOffset.UTC
    );
    private static final String SITE_KEY = "public-turnstile-site";
    private static final String SECRET_KEY = "private-turnstile-secret";

    private final PlatformSettingRepository repository = mock(
        PlatformSettingRepository.class
    );
    private final Map<String, PlatformSetting> stored = new LinkedHashMap<>();
    private PlatformConfigurationService configuration;

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
        configuration = new PlatformConfigurationService(
            repository,
            CLOCK,
            mock(ApplicationEventPublisher.class),
            new SubscriptionTemplates(new ObjectMapper()),
            "log"
        );
    }

    @Test
    void legacyProviderValueCannotBypassEnabledTurnstileAndSecretRemainsWriteOnly() {
        configuration.saveSectionSettings("safe", Map.of("captcha_enable", true));
        configuration.saveSectionSettings("safe", Map.of("captcha_type", "recaptcha"));
        configuration.saveSectionSettings(
            "safe",
            Map.of("turnstile_site_key", SITE_KEY)
        );
        configuration.saveSectionSettings(
            "safe",
            Map.of("turnstile_secret_key", SECRET_KEY)
        );

        PlatformConfigurationService.TurnstilePolicy policy =
            configuration.turnstilePolicy();
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.siteKey()).isEqualTo(SITE_KEY);
        assertThat(policy.secretKey()).isEqualTo(SECRET_KEY);

        Map<String, Object> safeSettings = configuration.sectionSettings("safe");
        assertThat(safeSettings)
            .containsEntry("captcha_type", "turnstile")
            .containsEntry("turnstile_secret_key", "")
            .containsEntry("turnstile_secret_key_configured", true);
        assertThat(safeSettings.toString()).doesNotContain(SECRET_KEY);

        // The UI submits an empty write-only password value when operators
        // deliberately edit that field; that must retain the saved secret.
        configuration.saveSectionSettings(
            "safe",
            Map.of("turnstile_secret_key", "")
        );
        assertThat(configuration.turnstilePolicy().secretKey())
            .isEqualTo(SECRET_KEY);
        assertThat(configuration.sectionSettings("safe"))
            .containsEntry("turnstile_secret_key_configured", true);
    }
}
