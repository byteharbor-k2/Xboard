# Tool catalogue — `chrome-devtools-mcp` 1.9.0 (29 tools)

Verified by driving the live server against Chrome on 2026-09-28. Argument
shapes are the common ones; when a call fails on arguments, read the error —
it names the expected fields.

## Pages & navigation

| Tool | Use |
|---|---|
| `list_pages` | Enumerate open tabs. Start here when you did not open the page. |
| `select_page` | Choose which tab later calls act on. |
| `new_page` | Open a tab. Prefer this over hijacking existing tabs. |
| `close_page` | Close a tab. |
| `navigate_page` | Go to a URL. |
| `resize_page` | Change viewport size. |
| `emulate` | Device/network/CPU emulation for responsive & throttling checks. |

## Observation

| Tool | Use |
|---|---|
| `take_snapshot` | **Primary.** Accessibility-tree **text** snapshot with `uid`s for interactive elements. Use those `uid`s to act. |
| `take_screenshot` | The only pixel view. Use deliberately for visual/layout verification. |
| `wait_for` | Wait for text or conditions. Prefer this over fixed sleeps. |
| `evaluate_script` | Run JS in the page. Fastest path for reading structured data (e.g. pull a list out of the DOM as JSON). |

## Interaction

| Tool | Use |
|---|---|
| `click` | Click an element (by snapshot `uid`). |
| `hover` | Hover — needed to reveal menus/overlays and hover-only controls. |
| `fill` | Set one input's value. |
| `fill_form` | Fill several fields in one call. |
| `type_text` | Type text (including into focused elements). |
| `press_key` | Keys and shortcuts (`Enter`, `Tab`, `Control+a`, …). |
| `drag` | Drag between points/elements. |
| `upload_file` | Attach a local file to a file input. |
| `handle_dialog` | Accept/dismiss native alert/confirm/prompt. |

## Debugging (the reason to prefer this stack)

| Tool | Use |
|---|---|
| `list_network_requests` | Every request the page made. Find failing/chattier calls. |
| `get_network_request` | One request in detail: headers, body, status, timing. |
| `list_console_messages` | Console output incl. errors, with source-mapped stack traces. |
| `get_console_message` | One console message in full. |
| `performance_start_trace` | Begin a performance trace. |
| `performance_stop_trace` | End it and collect the trace. |
| `performance_analyze_insight` | Turn a trace into actionable findings. |
| `take_heapsnapshot` | Heap snapshot for memory-leak investigation. |
| `lighthouse_audit` | Full Lighthouse audit. |

## Recommended sequences

**Debug a broken flow**

1. `navigate_page` to the app.
2. `list_console_messages` — surface JS errors first; they often explain everything.
3. Reproduce via `take_snapshot` + `click`/`fill`.
4. `list_network_requests` → `get_network_request` on the suspicious call.
5. `take_screenshot` to confirm what the user would actually see.

**Extract data reliably**

`take_snapshot` for structure, then `evaluate_script` returning `JSON.stringify(...)` for
bulk extraction. One evaluation beats dozens of snapshot round-trips.

**Performance work**

`performance_start_trace` → reload/interact → `performance_stop_trace` →
`performance_analyze_insight`. Add `lighthouse_audit` for an independent scorecard.

**Responsive check**

`resize_page` (or `emulate` for a device profile) → `take_screenshot` →
`take_snapshot` to confirm the layout did not break interaction.

## Notes

- `take_snapshot` uids are **per-snapshot**; they go stale after navigation or DOM churn.
  Always re-snapshot before acting on a stale reference.
- Prefer `uid` over CSS selectors — this SPA ships no `data-testid` attributes.
- `evaluate_script` is the escape hatch for anything the typed tools do not cover; the
  raw-CDP WebSocket recipe in `SKILL.md` covers what even that cannot reach.
