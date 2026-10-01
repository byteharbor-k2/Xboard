import { ApiError, adminSessionGuard } from "../lib/http";

const prefix = import.meta.env.VITE_ADMIN_API_PREFIX ?? "/api/v2/admin";

export type SupportConversation = {
  userId: string;
  displayName: string;
  email: string;
  lastMessage: string | null;
  lastSender: "USER" | "ADMIN" | null;
  updatedAt: string;
};

export type SupportMessage = {
  sender: "USER" | "ADMIN";
  content: string;
  createdAt: string;
};

async function request<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const response = await adminSessionGuard.authorizedFetch(path, {
    ...init,
    credentials: "include",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers
    }
  });
  if (!response.ok) {
    let problem: { detail?: string } = {};
    try { problem = await response.json() as { detail?: string }; } catch { /* response has no problem body */ }
    throw new ApiError(response.status, { detail: problem.detail ?? "客服请求失败" });
  }
  return await response.json() as T;
}

export function listSupportConversations(token: string) {
  return request<{ data: SupportConversation[] }>(`${prefix}/support/conversations`, token).then(result => result.data);
}

export function listSupportMessages(token: string, userId: string) {
  return request<{ data: SupportMessage[] }>(`${prefix}/support/conversations/${encodeURIComponent(userId)}/messages`, token).then(result => result.data);
}

export function replyToSupportConversation(token: string, userId: string, content: string) {
  return request<{ data: SupportMessage }>(`${prefix}/support/conversations/${encodeURIComponent(userId)}/reply`, token, {
    method: "POST", body: JSON.stringify({ content })
  }).then(result => result.data);
}
