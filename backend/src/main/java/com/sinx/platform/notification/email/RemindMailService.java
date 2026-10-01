package com.sinx.platform.notification.email;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.application.MailTemplateService;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/**
 * The daily renewal and traffic reminder mails, the original panel's
 * {@code send:remindMail} command.
 *
 * Two mails exist, both opt-out per account ({@code remind_expire} and
 * {@code remind_traffic} default to on) and both gated by the administrator's
 * {@code email.remind_mail_enable}. The original's own thresholds are kept:
 * an expiry reminder goes to a still-running subscription that ends within
 * the next 24 hours, and a traffic reminder goes to an account that has used
 * 80% of its allowance - but not one already at 100%, where the reminder
 * would be a lie the exhaustion state already tells elsewhere. Both send the
 * catalog templates {@code remindExpire} and {@code remindTraffic}, so an
 * administrator's override applies to them as to every other mail.
 *
 * The run is a courtesy, never a step of anything: an unconfigured mail host
 * skips the whole run with one log line, and a failure for one account is
 * logged and the batch carries on, the way the original counted
 * {@code errors} and kept walking its chunks.
 */
@Service
public class RemindMailService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        RemindMailService.class
    );

    /** How long before an expiry the renewal reminder may fire. */
    private static final Duration EXPIRY_WINDOW = Duration.ofHours(24);

    private final SubscriptionEntitlementRepository entitlements;
    private final PlatformConfigurationService configuration;
    private final MailTemplateService templates;
    private final ConfiguredNotificationMailSender mail;
    private final VerificationMailProperties mailProperties;
    private final MailConfigurationStatus mailConfiguration;
    private final Clock clock;

    public RemindMailService(
        SubscriptionEntitlementRepository entitlements,
        PlatformConfigurationService configuration,
        MailTemplateService templates,
        ConfiguredNotificationMailSender mail,
        VerificationMailProperties mailProperties,
        Clock clock
    ) {
        this.entitlements = entitlements;
        this.configuration = configuration;
        this.templates = templates;
        this.mail = mail;
        this.mailProperties = mailProperties;
        this.mailConfiguration = new MailConfigurationStatus(configuration);
        this.clock = clock;
    }

    /**
     * Judges every candidate account and sends what is due.
     *
     * Both reminders can fire for one account in one run, and a send that
     * fails stops that account's remaining sends - the original's per-user
     * {@code try} wrapped both - but never the accounts behind it.
     */
    @Transactional(readOnly = true)
    public ReminderRun sendDueReminders() {
        // Both gates are read once up front: a disabled feature or a host
        // nothing can be delivered through is normal operation, not a
        // problem worth a stack trace, so it is logged once and quietly.
        if (!configuration.mailSettings().remindersEnabled()) {
            LOGGER.debug("Reminder mail run skipped, reminders are disabled");
            return new ReminderRun(0, 0, 0);
        }
        if (!mailConfiguration.canDeliver()) {
            LOGGER.debug("Reminder mail run skipped, mail is not configured");
            return new ReminderRun(0, 0, 0);
        }

        Instant now = Instant.now(clock);
        List<SubscriptionEntitlement> candidates =
            entitlements.findReminderCandidates(
                UserStatus.ACTIVE,
                now,
                now.plus(EXPIRY_WINDOW)
            );
        if (candidates.isEmpty()) {
            return new ReminderRun(0, 0, 0);
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("name", escapeHtml(configuration.appName()));
        variables.put(
            "url",
            escapeHtml(configuration.appUrl()
                .orElse(mailProperties.publicBaseUrl()))
        );

        int expireReminders = 0;
        int trafficReminders = 0;
        int failures = 0;
        for (SubscriptionEntitlement entitlement : candidates) {
            UserAccount user = entitlement.getUser();
            try {
                if (user.isRemindExpire()
                    && expireReminderDue(entitlement, now)) {
                    MailTemplateService.RenderedTemplate rendered =
                        templates.render("remindExpire", variables);
                    mail.sendHtml(
                        user.getEmail(),
                        rendered.subject(),
                        rendered.content()
                    );
                    expireReminders++;
                }
                if (user.isRemindTraffic()
                    && trafficReminderDue(entitlement)) {
                    MailTemplateService.RenderedTemplate rendered =
                        templates.render("remindTraffic", variables);
                    mail.sendHtml(
                        user.getEmail(),
                        rendered.subject(),
                        rendered.content()
                    );
                    trafficReminders++;
                }
            } catch (RuntimeException exception) {
                // One account that cannot be mailed must not stop the sweep
                // from mailing the rest.
                failures++;
                LOGGER.warn(
                    "Reminder mail could not be delivered to {}",
                    user.getEmail(),
                    exception
                );
            }
        }
        return new ReminderRun(expireReminders, trafficReminders, failures);
    }

    /**
     * Whether the expiry reminder is due: the subscription is still running
     * - not cancelled and not already expired, the states the entitlement's
     * own {@code stateAt} rules out; an exhausted one still runs, and
     * renewing it is still the advice that matters - and its expiry falls
     * inside the next day, the original's
     * {@code expired_at - 86400 < now < expired_at}.
     */
    private boolean expireReminderDue(
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        Instant expiresAt = entitlement.getExpiresAt();
        return expiresAt != null
            && entitlement.stateAt(now) != EntitlementState.CANCELED
            && entitlement.stateAt(now) != EntitlementState.EXPIRED
            && expiresAt.isAfter(now)
            && expiresAt.isBefore(now.plus(EXPIRY_WINDOW));
    }

    /**
     * Whether the traffic reminder is due: at least 80% of the allowance is
     * used but it is not exhausted yet, the original's warn window - at 100%
     * the subscription has already stopped and the reminder would only
     * confuse. The comparison reuses the entity's saturated
     * {@code usedBytes()}, the same figure the exhaustion logic judges.
     */
    private boolean trafficReminderDue(SubscriptionEntitlement entitlement) {
        long transferLimitBytes = entitlement.getTransferLimitBytes();
        if (transferLimitBytes <= 0) {
            return false;
        }
        long usedBytes = entitlement.usedBytes();
        return usedBytes < transferLimitBytes
            && usedBytes >= transferLimitBytes - transferLimitBytes / 5;
    }

    /**
     * Substituted values reach admin-authored HTML, so they are escaped the
     * way the template service escapes its own test values.
     */
    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /** What one sweep did, the shape the scheduled job logs. */
    public record ReminderRun(
        long expireReminders,
        long trafficReminders,
        long failures
    ) {
    }
}
