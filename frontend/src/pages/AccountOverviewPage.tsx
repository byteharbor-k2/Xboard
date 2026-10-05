import { useEffect, useRef, useState } from "react";
import { MarkdownContent } from "../components/MarkdownContent";

import { AppLink } from "../components/AppLink";
import { AppShell } from "../components/AppShell";
import { SubscriptionClientDialog } from "../components/SubscriptionClientDialog";
import {
  AnnouncementCarousel,
  type PortalAnnouncement
} from "../components/AnnouncementCarousel";
import { ViewerNodePanel } from "../components/ViewerNodePanel";
import {
  ApiError,
  graphQl
} from "../lib/http";
import {
  entitlementStateLabel,
  formatBytes,
  formatDateTime,
  formatMoney,
  trafficResetLabel
} from "../lib/subscription";
import { fetchViewerNotices, fetchViewerNoticeImages } from "../lib/content";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";
import type { SubscriptionEntitlement } from "../types";

const copy = {
  "zh-CN": {
    kicker: "PRIVATE NETWORK",
    greeting: "欢迎回来",
    description: "当前订阅、用量与可用节点，一眼掌握。",
    announcement: "公告",
    previousAnnouncement: "上一篇公告",
    nextAnnouncement: "下一篇公告",
    viewNotice: "查看详情",
    close: "关闭",
    entitlementFailed: "订阅权益加载失败",
    subscription: "当前订阅",
    loading: "正在读取权益…",
    noPlan: "暂无套餐",
    viewPlans: "查看套餐",
    used: "已使用",
    remaining: "剩余流量",
    uploadDownload: "上传 / 下载",
    expires: "有效期至",
    trial: "免费试用",
    trialExpires: "试用有效期至",
    nextReset: "下次重置",
    speed: "峰值速率",
    unlimitedSpeed: "不限速",
    balance: "账户余额",
    empty:
      "当前账户还没有订阅权益，开通套餐后这里会显示流量和有效期。",
    quickStart: "快速开始使用",
    quickStartHint:
      "订阅地址不再直接显示。选择客户端后复制链接或扫码导入；链接本身就是凭据，请勿分享。",
    chooseClient: "订阅导入"
  },
  "en-US": {
    kicker: "PRIVATE NETWORK",
    greeting: "Welcome back",
    description: "Your subscription, usage, and available nodes at a glance.",
    announcement: "Announcement",
    previousAnnouncement: "Previous announcement",
    nextAnnouncement: "Next announcement",
    viewNotice: "View details",
    close: "Close",
    entitlementFailed: "Subscription benefits could not be loaded",
    subscription: "Current subscription",
    loading: "Loading benefits…",
    noPlan: "No plan",
    viewPlans: "View plans",
    used: "used",
    remaining: "Remaining data",
    uploadDownload: "Upload / download",
    expires: "Expires",
    trial: "Free trial",
    trialExpires: "Trial expires",
    nextReset: "Next reset",
    speed: "Peak speed",
    unlimitedSpeed: "Unlimited",
    balance: "Account balance",
    empty:
      "This account has no subscription benefits yet. Data and validity will appear after you activate a plan.",
    quickStart: "Quick start",
    quickStartHint:
      "The address is no longer shown here. Choose your client, then copy the link or scan its QR code. The link is the credential itself — do not share it.",
    chooseClient: "Import subscription"
  }
};

export function AccountOverviewPage() {
  const accessToken = useAuthStore((state) => state.accessToken)!;
  const viewer = useAuthStore((state) => state.viewer)!;
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const [error, setError] = useState("");
  const [entitlement, setEntitlement] =
    useState<SubscriptionEntitlement | null>(null);
  const [entitlementLoading, setEntitlementLoading] = useState(true);
  const [subscriptionUrl, setSubscriptionUrl] = useState("");
  const [siteName, setSiteName] = useState("");
  const [balanceMinor, setBalanceMinor] = useState<string>();
  const [clientChooserOpen, setClientChooserOpen] = useState(false);
  const [announcements, setAnnouncements] = useState<PortalAnnouncement[]>([]);
  const [selectedNotice, setSelectedNotice] = useState<{ id: string; title: string; content: string; imgUrl?: string | null } | null>(null);
  const [noticeDetails, setNoticeDetails] = useState<Record<string, { id: string; title: string; content: string; imgUrl?: string | null }>>({});
  const noticeOpener = useRef<HTMLElement | null>(null);
  const noticeDialog = useRef<HTMLElement | null>(null);

  function closeNotice() {
    setSelectedNotice(null);
    requestAnimationFrame(() => noticeOpener.current?.focus());
  }

  useEffect(() => {
    let active = true;
    fetchViewerNotices(accessToken)
      .then((notices) => {
        if (active) {
          setNoticeDetails(Object.fromEntries(notices.map(notice => [notice.id, notice])));
          setAnnouncements(
            notices.map((notice) => ({
              id: notice.id,
              title: notice.title,
              // The carousel shows one line; the full content lives in the
              // notice itself and the first line carries the gist.
              summary: notice.content.replace(/!\[[^\]]*\]\([^)]*\)/g, "").replace(/[#>*_`~\[\]()]/g, "").replace(/\s+/g, " ").trim().slice(0, 140) || notice.title,
              publishedAt: formatDateTime(notice.publishedAt, language)
            }))
          );
          void fetchViewerNoticeImages(accessToken).then(images => {
            if (!active) return;
            setNoticeDetails(current => Object.fromEntries(Object.entries(current).map(([id, notice]) => [id, { ...notice, imgUrl: images[id] ?? null }])));
            setAnnouncements(current => current.map(notice => ({ ...notice, imgUrl: images[notice.id] ?? null })));
          }).catch(() => { /* Older GraphQL deployments omit the optional image field. */ });
        }
      })
      .catch(() => {
        // A failed carousel is not the dashboard's headline error; the
        // empty state (carousel hidden) is quieter and honest.
      });
    return () => {
      active = false;
    };
  }, [accessToken, language]);

  useEffect(() => {
    if (!selectedNotice) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeNotice();
    };
    window.addEventListener("keydown", closeOnEscape);
    requestAnimationFrame(() => noticeDialog.current?.focus());
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [selectedNotice]);

  useEffect(() => {
    let active = true;
    graphQl<{
      siteName: string;
      viewer: { balanceMinor: string };
      viewerEntitlement: SubscriptionEntitlement | null;
      viewerSubscriptionUrl: string | null;
    }>(
      accessToken,
      `query AccountSnapshot {
        siteName
        viewer { balanceMinor }
        viewerSubscriptionUrl
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
          resetPolicy
          startsAt
          expiresAt
          nextResetAt
          isTrial
        }
      }`
    )
      .then((result) => {
        if (active) {
          setEntitlement(result.viewerEntitlement);
          setSubscriptionUrl(result.viewerSubscriptionUrl ?? "");
          setSiteName(result.siteName);
          setBalanceMinor(result.viewer.balanceMinor);
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

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.kicker}</p>
        <h1>{labels.greeting}, {viewer.displayName}</h1>
        <p className="muted">{labels.description}</p>
      </header>
      <AnnouncementCarousel
        announcements={announcements}
        label={labels.announcement}
        nextLabel={labels.nextAnnouncement}
        previousLabel={labels.previousAnnouncement}
        onViewDetails={(notice) => { noticeOpener.current = document.activeElement as HTMLElement; setSelectedNotice(noticeDetails[notice.id] ?? null); }}
        detailsLabel={labels.viewNotice}
      />
      {selectedNotice && <div className="notice-dialog-backdrop" role="presentation" onClick={closeNotice}><section ref={noticeDialog} aria-label={selectedNotice.title} aria-modal="true" className="notice-dialog" onClick={event => event.stopPropagation()} role="dialog" tabIndex={-1}><header><h2>{selectedNotice.title}</h2><button aria-label={labels.close} onClick={closeNotice} type="button">×</button></header>{selectedNotice.imgUrl && <img className="notice-detail-image" src={selectedNotice.imgUrl} alt="" />}<MarkdownContent source={selectedNotice.content} /></section></div>}
      {error && <p className="error-message">{error}</p>}
      <section className="user-dashboard-grid">
        <section className="subscription-overview dashboard-subscription-card">
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
              <div className="subscription-state-badges">
                {entitlement.isTrial && (
                  <span className="trial-state-badge">{labels.trial}</span>
                )}
                <span
                  className={`entitlement-state ${entitlement.state.toLowerCase()}`}
                >
                  {entitlementStateLabel(entitlement.state, language)}
                </span>
              </div>
            ) : (
              <AppLink className="secondary-button compact-link" href="/plans">
                {labels.viewPlans}
              </AppLink>
            )}
          </div>
          <p className="muted dashboard-balance">
            {labels.balance}:{" "}
            {balanceMinor === undefined
              ? labels.loading
              : formatMoney(balanceMinor, "CNY", language)}
          </p>
          {entitlement && (
            <>
              <div className="traffic-usage">
                <div>
                  <strong>{formatBytes(entitlement.usedBytes)}</strong>
                  <span>
                    {labels.used} /{" "}
                    {formatBytes(entitlement.transferLimitBytes)}
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
                  <dt>{entitlement.isTrial ? labels.trialExpires : labels.expires}</dt>
                  <dd>{formatDateTime(entitlement.expiresAt, language)}</dd>
                </div>
                {!entitlement.isTrial && (
                  <div>
                    <dt>{labels.nextReset}</dt>
                    <dd>
                      {entitlement.nextResetAt
                        ? formatDateTime(entitlement.nextResetAt, language)
                        : trafficResetLabel(
                            entitlement.resetPolicy,
                            language
                          )}
                    </dd>
                  </div>
                )}
                <div>
                  <dt>{labels.speed}</dt>
                  <dd>
                    {entitlement.speedLimitMbps
                      ? `${entitlement.speedLimitMbps} Mbps`
                      : labels.unlimitedSpeed}
                  </dd>
                </div>
              </dl>
            </>
          )}
          {!entitlementLoading && !entitlement && (
            <p className="subscription-empty-copy">{labels.empty}</p>
          )}
          {subscriptionUrl && (
            <div className="subscription-link">
              <div className="subscription-link-heading">
                <span>{labels.quickStart}</span>
              </div>
              <p className="muted">{labels.quickStartHint}</p>
              <button
                className="primary-button subscription-chooser-trigger"
                onClick={() => setClientChooserOpen(true)}
                type="button"
              >
                {labels.chooseClient}
              </button>
            </div>
          )}
          {clientChooserOpen && subscriptionUrl && (
            <SubscriptionClientDialog
              baseUrl={subscriptionUrl}
              language={language}
              onClose={() => setClientChooserOpen(false)}
              siteName={siteName}
            />
          )}
        </section>
        <ViewerNodePanel key={viewer.id} accessToken={accessToken} language={language} />
      </section>
    </AppShell>
  );
}
