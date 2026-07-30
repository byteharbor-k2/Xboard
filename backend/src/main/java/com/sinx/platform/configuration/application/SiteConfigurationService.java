package com.sinx.platform.configuration.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
@Transactional(readOnly = true)
public class SiteConfigurationService {

    private static final String TERMS_URL_KEY = "site.tos_url";

    private final PlatformSettingRepository settings;
    private final Clock clock;

    public SiteConfigurationService(
        PlatformSettingRepository settings,
        Clock clock
    ) {
        this.settings = settings;
        this.clock = clock;
    }

    public Map<String, Object> siteSettings() {
        return Map.of("tos_url", termsUrl().orElse(""));
    }

    public Optional<String> termsUrl() {
        return settings.findById(TERMS_URL_KEY)
            .map(PlatformSetting::value)
            .filter(value -> !value.isBlank());
    }

    @Transactional
    public void saveSiteSettings(Map<String, Object> values) {
        if (values.size() != 1 || !values.containsKey("tos_url")) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "SETTING_NOT_SUPPORTED",
                "Only the terms of service URL is supported"
            );
        }
        Object rawValue = values.get("tos_url");
        if (!(rawValue instanceof String value)) {
            throw invalidTermsUrl();
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            settings.deleteById(TERMS_URL_KEY);
            return;
        }
        validateTermsUrl(normalized);
        Instant now = Instant.now(clock);
        PlatformSetting setting = settings.findById(TERMS_URL_KEY)
            .orElseGet(() ->
                PlatformSetting.create(TERMS_URL_KEY, normalized, now)
            );
        setting.update(normalized, now);
        settings.save(setting);
    }

    private void validateTermsUrl(String value) {
        if (value.length() > 2048) {
            throw invalidTermsUrl();
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (
                scheme == null
                    || (!scheme.equalsIgnoreCase("https")
                        && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null
            ) {
                throw invalidTermsUrl();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidTermsUrl();
        }
    }

    private ApiProblemException invalidTermsUrl() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "TERMS_URL_INVALID",
            "The terms of service URL must be a valid HTTP or HTTPS URL"
        );
    }
}
