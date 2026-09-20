/**
 * Standalone check for the single-flight 401 recovery gate
 * (src/lib/authorizedFetch.ts). The frontend has no test framework, so the
 * core promise state machine is exercised directly:
 *
 *   cd frontend && npm run check
 *   (i.e. node --experimental-strip-types scripts/authorizedFetch.check.ts)
 *
 * The fake server models BOTH 401 flavors the real backend emits:
 *
 *   - token-expiry 401: the security layer rejected the bearer token
 *     (expired / malformed / revoked). 401 + `WWW-Authenticate:
 *     Bearer error="invalid_token", ...` header, empty body.
 *   - business 401: the token was accepted, the request reached a
 *     controller, and the controller answered 401 for a business reason
 *     (wrong current password, wrong TOTP code). 401 + ProblemDetail JSON
 *     body, NO WWW-Authenticate header.
 *
 * Only the first kind may trigger a refresh; the second must be returned
 * untouched so the caller's parseResponse -> ApiError path can show the
 * real error instead of logging the user out.
 *
 * Cases:
 *   1. N concurrent token-expiry 401s trigger exactly ONE refresh; every
 *      request is retried once with the new token (method / body /
 *      credentials preserved) and resolves with the 200 payload.
 *   2. A retried request that is rejected again by the security layer is
 *      given up (exactly 2 protected calls, no loop) and the expiry
 *      callback fires once.
 *   3. A refresh REJECTED with 401/403 returns the original 401, performs
 *      no retry, and fires the expiry callback exactly once for the burst.
 *   3b. A refresh that fails with 5xx does NOT log the user out and
 *       returns the original 401.
 *   3c. A refresh that fails with a network error does NOT log the user
 *       out and returns the original 401.
 *   4. Auth endpoints (login / refresh) are never refreshed or retried.
 *   5. The gate does not latch: a later expiry triggers a second refresh.
 *   6. isAuthEndpointPath classifies the real endpoint set correctly, and
 *      absolute-URL inputs (Request / URL objects) are still recognized.
 *   7. A business 401 (valid token, wrong credentials) is passed through:
 *      no refresh, no retry, no logout, body still readable by the caller.
 *   8. A token-expiry 401 (WWW-Authenticate: Bearer) refreshes + retries
 *      normally.
 *   9. The Authorization header is replaced case-insensitively on retry.
 *   10. Mixed burst (some retries 200, one still rejected by the security
 *       layer) fires the expiry callback exactly once.
 *   11. A 500 never triggers a refresh.
 *   12. A business 401 on the RETRY (token expired in the same moment the
 *       credentials were rejected) does not log the user out.
 */
import {
  createSessionRefreshGate,
  isAuthEndpointPath,
  SessionRefreshRejectedError,
  type FetchImpl
} from "../src/lib/authorizedFetch.ts";

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

let failures = 0;
function check(name: string, cond: boolean, detail = "") {
  console.log(`${cond ? "PASS" : "FAIL"}  ${name}${cond ? "" : `   [${detail}]`}`);
  if (!cond) failures++;
}

interface FakeCall {
  url: string;
  method: string;
  body: string | null;
  credentials: string | undefined;
  /** Raw header keys that are an Authorization header, in any casing. */
  authHeaderKeys: string[];
  /** The Authorization value (first match, any casing), or null. */
  auth: string | null;
}

interface Business401Rule {
  pathPrefix: string;
  code: string;
  detail: string;
}

interface FakeServerOptions {
  /**
   * Token the server accepts BEFORE any refresh in this scenario. null
   * models the defect: the token in the app's hand is already expired, so
   * nothing it sends is valid until a refresh succeeds.
   */
  preRefreshAccepted: string | null;
  /** The token a successful refresh rotates the server to accept. */
  nextAccepted: string;
  refreshBehavior:
    | "ok"
    | "fail-401"
    | "fail-403"
    | "fail-500"
    | "fail-network";
  /** If true, even the freshly rotated token is rejected (pathological). */
  still401AfterRefresh?: boolean;
  /**
   * Paths that answer a BUSINESS 401 (ProblemDetail body, no
   * WWW-Authenticate header) once the token check passes: the session is
   * alive, a controller rejected the credentials.
   */
  business401?: Business401Rule[];
  /**
   * Paths the security layer rejects even after a successful refresh -
   * e.g. one stale node behind the load balancer still holding the old
   * key. Used to build mixed bursts.
   */
  alwaysToken401?: string[];
  /** Paths that always answer 500 (reached the controller, upstream died). */
  always500?: string[];
  protectedDelayMs?: number;
  refreshDelayMs?: number;
}

function makeFakeServer(options: FakeServerOptions) {
  let accepted: string | null = options.preRefreshAccepted;
  let next = options.nextAccepted;
  let refreshCalls = 0;
  let protectedCalls = 0;
  let tokenChallenge401s = 0;
  const calls: FakeCall[] = [];

  function token401(description: string): Response {
    tokenChallenge401s++;
    return new Response("", {
      status: 401,
      headers: {
        "WWW-Authenticate": `Bearer error="invalid_token", error_description="${description}"`
      }
    });
  }

  function business401(rule: Business401Rule): Response {
    return Response.json(
      {
        detail: rule.detail,
        status: 401,
        type: `/problems/${rule.code.toLowerCase()}`,
        code: rule.code
      },
      { status: 401 }
    );
  }

  async function fakeFetch(
    input: Request | string | URL,
    init?: {
      method?: string;
      body?: string;
      credentials?: string;
      headers?: Record<string, string>;
    }
  ): Promise<Response> {
    const url =
      input instanceof Request
        ? input.url
        : input instanceof URL
          ? input.href
          : input;
    const headers = init?.headers ?? {};
    const authHeaderKeys = Object.keys(headers).filter(
      (key) => key.toLowerCase() === "authorization"
    );
    const auth =
      Object.entries(headers)
        .find(([key]) => key.toLowerCase() === "authorization")?.[1] ?? null;
    calls.push({
      url,
      method: init?.method ?? (input instanceof Request ? input.method : "GET"),
      body: init?.body ?? null,
      credentials: init?.credentials,
      authHeaderKeys,
      auth
    });

    // Only calls tagged by the gate's own refresh() count as "a refresh was
    // triggered". A direct request to /session/refresh (case 4) is an
    // ordinary protected call that must not be refreshed on top of.
    const fromGate = headers["X-Source"] === "gate-refresh";
    if (url === "/session/refresh" && fromGate) {
      refreshCalls++;
      await sleep(options.refreshDelayMs ?? 30);
      switch (options.refreshBehavior) {
        case "fail-401":
          return new Response("refresh denied", { status: 401 });
        case "fail-403":
          return new Response("refresh forbidden", { status: 403 });
        case "fail-500":
          return new Response("refresh exploded", { status: 500 });
        case "fail-network":
          throw new TypeError("fetch failed (network)");
        case "ok":
          accepted = next;
          return Response.json({ accessToken: next });
      }
    }

    protectedCalls++;
    await sleep(options.protectedDelayMs ?? 0);

    // Security layer (in front of the controllers): reject the bearer
    // token. A path in alwaysToken401 is rejected even after a successful
    // refresh (the stale-node model); still401AfterRefresh rejects
    // everything after a refresh.
    const stale = options.alwaysToken401?.some((p) => url.startsWith(p));
    const tokenValid =
      accepted !== null &&
      !options.still401AfterRefresh &&
      auth === `Bearer ${accepted}`;
    if (stale || !tokenValid) {
      return token401(stale ? "stale node" : "expired or unknown token");
    }

    // The request reached a controller.
    if (options.always500?.some((p) => url.startsWith(p))) {
      return new Response("boom", { status: 500 });
    }
    const business = options.business401?.find((r) =>
      url.startsWith(r.pathPrefix)
    );
    if (business) {
      return business401(business);
    }
    return Response.json({ ok: true, url, with: auth });
  }

  return {
    fakeFetch,
    /**
     * Expire whatever the server currently accepts and point the next
     * successful refresh at `token` - models the new token reaching its own
     * TTL while the app is still holding it.
     */
    expireAcceptedTo: (token: string) => {
      accepted = null;
      next = token;
    },
    refreshCalls: () => refreshCalls,
    protectedCalls: () => protectedCalls,
    tokenChallenge401s: () => tokenChallenge401s,
    calls: () => calls,
    retriedWith: (token: string) =>
      calls.filter(
        (call) => call.url.startsWith("/api/") && call.auth === `Bearer ${token}`
      ).length
  };
}

function makeGate(server: ReturnType<typeof makeFakeServer>) {
  let expiredCalls = 0;
  const gate = createSessionRefreshGate({
    // Mirrors the real refresh closures in src/lib/http.ts: 401/403 from
    // the refresh endpoint -> SessionRefreshRejectedError (logout); any
    // other failure -> plain Error (no logout).
    refresh: async () => {
      const response = await server.fakeFetch("/session/refresh", {
        headers: { "X-Source": "gate-refresh" }
      });
      if (response.ok) {
        const body = (await response.json()) as { accessToken: string };
        return body.accessToken;
      }
      if (response.status === 401 || response.status === 403) {
        throw new SessionRefreshRejectedError(
          response.status,
          `refresh rejected with ${response.status}`
        );
      }
      throw new Error(`refresh failed with ${response.status}`);
    },
    onSessionExpired: () => {
      expiredCalls++;
    },
    fetchImpl: server.fakeFetch as unknown as FetchImpl
  });
  return { gate, expiredCalls: () => expiredCalls };
}

const protectedInit = (token: string) => ({
  method: "POST" as const,
  body: JSON.stringify({ n: 1 }),
  credentials: "include" as const,
  headers: { Authorization: `Bearer ${token}` }
});

async function main() {
  console.log("--- case 1: 8 concurrent 401s -> one shared refresh ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      protectedDelayMs: 10 // all 401s land while the refresh is in flight
    });
    const { gate, expiredCalls } = makeGate(server);
    const responses = await Promise.all(
      Array.from({ length: 8 }, () =>
        gate.authorizedFetch("/api/v2/admin/user/update", protectedInit("EXPIRED"))
      )
    );
    const bodies = await Promise.all(responses.map((r) => r.json()));
    check(
      "exactly 1 refresh for 8 concurrent 401s",
      server.refreshCalls() === 1,
      `refreshCalls=${server.refreshCalls()}`
    );
    check("all 8 responses are 200", responses.every((r) => r.status === 200));
    check(
      "all 8 retries carried the NEW token",
      server.retriedWith("NEW") === 8,
      `retriedWithNew=${server.retriedWith("NEW")}`
    );
    // The retry must be the IDENTICAL request apart from the token: if it
    // silently dropped method/body/credentials, a POST would arrive as a
    // bodyless GET and every "recovered" request would quietly corrupt.
    const originalBody = JSON.stringify({ n: 1 });
    const retries = server
      .calls()
      .filter((c) => c.url.startsWith("/api/") && c.auth === "Bearer NEW");
    check(
      "retries preserved the method",
      retries.length === 8 && retries.every((c) => c.method === "POST"),
      JSON.stringify(retries.map((c) => c.method))
    );
    check(
      "retries preserved the body",
      retries.every((c) => c.body === originalBody),
      JSON.stringify(retries.map((c) => c.body))
    );
    check(
      "retries preserved the credentials mode",
      retries.every((c) => c.credentials === "include"),
      JSON.stringify(retries.map((c) => c.credentials))
    );
    check(
      "retries returned the server payload",
      bodies.every((b) => (b as { ok: boolean }).ok === true)
    );
    check("expiry callback not fired", expiredCalls() === 0);
  }

  console.log("--- case 2: retry still 401 -> give up, no loop ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      still401AfterRefresh: true
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch(
      "/api/v2/admin/user/update",
      protectedInit("EXPIRED")
    );
    check("final status is the retried 401", response.status === 401);
    check(
      "the retried 401 was a security-layer rejection (Bearer challenge)",
      response.headers.get("www-authenticate")?.startsWith("Bearer") === true
    );
    check(
      "exactly 2 protected calls (original + one retry)",
      server.protectedCalls() === 2,
      `protectedCalls=${server.protectedCalls()}`
    );
    check("exactly 1 refresh", server.refreshCalls() === 1);
    check("expiry callback fired once", expiredCalls() === 1);
  }

  console.log("--- case 3: refresh rejected (401/403) -> logout once, no retry ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "fail-401",
      protectedDelayMs: 10
    });
    const { gate, expiredCalls } = makeGate(server);
    const responses = await Promise.all(
      Array.from({ length: 5 }, () =>
        gate.authorizedFetch("/api/v2/admin/order/fetch", protectedInit("EXPIRED"))
      )
    );
    check(
      "all 5 responses are the original 401",
      responses.every((r) => r.status === 401)
    );
    check(
      "no retry happened (5 protected calls = the burst only)",
      server.protectedCalls() === 5,
      `protectedCalls=${server.protectedCalls()}`
    );
    check(
      "refresh attempted exactly once (single flight)",
      server.refreshCalls() === 1,
      `refreshCalls=${server.refreshCalls()}`
    );
    check(
      "expiry callback fired exactly once for the whole burst",
      expiredCalls() === 1,
      `expiredCalls=${expiredCalls()}`
    );
  }

  console.log("--- case 3b: refresh 5xx -> no logout, original 401 kept ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "fail-500"
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch(
      "/api/v2/admin/order/fetch",
      protectedInit("EXPIRED")
    );
    check("5xx refresh failure returns the original 401", response.status === 401);
    check(
      "5xx refresh failure performs no retry",
      server.protectedCalls() === 1,
      `protectedCalls=${server.protectedCalls()}`
    );
    check(
      "5xx refresh failure does NOT log the user out",
      expiredCalls() === 0,
      `expiredCalls=${expiredCalls()}`
    );
  }

  console.log("--- case 3c: refresh network failure -> no logout ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "fail-network"
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch(
      "/api/v2/admin/order/fetch",
      protectedInit("EXPIRED")
    );
    check("network refresh failure returns the original 401", response.status === 401);
    check(
      "network refresh failure performs no retry",
      server.protectedCalls() === 1,
      `protectedCalls=${server.protectedCalls()}`
    );
    check(
      "network refresh failure does NOT log the user out",
      expiredCalls() === 0,
      `expiredCalls=${expiredCalls()}`
    );
  }

  console.log("--- case 4: auth endpoints are never refreshed or retried ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "fail-401"
    });
    const { gate, expiredCalls } = makeGate(server);
    const login = await gate.authorizedFetch("/session/login", {
      method: "POST",
      headers: {}
    });
    check("login 401 is returned as-is", login.status === 401);
    check(
      "a 401 on /session/login triggers no refresh",
      server.refreshCalls() === 0,
      `refreshCalls=${server.refreshCalls()}`
    );
    check(
      "a 401 on /session/login is not retried",
      server.protectedCalls() === 1,
      `protectedCalls=${server.protectedCalls()}`
    );
    const refreshSelf = await gate.authorizedFetch("/session/refresh", {
      method: "POST"
    });
    check("401 on /session/refresh itself is returned as-is", refreshSelf.status === 401);
    check(
      "refresh does not recurse into itself",
      server.refreshCalls() === 0,
      `refreshCalls=${server.refreshCalls()}`
    );
    check("expiry callback not fired by auth 401s", expiredCalls() === 0);
  }

  console.log("--- case 5: gate does not latch; a later expiry refreshes again ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "T2",
      refreshBehavior: "ok"
    });
    const { gate, expiredCalls } = makeGate(server);
    const first = await gate.authorizedFetch("/api/v2/admin/a", protectedInit("T1"));
    server.expireAcceptedTo("T3");
    const second = await gate.authorizedFetch("/api/v2/admin/b", protectedInit("T2"));
    check("first burst refreshed and retried ok", first.status === 200);
    check(
      "second burst (new expiry) refreshed again",
      server.refreshCalls() === 2,
      `refreshCalls=${server.refreshCalls()}`
    );
    check(
      "second burst retried with T3 and resolved ok",
      second.status === 200 && server.retriedWith("T3") === 1,
      `retriedWithT3=${server.retriedWith("T3")}`
    );
    check("no expiry callbacks", expiredCalls() === 0);
  }

  console.log("--- case 6: endpoint classification + input normalization ---");
  {
    const mustExclude: Array<[string, boolean]> = [
      ["/session/login", true],
      ["/session/login?x=1", true],
      ["/session/register", true],
      ["/session/refresh", true],
      ["/session/current", true],
      ["/session/registration/config", true],
      ["/session/password-reset/request", true],
      ["/admin-session/login", true],
      ["/admin-session/login/mfa", true],
      ["/admin-session/refresh", true],
      ["/admin-session/current", true],
      // Protected resources: a 401 here MUST refresh + retry.
      ["/gateway", false],
      ["/session/password", false],
      ["/session/invitations", false],
      ["/admin-session/mfa", false],
      ["/api/v2/admin/user/update", false],
      ["/control/catalog/plans", false]
    ];
    let allGood = true;
    for (const [path, expected] of mustExclude) {
      const actual = isAuthEndpointPath(path);
      if (actual !== expected) {
        allGood = false;
        console.log(`      mismatch: ${path} -> ${actual}, expected ${expected}`);
      }
    }
    check("auth endpoints excluded, protected resources included", allGood);
  }
  {
    // Request objects carry an ABSOLUTE url; pathOf must normalize it to
    // pathname+search or the prefix table above stops matching and an auth
    // endpoint 401 would trigger a refresh on top of login.
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok"
    });
    const { gate } = makeGate(server);
    const loginRequest = new Request("http://localhost:8080/session/login", {
      method: "POST"
    });
    const response = await gate.authorizedFetch(loginRequest);
    check("401 on a Request('/session/login') is returned as-is", response.status === 401);
    check(
      "a Request input with an absolute URL is still an auth endpoint",
      server.refreshCalls() === 0,
      `refreshCalls=${server.refreshCalls()}`
    );
    const apiUrl = new URL("http://localhost:8080/api/v2/admin/url-input?x=1");
    const viaUrl = await gate.authorizedFetch(apiUrl, protectedInit("EXPIRED"));
    check(
      "a URL input to a protected path still refreshes + retries",
      viaUrl.status === 200 && server.refreshCalls() === 1,
      `refreshCalls=${server.refreshCalls()}`
    );
  }

  console.log("--- case 7: business 401 (valid token) passes through untouched ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: "TOKEN", // the session is ALIVE
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      business401: [
        {
          pathPrefix: "/session/password",
          code: "CURRENT_PASSWORD_INVALID",
          detail: "The current password is incorrect"
        }
      ]
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch("/session/password", {
      method: "PUT",
      credentials: "include",
      body: JSON.stringify({
        currentPassword: "wrong-password",
        newPassword: "NewPassword12345!"
      }),
      headers: { Authorization: "Bearer TOKEN" }
    });
    check("business 401 is returned as-is", response.status === 401);
    check(
      "a business 401 triggers NO refresh",
      server.refreshCalls() === 0,
      `refreshCalls=${server.refreshCalls()}`
    );
    check(
      "a business 401 is NOT retried",
      server.protectedCalls() === 1,
      `protectedCalls=${server.protectedCalls()}`
    );
    check(
      "a business 401 does NOT log the user out",
      expiredCalls() === 0,
      `expiredCalls=${expiredCalls()}`
    );
    const body = (await response.json()) as { code?: string; detail?: string };
    check(
      "the business 401 body is still readable by the caller",
      body.code === "CURRENT_PASSWORD_INVALID" &&
        body.detail === "The current password is incorrect",
      JSON.stringify(body)
    );
  }

  console.log("--- case 8: token-expiry 401 (WWW-Authenticate: Bearer) refreshes ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok"
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch(
      "/api/v2/admin/user/update",
      protectedInit("EXPIRED")
    );
    check(
      "the original 401 carried a Bearer challenge",
      server.tokenChallenge401s() === 1,
      `tokenChallenge401s=${server.tokenChallenge401s()}`
    );
    check("refreshed and retried to a 200", response.status === 200);
    check("exactly 1 refresh", server.refreshCalls() === 1);
    check("expiry callback not fired", expiredCalls() === 0);
  }

  console.log("--- case 9: Authorization header replaced case-insensitively ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      protectedDelayMs: 5 // both 401s land while the refresh is in flight
    });
    const { gate, expiredCalls } = makeGate(server);
    const init = (authKey: string) => ({
      method: "POST" as const,
      body: JSON.stringify({ n: 1 }),
      credentials: "include" as const,
      headers: { "Content-Type": "application/json", [authKey]: "Bearer EXPIRED" }
    });
    const [lower, upper] = await Promise.all([
      gate.authorizedFetch("/api/v2/low", init("authorization")),
      gate.authorizedFetch("/api/v2/up", init("AUTHORIZATION"))
    ]);
    check(
      "both casings recovered to 200",
      lower.status === 200 && upper.status === 200
    );
    check(
      "one shared refresh for both casings",
      server.refreshCalls() === 1,
      `refreshCalls=${server.refreshCalls()}`
    );
    const retries = server.calls().filter((c) => c.auth === "Bearer NEW");
    check(
      "both retries carried the NEW token",
      retries.length === 2,
      `retries=${retries.length}`
    );
    check(
      "each retry has exactly ONE authorization header (any casing)",
      retries.every((c) => c.authHeaderKeys.length === 1),
      JSON.stringify(retries.map((c) => c.authHeaderKeys))
    );
    check("expiry callback not fired", expiredCalls() === 0);
  }

  console.log("--- case 10: mixed burst -> expiry callback exactly once ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      protectedDelayMs: 10,
      alwaysToken401: ["/api/v2/stale"]
    });
    const { gate, expiredCalls } = makeGate(server);
    const responses = await Promise.all([
      gate.authorizedFetch("/api/v2/a", protectedInit("EXPIRED")),
      gate.authorizedFetch("/api/v2/b", protectedInit("EXPIRED")),
      gate.authorizedFetch("/api/v2/c", protectedInit("EXPIRED")),
      gate.authorizedFetch("/api/v2/stale", protectedInit("EXPIRED")),
      gate.authorizedFetch("/api/v2/d", protectedInit("EXPIRED"))
    ]);
    const stale = responses[3];
    const healthy = responses.filter((_, i) => i !== 3);
    check(
      "healthy requests recovered with the new token",
      healthy.every((r) => r.status === 200)
    );
    check("the stale-node request ends as the retried 401", stale.status === 401);
    check(
      "one shared refresh for the whole burst",
      server.refreshCalls() === 1,
      `refreshCalls=${server.refreshCalls()}`
    );
    check(
      "expiry callback fired exactly once for the burst",
      expiredCalls() === 1,
      `expiredCalls=${expiredCalls()}`
    );
    check(
      "no refresh loop after the stale 401",
      server.refreshCalls() === 1,
      `refreshCalls=${server.refreshCalls()}`
    );
  }

  console.log("--- case 11: a 500 never triggers a refresh ---");
  {
    const server = makeFakeServer({
      preRefreshAccepted: "TOKEN",
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      always500: ["/api/v2/broken"]
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch(
      "/api/v2/broken",
      protectedInit("TOKEN")
    );
    check("the 500 is returned as-is", response.status === 500);
    check(
      "a 500 triggers no refresh",
      server.refreshCalls() === 0,
      `refreshCalls=${server.refreshCalls()}`
    );
    check(
      "a 500 is not retried",
      server.protectedCalls() === 1,
      `protectedCalls=${server.protectedCalls()}`
    );
    check("a 500 does not log the user out", expiredCalls() === 0);
    const text = await response.text();
    check("the 500 body is still readable", text === "boom", text);
  }

  console.log("--- case 12: business 401 on the RETRY does not log out ---");
  {
    // The token expired at the same moment the user typed a wrong password:
    // the first 401 is the security layer's (refreshable), but the retry -
    // now carrying the fresh token - reaches the controller, which rejects
    // the password. The session is alive; only the credentials were wrong.
    const server = makeFakeServer({
      preRefreshAccepted: null,
      nextAccepted: "NEW",
      refreshBehavior: "ok",
      business401: [
        {
          pathPrefix: "/session/password",
          code: "CURRENT_PASSWORD_INVALID",
          detail: "The current password is incorrect"
        }
      ]
    });
    const { gate, expiredCalls } = makeGate(server);
    const response = await gate.authorizedFetch("/session/password", {
      method: "PUT",
      credentials: "include",
      body: JSON.stringify({
        currentPassword: "wrong-password",
        newPassword: "NewPassword12345!"
      }),
      headers: { Authorization: "Bearer EXPIRED" }
    });
    check("one refresh for the expired token", server.refreshCalls() === 1);
    check(
      "exactly 2 protected calls (original + one retry)",
      server.protectedCalls() === 2,
      `protectedCalls=${server.protectedCalls()}`
    );
    check(
      "the retry's business 401 does NOT log the user out",
      expiredCalls() === 0,
      `expiredCalls=${expiredCalls()}`
    );
    const body = (await response.json()) as { code?: string };
    check(
      "the real business error still reaches the caller",
      response.status === 401 && body.code === "CURRENT_PASSWORD_INVALID",
      JSON.stringify(body)
    );
  }

  console.log(failures === 0 ? "\nALL CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
  if (failures > 0) process.exitCode = 1;
}

void main();
