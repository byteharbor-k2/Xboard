package com.sinx.platform.subscription.client;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

import tools.jackson.databind.ObjectMapper;

/**
 * Which format a request is asking for.
 *
 * The original lets the client name a format with {@code ?flag=}, and falls
 * back to looking for a client's name inside its User-Agent; either way it asks
 * each format in turn whether any of its names appears in the string. This does
 * the same, and the order matters more than it looks: {@code
 * clashmetaforandroid} and {@code clash-verge} both contain a substring that
 * selects the narrow Clash format, and the mihomo format has to be asked first
 * or those clients would be served a config their own features do not fit in.
 *
 * Nothing matching means the generic URI list, which is what the original does
 * too - a client the panel has never heard of is more likely to read base64
 * links, if it reads anything at all, than to read Clash YAML.
 */
@Component
public class ClientFormatResolver {

    private static final Set<String> CLASH_PROTOCOLS =
        Set.of("shadowsocks", "vmess", "trojan", "socks", "http");

    private static final Set<String> CLASH_META_PROTOCOLS = Set.of(
        "shadowsocks", "vmess", "trojan", "vless", "hysteria",
        "tuic", "anytls", "socks", "http", "mieru"
    );

    private static final Set<String> SING_BOX_PROTOCOLS = Set.of(
        "shadowsocks", "vmess", "trojan", "vless", "hysteria",
        "tuic", "anytls", "socks", "http"
    );

    private static final Set<String> GENERIC_PROTOCOLS = Set.of(
        "shadowsocks", "vmess", "vless", "trojan", "hysteria",
        "tuic", "anytls", "socks", "http"
    );

    private final List<ClientFormat> formats;
    private final ClientFormat fallback;

    public ClientFormatResolver(ObjectMapper mapper) {
        this.fallback = new ClientFormat(
            "general",
            List.of("general", "v2rayn", "v2rayng", "passwall", "ssrplus", "sagernet"),
            GENERIC_PROTOCOLS,
            null,
            new GenericUriRenderer(mapper)
        );
        this.formats = List.of(
            new ClientFormat(
                "meta",
                List.of(
                    "meta", "mihomo", "clashmetaforandroid", "clash-verge", "verge",
                    "flclash", "nekobox"
                ),
                CLASH_META_PROTOCOLS,
                SubscriptionTemplates.Kind.CLASH_META,
                new ClashRenderer(true)
            ),
            new ClientFormat(
                "clash",
                List.of("clash"),
                CLASH_PROTOCOLS,
                SubscriptionTemplates.Kind.CLASH,
                new ClashRenderer(false)
            ),
            new ClientFormat(
                "sing-box",
                List.of("sing-box", "singbox", "hiddify", "sfm"),
                SING_BOX_PROTOCOLS,
                SubscriptionTemplates.Kind.SING_BOX,
                new SingBoxRenderer(mapper)
            ),
            fallback
        );
    }

    /**
     * The format a request wants.
     *
     * @param flag whatever the client said - the {@code flag} parameter when it
     *             gave one, its User-Agent otherwise, either of which may be
     *             absent
     */
    public ClientFormat resolve(String flag) {
        String candidate = flag == null ? "" : flag.toLowerCase(Locale.ROOT);
        if (!candidate.isBlank()) {
            for (ClientFormat format : formats) {
                for (String token : format.flags()) {
                    if (candidate.contains(token)) {
                        return format;
                    }
                }
            }
        }
        return fallback;
    }

    /** Every format this panel can serve, for reporting and for tests. */
    public List<ClientFormat> formats() {
        return formats;
    }
}
