import type { ManagedPlan, PlanDraft, ProblemDetails } from "../types";
import { ApiError } from "../lib/http";

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

export function listManagedPlans(accessToken: string) {
  return request<ManagedPlan[]>("/control/catalog/plans", accessToken);
}

export function createManagedPlan(
  accessToken: string,
  draft: PlanDraft
) {
  return request<ManagedPlan>("/control/catalog/plans", accessToken, {
    method: "POST",
    body: JSON.stringify(draft)
  });
}

export function updateManagedPlan(
  accessToken: string,
  planId: string,
  draft: PlanDraft
) {
  return request<ManagedPlan>(
    `/control/catalog/plans/${encodeURIComponent(planId)}`,
    accessToken,
    {
      method: "PUT",
      body: JSON.stringify(draft)
    }
  );
}

export function deleteManagedPlan(
  accessToken: string,
  planId: string
) {
  return request<void>(
    `/control/catalog/plans/${encodeURIComponent(planId)}`,
    accessToken,
    { method: "DELETE" }
  );
}
