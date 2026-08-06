import { ApiError } from "../lib/http";
import type { ProblemDetails } from "../types";

export type MachineLoadStatus = {
  cpu: number;
  mem: { total: number; used: number };
  swap: { total: number; used: number };
  disk: { total: number; used: number };
  net?: { in_speed: number; out_speed: number };
  updated_at: number;
};

export type ManagedMachine = {
  id: number;
  name: string;
  notes: string | null;
  is_active: boolean;
  last_seen_at: number | null;
  load_status: MachineLoadStatus | null;
  servers_count: number;
  created_at: number;
  updated_at: number;
};

export type MachineDraft = {
  id?: number;
  name: string;
  notes: string;
  is_active: boolean;
};

export type MachineCredential = {
  id: number;
  token: string;
  install_command?: string;
};

export type MachineLoadHistory = {
  cpu: number;
  mem_total: number;
  mem_used: number;
  disk_total: number;
  disk_used: number;
  net_in_speed: number | null;
  net_out_speed: number | null;
  recorded_at: number;
};

export type MachineNode = {
  id: number;
  type: string;
  name: string;
  machine_id: number | null;
  host: string | null;
  port: number | null;
  server_port: number;
  enabled: boolean;
  show: boolean;
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

export function listMachines(accessToken: string) {
  return dataRequest<ManagedMachine[]>(
    "/api/v2/admin/server/machine/fetch",
    accessToken
  );
}

export function saveMachine(accessToken: string, draft: MachineDraft) {
  return dataRequest<MachineCredential | true>(
    "/api/v2/admin/server/machine/save",
    accessToken,
    { method: "POST", body: JSON.stringify(draft) }
  );
}

export function deleteMachine(accessToken: string, id: number) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/machine/drop",
    accessToken,
    { method: "POST", body: JSON.stringify({ id }) }
  );
}

export function rotateMachineToken(accessToken: string, id: number) {
  return dataRequest<{ token: string }>(
    "/api/v2/admin/server/machine/resetToken",
    accessToken,
    { method: "POST", body: JSON.stringify({ id }) }
  );
}

export function getMachineToken(accessToken: string, id: number) {
  return dataRequest<{ token: string }>(
    `/api/v2/admin/server/machine/getToken?id=${id}`,
    accessToken
  );
}

export function getMachineInstallCommand(accessToken: string, id: number) {
  return dataRequest<{ command: string }>(
    `/api/v2/admin/server/machine/installCommand?id=${id}`,
    accessToken
  );
}

export function getMachineHistory(
  accessToken: string,
  id: number,
  rangeHours = 24,
  limit = 1_440
) {
  return dataRequest<MachineLoadHistory[]>(
    `/api/v2/admin/server/machine/history?machine_id=${id}&range_hours=${rangeHours}&limit=${limit}`,
    accessToken
  );
}

export function getMachineNodes(accessToken: string, id: number) {
  return dataRequest<MachineNode[]>(
    `/api/v2/admin/server/machine/nodes?machine_id=${id}`,
    accessToken
  );
}

export function listMachineAssignableNodes(accessToken: string) {
  return dataRequest<MachineNode[]>(
    "/api/v2/admin/server/manage/getNodes",
    accessToken
  );
}

export function updateMachineNode(
  accessToken: string,
  id: number,
  values: { enabled?: boolean; machine_id?: number | null }
) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/manage/update",
    accessToken,
    { method: "POST", body: JSON.stringify({ id, ...values }) }
  );
}

export function bindMachineNodes(
  accessToken: string,
  machineId: number,
  ids: number[]
) {
  return dataRequest<boolean>(
    "/api/v2/admin/server/manage/batchUpdate",
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({
        ids,
        machine_id: machineId,
        update_machine: true
      })
    }
  );
}
