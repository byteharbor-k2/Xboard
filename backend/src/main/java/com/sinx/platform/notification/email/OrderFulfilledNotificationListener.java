package com.sinx.platform.notification.email;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.configuration.application.MailTemplateService;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.order.application.OrderFulfilledEvent;

/**
 * Sends the fulfilment mail when an order opens, renews or upgrades a
 * subscription.
 *
 * The template is the catalog's {@code orderFulfilled} entry, so an
 * administrator's override applies to it exactly as to the original's own
 * templates. The mail is a courtesy, never a step of the fulfilment: it runs
 * after the order's commit, and an unconfigured SMTP host or a failed send is
 * logged and dropped - the subscription the customer paid for already
 * exists, and a mail outage must not look like a broken order.
 *
 * The subscription address is deliberately absent from the mail: the token
 * is a credential, and mailboxes are read by more parties than the panel
 * ever is. The template points the customer at their dashboard instead.
 */
@Component
public class OrderFulfilledNotificationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        OrderFulfilledNotificationListener.class
    );

    private static final DateTimeFormatter EXPIRY_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final MailTemplateService templates;
    private final ConfiguredNotificationMailSender mail;
    private final PlatformConfigurationService configuration;
    private final VerificationMailProperties mailProperties;
    private final MailConfigurationStatus mailConfiguration;

    public OrderFulfilledNotificationListener(
        MailTemplateService templates,
        ConfiguredNotificationMailSender mail,
        PlatformConfigurationService configuration,
        VerificationMailProperties mailProperties,
        @Value("${sinx.mail.delivery:log}") String mailDelivery
    ) {
        this.templates = templates;
        this.mail = mail;
        this.configuration = configuration;
        this.mailProperties = mailProperties;
        this.mailConfiguration = new MailConfigurationStatus(
            mailDelivery,
            configuration
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(OrderFulfilledEvent event) {
        // Unlike the sends below, a skipped mail is normal operation, not a
        // problem worth a stack trace: an unconfigured host is how a fresh
        // install looks.
        if (!mailConfiguration.canDeliver()) {
            LOGGER.debug(
                "Fulfilment mail for order {} skipped, mail is not configured",
                event.tradeNo()
            );
            return;
        }
        try {
            String url = configuration.appUrl()
                .orElse(mailProperties.publicBaseUrl());
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("name", escapeHtml(configuration.appName()));
            variables.put("url", escapeHtml(url));
            variables.put("plan", escapeHtml(event.planName()));
            variables.put("period", escapeHtml(event.periodLabel()));
            // An empty value lets the template's fallback speak for a
            // subscription that never expires.
            variables.put("expiry", event.expiresAt() == null
                ? ""
                : escapeHtml(EXPIRY_FORMAT.format(event.expiresAt()) + " UTC"));
            MailTemplateService.RenderedTemplate rendered =
                templates.render("orderFulfilled", variables);
            mail.sendHtml(event.email(), rendered.subject(), rendered.content());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Fulfilment mail for order {} could not be delivered to {}",
                event.tradeNo(),
                event.email(),
                exception
            );
        }
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
}
