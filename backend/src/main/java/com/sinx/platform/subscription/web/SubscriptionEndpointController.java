package com.sinx.platform.subscription.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.subscription.application.SubscriptionLinkService;
import com.sinx.platform.subscription.client.ClientConfigService;
import com.sinx.platform.subscription.client.RenderedConfig;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/**
 * The address a client subscribes to.
 *
 * This is the one endpoint in the panel that is reached with no session at all:
 * the credential is the URL itself, and a client that has it can fetch its
 * config as often as it likes. Everything below follows from that.
 *
 * <ul>
 *   <li>The token is never echoed, logged or put in an error detail. A response
 *       that quotes it turns any proxy log into a list of working subscriptions,
 *       and because the path is the credential, "which input was wrong" is not
 *       something the caller has a right to know.</li>
 *   <li>An unknown token is a 404 and a rotated token is the same 404. Telling
 *       them apart would let someone confirm that a guessed token had once been
 *       real.</li>
 *   <li>An account that cannot use the service gets 403 with an empty body, not
 *       an error document: this is fetched by software, and the original client
 *       middleware does the same. The node control plane only pushes active
 *       accounts to nodes, so a config handed to a suspended or exhausted
 *       account is a config with nothing working in it.</li>
 *   <li>Nothing here is cached upstream. A client is told to fetch
 *       {@code profile-update-interval} hours apart, and a cache that outlives a
 *       rotation would keep handing out a credential its owner has retired.</li>
 * </ul>
 */
@RestController
public class SubscriptionEndpointController {

    private static final String SUBSCRIPTION_PATH = "/sub/{token}";

    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final ClientConfigService configs;
    private final SubscriptionLinkService links;
    private final Clock clock;

    public SubscriptionEndpointController(
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        ClientConfigService configs,
        SubscriptionLinkService links,
        Clock clock
    ) {
        this.users = users;
        this.entitlements = entitlements;
        this.configs = configs;
        this.links = links;
        this.clock = clock;
    }

    @GetMapping(SUBSCRIPTION_PATH)
    public ResponseEntity<byte[]> subscribe(
        @PathVariable String token,
        @RequestParam(required = false) String flag,
        @RequestParam(required = false) String types,
        @RequestParam(required = false) String filter,
        @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
        @RequestHeader(value = HttpHeaders.HOST, required = false) String host
    ) {
        UserAccount user = users.findBySubscriptionToken(token).orElse(null);
        if (user == null) {
            return unavailable(404);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return unavailable(403);
        }
        SubscriptionEntitlement entitlement = entitlements
            .findByUserId(user.getId())
            .orElse(null);
        if (
            entitlement == null
                || entitlement.stateAt(Instant.now(clock)) != EntitlementState.ACTIVE
        ) {
            return unavailable(403);
        }

        RenderedConfig rendered = configs.render(
            entitlement,
            flag != null ? flag : userAgent,
            types,
            filter,
            originOf(host),
            links.subscriptionUrl(user)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(rendered.contentType()));
        headers.setCacheControl("no-store");
        rendered.headers().forEach(headers::set);
        return new ResponseEntity<>(
            rendered.body().getBytes(StandardCharsets.UTF_8),
            headers,
            200
        );
    }

    /**
     * A refusal, with nothing in it.
     *
     * The body is deliberately empty and the type deliberately plain: a client
     * that has been given a stale link should show its own message, and anything
     * this panel wrote here would end up rendered as a node list by software
     * that has no idea it received an error.
     */
    private static ResponseEntity<byte[]> unavailable(int status) {
        return ResponseEntity.status(status)
            .contentType(MediaType.TEXT_PLAIN)
            .build();
    }

    /**
     * The address this request arrived on, without its port.
     *
     * Only the Clash templates use it, to route the subscription's own host
     * direct; a port belongs to a rule's target, not to a domain match, and the
     * original reads the host the same way.
     */
    private static String originOf(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.startsWith("[")) {
            int closing = host.indexOf(']');
            return closing < 0 ? host : host.substring(0, closing + 1);
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }
}
