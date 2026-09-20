import type {
  AdminLoginResult,
  LoginResult,
  MfaEnrollment,
  MfaEnrollmentComplete,
  MfaStatus,
  ProblemDetails,
  RegistrationConfig,
  SessionGrant
} from "../types";
import {
  createSessionRefreshGate,
  SessionRefreshRejectedError,
  type SessionRefreshGate
} from "./authorizedFetch";
import { useAuthStore } from "../store/auth";
import { useAdminAuthStore } from "../store/adminAuth";
import { navigate } from "./navigation";

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
  deviceLabel: string,
  emailCode: string | null,
  turnstileToken: string,
  inviteCode: string | null
): Promise<SessionGrant> {
  const response = await fetch("/session/register", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      email,
      password,
      displayName,
      deviceLabel,
      emailCode,
      turnstileToken,
      inviteCode
    })
  });
  return parseResponse<SessionGrant>(response);
}

export async function getRegistrationConfig(): Promise<RegistrationConfig> {
  const response = await fetch("/session/registration/config", {
    credentials: "include"
  });
  return parseResponse<RegistrationConfig>(response);
}

export async function requestRegistrationCode(
  email: string,
  turnstileToken: string
): Promise<void> {
  const response = await fetch("/session/registration/email-code", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, turnstileToken })
  });
  return parseResponse<void>(response);
}

export async function adminLogin(
  email: string,
  password: string,
  deviceLabel: string
): Promise<AdminLoginResult> {
  const response = await fetch("/admin-session/login", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, deviceLabel })
  });
  return parseResponse<AdminLoginResult>(response);
}

export async function completeAdminMfaLogin(
  challengeToken: string,
  code: string
): Promise<SessionGrant> {
  const response = await fetch("/admin-session/login/mfa", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ challengeToken, code })
  });
  return parseResponse<SessionGrant>(response);
}

export async function startAdminMfaEnrollment(
  enrollmentToken: string
): Promise<MfaEnrollment> {
  const response = await fetch("/admin-session/enrollment", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enrollmentToken })
  });
  return parseResponse<MfaEnrollment>(response);
}

export async function confirmAdminMfaEnrollment(
  enrollmentToken: string,
  code: string
): Promise<MfaEnrollmentComplete> {
  const response = await fetch("/admin-session/enrollment/confirm", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enrollmentToken, code })
  });
  return parseResponse<MfaEnrollmentComplete>(response);
}

export async function refreshSession(): Promise<SessionGrant> {
  const response = await fetch("/session/refresh", {
    method: "POST",
    credentials: "include"
  });
  return parseResponse<SessionGrant>(response);
}

export async function refreshAdminSession(): Promise<SessionGrant> {
  const response = await fetch("/admin-session/refresh", {
    method: "POST",
    credentials: "include"
  });
  return parseResponse<SessionGrant>(response);
}

/**
 * POST .../refresh and convert the outcome into the error semantics the 401
 * gate expects (see the gate's `refresh` contract in authorizedFetch.ts):
 *
 *   - 401/403 from the refresh endpoint: the session family itself is gone
 *     (refresh token expired, revoked, account suspended) ->
 *     `SessionRefreshRejectedError`, the ONLY refresh failure that logs the
 *     user out;
 *   - any other failure (5xx, network error): the session may be perfectly
 *     healthy, the transport is just having a bad moment -> plain `Error`,
 *     so the gate keeps the user logged in and hands the original 401 back
 *     to the caller.
 */
async function refreshGrant(path: string): Promise<SessionGrant> {
  const response = await fetch(path, {
    method: "POST",
    credentials: "include"
  });
  if (response.status === 401 || response.status === 403) {
    throw new SessionRefreshRejectedError(
      response.status,
      `session refresh rejected with ${response.status}`
    );
  }
  if (!response.ok) {
    throw new Error(`session refresh failed with ${response.status}`);
  }
  return (await response.json()) as SessionGrant;
}

/**
 * 401 recovery gate for the USER session (POST /session/refresh, cookie
 * `rt_session`). When the 10-minute access token expires, every request on
 * screen 401s at once; the gate funnels all of them through a single refresh
 * and retries each of them exactly once with the new token. The refreshed
 * grant is written back to the store so the next render picks up the new
 * token (query keys do not carry the token, so a store update is what keeps
 * subsequent requests healthy). See authorizedFetch.ts for the full contract.
 */
export const userSessionGuard = createSessionRefreshGate({
  refresh: async () => {
    const grant = await refreshGrant("/session/refresh");
    useAuthStore.getState().setSession(grant);
    return grant.accessToken;
  },
  onSessionExpired: () => {
    useAuthStore.getState().clearSession();
    const returnTo = encodeURIComponent(window.location.pathname);
    navigate(`/login?returnTo=${returnTo}`, true);
  }
});

/**
 * 401 recovery gate for the ADMIN session (POST /admin-session/refresh,
 * cookie `rt_admin`). Kept separate from the user-side gate: the two
 * sessions are independent families, so each must get exactly one
 * in-flight refresh of its own, and a dead admin session must redirect to
 * /admin/login (not /login) while a dead user session goes to /login.
 */
export const adminSessionGuard = createSessionRefreshGate({
  refresh: async () => {
    const grant = await refreshGrant("/admin-session/refresh");
    useAdminAuthStore.getState().setSession(grant);
    return grant.accessToken;
  },
  onSessionExpired: () => {
    useAdminAuthStore.getState().clearSession();
    navigate("/admin/login", true);
  }
});

export async function logout(): Promise<void> {
  const response = await fetch("/session/current", {
    method: "DELETE",
    credentials: "include"
  });
  return parseResponse<void>(response);
}

export async function adminLogout(): Promise<void> {
  const response = await fetch("/admin-session/current", {
    method: "DELETE",
    credentials: "include"
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
  const response = await userSessionGuard.authorizedFetch("/session/password", {
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
  const response = await adminSessionGuard.authorizedFetch(
    "/admin-session/mfa",
    {
      credentials: "include",
      headers: { Authorization: `Bearer ${accessToken}` }
    }
  );
  return parseResponse<MfaStatus>(response);
}

export async function disableMfa(
  accessToken: string,
  password: string,
  code: string
): Promise<void> {
  const response = await adminSessionGuard.authorizedFetch(
    "/admin-session/mfa",
    {
      method: "DELETE",
      credentials: "include",
      headers: bearer(accessToken),
      body: JSON.stringify({ password, code })
    }
  );
  return parseResponse<void>(response);
}

/**
 * Authenticated GraphQL, USER session only: the request goes through
 * `userSessionGuard`, whose refresh talks to POST /session/refresh. Every
 * current caller (orders, device sessions, account pages) passes a user
 * access token. If an ADMIN GraphQL call ever appears, it must NOT be sent
 * through this function - an admin token refreshed via the user endpoint
 * would fail, and a user token would refresh the wrong session. Add a
 * parallel `adminGraphQl` wired to `adminSessionGuard` instead (that is why
 * `gatewayRequest` takes the gate explicitly rather than picking one).
 */
export async function graphQl<T>(
  accessToken: string,
  query: string,
  variables?: Record<string, unknown>
): Promise<T> {
  return gatewayRequest<T>(query, variables, accessToken, userSessionGuard);
}

export async function publicGraphQl<T>(
  query: string,
  variables?: Record<string, unknown>
): Promise<T> {
  return gatewayRequest<T>(query, variables, undefined, undefined);
}

async function gatewayRequest<T>(
  query: string,
  variables?: Record<string, unknown>,
  accessToken?: string,
  guard?: SessionRefreshGate
): Promise<T> {
  const init: RequestInit = {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(accessToken
        ? { Authorization: `Bearer ${accessToken}` }
        : {})
    },
    body: JSON.stringify({ query, variables })
  };
  // Authenticated queries go through the 401 recovery gate: an expired
  // token makes the gateway answer a plain HTTP 401, the gate runs one
  // shared refresh and retries the query once with the new token. Public
  // queries carry no session at all, so they stay on plain fetch.
  // The gate is an explicit parameter (not a hardcoded userSessionGuard)
  // so an admin GraphQL helper cannot silently refresh the USER session.
  const response =
    accessToken && guard
      ? await guard.authorizedFetch("/gateway", init)
      : await fetch("/gateway", init);
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
