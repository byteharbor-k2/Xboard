import type {
  BillingPeriod,
  EntitlementState,
  TrafficResetPolicy
} from "../types";

type Locale = "zh-CN" | "en-US";

const periodLabels: Record<Locale, Record<BillingPeriod, string>> = {
  "zh-CN": {
    MONTHLY: "月付",
    QUARTERLY: "季付",
    HALF_YEARLY: "半年付",
    YEARLY: "年付",
    TWO_YEARLY: "两年付",
    THREE_YEARLY: "三年付",
    ONETIME: "一次性"
  },
  "en-US": {
    MONTHLY: "Monthly",
    QUARTERLY: "Quarterly",
    HALF_YEARLY: "Half-yearly",
    YEARLY: "Yearly",
    TWO_YEARLY: "Two years",
    THREE_YEARLY: "Three years",
    ONETIME: "One-time"
  }
};

const resetLabels: Record<Locale, Record<TrafficResetPolicy, string>> = {
  "zh-CN": {
    FIRST_DAY_OF_MONTH: "每月 1 日",
    MONTHLY_FROM_ACTIVATION: "按开通日每月",
    NEVER: "不重置",
    FIRST_DAY_OF_YEAR: "每年 1 月 1 日",
    YEARLY_FROM_ACTIVATION: "按开通日每年"
  },
  "en-US": {
    FIRST_DAY_OF_MONTH: "First day of each month",
    MONTHLY_FROM_ACTIVATION: "Monthly from activation",
    NEVER: "Never",
    FIRST_DAY_OF_YEAR: "First day of each year",
    YEARLY_FROM_ACTIVATION: "Yearly from activation"
  }
};

const stateLabels: Record<Locale, Record<EntitlementState, string>> = {
  "zh-CN": {
    ACTIVE: "使用中",
    EXPIRED: "已到期",
    EXHAUSTED: "流量已用尽",
    CANCELED: "已取消"
  },
  "en-US": {
    ACTIVE: "Active",
    EXPIRED: "Expired",
    EXHAUSTED: "Data exhausted",
    CANCELED: "Canceled"
  }
};

export function formatBytes(value: string): string {
  const bytes = Number(value);
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return "0 GB";
  }
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  const index = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    units.length - 1
  );
  const amount = bytes / 1024 ** index;
  return `${amount.toFixed(amount >= 100 ? 0 : amount >= 10 ? 1 : 2)} ${units[index]}`;
}

export function formatMoney(
  amountMinor: string,
  currency: string,
  locale: Locale = "zh-CN"
): string {
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency
  }).format(Number(amountMinor) / 100);
}

export function formatDateTime(
  value: string | null,
  locale: Locale = "zh-CN"
): string {
  if (!value) {
    return locale === "zh-CN" ? "长期有效" : "No expiration";
  }
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function billingPeriodLabel(
  period: BillingPeriod,
  locale: Locale = "zh-CN"
): string {
  return periodLabels[locale][period];
}

export function trafficResetLabel(
  policy: TrafficResetPolicy,
  locale: Locale = "zh-CN"
): string {
  return resetLabels[locale][policy];
}

export function entitlementStateLabel(
  state: EntitlementState,
  locale: Locale = "zh-CN"
): string {
  return stateLabels[locale][state];
}
