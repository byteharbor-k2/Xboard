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

    private static final String APP_URL_KEY = "site.app_url";
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
    private static final String INVITE_REQUIRED_KEY = "invite.invite_force";
    private static final String INVITE_COMMISSION_KEY =
        "invite.invite_commission";
    private static final String INVITE_GENERATION_LIMIT_KEY =
        "invite.invite_gen_limit";
    private static final String INVITE_NEVER_EXPIRE_KEY =
        "invite.invite_never_expire";
    private static final String EMAIL_HOST_KEY = "email.email_host";
    private static final String EMAIL_PORT_KEY = "email.email_port";
    private static final String EMAIL_ENCRYPTION_KEY =
        "email.email_encryption";
    private static final String EMAIL_USERNAME_KEY = "email.email_username";
    private static final String EMAIL_PASSWORD_KEY = "email.email_password";
    private static final String EMAIL_FROM_ADDRESS_KEY =
        "email.email_from_address";
    private static final String EMAIL_REMINDERS_KEY =
        "email.remind_mail_enable";
    private static final String SERVER_TOKEN_KEY = "server.server_token";
    private static final String SERVER_PULL_INTERVAL_KEY =
        "server.server_pull_interval";
    private static final String SERVER_PUSH_INTERVAL_KEY =
        "server.server_push_interval";
    private static final String SERVER_WS_ENABLED_KEY =
        "server.server_ws_enable";
    private static final String SERVER_WS_URL_KEY = "server.server_ws_url";
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
            case "site" -> Map.of(
                "app_url", appUrl().orElse(""),
                "tos_url", termsUrl().orElse("")
            );
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
            case "invite" -> {
                InvitationPolicy policy = invitationPolicy();
                yield Map.of(
                    "invite_force",
                    policy.required(),
                    "invite_commission",
                    policy.commissionPercent(),
                    "invite_gen_limit",
                    policy.generationLimit(),
                    "invite_never_expire",
                    policy.neverExpire()
                );
            }
            case "email" -> {
                MailSettings mail = mailSettings();
                yield Map.of(
                    "email_host",
                    mail.host() == null ? "" : mail.host(),
                    "email_port",
                    mail.port(),
                    "email_encryption",
                    mail.encryption(),
                    "email_username",
                    mail.username() == null ? "" : mail.username(),
                    "email_password",
                    "",
                    "email_from_address",
                    mail.fromAddress() == null ? "" : mail.fromAddress(),
                    "remind_mail_enable",
                    mail.remindersEnabled()
                );
            }
            case "server" -> {
                NodeCommunicationSettings node = nodeCommunicationSettings();
                yield Map.of(
                    "server_token", node.legacyToken() == null ? "" : node.legacyToken(),
                    "server_pull_interval", node.pullIntervalSeconds(),
                    "server_push_interval", node.pushIntervalSeconds(),
                    "server_ws_enable", node.webSocketEnabled(),
                    "server_ws_url", node.webSocketUrl() == null ? "" : node.webSocketUrl()
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
            case APP_URL_KEY -> saveAppUrl(entry.getValue());
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
            case INVITE_REQUIRED_KEY ->
                saveBoolean(INVITE_REQUIRED_KEY, entry.getValue());
            case INVITE_COMMISSION_KEY ->
                saveInteger(INVITE_COMMISSION_KEY, entry.getValue(), 0, 100);
            case INVITE_GENERATION_LIMIT_KEY ->
                saveInteger(
                    INVITE_GENERATION_LIMIT_KEY,
                    entry.getValue(),
                    0,
                    100
                );
            case INVITE_NEVER_EXPIRE_KEY ->
                saveBoolean(INVITE_NEVER_EXPIRE_KEY, entry.getValue());
            case EMAIL_HOST_KEY -> saveMailHost(entry.getValue());
            case EMAIL_PORT_KEY ->
                saveInteger(EMAIL_PORT_KEY, entry.getValue(), 1, 65_535);
            case EMAIL_ENCRYPTION_KEY ->
                saveMailEncryption(entry.getValue());
            case EMAIL_USERNAME_KEY ->
                saveOptionalString(
                    EMAIL_USERNAME_KEY,
                    entry.getValue(),
                    320,
                    false
                );
            case EMAIL_PASSWORD_KEY ->
                saveOptionalString(
                    EMAIL_PASSWORD_KEY,
                    entry.getValue(),
                    2048,
                    true
                );
            case EMAIL_FROM_ADDRESS_KEY ->
                saveMailFromAddress(entry.getValue());
            case EMAIL_REMINDERS_KEY ->
                saveBoolean(EMAIL_REMINDERS_KEY, entry.getValue());
            case SERVER_TOKEN_KEY -> saveServerToken(entry.getValue());
            case SERVER_PULL_INTERVAL_KEY ->
                saveInteger(SERVER_PULL_INTERVAL_KEY, entry.getValue(), 5, 3600);
            case SERVER_PUSH_INTERVAL_KEY ->
                saveInteger(SERVER_PUSH_INTERVAL_KEY, entry.getValue(), 5, 3600);
            case SERVER_WS_ENABLED_KEY ->
                saveBoolean(SERVER_WS_ENABLED_KEY, entry.getValue());
            case SERVER_WS_URL_KEY -> saveWebSocketUrl(entry.getValue());
            default -> throw unsupportedSetting();
        }
    }

    public Optional<String> termsUrl() {
        return read(TERMS_URL_KEY).filter(value -> !value.isBlank());
    }

    public Optional<String> appUrl() {
        return read(APP_URL_KEY).filter(value -> !value.isBlank());
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

    public InvitationPolicy invitationPolicy() {
        return new InvitationPolicy(
            readBoolean(INVITE_REQUIRED_KEY, false),
            readInteger(INVITE_COMMISSION_KEY, 10),
            readInteger(INVITE_GENERATION_LIMIT_KEY, 5),
            readBoolean(INVITE_NEVER_EXPIRE_KEY, false)
        );
    }

    public MailSettings mailSettings() {
        return new MailSettings(
            read(EMAIL_HOST_KEY).filter(value -> !value.isBlank())
                .orElse(null),
            readInteger(EMAIL_PORT_KEY, 465),
            read(EMAIL_ENCRYPTION_KEY).orElse("ssl"),
            read(EMAIL_USERNAME_KEY).filter(value -> !value.isBlank())
                .orElse(null),
            read(EMAIL_PASSWORD_KEY).filter(value -> !value.isBlank())
                .orElse(null),
            read(EMAIL_FROM_ADDRESS_KEY).filter(value -> !value.isBlank())
                .orElse(null),
            readBoolean(EMAIL_REMINDERS_KEY, false)
        );
    }

    public NodeCommunicationSettings nodeCommunicationSettings() {
        return new NodeCommunicationSettings(
            read(SERVER_TOKEN_KEY).filter(value -> !value.isBlank()).orElse(null),
            readInteger(SERVER_PULL_INTERVAL_KEY, 60),
            readInteger(SERVER_PUSH_INTERVAL_KEY, 60),
            readBoolean(SERVER_WS_ENABLED_KEY, true),
            read(SERVER_WS_URL_KEY).filter(value -> !value.isBlank()).orElse(null)
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

    private void saveAppUrl(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidAppUrl();
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            settings.deleteById(APP_URL_KEY);
            return;
        }
        validateAppUrl(normalized);
        store(APP_URL_KEY, normalized);
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

    private void saveInteger(
        String key,
        Object rawValue,
        int minimum,
        int maximum
    ) {
        if (!(rawValue instanceof Number number)) {
            throw invalidSettingValue();
        }
        long value = number.longValue();
        if (
            value < minimum
                || value > maximum
                || number.doubleValue() != value
        ) {
            throw invalidSettingValue();
        }
        store(key, Long.toString(value));
    }

    private void saveMailHost(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            settings.deleteById(EMAIL_HOST_KEY);
            return;
        }
        if (
            normalized.length() > 253
                || normalized.contains("/")
                || normalized.contains(":")
                || normalized.chars().anyMatch(Character::isWhitespace)
        ) {
            throw invalidSettingValue();
        }
        store(EMAIL_HOST_KEY, normalized);
    }

    private void saveMailEncryption(Object rawValue) {
        if (
            !(rawValue instanceof String value)
                || !Set.of("", "ssl", "tls").contains(value)
        ) {
            throw invalidSettingValue();
        }
        store(EMAIL_ENCRYPTION_KEY, value);
    }

    private void saveMailFromAddress(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            settings.deleteById(EMAIL_FROM_ADDRESS_KEY);
            return;
        }
        int separator = normalized.lastIndexOf('@');
        if (
            normalized.length() > 320
                || separator < 1
                || separator == normalized.length() - 1
        ) {
            throw invalidSettingValue();
        }
        store(EMAIL_FROM_ADDRESS_KEY, normalized);
    }

    private void saveWebSocketUrl(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            settings.deleteById(SERVER_WS_URL_KEY);
            return;
        }
        if (normalized.length() > 2048) {
            throw invalidSettingValue();
        }
        try {
            URI uri = URI.create(normalized);
            if (!("ws".equalsIgnoreCase(uri.getScheme())
                || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
                throw invalidSettingValue();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidSettingValue();
        }
        store(SERVER_WS_URL_KEY, normalized);
    }

    private void saveServerToken(Object rawValue) {
        if (!(rawValue instanceof String value) || value.length() > 256) {
            throw invalidSettingValue();
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            settings.deleteById(SERVER_TOKEN_KEY);
            return;
        }
        if (normalized.length() < 16) {
            throw invalidSettingValue();
        }
        store(SERVER_TOKEN_KEY, normalized);
    }

    private void saveOptionalString(
        String key,
        Object rawValue,
        int maximumLength,
        boolean preserveWhenBlank
    ) {
        if (!(rawValue instanceof String value) || value.length() > maximumLength) {
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

    private boolean readBoolean(String key, boolean defaultValue) {
        return read(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    private int readInteger(String key, int defaultValue) {
        return read(key).map(Integer::parseInt).orElse(defaultValue);
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

    private void validateAppUrl(String value) {
        if (value.length() > 2048) {
            throw invalidAppUrl();
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (
                scheme == null
                    || (!scheme.equalsIgnoreCase("https")
                        && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
            ) {
                throw invalidAppUrl();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidAppUrl();
        }
    }

    private ApiProblemException invalidTermsUrl() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "TERMS_URL_INVALID",
            "The terms of service URL must be a valid HTTP or HTTPS URL"
        );
    }

    private ApiProblemException invalidAppUrl() {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "APP_URL_INVALID",
            "The application URL must be a valid HTTP or HTTPS base URL"
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

    public record InvitationPolicy(
        boolean required,
        int commissionPercent,
        int generationLimit,
        boolean neverExpire
    ) {
    }

    public record MailSettings(
        String host,
        int port,
        String encryption,
        String username,
        String password,
        String fromAddress,
        boolean remindersEnabled
    ) {
        public boolean configured() {
            return host != null
                && fromAddress != null
                && (username == null || password != null);
        }
    }

    public record NodeCommunicationSettings(
        String legacyToken,
        int pullIntervalSeconds,
        int pushIntervalSeconds,
        boolean webSocketEnabled,
        String webSocketUrl
    ) {
    }
}
