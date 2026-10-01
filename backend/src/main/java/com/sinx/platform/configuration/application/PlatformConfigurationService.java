package com.sinx.platform.configuration.application;

import java.net.IDN;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.domain.PlatformSetting;
import com.sinx.platform.configuration.repository.PlatformSettingRepository;
import com.sinx.platform.shared.web.ApiProblemException;

@Service
@Transactional(readOnly = true)
public class PlatformConfigurationService {

    private static final String APP_NAME_KEY = "site.app_name";
    private static final String APP_URL_KEY = "site.app_url";
    private static final String SUBSCRIBE_URL_KEY = "site.subscribe_url";
    private static final String TERMS_URL_KEY = "site.tos_url";
    /**
     * The human-support contact points, deliberately plain strings: the operator
     * chooses the destination (a hosted chat page, a Telegram link, a form), so
     * any URL shape is accepted and only blank means unconfigured. This is
     * one-time configuration, so no business validation applies - the same call
     * the interval settings already made.
     */
    private static final String SUPPORT_URL_KEY = "site.support_url";
    private static final String SUPPORT_EMAIL_KEY = "site.support_email";

    /**
     * What the site is called before anyone has said otherwise. It reaches the
     * customer inside their config - as the name of the group they pick a node
     * from, and as the profile title their client shows - so it cannot be left
     * empty and substituted as a blank.
     */
    private static final String DEFAULT_APP_NAME = "SinX Cloud";
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
    /**
     * The registration safety switches from the legacy panel. Field names,
     * thresholds and windows mirror Xboard's RegisterService and
     * LoginService so a saved legacy configuration keeps meaning the same
     * thing after the rewrite.
     */
    private static final String STOP_REGISTER_KEY = "safe.stop_register";
    private static final String SITE_STOP_REGISTER_KEY = "site.stop_register";
    private static final String REGISTER_IP_LIMIT_ENABLED_KEY =
        "safe.register_limit_by_ip_enable";
    private static final String REGISTER_IP_LIMIT_COUNT_KEY =
        "safe.register_limit_count";
    private static final String REGISTER_IP_LIMIT_EXPIRE_KEY =
        "safe.register_limit_expire";
    private static final String EMAIL_GMAIL_LIMIT_KEY =
        "safe.email_gmail_limit_enable";
    private static final String PASSWORD_LIMIT_ENABLED_KEY =
        "safe.password_limit_enable";
    private static final String PASSWORD_LIMIT_COUNT_KEY =
        "safe.password_limit_count";
    private static final String PASSWORD_LIMIT_EXPIRE_KEY =
        "safe.password_limit_expire";
    /**
     * Where a login is locked after too many wrong passwords and for how
     * long, matching the legacy LoginService defaults
     * (password_limit_count = 5, password_limit_expire = 60 minutes).
     */
    static final int DEFAULT_PASSWORD_LIMIT_COUNT = 5;
    static final int DEFAULT_PASSWORD_LIMIT_EXPIRE_MINUTES = 60;
    private static final String INVITE_REQUIRED_KEY = "invite.invite_force";
    private static final String INVITE_COMMISSION_KEY =
        "invite.invite_commission";
    private static final String INVITE_GENERATION_LIMIT_KEY =
        "invite.invite_gen_limit";
    private static final String INVITE_NEVER_EXPIRE_KEY =
        "invite.invite_never_expire";
    private static final String EMAIL_DELIVERY_KEY = "email.email_delivery";
    /**
     * How outbound mail is transported: written to the log for development
     * ("log") or through the stored SMTP settings ("smtp"). When no value
     * was saved the deployment property stays authoritative, so a local box
     * keeps logging mails and a test context whose environment says "smtp"
     * keeps demanding settings.
     */
    private static final String EMAIL_DELIVERY_LOG = "log";
    private static final String EMAIL_DELIVERY_SMTP = "smtp";
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
    private static final Pattern SERVER_TOKEN_PATTERN = Pattern.compile(
        "^[0-9a-f]{64}$"
    );
    /**
     * The domains Gmail itself treats as one mailbox, so an alias on these is
     * a duplicate account on this site too.
     */
    private static final Set<String> GMAIL_ALIAS_DOMAINS = Set.of(
        "gmail.com",
        "googlemail.com"
    );

    private final PlatformSettingRepository settings;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final SubscriptionTemplates templates;
    private final String mailDeliveryProperty;

    public PlatformConfigurationService(
        PlatformSettingRepository settings,
        Clock clock,
        ApplicationEventPublisher eventPublisher,
        SubscriptionTemplates templates,
        @Value("${sinx.mail.delivery:log}") String mailDeliveryProperty
    ) {
        this.settings = settings;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.templates = templates;
        this.mailDeliveryProperty = mailDeliveryProperty;
    }

    public Map<String, Object> sectionSettings(String section) {
        return switch (section) {
            case "site" -> Map.of(
                "app_name", appName(),
                "app_url", appUrl().orElse(""),
                // Every saved entry point, not just the first: an editor that
                // read back a shortened list would silently overwrite the rest
                // on its next save.
                "subscribe_url", String.join(",", subscribeUrls()),
                "tos_url", termsUrl().orElse(""),
                "support_url", supportUrl().orElse(""),
                "support_email", supportEmail().orElse(""),
                "stop_register",
                stopRegisterPolicy().stopped()
            );
            case "safe" -> {
                EmailDomainPolicy policy = emailDomainPolicy();
                TurnstilePolicy turnstile = turnstilePolicy();
                yield Map.ofEntries(
                    Map.entry(
                        "email_verify",
                        emailVerificationRequired()
                    ),
                    Map.entry(
                        "stop_register",
                        stopRegisterPolicy().stopped()
                    ),
                    Map.entry(
                        "email_gmail_limit_enable",
                        gmailAliasPolicy().enabled()
                    ),
                    Map.entry(
                        "email_whitelist_enable",
                        policy.enabled()
                    ),
                    Map.entry(
                        "email_whitelist_suffix",
                        policy.domains()
                    ),
                    Map.entry(
                        "captcha_enable",
                        turnstile.enabled()
                    ),
                    Map.entry("captcha_type", "turnstile"),
                    Map.entry(
                        "turnstile_site_key",
                        turnstile.siteKey() == null ? "" : turnstile.siteKey()
                    ),
                    Map.entry("turnstile_secret_key", ""),
                    Map.entry(
                        "register_limit_by_ip_enable",
                        registrationIpLimit().enabled()
                    ),
                    Map.entry(
                        "register_limit_count",
                        registrationIpLimit().limitCount()
                    ),
                    Map.entry(
                        "register_limit_expire",
                        registrationIpLimit().expireMinutes()
                    ),
                    Map.entry(
                        "password_limit_enable",
                        loginAttemptPolicy().enabled()
                    ),
                    Map.entry(
                        "password_limit_count",
                        loginAttemptPolicy().maxFailures()
                    ),
                    Map.entry(
                        "password_limit_expire",
                        loginAttemptPolicy().lockMinutes()
                    )
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
                    "email_delivery",
                    mailDelivery(),
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
            case "subscribe_template" -> {
                // The effective template, not the stored one: an administrator
                // editing this section is looking at what is being served, and
                // a blank box would otherwise hide the default they are
                // actually shipping.
                Map<String, Object> templateSettings = new LinkedHashMap<>();
                for (SubscriptionTemplates.Kind kind
                    : SubscriptionTemplates.Kind.values()) {
                    templateSettings.put(
                        kind.settingKey(),
                        subscriptionTemplate(kind)
                    );
                }
                yield Map.copyOf(templateSettings);
            }
            default -> throw unsupportedSection();
        };
    }

    /**
     * The template a renderer should use, whether or not one was stored.
     */
    public String subscriptionTemplate(SubscriptionTemplates.Kind kind) {
        return templates.effective(kind, read(kind.settingKey()).orElse(null));
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
        if ("subscribe_template".equals(section)) {
            saveSubscriptionTemplate(entry.getKey(), entry.getValue());
            return;
        }
        NodeCommunicationSettings before = "server".equals(section)
            ? nodeCommunicationSettings()
            : null;
        switch (section + "." + entry.getKey()) {
            case SITE_STOP_REGISTER_KEY -> saveBoolean(STOP_REGISTER_KEY, entry.getValue());
            case STOP_REGISTER_KEY -> saveBoolean(STOP_REGISTER_KEY, entry.getValue());
            case APP_NAME_KEY -> saveAppName(entry.getValue());
            case APP_URL_KEY -> saveAppUrl(entry.getValue());
            case SUBSCRIBE_URL_KEY -> saveSubscribeUrls(entry.getValue());
            case TERMS_URL_KEY -> saveTermsUrl(entry.getValue());
            case SUPPORT_URL_KEY ->
                saveSupportContact(SUPPORT_URL_KEY, entry.getValue());
            case SUPPORT_EMAIL_KEY ->
                saveSupportContact(SUPPORT_EMAIL_KEY, entry.getValue());
            case EMAIL_ALLOWLIST_ENABLED_KEY ->
                saveEmailAllowlistEnabled(entry.getValue());
            case EMAIL_ALLOWLIST_SUFFIXES_KEY ->
                saveEmailAllowlistDomains(entry.getValue());
            case EMAIL_VERIFICATION_KEY ->
                saveBoolean(EMAIL_VERIFICATION_KEY, entry.getValue());
            case EMAIL_GMAIL_LIMIT_KEY ->
                saveBoolean(EMAIL_GMAIL_LIMIT_KEY, entry.getValue());
            case REGISTER_IP_LIMIT_ENABLED_KEY ->
                saveBoolean(REGISTER_IP_LIMIT_ENABLED_KEY, entry.getValue());
            case REGISTER_IP_LIMIT_COUNT_KEY ->
                saveInteger(REGISTER_IP_LIMIT_COUNT_KEY, entry.getValue());
            case REGISTER_IP_LIMIT_EXPIRE_KEY ->
                saveInteger(REGISTER_IP_LIMIT_EXPIRE_KEY, entry.getValue());
            case PASSWORD_LIMIT_ENABLED_KEY ->
                saveBoolean(PASSWORD_LIMIT_ENABLED_KEY, entry.getValue());
            case PASSWORD_LIMIT_COUNT_KEY ->
                saveInteger(PASSWORD_LIMIT_COUNT_KEY, entry.getValue());
            case PASSWORD_LIMIT_EXPIRE_KEY ->
                saveInteger(PASSWORD_LIMIT_EXPIRE_KEY, entry.getValue());
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
                saveInteger(INVITE_COMMISSION_KEY, entry.getValue());
            case INVITE_GENERATION_LIMIT_KEY ->
                saveInteger(INVITE_GENERATION_LIMIT_KEY, entry.getValue());
            case INVITE_NEVER_EXPIRE_KEY ->
                saveBoolean(INVITE_NEVER_EXPIRE_KEY, entry.getValue());
            case EMAIL_DELIVERY_KEY -> saveMailDelivery(entry.getValue());
            case EMAIL_HOST_KEY -> saveMailHost(entry.getValue());
            case EMAIL_PORT_KEY ->
                saveInteger(EMAIL_PORT_KEY, entry.getValue());
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
                saveInteger(SERVER_PULL_INTERVAL_KEY, entry.getValue());
            case SERVER_PUSH_INTERVAL_KEY ->
                saveInteger(SERVER_PUSH_INTERVAL_KEY, entry.getValue());
            case SERVER_WS_ENABLED_KEY ->
                saveBoolean(SERVER_WS_ENABLED_KEY, entry.getValue());
            case SERVER_WS_URL_KEY -> saveWebSocketUrl(entry.getValue());
            default -> throw unsupportedSetting();
        }
        if (before != null) {
            publishNodeCommunicationChange(before, nodeCommunicationSettings());
        }
    }

    public String appName() {
        return read(APP_NAME_KEY)
            .filter(value -> !value.isBlank())
            .orElse(DEFAULT_APP_NAME);
    }

    /**
     * Addresses the subscription link may be built on. A list because a site
     * that fronts several entry points wants one of them to be the one the
     * customer copies, and because a link handed out once has to keep working
     * from wherever it was handed out.
     */
    public List<String> subscribeUrls() {
        return read(SUBSCRIBE_URL_KEY)
            .stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    public Optional<String> subscribeUrl() {
        return subscribeUrls().stream().findFirst();
    }

    public Optional<String> termsUrl() {
        return read(TERMS_URL_KEY).filter(value -> !value.isBlank());
    }

    public Optional<String> appUrl() {
        return read(APP_URL_KEY).filter(value -> !value.isBlank());
    }

    public EmailDomainPolicy emailDomainPolicy() {
        boolean enabled = readBoolean(EMAIL_ALLOWLIST_ENABLED_KEY, false);
        List<String> domains = read(EMAIL_ALLOWLIST_SUFFIXES_KEY)
            .stream()
            .flatMap(String::lines)
            .filter(value -> !value.isBlank())
            .toList();
        return new EmailDomainPolicy(enabled, domains);
    }

    public boolean emailVerificationRequired() {
        return readBoolean(EMAIL_VERIFICATION_KEY, true);
    }

    /**
     * The whole safety-switch surface at once. The registration admission
     * points read a snapshot of it, so a register request is judged by one
     * consistent set of values even while an administrator is editing.
     */
    public SafetySwitchPolicy safetyPolicy() {
        StopRegisterPolicy stopRegister = stopRegisterPolicy();
        RegistrationIpLimitPolicy ipLimit = registrationIpLimit();
        GmailAliasPolicy gmail = gmailAliasPolicy();
        LoginAttemptPolicy login = loginAttemptPolicy();
        return new SafetySwitchPolicy(
            stopRegister.stopped(),
            ipLimit.enabled(),
            ipLimit.limitCount(),
            ipLimit.expireMinutes(),
            gmail.enabled(),
            login.enabled(),
            login.maxFailures(),
            login.lockMinutes()
        );
    }

    /**
     * The legacy switch maps a truthy saved value to the effective switch
     * (ConfigSave's boolean caster parses "0"/"1"/"true"), so the section read
     * returns a real boolean either way.
     */
    public StopRegisterPolicy stopRegisterPolicy() {
        return new StopRegisterPolicy(readBoolean(STOP_REGISTER_KEY, false));
    }

    public RegistrationIpLimitPolicy registrationIpLimit() {
        return new RegistrationIpLimitPolicy(
            readBoolean(REGISTER_IP_LIMIT_ENABLED_KEY, false),
            readInteger(REGISTER_IP_LIMIT_COUNT_KEY, 3),
            readInteger(REGISTER_IP_LIMIT_EXPIRE_KEY, 60)
        );
    }

    public GmailAliasPolicy gmailAliasPolicy() {
        return new GmailAliasPolicy(
            readBoolean(EMAIL_GMAIL_LIMIT_KEY, false)
        );
    }

    public LoginAttemptPolicy loginAttemptPolicy() {
        return new LoginAttemptPolicy(
            readBoolean(PASSWORD_LIMIT_ENABLED_KEY, true),
            readInteger(PASSWORD_LIMIT_COUNT_KEY, DEFAULT_PASSWORD_LIMIT_COUNT),
            readInteger(PASSWORD_LIMIT_EXPIRE_KEY, DEFAULT_PASSWORD_LIMIT_EXPIRE_MINUTES)
        );
    }

    /**
     * The legacy alias guard covers only the alias-capable Gmail domains:
     * gmail.com and googlemail.com. It refuses a plus-tagged local part there
     * (dots keep passing, unlike the original check) and leaves every other
     * domain unaffected.
     */
    public void assertGmailAliasAllowed(String email) {
        if (!gmailAliasPolicy().enabled()) {
            return;
        }
        int separator = email.lastIndexOf('@');
        if (separator < 0) {
            return;
        }
        String domain = email.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!GMAIL_ALIAS_DOMAINS.contains(domain)) {
            return;
        }
        String localPart = email.substring(0, separator);
        if (localPart.indexOf('+') >= 0) {
            throw new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "GMAIL_ALIAS_NOT_SUPPORTED",
                "Gmail alias addresses cannot be used for registration"
            );
        }
    }

    public TurnstilePolicy turnstilePolicy() {
        boolean enabled = readBoolean(CAPTCHA_ENABLED_KEY, false);
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

    /**
     * The effective mail transport: a saved email_delivery value wins, and
     * otherwise the deployment property (set by the environment for tests
     * and non-default deploys) decides. A stored value outside the two
     * known modes is read as if it were never saved, so a corrupted row
     * cannot silently turn delivery off.
     */
    public String mailDelivery() {
        return read(EMAIL_DELIVERY_KEY)
            .filter(value -> EMAIL_DELIVERY_LOG.equals(value)
                || EMAIL_DELIVERY_SMTP.equals(value))
            .orElse(mailDeliveryProperty);
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

    /**
     * Where a customer reaches a human: an external chat page, a Telegram or
     * Matrix link, a form, or an email address. Raw as saved on purpose - any
     * destination shape the operator chooses stays intact, and blank is how
     * the contact point is withdrawn.
     */
    public Optional<String> supportUrl() {
        return read(SUPPORT_URL_KEY).filter(value -> !value.isBlank());
    }

    public Optional<String> supportEmail() {
        return read(SUPPORT_EMAIL_KEY).filter(value -> !value.isBlank());
    }

    private void saveSupportContact(String key, Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            settings.deleteById(key);
            return;
        }
        store(key, normalized);
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

    private void saveAppName(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            settings.deleteById(APP_NAME_KEY);
            return;
        }
        if (normalized.length() > 120) {
            throw invalidSettingValue();
        }
        store(APP_NAME_KEY, normalized);
    }

    private void saveSubscribeUrls(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        List<String> candidates = Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(entry -> !entry.isBlank())
            .toList();
        if (candidates.isEmpty()) {
            settings.deleteById(SUBSCRIBE_URL_KEY);
            return;
        }
        if (candidates.size() > 10) {
            throw invalidSettingValue();
        }
        List<String> urls = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            urls.add(validateSubscribeBase(candidate));
        }
        store(SUBSCRIBE_URL_KEY, String.join(",", urls));
    }

    /**
     * A base a subscription path gets appended to, so it has to be a scheme and
     * a host and nothing else - a query or a fragment on it would end up in the
     * middle of the link.
     */
    private String validateSubscribeBase(String candidate) {
        String normalized = candidate;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() > 2048) {
            throw invalidSettingValue();
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (
                scheme == null
                    || (!scheme.equalsIgnoreCase("https")
                        && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
            ) {
                throw invalidSettingValue();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidSettingValue();
        }
        return normalized;
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
        if (!(rawValue instanceof List<?> values)) {
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

    private void saveSubscriptionTemplate(String key, Object rawValue) {
        SubscriptionTemplates.Kind kind = SubscriptionTemplates.Kind
            .bySettingKey(key);
        if (kind == null) {
            throw unsupportedSetting();
        }
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        if (value.isBlank()) {
            // Blank is how an administrator restores the bundled default, so
            // there is nothing to validate and nothing to keep.
            settings.deleteById(kind.settingKey());
            return;
        }
        templates.validate(kind, value);
        store(kind.settingKey(), value);
    }

    private void saveBoolean(String key, Object rawValue) {
        if (!(rawValue instanceof Boolean enabled)) {
            throw invalidSettingValue();
        }
        store(key, Boolean.toString(enabled));
    }

    private void saveCaptchaType(Object rawValue) {
        if (!(rawValue instanceof String type)) {
            throw invalidSettingValue();
        }
        // Stored as given: the read side already treats a type it does not
        // know as "no captcha provider", so a junk value cannot break reads.
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

    private void saveInteger(String key, Object rawValue) {
        // One-time configuration is the operator's call: any number the
        // operator wrote is stored as written, with no business bounds. Only
        // a value of the wrong shape - one that cannot be read back as an
        // integer at all - is the flat invalid-setting answer.
        try {
            int value = Integer.parseInt(
                String.valueOf(rawValue).trim()
            );
            store(key, Integer.toString(value));
        } catch (NumberFormatException exception) {
            throw invalidSettingValue();
        }
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

    private void saveMailDelivery(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        // Stored as given: mailDelivery() only honors the two known modes
        // and ignores everything else, so a corrupted row is read as the
        // deployment property instead of failing reads.
        store(EMAIL_DELIVERY_KEY, value);
    }

    private void saveMailEncryption(Object rawValue) {
        if (!(rawValue instanceof String value)) {
            throw invalidSettingValue();
        }
        // Stored as given: the mailSettings read falls back to "ssl" when
        // the saved value is unusable, so no write-side whitelist is kept.
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
        if (!SERVER_TOKEN_PATTERN.matcher(normalized).matches()) {
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

    /**
     * A saved switch is read from its truthy representations - the legacy
     * caster accepted "0"/"1"/"true" - and anything else is junk a corrupt
     * row could carry: it reads as the default rather than failing the read.
     */
    private boolean readBoolean(String key, boolean defaultValue) {
        String value = read(key).map(String::trim).orElse(null);
        if (value == null) {
            return defaultValue;
        }
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> defaultValue;
        };
    }

    /**
     * A saved number that does not parse is kept out of the read path the
     * same way mailDelivery keeps an unknown mode out: the default stands
     * in, so a corrupted row never turns a read into a failure.
     */
    private int readInteger(String key, int defaultValue) {
        try {
            return read(key)
                .map(value -> Integer.parseInt(value.trim()))
                .orElse(defaultValue);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
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

    private void publishNodeCommunicationChange(
        NodeCommunicationSettings before,
        NodeCommunicationSettings after
    ) {
        boolean legacyTokenChanged = !Objects.equals(
            before.legacyToken(),
            after.legacyToken()
        );
        boolean webSocketDisabled = before.webSocketEnabled()
            && !after.webSocketEnabled();
        if (legacyTokenChanged || webSocketDisabled) {
            eventPublisher.publishEvent(new NodeCommunicationSettingsChangedEvent(
                legacyTokenChanged,
                webSocketDisabled
            ));
        }
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

    public record StopRegisterPolicy(
        boolean stopped
    ) {
    }

    public record RegistrationIpLimitPolicy(
        boolean enabled,
        int limitCount,
        int expireMinutes
    ) {
    }

    public record GmailAliasPolicy(
        boolean enabled
    ) {
    }

    public record LoginAttemptPolicy(
        boolean enabled,
        int maxFailures,
        int lockMinutes
    ) {
    }

    public record SafetySwitchPolicy(
        boolean registrationStopped,
        boolean registerIpLimitEnabled,
        int registerIpLimitCount,
        int registerIpLimitExpireMinutes,
        boolean gmailAliasesBlocked,
        boolean loginLimitEnabled,
        int loginMaxFailures,
        int loginLockMinutes
    ) {
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
