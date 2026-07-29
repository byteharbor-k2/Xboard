# Backend API Summary

> 当前范围：用户套餐与订阅权益、管理员仪表盘、系统设置
> 更新时间：2026-07-29

## 用户套餐与订阅权益 API

用户侧继续使用新的 GraphQL契约，不沿用 Xboard的 `/api/v1/user/plan/fetch`、
`/api/v1/user/getSubscribe`或默认响应包装。

统一入口：`POST /gateway`。

### 1. 可售套餐目录

- GraphQL查询：`offerCatalog`
- 登录要求：无
- 前端位置：`frontend/src/pages/PlansPage.tsx`

返回每个套餐的名称、说明、标签、总流量、限速、设备数、流量重置规则、
是否允许续费、剩余容量和可用价格周期。

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

## 通用约定

- 默认前缀：`/api/v2/admin`
- 可通过前端环境变量 `VITE_ADMIN_API_PREFIX` 修改前缀。
- 所有请求携带管理员 Access Token。
- 所有请求携带登录 Cookie。
- 返回类型为 JSON。
- 只有拥有 `ADMIN`角色并通过管理员登录校验的用户可以访问。
- 后端应对查询参数进行校验，并统一返回可识别的错误响应。

## 端点清单

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

交互：

- 待处理工单卡片跳转到工单管理。
- 待处理佣金卡片跳转到订单管理。

### 2. 收入趋势

- 方法：`GET`
- 路径：`/stat/getOrder`
- 前端区域：收入概览图表

查询参数：

| 参数 | 可选值 | 用途 |
|---|---|---|
| `range` | `7d`、`30d`、`90d` | 统计时间范围 |
| `metric` | `amount`、`count` | 按金额或订单数量统计 |

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `date` | string | 统计日期 |
| `value` | number | 当日金额或订单数量 |

返回值为按日期升序排列的数组。

### 3. 节点流量排行

- 方法：`GET`
- 路径：`/stat/getServerLastRank`
- 前端区域：节点流量排行

查询参数：

| 参数 | 可选值 |
|---|---|
| `period` | `today`、`yesterday`、`7d`、`30d` |

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | string | 节点唯一标识 |
| `label` | string | 节点名称 |
| `bytes` | integer | 统计周期内的流量 |
| `changePercent` | number/null | 与上一周期相比的变化比例 |

返回值按流量从高到低排列，前端展示前六项。

### 4. 用户流量排行

- 方法：`GET`
- 路径：`/stat/getTrafficRank`
- 前端区域：用户流量排行

查询参数：

| 参数 | 可选值 |
|---|---|
| `period` | `today`、`yesterday`、`7d`、`30d` |

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | string | 用户唯一标识 |
| `label` | string | 用户显示名称或邮箱 |
| `bytes` | integer | 统计周期内的流量 |
| `changePercent` | number/null | 与上一周期相比的变化比例 |

返回值按流量从高到低排列，前端展示前六项。

### 5. 系统服务状态

- 方法：`GET`
- 路径：`/system/getSystemStatus`
- 前端区域：系统状态
- 查询参数：无

返回字段：

| 字段 | 可选值 | 用途 |
|---|---|---|
| `key` | `api`、`database`、`redis`、`queue` | 服务名称 |
| `status` | `healthy`、`degraded`、`offline` | 当前状态 |

返回值为服务状态数组。

### 6. 队列统计

- 方法：`GET`
- 路径：`/system/getQueueStats`
- 前端区域：队列状态和作业详情
- 查询参数：无

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `status` | string | `healthy`、`degraded`或`offline` |
| `waitSeconds` | integer/null | 当前队列等待时间 |
| `recentJobs` | integer/null | 近期处理任务数量 |
| `jobsPerMinute` | number/null | 每分钟处理数量 |
| `errorsLastSevenDays` | integer/null | 最近七天失败任务数量 |
| `longestJobSeconds` | integer/null | 最长任务运行时间 |
| `activeProcesses` | integer/null | 当前活跃进程数量 |
| `maxProcesses` | integer/null | 最大进程数量 |

### 7. 队列工作负载

- 方法：`GET`
- 路径：`/system/getQueueWorkload`
- 前端用途：为后续队列名称、等待任务和各队列负载展示预留
- 查询参数：无

该端点已在前端端点清单中预留，具体返回字段将在队列后端实现时确定。

### 8. 失败任务分页

- 方法：`GET`
- 路径：`/system/getHorizonFailedJobs`
- 前端区域：失败任务详情弹窗

查询参数：

| 参数 | 类型 | 约束 |
|---|---|---|
| `page` | integer | 从1开始 |
| `pageSize` | integer | `10`、`20`或`50` |

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `items` | array | 当前页失败任务 |
| `page` | integer | 当前页 |
| `pageSize` | integer | 每页数量 |
| `total` | integer | 总记录数 |

每个失败任务包含：

| 字段 | 类型 | 用途 |
|---|---|---|
| `id` | string | 失败任务唯一标识 |
| `failedAt` | string | ISO 8601失败时间 |
| `queue` | string | 队列名称 |
| `jobName` | string | 任务名称 |
| `exception` | string | 异常摘要 |

前端支持刷新、修改每页数量、首页、上一页、下一页和末页。

## 前端实现位置

- 端点与数据类型：`frontend/src/admin/dashboardApi.ts`
- 仪表盘页面：`frontend/src/pages/AdminDashboardPage.tsx`

后端完成一个端点后，应同时补充权限测试、参数校验测试和返回契约测试。

## 系统设置 API

系统设置前端严格沿用原版 Xboard管理员配置接口和自动保存语义。当前支持的分区：

`site`、`safe`、`subscribe`、`invite`、`server`、`email`、`telegram`、
`app`、`subscribe_template`。

### 1. 读取配置分区

- 方法：`GET`
- 路径：`/config/fetch`
- 查询参数：`key`，值为一个有效配置分区

返回格式：

| 字段 | 类型 | 用途 |
|---|---|---|
| `data.<key>` | object | 所请求分区的键值配置 |

例如请求 `key=site`时，配置位于 `data.site`。

### 2. 保存配置分区

- 方法：`POST`
- 路径：`/config/save`

请求体直接包含当前分区的扁平配置键值，不额外包装 `section`或 `values`。
页面字段变化后按原版约 1 秒防抖自动提交；订阅模板约 1.5 秒。

返回格式沿用原版：`{"data": true}`。后端必须按字段白名单、类型和范围校验，
敏感字段不得写入日志或错误响应。

### 3. 测试邮件

- 方法：`POST`
- 路径：`/config/testSendMail`
- 前端区域：邮件与通知

使用当前已保存的 SMTP配置向管理员邮箱发送测试邮件。接口不接收或返回 SMTP密码。

### 4. 设置 Telegram Webhook

- 方法：`POST`
- 路径：`/config/setTelegramWebhook`
- 前端区域：Telegram通知

请求体为空。接口使用已经自动保存的 Telegram配置。

返回字段：

| 字段 | 类型 | 用途 |
|---|---|---|
| `data.success` | boolean | 设置结果 |
| `data.webhook_url` | string | Telegram最终使用的 Webhook地址 |
| `data.webhook_base_url` | string | Webhook基础地址 |

### 5. 邮件模板端点

以下端点供原版“邮件设置 / 模板管理”使用：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/mail/template/list` | 邮件模板列表 |
| `GET` | `/mail/template/get` | 读取单个模板 |
| `POST` | `/mail/template/save` | 保存模板 |
| `POST` | `/mail/template/reset` | 恢复默认模板 |
| `POST` | `/mail/template/test` | 测试渲染或发送 |

邮件模板接口参数与原版一致：

- `/mail/template/get`：查询参数 `name`。
- `/mail/template/save`：请求字段 `name`、`subject`、`content`。
- `/mail/template/reset`：请求字段 `name`。
- `/mail/template/test`：请求字段 `name`、可选 `email`。

### 6. 注册试用套餐选项

- 方法：`GET`
- 路径：`/plan/fetch`
- 前端区域：站点设置 / 注册试用

沿用原版套餐列表响应，前端使用 `data[].id`和 `data[].name`生成下拉选项。

### 配置字段范围

| 分区 | 当前前端字段 |
|---|---|
| `site` | 站点名称与描述、用户和订阅 URL、Logo、服务条款、货币、HTTPS、注册、工单和试用 |
| `safe` | 邮箱验证、Host限制、管理员路径、邮箱白名单、Gmail别名、CAPTCHA、注册与密码限速 |
| `subscribe` | 套餐变更、折抵方案、流量重置、订阅路径、线路展示、新购/续费/变更事件 |
| `invite` | 邀请码、佣金、提现和三级分成 |
| `server` | 通讯密钥、拉取/上报周期和 WebSocket |
| `email` | SMTP连接、发件地址和用户邮件提醒 |
| `telegram` | 机器人启用、Bot Token、Webhook和讨论群 |
| `app` | Windows、macOS和 Android版本及下载地址 |
| `subscribe_template` | Sing-box、Clash、Clash Meta、Stash、Surge和 Surfboard模板 |

系统设置前端实现位置：

- 端点与数据类型：`frontend/src/admin/systemSettingsApi.ts`
- 字段和双语结构：`frontend/src/admin/systemSettingsSchema.ts`
- 页面与交互：`frontend/src/pages/SystemSettingsPage.tsx`
