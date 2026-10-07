import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import { AppShell } from "../components/AppShell";
import { AppLink } from "../components/AppLink";
import { copyText } from "../lib/clipboard";
import { fetchViewerCommissionData } from "../lib/commissions";
import {
  createInvitationCode,
  fetchViewerInvitations,
  type InvitationCode
} from "../lib/invitations";
import { formatMinorMoney } from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const copy = {
  "zh-CN": {
    eyebrow: "REFERRALS",
    title: "我的邀请",
    description: "创建邀请码，邀请朋友注册并查看已建立的邀请关系。",
    create: "创建邀请码",
    creating: "创建中…",
    createSuccess: "邀请码已创建。",
    createFailed: "邀请码创建失败，请稍后重试。",
    retry: "重试",
    loadCommissionFailed: "佣金数据加载失败，请稍后重试。",
    commissionTitle: "邀请佣金",
    commissionDetails: "查看完整佣金记录",
    effectiveRate: "当前有效佣金比例",
    firstPaymentOnly: "仅被邀请用户首次支付产生佣金",
    everyPayment: "被邀请用户每次符合条件的支付均可产生佣金",
    pending: "待确认佣金",
    queued: "已确认，等待自动入账",
    earned: "累计已入账",
    balance: "当前站点余额",
    autoConfirm: "佣金将在订单完成 3 天后自动确认并入账。",
    manualConfirmDisabled: "自动确认已关闭；待确认佣金需由管理员手动确认，请联系站点客服。",
    siteBalance: "佣金自动发放至站点余额，可用于后续订单结算；不提供提现。",
    payoutLogs: "佣金入账记录",
    orderAmount: "订单金额",
    commissionBase: "返佣基数",
    commissionAmount: "佣金金额",
    level: "层级",
    date: "时间",
    loadingCommission: "正在加载佣金数据…",
    commissionEmpty: "暂无佣金入账记录。",
    previous: "上一页",
    next: "下一页",
    pageCount: (page: number, pages: number, count: number) => `第 ${page} / ${pages} 页，共 ${count} 条`,
    rateTypeSystem: "按全局设置",
    rateTypePeriodic: "周期佣金设置",
    rateTypeOneTime: "一次性佣金设置",
    copiedCode: "邀请码已复制",
    copiedLink: "注册链接已复制",
    copyFailed: "复制失败，请重试。",
    copyCode: "复制邀请码",
    copyLink: "复制注册链接",
    availableCodes: "可用邀请码",
    invitedUsers: "已邀请用户",
    codes: "可用邀请码",
    createdAt: "创建时间",
    usage: "使用方式",
    action: "操作",
    reusable: "可重复使用",
    singleUse: "成功注册后失效",
    limit: (limit: number) => `最多可同时持有 ${limit} 个可用邀请码。`,
    generationDisabled: "当前邀请码生成上限为 0，暂不能创建邀请码。",
    expiryNever: "邀请码不会过期，可重复用于注册。",
    expiryOnce: "邀请码成功用于一次注册后即失效。",
    loading: "正在加载邀请码…",
    loadFailed: "邀请码加载失败，请稍后重试。",
    empty: "暂无可用邀请码",
    emptyDescription: "创建邀请码后，可在这里复制邀请码或专属注册链接。"
  },
  "en-US": {
    eyebrow: "REFERRALS",
    title: "My invitations",
    description: "Create invitation codes and see the accounts registered through them.",
    create: "Create invitation code",
    creating: "Creating…",
    createSuccess: "Invitation code created.",
    createFailed: "Could not create an invitation code. Try again later.",
    retry: "Retry",
    loadCommissionFailed: "Could not load commission data. Try again later.",
    commissionTitle: "Referral commission",
    commissionDetails: "View full commission records",
    effectiveRate: "Effective commission rate",
    firstPaymentOnly: "Commission applies only to the invitee's first payment",
    everyPayment: "Eligible payments by invitees may earn commission",
    pending: "Pending confirmation",
    queued: "Confirmed, queued for automatic credit",
    earned: "Total credited",
    balance: "Current site balance",
    autoConfirm: "Commission is automatically confirmed and credited 3 days after order completion.",
    manualConfirmDisabled: "Automatic confirmation is disabled; pending commission requires manual admin review. Contact site support.",
    siteBalance: "Commission is credited to your site balance for future orders. Withdrawals are not available.",
    payoutLogs: "Commission credit history",
    orderAmount: "Order amount",
    commissionBase: "Commission base",
    commissionAmount: "Commission amount",
    level: "Level",
    date: "Date",
    loadingCommission: "Loading commission data…",
    commissionEmpty: "No commission credits yet.",
    previous: "Previous",
    next: "Next",
    pageCount: (page: number, pages: number, count: number) => `Page ${page} of ${pages}, ${count} entries`,
    rateTypeSystem: "Global rate",
    rateTypePeriodic: "Periodic commission rate",
    rateTypeOneTime: "One-time commission rate",
    copiedCode: "Invitation code copied",
    copiedLink: "Registration link copied",
    copyFailed: "Copy failed. Please try again.",
    copyCode: "Copy code",
    copyLink: "Copy registration link",
    availableCodes: "Available codes",
    invitedUsers: "Invited users",
    codes: "Available invitation codes",
    createdAt: "Created",
    usage: "Use",
    action: "Actions",
    reusable: "Reusable",
    singleUse: "One successful signup",
    limit: (limit: number) => `You can hold up to ${limit} available invitation codes at a time.`,
    generationDisabled: "The invitation-code limit is 0, so new codes cannot be created.",
    expiryNever: "Codes do not expire and can be reused for registration.",
    expiryOnce: "A code becomes unavailable after one successful registration.",
    loading: "Loading invitation codes…",
    loadFailed: "Could not load invitation codes. Try again later.",
    empty: "No available invitation codes",
    emptyDescription: "Create a code to copy it or its personal registration link here."
  }
};

function formatDate(value: string, language: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(language, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(date);
}

export function InvitationsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken) ?? "";
  const viewer = useAuthStore((state) => state.viewer);
  const invitationQueryKey = ["viewer-invitations", viewer?.id] as const;
  const queryClient = useQueryClient();
  const [message, setMessage] = useState("");
  const [commissionPage, setCommissionPage] = useState(0);
  const commissionQueryKey = ["viewer-commission", viewer?.id, commissionPage] as const;
  const invitations = useQuery({
    queryKey: invitationQueryKey,
    queryFn: () => fetchViewerInvitations(accessToken),
    enabled: Boolean(viewer?.id && accessToken),
    retry: false,
    refetchOnMount: "always"
  });
  const commissions = useQuery({
    queryKey: commissionQueryKey,
    queryFn: () => fetchViewerCommissionData(accessToken, commissionPage),
    enabled: Boolean(viewer?.id && accessToken),
    retry: false,
    refetchOnMount: "always"
  });
  const creation = useMutation({
    mutationFn: () => createInvitationCode(accessToken),
    onSuccess: async () => {
      setMessage(labels.createSuccess);
      await queryClient.invalidateQueries({ queryKey: invitationQueryKey });
    },
    onError: (error: Error) => setMessage(error.message || labels.createFailed)
  });

  const summary = invitations.isSuccess && !invitations.isFetching
    ? invitations.data
    : undefined;
  const commission = commissions.data;
  // Suppress cached monetary values while refreshing: commission settlement can
  // credit the balance between visits, so the account's persisted viewer is not authoritative.
  const commissionReady = commissions.isSuccess && !commissions.isFetching;
  const atLimit = summary
    ? summary.generationLimit <= 0
      || summary.availableCodeCount >= summary.generationLimit
    : false;

  async function copyValue(value: string, success: string) {
    try {
      await copyText(value);
      setMessage(success);
    } catch {
      setMessage(labels.copyFailed);
    }
  }

  function registerUrl(code: InvitationCode) {
    return `${window.location.origin}/register?invite=${encodeURIComponent(code.code)}`;
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">{labels.eyebrow}</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
        <button
          className="freedom-button primary"
          disabled={!summary || atLimit || creation.isPending || invitations.isError || invitations.isFetching}
          onClick={() => {
            setMessage("");
            creation.mutate();
          }}
          type="button"
        >
          {creation.isPending ? labels.creating : labels.create}
        </button>
      </header>
      <section className="referral-summary-grid">
        <article className="panel">
          <span>{labels.availableCodes}</span>
          <strong>{summary?.availableCodeCount ?? "—"}</strong>
        </article>
        <article className="panel">
          <span>{labels.invitedUsers}</span>
          <strong>{summary?.invitedUserCount ?? "—"}</strong>
        </article>
      </section>
      <section className="panel user-record-panel">
        <h2>{labels.codes}</h2>
        {summary && (
          <div className="traffic-retention-notice">
            <span aria-hidden="true">i</span>
            {summary.generationLimit <= 0
              ? labels.generationDisabled
              : labels.limit(summary.generationLimit)}{" "}
            {summary.neverExpire ? labels.expiryNever : labels.expiryOnce}
          </div>
        )}
        {invitations.isError && <p className="error-message">{invitations.error instanceof Error ? invitations.error.message : labels.loadFailed}</p>}
        {message && <p role="status">{message}</p>}
        <div className="user-table-wrap">
          <table className="user-data-table">
            <thead>
              <tr>
                <th>{labels.codes}</th>
                <th>{labels.createdAt}</th>
                <th>{labels.usage}</th>
                <th>{labels.action}</th>
              </tr>
            </thead>
            <tbody>
              {invitations.isFetching && !invitations.isError && (
                <tr>
                  <td colSpan={4}>
                    <div className="user-empty-state compact">
                      <strong>{labels.loading}</strong>
                    </div>
                  </td>
                </tr>
              )}
              {invitations.isError && (
                <tr>
                  <td colSpan={4}>
                    <div className="user-empty-state compact">
                      <strong>{labels.loadFailed}</strong>
                      <button onClick={() => void invitations.refetch()} type="button">{labels.retry}</button>
                    </div>
                  </td>
                </tr>
              )}
              {invitations.isSuccess && !invitations.isFetching && (summary?.codes.length ?? 0) > 0 &&
                summary!.codes.map((code) => (
                  <tr key={code.id}>
                    <td><span className="order-number">{code.code}</span></td>
                    <td>{formatDate(code.createdAt, language)}</td>
                    <td>{summary!.neverExpire ? labels.reusable : labels.singleUse}</td>
                    <td>
                      <button
                        className="order-number-link"
                        onClick={() => void copyValue(code.code, labels.copiedCode)}
                        type="button"
                      >
                        {labels.copyCode}
                      </button>{" "}
                      <button
                        className="order-number-link"
                        onClick={() => void copyValue(registerUrl(code), labels.copiedLink)}
                        type="button"
                      >
                        {labels.copyLink}
                      </button>
                    </td>
                  </tr>
                ))}
              {invitations.isSuccess && !invitations.isFetching && (summary?.codes.length ?? 0) === 0 && (
                <tr>
                  <td colSpan={4}>
                    <div className="user-empty-state compact">
                      <span aria-hidden="true">＋</span>
                      <strong>{labels.empty}</strong>
                      <p>{labels.emptyDescription}</p>
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel user-record-panel">
        <div className="account-ledger-actions">
          <h2>{labels.commissionTitle}</h2>
          <AppLink className="order-number-link" href="/account/commissions">{labels.commissionDetails}</AppLink>
        </div>
        {commissions.isPending || (commissions.isFetching && !commissionReady) ? (
          <div className="user-empty-state compact"><strong>{labels.loadingCommission}</strong></div>
        ) : null}
        {commissions.isError ? (
          <p className="error-message">
            {commissions.error instanceof Error
              ? commissions.error.message
              : labels.loadCommissionFailed}
            {" "}<button onClick={() => void commissions.refetch()} type="button">{labels.retry}</button>
          </p>
        ) : null}
        {commissionReady && commission ? (
          <>
            <div className="referral-summary-grid">
              <article className="panel">
                <span>{labels.effectiveRate}</span>
                <strong>{commission.summary.effectiveRatePercent}%</strong>
                <small>
                  {commission.summary.commissionType === 1
                    ? labels.rateTypePeriodic
                    : commission.summary.commissionType === 2
                      ? labels.rateTypeOneTime
                      : labels.rateTypeSystem}
                </small>
              </article>
              <article className="panel">
                <span>{labels.pending}</span>
                 <strong>{formatMinorMoney(commission.summary.pendingMinor, "CNY", language)}</strong>
              </article>
              <article className="panel">
                <span>{labels.queued}</span>
                 <strong>{formatMinorMoney(commission.summary.confirmedPendingMinor, "CNY", language)}</strong>
              </article>
              <article className="panel">
                <span>{labels.earned}</span>
                 <strong>{formatMinorMoney(commission.summary.earnedMinor, "CNY", language)}</strong>
              </article>
              <article className="panel">
                <span>{labels.balance}</span>
                 <strong>{formatMinorMoney(commission.balanceMinor, "CNY", language)}</strong>
              </article>
            </div>
            <div className="traffic-retention-notice">
              <span aria-hidden="true">i</span>
              {commission.summary.firstPaymentOnly
                ? labels.firstPaymentOnly
                : labels.everyPayment}{" "}
              {commission.summary.autoConfirmEnabled
                ? labels.autoConfirm
                : labels.manualConfirmDisabled}{" "}
              {labels.siteBalance}
            </div>
            <h3>{labels.payoutLogs}</h3>
            <div className="user-table-wrap">
              <table className="user-data-table">
                <thead>
                  <tr>
                    <th>{labels.orderAmount}</th>
                    <th>{labels.commissionBase}</th>
                    <th>{labels.commissionAmount}</th>
                    <th>{labels.level}</th>
                    <th>{labels.date}</th>
                  </tr>
                </thead>
                <tbody>
                  {commission.logs.items.map((log) => (
                    <tr key={log.id}>
                      <td>{formatMinorMoney(log.orderAmountMinor, "CNY", language)}</td>
                      <td>{formatMinorMoney(log.commissionBaseMinor, "CNY", language)}</td>
                      <td>{formatMinorMoney(log.amountMinor, "CNY", language)}</td>
                      <td>{log.level}</td>
                      <td>{formatDate(log.createdAt, language)}</td>
                    </tr>
                  ))}
                  {commission.logs.items.length === 0 ? (
                    <tr><td colSpan={5}><div className="user-empty-state compact"><strong>{labels.commissionEmpty}</strong></div></td></tr>
                  ) : null}
                </tbody>
              </table>
            </div>
            <div style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap", marginTop: 14 }}>
              <button
                disabled={commissionPage <= 0 || commissions.isFetching}
                onClick={() => setCommissionPage((current) => Math.max(current - 1, 0))}
                type="button"
              >
                {labels.previous}
              </button>
              <button
                disabled={(commissionPage + 1) * commission.logs.limit >= commission.logs.totalCount || commissions.isFetching}
                onClick={() => setCommissionPage((current) => current + 1)}
                type="button"
              >
                {labels.next}
              </button>
              <span className="muted">
                {labels.pageCount(
                  commission.logs.page + 1,
                  Math.max(Math.ceil(commission.logs.totalCount / commission.logs.limit), 1),
                  commission.logs.totalCount
                )}
              </span>
            </div>
          </>
        ) : null}
      </section>
    </AppShell>
  );
}
