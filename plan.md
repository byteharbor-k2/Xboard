# SinX Platform 开发计划

> 分支 `dev` · 更新 2026-10-07

## 1. 目标

用 Java 21 / Spring Boot / React / PostgreSQL / Redis 重写 Xboard 的核心业务，
不继承 Laravel 代码、旧数据库、旧用户页面与用户 API。

- 用户侧建立全新的网站、API 与静态资源体系，消除 Xboard 默认指纹。
- 管理端沿用原版 API 路径与操作语义，但数据模型与业务逻辑全部新写。
- 节点控制面兼容 `xboard-node`；用户代理流量直连节点 VPS，不经过面板。

## 2. 架构

```text
用户域名 ─→ 用户 React ─┐
                        ├─→ Spring Boot 模块化单体 ─→ PostgreSQL / Redis / 后台任务
管理员域名 ─→ 管理 React ─┘
订阅客户端 ─→ /sub/{token} ─→ 配置输出
xboard-node ─→ 节点 HTTP API + WebSocket
代理客户端 ─→ 节点 VPS 代理端口 ─→ Internet（不经过网站和面板）
```

四个边界：**用户侧**（公网，全新协议与资源特征）、**管理侧**（仅管理员）、
**订阅侧**（不透明凭据，返回客户端配置）、**节点侧**（控制数据走面板，代理数据直连）。

接口边界：

| 场景 | 入口 |
| --- | --- |
| 用户业务查询/修改 | `POST /gateway` GraphQL（匿名仅 `systemStatus`、`siteName`） |
| 登录、刷新、退出、安全操作 | `/session/*` |
| 支付回调 | 独立可验证 Webhook |
| 订阅 | `/sub/{token}`（令牌只在路径，不进日志与错误 detail） |
| 管理端 | `/api/v2/admin/*`、`/control/*` |
| 节点兼容层 | `/api/v2/server`、`/api/v1/server/UniProxy`、`/ws` |

用户侧不复用管理员或节点接口；框架版本、默认错误页与非必要响应头在生产隐藏。

## 3. 已完成

**用户侧**

- 注册（邮箱验证码 + Turnstile + 单 IP 限流，写库前完成验证）、登录、刷新、退出
- 密码找回与重置、资料修改、密码修改、设备会话
- Account-setting backend completion (2026-10-06): trimmed nonblank nicknames; authenticated
  email change with current-password verification and an account/target-bound email code.
  The email-change proof is independent of registration; rollback releases its claim.
  Success verifies the new address, invalidates old password-reset links and revokes other
  USER refresh sessions while retaining the current device and ADMIN sessions. Full Viewer
  responses preserve reminder and balance fields. Existing password-change semantics remain.
- Balance ledger backend: all order debits, cancellation refunds, surplus credits and
  commission payouts record atomic cash movements. V35 initializes cutover opening balances
  without changing cash or fabricating prior transactions. Per-account lock ordering and
  database ledger sequence preserve actual posting order; empty accounts with only opening
  anchors remain deletable. Owner-only GraphQL summaries/pages expose current-order access
  separately from source transaction references. Main-agent full backend gate: 659 tests;
  independent adversarial review passed.
- USER / ADMIN 独立会话：Token audience、Refresh Cookie、有效期、轮换与重放检测
- 邀请码生成 / 查询 / 注册填写 / 邀请人关系（策略由管理员配置）
- 套餐目录、价格周期、容量与可售状态；订阅权益、流量、有效期、重置策略
- 下单：结算页、付款周期、优惠券校验、折抵（优惠券 → 升级折抵 → 余额）、创建与取消
- 支付：EPay 全链路（后台配置、手续费、发起支付、回调自动开通对账）
- **零应付订单下单即开通**（优惠券、折抵或余额覆盖应付金额时不走网关）
- 订阅凭据与输出：`/sub/{token}` 公开入口、七种格式、管理员可编辑模板（保存前校验）、
  自助轮换
- 账户页客户端选择面板：11 种具名客户端，逐个深链导入 / 复制专属链接 / 二维码
- 订单记录页与详情页 `/account/orders/{tradeNo}`
- User layout cleanup (2026-10-10): promoted subscription import to a dedicated
  quick-start card immediately below the dashboard greeting, before notices and
  usage details, retaining the existing client dialog and credential handling.
  Fixed long device labels overflowing mobile session cards; styled knowledge
  categories and article links with separate wrapping title/date rows. Mobile
  navigation now occupies its own horizontally scrollable row, with the account
  dropdown anchored inside the viewport rather than overflowing in English.
  CDP checked authenticated user routes at desktop and 390px, and English routes
  at 320px; inspected mobile page screenshots and clicked import/document flows.
  Frontend build, auth/quote checks, diff whitespace check and independent
  read-only review passed. Public dev publication authorized by the owner;
  release uses an immutable frontend image on the HK dev host.
- Checkout UI cleanup (2026-10-10): removed the customer-facing STANDARD /
  FULL_PAYMENT radio card and the deduction-mode field in order details. Normal
  purchases retain automatic STANDARD deductions; the existing exceptional
  cancel-and-requote path remains compatible without exposing internal mode names.
  Keep actual discount, balance, unused-plan-value, estimated credit and payable
  amounts visible; replace settlement/reservation jargon with concise localized
  messages, including the actual service-pause consequence and ¥10 minimum.
  Added narrow-screen card sizing and wrapping rules that keep amounts intact.
  Frontend build and auth/quote state-machine checks passed; independent read-only
  review passed. Real CDP preview against the dev API verified monthly/reset
  selection, completed-order details, and desktop/mobile Chinese/English layouts.
  Preview: `http://127.0.0.1:5180`; included in the owner-authorized public dev
  frontend release. No application backend or financial calculation changes.
- 页面：主页（原版 Freedom HTML）、注册、登录、密码找回、账户概览、资料、
  登录设备、套餐、结算、订单
- 订单开通邮件：双语模板（管理员可编辑），未配置即静默跳过，绝不影响开通
- 流量明细页接真数据（`viewerTrafficDaily`，按日账本口径）
- 续费与流量提醒邮件：24 小时内到期、用量 ≥80% 双提醒（用户级开关 + 管理员总闸
  `remind_mail_enable`，每日 11:30 Asia/Shanghai 定时，单封失败不断批次）
- 公告：真实数据轮播 + 可打开的全文详情；后台保存 Markdown 源文，用户端渲染 Markdown。
- 知识库：分类 + 关键词搜索 + 宽版文章详情；后台 Markdown 编辑/预览，用户端渲染 GFM 表格等格式。
- 站内人工客服：用户文字消息持久化、后台独立在线客服收件箱与回复、面板打开时轮询；
  不提供邮箱客服或外部跳转，不公开管理员邮箱，不接 AI。2026-10-01 公网用户发消息→
  管理员回复→用户收到的真实浏览器流程通过，并检查桌面与手机截图；全量 577 测试通过。
- 新用户注册试用：后台选订阅套餐，默认成功注册起 3 小时，继承其流量/限速/权限组；
  不产生购买订单，试用无折抵金额。公网注册与邮箱验证流程验收通过。
- Trial reset correction (2026-10-05): trials have no monthly reset boundary; automatic
  reset/backfill excludes them, including direct service entrypoints. Manual reset still
  clears usage and records it without adding a monthly boundary. V32 clears legacy trial
  boundaries; paid purchase starts its own cycle. Dashboard hides the next-reset fact for
  trials. Full backend verification: 611 tests passed; independent review and frontend
  build/check passed. Frontend acceptance remains user-owned.
- 新用户专属流量包：后台选品、沿用一次性价格；未完成正式购买的账号可购买一次，
  取消可重试，完成后目录隐藏且直接报价/下单被拒绝。试用转正式权益正确清除试用标记与期限。
- User-core APIs (2026-10-05): invitation summary/create and actual invitation counts;
  paired subscription-token/proxy-UUID rotation through shared user/admin service;
  entitlement-filtered viewer node metadata with report-derived status. Full backend
  verification passed: 604 tests, zero failures/errors; independent review passed.
  Deployed code revision `968e211` to dev; V31 migration succeeded, no null proxy UUIDs,
  and backend/frontend containers are healthy. Existing credentials were preserved by migration.
- Commission backend (2026-10-06): order-time eligibility/base/pool snapshots, SYSTEM /
  PERIOD / ONETIME inviter overrides, default 10% first-purchase commission, 72-hour
  completion-update maturity, automatic/manual confirmation, optional three-level
  distribution, atomic ordinary-balance credits and recipient ledger. V33 preserves
  historical orders without retrospective commission. User GraphQL summary/logs,
  admin policy/status/detail/filter APIs and real pending-order statistics are wired.
  Main-agent full backend verification: 619 tests passed; independent adversarial
  review passed after fixing payout-chain races and effective-policy display.
- Commission frontend implementation: invitation page reads live policy, pending/queued/
  earned amounts, current site balance and paginated ledger; admin settings, user overrides
  and order confirmation/detail/filter controls are connected. Creation action is now
  prominent. Main-agent frontend build/check/audit passed; visual acceptance is user-owned.
  Dev deployment `8c64e4c` succeeded after a database backup; V33 applied successfully and
  all containers are healthy. Public QA GraphQL verified effective default 10%, first-only,
  automatic confirmation, site-balance destination and paginated logs. Historical orders
  have no commission snapshots and were not retroactively credited. Full commercial-loop
  proof is integration-tested; the user checks the newly deployed frontend flow.

**管理侧**

- 独立登录入口、会话、TOTP MFA（含首次扫码配置与恢复码）
- 系统设置九个分区的 UI 骨架与自动保存、邮件测试交互
- 套餐、订单、支付配置、节点、机器、访问组、路由管理页
- **用户管理**：列表 / 搜索 / 分页、详情、封禁解封、改邮箱密码流量额度有效期限速备注、
  重置订阅链接、重置流量、删除
- **用户管理补全**：导出 CSV（全量、防注入）、发信（未配置 503 / 发送失败 500 分流）、
  指定邀请人（含环检测）、分配订单（订单与订阅一并迁移到目标账号）
- **封号真正断开**：封禁即撤销全部设备会话并按权益组实时推送节点用户表（解封同推）；
  已签发 access JWT 残留 ≤10 分钟（stateless，原版同款）；封禁入口只有 `/user/ban`，
  `/user/update` 不再接受 `banned`
- 优惠券管理 CRUD：`/api/v2/admin/coupon/*` + 管理页（折扣类型、适用范围、有效期、每人限用）；
  并发重码落库冲突返回 500 而非 409（单管理员，可接受）
- 安全开关接线：`stop_register`、`register_limit_*`（复用 Redis 计数）、`password_limit_*`
  （锁定窗口默认改回原版 60 分钟）、`email_gmail_limit_enable`（只对 gmail 系域名拒 `+`、不拒 `.`）；
  `captcha_type` 只存 turnstile，UI 移除 reCAPTCHA 选项（消除静默失效陷阱）
- 仪表盘统计：收入（已支付订单）、用户（总数/活跃）、流量（日账本 30 天）、节点排名
  （`/api/v2/admin/stat/*`）
- 邮件投递模式 `email_delivery`（log/smtp）管理台可切：保存值优先、未保存回落
  `sinx.mail.delivery` 环境默认；`tls`＝STARTTLS(587)、`ssl`＝隐式 TLS(465)，
  SMTP 设置完整时测试邮件按钮即真发
- 公告管理、知识库管理、流量重置记录页（原版管理面路径与字段语义对齐）
- 新用户福利独立入口 `/admin/system/settings/new-user`：试用套餐/时长、优惠流量包；
  桌面与手机截图、后台保存验证通过，临时验收选品已恢复。全量 588 测试通过。

**节点与订阅**

- 原版节点 HTTP 协议 `/api/v2/server/config|user|report`、旧版 UniProxy 兼容、
  xboard-node 机器模式、WebSocket 实时通道（配置 / 用户 / 设备 / 访问组同步）
- 通讯设置热生效（WS 开关断连、令牌轮换断连、间隔下限）
- 七种订阅格式，口径逐条对齐原版渲染器
- ByteVirt-SG 测试环境：面板 Docker 部署 + xboard-node 机器模式**全链路验证通过**
  （后台建节点 → WebSocket 实时下发 → 内核启动 → 订阅下发 → 外部机器真实出网 →
  流量入账精确对账）
- **五种协议同时在 SG 上跑通并逐一实测**：shadowsocks 2022、VLESS-Reality（XTLS-Vision）、
  Hysteria2、TUIC v5、AnyTLS。每个协议都用 sing-box 从**外部机器**真实建连出网
  （出口 IP 与地理位置均为 SG），且流量逐字节精确计入发起用户、无串号。
- `NodeProtocolService.users()` SQL 化：`whereIn` 权益组过滤，去掉全表载入
  （payload 与 etag 字节不变）
- 流量日账本 `traffic_daily`：上报即入账（user×node×日，`billed_bytes` 计费口径，
  Asia/Shanghai 切日），客诉核验与统计的地基
- 权益变更实时推送：开通/续费/升级与后台改订阅 → `sync.users` 推相关节点
  （`pushUserDelta` 仍无人调用，可后续清理；流量重置不改节点可见字段，不推）

## 4. 待办

### P2

- [ ] User-core frontend acceptance is user-owned (2026-10-05 instruction); implementation is deployed, build/check/audit and independent review passed. Invitation cache and node panel are scoped to viewer identity. Agent browser acceptance stopped at user request; no QA credential rotation was performed on dev.
- [x] Invitation flow user acceptance (2026-10-06): the user confirmed the creation button works but is too inconspicuous, and completed invitation-link registration plus a paid order. Read-only dev inspection confirmed both accounts have USER roles, the inviter relation and one consumed code, one invited account, and a completed CNY 50 monthly first purchase with zero trial surplus. Paid entitlement is no longer a trial and starts at fulfilment, with matching next-month expiry/reset. Current paid state overwrites the prior trial; historical trial values were not independently snapshotted. Independent read-only business-logic review found no blocker in this flow.
- [ ] User acceptance of the more prominent invitation creation action (implementation delivered with the commission frontend).
- [ ] Turnstile frontend acceptance is user-owned. Configuration/retry refinement is deployed and backend verified: keys remain editable while disabled; saved secret is write-only and blank saves preserve it; unsupported legacy provider values no longer bypass verification.
- [x] Commission backend commercial loop, reprioritized by the user on 2026-10-06; ledger credits are spendable through existing order balance deductions. Withdrawal remains excluded.
- [ ] User-owned commission frontend acceptance; code `8c64e4c` is deployed, V33 and public API smoke checks passed.
- [x] Subscription throttling (2026-10-06): Redis-atomic rolling-second window allows at most two requests per user across formats and rotated tokens; the third returns empty 429 with `Retry-After: 1`, without exposing credentials. Real Redis concurrency and endpoint tests pass; main full backend gate: 644 tests, independent review passed.
- [x] Subscription commercial/reset settings: quote and order placement honor `plan_change_enable` and `surplus_enable`; defaults remain enabled. Global reset setting uses original admin key `globalreset_traffic_method` (the requested reset_traffic_method), default 1. Nullable plan policy follows global; explicit overrides take precedence. V34 migrates existing default monthly subscription plans to inheritance. All five modes, setting changes, new-grant races, trial/package exclusions and manual resets are tested; 644-test full backend gate and independent review passed.
- [x] Catalogue category tabs implementation: All / Subscriptions / Traffic packages filter by actual planType, with API-derived counts, empty states and accessible keyboard navigation. Anonymous/user catalogue caches are separately scoped; new-user exclusive eligibility is unchanged. Main-agent frontend build/check/production audit passed, independent read-only review passed.
- [ ] User-owned visual acceptance of catalogue/settings pages; current batch code `f77cd7d` is deployed. Database backup completed, V34 succeeded, containers are healthy, and GitHub backend/frontend CI plus publish passed. Public catalogue returned effective policies; a QA subscription burst produced 429 with `Retry-After: 1`. Request-window timing/concurrency is proved by real Redis tests; main full backend gate: 644 passing tests.
- [x] Account editing and balance-ledger backend: nickname/password retained and verified; new email change and real balance API are implemented, tested and independently reviewed.
- [x] Dedicated frontend implementation: account profile includes verified email changes and complete Viewer updates; `/account/balance` shows live cash totals and paginated posting history; `/account/commissions` shows existing payout logs and live commission policy/totals. Invitation-page commission history remains. Transaction references link only to currently owned orders; commission-source references are read-only. Exact integer-cent formatting and viewer-scoped caches are used. Main frontend build/check/production audit and independent review passed.
- [ ] User-owned acceptance of account editing and dedicated finance pages; code `504638a` deployed on 2026-10-07 after database backup. V35 succeeded, all containers are healthy, and backend/frontend CI plus image publish passed. Opening records preserve existing cash (ten migrated accounts; zero balance/ledger mismatches). Public QA GraphQL balance/commission queries passed; wrong-password email-change request returned business 401 without a Bearer challenge. Main-agent full backend gate: 659 tests, frontend build/check/production audit and independent review passed.
- [ ] Remaining admin field wiring: re-audit supported settings as needed; the old 74/33 count is obsolete after onboarding and commission work.

### Current custom-business batch (2026-10-07, verified implementation)

Scope: one owner ADMIN; friends and ordinary customers remain USER accounts. Friends use
offline transfers and manual order completion, not user-specific discounts or new RBAC tiers.
Build the site's own experience rather than a general theme/plugin/customization platform.

- [x] Priority 1: admin target-balance adjustment with signed real-ledger entry and optional user-visible note; do not create recharge/withdrawal flows.
- [x] Holder-only renewal/reset entry for hidden, stopped-sale or full-capacity ordinary plans; preserve anonymous/unrelated-user filtering and one-time welcome-offer restrictions.
- [x] Minimum nonzero online payment CNY 10 after deductions. Zero-pay orders still auto-fulfil; manual offline admin completion bypasses the gateway. Full-payment mode does not spend existing balance and credits displaced remaining value only after successful fulfilment.
- [x] Traffic-package renewal: apply unused-traffic proportional remaining value, then replace/reset quota on fulfilment; exhausted packages receive the purchased quota without residual value. Plan changes apply remaining value and credit excess to balance.
- [x] Periodic subscription renewal extends months without clearing current-cycle usage, including exhausted subscriptions. Dashboard reset creates a paid `RESET_TRAFFIC` order using the current plan's admin-configured reset price. Payment/settlement must succeed before clearing usage; expiry and normal automatic-reset boundary are preserved. Allow at most one successful reset per current monthly traffic cycle; pending, cancelled and failed orders do not consume it. Trial/package entitlements are not eligible. No free reset is implemented.
- [x] Configurable default expiry/traffic reminder preferences for new accounts; existing user preferences stay unchanged.
- [x] Final cleanup implemented: unsupported Logo/Telegram/dedicated-client settings, navigation and webhook handler controls removed; unknown old setting paths fall back to supported sections. User speed editing is removed and the displayed speed is the actual plan entitlement limit.
- Not building, user decision 2026-10-07: per-user purchase discount, user speed overrides, extra admin plan-selection UI, bulk account creation/management, configurable purchase-event resets and knowledge-base personal/subscriber-only template features. Friends use offline transfers plus existing manual order completion; ordinary buyers use coupons.
- Execution order: admin balance/default reminders → holder renewal and pricing/payment modes → package/periodic renewal and paid cycle reset → frontend integration → unsupported-setting cleanup → full tests/read-only review → dev delivery. Frontend acceptance remains user-owned.
- Backend verification: main agent ran the full gate (678 tests, zero failures/errors), then independent review passed. V36 adds admin balance notes/adjustment records; V37 adds deduction mode, actual periodic coverage, paid reset-cycle claims and explicit settlement outcomes. Real PostgreSQL/signed-payment tests cover reset configuration changes, late callbacks and rollover, cash-return idempotence, delayed/gapped renewals, full-payment deferred credits and source consumption. A stale reset is rejected before gateway initiation; if an initiated payment can no longer reset its saved cycle, captured funds are credited once to site balance with `BALANCE_RETURNED`, never silently applied to the next cycle.
- Frontend implementation verified: admin balance target/note forms, new reminder defaults, holder-only renewal and paid-reset checkout links, minimum-payment warning, STANDARD/FULL_PAYMENT selection and explicit balance-return outcomes. Controlled-response checks prove old quote success/failure cannot overwrite current inputs or enable a mismatched order submission. Main frontend build/check/production audit and independent review passed.
- [ ] User-owned visual acceptance of the custom-business batch; code `8ebfff3` is deployed after database backup. V36/V37 succeeded, backend/frontend containers are healthy, and all existing cash balances reconcile with ledger totals. Latest backend CI and image publish passed; frontend CI/build/check/production audit and independent review passed. Public QA queries verified STANDARD vs FULL_PAYMENT, zero-pay allowance, exactly-CNY-10 payment eligibility, deferred credit and paid-reset eligibility; no real user balances, orders or credentials were changed for acceptance.
- CI portability fix: integration-test clocks are normalized to PostgreSQL microsecond precision before fixture writes. Linux nanosecond timestamps previously failed exact date assertions; exact expiry/cycle and proration assertions are retained or strengthened. Main full backend verification remains 678 passing tests, and the test-only change passed independent review.

### Payment/valuation audit findings (2026-10-08; local repairs verified 2026-10-09)

Read-only source audit against deployed `8ebfff3`; the earlier 678-test result does not
cover these counterexamples. No public payment requests or account mutations were used
to reproduce them, and no application code was changed during this audit.

Repair status (2026-10-09): the audit findings below and subsequent adversarial-review
blockers are repaired in the local worktree. Main-agent full `./mvnw verify` passed:
712 tests, zero failures/errors; the independent read-only reviewer passed after the
last populated V39 activation-isolation regression. Code `bc297e4` was deployed to dev
on 2026-10-09 after database backup `pre-v38-v39-bc297e4-20261009-134038.dump`.
Backend CI, frontend CI and image publication passed for that exact SHA; deployed
backend/frontend image IDs match its immutable SHA tags. V38/V39 applied successfully,
all test containers are healthy, cash/ledger mismatches and missing payer snapshots are
zero, and public frontend/anonymous GraphQL smoke checks passed. Production xboard-node
remained active with its existing PID throughout the test-panel container deployment.
V38 introduces immutable payment attempts and merchant/platform-scoped receipts,
including historical receipt identities. V39 preserves original balance payers,
traffic-cycle identities, consumption across manual/paid resets and surplus reservations.
STANDARD freezes time valuation at order confirmation while accounting for actual
in-flight consumption; FULL_PAYMENT values actual remaining rights at fulfilment.
Replacing rights retires all associated funded sources, including zero-value sources.
Traffic-report locking uses a single-table PostgreSQL `FOR UPDATE` query: joining the
mutable plan previously caused a queued report to disappear after concurrent replacement.
Exact ledger/counter assertions now cover both report/settlement lock orders.
Legacy reset fulfilment requires matching saved cycle/activation evidence; migration
cycle/history/consumption attribution is bounded by the current activation's `starts_at`.
Historical overwritten merchant settings, missing reset kinds and pre-ledger payer facts
cannot be reconstructed perfectly; populated migration tests cover the recoverable cases.
Frontend build/check/production dependency audit passed; visual acceptance is user-owned.
ByteVirt-SG now serves production machine 10 and must not be used for development node QA.

Dev host migration (2026-10-09): current deployment is SSH `YUNYOO-HK`,
`/opt/sinx-test/compose.test.yml`, still `https://dev.sinx.it.com`, code `bc297e4`.
The owner moved HK production proxy 443 listeners to 8445; the explicitly requested
node restart succeeded (machine 8, all five nodes). Dev uses Nginx 80/443 and
loopback-only backend/frontend 8080/8081; database/cache ports remain internal.
SG writes were stopped before the final PostgreSQL dump and Redis-volume transfer.
HK restored 10 users, 16 orders, 10 balance records and 7 receipts; cash/ledger
mismatches are zero and V38/V39 remain successful. All four containers, HTTPS,
anonymous GraphQL and both hosts' production node health checks passed.
HK certificate is valid until 2027-01-07, renewed by existing acme.sh cron with
webroot validation and `nginx -t` before reload. SG dev containers/network were
removed without deleting volumes; final backup is
`/opt/sinx-test/backups/move-to-hk-20261009-165010.dump` (SG), with a protected copy
at `/opt/sinx-test/backups/hk-migration-latest.dump` (HK). SG Nginx temporarily
forwards cached-DNS requests to HK over verified TLS. Production node processes
were not restarted during application/data migration after the authorized HK
port-change restart. Future dev deployment targets HK only.

- [x] P0: EPay requires successful trade status and a gateway transaction number; signed checkout requests cannot settle service or balance.
- [ ] P1: periodic surplus ignores current-cycle traffic consumption while cross-plan fulfilment replenishes quota, allowing an exhausted month to redeem time-based value and obtain fresh quota/cash credit. Preserve future prepaid periods separately when defining the corrected policy.
- [ ] P1: pending orders freeze old remaining value but leave the old entitlement consumable; later fulfilment can redeem value already consumed during the payment wait. Pricing confirmation and resource/value reservation need a consistent policy.
- [ ] P1: repeated checkout overwrites method/fee metadata; callbacks use current enabled/configured merchant settings. Existing cashier links may be paid but rejected after another checkout or configuration change.
- [ ] P1: a first real gateway receipt arriving after cancellation or manual completion is acknowledged without recording or applying the extra receipt. Existing same-order fulfilment idempotence is not full payment-receipt reconciliation.
- [ ] P2: clipping a funded period to an administratively shortened expiry also shrinks its valuation denominator, overvaluing the remaining portion.
- [ ] P2: order assignment moves an entitlement but only one funding order and no paid-reset claims, breaking multi-order remaining-value and cycle ownership continuity.
- [ ] UI: selected payment-method totals can retain an earlier stored fee; settled-order paymentOptions and admin detail invalidation have state inconsistencies.
- Confirmed policy: purchases after expiry are NEW_PURCHASE with new usage/anchors and no old surplus; manual resets/policy changes preserve the actual traffic-cycle identity.

### P3

- Telegram configuration/webhook is not being built; unsupported UI removed in the custom-business batch.
- Deferred by user (2026-10-06): client-version compatibility filtering; most users use current Clash Verge kernels.
- Deferred by user (2026-10-06): multi-domain subscription allocation, reserved for future domain-blocking mitigation; currently one domain.
- Deferred by user (2026-10-06): dedicated client/download configuration; distribute open-source proxy-client links through the knowledge base instead. Logo branding remains unscheduled.
- Deferred by user (2026-10-06): station notification centre.
- [x] Real client import/connectivity baseline: external sing-box and xboard-node acceptance recorded in §7; current VLESS Reality configuration remains a separate user follow-up.
- Knowledge base, traffic details, renewal reminders and invitation registration flow are complete; current category-tab work and subsequent account/finance pages are tracked above.

### 上线验收

- [ ] 用户 API 指纹验收
- [ ] HTTP/3、HTTP/2、HTTP/1.1 入口验收
- [ ] 公网测试环境端到端验收

## 5. 决策记录

### 5.1 不做

> 2026-09-19 追加原则：**原版没有的安全加固一律不做**。初版只复刻原版已有的
> 商业能力与防护，不引入"更严但原版没有"的设计。节点失陷、令牌泄露这类
> 威胁模型按「自控 VPS + SSH 密钥登录」评估，初版不设防。

| 项 | 理由 |
| --- | --- |
| 节点上报的合法性校验（单次上限、累计对账、幂等去重） | 原版 `TrafficFetchJob` 直接 `incrementEach($v[0]*rate)`，无任何校验。本项目已比原版更严（按权益状态与权限组过滤），不再加码 |
| 匿名读套餐目录 / 单个套餐报价 | **原版就是匿名的**（`/api/v1/guest/plan/fetch`）。且本项目 `availableOffers()` 已过滤 `published+sellable`，`availableOffer()` 对未上架返回空 |
| 工单流程、AI 客服、邮箱客服 | 2026-10-01 用户明确只要网页内人工聊天；仅做站内文字消息及单管理员回复，不做工单状态、分配、SLA 或 AI |
| 额外试用防薅羊毛识别（IP 领取间隔、设备指纹、浏览器领取标记） | 2026-10-03 用户决定在用户数达到一万前不做；中国大陆 CGNAT 不适合用共享公网 IP 判定同一人。现有可选注册安全设置保留，不自动改动已保存开关 |
| 礼品卡、主题配置、插件管理 | 不做的模块，表与服务从未实现 |
| 管理员与角色 | 只有一名管理员，不做分级与权限模型 |
| 通用管理员请求审计日志 | 低价值，已删除（原版有） |
| 管理员「登录设备」 | 单人使用，无多设备管理需求（会话本身保留） |
| 其余支付网关、退款 | 只做 EPay；退款在客服系统私聊解决 |
| 实名、内容审计 | 无此合规要求 |
| 异常用量监控 | 只保留订阅入口固定限速 |
| 设备数上限 `device_limit` 与计数模式 `device_limit_mode` | 无法准确判断同时在线设备数，多设备访问也不构成漏洞。**在线设备数据仍采集与展示**，只是不再有上限 |
| `secure_path` / `safe_mode_enable` | 由 Nginx 直接配置 |
| 订阅头注释 `show_info_*` / `show_protocol_*` | 订阅配置里不输出站点信息与协议名注释 |
| `subscribe_path` / `currency` / `currency_symbol` / `force_https` / `app_description` | 架构已变更，原语义不存在；价格自带 ISO 币种 |
| 「Clash」「通用订阅」两个客户端入口 | 前者只承载旧内核窄协议集，后者按 UA 猜格式对新手有误导 |
| 兼容非 xboard-node 代理（V2bX / XrayR 等） | **只用 xboard-node**（2026-09-19 定）。不补 `/api/v2/server/push\|alive\|alivelist\|status` 与 Tidalab 端点 |
| Commission withdrawal | Explicitly excluded again by user on 2026-10-06; commissions credit ordinary site balance |
| Loon / QuantumultX / Shadowrocket 专属订阅格式 | 不补；Shadowrocket 走通用 v2ray 列表 |
| 管理台设置保存的字段级校验（数值范围、枚举白名单）与发信 503/500 错误分类 | 单管理员一次配置，写侧校验徒增复杂度；读侧对无效存值回落默认值，发信失败原样透传、管理台直接显示邮件服务器的真实报错（2026-09-29 定）。公开用户面的防护（限流、优惠券校验、CSV 防注入、开通邮件 log-and-drop）不受此原则影响 |

### 5.2 业务口径

- **支付**：只接 EPay 协议；币种只用 CNY；不做系统退款
- **零元订单自动开通**：下单时应付为 0（优惠券 / 升级折抵 / 余额 任一覆盖）即直接开通并
  标记 `auto_settled`，不走网关。理由是网关列表对 0 元订单本就为空，留在待支付会让客户
  **既付不了也开不了**（2026-09-20 实测踩到并修复）
- **应付总额必须含手续费**：选中支付方式后页面显示的「应付总额」要等于网关实际收取的金额。
  此前只显示订单金额、手续费另挂小字，实测客户看到 ¥0.72 而网关收 ¥1.74
- **优惠券**：照搬原版逻辑（折扣类型、适用范围、有效期、每人限用）
- **新用户福利**：注册试用按原版配置套餐，默认注册成功起 3 小时；试用不创建购买订单且没有折抵金额。
  新用户特惠流量包由管理员选品与定价，仅未完成正式套餐购买的账号可购买；试用不消耗资格，
  取消/过期待支付订单可重试，完成正式购买后不再符合新用户资格。购买流量包替换当前试用，不重复叠加权益。
- **返佣**：照搬原版（全局默认比例 + 用户级覆盖、仅首次支付、完成 3 日后自动确认、
  三级分销可关）；2026-10-06 用户要求实现商业闭环，佣金直接进入普通站点余额供下次购买抵扣，提现不做。
- Commission parity details: eligibility is snapshotted at order placement; valid history
  excludes only pending/cancelled orders, so trials do not consume eligibility but completed
  zero-cost purchases do. Base excludes coupon/surplus deductions and payment fees, but
  includes the portion paid from balance. Rate null/zero follows the global rate. Automatic
  confirmation uses completed order `updated_at` plus 72 hours; manual confirmation queues
  immediate settlement. Existing orders are not backfilled. Integer-cent amounts truncate
  per calculation; zero-share levels still advance to the actual ancestor, and paid orders
  cannot be requeued. These last rules normalize legacy rounding and prevent duplicate or
  misdirected balance credits. No automatic invitee first-purchase discount was found in
  original core code, so none is added.
- **知识库**：自建文档系统，参考原版
- **内容交付**：公告和知识库保留 Markdown 源文，编辑预览与用户展示使用同一渲染器；桌面与手机截图验收，不能只以构建成功/HTTP 200 标记页面可用。
- **封号语义**：禁止登录 + 断开订阅。**断开 = 从所有节点用户表移除 + 踢掉已连接代理**，
  并撤销该账号全部设备会话与已签发 token（2026-09-19 定，原版行为）
- **订阅限速**：每用户每秒最多 2 次请求
- Custom payment message (2026-10-07): “受支付系统限制，最小付款金额不得小于10CNY，此笔支付无法使用剩余价值或余额折抵，请选择折抵后大于10CNY的套餐或不使用折抵全额支付，折抵金额会进入您的余额，下次可以使用”. Apply to nonzero online payments below 1000 minor units; allow exactly CNY 10 and zero-pay automatic fulfilment. No monetary credits or source-order consumption occur merely from quoting, cancelling or failing payment.
- **管理端**：仪表盘沿用现有布局接真实数据即可
- **交付**：中英双语；邮件模板复刻原版

### 5.3 流量重置档位

2026-10-06 implementation: the global default remains monthly from activation (1),
preserving current behavior. Periodic plans may follow the global method (`resetPolicy`
null) or override it explicitly; traffic packages and registration trials never auto-reset.
The five methods below are implemented, using Asia/Shanghai calendar boundaries and
activation-based monthly/yearly anniversaries. Settings/plan changes reconcile boundaries
without clearing traffic; unchanged policies preserve their anchors. A post-commit check
also covers new entitlements racing a policy change. Scheduler runs hourly with one reset
per overdue entitlement, not one reset per missed cycle. V34 preserves current monthly
behavior while making existing default monthly plans inherit the global setting.

| Method | Meaning | Notes |
| --- | --- | --- |
| 0 | First day of each month | Asia/Shanghai calendar month |
| 1 | Monthly from activation | Default; existing short-month clamping behavior retained |
| 2 | Never | No automatic boundary |
| 3 | First day of each year | Asia/Shanghai calendar year |
| 4 | Yearly from activation | Leap-year boundary handling |

## 6. 开发流程

主 agent 负责**巡检、规划、决策与验收**；subagent 负责**执行**。主 agent 的上下文要留给
判断，不要把整库读进来。

```text
巡检仓库 → 规划 → 并行巡检 subagent（只读）→ 主 agent 决策
        → 改代码（subagent）→ 测试 → 独立 reviewer → 完成
```

硬性规则（都是踩过坑换来的）：

1. **subagent 自报完成不算数。** 主 agent 必须自己跑一遍全量测试并在需要时做浏览器实测。
   已发生过两次 subagent 声称"全绿"而工作树**编译不过**。
2. **并发按「文件区域不重叠」分配。** 重叠就串行。已发生过：一个 agent 的测试编译错误
   把另外两个 agent 和主 agent 的 dev 后端全部堵死。
3. **独立 reviewer 阶段不可省。** 实现完成、测试通过后，另派一个只读 subagent 做对抗性
   评审（正确性、竞态、覆盖面、简化机会、测试是否真的证明了它声称的东西）。主 agent
   自己的"验收"抓得到 bug，但抓不到设计层面的问题。
4. **subagent 不得重启主 agent 的本地 dev 后端**（8080）。需要验证就走测试，不要起服务。
   已发生过：某 agent 重启后端并覆盖了日志，导致主 agent 实测时看到"订单读取失败"。
5. **测试不许塞进长期共享的大文件**（如 `InfrastructureIntegrationTest`，1900+ 行）。
   新功能用自己的测试类。
6. **提交由主 agent 做**，按逻辑单元分批，**reviewer 通过后才提交**。

2026-10-05 user instruction: after backend API implementation, full tests and review pass,
stop agent-led frontend/browser acceptance; the user checks frontend behavior. Record UI
acceptance as pending user confirmation rather than claiming agent browser completion.

## 7. 已知取舍与风险

- 注册验证码接口成功返回 `202` 空响应体，前端必须按无返回值的成功确认处理，不能继续解析 JSON；
  管理员登录的 `202` 则含 MFA challenge JSON，不能对所有 `202` 全局跳过解析。

- **删除节点会丢弃该节点最后一个上报周期（默认 60 秒）的流量。** 收尾报告到达时
  节点记录已删除，后端无法解析 `groupIds`，也就无法校验这台机器有权给哪些用户记账；
  跳过校验会让被攻破的机器令牌能给任意用户记流量，代价高于这点流量。彻底修复需要
  软删除并改造全部节点查询。停用节点不受影响。
- **真实客户端导入已验证。** 2026-09-24 在 ByteVirt-SG 上以 xboard-node 机器模式跑通
  真实链路：三个有权益用户的订阅均含该节点（七种格式里六种有它，`clash` 经典格式按
  设计排除 SS2022）；从一台**外部机器**用 sing-box 经该节点出网，出口 IP 与地理位置
  均为该节点；节点上报总量与各用户入账之和**精确相等**。
- **独立订阅域名未做。** 当前 `SubscriptionLinkService` 只取配置列表的第一个域名。
  原版是每次随机挑一个，副作用是链接会漂移；要做多域名又不漂移，应按用户确定性取模。
- **Unsupported admin setting skeletons were removed.** The old 74/33 count is obsolete;
  subscription, onboarding, commission and new-account reminder settings are wired. Logo,
  Telegram and dedicated-client/app controls are not presented as editable features.
- **在线设备数据依赖节点上报。** 本项目只用 xboard-node，其链路与设备上限无关；
  若日后接入 V2bX / XrayR 需重新验证。
- **新建节点后 xboard-node 不会自动启动内核，必须 `xbctl restart`。** 表现为日志里
  `users updated, N users` 已出现、但 `ss` 看不到监听端口、内核进程不存在。重启后
  立即 `[<proto>:<port>] started`。属 agent 侧行为，面板侧无需改动。
- **经典 Clash（`?flag=clash`）不出 SS2022 节点。** `ClashRenderer` 有意过滤
  `2022-blake3-*` 与 vless（旧内核不支持），代码注释已说明；mihomo / meta / stash
  无此限制。若客户群里有经典 Clash 用户，需引导换内核或给节点配经典 cipher，
  否则他们订阅后一个节点都看不到。
- **节点域名需要真实 DNS 记录。** 测试期 host 只能填裸 IP。注意 `198.18.0.0/15` 是
  代理工具的 fake-IP 段：在装有 Clash/Surge 类工具的机器上解析节点域名会得到
  `198.18.x.x`，据此判断「公网不可达」是错的——可达性必须在**无代理的机器**上判断。
- **节点机器要有一张有效证书，`cert_config` 必须配。** agent 会给每个节点下发证书；
  测试机上原指向的 `*.node.sinx.it.com` 已于 2026-09-05 过期，导致所有 TLS 协议
  握手失败（症状是客户端全部报错，而非面板报错）。现指向 `dev.sinx.it.com`
  （真实 LE 证书，自动续期）。**给节点开 TLS 协议前先确认证书有效期。**
- **Hysteria2 / TUIC 走 QUIC，`ss -tlnp` 看不到监听。** 必须用 `ss -ulnp` 查，
  否则会误判成"节点没起来"。
- **sing-box 客户端版本影响协议支持。** v1.11.x **不支持 anytls** outbound（报
  `unknown outbound type: anytls`），会被误判成服务端故障；v1.12.x 起支持。
  另 v1.12.x 的 macOS 二进制在 macOS 26 上可能被系统 kill（exit 137），
  需 `codesign --force --sign -` 临时签名。
- **Surge 的 Hysteria2 混淆参数是 `salamander-password`，不是 `obfs=`/`obfs-password=`。**
  Surge 把模式折进参数名（`salamander-password` / `gecko-password`），Clash 与 URI
  语法才是 `obfs=salamander` + `obfs-password=`。Surge 的 Hysteria2 **只文档化
  `download-bandwidth`，没有 `upload-bandwidth`**（原版 `Surge.php:249` 写了它，
  属上游缺陷，本仓库已移除）。
- **`bandwidth.down = 0` 会在 Surge 里渲染成 `download-bandwidth=0`。** Hysteria2
  服务端文档说 0 表示不限速，但 Surge 客户端是否同样解释**未经验证**；若日后有
  Surge 用户反馈被限速，先查这里。
- **AnyTLS 在 sing-box 渲染器里会带 `alpn:["h3"]`。** 来源是上游 PHP 默认值的字面
  移植（校验器只为 hysteria/tuic 定义 alpn，AnyTLS 无该字段故默认必然触发）。已确认
  不影响连通（实测可用），暂留观；若日后 AnyTLS 出现握手问题，此处是第一嫌疑。
- **`mieru` 协议没有任何订阅渲染分支**（Surge/SingBox/URI 渲染器 `entry()` 均无 case），
  但后台 UI 允许创建 mieru 节点。属既存缺口，已知未修。
- **测试机磁盘会被镜像撑满，且有陷阱。** 每部署一次落一个约 540MB 的后端镜像，而根分区
  只有 10GB。这台机器的 Docker 用的是 **containerd snapshotter**（`overlayfs` /
  `io.containerd.snapshotter.v1`），**`docker image prune` 清不到 containerd 的
  content store** —— 手动清理时曾因此少释放一大半空间。机器上已装
  `sinx-docker-prune.timer`（每周日 04:23，`/usr/local/sbin/sinx-docker-prune`）自动清理，
  同时清 Docker 与 containerd 两侧，并保留一周内的旧镜像作为回滚路径。
- **私有 GHCR 镜像无法用公共 registry mirror 加速。** mirror 只代理 Docker Hub 的
  `library/*`，不会代理 `ghcr.io/<org>/<pkg>`。实测 GHCR 到新加坡有 **8 MB/s**，
  本身不是瓶颈——**部署慢的真因是磁盘满导致解压失败重试**，不是网络。
  （另：不要用 `https://ghcr.io/v2/` 的响应速度估算镜像下载速度，那只有 19 字节的
  401 挑战体，会得出荒谬的数字。）

## 8. 计划维护规则

- 每完成一个功能，同步更新待办与已完成，代码与计划进同一次提交。
- 删除或放弃功能时记录原因与数据迁移处理。
- 未通过测试或人工验收的功能不能标记完成。
- 文档不得记录密码、Token、MFA 恢复码、私钥或其他凭据。
- `plan.md` 是唯一进度清单，禁止积压后再补。

## 9. 部署

### Aliyun proxy test deployment (2026-10-10)

- Test VPS: `Aliyun-Eason-main`, `120.27.158.209`; the former subscription VPS
  `114.215.183.154` expired and was deleted. Removed its obsolete SSH alias locally.
- Created machine 3 and nodes 10 (`Aliyun-Reality-3306`, TCP 3306) and 11
  (`Aliyun-Hysteria2-3306`, UDP 3306) through the public dev admin UI using CDP.
  Node 10 uses machine mode; node 11 uses standalone mode on the same VPS because
  machine port validation incorrectly treats TCP/UDP sharing a number as a collision.
  This validation issue remains open; no application code was changed.
- With explicit user authorization, gracefully stopped the existing MySQL service
  using `/etc/init.d/mysqld stop`; its data and configuration remain intact.
  Pre-change configuration/firewall/listener backup:
  `/root/sinx-proxy-test-20261010-020853`. Added only UDP 3306 to runtime and
  permanent firewalld rules; the original TCP 3306 rule was already present.
- Both xboard-node instances target `https://dev.sinx.it.com`. Hysteria2 inherits
  the existing `linyirentest.xyz` certificate at
  `/www/server/panel/vhost/cert/120.27.158.209/`, valid through 2026-11-25.
- Real subscription-generated sing-box 1.14.3 verification: external HK client
  through VLESS returned Apple HTTP 200; local Hysteria2 client also returned
  Apple HTTP 200. External Hysteria2 times out and simultaneous AF_PACKET capture
  on Aliyun eth0 saw zero UDP 3306 packets. Public UDP remains blocked upstream;
  investigate the cloud security group before marking Hysteria2 fully verified.
- Both nodes report online in the user UI. Desktop/mobile screenshots inspected.
  Independent read-only operations review passed host deployment and rollback;
  public Hysteria2 remains unverified. Existing Nginx TCP 443 and subscription
  root-path 404 were preserved.
- At the user's request, removed obsolete dev nodes 1–9 through admin UI delete
  confirmations: four E2E placeholder nodes (IDs 1–4) and five former SG nodes
  (IDs 5–9).
  Only Aliyun nodes 10 and 11 remain; refreshed user UI confirms two nodes.
- Restore after testing: stop `xboard-node`, verify TCP 3306 is free, run
  `/etc/init.d/mysqld start`, remove runtime and permanent `3306/udp` firewalld
  rules, and verify MySQL/site listeners. Keep the original `3306/tcp` rule.

#### Port migration and MySQL restoration (2026-10-10, completed)

- At the user's request, moved node 10 to TCP 8443 (`Aliyun-Reality-8443`) and
  node 11 to UDP 8444 (`Aliyun-Hysteria2-8444`) through CDP admin form saves.
  Node IDs, authentication, permission group and deployment bindings are retained.
- Migration backup: `/root/sinx-proxy-move-20261010-153749`. Added runtime and
  permanent `8443/tcp` and `8444/udp` firewalld rules; removed both temporary
  `3306/udp` rules. The original `3306/tcp` rule remains unchanged.
- Restarted only the Aliyun dev xboard-node and confirmed no proxy listener on
  3306, then restored MySQL with `/etc/init.d/mysqld start` (SUCCESS).
  MySQL now listens on TCP 3306 and an external HK connection receives a valid
  MySQL protocol-10 greeting. Do not stop MySQL again without authorization.
- Fresh subscription-generated clients: public VLESS TCP 8443 returned Apple
  HTTP 200; local Hysteria2 UDP 8444 returned Apple HTTP 200. Public Hysteria2
  UDP 8444 still times out; cloud security-group UDP 8444 admission remains
  pending. Existing subscription gateway root still returns HTTP 404.
- User identified security-group TCP/UDP 5201 rules from `14.137.229.93/32`
  as obsolete Gomami-JP bandwidth testing and plans to remove them personally.

#### Four-protocol public verification (2026-10-10, completed)

- User opened both TCP and UDP 8443/8444 in the Aliyun security group.
  Through CDP admin forms, created TUIC v5 node 12 (`Aliyun-TUIC-8443`, UDP
  8443, BBR/native relay) and AnyTLS node 13 (`Aliyun-AnyTLS-8444`, TCP 8444).
  Both use the existing test permission group, IP `120.27.158.209`, verified
  `linyirentest.xyz` SNI and inherited valid certificate; insecure TLS is off.
- Final layout: machine 3 hosts VLESS node 10 / TCP 8443 and AnyTLS node 13 /
  TCP 8444. Standalone bindings host TUIC node 12 / UDP 8443 and Hysteria2
  node 11 / UDP 8444, avoiding the known same-machine TCP/UDP port validator.
- Pre-change backup `/root/sinx-four-protocols-20261010-160434`. Added only
  runtime/permanent `8443/udp` and `8444/tcp` rules to the previous deployment.
  Restarted only the Aliyun dev xboard-node; all four listeners are healthy.
- Fresh user subscription-generated sing-box 1.14.3 client configs were tested
  from external HK: VLESS, Hysteria2, TUIC and AnyTLS each returned Apple HTTP
  200 with curl exit 0. Previous public UDP blockers are resolved.
- User UI shows four nodes online, zero unknown/offline; desktop and mobile
  screenshots inspected. Independent read-only operational review passed.
  MySQL remains PID 2086748 on TCP 3306, original Nginx remains on TCP 443,
  subscription gateway root returns HTTP 404, and no UDP 3306 rule remains.

GitHub Actions 验证并构建不可变镜像，服务器从 GHCR 拉取指定版本部署。
新平台镜像由 `platform-publish` 工作流在 `dev` 分支发布（`xboard-backend`、
`xboard-frontend`），测试环境用 `compose.test.yml`。
