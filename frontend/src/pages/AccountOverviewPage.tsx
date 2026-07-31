import { useEffect, useState } from "react";

import { AppLink } from "../components/AppLink";
import { AppShell } from "../components/AppShell";
import {
  ApiError,
  graphQl,
  requestEmailVerification
} from "../lib/http";
import {
  entitlementStateLabel,
  formatBytes,
  formatDateTime,
  trafficResetLabel
} from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { SubscriptionEntitlement } from "../types";

function formatDate(value: string, locale: "zh-CN" | "en-US") {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "long"
  }).format(new Date(value));
}

const copy = {
  "zh-CN": {
    greeting: "你好",
    description: "在这里管理账户资料与安全状态。",
    emailUnverified: "邮箱尚未验证",
    emailUnverifiedDescription:
      "完成验证后，账户恢复与重要通知会更加可靠。",
    sending: "正在发送…",
    resend: "重新发送邮件",
    verificationSent: "验证邮件已重新发送。",
    emailFailed: "邮件发送失败",
    entitlementFailed: "订阅权益加载失败",
    subscription: "当前订阅",
    loading: "正在读取权益…",
    noPlan: "暂无套餐",
    viewPlans: "查看套餐",
    used: "已使用",
    remaining: "剩余流量",
    uploadDownload: "上传 / 下载",
    expires: "有效期至",
    nextReset: "下次重置",
    speed: "峰值速率",
    unlimitedSpeed: "不限速",
    devices: "设备数量",
    unlimitedDevices: "不限制",
    deviceUnit: "台",
    empty:
      "当前账户还没有订阅权益，开通套餐后这里会显示流量和有效期。",
    accountEmail: "账户邮箱",
    verified: "已验证",
    awaitingVerification: "等待验证",
    identity: "账户身份",
    user: "用户",
    identityDescription: "权限由系统角色控制",
    joined: "加入时间",
    account: "SinX Cloud 账户"
  },
  "en-US": {
    greeting: "Hello",
    description: "Manage your profile and account security here.",
    emailUnverified: "Email not verified",
    emailUnverifiedDescription:
      "Verification makes account recovery and important notices more reliable.",
    sending: "Sending…",
    resend: "Resend email",
    verificationSent: "The verification email has been sent again.",
    emailFailed: "Email delivery failed",
    entitlementFailed: "Subscription benefits could not be loaded",
    subscription: "Current subscription",
    loading: "Loading benefits…",
    noPlan: "No plan",
    viewPlans: "View plans",
    used: "used",
    remaining: "Remaining data",
    uploadDownload: "Upload / download",
    expires: "Expires",
    nextReset: "Next reset",
    speed: "Peak speed",
    unlimitedSpeed: "Unlimited",
    devices: "Devices",
    unlimitedDevices: "Unlimited",
    deviceUnit: "devices",
    empty:
      "This account has no subscription benefits yet. Data and validity will appear after you activate a plan.",
    accountEmail: "Account email",
    verified: "Verified",
    awaitingVerification: "Awaiting verification",
    identity: "Account role",
    user: "User",
    identityDescription: "Permissions are controlled by system roles",
    joined: "Joined",
    account: "SinX Cloud account"
  }
};

export function AccountOverviewPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);
  const [entitlement, setEntitlement] =
    useState<SubscriptionEntitlement | null>(null);
  const [entitlementLoading, setEntitlementLoading] = useState(true);

  useEffect(() => {
    let active = true;
    graphQl<{ viewerEntitlement: SubscriptionEntitlement | null }>(
      accessToken,
      `query AccountSnapshot {
        viewerEntitlement {
          id
          planId
          planName
          state
          transferLimitBytes
          uploadedBytes
          downloadedBytes
          usedBytes
          remainingBytes
          usagePercent
          speedLimitMbps
          deviceLimit
          resetPolicy
          startsAt
          expiresAt
          nextResetAt
        }
      }`
    )
      .then((result) => {
        if (active) {
          setEntitlement(result.viewerEntitlement);
        }
      })
      .catch((caught) => {
        if (active) {
          setError(
            caught instanceof ApiError
              ? caught.message
              : labels.entitlementFailed
          );
        }
      })
      .finally(() => {
        if (active) {
          setEntitlementLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [accessToken]);

  async function resendVerification() {
    setSending(true);
    setNotice("");
    setError("");
    try {
      await requestEmailVerification(accessToken);
      setNotice(labels.verificationSent);
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : labels.emailFailed
      );
    } finally {
      setSending(false);
    }
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Account</p>
        <h1>{labels.greeting}, {viewer.displayName}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      {!viewer.emailVerified && (
        <section className="account-alert">
          <div>
            <strong>{labels.emailUnverified}</strong>
            <p>{labels.emailUnverifiedDescription}</p>
          </div>
          <button
            className="secondary-button compact-button"
            disabled={sending}
            onClick={resendVerification}
          >
            {sending ? labels.sending : labels.resend}
          </button>
        </section>
      )}
      {notice && <p className="account-inline-message success">{notice}</p>}
      {error && <p className="error-message">{error}</p>}
      <section className="subscription-overview">
        <div className="subscription-heading">
          <div>
            <span>{labels.subscription}</span>
            <h2>
              {entitlementLoading
                ? labels.loading
                : entitlement?.planName ?? labels.noPlan}
            </h2>
          </div>
          {entitlement ? (
            <span
              className={`entitlement-state ${entitlement.state.toLowerCase()}`}
            >
              {entitlementStateLabel(entitlement.state, language)}
            </span>
          ) : (
            <AppLink className="secondary-button compact-link" href="/plans">
              {labels.viewPlans}
            </AppLink>
          )}
        </div>
        {entitlement && (
          <>
            <div className="traffic-usage">
              <div>
                <strong>{formatBytes(entitlement.usedBytes)}</strong>
                <span>
                  {labels.used} / {formatBytes(entitlement.transferLimitBytes)}
                </span>
              </div>
              <strong>{entitlement.usagePercent.toFixed(1)}%</strong>
            </div>
            <div className="traffic-progress" aria-hidden="true">
              <span
                style={{
                  width: `${Math.min(100, entitlement.usagePercent)}%`
                }}
              />
            </div>
            <dl className="subscription-facts">
              <div>
                <dt>{labels.remaining}</dt>
                <dd>{formatBytes(entitlement.remainingBytes)}</dd>
              </div>
              <div>
                <dt>{labels.uploadDownload}</dt>
                <dd>
                  {formatBytes(entitlement.uploadedBytes)} /{" "}
                  {formatBytes(entitlement.downloadedBytes)}
                </dd>
              </div>
              <div>
                <dt>{labels.expires}</dt>
                <dd>{formatDateTime(entitlement.expiresAt, language)}</dd>
              </div>
              <div>
                <dt>{labels.nextReset}</dt>
                <dd>
                  {entitlement.nextResetAt
                    ? formatDateTime(entitlement.nextResetAt, language)
                    : trafficResetLabel(entitlement.resetPolicy, language)}
                </dd>
              </div>
              <div>
                <dt>{labels.speed}</dt>
                <dd>
                  {entitlement.speedLimitMbps
                    ? `${entitlement.speedLimitMbps} Mbps`
                    : labels.unlimitedSpeed}
                </dd>
              </div>
              <div>
                <dt>{labels.devices}</dt>
                <dd>
                  {entitlement.deviceLimit
                    ? `${entitlement.deviceLimit} ${labels.deviceUnit}`
                    : labels.unlimitedDevices}
                </dd>
              </div>
            </dl>
          </>
        )}
        {!entitlementLoading && !entitlement && (
          <p className="subscription-empty-copy">
            {labels.empty}
          </p>
        )}
      </section>
      <section className="account-summary-grid">
        <article className="account-summary-card">
          <span>{labels.accountEmail}</span>
          <strong>{viewer.email}</strong>
          <small>
            {viewer.emailVerified
              ? labels.verified
              : labels.awaitingVerification}
          </small>
        </article>
        <article className="account-summary-card">
          <span>{labels.identity}</span>
          <strong>{labels.user}</strong>
          <small>{labels.identityDescription}</small>
        </article>
        <article className="account-summary-card">
          <span>{labels.joined}</span>
          <strong>{formatDate(viewer.createdAt, language)}</strong>
          <small>{labels.account}</small>
        </article>
      </section>
    </AppShell>
  );
}
