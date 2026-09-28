import { ApiError, adminSessionGuard } from "../lib/http";
import type { ProblemDetails } from "../types";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

/**
 * One account as the admin surface reports it: the original panel's snake_case
 * field names and epoch-second timestamps.
 *
 * The subscription figures come from the account's entitlement, which is where
 * the allowance and the usage counters live. `online_devices` is an observation
 * of how many devices are on the subscription right now; nothing caps it.
 */
export type AdminUser = {
  id: string;
  node_user_id: number;
  email: string;
  status: "ACTIVE" | "SUSPENDED";
  banned: boolean;
  email_verified: boolean;
  remarks: string | null;
  speed_limit_mbps: number | null;
  balance: number;
  plan_id: string | null;
  plan_name: string | null;
  transfer_limit_bytes: string;
  used_bytes: string;
  online_devices: number;
  expires_at: number | null;
  last_login_at: number | null;
  created_at: number;
};

export type AdminUserPage = {
  data: AdminUser[];
  total: number;
  page: number;
  limit: number;
};

export type AdminUserUpdate = {
  email?: string;
  password?: string;
  remarks?: string;
  speed_limit_mbps?: number | null;
  banned?: boolean;
  plan_id?: string | null;
  transfer_limit_bytes?: string | null;
  /** Epoch seconds. Absent (or null) leaves the current expiry untouched. */
  expires_at?: number | null;
  /**
   * Make the account permanent. An absent expiry cannot express this on its
   * own (absent = "leave alone"), so clearing rides this flag.
   */
  clear_expiry?: boolean;
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

function dataRequest<T>(
  path: string,
  accessToken: string,
  init?: RequestInit
) {
  return request<{ data: T }>(path, accessToken, init).then(
    (response) => response.data
  );
}

export function listUsers(
  accessToken: string,
  options: {
    search?: string;
    status?: "ACTIVE" | "SUSPENDED" | null;
    page?: number;
    limit?: number;
  } = {}
) {
  const query = new URLSearchParams();
  if (options.search?.trim()) {
    query.set("search", options.search.trim());
  }
  if (options.status) {
    query.set("status", options.status);
  }
  query.set("page", String(options.page ?? 0));
  query.set("limit", String(options.limit ?? 20));
  return dataRequest<AdminUserPage>(
    `${adminApiPrefix}/user/fetch?${query}`,
    accessToken
  );
}

export function getUser(accessToken: string, id: string) {
  return dataRequest<AdminUser>(
    `${adminApiPrefix}/user/getUserInfoById?id=${encodeURIComponent(id)}`,
    accessToken
  );
}

export function updateUser(
  accessToken: string,
  id: string,
  update: AdminUserUpdate
) {
  return dataRequest<AdminUser>(`${adminApiPrefix}/user/update`, accessToken, {
    method: "POST",
    body: JSON.stringify({ id, ...update })
  });
}

export function setUserBanned(
  accessToken: string,
  id: string,
  banned: boolean
) {
  return dataRequest<AdminUser>(`${adminApiPrefix}/user/ban`, accessToken, {
    method: "POST",
    body: JSON.stringify({ id, banned })
  });
}

export function resetUserSecret(accessToken: string, id: string) {
  return dataRequest<string>(
    `${adminApiPrefix}/user/resetSecret`,
    accessToken,
    { method: "POST", body: JSON.stringify({ id }) }
  );
}

export function resetUserTraffic(accessToken: string, id: string) {
  return dataRequest<AdminUser>(
    `${adminApiPrefix}/user/resetTraffic`,
    accessToken,
    { method: "POST", body: JSON.stringify({ id }) }
  );
}

export function deleteUser(accessToken: string, id: string) {
  return dataRequest<boolean>(`${adminApiPrefix}/user/destroy`, accessToken, {
    method: "POST",
    body: JSON.stringify({ id })
  });
}

/**
 * Downloads the whole customer base as CSV.
 *
 * The answer is a file, not JSON, so it is fetched as a blob with the admin
 * session guard (an expired token still gets its one refresh); the caller
 * turns the blob into a browser download.
 */
export async function exportUserCsv(accessToken: string): Promise<Blob> {
  const response = await adminSessionGuard.authorizedFetch(
    `${adminApiPrefix}/user/exportCsv`,
    {
      credentials: "include",
      headers: { Authorization: `Bearer ${accessToken}` }
    }
  );
  if (!response.ok) {
    let problem: ProblemDetails = {};
    try {
      problem = (await response.json()) as ProblemDetails;
    } catch {
      problem = { detail: "请求未能完成" };
    }
    throw new ApiError(response.status, problem);
  }
  return await response.blob();
}

/** Sends one mail the admin wrote, straight to the account's address. */
export function sendUserMail(
  accessToken: string,
  userId: string,
  subject: string,
  body: string
) {
  return dataRequest<boolean>(`${adminApiPrefix}/user/sendMail`, accessToken, {
    method: "POST",
    body: JSON.stringify({
      user_id: userId,
      subject,
      body
    })
  });
}

/** Assigns an account's inviter; null clears the current one. */
export function assignUserInviter(
  accessToken: string,
  userId: string,
  inviterUserId: string | null
) {
  return dataRequest<AdminUser>(
    `${adminApiPrefix}/user/assignInviter`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({
        user_id: userId,
        inviter_user_id: inviterUserId
      })
    }
  );
}

/**
 * Hands one order to another account. Backend contract owned by the order
 * module: 404 when the order is unknown, business error when the target
 * account is missing or suspended, otherwise the order (and its
 * entitlement) belongs to the target.
 */
export function assignUserOrder(
  accessToken: string,
  tradeNo: string,
  userId: string
) {
  return dataRequest<boolean>(
    `${adminApiPrefix}/order/assign`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ trade_no: tradeNo, user_id: userId })
    }
  );
}
