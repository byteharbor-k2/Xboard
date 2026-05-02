Xboard 分支结构 + 你的 fork 二开工作流

  一、上游 6 个分支各自定位

  分支: master ⭐
  有源码？: ✅ 完整源码
  关键文件: Dockerfile、composer.json、app/、compose.sample.yaml（dev 模板）
  用途: 源码 + 二开主仓库
  你该不该用: ✅ 你 fork 的就是这个，正确
  ────────────────────────────────────────
  分支: compose
  有源码？: ❌ 只有部署配置
  关键文件: compose.yaml（成品）+ .env
  用途: 纯部署：拉官方预构建镜像，docker compose up 就跑
  你该不该用: ⚠️  用于部署 vanilla Xboard，不能直接用于你的 fork（它指向 cedar2025 的镜像）
  ────────────────────────────────────────
  分支: docker-compose
  有源码？: ❌ 同上
  关键文件: docker-compose.yaml
  用途: 老命名，已被 compose 取代
  你该不该用: ❌ 不用
  ────────────────────────────────────────
  分支: dev
  有源码？: ✅ 源码
  关键文件: 老结构（看见 webman.php），可能是 webman 实验分支
  用途: 老开发分支
  你该不该用: ❌ 不用
  ────────────────────────────────────────
  分支: new-dev
  有源码？: ✅ 源码
  关键文件: 类似 master
  用途: 新功能开发
  你该不该用: ❌ 不用
  ────────────────────────────────────────
  分支: revert-755-...
  有源码？: —
  关键文件: —
  用途: 临时 revert
  你该不该用: ❌ 不用

  二、关键认知：源码分支 vs 部署分支

  Xboard 是 "代码归代码、部署归部署" 的拆分：

  GitHub Actions
  master 源码 ──────────────►  ghcr.io/cedar2025/xboard:latest（官方镜像）
                                              │
                                              ▼
                                compose 分支的 compose.yaml 引用这个镜像
                                              │
                                              ▼
                                       生产 VPS 上 docker compose up

  部署分支不需要源码——所有依赖都已经烧进镜像里了，部署只需要"配置 + 数据"。

  三、看几个实际差异

  我刚抓了 compose 分支的 compose.yaml，对比 master/compose.sample.yaml：

  ┌──────────┬─────────────────────────────────┬───────────────────────────────────────────────────┐
  │   维度   │   master compose.sample.yaml    │             compose 分支 compose.yaml             │
  ├──────────┼─────────────────────────────────┼───────────────────────────────────────────────────┤
  │ 镜像     │ ghcr.io/cedar2025/xboard:new    │ ghcr.io/cedar2025/xboard:latest                   │
  ├──────────┼─────────────────────────────────┼───────────────────────────────────────────────────┤
  │ 网络     │ network_mode: host ❌ Mac 不通  │ ports: ["7001:7001"] ✅ Mac 直通                  │
  ├──────────┼─────────────────────────────────┼───────────────────────────────────────────────────┤
  │ 挂载源码 │ ./:/www/ （把整个仓库覆盖容器） │ 不挂源码，只挂 .env、数据、storage/theme、plugins │
  ├──────────┼─────────────────────────────────┼───────────────────────────────────────────────────┤
  │ 用途     │ 给开发者改代码用                │ 给部署用，不能改源码                              │
  └──────────┴─────────────────────────────────┴───────────────────────────────────────────────────┘

  你目前撞上的所有问题（缺 vendor、Redis 连不上、Mac localhost 不通）全是因为你用了 master 的 dev 模板——它假设你在 Linux
  上、本地有 PHP 装好、要边改边看。

  四、Dockerfile 是 fork 友好的

  我看了 master/Dockerfile，它有这两行：

  ARG REPO_URL=https://github.com/cedar2025/Xboard
  ARG BRANCH_NAME=master
  RUN git clone --depth 1 --branch ${BRANCH_NAME} ${REPO_URL} .

  意思是：构建镜像时用 build-arg 改这两个参数，就能把你 fork 的代码烧进镜像：

  docker build \
    --build-arg REPO_URL=https://github.com/byteharbor-k2/Xboard \
    --build-arg BRANCH_NAME=master \
    -t ghcr.io/byteharbor-k2/xboard:latest .

  ---
  给你的两套推荐工作流（长期可维护视角）

  工作流 A：覆盖式（推荐起步，最小心智负担）

  ▎ 思路：用上游官方镜像，本地/生产都只把"你 fork 比 vanilla 多/改的几个文件"挂进去。

  你目前对 vanilla Xboard 的改动只有两类：
  - 新增：theme/Freedom/（一整个新主题目录）
  - 修改：routes/web.php（加了 /app 路由）

  本地 compose.yaml（自己写的，gitignored）：

  services:
    xboard:
      image: ghcr.io/cedar2025/xboard:latest   # 官方镜像
      restart: unless-stopped
      ports:
        - "7001:7001"                          # Mac 直接能访问 localhost:7001
      volumes:
        - ./.env:/www/.env
        - ./.docker/.data/:/www/.docker/.data
        - ./storage/logs:/www/storage/logs
        - ./storage/theme:/www/storage/theme
        - ./plugins:/www/plugins
        - ./theme/Freedom:/www/theme/Freedom   # ← 注入你的主题
        - ./routes/web.php:/www/routes/web.php # ← 注入你的路由改动
        - redis-data:/data
      environment:
        - RESOURCE_PROFILE=balanced
        - ENABLE_HORIZON=true
        - docker=true

  volumes:
    redis-data:

  生产环境：完全一样的 compose.yaml，只是 VPS 上得有 fork 仓库（git clone 你的 fork 即可，不需要 composer
  install，因为镜像里都有）。

  ┌───────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────────┐
  │                         优点                          │                               缺点                                │
  ├───────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
  │ 不需要构建镜像                                        │ 改了 vanilla 的核心 PHP 文件时，每多改一个文件就要多挂一个 volume │
  ├───────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
  │ 本地/生产配置一致                                     │ 文件级 mount 在 macOS Docker Desktop 上有微小性能损耗（可忽略）   │
  ├───────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────┤
  │ 升级 Xboard：docker compose pull 即可（你的改动不变） │                                                                   │
  └───────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────┘

  工作流 B：构建式（深度二开后再切）

  ▎ 思路：把你 fork 当独立产品，构建自己的 docker 镜像，发布到 GitHub Container Registry，本地/生产都拉自己的镜像。

  设置步骤：
  1. 在你 fork 上加 GitHub Actions（每次 push master 自动构建并推送到 ghcr.io/byteharbor-k2/xboard:latest）
  2. 本地/生产 compose.yaml 都引用 image: ghcr.io/byteharbor-k2/xboard:latest
  3. 不需要挂任何源码，配置/数据 volumes 即可

  ┌────────────────────────────┬─────────────────────────────────────────────────────────┐
  │            优点            │                          缺点                           │
  ├────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ 镜像就是部署单元，最干净   │ 需要学/配 GitHub Actions（一次性投入 1-2 小时）         │
  ├────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ 任何源码改动都自动收进镜像 │ 每次改 PHP 都要等 CI 跑完才能在生产看到（约 5-10 分钟） │
  ├────────────────────────────┼─────────────────────────────────────────────────────────┤
  │ 适合改动量大的二开         │                                                         │
  └────────────────────────────┴─────────────────────────────────────────────────────────┘

  ---
  我的建议

  先走工作流 A：

  1. 你目前的改动只动了 2 处（新增主题 + 加路由），mount 文件就够
  2. 学习曲线接近零（你已经懂 docker compose）
  3. 等改动深入到要改 controller/service/model 时再切到 B

  具体怎么改：
  1. 把现在 Xboard/compose.yaml 替换成上面工作流 A 的版本
  2. 修复 Xboard/.env（DB 用 SQLite、Redis 用 TCP——因为 compose 分支的镜像内置 Redis 走 TCP 而不是 socket）
  3. 重跑 xboard:install（这次会写正确配置 + 创建管理员 + 打印密码）
  4. 把你之前手动加的 Xboard/theme/Freedom/ 和 routes/web.php 改动 commit + push 到你 fork 的 master（这样以后生产 VPS git pull
  就能拿到）

  你确认走 A，我就开始做这 4 步。
