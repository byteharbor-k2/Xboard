# SinX Platform 全新架构与开发计划

## 1. 项目目标

在 `dev` 分支上重新实现 Xboard 提供的核心业务能力，但不继承 Laravel
代码、不兼容旧 Xboard 的网站、用户和管理 API、不迁移旧数据库，也不保留
Xboard 的页面、资源、路由和响应格式。唯一兼容例外是现有 `xboard-node`
节点程序所需的面板协议。

新系统采用：

- 后端：Java 21 + Spring Boot
- 前端：React + TypeScript
- 数据库：PostgreSQL
- 缓存与临时状态：Redis/Valkey
- 边缘入口：Caddy
- 节点控制：Spring Boot实现兼容面板接口 + 现有 `xboard-node`
- 公网测试环境：`ByteVirt-SG`

这是一套全新产品，而不是在 Xboard 上继续打补丁。

## 2. 测试环境现状

### ByteVirt-SG

- 系统：Ubuntu 22.04 LTS
- CPU架构：x86_64
- 内存：约 1 GB
- Swap：约 256 MB
- 系统盘：约 10 GB
- SSH：`16193/tcp`
- 当前旧节点进程：`xboard-node`
- 当前旧节点健康端口：`65530/tcp`
- 当前未安装 Docker 和 Nginx

### 测试域名

- `dev.sinx.it.com`
  - 新 React网站和 Spring Boot API测试入口
- `cdnsg.node.sinx.it.com`
  - 现有 `xboard-node` 与新 Spring Boot控制面的连接测试域名

测试环境没有生产用户数据，数据库从空库开始。

## 3. 总体架构

```text
                         Internet
                            │
          ┌─────────────────┴─────────────────┐
          │                                   │
 dev.sinx.it.com                    cdnsg.node.sinx.it.com
          │                                   │
          └──────────────┬────────────────────┘
                         │
                Caddy Edge Gateway
          HTTP/3 + HTTP/2 + HTTP/1.1 + TLS
                         │
       ┌─────────────────┼──────────────────┐
       │                 │                  │
 React Static App   Spring Boot API   Node Control Gateway
       │                 │                  │
       │       ┌─────────┼─────────┐        │
       │       │         │         │        │
       │   PostgreSQL  Redis    Job Worker  │
       │                           HTTP API/WebSocket
       │                                     │
       └──────────── Browser                 │
                                             │
                                     xboard-node
                                             │
                                  Proxy Runtime/Protocols
```

生产环境可以横向扩展，但 ByteVirt-SG 测试阶段全部运行在单机，并通过容器网络
隔离内部服务。

## 4. 技术栈

### 4.1 后端

- Java 21
- Spring Boot 4.1
- Spring Security
- Spring GraphQL
- Spring Data JPA
- PostgreSQL Driver
- Flyway
- Bean Validation
- Micrometer
- OpenTelemetry
- Testcontainers
- Maven

后端采用模块化单体，避免在业务尚未稳定时引入微服务复杂度。

建议模块：

```text
backend/
  bootstrap/
  identity/
  catalog/
  subscription/
  order/
  payment/
  coupon/
  node/
  traffic/
  support/
  notification/
  audit/
  shared/
```

模块之间通过明确的应用服务和领域事件通信，不允许跨模块直接修改数据库表。

### 4.2 前端

- React
- TypeScript
- Vite
- React Router
- TanStack Query
- React Hook Form
- Zod
- Zustand（仅用于少量客户端状态）
- 自建视觉设计系统

不复用 Xboard 的主题、HTML、CSS、图标、文案和编译资源。

### 4.3 数据与基础设施

- PostgreSQL：持久业务数据
- Redis/Valkey：验证码、限速、短期会话和任务协调
- Caddy：TLS、HTTP/3、HTTP/2、静态资源和反向代理
- Docker Compose：测试环境部署
- GHCR：发布不可变镜像
- GitHub Actions：测试、构建、镜像发布

ByteVirt-SG 资源较低，测试阶段建议限制：

- Spring Boot JVM：最大堆约 320–384 MB
- PostgreSQL：约 128–192 MB
- Redis/Valkey：约 64 MB，并设置淘汰策略
- Caddy与 React静态资源：约 64–96 MB

## 5. 公网协议

Caddy统一监听：

- `80/tcp`：跳转 HTTPS和证书验证
- `443/tcp`：HTTP/1.1、HTTP/2
- `443/udp`：HTTP/3/QUIC

浏览器自动协商协议。HTTP/3不可用时必须自动回退 HTTP/2或 HTTP/1.1。

Spring Boot、PostgreSQL和 Redis端口不直接暴露公网。只有 Caddy代理后的
xboard-node兼容接口可以由节点访问。

## 6. API边界

不把所有功能强行塞进 GraphQL，按使用场景选择协议。

```text
Browser
├─ POST /gateway                 GraphQL业务查询与修改
├─ /session/*                    登录、刷新、退出
└─ /files/*                      明确的文件下载

Subscription Client
└─ /connect/{opaqueCredential}   订阅获取

Payment Provider
└─ /hooks/payment/{provider}     支付异步通知

Node Agent
├─ xboard-node兼容 HTTP API      握手、配置、用户和流量
└─ xboard-node兼容 WebSocket     配置同步与状态事件
```

面向网站用户的公网不再存在：

- `/api/v1/*`
- `/api/v2/*`
- `/s/{token}`
- Xboard后台安全路径
- Xboard原始 JSON响应结构

节点专用域名将保留 xboard-node协议需要的兼容路由和响应结构，但不与用户网站
共用入口。

## 7. GraphQL设计

GraphQL仅服务 React用户端和管理端。

生产要求：

- 使用非默认入口 `/gateway`
- 关闭生产环境 Schema Introspection
- 关闭 GraphQL Playground
- 使用 Persisted Queries
- 限制查询深度、字段数量和执行成本
- 对管理操作执行独立权限检查
- 禁止在错误信息中暴露 Java类名、SQL和堆栈

示例模型：

```graphql
type Query {
  viewer: Viewer!
  plans(filter: PlanFilter): PlanConnection!
  orders(page: PageInput!): OrderConnection!
  nodes: [ClientNode!]!
  tickets(page: PageInput!): TicketConnection!
}

type Mutation {
  createOrder(input: CreateOrderInput!): CreateOrderPayload!
  applyCoupon(input: ApplyCouponInput!): ApplyCouponPayload!
  createTicket(input: CreateTicketInput!): CreateTicketPayload!
  revokeSubscriptionCredential(id: ID!): RevokeCredentialPayload!
}
```

GraphQL响应遵循标准 `data/errors` 结构，但扩展中加入追踪标识：

```json
{
  "data": {},
  "extensions": {
    "requestId": "01J...",
    "timestamp": "2026-07-27T10:30:00Z"
  }
}
```

## 8. REST与错误格式

认证、Webhook、订阅下载和节点注册继续使用 REST或专用协议。

REST错误采用 Problem Details：

```json
{
  "type": "https://dev.sinx.it.com/problems/coupon-not-applicable",
  "title": "Coupon cannot be applied",
  "status": 422,
  "code": "COUPON_NOT_APPLICABLE",
  "detail": "The coupon is not valid for this plan.",
  "instance": "/orders",
  "traceId": "01J..."
}
```

约定：

- HTTP状态码表达请求结果
- 稳定英文业务代码供前端判断
- 用户文案由 React国际化系统处理
- 时间统一为 ISO 8601 UTC
- 金额使用最小货币单位整数
- 流量统一使用字节整数
- 所有请求带 `requestId/traceId`

## 9. 身份认证

网站登录采用：

```text
用户名与密码/邮件验证
        │
        ▼
短期 Access Token（React内存）
        +
Refresh Token（HttpOnly Cookie）
```

要求：

- Cookie启用 `Secure`、`HttpOnly`、合理的 `SameSite`
- Refresh Token按设备保存并可单独撤销
- Access Token短期有效
- 刷新Token轮换并检测重放
- 管理员与普通用户权限完全分离
- 管理员支持 TOTP或 WebAuthn
- 密码使用 Argon2id
- 登录、验证码和重置密码均独立限速

## 10. 订阅系统

订阅下载不再使用永久 `/s/{token}`。

新模型：

```text
User
 └─ Subscription Credential
     ├─ 随机不可预测ID
     ├─ 独立密钥
     ├─ 设备标签
     ├─ 创建与最后使用时间
     ├─ 到期时间
     └─ 撤销状态
```

能力：

- 每个用户可以创建多个设备凭据
- 单个凭据泄漏时独立撤销
- 支持短期签名下载地址
- 支持请求频率和流量限制
- 默认禁止缓存和搜索引擎索引
- 订阅域名与主站域名分离
- 记录异常国家、ASN和并发使用，但避免保存不必要的隐私数据

订阅输出仍需符合目标客户端真实支持的格式，例如 Sing-box和 Clash；这是客户端
协议要求，不是对 Xboard 的兼容。

## 11. 节点控制面

继续使用现有 `xboard-node`程序，不开发新的节点代理。Spring Boot需要实现
xboard-node期望的面板 HTTP API、WebSocket消息、机器绑定、节点配置和流量
上报协议。

通信设计：

```text
Spring Node Control
        │
        ├─ 兼容 HTTP API
        ├─ 兼容 WebSocket
        └─ 节点/机器Token验证
        ▼
xboard-node
        │
        ├─ 接收版本化节点配置
        ├─ 健康检查
        ├─ 上报流量增量
        ├─ 上报在线状态
        └─ 管理本机代理协议
```

兼容范围只覆盖节点控制协议，不扩展到 Xboard用户端、管理端、支付、订阅或
数据库结构。Spring Boot内部使用自己的 Node、Machine、Binding和 Traffic
领域模型，通过适配器转换成 xboard-node需要的旧协议格式。

节点认证第一阶段沿用 xboard-node现有 Token机制，后续可以在不破坏客户端的
前提下增加来源限制、短期凭据或双向TLS网关。

测试阶段：

- `cdnsg.node.sinx.it.com` 指向 ByteVirt-SG
- 保留并重新绑定现有 `xboard-node`
- 将其面板地址切换到新 Spring Boot测试控制面
- 使用测试节点配置和虚拟流量验证兼容接口
- 不接入任何生产用户或生产节点

## 12. 核心数据模型

第一阶段数据实体：

- User
- Role
- DeviceSession
- Plan
- Subscription
- SubscriptionCredential
- Order
- Payment
- PaymentEvent
- Coupon
- Node
- NodeCertificate
- NodeConfigRevision
- TrafficUsage
- Ticket
- KnowledgeArticle
- Notification
- AuditEvent

PostgreSQL Schema由 Flyway从第一个版本开始管理，不导入 Laravel迁移。

## 13. 去除 Xboard应用指纹

- 不使用 Xboard路径、响应字段和错误码
- 不复用 Xboard前端资源和文案
- 不暴露 Spring Boot默认错误页和 Actuator
- 移除 `Server`、`X-Powered-By`等实现标识
- 自定义404、429和500响应
- 静态资源使用自己的构建、命名和缓存策略
- 公网证书只用于边缘入口
- 数据库、Redis和管理端口不暴露公网
- xboard-node兼容接口使用独立节点域名，并与用户网站接口隔离

这些措施用于减少开源产品默认指纹和攻击面，不承诺对 ISP、云厂商或流量分析
完全不可识别。

## 14. CI/CD

建议分支：

- `dev`：开发集成和 ByteVirt-SG测试环境
- `master`：稳定生产候选

目标流程：

```text
Push dev
   │
   ├─ Java测试、静态检查
   ├─ React测试、类型检查
   ├─ 数据库迁移测试
   ├─ 构建多架构镜像
   └─ 发布 sha-* 镜像到 GHCR
           │
           ▼
    ByteVirt-SG手动批准部署
```

初期保持手动批准部署；测试稳定后再考虑 `dev` 自动部署。

## 15. 实施阶段

### Phase 0：设计冻结

- 确定产品名称与视觉方向
- 确定领域模型和权限模型
- 确定 GraphQL Schema
- 确定 REST、Webhook和订阅协议
- 固化 xboard-node兼容协议清单与测试样本
- 输出威胁模型

### Phase 1：工程骨架

- 建立 Spring Boot Maven工程
- 建立 React TypeScript工程
- 建立 PostgreSQL、Redis和 Caddy测试栈
- 建立 GitHub Actions
- 部署 `/health` 最小闭环

### Phase 2：身份与用户

- 注册、登录、邮件验证
- Access/Refresh Token
- 设备会话管理
- 管理员权限与审计

### Phase 3：套餐、订单与优惠券

- 套餐目录
- 订单状态机
- 优惠券规则
- 支付抽象和 Webhook幂等

### Phase 4：订阅与节点

- 设备订阅凭据
- Sing-box和 Clash输出适配器
- xboard-node兼容 HTTP API
- xboard-node兼容 WebSocket
- 机器绑定与节点配置适配器
- 流量统计

### Phase 5：支持与运营

- 公告
- 知识库
- 工单
- 邮件通知
- 管理报表

### Phase 6：安全与上线准备

- 权限审计
- API模糊测试
- 依赖与镜像扫描
- 备份与恢复演练
- 压力测试
- HTTP/3、HTTP/2和回退测试

## 16. 第一阶段验收标准

- `https://dev.sinx.it.com` 可加载全新 React页面
- 浏览器可协商 HTTP/3或自动回退
- Spring Boot健康检查不直接暴露实现细节
- PostgreSQL迁移可从空库重复执行
- 用户可以注册、登录、刷新和退出
- GraphQL只接受允许的查询
- 错误响应格式统一并带 traceId
- 不出现任何 Xboard/Laravel页面、路径、Header或静态资源
- 现有 `xboard-node` 能通过 `cdnsg.node.sinx.it.com`完成握手、拉取配置、
  保持 WebSocket并上报测试流量
- CI生成与提交SHA绑定的不可变镜像

## 17. 暂不执行的事项

- 不修改或迁移生产 Xboard
- 不导入生产用户和订单
- 除 xboard-node控制协议外，不兼容旧 Xboard API
- 不复用旧订阅Token
- 不连接生产代理节点
- 不自动部署到生产
