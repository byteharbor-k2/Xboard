package com.sinx.platform.subscription.client;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

/**
 * Everything a renderer needs that is not the node list itself.
 *
 * @param kind        which bundled template family is in play; null for the
 *                    generic URI renderer, which is built in code and has no
 *                    template at all
 * @param template    the template to render into - the stored one when an
 *                    admin has edited it, the bundled one otherwise
 * @param appName     the site name, substituted into Clash's {@code $app_name}
 * @param appUrl      the site address, handed to Clash so the client can offer
 *                    a link back to it
 * @param requestHost the Host the client asked on, which Clash is told to
 *                    route DIRECT so the subscription itself never goes
 *                    through a node
 */
public record ClientConfigRequest(
    SubscriptionTemplates.Kind kind,
    String template,
    String appName,
    String appUrl,
    String requestHost
) {
}
