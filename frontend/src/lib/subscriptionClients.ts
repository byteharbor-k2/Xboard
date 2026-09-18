type Locale = "zh-CN" | "en-US";

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
    }
  },
  {
    id: "clash",
    name: { "zh-CN": "Clash", "en-US": "Clash" },
    monogram: "CL",
    accent: "#2f6fed",
    flag: "clash",
    detail: { "zh-CN": "Windows", "en-US": "Windows" }
  },
  {
    id: "meta",
    name: { "zh-CN": "Clash Meta", "en-US": "Clash Meta" },
    monogram: "CM",
    accent: "#7c3aed",
    flag: "meta",
    detail: { "zh-CN": "macOS · Android", "en-US": "macOS · Android" }
  },
  {
    id: "nekobox",
    name: { "zh-CN": "NekoBox", "en-US": "NekoBox" },
    monogram: "NB",
    accent: "#db2777",
    flag: "meta",
    detail: { "zh-CN": "Android", "en-US": "Android" }
  },
  {
    id: "stash",
    name: { "zh-CN": "Stash", "en-US": "Stash" },
    monogram: "ST",
    accent: "#0d9488",
    flag: "stash",
    detail: { "zh-CN": "macOS · iOS", "en-US": "macOS · iOS" }
  },
  {
    id: "surge",
    name: { "zh-CN": "Surge", "en-US": "Surge" },
    monogram: "SG",
    accent: "#ea580c",
    flag: "surge",
    detail: { "zh-CN": "macOS · iOS", "en-US": "macOS · iOS" }
  },
  {
    id: "surfboard",
    name: { "zh-CN": "Surfboard", "en-US": "Surfboard" },
    monogram: "SF",
    accent: "#0891b2",
    flag: "surfboard",
    detail: { "zh-CN": "Android", "en-US": "Android" }
  },
  {
    id: "singbox",
    name: { "zh-CN": "sing-box", "en-US": "sing-box" },
    monogram: "SI",
    accent: "#16a34a",
    flag: "sing-box",
    detail: { "zh-CN": "macOS · iOS · Android", "en-US": "macOS · iOS · Android" }
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
    }
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
    }
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
