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
import type { SubscriptionEntitlement } from "../types";

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "long"
  }).format(new Date(value));
}

export function AccountOverviewPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
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
              : "订阅权益加载失败"
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
      setNotice("验证邮件已重新发送。");
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : "邮件发送失败"
      );
    } finally {
      setSending(false);
    }
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">Account</p>
        <h1>你好，{viewer.displayName}</h1>
        <p className="muted">在这里管理账户资料与安全状态。</p>
      </header>
      {!viewer.emailVerified && (
        <section className="account-alert">
          <div>
            <strong>邮箱尚未验证</strong>
            <p>完成验证后，账户恢复与重要通知会更加可靠。</p>
          </div>
          <button
            className="secondary-button compact-button"
            disabled={sending}
            onClick={resendVerification}
          >
            {sending ? "正在发送…" : "重新发送邮件"}
          </button>
        </section>
      )}
      {notice && <p className="account-inline-message success">{notice}</p>}
      {error && <p className="error-message">{error}</p>}
      <section className="subscription-overview">
        <div className="subscription-heading">
          <div>
            <span>当前订阅</span>
            <h2>
              {entitlementLoading
                ? "正在读取权益…"
                : entitlement?.planName ?? "暂无套餐"}
            </h2>
          </div>
          {entitlement ? (
            <span
              className={`entitlement-state ${entitlement.state.toLowerCase()}`}
            >
              {entitlementStateLabel(entitlement.state)}
            </span>
          ) : (
            <AppLink className="secondary-button compact-link" href="/plans">
              查看套餐
            </AppLink>
          )}
        </div>
        {entitlement && (
          <>
            <div className="traffic-usage">
              <div>
                <strong>{formatBytes(entitlement.usedBytes)}</strong>
                <span>
                  已使用 / {formatBytes(entitlement.transferLimitBytes)}
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
                <dt>剩余流量</dt>
                <dd>{formatBytes(entitlement.remainingBytes)}</dd>
              </div>
              <div>
                <dt>上传 / 下载</dt>
                <dd>
                  {formatBytes(entitlement.uploadedBytes)} /{" "}
                  {formatBytes(entitlement.downloadedBytes)}
                </dd>
              </div>
              <div>
                <dt>有效期至</dt>
                <dd>{formatDateTime(entitlement.expiresAt)}</dd>
              </div>
              <div>
                <dt>下次重置</dt>
                <dd>
                  {entitlement.nextResetAt
                    ? formatDateTime(entitlement.nextResetAt)
                    : trafficResetLabel(entitlement.resetPolicy)}
                </dd>
              </div>
              <div>
                <dt>峰值速率</dt>
                <dd>
                  {entitlement.speedLimitMbps
                    ? `${entitlement.speedLimitMbps} Mbps`
                    : "不限速"}
                </dd>
              </div>
              <div>
                <dt>设备数量</dt>
                <dd>
                  {entitlement.deviceLimit
                    ? `${entitlement.deviceLimit} 台`
                    : "不限制"}
                </dd>
              </div>
            </dl>
          </>
        )}
        {!entitlementLoading && !entitlement && (
          <p className="subscription-empty-copy">
            当前账户还没有订阅权益，开通套餐后这里会显示流量和有效期。
          </p>
        )}
      </section>
      <section className="account-summary-grid">
        <article className="account-summary-card">
          <span>账户邮箱</span>
          <strong>{viewer.email}</strong>
          <small>{viewer.emailVerified ? "已验证" : "等待验证"}</small>
        </article>
        <article className="account-summary-card">
          <span>账户身份</span>
          <strong>用户</strong>
          <small>权限由系统角色控制</small>
        </article>
        <article className="account-summary-card">
          <span>加入时间</span>
          <strong>{formatDate(viewer.createdAt)}</strong>
          <small>SinX Cloud 账户</small>
        </article>
      </section>
    </AppShell>
  );
}
