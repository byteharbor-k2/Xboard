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
    private static final String EMAIL_VERIFICATION_KEY = "safe.email_verify";
    private static final String CAPTCHA_ENABLED_KEY = "safe.captcha_enable";
    private static final String CAPTCHA_TYPE_KEY = "safe.captcha_type";
    private static final String TURNSTILE_SITE_KEY =
        "safe.turnstile_site_key";
    private static final String TURNSTILE_SECRET_KEY =
        "safe.turnstile_secret_key";
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
                TurnstilePolicy turnstile = turnstilePolicy();
                yield Map.of(
                    "email_verify",
                    emailVerificationRequired(),
                    "email_whitelist_enable",
                    policy.enabled(),
                    "email_whitelist_suffix",
                    policy.domains(),
                    "captcha_enable",
                    turnstile.enabled(),
                    "captcha_type",
                    "turnstile",
                    "turnstile_site_key",
                    turnstile.siteKey() == null ? "" : turnstile.siteKey(),
                    "turnstile_secret_key",
                    ""
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
            case EMAIL_VERIFICATION_KEY ->
                saveBoolean(EMAIL_VERIFICATION_KEY, entry.getValue());
            case CAPTCHA_ENABLED_KEY ->
                saveBoolean(CAPTCHA_ENABLED_KEY, entry.getValue());
            case CAPTCHA_TYPE_KEY -> saveCaptchaType(entry.getValue());
            case TURNSTILE_SITE_KEY ->
                saveTurnstileKey(TURNSTILE_SITE_KEY, entry.getValue(), false);
            case TURNSTILE_SECRET_KEY ->
                saveTurnstileKey(TURNSTILE_SECRET_KEY, entry.getValue(), true);
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

    public boolean emailVerificationRequired() {
        return read(EMAIL_VERIFICATION_KEY)
            .map(Boolean::parseBoolean)
            .orElse(true);
    }

    public TurnstilePolicy turnstilePolicy() {
        boolean enabled = read(CAPTCHA_ENABLED_KEY)
            .map(Boolean::parseBoolean)
            .orElse(false);
        String type = read(CAPTCHA_TYPE_KEY).orElse("turnstile");
        if (!"turnstile".equals(type)) {
            return new TurnstilePolicy(false, null, null);
        }
        return new TurnstilePolicy(
            enabled,
            read(TURNSTILE_SITE_KEY).filter(value -> !value.isBlank())
                .orElse(null),
            read(TURNSTILE_SECRET_KEY).filter(value -> !value.isBlank())
                .orElse(null)
        );
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
        saveBoolean(EMAIL_ALLOWLIST_ENABLED_KEY, rawValue);
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

    private void saveBoolean(String key, Object rawValue) {
        if (!(rawValue instanceof Boolean enabled)) {
            throw invalidSettingValue();
        }
        store(key, Boolean.toString(enabled));
    }

    private void saveCaptchaType(Object rawValue) {
        if (!(rawValue instanceof String type) || !"turnstile".equals(type)) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "CAPTCHA_PROVIDER_NOT_SUPPORTED",
                "Only Cloudflare Turnstile is currently supported"
            );
        }
        store(CAPTCHA_TYPE_KEY, type);
    }

    private void saveTurnstileKey(
        String key,
        Object rawValue,
        boolean preserveWhenBlank
    ) {
        if (!(rawValue instanceof String value) || value.length() > 256) {
            throw invalidSettingValue();
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            if (!preserveWhenBlank) {
                settings.deleteById(key);
            }
            return;
        }
        store(key, normalized);
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

    private ApiProblemException invalidSettingValue() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "SETTING_VALUE_INVALID",
            "The setting value is invalid"
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

    public record TurnstilePolicy(
        boolean enabled,
        String siteKey,
        String secretKey
    ) {
    }
}
