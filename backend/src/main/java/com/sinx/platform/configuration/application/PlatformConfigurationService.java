package com.sinx.platform.configuration.application;

import java.net.IDN;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
@Transactional(readOnly = true)
public class PlatformConfigurationService {

    private static final String TERMS_URL_KEY = "site.tos_url";
    private static final String EMAIL_ALLOWLIST_ENABLED_KEY =
        "safe.email_whitelist_enable";
    private static final String EMAIL_ALLOWLIST_SUFFIXES_KEY =
        "safe.email_whitelist_suffix";
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
            + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"
    );

    private final PlatformSettingRepository settings;
    private final Clock clock;

    public PlatformConfigurationService(
        PlatformSettingRepository settings,
        Clock clock
    ) {
        this.settings = settings;
        this.clock = clock;
    }

    public Map<String, Object> sectionSettings(String section) {
        return switch (section) {
            case "site" -> Map.of("tos_url", termsUrl().orElse(""));
            case "safe" -> {
                EmailDomainPolicy policy = emailDomainPolicy();
                yield Map.of(
                    "email_whitelist_enable",
                    policy.enabled(),
                    "email_whitelist_suffix",
                    policy.domains()
                );
            }
            default -> throw unsupportedSection();
        };
    }

    @Transactional
    public void saveSectionSettings(
        String section,
        Map<String, Object> values
    ) {
        if (values.size() != 1) {
            throw unsupportedSetting();
        }
        Map.Entry<String, Object> entry = values.entrySet()
            .iterator()
            .next();
        switch (section + "." + entry.getKey()) {
            case TERMS_URL_KEY -> saveTermsUrl(entry.getValue());
            case EMAIL_ALLOWLIST_ENABLED_KEY ->
                saveEmailAllowlistEnabled(entry.getValue());
            case EMAIL_ALLOWLIST_SUFFIXES_KEY ->
                saveEmailAllowlistDomains(entry.getValue());
            default -> throw unsupportedSetting();
        }
    }

    public Optional<String> termsUrl() {
        return read(TERMS_URL_KEY).filter(value -> !value.isBlank());
    }

    public EmailDomainPolicy emailDomainPolicy() {
        boolean enabled = read(EMAIL_ALLOWLIST_ENABLED_KEY)
            .map(Boolean::parseBoolean)
            .orElse(false);
        List<String> domains = read(EMAIL_ALLOWLIST_SUFFIXES_KEY)
            .stream()
            .flatMap(String::lines)
            .filter(value -> !value.isBlank())
            .toList();
        return new EmailDomainPolicy(enabled, domains);
    }

    public void assertEmailDomainAllowed(String email) {
        EmailDomainPolicy policy = emailDomainPolicy();
        if (!policy.enabled()) {
            return;
        }
        int separator = email.lastIndexOf('@');
        String domain = separator < 0
            ? ""
            : email.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!policy.domains().contains(domain)) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "EMAIL_DOMAIN_NOT_ALLOWED",
                "This email domain is not allowed for registration"
            );
        }
    }

    private void saveTermsUrl(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidTermsUrl();
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            settings.deleteById(TERMS_URL_KEY);
            return;
        }
        validateTermsUrl(normalized);
        store(TERMS_URL_KEY, normalized);
    }

    private void saveEmailAllowlistEnabled(Object rawValue) {
        if (!(rawValue instanceof Boolean enabled)) {
            throw invalidEmailDomainPolicy();
        }
        store(EMAIL_ALLOWLIST_ENABLED_KEY, Boolean.toString(enabled));
    }

    private void saveEmailAllowlistDomains(Object rawValue) {
        if (!(rawValue instanceof List<?> values) || values.size() > 100) {
            throw invalidEmailDomainPolicy();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String domain)) {
                throw invalidEmailDomainPolicy();
            }
            normalized.add(normalizeDomain(domain));
        }
        store(
            EMAIL_ALLOWLIST_SUFFIXES_KEY,
            String.join("\n", new ArrayList<>(normalized))
        );
    }

    private String normalizeDomain(String value) {
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        while (candidate.startsWith("@")) {
            candidate = candidate.substring(1);
        }
        try {
            candidate = IDN.toASCII(candidate);
        } catch (IllegalArgumentException exception) {
            throw invalidEmailDomainPolicy();
        }
        if (
            candidate.length() > 253
                || !DOMAIN_PATTERN.matcher(candidate).matches()
        ) {
            throw invalidEmailDomainPolicy();
        }
        return candidate;
    }

    private Optional<String> read(String key) {
        return settings.findById(key).map(PlatformSetting::value);
    }

    private void store(String key, String value) {
        Instant now = Instant.now(clock);
        PlatformSetting setting = settings.findById(key)
            .orElseGet(() -> PlatformSetting.create(key, value, now));
        setting.update(value, now);
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

    private ApiProblemException invalidEmailDomainPolicy() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "EMAIL_DOMAIN_POLICY_INVALID",
            "The email domain allowlist is invalid"
        );
    }

    private ApiProblemException unsupportedSection() {
        return new ApiProblemException(
            HttpStatus.NOT_FOUND,
            "SETTINGS_SECTION_NOT_AVAILABLE",
            "This settings section is not available yet"
        );
    }

    private ApiProblemException unsupportedSetting() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "SETTING_NOT_SUPPORTED",
            "This setting is not supported yet"
        );
    }

    public record EmailDomainPolicy(
        boolean enabled,
        List<String> domains
    ) {
        public EmailDomainPolicy {
            domains = List.copyOf(domains);
        }
    }
}
