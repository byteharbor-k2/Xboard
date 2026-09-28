# Setup & troubleshooting

## Where this skill lives

`.opencode/skills/cdp-browser/` — opencode auto-scans `**/SKILL.md` under
`.opencode/skills/`. Frontmatter rules: `name` must match the folder name;
`description` is required (skills without one are filtered out and never
surfaced).

opencode loads config and skills **once at startup**. After editing this
skill, `opencode.json`, or any config-time file, **quit and restart opencode**
for the change to take effect.

## The MCP wiring

`opencode.json` at the repo root carries the server:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "chrome-devtools": {
      "type": "local",
      "command": [
        "npx", "-y", "chrome-devtools-mcp@1.9.0",
        "--browser-url", "http://127.0.0.1:9223",
        "--no-usage-statistics"
      ],
      "enabled": true
    }
  }
}
```

- `--browser-url` is **attach mode**: the MCP drives the externally-launched
  test Chrome on 9223 and never launches or kills a browser itself.
- The version is pinned to 1.9.0 to match the verified catalogue in
  `references/tools.md`.
- If Chrome is down when opencode starts, the MCP server exits at boot and
  the tools are missing → launch Chrome (recipe in `SKILL.md`), then restart
  opencode.

## Connection checks

```bash
curl -sS --max-time 5 http://127.0.0.1:9223/json/version   # JSON = attachable
curl -sS --max-time 5 http://127.0.0.1:9223/json/list      # open tabs
lsof -nP -iTCP:9223 -sTCP:LISTEN                            # who holds the port
pgrep -fl chrome-cdp                                        # which instance is which
```

## Troubleshooting

- **Tools missing in every session** → Chrome was down at opencode boot (most
  common cause). Launch the test Chrome, restart opencode.
- **Tools error mid-session / connect refused** → the test Chrome was closed.
  Relaunch it, restart opencode.
- **Another client holds the endpoint** → attach is exclusive: one client owns
  9223. A second opencode session (or a direct poke) blocks later ones. Close
  the other client, then restart opencode.
- **Port 9223 held by the wrong process** → check `lsof` output; the listener
  must be our Chrome (`--user-data-dir=…/.chrome-cdp-test`). If something else
  grabbed it, launch on another port and update `opencode.json` to match.
- **Chrome refuses CDP on the user's default profile** → expected since
  Chrome 136+. Only ever use `--user-data-dir` profiles.
- **Poking the MCP server directly** (bypassing opencode) to list tools:

```bash
( printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' ; sleep 4 ) \
| npx -y chrome-devtools-mcp@1.9.0 --browser-url http://127.0.0.1:9223 --no-usage-statistics
```

## The other Chrome (read this before killing anything)

Port **9222** / profile `.chrome-cdp/` is a **different session's browser**
(video transcription, etc.). Never kill, restart, or drive it. `pkill -f
chrome-cdp-test` matches only the test instance — 9222's profile path does not
contain the string `chrome-cdp-test` — but check `pgrep -fl chrome-cdp` output
before any kill.

## Local test accounts

Root `test_user.txt` (gitignored) holds the dev accounts (admin + user) for
the local site. Read it when signing into the fresh profile; never commit or
inline its contents anywhere.
