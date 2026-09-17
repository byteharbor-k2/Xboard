package com.sinx.platform.subscription.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.subscription.application.SubscriptionNodeSelector;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

/**
 * Turns an account's entitlement into the config its client asked for.
 *
 * The request arrives as a token and a few query parameters, and this is where
 * those become a format, a template and a list of nodes. Everything that can
 * make a subscription wrong lives here: picking the wrong format, serving the
 * wrong template, handing the client a node it cannot connect to.
 *
 * The output is deterministic. The same account asking the same way twice gets
 * the same bytes, which is what lets a client tell "nothing changed" from "the
 * subscription moved" without re-reading the whole config.
 */
@Service
public class ClientConfigService {

    private final ClientFormatResolver formats;
    private final SubscriptionNodeSelector selector;
    private final PlatformConfigurationService configuration;

    public ClientConfigService(
        ClientFormatResolver formats,
        SubscriptionNodeSelector selector,
        PlatformConfigurationService configuration
    ) {
        this.formats = formats;
        this.selector = selector;
        this.configuration = configuration;
    }

    /**
     * The config this request asks for.
     *
     * @param flag   the format the client named, or its User-Agent when it named
     *               none - the caller decides which, because only it knows
     *               whether a flag parameter was actually present
     * @param types  a comma or pipe separated protocol whitelist; blank means
     *               every protocol
     * @param filter a keyword the node's name or one of its tags must contain;
     *               blank means no filtering
     * @param host   the address this subscription was fetched from, so the
     *               client can be told to reach it without a proxy
     */
    @Transactional(readOnly = true)
    public RenderedConfig render(
        SubscriptionEntitlement entitlement,
        String flag,
        String types,
        String filter,
        String host
    ) {
        ClientFormat format = formats.resolve(flag);
        List<String> wanted = parseTypes(types);
        List<String> keywords = parseFilter(filter);

        List<NodeClientView> nodes = selector
            .select(entitlement)
            .stream()
            .filter(node -> format.accepts(node.protocol()))
            .filter(node -> wanted.isEmpty() || wanted.contains(node.protocol()))
            .filter(node -> matches(node, keywords))
            .toList();

        String template = format.templateKind() == null
            ? null
            : configuration.subscriptionTemplate(format.templateKind());
        RenderedConfig rendered = format.renderer().render(
            new ClientConfigRequest(
                format.templateKind(),
                template,
                configuration.appName(),
                configuration.appUrl().orElse(""),
                host
            ),
            nodes
        );

        Map<String, String> headers = new LinkedHashMap<>(rendered.headers());
        headers.put("subscription-userinfo", userInfo(entitlement));
        return new RenderedConfig(rendered.body(), rendered.contentType(), headers);
    }

    /**
     * The traffic report a client shows next to its node list.
     *
     * Raw bytes and a raw unix timestamp, exactly as the original writes it -
     * the header is read by clients that have been parsing this panel's output
     * for years, and a friendlier rendering would be a different header. An
     * account with no expiry sends an empty value rather than a zero, because
     * zero is an expiry date a client would display as 1970.
     */
    private static String userInfo(SubscriptionEntitlement entitlement) {
        return "upload=" + entitlement.getUploadedBytes()
            + "; download=" + entitlement.getDownloadedBytes()
            + "; total=" + entitlement.getTransferLimitBytes()
            + "; expire=" + (
                entitlement.getExpiresAt() == null
                    ? ""
                    : entitlement.getExpiresAt().getEpochSecond()
            );
    }

    /**
     * The protocols this request will accept.
     *
     * A blank list, or the exact literal {@code all}, means everything; anything
     * else is narrowed to it. The delimiters are a vertical bar, a comma and a
     * fullwidth vertical bar, and a name that is none of the panel's protocols
     * simply never matches a node.
     */
    private static List<String> parseTypes(String types) {
        if (types == null || types.isBlank() || "all".equals(types)) {
            return List.of();
        }
        return split(types);
    }

    /**
     * The keywords a node must match to appear.
     *
     * Long input is discarded rather than searched for. The original caps this
     * at twenty characters and this is a real limit, not a tidiness rule: the
     * cap is what stops the parameter being used to walk a node list the account
     * is not being shown.
     */
    private static List<String> parseFilter(String filter) {
        if (
            filter == null
                || filter.isBlank()
                || filter.codePointCount(0, filter.length()) > 20
        ) {
            return List.of();
        }
        return split(filter);
    }

    private static List<String> split(String value) {
        return List.of(value.split("[|,\\uFF5C]+"))
            .stream()
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
    }

    /**
     * Whether a node matches any of the keywords.
     *
     * Case-insensitive against the name, exact against the tags - the same two
     * tests the original applies, and the asymmetry is deliberate: a tag is a
     * label an administrator chose, so asking for it exactly is reasonable,
     * while a node name is a display string that may be capitalised any way.
     */
    private static boolean matches(NodeClientView node, List<String> keywords) {
        if (keywords.isEmpty()) {
            return true;
        }
        String name = node.name().toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (name.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (node.tags().contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
