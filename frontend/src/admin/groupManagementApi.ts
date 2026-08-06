import { ApiError } from "../lib/http";
import type { ProblemDetails } from "../types";

export type ManagedNodeGroup = {
  id: number;
  name: string;
  users_count: number;
  server_count: number;
  created_at: number;
  updated_at: number;
};

export type NodeGroupDraft = {
  id?: number;
  name: string;
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

export function listNodeGroups(accessToken: string) {
  return dataRequest<ManagedNodeGroup[]>(
    "/api/v2/admin/server/group/fetch",
    accessToken
  );
}

export function saveNodeGroup(
  accessToken: string,
  draft: NodeGroupDraft
) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/group/save",
    accessToken,
    { method: "POST", body: JSON.stringify(draft) }
  );
}

export function deleteNodeGroup(accessToken: string, id: number) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/group/drop",
    accessToken,
    { method: "POST", body: JSON.stringify({ id }) }
  );
}
