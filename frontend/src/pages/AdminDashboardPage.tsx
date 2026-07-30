import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";

import {
  getDashboardSummary,
  getFailedJobs,
  getQueueOverview,
  getRevenueSeries,
  getSystemStatus,
  getTrafficRanking,
  type DashboardPeriod,
  type FailedJob,
  type PageResult,
  type RevenueMetric,
  type RevenueRange,
  type TrafficRankingEntry
} from "../admin/dashboardApi";
import { AdminShell } from "../components/AdminShell";
import { AppLink } from "../components/AppLink";
import { useAdminPreferences } from "../store/adminPreferences";
import { useAdminAuthStore } from "../store/adminAuth";

const dashboardCopy = {
  "zh-CN": {
    eyebrow: "运营概览",
    title: "仪表盘",
    description: "集中查看业务、用户、流量和系统运行状态。",
    waiting: "等待后端数据",
    endpointPending: "后端端点尚未实现",
    todayIncome: "今日收入",
    monthlyIncome: "月收入",
    pendingTickets: "待处理工单",
    pendingCommission: "待处理佣金",
    monthlyUsers: "月新增用户",
    totalUsers: "总用户",
    monthlyUpload: "月上传",
    monthlyDownload: "月下载",
    revenue: "收入概览",
    last7Days: "最近 7 天",
    last30Days: "最近 30 天",
    last90Days: "最近 90 天",
    amount: "金额",
    count: "数量",
    system: "系统状态",
    api: "应用服务",
    database: "数据库",
    redis: "Redis",
    queue: "任务队列",
    notConnected: "尚未连接",
    healthy: "正常",
    degraded: "异常",
    offline: "离线",
    quickActions: "快捷入口",
    manageUsers: "管理用户",
    manageNodes: "管理节点",
    manageOrders: "查看订单",
    systemSettings: "系统配置",
    nodeTrafficRank: "节点流量排行",
    userTrafficRank: "用户流量排行",
    today: "今天",
    yesterday: "昨天",
    sevenDays: "最近 7 天",
    queueStatus: "队列状态",
    queueDescription: "当前队列运行状态",
    currentStatus: "运行状态",
    waitTime: "当前等待时间",
    recentJobs: "近期任务数",
    perMinute: "每分钟处理量",
    jobDetails: "作业详情",
    jobDescription: "队列处理详细信息",
    errorsSevenDays: "7 日报错数量",
    longestJob: "最长运行队列",
    activeProcesses: "活跃进程",
    refresh: "刷新",
    refreshAll: "刷新全部",
    failedJobsTitle: "失败任务详情",
    failedAt: "时间",
    queueName: "队列",
    jobName: "任务名称",
    exception: "异常信息",
    action: "操作",
    view: "查看",
    noData: "暂无数据",
    selected: "已选择 0 项",
    total: "共 {count} 项",
    pageSize: "每页显示",
    page: "第 {page} 页，共 {pages} 页",
    close: "关闭",
    firstPage: "第一页",
    previousPage: "上一页",
    nextPage: "下一页",
    lastPage: "最后一页"
  },
  "en-US": {
    eyebrow: "Operations overview",
    title: "Dashboard",
    description: "Review business, users, traffic, and platform health in one place.",
    waiting: "Waiting for backend data",
    endpointPending: "Backend endpoint is not implemented",
    todayIncome: "Today’s revenue",
    monthlyIncome: "Monthly revenue",
    pendingTickets: "Pending tickets",
    pendingCommission: "Pending commission",
    monthlyUsers: "New users this month",
    totalUsers: "Total users",
    monthlyUpload: "Monthly upload",
    monthlyDownload: "Monthly download",
    revenue: "Revenue overview",
    last7Days: "Last 7 days",
    last30Days: "Last 30 days",
    last90Days: "Last 90 days",
    amount: "Amount",
    count: "Count",
    system: "System status",
    api: "Application service",
    database: "Database",
    redis: "Redis",
    queue: "Job queue",
    notConnected: "Not connected",
    healthy: "Healthy",
    degraded: "Degraded",
    offline: "Offline",
    quickActions: "Quick actions",
    manageUsers: "Manage users",
    manageNodes: "Manage nodes",
    manageOrders: "Review orders",
    systemSettings: "System settings",
    nodeTrafficRank: "Node traffic ranking",
    userTrafficRank: "User traffic ranking",
    today: "Today",
    yesterday: "Yesterday",
    sevenDays: "Last 7 days",
    queueStatus: "Queue status",
    queueDescription: "Current queue operating status",
    currentStatus: "Operating status",
    waitTime: "Current wait time",
    recentJobs: "Recent jobs",
    perMinute: "Processed per minute",
    jobDetails: "Job details",
    jobDescription: "Queue processing details",
    errorsSevenDays: "Errors in 7 days",
    longestJob: "Longest running queue",
    activeProcesses: "Active processes",
    refresh: "Refresh",
    refreshAll: "Refresh all",
    failedJobsTitle: "Failed job details",
    failedAt: "Time",
    queueName: "Queue",
    jobName: "Job name",
    exception: "Exception",
    action: "Action",
    view: "View",
    noData: "No data",
    selected: "0 selected",
    total: "{count} items",
    pageSize: "Rows per page",
    page: "Page {page} of {pages}",
    close: "Close",
    firstPage: "First page",
    previousPage: "Previous page",
    nextPage: "Next page",
    lastPage: "Last page"
  }
};

type DashboardCopy = (typeof dashboardCopy)["zh-CN"];

function formatCurrency(value: number | undefined, language: "zh-CN" | "en-US") {
  if (value === undefined) return "—";
  return new Intl.NumberFormat(language, {
    style: "currency",
    currency: "CNY",
    maximumFractionDigits: 2
  }).format(value);
}

function formatNumber(value: number | null | undefined, language: "zh-CN" | "en-US") {
  if (value === null || value === undefined) return "—";
  return new Intl.NumberFormat(language).format(value);
}

function formatBytes(value: number | undefined) {
  if (value === undefined) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let normalized = value;
  let unit = 0;
  while (normalized >= 1024 && unit < units.length - 1) {
    normalized /= 1024;
    unit += 1;
  }
  return `${normalized.toFixed(unit > 2 ? 2 : 1)} ${units[unit]}`;
}

function statusLabel(
  status: "healthy" | "degraded" | "offline" | undefined,
  copy: DashboardCopy
) {
  if (!status) return copy.notConnected;
  return copy[status];
}

function RankingCard({
  title,
  glyph,
  period,
  onPeriodChange,
  entries,
  loading,
  error,
  onRefresh,
  copy
}: {
  title: string;
  glyph: string;
  period: DashboardPeriod;
  onPeriodChange: (period: DashboardPeriod) => void;
  entries: TrafficRankingEntry[] | undefined;
  loading: boolean;
  error: boolean;
  onRefresh: () => void;
  copy: DashboardCopy;
}) {
  const visibleEntries = entries?.slice(0, 6) ?? [];
  const maximum = Math.max(...visibleEntries.map((entry) => entry.bytes), 1);
  const periodLabels: Record<DashboardPeriod, string> = {
    today: copy.today,
    yesterday: copy.yesterday,
    "7d": copy.sevenDays,
    "30d": copy.last30Days
  };

  return (
    <article className="admin-card admin-ranking-card">
      <div className="admin-card-heading">
        <div className="admin-ranking-title">
          <i aria-hidden="true">{glyph}</i>
          <h2>{title}</h2>
        </div>
        <div className="admin-ranking-actions">
          <select
            aria-label={title}
            className="admin-period-select"
            value={period}
            onChange={(event) =>
              onPeriodChange(event.target.value as DashboardPeriod)
            }
          >
            {Object.entries(periodLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <button
            aria-label={copy.refresh}
            className="admin-refresh-button"
            disabled={loading}
            onClick={onRefresh}
            type="button"
          >
            ↻
          </button>
        </div>
      </div>
      <div className="admin-ranking-list">
        {visibleEntries.length > 0
          ? visibleEntries.map((entry) => (
              <div className="admin-ranking-row" key={entry.id}>
                <div>
                  <strong>{entry.label}</strong>
                  <small>{formatBytes(entry.bytes)}</small>
                </div>
                <span>
                  <i style={{ width: `${(entry.bytes / maximum) * 100}%` }} />
                </span>
              </div>
            ))
          : [72, 61, 49, 38, 27, 18].map((width, index) => (
              <div className="admin-ranking-row" key={`${title}-${index}`}>
                <div>
                  <strong>—</strong>
                  <small>—</small>
                </div>
                <span>
                  <i style={{ width: `${width}%` }} />
                </span>
              </div>
            ))}
      </div>
      {(error || (!loading && visibleEntries.length === 0)) && (
        <p className="admin-inline-state">
          {error ? copy.endpointPending : copy.noData}
        </p>
      )}
    </article>
  );
}

function FailedJobsDialog({
  accessToken,
  copy,
  language,
  onClose
}: {
  accessToken: string;
  copy: DashboardCopy;
  language: "zh-CN" | "en-US";
  onClose: () => void;
}) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const failedJobs = useQuery({
    queryKey: ["admin-dashboard", "failed-jobs", page, pageSize],
    queryFn: () => getFailedJobs(accessToken, page, pageSize)
  });
  const result: PageResult<FailedJob> = failedJobs.data ?? {
    items: [],
    page,
    pageSize,
    total: 0
  };
  const pageCount = Math.max(1, Math.ceil(result.total / pageSize));

  useEffect(() => {
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") onClose();
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [onClose]);

  return (
    <div className="admin-modal-backdrop" role="presentation">
      <section
        aria-labelledby="failed-jobs-title"
        aria-modal="true"
        className="admin-modal"
        role="dialog"
      >
        <header>
          <h2 id="failed-jobs-title">{copy.failedJobsTitle}</h2>
          <button aria-label={copy.close} onClick={onClose} type="button">
            ×
          </button>
        </header>
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>{copy.failedAt}</th>
                <th>{copy.queueName}</th>
                <th>{copy.jobName}</th>
                <th>{copy.exception}</th>
                <th>{copy.action}</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((job) => (
                <tr key={job.id}>
                  <td>{new Date(job.failedAt).toLocaleString(language)}</td>
                  <td>{job.queue}</td>
                  <td>{job.jobName}</td>
                  <td className="admin-table-exception">{job.exception}</td>
                  <td>
                    <button className="admin-table-action" type="button">
                      {copy.view}
                    </button>
                  </td>
                </tr>
              ))}
              {result.items.length === 0 && (
                <tr>
                  <td className="admin-table-empty" colSpan={5}>
                    {failedJobs.isError ? copy.endpointPending : copy.noData}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="admin-pagination">
          <span>
            {copy.selected}，{copy.total.replace("{count}", String(result.total))}
          </span>
          <label>
            {copy.pageSize}
            <select
              value={pageSize}
              onChange={(event) => {
                setPageSize(Number(event.target.value));
                setPage(1);
              }}
            >
              {[10, 20, 50].map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </label>
          <strong>
            {copy.page
              .replace("{page}", String(page))
              .replace("{pages}", String(pageCount))}
          </strong>
          <div>
            <button
              aria-label={copy.firstPage}
              disabled={page <= 1}
              onClick={() => setPage(1)}
              type="button"
            >
              «
            </button>
            <button
              aria-label={copy.previousPage}
              disabled={page <= 1}
              onClick={() => setPage((value) => Math.max(1, value - 1))}
              type="button"
            >
              ‹
            </button>
            <button
              aria-label={copy.nextPage}
              disabled={page >= pageCount}
              onClick={() => setPage((value) => Math.min(pageCount, value + 1))}
              type="button"
            >
              ›
            </button>
            <button
              aria-label={copy.lastPage}
              disabled={page >= pageCount}
              onClick={() => setPage(pageCount)}
              type="button"
            >
              »
            </button>
          </div>
        </div>
        <footer>
          <button
            disabled={failedJobs.isFetching}
            onClick={() => void failedJobs.refetch()}
            type="button"
          >
            ↻ {copy.refresh}
          </button>
          <button onClick={onClose} type="button">
            {copy.close}
          </button>
        </footer>
      </section>
    </div>
  );
}

export function AdminDashboardPage() {
  const accessToken = useAdminAuthStore((state) => state.accessToken)!;
  const language = useAdminPreferences((state) => state.language);
  const copy = dashboardCopy[language];
  const queryClient = useQueryClient();
  const [revenueRange, setRevenueRange] = useState<RevenueRange>("30d");
  const [revenueMetric, setRevenueMetric] = useState<RevenueMetric>("amount");
  const [nodePeriod, setNodePeriod] = useState<DashboardPeriod>("today");
  const [userPeriod, setUserPeriod] = useState<DashboardPeriod>("today");
  const [failedJobsOpen, setFailedJobsOpen] = useState(false);

  const summary = useQuery({
    queryKey: ["admin-dashboard", "summary"],
    queryFn: () => getDashboardSummary(accessToken)
  });
  const revenue = useQuery({
    queryKey: ["admin-dashboard", "revenue", revenueRange, revenueMetric],
    queryFn: () => getRevenueSeries(accessToken, revenueRange, revenueMetric)
  });
  const nodeRanking = useQuery({
    queryKey: ["admin-dashboard", "ranking", "node", nodePeriod],
    queryFn: () => getTrafficRanking(accessToken, "node", nodePeriod)
  });
  const userRanking = useQuery({
    queryKey: ["admin-dashboard", "ranking", "user", userPeriod],
    queryFn: () => getTrafficRanking(accessToken, "user", userPeriod)
  });
  const systemStatus = useQuery({
    queryKey: ["admin-dashboard", "system-status"],
    queryFn: () => getSystemStatus(accessToken)
  });
  const queueOverview = useQuery({
    queryKey: ["admin-dashboard", "queue-overview"],
    queryFn: () => getQueueOverview(accessToken)
  });

  const metrics = useMemo(
    () => [
      {
        id: "today-income",
        label: copy.todayIncome,
        glyph: "↘",
        value: formatCurrency(summary.data?.todayIncome, language)
      },
      {
        id: "monthly-income",
        label: copy.monthlyIncome,
        glyph: "▥",
        value: formatCurrency(summary.data?.monthlyIncome, language)
      },
      {
        id: "pending-tickets",
        label: copy.pendingTickets,
        glyph: "▱",
        value: formatNumber(summary.data?.pendingTickets, language),
        href: "/admin/support/tickets"
      },
      {
        id: "pending-commission",
        label: copy.pendingCommission,
        glyph: "◇",
        value: formatNumber(summary.data?.pendingCommission, language),
        href: "/admin/finance/orders"
      },
      {
        id: "monthly-users",
        label: copy.monthlyUsers,
        glyph: "♙",
        value: formatNumber(summary.data?.monthlyUsers, language)
      },
      {
        id: "total-users",
        label: copy.totalUsers,
        glyph: "◎",
        value: formatNumber(summary.data?.totalUsers, language)
      },
      {
        id: "monthly-upload",
        label: copy.monthlyUpload,
        glyph: "↑",
        value: formatBytes(summary.data?.monthlyUploadBytes)
      },
      {
        id: "monthly-download",
        label: copy.monthlyDownload,
        glyph: "↓",
        value: formatBytes(summary.data?.monthlyDownloadBytes)
      }
    ],
    [copy, language, summary.data]
  );

  const rangeLabels: Record<RevenueRange, string> = {
    "7d": copy.last7Days,
    "30d": copy.last30Days,
    "90d": copy.last90Days
  };
  const serviceLabels = {
    api: copy.api,
    database: copy.database,
    redis: copy.redis,
    queue: copy.queue
  };

  async function refreshDashboard() {
    await queryClient.invalidateQueries({ queryKey: ["admin-dashboard"] });
  }

  return (
    <AdminShell>
      <header className="admin-page-heading">
        <div>
          <p>{copy.eyebrow}</p>
          <h1>{copy.title}</h1>
          <span>{copy.description}</span>
        </div>
        <div className="admin-heading-actions">
          <button
            disabled={summary.isFetching}
            onClick={() => void refreshDashboard()}
            type="button"
          >
            ↻ {copy.refreshAll}
          </button>
          <span className="admin-environment">DEV</span>
        </div>
      </header>

      <section className="admin-metric-grid" aria-label={copy.title}>
        {metrics.map((metric) => {
          const content = (
            <>
              <div>
                <span>{metric.label}</span>
                <i aria-hidden="true">{metric.glyph}</i>
              </div>
              <strong>{metric.value}</strong>
              <small>
                {summary.isError ? copy.endpointPending : copy.waiting}
              </small>
            </>
          );
          return metric.href ? (
            <AppLink
              className="admin-metric-card admin-metric-link"
              href={metric.href}
              key={metric.id}
            >
              {content}
            </AppLink>
          ) : (
            <article className="admin-metric-card" key={metric.id}>
              {content}
            </article>
          );
        })}
      </section>

      <section className="admin-dashboard-grid">
        <article className="admin-card admin-chart-card">
          <div className="admin-card-heading">
            <div>
              <h2>{copy.revenue}</h2>
              <span>
                {revenue.isError
                  ? copy.endpointPending
                  : revenue.data?.length
                    ? `${revenue.data.length}`
                    : copy.waiting}
              </span>
            </div>
            <div className="admin-chart-controls">
              <select
                aria-label={copy.revenue}
                value={revenueRange}
                onChange={(event) =>
                  setRevenueRange(event.target.value as RevenueRange)
                }
              >
                {Object.entries(rangeLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
              <span>
                <button
                  className={revenueMetric === "amount" ? "active" : undefined}
                  onClick={() => setRevenueMetric("amount")}
                  type="button"
                >
                  {copy.amount}
                </button>
                <button
                  className={revenueMetric === "count" ? "active" : undefined}
                  onClick={() => setRevenueMetric("count")}
                  type="button"
                >
                  {copy.count}
                </button>
              </span>
              <button
                aria-label={copy.refresh}
                disabled={revenue.isFetching}
                onClick={() => void revenue.refetch()}
                type="button"
              >
                ↻
              </button>
            </div>
          </div>
          <div className="admin-empty-chart">
            <div className="admin-chart-lines" aria-hidden="true">
              <span />
              <span />
              <span />
              <span />
            </div>
            <p>{revenue.isError ? copy.endpointPending : copy.waiting}</p>
          </div>
        </article>

        <aside className="admin-dashboard-aside">
          <article className="admin-card">
            <div className="admin-card-heading">
              <div>
                <h2>{copy.system}</h2>
                <span>
                  {systemStatus.isError ? copy.endpointPending : copy.waiting}
                </span>
              </div>
              <button
                aria-label={copy.refresh}
                className="admin-refresh-button"
                disabled={systemStatus.isFetching}
                onClick={() => void systemStatus.refetch()}
                type="button"
              >
                ↻
              </button>
            </div>
            <div className="admin-status-list">
              {(["api", "database", "redis", "queue"] as const).map((key) => {
                const service = systemStatus.data?.find((item) => item.key === key);
                return (
                  <div key={key}>
                    <span
                      className={`admin-status-dot ${service?.status ?? "offline"}`}
                    />
                    <strong>{serviceLabels[key]}</strong>
                    <small>{statusLabel(service?.status, copy)}</small>
                  </div>
                );
              })}
            </div>
          </article>

          <article className="admin-card">
            <div className="admin-card-heading">
              <div>
                <h2>{copy.quickActions}</h2>
              </div>
            </div>
            <div className="admin-quick-links">
              <AppLink href="/admin/users">{copy.manageUsers}</AppLink>
              <AppLink href="/admin/nodes">{copy.manageNodes}</AppLink>
              <AppLink href="/admin/finance/orders">{copy.manageOrders}</AppLink>
              <AppLink href="/admin/system/settings">{copy.systemSettings}</AppLink>
            </div>
          </article>
        </aside>
      </section>

      <section className="admin-ranking-grid">
        <RankingCard
          copy={copy}
          entries={nodeRanking.data}
          error={nodeRanking.isError}
          glyph="⌘"
          loading={nodeRanking.isFetching}
          onPeriodChange={setNodePeriod}
          onRefresh={() => void nodeRanking.refetch()}
          period={nodePeriod}
          title={copy.nodeTrafficRank}
        />
        <RankingCard
          copy={copy}
          entries={userRanking.data}
          error={userRanking.isError}
          glyph="♙"
          loading={userRanking.isFetching}
          onPeriodChange={setUserPeriod}
          onRefresh={() => void userRanking.refetch()}
          period={userPeriod}
          title={copy.userTrafficRank}
        />
      </section>

      <section className="admin-queue-grid">
        <article className="admin-card admin-queue-card">
          <div className="admin-card-heading">
            <div>
              <h2>{copy.queueStatus}</h2>
              <span>
                {queueOverview.isError
                  ? copy.endpointPending
                  : copy.queueDescription}
              </span>
            </div>
            <button
              aria-label={copy.refresh}
              className="admin-refresh-button"
              disabled={queueOverview.isFetching}
              onClick={() => void queueOverview.refetch()}
              type="button"
            >
              ↻
            </button>
          </div>
          <div className="admin-queue-state">
            <span
              className={`admin-status-dot ${
                queueOverview.data?.status ?? "offline"
              }`}
            />
            <div>
              <strong>{copy.currentStatus}</strong>
              <small>
                {copy.waitTime}:{" "}
                {queueOverview.data?.waitSeconds === null ||
                queueOverview.data?.waitSeconds === undefined
                  ? "—"
                  : `${queueOverview.data.waitSeconds}s`}
              </small>
            </div>
            <em>{statusLabel(queueOverview.data?.status, copy)}</em>
          </div>
          <div className="admin-queue-metrics">
            <div>
              <span>{copy.recentJobs}</span>
              <strong>
                {formatNumber(queueOverview.data?.recentJobs, language)}
              </strong>
              <i />
            </div>
            <div>
              <span>{copy.perMinute}</span>
              <strong>
                {formatNumber(queueOverview.data?.jobsPerMinute, language)}
              </strong>
              <i />
            </div>
          </div>
        </article>

        <article className="admin-card admin-queue-card">
          <div className="admin-card-heading">
            <div>
              <h2>{copy.jobDetails}</h2>
              <span>{copy.jobDescription}</span>
            </div>
            <button
              aria-label={copy.failedJobsTitle}
              className="admin-eye-button"
              onClick={() => setFailedJobsOpen(true)}
              type="button"
            >
              ◉
            </button>
          </div>
          <div className="admin-job-metrics">
            <div>
              <span>{copy.errorsSevenDays}</span>
              <strong>
                {formatNumber(
                  queueOverview.data?.errorsLastSevenDays,
                  language
                )}
              </strong>
              <button
                className="admin-inline-eye"
                onClick={() => setFailedJobsOpen(true)}
                type="button"
              >
                ◉ {copy.view}
              </button>
            </div>
            <div>
              <span>{copy.longestJob}</span>
              <strong>
                {queueOverview.data?.longestJobSeconds === null ||
                queueOverview.data?.longestJobSeconds === undefined
                  ? "—"
                  : `${queueOverview.data.longestJobSeconds}s`}
              </strong>
              <small>{copy.waiting}</small>
            </div>
          </div>
          <div className="admin-process-row">
            <span>{copy.activeProcesses}</span>
            <strong>
              {formatNumber(queueOverview.data?.activeProcesses, language)} /{" "}
              {formatNumber(queueOverview.data?.maxProcesses, language)}
            </strong>
            <i />
          </div>
        </article>
      </section>

      {failedJobsOpen && (
        <FailedJobsDialog
          accessToken={accessToken}
          copy={copy}
          language={language}
          onClose={() => setFailedJobsOpen(false)}
        />
      )}
    </AdminShell>
  );
}
