import { ApiError, adminSessionGuard } from "../lib/http";
import type { ProblemDetails } from "../types";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

/**
 * Notices on the Xboard-compatible admin surface: the same snake_case field
 * names the original panel's notice endpoints used, epoch-second timestamps.
 */
export type AdminNotice = {
  id: string;
  title: string;
  content: string;
  img_url: string | null;
  tags: string[];
  show: boolean;
  popup: boolean;
  sort: number;
  created_at: number;
  updated_at: number;
};

export type NoticeDraft = {
  id?: string;
  title: string;
  content: string;
  img_url?: string;
  tags?: string[];
  show?: boolean;
  popup?: boolean;
  sort?: number;
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

export function listNotices(accessToken: string) {
  return request<{ data: AdminNotice[] }>(
    `${adminApiPrefix}/notice/fetch`,
    accessToken
  ).then((response) => response.data);
}

/** No id creates, an id edits - the original save's own contract. */
export function saveNotice(accessToken: string, draft: NoticeDraft) {
  return request<{ data: AdminNotice }>(
    `${adminApiPrefix}/notice/save`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify(draft)
    }
  ).then((response) => response.data);
}

/** Toggles the notice's carousel visibility to its opposite. */
export function toggleNoticeShow(accessToken: string, noticeId: string) {
  return request<{ data: AdminNotice }>(
    `${adminApiPrefix}/notice/show`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ id: noticeId })
    }
  ).then((response) => response.data);
}

export function deleteNotice(accessToken: string, noticeId: string) {
  return request<{ data: boolean }>(
    `${adminApiPrefix}/notice/drop`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ id: noticeId })
    }
  ).then((response) => response.data);
}

/** Each id gets its list position as sort, starting at one. */
export function sortNotices(accessToken: string, ids: string[]) {
  return request<{ data: boolean }>(
    `${adminApiPrefix}/notice/sort`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ ids })
    }
  ).then((response) => response.data);
}
