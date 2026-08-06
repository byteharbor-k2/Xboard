import { ApiError } from "../lib/http";
import type { ProblemDetails } from "../types";

export const NODE_PROTOCOLS = [
  "shadowsocks", "vmess", "trojan", "hysteria", "vless", "tuic",
  "socks", "naive", "http", "mieru", "anytls"
] as const;

export type NodeProtocol = (typeof NODE_PROTOCOLS)[number];

export type ManagedNode = {
  id: number;
  type: NodeProtocol;
  code: string | null;
  parent_id: number | null;
  machine_id: number | null;
  group_ids: number[];
  route_ids: number[];
  name: string;
  rate: number;
  rate_time_enable: boolean;
  rate_time_ranges: unknown[];
  transfer_enable: number;
  u: number;
  d: number;
  tags: string[];
  host: string | null;
  port: number | null;
  server_port: number;
  protocol_settings: Record<string, unknown>;
  custom_outbounds: unknown[];
  custom_routes: unknown[];
  cert_config: Record<string, unknown> | null;
  show: boolean;
  enabled: boolean;
  sort: number;
  onlineUsers: number;
  online_conn: number;
  last_check_at: number | null;
  load_status?: Record<string, unknown> | null;
  metrics?: Record<string, unknown> | null;
  last_push_at?: number | null;
  created_at?: number;
  updated_at?: number;
};

export type NodeDraft = {
  id?: number;
  type: NodeProtocol;
  code: string | null;
  parent_id: number | null;
  name: string;
  machine_id: number | null;
  host: string;
  port: number | null;
  server_port: number;
  rate: number;
  rate_time_enable: boolean;
  rate_time_ranges: RateTimeRange[];
  transfer_enable: number;
  show: boolean;
  enabled: boolean;
  protocol_settings: Record<string, unknown>;
  group_ids: number[];
  route_ids: number[];
  tags: string[];
  custom_outbounds: unknown[];
  custom_routes: unknown[];
  cert_config: Record<string, unknown> | null;
  sort?: number;
};

export type RateTimeRange = {
  start: string;
  end: string;
  rate: number;
};

export type NodeSortItem = { id: number; order: number };

export type NodeBatchUpdate = {
  ids: number[];
  show?: boolean;
  enabled?: boolean;
  machine_id?: number | null;
  update_machine?: boolean;
};

export type EchKeyPair = { key: string; config: string };

async function request<T>(path: string, accessToken: string, init?: RequestInit): Promise<T> {
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
    try { problem = (await response.json()) as ProblemDetails; }
    catch { problem = { detail: "请求未能完成" }; }
    throw new ApiError(response.status, problem);
  }
  return (await response.json()) as T;
}

function dataRequest<T>(path: string, token: string, init?: RequestInit) {
  return request<{ data: T }>(path, token, init).then((response) => response.data);
}

export function listNodes(token: string) {
  return dataRequest<ManagedNode[]>("/api/v2/admin/server/manage/getNodes", token);
}

export function saveNode(token: string, draft: NodeDraft) {
  return dataRequest<ManagedNode>("/api/v2/admin/server/manage/save", token, {
    method: "POST", body: JSON.stringify(draft)
  });
}

export function updateNode(token: string, id: number, values: Partial<Pick<ManagedNode, "show" | "enabled" | "machine_id">>) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/update", token, {
    method: "POST", body: JSON.stringify({ id, ...values })
  });
}

export function deleteNode(token: string, id: number) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/drop", token, {
    method: "POST", body: JSON.stringify({ id })
  });
}

export function copyNode(token: string, id: number) {
  return dataRequest<ManagedNode>("/api/v2/admin/server/manage/copy", token, {
    method: "POST", body: JSON.stringify({ id })
  });
}

export function resetNodeTraffic(token: string, id: number) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/resetTraffic", token, {
    method: "POST", body: JSON.stringify({ id })
  });
}

export function sortNodes(token: string, items: NodeSortItem[]) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/sort", token, {
    method: "POST", body: JSON.stringify(items)
  });
}

export function batchDeleteNodes(token: string, ids: number[]) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/batchDelete", token, {
    method: "POST", body: JSON.stringify({ ids })
  });
}

export function batchUpdateNodes(token: string, values: NodeBatchUpdate) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/batchUpdate", token, {
    method: "POST", body: JSON.stringify(values)
  });
}

export function batchResetNodeTraffic(token: string, ids: number[]) {
  return dataRequest<boolean>("/api/v2/admin/server/manage/batchResetTraffic", token, {
    method: "POST", body: JSON.stringify({ ids })
  });
}

export function generateEchKey(token: string, publicName: string) {
  return dataRequest<EchKeyPair>(
    `/api/v2/admin/server/manage/generateEchKey?public_name=${encodeURIComponent(publicName)}`,
    token
  );
}
