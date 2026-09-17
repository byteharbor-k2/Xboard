package com.sinx.platform.subscription.client;

/**
 * Which member of the Clash family a config is being written for.
 *
 * The three are not a version ladder. Stash, in the middle, reads mihomo's
 * protocol set - it will take a vless, hysteria 2, TUIC or shadowsocks 2022 node
 * without complaint - but none of mihomo's newer proxy fields: no uTLS
 * fingerprint, no multiplex, no ECH, and no {@code httpupgrade} or {@code xhttp}
 * transport. It has quirks of its own in exchange, most of them in how it spells
 * the hysteria and tuic entries.
 *
 * That is why this is an enum and not a boolean. The original splits the same
 * decisions across two classes and a per-protocol method table, which is how
 * {@code ClashMeta.php} came to be two and a half times the length of
 * {@code Clash.php} while differing from it in about a dozen lines.
 */
enum Dialect {

    /** The older Clash the original still serves, with the narrow protocol set. */
    CLASH,

    /** mihomo, and every client that embeds it. */
    META,

    /** Stash. */
    STASH;

    /** Whether mihomo's newer proxy fields may be written. */
    boolean isMihomo() {
        return this == META;
    }

    boolean isStash() {
        return this == STASH;
    }

    /**
     * Whether this dialect understands the protocols mihomo added.
     *
     * True for Stash, which is why the two cannot be told apart by a single
     * flag: the protocol set is the wide one while the fields are the narrow
     * one's.
     */
    boolean isWide() {
        return this != CLASH;
    }
}
