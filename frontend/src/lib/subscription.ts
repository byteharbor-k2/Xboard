import type {
  BillingPeriod,
  EntitlementState,
  TrafficResetPolicy
} from "../types";

const periodLabels: Record<BillingPeriod, string> = {
  MONTHLY: "月付",
  QUARTERLY: "季付",
  HALF_YEARLY: "半年付",
  YEARLY: "年付",
  TWO_YEARLY: "两年付",
  THREE_YEARLY: "三年付",
  ONETIME: "一次性"
};

const resetLabels: Record<TrafficResetPolicy, string> = {
  FIRST_DAY_OF_MONTH: "每月 1 日",
  MONTHLY_FROM_ACTIVATION: "按开通日每月",
  NEVER: "不重置",
  FIRST_DAY_OF_YEAR: "每年 1 月 1 日",
  YEARLY_FROM_ACTIVATION: "按开通日每年"
};

const stateLabels: Record<EntitlementState, string> = {
  ACTIVE: "使用中",
  EXPIRED: "已到期",
  EXHAUSTED: "流量已用尽",
  CANCELED: "已取消"
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
  currency: string
): string {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency
  }).format(Number(amountMinor) / 100);
}

export function formatDateTime(value: string | null): string {
  if (!value) {
    return "长期有效";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(value));
}

export function billingPeriodLabel(period: BillingPeriod): string {
  return periodLabels[period];
}

export function trafficResetLabel(policy: TrafficResetPolicy): string {
  return resetLabels[policy];
}

export function entitlementStateLabel(state: EntitlementState): string {
  return stateLabels[state];
}
