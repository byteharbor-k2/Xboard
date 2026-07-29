import type {
  LoginResult,
  MfaEnrollment,
  MfaEnrollmentComplete,
  MfaStatus,
  ProblemDetails,
  SessionGrant
} from "../types";

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
): Promise<LoginResult> {
  const response = await fetch("/session/login", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, deviceLabel })
  });
  return parseResponse<LoginResult>(response);
}

export async function register(
  email: string,
  password: string,
  displayName: string,
  deviceLabel: string
): Promise<SessionGrant> {
  const response = await fetch("/session/register", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, displayName, deviceLabel })
  });
  return parseResponse<SessionGrant>(response);
}

export async function completeMfaLogin(
  challengeToken: string,
  code: string
): Promise<SessionGrant> {
  const response = await fetch("/session/login/mfa", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ challengeToken, code })
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

export async function requestEmailVerification(
  accessToken: string
): Promise<void> {
  const response = await fetch("/session/email-verification/request", {
    method: "POST",
    credentials: "include",
    headers: { Authorization: `Bearer ${accessToken}` }
  });
  return parseResponse<void>(response);
}

export async function requestPasswordReset(email: string): Promise<void> {
  const response = await fetch("/session/password-reset/request", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email })
  });
  return parseResponse<void>(response);
}

export async function confirmPasswordReset(
  token: string,
  newPassword: string
): Promise<void> {
  const response = await fetch("/session/password-reset/confirm", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, newPassword })
  });
  return parseResponse<void>(response);
}

export async function changePassword(
  accessToken: string,
  currentPassword: string,
  newPassword: string
): Promise<void> {
  const response = await fetch("/session/password", {
    method: "PUT",
    credentials: "include",
    headers: bearer(accessToken),
    body: JSON.stringify({ currentPassword, newPassword })
  });
  return parseResponse<void>(response);
}

function bearer(accessToken: string) {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${accessToken}`
  };
}

export async function getMfaStatus(
  accessToken: string
): Promise<MfaStatus> {
  const response = await fetch("/session/mfa", {
    credentials: "include",
    headers: { Authorization: `Bearer ${accessToken}` }
  });
  return parseResponse<MfaStatus>(response);
}

export async function startMfaEnrollment(
  accessToken: string
): Promise<MfaEnrollment> {
  const response = await fetch("/session/mfa/enrollment", {
    method: "POST",
    credentials: "include",
    headers: bearer(accessToken)
  });
  return parseResponse<MfaEnrollment>(response);
}

export async function confirmMfaEnrollment(
  accessToken: string,
  code: string
): Promise<MfaEnrollmentComplete> {
  const response = await fetch("/session/mfa/enrollment/confirm", {
    method: "POST",
    credentials: "include",
    headers: bearer(accessToken),
    body: JSON.stringify({ code })
  });
  return parseResponse<MfaEnrollmentComplete>(response);
}

export async function disableMfa(
  accessToken: string,
  password: string,
  code: string
): Promise<void> {
  const response = await fetch("/session/mfa", {
    method: "DELETE",
    credentials: "include",
    headers: bearer(accessToken),
    body: JSON.stringify({ password, code })
  });
  return parseResponse<void>(response);
}

export async function graphQl<T>(
  accessToken: string,
  query: string,
  variables?: Record<string, unknown>
): Promise<T> {
  return gatewayRequest<T>(query, variables, accessToken);
}

export async function publicGraphQl<T>(
  query: string,
  variables?: Record<string, unknown>
): Promise<T> {
  return gatewayRequest<T>(query, variables);
}

async function gatewayRequest<T>(
  query: string,
  variables?: Record<string, unknown>,
  accessToken?: string
): Promise<T> {
  const response = await fetch("/gateway", {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(accessToken
        ? { Authorization: `Bearer ${accessToken}` }
        : {})
    },
    body: JSON.stringify({ query, variables })
  });
  const payload = (await response.json()) as {
    data?: T;
    errors?: Array<{ message: string }>;
  };
  if (!response.ok || payload.errors?.length || !payload.data) {
    throw new ApiError(response.ok ? 500 : response.status, {
      detail: payload.errors?.[0]?.message ?? "请求未能完成"
    });
  }
  return payload.data;
}
