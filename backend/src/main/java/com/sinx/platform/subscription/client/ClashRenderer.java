package com.sinx.platform.subscription.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Clash family, rendered from an administrator's template.
 *
 * One renderer serves every flag because the template mechanics are identical
 * and only the proxy entries differ; the original splits them across three
 * classes and duplicates all of this, which is how they drifted apart in the
 * first place. {@link Dialect} is what tells them apart.
 *
 * The mechanics, in the order the original applies them: read the template,
 * append one proxy entry per node, expand the proxy groups that name their
 * members as patterns, drop the groups left with nothing in them, force a
 * DIRECT rule for the address the subscription was fetched from, write the YAML
 * back out, and replace {@code $app_name} in the result.
 */
final class ClashRenderer implements ClientConfigRenderer {

    private final Dialect dialect;

    ClashRenderer(Dialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public RenderedConfig render(ClientConfigRequest request, List<NodeClientView> nodes) {
        Map<String, Object> config = ClashYaml.parse(request.template());

        List<Object> proxies = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (NodeClientView node : nodes) {
            Map<String, Object> proxy = proxy(node);
            if (proxy == null) {
                continue;
            }
            proxies.add(proxy);
            names.add(node.name());
        }

        config.put("proxies", mergeProxies(config.get("proxies"), proxies));
        expandGroups(config, names);
        prependSubscriptionRule(config, request.requestHost());

        // Text-level, as the original does it: the name is a value inside the
        // template, and it may appear in more than one place.
        String body = ClashYaml.dump(config).replace("$app_name", request.appName());
        return new RenderedConfig(body, "text/yaml", Map.of(
            "profile-update-interval", "24",
            "content-disposition",
            "attachment;filename*=UTF-8''" + V2rayUriEncoding.rawUrlEncode(request.appName()),
            // The original sets this only on the narrow Clash output. It is
            // harmless everywhere - it is a documentation link the client shows
            // beside the profile, and mihomo and Stash both accept it - and
            // having the whole family carry it is worth more than reproducing
            // which of the three classes happened to remember.
            "profile-web-page-url", request.appUrl()
        ));
    }

    /**
     * The ciphers the narrow builder accepts.
     *
     * Not a preference: the older Clash releases it targets cannot do
     * shadowsocks 2022, so a node using it is left out of that output rather
     * than written as a proxy the client will fail to start on. The mihomo
     * builds have no such limit.
     */
    private static final java.util.Set<String> CLASH_CIPHERS = java.util.Set.of(
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305"
    );

    /**
     * The protocols only the wide dialects understand.
     *
     * The resolver's protocol set has already excluded these for the narrow
     * dialect, so this is a second line of defence rather than the filter -
     * but it is the one that would matter, because a client handed a proxy type
     * it does not know refuses to start on the whole config, not just that node.
     */
    private static final java.util.Set<String> WIDE_PROTOCOLS = java.util.Set.of(
        "vless", "hysteria", "tuic", "anytls", "mieru"
    );

    /**
     * One proxy entry, or nothing.
     *
     * Null means the node is left out of this output entirely.
     */
    private Map<String, Object> proxy(NodeClientView node) {
        if (!dialect.isWide() && WIDE_PROTOCOLS.contains(node.protocol())) {
            return null;
        }
        return switch (node.protocol()) {
            case "shadowsocks" -> shadowsocks(node);
            case "vmess" -> ClashProxies.vmess(node, dialect);
            case "trojan" -> ClashProxies.trojan(node, dialect);
            case "socks" -> ClashProxies.socks(node, dialect);
            case "http" -> ClashProxies.http(node, dialect);
            case "vless" -> ClashProxies.vless(node, dialect);
            case "hysteria" -> ClashProxies.hysteria(node, dialect);
            case "tuic" -> ClashProxies.tuic(node, dialect);
            case "anytls" -> ClashProxies.anytls(node, dialect);
            // Stash has no mieru, though it shares the rest of the wide set.
            case "mieru" -> dialect.isMihomo() ? ClashProxies.mieru(node) : null;
            default -> null;
        };
    }

    private Map<String, Object> shadowsocks(NodeClientView node) {
        if (!dialect.isWide() && !CLASH_CIPHERS.contains(node.settings().text("cipher"))) {
            return null;
        }
        return ClashProxies.shadowsocks(node, dialect);
    }

    /** Whatever the template already listed, followed by this account's nodes. */
    private static List<Object> mergeProxies(Object existing, List<Object> proxies) {
        List<Object> merged = existing instanceof List<?> list
            ? new ArrayList<>(list)
            : new ArrayList<>();
        merged.addAll(proxies);
        return merged;
    }

    /**
     * Fill in the proxy groups whose members are patterns.
     *
     * A member is a pattern only when it is delimited - {@code /香港|HK/i} - and
     * not when it merely looks like one, because {@code 香港} is a perfectly
     * good node name and a perfectly good regex at the same time. A group with
     * no pattern members keeps its own list and has every node appended to it,
     * which is how a template says "and all of them, too".
     *
     * A group left empty is dropped rather than kept with {@code proxies: []},
     * which a client would show as an unselectable entry.
     */
    private static void expandGroups(Map<String, Object> config, List<String> names) {
        if (!(config.get("proxy-groups") instanceof List<?> groups)) {
            return;
        }
        List<Object> kept = new ArrayList<>();
        for (Object entry : groups) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> group = (Map<String, Object>) raw;
            List<String> members = new ArrayList<>();
            if (group.get("proxies") instanceof List<?> declared) {
                declared.forEach(member -> members.add(String.valueOf(member)));
            }

            boolean patternFound = false;
            List<String> resolved = new ArrayList<>(members);
            if (!names.isEmpty()) {
                for (String member : members) {
                    if (!SubscriptionNamePattern.isDelimited(member)) {
                        continue;
                    }
                    patternFound = true;
                    resolved.removeIf(member::equals);
                    for (String name : names) {
                        if (SubscriptionNamePattern.matches(member, name)) {
                            resolved.add(name);
                        }
                    }
                }
            }
            if (!patternFound) {
                resolved.addAll(names);
            }
            if (resolved.isEmpty()) {
                continue;
            }
            group.put("proxies", resolved);
            kept.add(group);
        }
        config.put("proxy-groups", kept);
    }

    /**
     * The subscription's own address goes direct.
     *
     * Otherwise the client would try to fetch its next update through a node
     * that it only knows about because it managed to fetch this one.
     */
    private static void prependSubscriptionRule(Map<String, Object> config, String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        List<Object> rules = new ArrayList<>();
        rules.add("DOMAIN," + host + ",DIRECT");
        rules.addAll(ClashYaml.list(config, "rules"));
        config.put("rules", rules);
    }
}
