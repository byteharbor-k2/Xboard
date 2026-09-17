package com.sinx.platform.subscription.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.subscription.config.SubscriptionProperties;

/**
 * The address a customer copies into their client.
 *
 * The panel advertises one entry point and it is a path, {@code /sub/{token}},
 * on whichever hostname the site is served from. Two things decide that
 * hostname, in order: the subscription address an administrator set in the site
 * settings, because that is the one that reflects how this particular
 * deployment is reached; and failing that the configured public base URL, so a
 * fresh install that has never had the setting touched still hands out a link
 * that works.
 *
 * A site may list more than one subscription address - an operator whose
 * customers reach them over several entry points wants to name them all - and
 * only the first is used. Handing out a link at random from that list is what
 * the original does, and it is left out here on purpose: a customer who copied
 * a link and came back to copy it again must get the same one, or every visit
 * looks like the subscription moved.
 */
@Service
public class SubscriptionLinkService {

    private static final String SUBSCRIPTION_PATH = "/sub/";

    private final PlatformConfigurationService configuration;
    private final SubscriptionProperties properties;

    public SubscriptionLinkService(
        PlatformConfigurationService configuration,
        SubscriptionProperties properties
    ) {
        this.configuration = configuration;
        this.properties = properties;
    }

    /**
     * The subscription address this account's client should be given.
     *
     * It carries the account's credential, so it is returned only to the account
     * itself and never written to a log.
     */
    public String subscriptionUrl(UserAccount user) {
        return base() + SUBSCRIPTION_PATH + user.getSubscriptionToken();
    }

    private String base() {
        List<String> configured = configuration.subscribeUrls();
        if (!configured.isEmpty()) {
            return trimTrailingSlash(configured.getFirst());
        }
        return trimTrailingSlash(properties.publicBaseUrl());
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
