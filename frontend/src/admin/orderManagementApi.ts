import { ApiError, adminSessionGuard } from "../lib/http";
import type {
  BillingPeriod,
  OrderStatus,
  OrderType,
  ProblemDetails
} from "../types";

/**
 * One order as the admin surface reports it: the original panel's snake_case
 * field names and epoch-second timestamps, amounts in minor units.
 */
export type AdminOrder = {
  trade_no: string;
  user_id: string;
  email: string;
  plan_id: string;
  plan_name: string;
  period: BillingPeriod;
  order_type: OrderType;
  status: OrderStatus;
  currency: string;
  original_amount: number;
  discount_amount: number;
  surplus_amount: number;
  surplus_credit: number;
  balance_amount: number;
  total_amount: number;
  callback_no: string | null;
  created_at: number;
  paid_at: number | null;
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

export function listOrders(
  accessToken: string,
  status: OrderStatus | null,
  limit = 100
) {
  const query = new URLSearchParams({ limit: String(limit) });
  if (status) {
    query.set("status", status);
  }
  return dataRequest<AdminOrder[]>(
    `/api/v2/admin/order/fetch?${query.toString()}`,
    accessToken
  );
}

/** Opens an order without a payment behind it, recorded as a manual settlement. */
export function settleOrder(accessToken: string, tradeNo: string) {
  return dataRequest<boolean>("/api/v2/admin/order/paid", accessToken, {
    method: "POST",
    body: JSON.stringify({ trade_no: tradeNo })
  });
}

export function cancelOrder(
  accessToken: string,
  tradeNo: string
) {
  return dataRequest<boolean>("/api/v2/admin/order/cancel", accessToken, {
    method: "POST",
    body: JSON.stringify({ trade_no: tradeNo })
  });
}
