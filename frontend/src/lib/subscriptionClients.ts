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
  | "shadowrocket";

/**
 * One row of the client chooser on the account dashboard.
 *
 * The list mirrors the original panel's subscribe popup, minus QuantumultX:
 * this backend has no renderer for its format, and an entry that hands out a
 * config the client cannot read is worse than no entry at all. Shadowrocket is
 * kept because it reads the generic base64 URI list, which this backend does
 * serve — the row says so in its detail line rather than pretending otherwise.
 */
export type SubscriptionClient = {
  id: string;
  /** Brand names are not translated; the universal row is. */
  name: Record<Locale, string>;
  /** One or two characters for the badge, unique across the list. */
  monogram: string;
  /** Badge colour, picked so no two neighbours read as the same client. */
  accent: string;
  /**
   * The `flag` this client's format is served under, or null to send the bare
   * URL and let the client identify itself from its User-Agent.
   */
  flag: string | null;
  /** Where it runs, plus the one caveat a user would otherwise be surprised by. */
  detail: Record<Locale, string>;
  /** Systems the client has a build for. Empty when it cannot import at all. */
  platforms: VisitorPlatform[];
  /** How to open the client with a subscription, or null for no import. */
  importScheme: ImportScheme | null;
};

export const subscriptionClients: SubscriptionClient[] = [
  {
    id: "universal",
    name: { "zh-CN": "通用订阅", "en-US": "Universal" },
    monogram: "＊",
    accent: "#64748b",
    flag: null,
    detail: {
      "zh-CN": "任意客户端 · 由客户端自行识别格式",
      "en-US": "Any client · format detected from its user agent"
    },
    platforms: [],
    importScheme: null
  },
  {
    id: "clash",
    name: { "zh-CN": "Clash", "en-US": "Clash" },
    monogram: "CL",
    accent: "#2f6fed",
    flag: "clash",
    detail: { "zh-CN": "Windows", "en-US": "Windows" },
    platforms: ["windows"],
    importScheme: "clash"
  },
  {
    id: "meta",
    name: { "zh-CN": "Clash Meta", "en-US": "Clash Meta" },
    monogram: "CM",
    accent: "#7c3aed",
    flag: "meta",
    detail: { "zh-CN": "macOS · Android", "en-US": "macOS · Android" },
    platforms: ["mac", "android"],
    importScheme: "clash"
  },
  {
    id: "nekobox",
    name: { "zh-CN": "NekoBox", "en-US": "NekoBox" },
    monogram: "NB",
    accent: "#db2777",
    flag: "meta",
    detail: { "zh-CN": "Android", "en-US": "Android" },
    platforms: ["android"],
    importScheme: "clash"
  },
  {
    id: "stash",
    name: { "zh-CN": "Stash", "en-US": "Stash" },
    monogram: "ST",
    accent: "#0d9488",
    flag: "stash",
    detail: { "zh-CN": "macOS · iOS", "en-US": "macOS · iOS" },
    platforms: ["mac", "ios"],
    importScheme: "stash"
  },
  {
    id: "surge",
    name: { "zh-CN": "Surge", "en-US": "Surge" },
    monogram: "SG",
    accent: "#ea580c",
    flag: "surge",
    detail: { "zh-CN": "macOS · iOS", "en-US": "macOS · iOS" },
    platforms: ["mac", "ios"],
    importScheme: "surge"
  },
  {
    id: "surfboard",
    name: { "zh-CN": "Surfboard", "en-US": "Surfboard" },
    monogram: "SF",
    accent: "#0891b2",
    flag: "surfboard",
    detail: { "zh-CN": "Android", "en-US": "Android" },
    platforms: ["android"],
    importScheme: "surfboard"
  },
  {
    id: "singbox",
    name: { "zh-CN": "sing-box", "en-US": "sing-box" },
    monogram: "SI",
    accent: "#16a34a",
    flag: "sing-box",
    detail: { "zh-CN": "macOS · iOS · Android", "en-US": "macOS · iOS · Android" },
    platforms: ["mac", "ios", "android"],
    importScheme: "singbox"
  },
  {
    id: "hiddify",
    name: { "zh-CN": "Hiddify", "en-US": "Hiddify" },
    monogram: "HD",
    accent: "#ca8a04",
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
    monogram: "SR",
    accent: "#be123c",
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
 * A flagged link is the point of the chooser: the client gets the config built
 * for it rather than whatever its User-Agent happens to resolve to, and the row
 * the user clicked is the row they get. The universal row passes null so its
 * link stays format-agnostic.
 */
export function subscriptionUrlForClient(
  baseUrl: string,
  client: SubscriptionClient
): string {
  if (!client.flag) {
    return baseUrl;
  }
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
 * Ported from the original's client list, one scheme at a time, because the
 * differences are not cosmetic: Clash and Surge take the address percent-encoded
 * in a query parameter, sing-box and Hiddify take it in the fragment, Hiddify
 * wants it *unencoded*, and Shadowrocket wants it base64url-encoded in the path.
 * Returns null for a client that has no import at all (the universal row).
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
  if (ua.includes("iphone") || ua.includes("ipod")) {
    return "ios";
  }
  if (ua.includes("ipad")) {
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
