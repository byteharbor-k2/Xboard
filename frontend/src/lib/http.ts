import type { ProblemDetails, SessionGrant } from "../types";

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(status: number, problem: ProblemDetails) {
    super(problem.detail ?? "请求未能完成");
    this.name = "ApiError";
    this.status = status;
    this.code = problem.code;
  }
}

async function parseResponse<T>(response: Response): Promise<T> {
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

export async function login(
  email: string,
  password: string,
  deviceLabel: string
): Promise<SessionGrant> {
  const response = await fetch("/session/login", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, deviceLabel })
  });
  return parseResponse<SessionGrant>(response);
}

export async function refreshSession(): Promise<SessionGrant> {
  const response = await fetch("/session/refresh", {
    method: "POST",
    credentials: "include"
  });
  return parseResponse<SessionGrant>(response);
}

export async function logout(): Promise<void> {
  const response = await fetch("/session/current", {
    method: "DELETE",
    credentials: "include"
  });
  return parseResponse<void>(response);
}

export async function confirmEmail(token: string): Promise<void> {
  const response = await fetch("/session/email-verification/confirm", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token })
  });
  return parseResponse<void>(response);
}

export async function graphQl<T>(
  accessToken: string,
  query: string,
  variables?: Record<string, unknown>
): Promise<T> {
  const response = await fetch("/gateway", {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`
    },
    body: JSON.stringify({ query, variables })
  });
  const payload = (await response.json()) as {
    data?: T;
    errors?: Array<{ message: string }>;
  };
  if (!response.ok || payload.errors?.length || !payload.data) {
    throw new ApiError(response.status || 500, {
      detail: payload.errors?.[0]?.message ?? "请求未能完成"
    });
  }
  return payload.data;
}
