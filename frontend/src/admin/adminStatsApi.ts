import { ApiError, adminSessionGuard } from "../lib/http";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

export type AdminStatPeriod = "today" | "yesterday" | "7d" | "30d";

export type AdminStatSummary = {
  todayIncome: number;
  monthlyIncome: number;
  pendingCommission: number;
  monthlyUsers: number;
  totalUsers: number;
  monthlyUploadBytes: number;
  monthlyDownloadBytes: number;
};

export type AdminStatRankingEntry = {
  id: string;
  label: string;
  bytes: number;
  changePercent: number | null;
};

export const adminStatEndpoints = {
  summary: `${adminApiPrefix}/stat/getStats`,
  nodeRanking: `${adminApiPrefix}/stat/getServerLastRank`,
  userRanking: `${adminApiPrefix}/stat/getTrafficRank`
} as const;

/**
 * Business statistics answers come bare: unlike most v2 admin calls the stat
 * endpoints do not wrap their payload in `{ data }`, which is how the dashboard
 * has always parsed them.
 */
async function statGet<T>(
  accessToken: string,
  endpoint: string,
  parameters?: Record<string, string>
): Promise<T> {
  const url = new URL(endpoint, window.location.origin);
  for (const [key, value] of Object.entries(parameters ?? {})) {
    url.searchParams.set(key, value);
  }
  const response = await adminSessionGuard.authorizedFetch(
    `${url.pathname}${url.search}`,
    {
      credentials: "include",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` }
    }
  );
  if (!response.ok) {
    throw new ApiError(response.status, {
      detail: `管理员统计接口尚未可用：${url.pathname}`
    });
  }
  return (await response.json()) as T;
}

/** Headline dashboard numbers over the trailing month. */
export function getAdminStatSummary(
  accessToken: string
): Promise<AdminStatSummary> {
  return statGet<AdminStatSummary>(accessToken, adminStatEndpoints.summary);
}

/**
 * Traffic ranking across nodes or accounts. The backend aggregates the
 * trailing-month slice only, so the period is sent for compatibility.
 */
export function getStatRanking(
  accessToken: string,
  kind: "node" | "user",
  period: AdminStatPeriod
): Promise<AdminStatRankingEntry[]> {
  return statGet<AdminStatRankingEntry[]>(
    accessToken,
    kind === "node"
      ? adminStatEndpoints.nodeRanking
      : adminStatEndpoints.userRanking,
    { period }
  );
}
