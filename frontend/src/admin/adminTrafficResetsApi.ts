import { ApiError, adminSessionGuard } from "../lib/http";
import type { ProblemDetails } from "../types";

/**
 * One traffic reset as the admin surface reports it: the original panel's
 * snake_case field names and epoch-second timestamps, counters in whole
 * bytes. The page formats them with `formatBytes(stringify(...))`.
 */
export type AdminTrafficReset = {
  user_id: string;
  email: string | null;
  node_user_id: number | null;
  entitlement_id: string;
  reset_at: number;
  uploaded_bytes_before: number;
  downloaded_bytes_before: number;
};

async function request<T>(
  path: string,
  accessToken: string,
  init?: RequestInit
): Promise<T> {
  const response = await adminSessionGuard.authorizedFetch(path, {
    ...init,
    credentials: "include",
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      Authorization: `Bearer ${accessToken}`,
      ...init?.headers
    }
  });
  if (!response.ok) {
    let problem: ProblemDetails = {};
    try {
      problem = (await response.json()) as ProblemDetails;
    } catch {
      problem = { detail: "请求未能完成" };
    }
    throw new ApiError(response.status, problem);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

function dataRequest<T>(path: string, accessToken: string, init?: RequestInit) {
  return request<{ data: T }>(path, accessToken, init).then(
    (response) => response.data
  );
}

/** The newest resets, at most `limit` of them, one account's id optional. */
export function listTrafficResets(
  accessToken: string,
  userId: string | null,
  limit = 100
) {
  const query = new URLSearchParams({ limit: String(limit) });
  if (userId) {
    query.set("user_id", userId);
  }
  return dataRequest<AdminTrafficReset[]>(
    `/api/v2/admin/traffic-reset/records?${query.toString()}`,
    accessToken
  );
}
