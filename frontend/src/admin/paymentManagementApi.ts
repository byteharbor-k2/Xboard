import { ApiError } from "../lib/http";
import type { ProblemDetails } from "../types";

export type LocalizedText = {
  "zh-CN": string;
  "en-US": string;
};

/**
 * One configured payment method, as the admin surface reports it: the original
 * panel's snake_case field names and epoch-second timestamps. `payment` is the
 * gateway code and `enable` is the switch.
 *
 * `config` holds the gateway's own credentials. They are returned here so an
 * existing configuration can be edited without retyping the merchant key, and
 * this is the only surface that ever returns them.
 */
export type AdminPaymentMethod = {
  id: string;
  uuid: string;
  payment: string;
  name: string;
  icon: string | null;
  notify_domain: string | null;
  /** Where the gateway should report a payment; computed server-side. */
  notify_url: string;
  /**
   * True when that address is one a remote gateway cannot reach (a local name
   * or a private network): payments would be taken but never confirmed.
   */
  notify_unreachable: boolean;
  /** Minor units for the fixed part, whole percent for the other. */
  handling_fee_fixed: number | null;
  handling_fee_percent: number | null;
  enable: boolean;
  sort: number;
  config: Record<string, string>;
  created_at: number;
  updated_at: number;
};

/** One input a gateway needs, described by the server so it can be rendered. */
export type GatewayField = {
  type: string;
  label: LocalizedText;
  placeholder: LocalizedText;
  description: LocalizedText;
  required: boolean;
  secret: boolean;
  value: string;
};

export type PaymentMethodDraft = {
  id?: string;
  payment: string;
  name: string;
  icon: string;
  notify_domain: string;
  /** Minor units. The form collects major units and converts. */
  handling_fee_fixed: number | null;
  handling_fee_percent: number | null;
  config: Record<string, string>;
};

async function request<T>(
  path: string,
  accessToken: string,
  init?: RequestInit
): Promise<T> {
  const response = await fetch(path, {
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

export function listPaymentMethods(accessToken: string) {
  return dataRequest<AdminPaymentMethod[]>(
    "/api/v2/admin/payment/fetch",
    accessToken
  );
}

/** The gateway codes this build can configure, for the editor's picker. */
export function listPaymentGateways(accessToken: string) {
  return dataRequest<Record<string, string>>(
    "/api/v2/admin/payment/getPaymentMethods",
    accessToken
  );
}

/**
 * The fields of one gateway, with anything already stored filled in. Asked for
 * by gateway code alone for a new method, or by id for an existing one.
 */
export function fetchPaymentForm(
  accessToken: string,
  payment: string,
  id?: string
) {
  return dataRequest<Record<string, GatewayField>>(
    "/api/v2/admin/payment/getPaymentForm",
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ payment, id: id ?? null })
    }
  );
}

export function savePaymentMethod(
  accessToken: string,
  draft: PaymentMethodDraft
) {
  return dataRequest<string>("/api/v2/admin/payment/save", accessToken, {
    method: "POST",
    body: JSON.stringify(draft)
  });
}

/** Flips the switch and answers the state it is in now. */
export function togglePaymentMethod(accessToken: string, id: string) {
  return dataRequest<boolean>("/api/v2/admin/payment/show", accessToken, {
    method: "POST",
    body: JSON.stringify({ id })
  });
}

export function deletePaymentMethod(accessToken: string, id: string) {
  return dataRequest<boolean>("/api/v2/admin/payment/drop", accessToken, {
    method: "POST",
    body: JSON.stringify({ id })
  });
}

export function sortPaymentMethods(accessToken: string, ids: string[]) {
  return dataRequest<boolean>("/api/v2/admin/payment/sort", accessToken, {
    method: "POST",
    body: JSON.stringify({ ids })
  });
}
