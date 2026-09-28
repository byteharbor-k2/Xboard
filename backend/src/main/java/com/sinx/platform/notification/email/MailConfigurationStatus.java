package com.sinx.platform.notification.email;

import com.sinx.platform.configuration.application.PlatformConfigurationService;

/**
 * The one definition of "mail can go out" for notification callers.
 *
 * The configured mail sender refuses to build a message when SMTP is
 * incomplete and the delivery mode demands one, so callers that must not
 * even attempt a send ask here first. Development log delivery succeeds
 * without any SMTP settings, so it counts as configured; only a
 * required-but-absent configuration (delivery mode stricter than {@code
 * log} with no stored host) is a send that cannot happen.
 *
 * The property is injected with the same name the configured sender falls
 * back on, so the answer here matches what the sender would do.
 */
public final class MailConfigurationStatus {

    private final String mailDelivery;
    private final PlatformConfigurationService configuration;

    public MailConfigurationStatus(
        String mailDelivery,
        PlatformConfigurationService configuration
    ) {
        this.mailDelivery = mailDelivery;
        this.configuration = configuration;
    }

    /**
     * Whether a notification send would not outright fail: a {@code log}
     * delivery mode means yes without any stored settings, otherwise the
     * stored SMTP settings have to be complete.
     */
    public boolean canDeliver() {
        boolean logDelivery = "log".equalsIgnoreCase(mailDelivery);
        return logDelivery || configuration.mailSettings().configured();
    }
}
