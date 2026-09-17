package com.sinx.platform.subscription.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surge and Surfboard, whose configs are the same shape.
 *
 * Surfboard's manual says its proxy and policy-group syntax follows Surge's, and
 * the two templates the original ships are near-identical; one renderer covers
 * both because the difference is four things, all of them here: the name
 * separator ({@code name = ss} against {@code name=ss}), which proxies the older
 * cipher set leaves out, whether AnyTLS carries the UDP and fast-open flags, and
 * the headers. Splitting this into two classes would duplicate the substitution
 * mechanics - which is the part that is actually easy to get wrong - to
 * accommodate four lines.
 *
 * Unlike Clash, nothing here is parsed. The template is a text file with five
 * placeholders and each is replaced by a string; the original does the same, and
 * reproducing it means an administrator's template is honoured exactly as
 * written, comments and all.
 */
final class SurgeRenderer implements ClientConfigRenderer {

    /** The ciphers Surge is willing to encrypt with. */
    private static final Set<String> SURGE_CIPHERS = Set.of(
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm"
    );

    /** Surfboard's parser is its own, and it knows the 2022 chacha cipher. */
    private static final Set<String> SURFBOARD_CIPHERS = Set.of(
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305"
    );

    /**
     * The zone the expiry date is written in.
     *
     * The original formats it with PHP's application timezone, which its config
     * sets to Shanghai, and this panel already bills node traffic in that zone.
     * A date in a config file the customer reads is a display string, so it is
     * written where the customer is most likely to be rather than in UTC.
     */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter EXPIRY =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final BigDecimal BYTES_PER_GIB =
        BigDecimal.valueOf(1024L * 1024L * 1024L);

    private final boolean surfboard;

    private SurgeRenderer(boolean surfboard) {
        this.surfboard = surfboard;
    }

    static ClientConfigRenderer surge() {
        return new SurgeRenderer(false);
    }

    static ClientConfigRenderer surfboard() {
        return new SurgeRenderer(true);
    }

    @Override
    public RenderedConfig render(ClientConfigRequest request, List<NodeClientView> nodes) {
        StringBuilder proxies = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (NodeClientView node : nodes) {
            String entry = entry(node);
            if (entry == null) {
                continue;
            }
            proxies.append(entry);
            names.add(node.name());
        }

        String body = request.template()
            .replace("$subs_link", request.subscriptionUrl())
            .replace("$subs_domain", nullToEmpty(request.requestHost()))
            .replace("$proxies", proxies.toString().stripTrailing())
            .replace("$proxy_group", String.join(", ", names))
            .replace("$subscribe_info", information(request));

        return new RenderedConfig(body, contentType(), Map.of(
            "content-disposition",
            "attachment;filename*=UTF-8''"
                + V2rayUriEncoding.rawUrlEncode(request.appName())
                + ".conf"
        ));
    }

    /**
     * The panel a Surge or Surfboard client shows above its node list.
     *
     * The gigabytes are rounded to two places and then written without trailing
     * zeros, and the remaining figure is the difference between two already
     * rounded numbers - both as the original computes it, so the arithmetic on
     * screen adds up the way a customer checking it expects.
     *
     * The line breaks are a literal backslash and {@code n}, which is how these
     * clients express a line break inside a config value. Writing real newlines
     * here would end the entry.
     */
    private static String information(ClientConfigRequest request) {
        SubscriptionUsage usage = request.usage();
        BigDecimal uploaded = gigabytes(usage.uploadedBytes());
        BigDecimal downloaded = gigabytes(usage.downloadedBytes());
        BigDecimal total = gigabytes(usage.transferLimitBytes());
        BigDecimal remaining = total.subtract(uploaded).subtract(downloaded);
        String expiry = usage.expiresAt() == null
            ? "长期有效"
            : EXPIRY.format(ZonedDateTime.ofInstant(usage.expiresAt(), DISPLAY_ZONE));

        return "title=" + request.appName()
            + "订阅信息, content=上传流量：" + plain(uploaded)
            + "GB\\n下载流量：" + plain(downloaded)
            + "GB\\n剩余流量：" + plain(remaining)
            + "GB\\n套餐流量：" + plain(total)
            + "GB\\n到期时间：" + expiry;
    }

    private static BigDecimal gigabytes(long bytes) {
        return BigDecimal.valueOf(bytes)
            .divide(BYTES_PER_GIB, 2, RoundingMode.HALF_UP);
    }

    /** Two decimals at most, and no trailing zeros: {@code 2}, not {@code 2.00}. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * One node's entry, already terminated, or null when this format cannot
     * express it.
     */
    private String entry(NodeClientView node) {
        List<String> lines = switch (node.protocol()) {
            case "shadowsocks" -> shadowsocks(node);
            case "vmess" -> vmess(node);
            case "trojan" -> trojan(node);
            case "anytls" -> anytls(node);
            case "hysteria" -> surfboard ? null : hysteria(node);
            case "socks" -> surfboard ? null : socks(node);
            case "http" -> surfboard ? null : http(node);
            default -> null;
        };
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        return String.join(",", lines) + "\r\n";
    }

    /**
     * {@code name = type} for Surge, {@code name=type} for Surfboard.
     *
     * The spacing is not cosmetic on either side: it is how each client writes
     * its own sample configs, and an administrator comparing this output with
     * the manual is entitled to see the same thing.
     */
    private String head(NodeClientView node, String type) {
        return node.name() + (surfboard ? "=" : " = ") + type;
    }

    private Set<String> ciphers() {
        return surfboard ? SURFBOARD_CIPHERS : SURGE_CIPHERS;
    }

    private List<String> shadowsocks(NodeClientView node) {
        String cipher = node.settings().text("cipher");
        if (cipher == null || !ciphers().contains(cipher)) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of(
            head(node, "ss"),
            node.host(),
            String.valueOf(node.port()),
            "encrypt-method=" + cipher,
            "password=" + node.credential(),
            "tfo=true",
            "udp-relay=true"
        ));
        appendObfs(lines, node);
        return lines;
    }

    /**
     * The simple-obfs stanza, which is all either of these clients understands.
     *
     * The original reads the plugin options out of one semicolon-separated
     * string and writes only {@code obfs} back out - a {@code v2ray-plugin} node
     * therefore ends up rendered as a plain shadowsocks proxy, which is the
     * behaviour this preserves rather than fixes.
     */
    private static void appendObfs(List<String> lines, NodeClientView node) {
        String plugin = node.settings().text("plugin");
        String options = node.settings().text("plugin_opts");
        if (plugin == null || options == null || !"obfs".equals(plugin)) {
            return;
        }
        Map<String, String> parsed = parsePluginOptions(options);
        String mode = parsed.get("obfs");
        if (mode != null) {
            lines.add("obfs=" + mode);
        }
        String host = parsed.get("obfs-host");
        if (host != null) {
            lines.add("obfs-host=" + host);
        }
        String path = parsed.get("path");
        if (path != null) {
            lines.add("obfs-uri=" + path);
        }
    }

    /** {@code obfs=http;obfs-host=www.bing.com} into its pairs. */
    private static Map<String, String> parsePluginOptions(String options) {
        Map<String, String> parsed = new java.util.LinkedHashMap<>();
        for (String pair : options.split(";")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            parsed.put(
                pair.substring(0, equals).trim(),
                pair.substring(equals + 1).trim()
            );
        }
        return parsed;
    }

    private List<String> vmess(NodeClientView node) {
        SettingsView settings = node.settings();
        List<String> lines = new ArrayList<>(List.of(
            head(node, "vmess"),
            node.host(),
            String.valueOf(node.port()),
            "username=" + node.credential(),
            "vmess-aead=true",
            "tfo=true",
            "udp-relay=true"
        ));

        if (settings.flag("tls")) {
            lines.add("tls=true");
            // The original writes the trust settings in this order for vmess
            // and the other way round for socks and http. Nothing reads the
            // order, but an administrator diffing against the original's
            // output should not have to wonder whether the difference means
            // something.
            if (settings.flag("tls_settings.allow_insecure")) {
                lines.add("skip-cert-verify=true");
            }
            String serverName = settings.text("tls_settings.server_name");
            if (serverName != null) {
                lines.add("sni=" + serverName);
            }
        }
        if ("ws".equals(settings.text("network"))) {
            lines.add("ws=true");
            String path = settings.text("network_settings.path");
            if (path != null) {
                lines.add("ws-path=" + path);
            }
            // The original reads a nested network_settings.headers.Host, which
            // this panel has no field for; the flat host is what its node side
            // reads, so it serves both, as it does for Clash.
            String host = settings.text("network_settings.host");
            if (host != null) {
                lines.add("ws-headers=Host:" + host);
            }
        }
        return lines;
    }

    private List<String> trojan(NodeClientView node) {
        SettingsView settings = node.settings();
        List<String> lines = new ArrayList<>(List.of(
            head(node, "trojan"),
            node.host(),
            String.valueOf(node.port()),
            "password=" + node.credential()
        ));
        String serverName = settings.text("tls_settings.server_name");
        if (serverName != null) {
            lines.add("sni=" + serverName);
        }
        lines.add("tfo=true");
        lines.add("udp-relay=true");
        if (settings.flag("tls_settings.allow_insecure")) {
            lines.add("skip-cert-verify=true");
        }
        return lines;
    }

    /**
     * The one place the two clients genuinely disagree about a protocol's entry.
     *
     * Surge's own sample for AnyTLS lists neither the fast-open nor the UDP
     * flag, and Surge refuses a proxy line carrying options it does not know;
     * Surfboard takes both. The original writes them for Surfboard only, and
     * that asymmetry is the whole reason this method is not shared.
     */
    private List<String> anytls(NodeClientView node) {
        SettingsView settings = node.settings();
        List<String> lines = new ArrayList<>(List.of(
            head(node, "anytls"),
            node.host(),
            String.valueOf(node.port()),
            "password=" + node.credential()
        ));
        if (surfboard) {
            lines.add("tfo=true");
            lines.add("udp-relay=true");
        }
        String serverName = settings.text("tls.server_name");
        if (serverName != null) {
            lines.add("sni=" + serverName);
        }
        if (settings.flag("tls.allow_insecure")) {
            lines.add("skip-cert-verify=true");
        }
        return lines;
    }

    /**
     * Hysteria 2 only.
     *
     * Surge has no Hysteria 1 support at all, and the original answers with an
     * empty string rather than a best-effort entry - a node that cannot work is
     * worse in a client's list than one that is simply absent.
     */
    private List<String> hysteria(NodeClientView node) {
        SettingsView settings = node.settings();
        if (settings.integer("version", 1) != 2) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of(
            head(node, "hysteria2"),
            node.host(),
            String.valueOf(node.port()),
            "password=" + node.credential()
        ));
        String serverName = settings.text("tls.server_name");
        if (serverName != null) {
            lines.add("sni=" + serverName);
        }
        lines.add("udp-relay=true");
        appendBandwidth(lines, settings);
        if (settings.flag("tls.allow_insecure")) {
            lines.add("skip-cert-verify=true");
        }
        return lines;
    }

    /**
     * The bandwidth hints, in the units the node stored them in.
     *
     * Surge reads both as Mbps and the panel's node form asks for Mbps, so the
     * value is passed through rather than converted.
     */
    private static void appendBandwidth(List<String> lines, SettingsView settings) {
        String up = settings.scalar("bandwidth.up");
        if (up != null) {
            lines.add("upload-bandwidth=" + up);
        }
        String down = settings.scalar("bandwidth.down");
        if (down != null) {
            lines.add("download-bandwidth=" + down);
        }
    }

    /**
     * Socks and HTTP carry the account identity as both halves of a basic auth
     * pair, which is the shape the original writes - the credential repeated on
     * its own line, twice, because that position is the username and then the
     * password.
     */
    private List<String> socks(NodeClientView node) {
        SettingsView settings = node.settings();
        boolean tls = settings.flag("tls");
        List<String> lines = new ArrayList<>(List.of(
            head(node, tls ? "socks5-tls" : "socks5"),
            node.host(),
            String.valueOf(node.port()),
            node.credential(),
            node.credential()
        ));
        if (tls) {
            appendTlsTrust(lines, settings, "tls_settings");
        }
        lines.add("udp-relay=true");
        return lines;
    }

    private List<String> http(NodeClientView node) {
        SettingsView settings = node.settings();
        boolean tls = settings.flag("tls");
        List<String> lines = new ArrayList<>(List.of(
            head(node, tls ? "https" : "http"),
            node.host(),
            String.valueOf(node.port()),
            node.credential(),
            node.credential()
        ));
        if (tls) {
            appendTlsTrust(lines, settings, "tls_settings");
        }
        return lines;
    }

    private static void appendTlsTrust(
        List<String> lines,
        SettingsView settings,
        String prefix
    ) {
        String serverName = settings.text(prefix + ".server_name");
        if (serverName != null) {
            lines.add("sni=" + serverName);
        }
        if (settings.flag(prefix + ".allow_insecure")) {
            lines.add("skip-cert-verify=true");
        }
    }

    private String contentType() {
        // The original sets a type for Surge and none at all for Surfboard,
        // where the framework then filled in text/html. A .conf download is
        // neither, so the unset case is given the plain type it should have had.
        return surfboard ? "text/plain" : "application/octet-stream";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
