import { ApiError, adminSessionGuard } from "../lib/http";
import type { ProblemDetails } from "../types";

const adminApiPrefix =
  import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

/**
 * Knowledge-base articles on the Xboard-compatible admin surface: the same
 * snake_case field names the original panel's knowledge endpoints used,
 * epoch-second timestamps, category a free-form string.
 */
export type AdminKnowledgeSummary = {
  id: string;
  title: string;
  category: string;
  show: boolean;
  sort: number;
  created_at: number;
  updated_at: number;
};

/** fetch?id=... answers this; the bare list answer omits language and body. */
export type AdminKnowledgeFull = {
  id: string;
  title: string;
  category: string;
  language: string;
  body: string;
  show: boolean;
  sort: number;
  created_at: number;
  updated_at: number;
};

export type KnowledgeDraft = {
  id?: string;
  category: string;
  language: string;
  title: string;
  body: string;
  show?: boolean;
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

export function listKnowledge(accessToken: string) {
  return request<{ data: AdminKnowledgeSummary[] }>(
    `${adminApiPrefix}/knowledge/fetch`,
    accessToken
  ).then((response) => response.data);
}

export function getKnowledge(accessToken: string, id: string) {
  return request<{ data: AdminKnowledgeFull }>(
    `${adminApiPrefix}/knowledge/fetch?id=${encodeURIComponent(id)}`,
    accessToken
  ).then((response) => response.data);
}

export function listKnowledgeCategories(accessToken: string) {
  return request<{ data: string[] }>(
    `${adminApiPrefix}/knowledge/getCategory`,
    accessToken
  ).then((response) => response.data);
}

/** No id creates, an id edits - the original save's own contract. */
export function saveKnowledge(accessToken: string, draft: KnowledgeDraft) {
  return request<{ data: AdminKnowledgeFull }>(
    `${adminApiPrefix}/knowledge/save`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify(draft)
    }
  ).then((response) => response.data);
}

/** Toggles the article's help-centre visibility to its opposite. */
export function toggleKnowledgeShow(accessToken: string, id: string) {
  return request<{ data: AdminKnowledgeSummary }>(
    `${adminApiPrefix}/knowledge/show`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ id })
    }
  ).then((response) => response.data);
}

export function deleteKnowledge(accessToken: string, id: string) {
  return request<{ data: boolean }>(
    `${adminApiPrefix}/knowledge/drop`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ id })
    }
  ).then((response) => response.data);
}

/** Each id gets its list position as sort, starting at one. */
export function sortKnowledge(accessToken: string, ids: string[]) {
  return request<{ data: boolean }>(
    `${adminApiPrefix}/knowledge/sort`,
    accessToken,
    {
      method: "POST",
      body: JSON.stringify({ ids })
    }
  ).then((response) => response.data);
}
