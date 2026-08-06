import { ApiError } from "../lib/http";
import type { ProblemDetails } from "../types";

export const EDITABLE_ROUTE_ACTIONS = ["block", "direct", "proxy"] as const;

export type EditableRouteAction =
  (typeof EDITABLE_ROUTE_ACTIONS)[number];

export type ManagedNodeRoute = {
  id: number;
  remarks: string;
  match: string[];
  action: string;
  action_value: string | null;
  created_at: number;
  updated_at: number;
};

export type NodeRouteDraft = {
  id?: number;
  remarks: string;
  match: string[];
  action: EditableRouteAction;
  action_value: string | null;
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

export function listNodeRoutes(accessToken: string) {
  return dataRequest<ManagedNodeRoute[]>(
    "/api/v2/admin/server/route/fetch",
    accessToken
  );
}

export function saveNodeRoute(
  accessToken: string,
  draft: NodeRouteDraft
) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/route/save",
    accessToken,
    { method: "POST", body: JSON.stringify(draft) }
  );
}

export function deleteNodeRoute(accessToken: string, id: number) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/route/drop",
    accessToken,
    { method: "POST", body: JSON.stringify({ id }) }
  );
}
