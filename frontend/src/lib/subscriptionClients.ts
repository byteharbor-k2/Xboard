type Locale = "zh-CN" | "en-US";

/** What the visitor is browsing from, as far as their User-Agent admits. */
export type VisitorPlatform = "windows" | "mac" | "ios" | "android" | "unknown";

/**
 * The URL scheme a client registers to be opened with a subscription.
 *
 * Named rather than stored as a builder function so the catalog stays data and
 * the escaping lives in one place — each of these schemes quotes the address
 * differently, and two of them do not quote it at all.
 */
type ImportScheme =
  | "clash"
  | "surge"
  | "stash"
  | "surfboard"
  | "singbox"
  | "hiddify"
  | "v2rayng"
  | "shadowrocket";

/**
 * One row of the client chooser on the account dashboard.
 *
 * Two deliberate omissions. There is no "Clash" row: that name selects the
 * original Clash core's narrow protocol set, which cannot carry vless,
 * hysteria, tuic or anytls, and everyone on that core has a concrete app named
 * below instead. There is no universal row either — a row that hands out
 * whatever the User-Agent resolves to reads as a safe default to a newcomer and
 * is not one. Every row is now a named app, so the link a user copies is the
 * one they asked for.
 *
 * QuantumultX is still absent for a different reason: this backend has no
 * renderer for its format, and an entry that hands out a config the client
 * cannot read is worse than no entry.
 */
export type SubscriptionClient = {
  id: string;
  /** Brand names are not translated. */
  name: Record<Locale, string>;
  /** Path under `public/`, served from the site root. */
  icon: string;
  /**
   * The `flag` this client's format is served under.
   */
  flag: string;
  /** Where it runs, plus any caveat a user would otherwise be surprised by. */
  detail: Record<Locale, string>;
  /** Systems the client has a build for. */
  platforms: VisitorPlatform[];
  /**
   * How to open the client with a subscription, or null when the app has no
   * such scheme. V2rayN is the null case: it reads a subscription from the
   * clipboard, and the only `v2rayn://` it registers imports a single profile
   * (`v2rayn://{type}/{base64}`), not a subscription.
   */
  importScheme: ImportScheme | null;
};

export const subscriptionClients: SubscriptionClient[] = [
  {
    id: "meta",
    name: { "zh-CN": "Clash Meta", "en-US": "Clash Meta" },
    icon: "/clients/clash-meta.webp",
    flag: "meta",
    detail: {
      "zh-CN": "macOS · Android · mihomo 内核",
      "en-US": "macOS · Android · mihomo core"
    },
    platforms: ["mac", "android"],
    importScheme: "clash"
  },
  {
    id: "cfw",
    name: { "zh-CN": "Clash for Windows", "en-US": "Clash for Windows" },
    icon: "/clients/cfw.png",
    flag: "clash",
    detail: {
      "zh-CN": "Windows · 旧版 Clash 内核，无 vless / hysteria",
      "en-US": "Windows · legacy Clash core, no vless / hysteria"
    },
    platforms: ["windows"],
    importScheme: "clash"
  },
  {
    id: "v2rayn",
    name: { "zh-CN": "V2rayN", "en-US": "V2rayN" },
    icon: "/clients/v2rayn.png",
    flag: "general",
    detail: {
      "zh-CN": "Windows · 通用 v2ray 链接，需手动粘贴订阅",
      "en-US": "Windows · generic v2ray links, paste the subscription manually"
    },
    platforms: ["windows"],
    importScheme: null
  },
  {
    id: "v2rayng",
    name: { "zh-CN": "V2rayNG", "en-US": "V2rayNG" },
    icon: "/clients/v2rayng.png",
    flag: "general",
    detail: { "zh-CN": "Android", "en-US": "Android" },
    platforms: ["android"],
    importScheme: "v2rayng"
  },
  {
    id: "nekobox",
    name: { "zh-CN": "NekoBox", "en-US": "NekoBox" },
    icon: "/clients/nekobox.jpg",
    flag: "meta",
    detail: { "zh-CN": "Android · mihomo 内核", "en-US": "Android · mihomo core" },
    platforms: ["android"],
    importScheme: "clash"
  },
  {
    id: "surfboard",
    name: { "zh-CN": "Surfboard", "en-US": "Surfboard" },
    icon: "/clients/surfboard.png",
    flag: "surfboard",
    detail: { "zh-CN": "Android", "en-US": "Android" },
    platforms: ["android"],
    importScheme: "surfboard"
  },
  {
    id: "stash",
    name: { "zh-CN": "Stash", "en-US": "Stash" },
    icon: "/clients/stash.png",
    flag: "stash",
    detail: { "zh-CN": "macOS · iOS", "en-US": "macOS · iOS" },
    platforms: ["mac", "ios"],
    importScheme: "stash"
  },
  {
    id: "surge",
    name: { "zh-CN": "Surge", "en-US": "Surge" },
    icon: "/clients/surge.png",
    flag: "surge",
    detail: { "zh-CN": "macOS · iOS", "en-US": "macOS · iOS" },
    platforms: ["mac", "ios"],
    importScheme: "surge"
  },
  {
    id: "singbox",
    name: { "zh-CN": "sing-box", "en-US": "sing-box" },
    icon: "/clients/singbox.png",
    flag: "sing-box",
    detail: { "zh-CN": "macOS · iOS · Android", "en-US": "macOS · iOS · Android" },
    platforms: ["mac", "ios", "android"],
    importScheme: "singbox"
  },
  {
    id: "hiddify",
    name: { "zh-CN": "Hiddify", "en-US": "Hiddify" },
    icon: "/clients/hiddify.png",
    flag: "sing-box",
    detail: {
      "zh-CN": "macOS · Windows · iOS · Android",
      "en-US": "macOS · Windows · iOS · Android"
    },
    platforms: ["mac", "windows", "ios", "android"],
    importScheme: "hiddify"
  },
  {
    id: "shadowrocket",
    name: { "zh-CN": "Shadowrocket", "en-US": "Shadowrocket" },
    icon: "/clients/shadowrocket.png",
    flag: "general",
    detail: {
      "zh-CN": "macOS · iOS · 通用 v2ray 链接",
      "en-US": "macOS · iOS · generic v2ray links"
    },
    platforms: ["mac", "ios"],
    importScheme: "shadowrocket"
  }
];

/**
 * The address to hand a specific client.
 *
 * The flag is what makes the row the user clicked the format they get, rather
 * than whatever their User-Agent happens to resolve to.
 */
export function subscriptionUrlForClient(
  baseUrl: string,
  client: SubscriptionClient
): string {
  const separator = baseUrl.includes("?") ? "&" : "?";
  return `${baseUrl}${separator}flag=${encodeURIComponent(client.flag)}`;
}

/**
 * base64url without padding, which is what Shadowrocket's `sub://` payload is.
 *
 * Encoding through TextEncoder rather than `btoa(url)` keeps a non-ASCII
 * address from throwing, which `btoa` does on any code point above U+00FF.
 */
function base64Url(value: string): string {
  let binary = "";
  for (const byte of new TextEncoder().encode(value)) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

/**
 * The deep link that opens a client with this subscription.
 *
 * Ported from the original's client list plus two apps it does not carry, one
 * scheme at a time, because the differences are not cosmetic: Clash and Surge
 * take the address percent-encoded in a query parameter, sing-box and V2rayNG
 * take it in the fragment, Hiddify wants it *unencoded*, and Shadowrocket wants
 * it base64url-encoded in the path.
 *
 * Returns null for an app with no subscription scheme (V2rayN).
 */
export function clientImportLink(
  client: SubscriptionClient,
  url: string,
  siteName: string
): string | null {
  const encoded = encodeURIComponent(url);
  const name = encodeURIComponent(siteName);
  switch (client.importScheme) {
    case "clash":
      return `clash://install-config?url=${encoded}&name=${name}`;
    case "surge":
      return `surge:///install-config?url=${encoded}&name=${name}`;
    case "stash":
      return `stash://install-config?url=${encoded}&name=${name}`;
    case "surfboard":
      return `surfboard:///install-config?url=${encoded}&name=${name}`;
    case "singbox":
      return `sing-box://import-remote-profile?url=${encoded}#${name}`;
    case "hiddify":
      return `hiddify://import/${url}#${name}`;
    // V2rayNG's UrlSchemeActivity reads the `url` query parameter and the
    // fragment, then URL-decodes the parameter a second time — so the address
    // is percent-encoded here and the name rides in the fragment.
    case "v2rayng":
      return `v2rayng://install-config?url=${encoded}#${name}`;
    case "shadowrocket":
      return `shadowrocket://add/sub://${base64Url(url)}?remark=${name}`;
    default:
      return null;
  }
}

/**
 * Which system the visitor is on, so a deep link is only offered where it can
 * actually be handled.
 *
 * iPadOS 13 and later report themselves as `Macintosh`, so a Mac that reports
 * more than one touch point is an iPad. Without that check every iPad would be
 * offered macOS-only clients.
 */
export function detectVisitorPlatform(
  userAgent: string,
  maxTouchPoints = 0
): VisitorPlatform {
  const ua = userAgent.toLowerCase();
  if (ua.includes("windows")) {
    return "windows";
  }
  if (ua.includes("iphone") || ua.includes("ipod") || ua.includes("ipad")) {
    return "ios";
  }
  if (ua.includes("macintosh") || ua.includes("mac os x")) {
    return maxTouchPoints > 1 ? "ios" : "mac";
  }
  if (ua.includes("android")) {
    return "android";
  }
  return "unknown";
}

/**
 * Whether the client can be opened from the visitor's current system.
 *
 * An undetectable system does not block the button: refusing to offer an import
 * on a guess is worse than offering one that may not resolve.
 */
export function canImportOn(
  client: SubscriptionClient,
  platform: VisitorPlatform
): boolean {
  if (!client.importScheme) {
    return false;
  }
  return platform === "unknown" || client.platforms.includes(platform);
}

export const platformLabels: Record<Locale, Record<VisitorPlatform, string>> = {
  "zh-CN": {
    windows: "Windows",
    mac: "macOS",
    ios: "iOS",
    android: "Android",
    unknown: "当前系统"
  },
  "en-US": {
    windows: "Windows",
    mac: "macOS",
    ios: "iOS",
    android: "Android",
    unknown: "your system"
  }
};
