# DevOps TODO

## 待办：GitHub Actions CI/CD 自动构建镜像

### 背景
当前部署方式是 SSH 到 VPS 手动 `docker build`，每次更新需要人工操作。
长期应设置 CI/CD：push 代码 → GitHub Actions 自动构建 amd64 镜像 → 推送到 GHCR → VPS 拉取部署。

### 实施步骤
1. 在 `.github/workflows/` 下创建 build-and-push.yml
2. 配置 GitHub Actions 使用 `docker/build-push-action`
3. 目标 registry: `ghcr.io/byteharbor-k2/xboard`
4. 触发条件: push to master
5. 构建架构: linux/amd64（VPS 是 x86_64）
6. VPS 端配置 watchtower 或 webhook 自动拉取新镜像

### 参考
- 上游 Xboard 的 CI 配置（如有）
- GitHub Actions Docker 文档: https://docs.github.com/en/actions/use-cases-and-examples/publishing-packages/publishing-docker-images

---

## 待办：支付定价体系重构

### 问题
当前法币与加密货币的手续费定价不统一，需要重新设计定价模型，让两种支付方式的最终用户成本一致或透明。

### 状态
待设计，后续单独开发。

---

## 上游代码更新策略

不再通过 `git pull upstream` 自动合并上游更新。
改为手动审查上游优秀设计，cherry-pick 或手动移植有价值的改动到 fork 中。
原因：fork 会逐步积累自定义业务逻辑（支付、定价等），自动合并冲突风险过高。
