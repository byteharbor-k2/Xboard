---
name: cdp-browser
description: Drive a real Chrome over the Chrome DevTools Protocol to test and debug the local SinX website — frontend dev server http://localhost:5173, backend :8080, admin UI at /admin/login. Use whenever a task needs a real browser: "test the site", "click through the flow", "本地测试网站", check console errors, inspect network requests, take screenshots, verify login/checkout/admin pages end-to-end. Tools come from the `chrome-devtools` MCP server (opencode.json) attached to the project test Chrome on 127.0.0.1:9223, which uses the repo-local `.chrome-cdp-test/` profile.
metadata:
  version: "1.0.0"
  origin: "adapted from the DSH global skill ~/.dsh/skills/cdp-browser for OpenCode on 2026-09-28; verified against chrome-devtools-mcp 1.9.0, Chrome 154, macOS darwin arm64"
---

# CDP Browser — real-Chrome testing of the local site

A real Chrome the **project** owns, launched with `--remote-debugging-port=9223`
and the repo-local profile `.chrome-cdp-test/` (gitignored — logins persist
across restarts). opencode attaches via the `chrome-devtools` MCP server
(`opencode.json`) and exposes the chrome-devtools-mcp tool set (~29 tools;
opencode may prefix tool names with the server name). **Chrome is externally
owned**: the MCP never launches or kills it; closing the connection leaves the
browser and its pages untouched.

## Never touch the other Chrome

Port **9222** / profile `.chrome-cdp/` belongs to **other sessions' work**
(video transcription, etc.). Never kill, restart, or drive that instance, and
never `pkill` Chrome broadly. The project test browser is **9223 +
`.chrome-cdp-test`**. Identify instances with `pgrep -fl chrome-cdp`.

## Preconditions — check first, always

1. **Browser up**: `curl -sS --max-time 5 http://127.0.0.1:9223/json/version`
   (JSON reply = attachable). If down, launch it (recipe below).
2. **Site up**: frontend `http://localhost:5173` (vite dev), backend `:8080`,
   Postgres/Redis via `compose.dev.yml`. **Never start/restart the :8080
   backend** — it is the user's instance and someone may be testing against it
   (AGENTS.md workflow rule). Verify by reading pages, not by restarting.
3. **MCP tools present**: if the chrome-devtools tools are missing or error,
   Chrome was probably down when opencode started → launch Chrome, then
   restart opencode (the MCP binds at startup).

## Launching the test Chrome

```bash
mkdir -p "$PWD/.chrome-cdp-test"
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --remote-debugging-port=9223 \
  --remote-debugging-address=127.0.0.1 \
  --user-data-dir="$PWD/.chrome-cdp-test" \
  --no-first-run --no-default-browser-check &
```

Run it as a background job so it survives the tool call. Never use the user's
default profile — Chrome 136+ refuses CDP on it, and it would expose all their
sessions. The profile starts empty: sign in with the dev test accounts from
root `test_user.txt` (gitignored; never commit or inline its contents).

Closing when done: `pkill -f chrome-cdp-test` (matches only this instance —
9222's `.chrome-cdp/` does not contain that string).

## The core loop

Observation is text, not pixels:

1. `list_pages` / `select_page`, or `new_page` — open a **new tab** for
   testing; do not hijack tabs already open in the profile.
2. `navigate_page` (`type: "url"`).
3. `take_snapshot` — the main observation tool: an accessibility-tree **text**
   snapshot with stable `uid`s for interactive elements.
4. Act on a `uid` from that snapshot: `click`, `fill`, `fill_form`, `hover`,
   `press_key`.
5. Re-snapshot after any page change — **uids go stale** on navigation/DOM
   churn.

Prefer snapshot `uid` over CSS selectors — this SPA ships no `data-testid`
attributes, so selector-based clicking fails while `uid` works.

## Testing this site

- User site `http://localhost:5173`: `/login`, `/register`, `/forgot-password`,
  `/plans` and `/plans/{id}` (checkout), `/dashboard`, `/account/*`,
  `/security/sessions`.
- Admin: `/admin/login` → `/admin`, `/admin/finance/*`, `/admin/nodes/*`,
  `/admin/system/settings` — separate session and cookie from users.
- The frontend proxies `/gateway /session /admin-session /control /api /sub`
  to `:8080`, so network tools show the real backend traffic.
- Two 401 flavors exist (see AGENTS.md): token-expiry 401 (`WWW-Authenticate`
  header) triggers one silent refresh; business 401 (ProblemDetail body) must
  surface as an error. When testing auth flows, `list_network_requests` is how
  you tell which one happened.
- Suggested flow: `navigate_page` → `list_console_messages` (JS errors first)
  → reproduce via snapshot + click/fill → `get_network_request` on the
  failing call → `take_screenshot` to confirm what the user would see.

## Debugging domains (the reason this stack exists)

- **Network**: `list_network_requests`, then `get_network_request` for one
  request's headers/body/timing — verify API payloads, find 4xx/5xx, inspect
  auth headers.
- **Console**: `list_console_messages`, `get_console_message` — source-mapped
  stack traces.
- **Performance**: `performance_start_trace` → reproduce →
  `performance_stop_trace` → `performance_analyze_insight`;
  `take_heapsnapshot` for leak hunting.
- **Visual**: `take_screenshot` (the only pixel view), `emulate` /
  `resize_page` for responsive checks. `lighthouse_audit` for a full audit.

Full catalogue and argument shapes: `references/tools.md`.

## Honest limits

- `take_snapshot` is a text tree, not an image — layout breakage, overlap,
  colors, and canvas content are invisible unless `take_screenshot` is called.
- No visual hesitation: you will happily click "ban user". Confirm destructive
  admin actions (ban/delete user, node changes) with the user first, and
  prefer read-only verification until then.
- uids are per-snapshot; never reuse across navigation.
- Attach is **exclusive**: one client owns the endpoint. If tools suddenly
  error, another client may hold 9223 — check `lsof -nP -iTCP:9223`.

## Safety

- The CDP port is **unauthenticated** — anything that reaches it owns the
  browser. It binds to 127.0.0.1 only; never port-forward it, and close the
  test Chrome when done.
- Never commit `.chrome-cdp-test/` or `test_user.txt`; never exfiltrate page
  content, cookies, or tokens out of the browser.

## Raw CDP fallback (when a tool is missing)

Node ≥ 22 has a native `WebSocket` — no dependencies needed. Save as a temp
script or run with `node --input-type=module -e '…'`:

```js
const tabs = await (await fetch("http://127.0.0.1:9223/json/list")).json();
const tab = tabs.find(t => t.type === "page");
const ws = new WebSocket(tab.webSocketDebuggerUrl);
let id = 0; const pend = new Map();
const send = (method, params = {}) => new Promise((res, rej) => {
  const i = ++id; pend.set(i, { res, rej });
  ws.send(JSON.stringify({ id: i, method, params }));
});
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pend.has(m.id)) {
    const p = pend.get(m.id); pend.delete(m.id);
    m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
  }
};
await new Promise(r => ws.onopen = r);
const r = await send("Runtime.evaluate", {
  expression: "JSON.stringify({url: location.href, title: document.title})",
  returnByValue: true,
});
console.log(r.result.value);
ws.close();
```

Useful for CDP domains no MCP tool wraps (Emulation, DOM snapshotting, cookie
state inspection beyond the typed tools).

## Pitfalls

- Snapshot uids go stale after navigation — re-snapshot, don't reuse.
- Chrome cookies are App-Bound-Encrypted (`v20`) since Chrome 127+: reading
  the SQLite cookie DB yields empty values, and copying a profile does not
  carry usable logins. Sign in inside the profile instead.
- Don't trust cookie counts as proof of login — verify by page state (sidebar
  present, no login wall) or an authenticated action.
- Playwright-style `>> nth=0` selectors are not supported; use
  `xpath=(//a[...])[1]` instead.

Wiring and troubleshooting: `references/setup.md`.
