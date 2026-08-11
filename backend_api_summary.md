# Backend API Summary

> 当前范围：身份与注册安全、用户邀请、用户套餐与订阅权益、管理员套餐与节点
> 控制面、节点兼容协议（HTTP/轮询/WebSocket）、仪表盘契约、系统设置
>
> 更新时间：2026-08-11（同步至提交 `8e3f9c3`）
>
> 状态说明：本文区分「已实现」与「前端契约/预留」。标记为契约的端点当前后端
> 未实现，调用会失败，仅代表前端类型、交互与数据形状已先行就绪。

## 身份与注册安全 API（已实现）

### 用户注册

- `GET /session/registration/config`：返回邮箱验证与 Turnstile公开配置。
- `POST /session/registration/email-code`：通过人机验证后发送 6位邮箱验证码。
- `POST /session/register`：再次校验人机令牌和邮箱验证码，全部通过后才写入用户表。
- 邮箱验证码有效 5分钟，同邮箱默认 60秒内不能重复发送。
- 单 IP默认每 60分钟最多成功注册 3个账户。
- 生产环境强制配置 Cloudflare Turnstile站点密钥与服务端密钥。

新注册成功时邮箱已经验证，不再创建“未验证邮箱”的垃圾账户。无效、过期或
尝试次数超限的验证码不会产生数据库记录。

### 用户会话

- 用户登录、刷新和退出：`/session/login`、`/session/refresh`、
  `/session/current`。
- 用户 Access Token audience为 `sinx-web`，只包含 `USER`角色。
- 用户 Refresh Cookie默认名为 `rt_session`，路径限制为 `/session`。

### 用户邀请

- `GET /session/invitations`：当前用户可用的邀请码列表。
- `POST /session/invitations`：生成新的邀请码（受生成上限约束）。
- 注册请求可选携带 `inviteCode`，成功后建立邀请人与被邀请人关系。
- 邀请策略由管理员配置：是否强制邀请、佣金比例、生成上限、是否永不过期。

### 管理员会话

- 管理员密码登录：`POST /admin-session/login`。
- 首次 MFA配置：`POST /admin-session/enrollment`与
  `POST /admin-session/enrollment/confirm`。
- MFA登录完成：`POST /admin-session/login/mfa`。
- 刷新和退出：`POST /admin-session/refresh`与
  `DELETE /admin-session/current`。
- MFA状态和关闭：`GET/DELETE /admin-session/mfa`。
- 管理员 Access Token audience为 `sinx-admin`，只包含 `ADMIN`角色。
- 管理员 Refresh Cookie默认名为 `rt_admin`，路径限制为
  `/admin-session`。

用户与管理员 Refresh Token在数据库中也分别标记为 `USER`和 `ADMIN`。
即使人为交换 Cookie名称，错误作用域的 Refresh Token仍会被拒绝。

## 用户套餐与订阅权益 API（已实现）

用户侧继续使用新的 GraphQL契约，不沿用 Xboard的 `/api/v1/user/plan/fetch`、
`/api/v1/user/getSubscribe`或默认响应包装。

统一入口：`POST /gateway`。

### 1. 可售套餐目录

- GraphQL查询：`offerCatalog`
- 登录要求：无
- 前端位置：`frontend/src/pages/PlansPage.tsx`

返回每个套餐的名称、说明、标签、套餐类型、总流量、限速、设备数、流量重置
规则、是否允许续费、是否允许重置、单用户限购次数、剩余容量和可用价格周期。

金额使用最小货币单位的十进制字符串，流量使用字节数字符串，避免 GraphQL
32位整数溢出和浮点金额误差。

只有同时发布且可售、价格有效并且容量未满的套餐会进入用户目录。

### 2. 当前用户订阅权益

- GraphQL查询：`viewerEntitlement`
- 登录要求：有效 Access Token
- 前端位置：`frontend/src/pages/AccountOverviewPage.tsx`

返回当前用户的套餐快照、权益状态、总流量、上传、下载、已用和剩余流量、
使用率、限速、设备数、开通时间、到期时间、流量重置方式和下次重置时间。

权益状态包括：

- `ACTIVE`：有效且仍有流量。
- `EXPIRED`：超过有效期。
- `EXHAUSTED`：流量已用尽。
- `CANCELED`：权益已取消。

套餐和权益是两个独立模型。权益保存开通时的合同快照，后续修改套餐不会
静默改变已有用户的流量、限速、设备数或重置规则。

### 3. 当前用户与设备会话

- GraphQL查询：`viewer`、`deviceSessions`
- GraphQL变更：`revokeDeviceSession`
- 登录要求：有效 Access Token

`viewer` 返回当前用户资料与安全设置，`deviceSessions` 返回登录设备列表，
`revokeDeviceSession` 撤销指定设备会话。

## 管理员套餐控制面（已实现）

管理员套餐接口采用独立控制面契约，不使用 Xboard的
`/api/v2/admin/plan/fetch|save|update|drop|sort`路径，也不返回
`status/message/data`包装。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/control/catalog/plans` | 查询全部套餐、价格周期和订阅人数 |
| `POST` | `/control/catalog/plans` | 创建套餐 |
| `PUT` | `/control/catalog/plans/{id}` | 更新套餐及其价格周期 |
| `DELETE` | `/control/catalog/plans/{id}` | 删除没有订阅历史的套餐 |

接口仅接受管理员作用域令牌。错误使用 RFC 7807 Problem Details。
价格周期按套餐唯一；未发布、不可售、没有有效价格或容量已满的套餐不会
进入用户侧 `offerCatalog`。修改套餐不会自动覆盖已有订阅权益。

套餐类型分为：

- `SUBSCRIPTION`：有有效期的周期订阅，只允许月付、季付、半年付、年付、
  两年付和三年付价格，并按套餐流量重置规则运行。可选开放付费流量重置；
  开启时必须同时配置 `RESET_TRAFFIC`价格。
- `TRAFFIC_PACKAGE`：无到期时间、流量用尽即止，只允许流量包一次性价格；
  后端固定其流量重置规则为 `NEVER`且不允许续费。开启重置后必须同时配置
  重置包价格，可另设单用户限购次数。

前端位置：`frontend/src/pages/AdminPlansPage.tsx`、
`frontend/src/admin/planManagementApi.ts`。

## 管理员节点控制面（已实现）

保留原版 `/api/v2/admin/server/*` 前缀与操作语义，数据模型、权限检查和业务
服务全部使用新系统实现，错误使用 RFC 7807 Problem Details。

### 1. 节点管理

路径前缀：`/api/v2/admin/server/manage`

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/getNodes` | 查询节点列表（含访问组、路由、机器绑定与在线状态） |
| `GET` | `/generateEchKey` | 生成 ECH 密钥对 |
| `POST` | `/save` | 创建节点 |
| `POST` | `/update` | 更新节点 |
| `POST` | `/drop` | 删除节点 |
| `POST` | `/copy` | 复制节点 |
| `POST` | `/sort` | 节点排序 |
| `POST` | `/batchDelete` | 批量删除 |
| `POST` | `/batchUpdate` | 批量更新 |
| `POST` | `/resetTraffic` | 重置单个节点流量 |
| `POST` | `/batchResetTraffic` | 批量重置节点流量 |

### 2. 机器管理

路径前缀：`/api/v2/admin/server/machine`

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/fetch` | 机器列表 |
| `POST` | `/save` | 保存机器 |
| `POST` | `/resetToken` | 重置机器令牌 |
| `GET` | `/getToken` | 读取机器令牌 |
| `GET` | `/installCommand` | 生成安装命令 |
| `POST` | `/drop` | 删除机器 |
| `GET` | `/nodes` | 查询机器绑定的节点 |
| `GET` | `/history` | 机器负载历史 |

### 3. 访问组

路径前缀：`/api/v2/admin/server/group`：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/fetch` | 访问组列表 |
| `POST` | `/save` | 保存访问组 |
| `POST` | `/drop` | 删除访问组 |

### 4. 路由规则

路径前缀：`/api/v2/admin/server/route`：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/fetch` | 路由规则列表 |
| `POST` | `/save` | 保存路由规则 |
| `POST` | `/drop` | 删除路由规则 |

前端位置：`frontend/src/pages/AdminNodesPage.tsx`、
`frontend/src/pages/AdminMachinesPage.tsx`、
`frontend/src/pages/AdminNodeGroupsPage.tsx`、
`frontend/src/pages/AdminNodeRoutesPage.tsx`。

## 节点协议兼容层（已实现）

节点控制面兼容 xboard-node与旧版 UniProxy，用户代理流量不经过面板。

### 1. 原版节点协议

路径前缀：`/api/v2/server`

- `GET /config`：节点配置。
- `GET /user`：节点用户列表（按访问组过滤）。
- `POST /report`：流量上报，按权益计费并更新在线用户。

### 2. xboard-node机器模式

路径前缀：`/api/v2/server`

- `POST /handshake`：机器/节点/旧版令牌认证，返回 WebSocket 与轮询参数。
- `POST /machine/nodes`：机器绑定的节点列表。
- `POST /machine/status`：机器负载状态上报（CPU、内存、交换、磁盘、网络）。

### 3. 旧版 UniProxy 兼容

路径前缀：`/api/v1/server/UniProxy`

- `GET /config`、`GET /user`、`POST /push`、`POST /alive`、
  `GET /alivelist`、`POST /status`。

### 4. WebSocket 实时通道

- 路径：`/ws`，握手时完成认证（机器令牌、节点令牌、旧版令牌三种模式）。
- 推送：配置变更、用户列表、设备状态与访问组用户同步。
- 生命周期：WebSocket 开关关闭时拒绝握手并断开全部连接；旧版令牌轮换后
  断开非机器模式连接，机器模式连接在下次握手时使用新令牌重新认证。
- 轮询下限：pull 间隔 >= 30 秒，push 间隔 >= 10 秒。

## 仪表盘 API（前端契约，后端待实现）

以下端点在前端 `dashboardApi.ts` 中已定义契约和数据类型，后端统计模块尚未
实现，调用当前会失败。

### 1. 仪表盘汇总

- 方法：`GET`
- 路径：`/stat/getStats`
- 前端区域：顶部八个统计卡片
- 查询参数：无

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `todayIncome` | number | 今日收入 |
| `monthlyIncome` | number | 本月收入 |
| `pendingTickets` | integer | 待处理工单数量 |
| `pendingCommission` | integer | 待处理佣金数量 |
| `monthlyUsers` | integer | 本月新增用户 |
| `totalUsers` | integer | 总用户数量 |
| `monthlyUploadBytes` | integer | 本月上传字节数 |
| `monthlyDownloadBytes` | integer | 本月下载字节数 |

交互：待处理工单卡片跳转到工单管理，待处理佣金卡片跳转到订单管理。

### 2. 收入趋势

- 方法：`GET`
- 路径：`/stat/getOrder`
- 查询参数：`range`（`7d`、`30d`、`90d`）、`metric`（`amount`、`count`）
- 返回：按日期升序的 `{date, value}` 数组

### 3. 节点流量排行

- 方法：`GET`
- 路径：`/stat/getServerLastRank`
- 查询参数：`period`（`today`、`yesterday`、`7d`、`30d`）
- 返回：按流量降序的 `{id, label, bytes, changePercent}` 数组，前端展示前六项

### 4. 用户流量排行

- 方法：`GET`
- 路径：`/stat/getTrafficRank`
- 查询参数：`period`（`today`、`yesterday`、`7d`、`30d`）
- 返回：按流量降序的 `{id, label, bytes, changePercent}` 数组，前端展示前六项

### 5. 系统服务状态

- 方法：`GET`
- 路径：`/system/getSystemStatus`
- 返回：`{key: api|database|redis|queue, status: healthy|degraded|offline}` 数组

### 6. 队列统计

- 方法：`GET`
- 路径：`/system/getQueueStats`
- 返回：`status`、`waitSeconds`、`recentJobs`、`jobsPerMinute`、
  `errorsLastSevenDays`、`longestJobSeconds`、`activeProcesses`、
  `maxProcesses`

### 7. 队列工作负载

- 方法：`GET`
- 路径：`/system/getQueueWorkload`
- 用途：为队列名称、等待任务和各队列负载展示预留，具体返回字段待后端实现

### 8. 失败任务分页

- 方法：`GET`
- 路径：`/system/getHorizonFailedJobs`
- 查询参数：`page`（从 1 开始）、`pageSize`（`10`、`20`或`50`）
- 返回：`{items, page, pageSize, total}`；任务含 `id`、`failedAt`、`queue`、
  `jobName`、`exception`

前端位置：`frontend/src/pages/AdminDashboardPage.tsx`、
`frontend/src/admin/dashboardApi.ts`。

## 系统设置 API

系统设置前端沿用原版 Xboard管理员配置接口和自动保存语义。

后端已实现读写分区：

`site`、`safe`、`invite`、`email`、`server`

前端契约已就绪、后端待实现：

`subscribe`、`telegram`、`app`、`subscribe_template`（保存会失败，仅交互与
类型先行）。

### 1. 读取配置分区（已实现）

- 方法：`GET`
- 路径：`/config/fetch`
- 查询参数：`key`，值为一个有效配置分区

返回格式：`data.<key>` 为该分区的键值配置。

### 2. 保存配置分区（已实现）

- 方法：`POST`
- 路径：`/config/save`
- 查询参数：`key`，值为一个有效配置分区
- 请求体：直接包含当前分区的扁平配置键值，不额外包装 `section`或 `values`

返回格式沿用原版：`{"data": true}`。后端必须按字段白名单、类型和范围校验，
敏感字段不得写入日志或错误响应。

### 3. 测试邮件（已实现）

- 方法：`POST`
- 路径：`/config/testSendMail`

使用当前已保存的 SMTP配置向管理员邮箱发送测试邮件。接口不接收或返回 SMTP密码。

### 4. 设置 Telegram Webhook（前端契约，后端待实现）

- 方法：`POST`
- 路径：`/config/setTelegramWebhook`
- 请求体：空。前端定义返回 `{data: {success, webhook_url, webhook_base_url}}`。

### 5. 邮件模板端点（前端契约，后端待实现）

以下端点供原版“邮件设置 / 模板管理”使用，前端已定义调用：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/mail/template/list` | 邮件模板列表 |
| `GET` | `/mail/template/get` | 读取单个模板 |
| `POST` | `/mail/template/save` | 保存模板 |
| `POST` | `/mail/template/reset` | 恢复默认模板 |
| `POST` | `/mail/template/test` | 测试渲染或发送 |

参数约定：`get` 用查询参数 `name`；`save` 用 `name`、`subject`、`content`；
`reset` 用 `name`；`test` 用 `name` 和可选 `email`。

### 6. 注册试用套餐选项（前端契约，后端待实现）

- 方法：`GET`
- 路径：`/plan/fetch`
- 用途：站点设置 / 注册试用下拉选项，前端使用 `data[].id` 和 `data[].name`。

### 配置字段范围

| 分区 | 后端状态 | 当前前端字段 |
|---|---|---|
| `site` | 已实现 | 站点名称与描述、用户和订阅 URL、Logo、服务条款、货币、HTTPS、注册、工单和试用 |
| `safe` | 已实现 | 邮箱验证、Host限制、管理员路径、邮箱白名单、Gmail别名、CAPTCHA、注册与密码限速 |
| `subscribe` | 待实现 | 套餐变更、折抵方案、流量重置、订阅路径、线路展示、新购/续费/变更事件 |
| `invite` | 已实现 | 邀请码、佣金、提现和三级分成（提现与三级分成待后端细化） |
| `server` | 已实现 | 通讯密钥（系统生成 64 位十六进制、只读）、拉取/上报周期、设备限制模式、WebSocket |
| `email` | 已实现 | SMTP连接、发件地址和用户邮件提醒 |
| `telegram` | 待实现 | 机器人启用、Bot Token、Webhook和讨论群 |
| `app` | 待实现 | Windows、macOS和 Android版本及下载地址 |
| `subscribe_template` | 待实现 | Sing-box、Clash、Clash Meta、Stash、Surge和 Surfboard模板 |

系统设置前端实现位置：

- 端点与数据类型：`frontend/src/admin/systemSettingsApi.ts`
- 字段和双语结构：`frontend/src/admin/systemSettingsSchema.ts`
- 页面与交互：`frontend/src/pages/SystemSettingsPage.tsx`

## 通用约定

- 管理员 REST 默认前缀：`/api/v2/admin`
- 可通过前端环境变量 `VITE_ADMIN_API_PREFIX` 修改前缀。
- 所有请求携带管理员 Access Token。
- 所有请求携带登录 Cookie。
- 返回类型为 JSON。
- 只有拥有 `ADMIN`角色并通过管理员登录校验的用户可以访问。
- 后端应对查询参数进行校验，并统一返回可识别的错误响应。

后端完成一个端点后，应同时补充权限测试、参数校验测试和返回契约测试。
