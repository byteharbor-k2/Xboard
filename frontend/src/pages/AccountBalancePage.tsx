import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { AppLink } from "../components/AppLink";
import { AppShell } from "../components/AppShell";
import { fetchViewerBalanceData, type ViewerBalanceLog } from "../lib/balance";
import { formatMinorMoney } from "../lib/subscription";
import { useAuthStore } from "../store/auth";
import { useUserPreferences } from "../store/userPreferences";

const PAGE_SIZE = 20;

const copy = {
  "zh-CN": {
    title: "余额明细",
    description: "查看当前站点余额与实际记录的余额变动。",
    refresh: "刷新余额",
    refreshing: "正在刷新…",
    current: "当前余额",
    opening: "账本启用时的期初余额",
    credits: "记录的余额入账",
    debits: "记录的余额支出",
    since: "账本记录起始时间",
    notRecorded: "未提供",
    openingNote: "期初余额是账本启用时的对账锚点，不是历史交易重建。入账合计包含退款、折余转入、佣金入账和管理员余额调整；当前余额是实时账户余额，不是本页记录的金额合计。",
    logTitle: "余额变动记录",
    loading: "正在加载余额记录…",
    failed: "余额记录加载失败。",
    retry: "重试",
    empty: "暂无余额变动记录。",
    previous: "上一页",
    next: "下一页",
    count: (page: number, pages: number, count: number) => `第 ${page} / ${pages} 页，共 ${count} 条`,
    type: "类型",
    amount: "变动金额",
    after: "变动后余额",
    reference: "关联订单",
    time: "时间",
    types: {
      OPENING_BALANCE: "期初余额",
      ORDER_PAYMENT: "订单余额支付",
      ORDER_REFUND: "订单退款入账",
      SURPLUS_CREDIT: "套餐折余转入",
      COMMISSION_CREDIT: "佣金入账",
      ADMIN_ADJUSTMENT: "管理员余额调整"
    },
    adjustmentCredit: "管理员余额增加",
    adjustmentDebit: "管理员余额扣减",
    noReference: "—",
  },
  "en-US": {
    title: "Balance history",
    description: "Review the live site balance and recorded balance movements.",
    refresh: "Refresh balance",
    refreshing: "Refreshing…",
    current: "Current balance",
    opening: "Opening balance at ledger cutover",
    credits: "Recorded credits",
    debits: "Recorded debits",
    since: "Ledger recorded since",
    notRecorded: "Not provided",
    openingNote: "The opening balance is a ledger-cutover reconciliation anchor, not a reconstruction of historical transactions. Credits include refunds, surplus credits, commission credits, and administrator balance adjustments. The current balance is live account state, not a sum of this page.",
    logTitle: "Balance movements",
    loading: "Loading balance records…",
    failed: "Could not load balance records.",
    retry: "Retry",
    empty: "No balance movements yet.",
    previous: "Previous",
    next: "Next",
    count: (page: number, pages: number, count: number) => `Page ${page} of ${pages}, ${count} entries`,
    type: "Type",
    amount: "Movement",
    after: "Balance after",
    reference: "Order reference",
    time: "Time",
    types: {
      OPENING_BALANCE: "Opening balance",
      ORDER_PAYMENT: "Order balance payment",
      ORDER_REFUND: "Order refund credit",
      SURPLUS_CREDIT: "Plan surplus credit",
      COMMISSION_CREDIT: "Commission credit",
      ADMIN_ADJUSTMENT: "Administrator balance adjustment"
    },
    adjustmentCredit: "Administrator balance increase",
    adjustmentDebit: "Administrator balance decrease",
    noReference: "—",
  }
};

function formatDate(value: string, locale: "zh-CN" | "en-US") {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function AccountBalancePage() {
  const language = useUserPreferences((state) => state.language);
  const labels = copy[language];
  const accessToken = useAuthStore((state) => state.accessToken) ?? "";
  const viewerId = useAuthStore((state) => state.viewer?.id);
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["viewer-balance-ledger", viewerId, page, PAGE_SIZE],
    queryFn: () => fetchViewerBalanceData(accessToken, page, PAGE_SIZE),
    enabled: Boolean(viewerId && accessToken),
    retry: false,
    refetchOnMount: "always"
  });
  const ready = query.isSuccess && !query.isFetching;
  const data = ready ? query.data : undefined;
  const pageData = data?.logs;
  const pages = pageData ? Math.max(Math.ceil(pageData.totalCount / pageData.limit), 1) : 1;

  function typeLabel(log: ViewerBalanceLog) {
    if (log.type === "ADMIN_ADJUSTMENT") {
      return log.amountMinor.startsWith("-")
        ? labels.adjustmentDebit
        : labels.adjustmentCredit;
    }
    return labels.types[log.type] ?? log.type;
  }

  return (
    <AppShell>
      <header className="page-header">
        <p className="eyebrow">FINANCE</p>
        <h1>{labels.title}</h1>
        <p className="muted">{labels.description}</p>
        <button
          className="freedom-button primary"
          disabled={query.isFetching || !viewerId}
          onClick={() => void query.refetch()}
          type="button"
        >
          {query.isFetching ? labels.refreshing : labels.refresh}
        </button>
      </header>
      {query.isError && (
        <p className="error-message" role="alert">
          {query.error instanceof Error ? query.error.message : labels.failed}{" "}
          <button onClick={() => void query.refetch()} type="button">{labels.retry}</button>
        </p>
      )}
      {query.isPending && <div className="user-empty-state compact"><strong>{labels.loading}</strong></div>}
      {data && pageData && (
        <>
          <section className="referral-summary-grid balance-summary-grid" aria-label={labels.title}>
            <article className="panel"><span>{labels.current}</span><strong>{formatMinorMoney(data.summary.balanceMinor, "CNY", language)}</strong></article>
            <article className="panel"><span>{labels.opening}</span><strong>{formatMinorMoney(data.summary.openingBalanceMinor, "CNY", language)}</strong></article>
            <article className="panel"><span>{labels.credits}</span><strong>{formatMinorMoney(data.summary.totalCreditsMinor, "CNY", language)}</strong></article>
            <article className="panel"><span>{labels.debits}</span><strong>{formatMinorMoney(data.summary.totalDebitsMinor, "CNY", language)}</strong></article>
          </section>
          <div className="traffic-retention-notice">
            <span aria-hidden="true">i</span>
            {labels.openingNote}
            <p>{labels.since}: {data.summary.recordedSince ? formatDate(data.summary.recordedSince, language) : labels.notRecorded}</p>
          </div>
          <section className="panel user-record-panel balance-ledger-panel">
            <h2>{labels.logTitle}</h2>
            <div className="user-table-wrap">
              <table className="user-data-table">
                <thead><tr><th>{labels.type}</th><th>{labels.amount}</th><th>{labels.after}</th><th>{labels.reference}</th><th>{labels.time}</th></tr></thead>
                <tbody>
                  {pageData.items.map((log) => (
                    <tr key={log.id}>
                      <td>
                        {typeLabel(log)}
                        {log.note ? <small style={{ display: "block", color: "#707c93" }}>{log.note}</small> : null}
                      </td>
                      <td className={log.amountMinor.startsWith("-") ? "balance-amount debit" : "balance-amount credit"}>
                        {formatMinorMoney(log.amountMinor, log.currency, language)}
                      </td>
                      <td>{formatMinorMoney(log.balanceAfterMinor, log.currency, language)}</td>
                      <td>
                        {log.tradeNo
                          ? log.type !== "COMMISSION_CREDIT" && Boolean(log.canViewOrder)
                            ? <AppLink href={`/account/orders/${encodeURIComponent(log.tradeNo)}`}>{log.tradeNo}</AppLink>
                            : <span className="order-number">{log.tradeNo}</span>
                          : labels.noReference}
                      </td>
                      <td>{formatDate(log.createdAt, language)}</td>
                    </tr>
                  ))}
                  {pageData.items.length === 0 && <tr><td colSpan={5}><div className="user-empty-state compact"><strong>{labels.empty}</strong></div></td></tr>}
                </tbody>
              </table>
            </div>
            <div className="account-ledger-pagination">
              <button disabled={pageData.page <= 0 || query.isFetching} onClick={() => setPage((current) => Math.max(current - 1, 0))} type="button">{labels.previous}</button>
              <button disabled={(pageData.page + 1) * pageData.limit >= pageData.totalCount || query.isFetching} onClick={() => setPage((current) => current + 1)} type="button">{labels.next}</button>
              <span className="muted">{labels.count(pageData.page + 1, pages, pageData.totalCount)}</span>
            </div>
          </section>
        </>
      )}
    </AppShell>
  );
}
