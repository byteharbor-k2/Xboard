import { ApiError, adminSessionGuard } from "../lib/http";
import type { BillingPeriod, ProblemDetails } from "../types";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

export type CouponDiscountType = "FIXED_AMOUNT" | "PERCENTAGE";

/** Response and draft shapes are minor units for FIXED_AMOUNT. */
export type AdminCoupon = {
  id: string;
  code: string;
  name: string;
  discount_type: CouponDiscountType;
  discount_value: number;
  /** Epoch seconds; null is open-ended on that side. */
  starts_at: number | null;
  ends_at: number | null;
  max_redemptions: number | null;
  redemptions_used: number;
  max_redemptions_per_user: number | null;
  limited_plan_ids: string[];
  limited_periods: BillingPeriod[];
  enabled: boolean;
  created_at: number;
  updated_at: number;
};

export type CouponDraft = {
  id?: string;
  code: string;
  name: string;
  discount_type: CouponDiscountType;
  discount_value: number;
  starts_at: number | null;
  ends_at: number | null;
  max_redemptions: number | null;
  max_redemptions_per_user: number | null;
  limited_plan_ids: string[];
  limited_periods: BillingPeriod[];
  enabled: boolean;
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

export function listCoupons(
  accessToken: string,
  limit = 100
) {
  const query = new URLSearchParams({ limit: String(limit) });
  return request<{ data: AdminCoupon[] }>(
    `${adminApiPrefix}/coupon/list?${query.toString()}`,
    accessToken
  ).then((response) => response.data);
}

export function createCoupon(accessToken: string, draft: CouponDraft) {
  return request<{ data: AdminCoupon }>(
    `${adminApiPrefix}/coupon/create`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify(draft)
    }
  ).then((response) => response.data);
}

export function updateCoupon(accessToken: string, draft: CouponDraft) {
  return request<{ data: AdminCoupon }>(
    `${adminApiPrefix}/coupon/update`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify(draft)
    }
  ).then((response) => response.data);
}

/** Deleting is always permitted: orders keep their priced discounts. */
export function deleteCoupon(accessToken: string, couponId: string) {
  return request<{ data: boolean }>(
    `${adminApiPrefix}/coupon/delete`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ id: couponId })
    }
  ).then((response) => response.data);
}
