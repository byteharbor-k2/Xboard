import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { AppLink } from "../components/AppLink";
import { AppShell } from "../components/AppShell";
import { fetchViewerCommissionData } from "../lib/commissions";
import { formatMinorMoney } from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const PAGE_SIZE = 20;

const copy = {
  "zh-CN": {
    title: "佣金记录",
    description: "查看佣金策略、待确认金额和已实际入账的记录。",
    refresh: "刷新佣金数据",
    refreshing: "正在刷新…",
    rate: "当前有效比例",
    pending: "待确认",
    queued: "已确认待入账",
    earned: "累计已入账",
    balance: "当前站点余额",
    firstOnly: "佣金仅由被邀请用户首次支付产生。",
    eachPayment: "符合条件的被邀请用户支付可产生佣金。",
    automatic: "订单完成 3 天后自动确认并入账。",
    manual: "自动确认已关闭，待确认金额需由管理员手动确认。",
    destination: "佣金确认后直接记入普通站点余额，可用于后续订单结算；不提供提现，也没有独立佣金钱包。站点余额可能已经用于购买，因此它与累计已入账佣金是不同口径。",
    logs: "已入账记录",
    loading: "正在加载佣金记录…",
    failed: "佣金记录加载失败。",
    retry: "重试",
    empty: "暂无已入账佣金记录。",
    previous: "上一页",
    next: "下一页",
    count: (page: number, pages: number, count: number) => `第 ${page} / ${pages} 页，共 ${count} 条`,
    order: "订单号",
    orderAmount: "订单金额",
    base: "返佣基数",
    amount: "佣金金额",
    level: "层级",
    time: "入账时间",
    zeroAmountNote: "返佣基数按余额抵扣前的订单金额计算；即使订单金额为 ¥0（例如由余额全额支付），仍可能依据正数返佣基数产生佣金。",
    invites: "查看邀请与佣金概览",
  },
  "en-US": {
    title: "Commission records",
    description: "Review the effective commission policy, pending amounts, and actual credits.",
    refresh: "Refresh commission data",
    refreshing: "Refreshing…",
    rate: "Effective rate",
    pending: "Pending confirmation",
    queued: "Confirmed, awaiting credit",
    earned: "Total credited",
    balance: "Current site balance",
    firstOnly: "Commission is generated only by an invitee's first payment.",
    eachPayment: "Eligible payments by invitees may generate commission.",
    automatic: "Commission is automatically confirmed and credited three days after order completion.",
    manual: "Automatic confirmation is disabled; pending commission needs manual admin confirmation.",
    destination: "Confirmed commission is credited to the ordinary site balance for future orders. Withdrawals and a separate commission wallet are not available. The balance may already have been spent, so it is not the same measure as total commission credited.",
    logs: "Credited records",
    loading: "Loading commission records…",
    failed: "Could not load commission records.",
    retry: "Retry",
    empty: "No commission credits yet.",
    previous: "Previous",
    next: "Next",
    count: (page: number, pages: number, count: number) => `Page ${page} of ${pages}, ${count} entries`,
    order: "Order reference",
    orderAmount: "Order amount",
    base: "Commission base",
    amount: "Commission amount",
    level: "Level",
    time: "Credited at",
    zeroAmountNote: "The commission base is calculated before balance deductions. An order amount of ¥0 (for example, an order fully paid by balance) can still earn commission when its commission base is positive.",
    invites: "View invitation and commission overview",
  }
};

function formatDate(value: string, locale: "zh-CN" | "en-US") {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function AccountCommissionsPage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken) ?? "";
  const viewerId = useAuthStore((state) => state.viewer?.id);
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["viewer-commission", viewerId, page, PAGE_SIZE],
    queryFn: () => fetchViewerCommissionData(accessToken, page, PAGE_SIZE),
    enabled: Boolean(viewerId && accessToken),
    retry: false,
    refetchOnMount: "always"
  });
  const ready = query.isSuccess && !query.isFetching;
  const data = ready ? query.data : undefined;
  const logPage = data?.logs;
  const pages = logPage ? Math.max(Math.ceil(logPage.totalCount / logPage.limit), 1) : 1;

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">REFERRALS</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
        <div className="account-ledger-actions">
          <button className="freedom-button primary" disabled={query.isFetching || !viewerId} onClick={() => void query.refetch()} type="button">
            {query.isFetching ? labels.refreshing : labels.refresh}
          </button>
          <AppLink className="order-number-link" href="/account/invitations">{labels.invites}</AppLink>
        </div>
      </header>
      {query.isError && (
        <p className="error-message" role="alert">
          {query.error instanceof Error ? query.error.message : labels.failed}{" "}
          <button onClick={() => void query.refetch()} type="button">{labels.retry}</button>
        </p>
      )}
      {query.isPending && <div className="user-empty-state compact"><strong>{labels.loading}</strong></div>}
      {data && logPage && (
        <>
          <section className="referral-summary-grid balance-summary-grid" aria-label={labels.title}>
            <article className="panel"><span>{labels.rate}</span><strong>{data.summary.effectiveRatePercent}%</strong></article>
            <article className="panel"><span>{labels.pending}</span><strong>{formatMinorMoney(data.summary.pendingMinor, "CNY", language)}</strong></article>
            <article className="panel"><span>{labels.queued}</span><strong>{formatMinorMoney(data.summary.confirmedPendingMinor, "CNY", language)}</strong></article>
            <article className="panel"><span>{labels.earned}</span><strong>{formatMinorMoney(data.summary.earnedMinor, "CNY", language)}</strong></article>
            <article className="panel"><span>{labels.balance}</span><strong>{formatMinorMoney(data.balanceSummary.balanceMinor, "CNY", language)}</strong></article>
          </section>
          <div className="traffic-retention-notice">
            <span aria-hidden="true">i</span>
            {data.summary.firstPaymentOnly ? labels.firstOnly : labels.eachPayment}{" "}
            {data.summary.autoConfirmEnabled ? labels.automatic : labels.manual}{" "}
            {labels.destination}
          </div>
          <section className="panel user-record-panel balance-ledger-panel">
            <h2>{labels.logs}</h2>
            <div className="traffic-retention-notice"><span aria-hidden="true">i</span>{labels.zeroAmountNote}</div>
            <div className="user-table-wrap">
              <table className="user-data-table">
                <thead><tr><th>{labels.order}</th><th>{labels.orderAmount}</th><th>{labels.base}</th><th>{labels.amount}</th><th>{labels.level}</th><th>{labels.time}</th></tr></thead>
                <tbody>
                  {logPage.items.map((log) => (
                    <tr key={log.id}>
                      <td><span className="order-number">{log.tradeNo}</span></td>
                      <td>{formatMinorMoney(log.orderAmountMinor, "CNY", language)}</td>
                      <td>{formatMinorMoney(log.commissionBaseMinor, "CNY", language)}</td>
                      <td className="balance-amount credit">{formatMinorMoney(log.amountMinor, "CNY", language)}</td>
                      <td>{log.level}</td>
                      <td>{formatDate(log.createdAt, language)}</td>
                    </tr>
                  ))}
                  {logPage.items.length === 0 && <tr><td colSpan={6}><div className="user-empty-state compact"><strong>{labels.empty}</strong></div></td></tr>}
                </tbody>
              </table>
            </div>
            <div className="account-ledger-pagination">
              <button disabled={logPage.page <= 0 || query.isFetching} onClick={() => setPage((current) => Math.max(current - 1, 0))} type="button">{labels.previous}</button>
              <button disabled={(logPage.page + 1) * logPage.limit >= logPage.totalCount || query.isFetching} onClick={() => setPage((current) => current + 1)} type="button">{labels.next}</button>
              <span className="muted">{labels.count(logPage.page + 1, pages, logPage.totalCount)}</span>
            </div>
          </section>
        </>
      )}
    </AppShell>
  );
}
