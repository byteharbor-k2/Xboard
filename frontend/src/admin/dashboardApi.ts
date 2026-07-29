import { ApiError } from "../lib/http";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

export const adminDashboardEndpoints = {
  summary: `${adminApiPrefix}/stat/getStats`,
  revenue: `${adminApiPrefix}/stat/getOrder`,
  nodeTrafficRanking: `${adminApiPrefix}/stat/getServerLastRank`,
  userTrafficRanking: `${adminApiPrefix}/stat/getTrafficRank`,
  systemStatus: `${adminApiPrefix}/system/getSystemStatus`,
  queueStats: `${adminApiPrefix}/system/getQueueStats`,
  queueWorkload: `${adminApiPrefix}/system/getQueueWorkload`,
  failedJobs: `${adminApiPrefix}/system/getHorizonFailedJobs`
} as const;

export type DashboardPeriod = "today" | "yesterday" | "7d" | "30d";
export type RevenueRange = "7d" | "30d" | "90d";
export type RevenueMetric = "amount" | "count";

export type DashboardSummary = {
  todayIncome: number;
  monthlyIncome: number;
  pendingTickets: number;
  pendingCommission: number;
  monthlyUsers: number;
  totalUsers: number;
  monthlyUploadBytes: number;
  monthlyDownloadBytes: number;
};

export type RevenuePoint = {
  date: string;
  value: number;
};

export type TrafficRankingEntry = {
  id: string;
  label: string;
  bytes: number;
  changePercent: number | null;
};

export type SystemServiceStatus = {
  key: "api" | "database" | "redis" | "queue";
  status: "healthy" | "degraded" | "offline";
};

export type QueueOverview = {
  status: "healthy" | "degraded" | "offline";
  waitSeconds: number | null;
  recentJobs: number | null;
  jobsPerMinute: number | null;
  errorsLastSevenDays: number | null;
  longestJobSeconds: number | null;
  activeProcesses: number | null;
  maxProcesses: number | null;
};

export type FailedJob = {
  id: string;
  failedAt: string;
  queue: string;
  jobName: string;
  exception: string;
};

export type PageResult<T> = {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
};

async function adminGet<T>(
  accessToken: string,
  endpoint: string,
  parameters?: Record<string, string | number>
) {
  const url = new URL(endpoint, window.location.origin);
  for (const [key, value] of Object.entries(parameters ?? {})) {
    url.searchParams.set(key, String(value));
  }
  const response = await fetch(`${url.pathname}${url.search}`, {
    credentials: "include",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${accessToken}`
    }
  });
  if (!response.ok) {
    throw new ApiError(response.status, {
      detail: `管理员接口尚未可用：${url.pathname}`
    });
  }
  return (await response.json()) as T;
}

export function getDashboardSummary(accessToken: string) {
  return adminGet<DashboardSummary>(
    accessToken,
    adminDashboardEndpoints.summary
  );
}

export function getRevenueSeries(
  accessToken: string,
  range: RevenueRange,
  metric: RevenueMetric
) {
  return adminGet<RevenuePoint[]>(
    accessToken,
    adminDashboardEndpoints.revenue,
    { range, metric }
  );
}

export function getTrafficRanking(
  accessToken: string,
  kind: "node" | "user",
  period: DashboardPeriod
) {
  return adminGet<TrafficRankingEntry[]>(
    accessToken,
    kind === "node"
      ? adminDashboardEndpoints.nodeTrafficRanking
      : adminDashboardEndpoints.userTrafficRanking,
    { period }
  );
}

export function getSystemStatus(accessToken: string) {
  return adminGet<SystemServiceStatus[]>(
    accessToken,
    adminDashboardEndpoints.systemStatus
  );
}

export function getQueueOverview(accessToken: string) {
  return adminGet<QueueOverview>(
    accessToken,
    adminDashboardEndpoints.queueStats
  );
}

export function getQueueWorkload(accessToken: string) {
  return adminGet<Record<string, unknown>>(
    accessToken,
    adminDashboardEndpoints.queueWorkload
  );
}

export function getFailedJobs(
  accessToken: string,
  page: number,
  pageSize: number
) {
  return adminGet<PageResult<FailedJob>>(
    accessToken,
    adminDashboardEndpoints.failedJobs,
    { page, pageSize }
  );
}
