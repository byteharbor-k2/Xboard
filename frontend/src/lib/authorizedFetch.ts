/**
 * Single-flight 401 recovery for bearer-token sessions.
 *
 * Access tokens are short-lived (user: 10 min, admin: 5 min) while a screen
 * such as the admin dashboard issues 6-8 requests in flight. When the token
 * expires, all of them 401 at once. If each of them triggered a refresh, the
 * browser would race the rotation endpoint: this backend's refresh has replay
 * detection (RefreshTokenReplayWindow, a 10 s tolerance around a rotation),
 * and a race that falls outside the window revokes the whole session family
 * and logs the user out.
 *
 * The gate created here guarantees that no matter how many 401s arrive
 * concurrently, at most one refresh is in flight at any time:
 *
 *   - the first token-expiry 401 triggers `config.refresh()`;
 *   - every other concurrent token-expiry 401 awaits the very same promise;
 *   - once the promise settles it is dropped, so a *later* expiry can still
 *     trigger a fresh refresh (the gate never latches);
 *   - each request is retried exactly once with the newly issued token -
 *     never more, so a permanently dead session cannot loop;
 *   - when the refresh is rejected with 401/403, or a retried request is
 *     rejected again by the security layer, `config.onSessionExpired()` is
 *     called at most once per burst (the caller clears local session state
 *     and redirects to the login page);
 *   - 401s from auth endpoints (login / refresh / register / password reset
 *     / logout) are returned as-is: they are never refreshed, never retried,
 *     so the refresh flow can never recurse into itself.
 *
 * TWO KINDS OF 401 EXIST ON PROTECTED ENDPOINTS, and only one of them means
 * "the session is dead". The gate MUST tell them apart before it touches the
 * refresh, because confusing the two logs a perfectly healthy user out:
 *
 *   - TOKEN-EXPIRY 401: the security layer rejected the bearer token
 *     (expired / malformed / revoked). Spring Security answers these with a
 *     `WWW-Authenticate: Bearer error="invalid_token", ...` challenge header
 *     and an EMPTY body. This is the only 401 the gate refreshes on.
 *   - BUSINESS 401: the token was accepted, the request reached the
 *     controller, and the controller answered 401 for a business reason:
 *     PUT /session/password with a wrong current password
 *     (CURRENT_PASSWORD_INVALID), DELETE /admin-session/mfa with a wrong
 *     password (INVALID_CREDENTIALS) or a wrong TOTP code (INVALID_MFA_CODE).
 *     These carry a ProblemDetail JSON body and NO WWW-Authenticate header.
 *     The session is ALIVE: the gate returns the response untouched so the
 *     caller's `parseResponse -> ApiError` path shows the real error ("The
 *     current password is incorrect") instead of unmounting the page and
 *     redirecting to login. Do not "simplify" this distinction away - the
 *     backend deliberately reuses 401 for bad credentials on authenticated
 *     endpoints, and a blanket "any 401 = session dead" rule turns every
 *     typo in a password field into a forced logout.
 *
 * The discrimination is header-only on purpose: a Response body can be read
 * exactly once, and reading it here to look for a ProblemDetail would
 * consume it, leaving the caller nothing to show. `response.headers.get`
 * never touches the body.
 *
 * Conservative fallbacks (always fail toward "no logout"):
 *   - a 401 without a Bearer challenge is treated as a business 401 and
 *     returned as-is, even when the body cannot be inspected; the cost of a
 *     missed refresh is one error the user can retry, the cost of a false
 *     logout is a healthy user thrown off their session;
 *   - a refresh that fails for any reason OTHER than a 401/403 rejection
 *     (network error, 5xx) does NOT log the user out: the original 401 is
 *     returned so the caller can surface it, and the next request may try a
 *     fresh refresh. Only a 401/403 from the refresh endpoint itself proves
 *     the session family is gone (expired, revoked, suspended).
 *
 * The gate returns raw `Response` objects. Callers keep their existing
 * "response -> ApiError / data" mapping, so an unrecoverable 401 surfaces
 * through the normal error path after the logout redirect has happened.
 *
 * This module is deliberately dependency-free: the orchestration is a pure
 * promise state machine, which is what lets it be exercised by a standalone
 * script under `node --experimental-strip-types` (scripts/authorizedFetch.check.ts).
 */

export type FetchImpl = (
  input: RequestInfo | URL,
  init?: RequestInit
) => Promise<Response>;

export interface SessionRefreshGateConfig {
  /**
   * Perform ONE session refresh (POST .../refresh with the HttpOnly cookie)
   * and resolve with the newly issued access token. It is invoked at most
   * once per burst of concurrent 401s - that is the whole point of the gate.
   *
   * Rejection contract (see the module doc):
   *   - session family gone (refresh endpoint answered 401/403): reject with
   *     `SessionRefreshRejectedError` - the only refresh failure that makes
   *     the gate call `onSessionExpired`;
   *   - anything else (network error, 5xx): reject with a plain `Error` -
   *     the gate keeps the user logged in and hands the original 401 back.
   */
  refresh: () => Promise<string>;

  /**
   * Called at most once per expiry burst when the session is no longer
   * recoverable: the refresh endpoint rejected the refresh (401/403), or a
   * retried request was rejected again by the security layer. Implementations
   * should clear their local session state and redirect to the matching
   * login page. It is safe to call it when the user is already on the login
   * page.
   */
  onSessionExpired: () => void;

  /** Injectable for tests; defaults to the global fetch. */
  fetchImpl?: FetchImpl;
}

/**
 * The refresh endpoint itself rejected the refresh (401/403): the session
 * family is expired, revoked, or suspended and no retry can fix it. The gate
 * calls `onSessionExpired` only for this rejection; any other refresh
 * failure (network error, 5xx) must be a plain Error so nobody gets logged
 * out over a transient hiccup.
 */
export class SessionRefreshRejectedError extends Error {
  readonly status: number;

  constructor(status: number, detail: string) {
    super(detail);
    this.name = "SessionRefreshRejectedError";
    this.status = status;
  }
}

/**
 * Endpoints that establish, rotate or tear down the session itself. A 401
 * from any of them is terminal for that call: refreshing on top of a failed
 * refresh would recurse, and racing refreshes against each other is exactly
 * what the replay window treats as an attack.
 */
const AUTH_ENDPOINT_PREFIXES = [
  "/session/login",
  "/session/register",
  "/session/refresh",
  "/session/current",
  "/session/registration/",
  "/session/password-reset/",
  "/admin-session/login",
  "/admin-session/enrollment",
  "/admin-session/refresh",
  "/admin-session/current"
] as const;

export function isAuthEndpointPath(path: string): boolean {
  return AUTH_ENDPOINT_PREFIXES.some((prefix) => path.startsWith(prefix));
}

/**
 * Normalize any fetch input to "pathname + search" so the prefix table above
 * matches every input shape. `Request.url` is an ABSOLUTE url (e.g.
 * "http://localhost:8080/session/login"), which would silently defeat the
 * `startsWith` prefixes and make the gate treat an auth endpoint as a
 * protected resource; absolute strings have the same problem.
 */
function pathOf(input: RequestInfo | URL): string {
  if (typeof input === "string") {
    if (!input.includes("://")) return input; // relative path, already fine
    try {
      const url = new URL(input);
      return url.pathname + url.search;
    } catch {
      return input;
    }
  }
  if (input instanceof URL) return input.pathname + input.search;
  return new URL(input.url).pathname + new URL(input.url).search;
}

/**
 * True when this 401 was emitted by the SECURITY layer (the bearer token
 * itself was rejected: expired, malformed, revoked) rather than by a
 * controller. The signal is a `WWW-Authenticate` header whose value starts
 * with the "Bearer" auth-scheme (RFC 6750 schemes are case-insensitive);
 * business 401s from this backend never carry it.
 *
 * Header-only by design - reading the body to check for a ProblemDetail
 * would consume it (a Response body is single-read) and the caller would
 * lose the error message it has to show the user.
 */
function isTokenExpiryChallenge(response: Response): boolean {
  const challenge = response.headers.get("www-authenticate");
  if (challenge === null) return false;
  return challenge.trim().toLowerCase().startsWith("bearer");
}

/**
 * Returns a plain header object identical to `headers` except that every
 * Authorization entry (case-insensitive) is replaced by the freshly issued
 * token, so a retry never sends the token that just 401'd.
 */
function withBearerToken(
  headers: HeadersInit | undefined,
  token: string
): Record<string, string> {
  let normalized: Record<string, string> = {};
  if (headers instanceof Headers) {
    headers.forEach((value, key) => {
      normalized[key] = value;
    });
  } else if (Array.isArray(headers)) {
    for (const [key, value] of headers) normalized[key] = value;
  } else if (headers) {
    normalized = { ...headers };
  }
  for (const key of Object.keys(normalized)) {
    if (key.toLowerCase() === "authorization") delete normalized[key];
  }
  normalized.Authorization = `Bearer ${token}`;
  return normalized;
}

export interface SessionRefreshGate {
  /**
   * `fetch` with 401 recovery: on a token-expiry 401 from a protected
   * endpoint, refresh the session single-flight and retry the identical
   * request once with the new access token. Business 401s (the token was
   * fine, a controller rejected the credentials) are returned untouched.
   * Returns the (possibly retried) response.
   */
  authorizedFetch: (
    input: RequestInfo | URL,
    init?: RequestInit
  ) => Promise<Response>;
}

export function createSessionRefreshGate(
  config: SessionRefreshGateConfig
): SessionRefreshGate {
  const doFetch: FetchImpl =
    config.fetchImpl ?? ((input, init) => fetch(input, init));

  /** The single in-flight refresh, shared by every concurrent 401. */
  let inFlightRefresh: Promise<string> | null = null;
  /** True once this burst reported the expiry; reset by any success. */
  let expiryNotified = false;

  function refreshOnce(): Promise<string> {
    if (inFlightRefresh === null) {
      inFlightRefresh = Promise.resolve()
        .then(() => config.refresh())
        .then((token) => {
          if (typeof token !== "string" || token.length === 0) {
            throw new Error("session refresh did not produce an access token");
          }
          return token;
        })
        .finally(() => {
          inFlightRefresh = null;
        });
    }
    return inFlightRefresh;
  }

  function reportSessionExpired(): void {
    if (expiryNotified) return;
    expiryNotified = true;
    try {
      config.onSessionExpired();
    } catch {
      // The recovery hook must never break the request flow itself.
    }
  }

  return {
    async authorizedFetch(input, init) {
      const response = await doFetch(input, init);

      // Not a 401, or a 401 from an auth endpoint: nothing to recover.
      if (response.status !== 401 || isAuthEndpointPath(pathOf(input))) {
        if (response.ok) expiryNotified = false;
        return response;
      }

      // 401 on a protected endpoint. FIRST decide whether the security layer
      // rejected the token (refreshable) or a controller rejected the
      // credentials (business 401 - the session is alive). See the module
      // doc: refreshing on a business 401 wastes a refresh-token rotation
      // and, when the retry 401s again, logs the user out of a healthy
      // session while hiding the real error ("wrong current password").
      if (!isTokenExpiryChallenge(response)) {
        expiryNotified = false;
        return response;
      }

      let newToken: string;
      try {
        newToken = await refreshOnce();
      } catch (error) {
        // Only a 401/403 from the refresh endpoint proves the session
        // family is dead. A network blip or 5xx must NOT log the user out:
        // hand the original 401 back so the caller surfaces it, and let the
        // next request try a fresh refresh.
        if (error instanceof SessionRefreshRejectedError) {
          reportSessionExpired();
        }
        return response;
      }

      const retried = await doFetch(input, {
        ...init,
        headers: withBearerToken(init?.headers, newToken)
      });

      if (retried.status === 401) {
        // Still 401 after the rotation - same discrimination as above. If
        // the security layer rejects the FRESH token, the session is
        // unrecoverable (never loop). If a controller rejected the request
        // (e.g. the token expired in the same moment the user typed a wrong
        // password), the session is alive and the error must reach the
        // caller, not the logout.
        if (isTokenExpiryChallenge(retried)) {
          reportSessionExpired();
          return retried;
        }
        expiryNotified = false;
        return retried;
      }

      // The fresh token passed the security layer: the expiry flag no
      // longer applies, so a genuine later expiry can still report.
      expiryNotified = false;
      return retried;
    }
  };
}
