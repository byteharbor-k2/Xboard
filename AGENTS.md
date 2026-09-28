# AGENTS.md — SinX Platform monorepo

Rewrite of Xboard: Java 21 / Spring Boot backend + React frontend, replacing the
legacy Laravel panel. **Read `plan.md` first** — it is the single source of truth
for scope, progress, decisions, and known traps. The user prefers Chinese in
conversation; code, commits, and docs are English.

## Layout

| Path | What it is |
| --- | --- |
| `backend/` | Spring Boot 4.1 / Java 21 modular monolith (`com.sinx.platform.{identity,catalog,order,payment,node,subscription,configuration,...}`). Entry: `SinxBackendApplication`. |
| `frontend/` | React 19 + Vite 8 + TS. User pages in `src/pages/`, admin pages are `Admin*`. No test framework — see `npm run check` below. |
| `Xboard/` | Legacy Laravel fork, kept for behavior parity reference and old-production ops only. **Do not modify it for new-platform work.** Its own `Xboard/AGENTS.md` covers production ops (Nginx, tunnels, backups) and applies only to that. |
| `plan.md` | Progress list, scope decisions (§5.1 lists features deliberately NOT built), workflow rules (§6), verification traps (§7). |
| Root `resources/ routes/ theme/ plugins/ .env/` | Near-empty legacy leftovers. Ignore. |

## Commands

```bash
docker compose -f compose.dev.yml up -d     # Postgres 17 + Redis on localhost (required for dev and tests)
cd backend && ./mvnw spring-boot:run        # API on :8080, actuator on :9091; dev profile has defaults, no .env needed
cd backend && ./mvnw verify                 # full CI gate
cd backend && ./mvnw test -Dtest=OrderFulfilmentIntegrationTest   # single test
cd frontend && npm run dev                  # :5173, proxies /gateway /session /admin-session /control /api /sub to :8080
cd frontend && npm run build                # includes tsc typecheck; this is the CI gate
cd frontend && npm run check                # standalone gate for the 401-recovery state machine (no Jest/Vitest exists)
```

- Backend tests self-provision Postgres 17 + Redis 7.4 via Testcontainers — Docker must be running, but `compose.dev.yml` is not needed.
- DB schema changes = new Flyway migration in `backend/src/main/resources/db/migration/` (next V-number). Never edit applied migrations.
- GraphQL schema: `backend/src/main/resources/graphql/schema.graphqls`; user-facing API is `POST /gateway` only.

## Local browser testing (CDP)

- Skill: `.opencode/skills/cdp-browser/` — drives a real Chrome via the `chrome-devtools` MCP server wired in `opencode.json`. The project test browser is Chrome on `127.0.0.1:9223` with the repo-local `.chrome-cdp-test/` profile (launch recipe inside the skill; profile is gitignored, logins persist).
- The Chrome on port **9222** (profile `.chrome-cdp/`) belongs to other sessions — never kill, restart, or drive it.
- Dev test accounts (admin + user) for signing in live in root `test_user.txt` (gitignored) — read it, never commit it.
- Restart opencode after editing `opencode.json` or the skill — config loads once at startup.

## Workflow hard rules (from plan.md §6 — all were learned the expensive way)

1. A subagent reporting "all green" proves nothing. Run `./mvnw verify` yourself before accepting work; the tree has been left non-compiling twice.
2. Parallel subagents only on **non-overlapping file areas**; overlapping work goes serial.
3. After tests pass, a separate read-only reviewer subagent does an adversarial review. Commits happen only after it passes, in logical units.
4. **Never restart the dev backend on :8080** — the user/main agent may be testing against it. Verify via tests, not by starting services.
5. New tests go in their own test class. Do not add to `InfrastructureIntegrationTest` (1900+ lines).
6. Update `plan.md` in the same commit as the code (§8). Never batch up plan edits.

## Scope discipline

- plan.md §5.1 is a "NOT building" table (gift cards, tickets, multi-admin roles, non-xboard-node agents, extra payment gateways, ...). Check it before implementing anything that "seems missing" — it is likely deliberately excluded. Principle: don't add hardening/features the original Xboard didn't have.
- Only EPay (CNY), only xboard-node, single admin. Zero-amount orders auto-fulfill without a gateway.

## API boundaries that must not blur

- User side: `POST /gateway` (GraphQL) + `/session/*`. Anonymous GraphQL may expose only `systemStatus`/`siteName`.
- Admin: `/api/v2/admin/*`, `/control/*`, separate session/JWT audience from users.
- Nodes: `/api/v2/server/*`, `/api/v1/server/UniProxy`, `/ws`.
- Subscription: `/sub/{token}` — the token stays in the path only, never in logs or error bodies.
- Frontend `src/lib/authorizedFetch.ts` distinguishes token-expiry 401 (has `WWW-Authenticate`, triggers one refresh) from business 401 (ProblemDetail body, must surface untouched). `npm run check` guards this; don't break the distinction.

## Deploy

Push to `dev` → `platform-publish` workflow builds `ghcr.io/byteharbor-k2/xboard-backend:dev` / `xboard-frontend:dev` → test host deploys via `compose.test.yml`. GHCR packages are private; public registry mirrors don't proxy GHCR (don't try).

## Gotchas already paid for (details in plan.md §7)

- New node created in admin → xboard-node won't start the kernel until `xbctl restart` on the node.
- Hysteria2/TUIC are QUIC: check with `ss -ulnp`, not `ss -tlnp`.
- Classic Clash (`?flag=clash`) intentionally omits SS2022/vless nodes.
- Node `cert_config` must point at a valid cert before enabling TLS protocols.
- Never commit secrets; `.env`, `test_user.txt`, `*.env.bak` are gitignored.
