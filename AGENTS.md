# Agent Context for Xboard

This file preserves the operational and development context for the SinX Cloud Xboard fork. It is intended for future Codex sessions started from this repository so the user does not need to re-explain the deployment, infrastructure, and project conventions.

Last consolidated: 2026-07-21.

## Project Identity

- Local repo: `/Users/howienew/Workspace/Xboard`
- GitHub repo: `byteharbor-k2/Xboard`
- Upstream lineage: fork of Xboard / cedar2025 ecosystem, with Freedom theme customization and production deployment changes.
- Production principle: local source changes are committed and pushed to GitHub, GitHub Actions builds a Docker image, and the production VPS pulls the image from GHCR. Avoid hand-editing production source or building long-term production images directly on the VPS.
- Container image pattern: `ghcr.io/byteharbor-k2/xboard:prod` for production, with commit-specific tags/digests used when pinning or debugging.

## Communication Preferences

- User prefers Chinese explanations with practical engineering reasoning.
- Explain each infrastructure step briefly: what is being changed, why it matters, and how to verify it.
- Before remote config edits, make a timestamped backup.
- After Nginx edits, always run `nginx -t` before reload.
- Do not commit secrets, private keys, `.env`, Cloudflare tunnel tokens, or acme credentials.
- Be careful with dirty worktrees. Do not revert user changes unless explicitly requested.

## Production Host

- Main production host alias: `Evoxt-MY-Panel`
- Current role: Xboard panel, Nginx reverse proxy, Cloudflare Tunnel connector, xboard-node for MY proxy nodes, EPay, BEpusdt.
- Xboard production path: `/opt/xboard-prod`
- Xboard container: `xboard-prod-xboard-1`
- Xboard image: `ghcr.io/byteharbor-k2/xboard:prod`
- Xboard app internal port: `127.0.0.1:7001`
- Xboard WebSocket port: `8076/tcp`
- Production SQLite database: `/opt/xboard-prod/data/database.sqlite`
- If backing up SQLite, include WAL/SHM files or use a consistent SQLite backup method.

## Xboard Runtime Notes

- `8076/tcp` is the Xboard WebSocket server used by panel and xboard-node communication when `ENABLE_WS_SERVER=true`.
- Keep `8076/tcp` reachable by nodes for now if direct WS is used.
- Long-term hardening goal: proxy/lock down 8076 instead of exposing it broadly.
- Do not expose internal service ports such as `7001`, `7080-7083`, `9080-9081`, `20241`, or `65530`.

## Nginx Layout

Main Xboard Nginx site:

- `/etc/nginx/sites-available/xboard`
- enabled from `/etc/nginx/sites-enabled/`

Important route pattern:

- `app.sinx.it.com`
  - Cloudflare Tunnel route to local Nginx.
  - Local listen: `127.0.0.1:7080`
  - Proxies to Xboard web: `127.0.0.1:7001`
- `dashboard.app.sinx.it.com`
  - Admin/backend hostname.
  - Has public 443 direct access and local access for internal proxying.
  - Admin path includes `/bljozfrlavszfmwnghde/`.
  - Also serves `/assets/`, `/api/`, `/favicon.ico`.
- `/branding/`
  - Static files under `/var/www/branding/`
  - Includes ToS/privacy HTML.
- `/downloads/`
  - Static installer downloads under `/var/www/downloads/`
  - Uses long-ish cache headers and `X-Content-Type-Options nosniff`.
- `/web/`, `/sitemap.xml`, `/robots.txt`
  - Public SEO/static web files under `/var/www/web/`.

Known Nginx backup directory on Evoxt:

- `/root/nginx-config-backups/`
- Known backups include tunnel/public transition snapshots such as:
  - `xboard.tunnel.20260718131214`
  - `xboard.before-public.20260718132318`
  - `epay.before-public.20260718132318`
  - `bepusdt.before-public.20260718132318`

## Cloudflare Tunnel

Tunnel on Evoxt:

- Systemd service: `cloudflared.service`
- Tunnel name: `evoxt-xboard-app`
- Tunnel ID: `ac01e090-f3c1-4b8f-ba43-9ec16bdfc165`
- DNS CNAME target: `ac01e090-f3c1-4b8f-ba43-9ec16bdfc165.cfargotunnel.com`

Published application routes:

- `app.sinx.it.com` -> `http://localhost:7080`
- `pay.sinx.it.com` -> `http://localhost:7082`
- `crypto.sinx.it.com` -> `http://localhost:7083`

Operational notes:

- Prefer stopping/starting `cloudflared.service` for reversible tests. Avoid deleting the tunnel unless intentional.
- If restoring tunnel DNS manually, create CNAME records to the tunnel target and enable orange cloud.
- The user tested gray-cloud direct VPS IP access and found many China regions still timed out, so tunnel mode was restored.

## TLS and ACME

Certificates were reissued on Evoxt using acme.sh and Cloudflare DNS API.

Important paths:

- `*.sinx.it.com`: `/etc/nginx/ssl/sinx.it.com/`
- `*.app.sinx.it.com`: `/etc/nginx/ssl/dashboard.app.sinx.it.com/`
- `*.node.sinx.it.com`: `/etc/xboard-node/certs/node.sinx.it.com/`

Cloudflare DNS API environment:

- `CF_TOKEN`
- Case matters. Do not rename to `CF_Token` or other variants.

Node wildcard certificate source on Evoxt:

- `/etc/xboard-node/certs/node.sinx.it.com/fullchain.pem`
- `/etc/xboard-node/certs/node.sinx.it.com/privkey.pem`

## xboard-node

Evoxt local xboard-node:

- Config: `/etc/xboard-node/config.yml`
- Service: `xboard-node.service`
- Health port: `65530/tcp`
- Health endpoint: `/healthz`
- `65530` is local health/status and should not be publicly exposed.

Proxy inbound ports currently associated with MY nodes:

- `8443-8446/tcp`
- `8443-8446/udp`

Node certificate sync:

- Reference doc outside this repo:
  `/Users/howienew/Workspace/Howie_International_OMC/VPN_机场/Xboard&Node/Xboard_Node/xboard-node-cert-sync.md`
- Standard target path on nodes:
  `/etc/xboard-node/certs/node.sinx.it.com/`
- After syncing cert files, run:
  - `xbctl service restart`
  - `xbctl service status`

Machine mode:

- Xboard server management can bind multiple node IDs to a machine/server.
- In machine mode, xboard-node pulls the machine node list from panel API/WS rather than requiring many independent local node configs.
- Use `xbctl bind remove-node --panel https://dashboard.app.sinx.it.com --node-id <id>` to remove old bindings rather than editing config files directly.

Known node aliases or providers seen in operations:

- `NOSLA-DE`
- `NOSLA-HK`
- `NOSLA-US`
- `YUNYOO-HK`
- `DMIT-US`
- `VMISS-JP`
- `VMISS-KR`
- `ByteVirt-LSD-US-LA-CN2GIA`

Known node troubleshooting notes:

- If a provider swaps/reinstalls a VPS, SSH host keys change and `known_hosts` must be updated.
- Some Ubuntu images use `ssh.service`, not `sshd.service`. Validate config with `sshd -t`, then restart `systemctl restart ssh`.
- A NOSLA-HK VLESS Reality issue was resolved by setting uTLS fingerprint to `chrome` and server name to `www.klook.com`.
- If all nodes on one VPS timeout but SSH and ping work, check node ID bindings, firewall, xboard-node logs, and whether the panel generated client config matches the actual node configuration.
- UDP protocols such as Hysteria2/TUIC can remain broken after TCP recovers because providers often prioritize TCP mitigation during DDoS incidents and may rate-limit or block UDP more aggressively.

## Payment Stack

EPay:

- Domain: `pay.sinx.it.com`
- Cloudflare Tunnel route: `http://localhost:7082`
- Nginx site: `/etc/nginx/sites-available/epay`
- Local Docker app: `127.0.0.1:9080`
- Web container: `epay-web-1`
- Database container: `epay-db-1`
- Image pattern: `ghcr.io/byteharbor-k2/epay:<tag>`
- Production DB/data should be backed up before container or image changes.

BEpusdt:

- Domain: `crypto.sinx.it.com`
- Cloudflare Tunnel route: `http://localhost:7083`
- Nginx site: `/etc/nginx/sites-available/bepusdt`
- Local Docker app: `127.0.0.1:9081`
- Container: `bepusdt`
- Image: `v03413/bepusdt:eeb7ac1`
- Data includes SQLite DB and WAL files under `/opt/bepusdt/data/`.

Payment integration notes:

- EPay -> BEpusdt gateway URL needs a trailing slash `/`.
- Without the trailing slash, EPay can show: `请求失败，请检查服务器是否能正常请求 BEpusdt 网关！`
- Xboard showing `支付方式(type)不存在` usually means Xboard payment plugin/type configuration does not match the expected EPay plugin identifier.

## Static Downloads

Static installer directory:

- `/var/www/downloads/`
- Public URL prefix: `https://app.sinx.it.com/downloads/`

Known uploaded packages:

- `AuroraStore-4.8.3.apk`
- `F-Droid.apk`
- `cmfa-2.11.21-meta-arm64-v8a-release.apk`
- `Hiddify-Android-arm64.apk`
- `Hiddify-Android-universal.apk`
- `Clash.Verge_2.5.1_aarch64.dmg`
- `Clash.Verge_2.5.1_amd64.deb`
- `Clash.Verge_2.5.1_x64-setup.exe`

User intends to use Xboard knowledge base articles to link users to these downloads.

## SEO and Public Content

Current public SEO files:

- `https://app.sinx.it.com/sitemap.xml`
- `https://app.sinx.it.com/robots.txt`
- Server path: `/var/www/web/`

Legal page:

- `https://app.sinx.it.com/branding/terms-of-service_privacy-policy.html`
- Server path: `/var/www/branding/terms-of-service_privacy-policy.html`

SEO strategy discussed:

- Do not rely only on logged-in Xboard knowledge base content for search traffic.
- Public long-tail tutorial pages should have independent URLs and be included in sitemap.
- Useful topics include Clash Verge Rev, Shadowrocket, iOS/Android/macOS/Windows setup, VLESS Reality, Hysteria2, importing subscription links, and VPN vs proxy.

## Sing-box Template Notes

The default Xboard sing-box template was too old for modern sing-box clients.

Observed errors:

- `legacy inbound fields are deprecated in sing-box 1.11.0 and removed in sing-box 1.13.0`
- `start dns/https[local]: detour to an empty direct outbound makes no sense`
- `missing route.default_domain_resolver or domain_resolver in dial fields is deprecated in sing-box 1.12.0`

Implications:

- Do not keep old inbound fields such as `domain_strategy`, `sniff`, `sniff_override_destination`, or `endpoint_independent_nat` directly on inbounds for sing-box 1.13+.
- Move sniff/resolve behavior into `route.rules` actions as required by modern sing-box.
- Do not set `"detour": "direct"` on a local DNS server where direct has no dialer behavior.
- Add `route.default_domain_resolver`, typically pointing to the local DNS server with `prefer_ipv4`.

## Subscription Settings

Xboard subscription event settings:

- New order event: action triggered after a new subscription purchase.
- Renewal event: action triggered after renewal.
- Change order event: action triggered after upgrade/change.

Known action logic:

- `0`: no action.
- `1`: reset user traffic counters.

Practical recommendation:

- New order: reset traffic.
- Renewal: reset traffic.
- Change order: no action, unless the business rule explicitly wants upgrades to reset traffic.

## Security and Firewall

Preferred firewall layering:

1. Provider dashboard firewall first, because traffic is dropped before reaching the VPS.
2. UFW on Ubuntu as local defense-in-depth.

Typical public ports on Evoxt:

- `22/tcp` SSH
- `80/tcp`, `443/tcp` Nginx and ACME/HTTP compatibility
- `8076/tcp` Xboard WebSocket, currently needed by nodes
- `8443-8446/tcp` and `8443-8446/udp` if MY proxy nodes are active

Do not expose:

- `7001`
- `7080-7083`
- `9080-9081`
- `20241`
- `65530`

## Deployment Workflow

Preferred Xboard application deployment:

1. Edit code locally in `/Users/howienew/Workspace/Xboard`.
2. Run relevant local checks.
3. Commit and push to GitHub.
4. GitHub Actions builds Docker image and publishes to GHCR.
5. On Evoxt, pull the image and deploy via Docker Compose in `/opt/xboard-prod`.
6. Verify app, dashboard, API, WebSocket, and node status.

Avoid:

- Editing production container files directly.
- Rebuilding production images manually on the VPS as the normal path.
- Changing Nginx without backup and `nginx -t`.
- Copying sensitive production files into git.

## Backup Priorities

Xboard:

- `/opt/xboard-prod/data/database.sqlite`
- SQLite WAL/SHM files
- `/opt/xboard-prod/.env`
- plugins, theme, logs as needed

EPay:

- MariaDB data or logical dump from `epay-db-1`
- `/opt/epay/config.php`
- `/opt/epay/docker-compose.yml`

BEpusdt:

- `/opt/bepusdt/data/sqlite.db`
- WAL/SHM files
- compose/config files

Infra:

- `/etc/nginx/nginx.conf`
- `/etc/nginx/sites-available/`
- `/etc/nginx/sites-enabled/`
- `/etc/nginx/ssl/`
- `/root/.acme.sh/`
- `/etc/xboard-node/`
- cloudflared service/config/token references, without committing secrets

## Common Verification Commands

Use these patterns when checking production. Run remote commands read-only unless explicitly changing state.

- `docker ps`
- `docker compose ps`
- `nginx -t`
- `systemctl status cloudflared --no-pager`
- `systemctl status xboard-node --no-pager`
- `xbctl service status`
- `ss -tulpen`
- `curl -I https://app.sinx.it.com/`
- `curl -I https://dashboard.app.sinx.it.com/`
- `curl -I https://pay.sinx.it.com/`
- `curl -I https://crypto.sinx.it.com/`

## Operational Cautions

- Evoxt MY has experienced provider-wide DDoS instability. During such events, dashboard may recover before proxy protocols, and UDP may recover later than TCP.
- China-region access may fail even when global ping tests look acceptable.
- Ping reachability does not prove TCP/UDP proxy usability.
- When diagnosing node timeout, compare:
  - DNS A/AAAA records
  - firewall and provider firewall
  - xboard-node logs
  - panel node ID and machine binding
  - generated client config fields
  - TLS cert paths
  - protocol-specific requirements such as Reality server name and fingerprint

