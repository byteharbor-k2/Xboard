# Xboard 生产环境手动部署流程

本文记录 SinX Cloud Xboard fork 当前的生产发布方式。

## 当前发布架构

```text
本地提交并推送 master
        ↓
GitHub Actions 自动构建 amd64/arm64 镜像
        ↓
GHCR 发布 prod、latest、sha-* 和日期版本标签
        ↓
人工登录 Evoxt 执行部署脚本
        ↓
备份 → 拉取镜像 → 重建容器 → 数据库迁移 → 验证
```

- GitHub 仓库：`byteharbor-k2/Xboard`
- 生产主机 SSH 别名：`Evoxt-MY-Panel`
- 生产目录：`/opt/xboard-prod`
- 生产镜像：`ghcr.io/byteharbor-k2/xboard:prod`
- Compose 文件：`/opt/xboard-prod/compose.yaml`
- 部署脚本：`/opt/xboard-prod/scripts/deploy.sh`
- 回滚脚本：`/opt/xboard-prod/scripts/rollback.sh`
- 备份目录：`/opt/xboard-prod/backups`

## Step 1：确认代码和 CI

确认目标提交已经推送到 `master`，GitHub Actions 的 **Docker Build and Publish** 工作流执行成功。

`master` 构建成功后会发布以下标签：

- `prod`
- `latest`
- `master`
- `sha-<短提交号>`
- `<YYYYMMDD>-<短提交号>`

`prod` 适合表示最新生产候选版本，但它是可变标签。正式部署时应使用
`sha-*` 或日期标签，才能精确确认版本并可靠回滚。

## Step 2：部署前检查 Evoxt

登录生产服务器：

```sh
ssh Evoxt-MY-Panel
cd /opt/xboard-prod
```

确认当前容器正常运行：

```sh
docker compose --env-file .deploy/image.env -f compose.yaml ps
```

确认磁盘空间足够，并检查当前镜像记录：

```sh
df -h
cat .deploy/image.env
docker inspect xboard-prod-xboard-1 --format '{{.Image}}'
```

## Step 3：执行标准部署

执行现有部署脚本：

```sh
XBOARD_ROOT=/opt/xboard-prod \
  /opt/xboard-prod/scripts/deploy.sh \
  ghcr.io/byteharbor-k2/xboard:sha-<短提交号>
```

例如提交 `d4ad1f9...` 对应的镜像标签为 `sha-d4ad1f9`。

脚本会自动完成：

1. 将当前镜像记录为 `previous_image`。
2. 使用 SQLite `.backup` 创建一致性数据库备份。
3. 备份 `.env`、`storage`、`plugins` 和 `theme`。
4. 拉取新的 GHCR 镜像。
5. 重建并启动 Xboard 容器。
6. 执行 `php artisan migrate --force`。
7. 清理 Laravel 配置、路由、视图和应用缓存。
8. 输出 Compose 状态和最近 80 行容器日志。

部署通常会造成几十秒的短暂不可用。

## Step 4：确认镜像和迁移

确认容器已经使用新镜像：

```sh
docker inspect xboard-prod-xboard-1 \
  --format 'image={{.Image}} revision={{index .Config.Labels "org.opencontainers.image.revision"}}'
```

其中 `revision` 应等于本次部署的 Git 提交 SHA。

确认没有待执行迁移：

```sh
docker exec xboard-prod-xboard-1 \
  php artisan migrate:status --no-ansi
```

确认备份已经生成：

```sh
find /opt/xboard-prod/backups -maxdepth 3 -type f -ls
```

## Step 5：生产功能验证

检查容器和相关服务：

```sh
docker compose --env-file .deploy/image.env -f compose.yaml ps
systemctl status xboard-node --no-pager
ss -lntp | grep 8076
```

验证公网入口：

```sh
curl -I https://app.sinx.it.com/
curl -I https://dashboard.app.sinx.it.com/
curl -I https://sub.linyirentest.xyz/
```

说明：订阅域名根路径返回 `404` 是正常设计；有效订阅只能通过 `/s/<用户令牌>` 访问。

最后检查新容器日志中是否出现异常：

```sh
docker logs --since 10m xboard-prod-xboard-1
```

还应在浏览器中人工验证：

1. 前台首页和用户中心。
2. 管理后台。
3. 用户登录与订阅生成。
4. 节点在线状态。
5. 支付功能（涉及支付代码变更时）。

## Step 6：出现问题时回滚

现有回滚脚本会恢复 `.deploy/previous_image` 记录的上一镜像：

```sh
XBOARD_ROOT=/opt/xboard-prod \
  /opt/xboard-prod/scripts/rollback.sh
```

注意：该脚本只回滚容器镜像，不会自动回滚数据库结构。

如果上一轮和本轮都使用 `prod`，两条记录会指向同一个可变标签，回滚可能仍然
拉取最新镜像。因此只有使用不同的 `sha-*` 或日期标签部署时，当前回滚脚本才
能可靠恢复上一版本。

如果新版本已经执行数据库迁移，应先判断迁移是否向后兼容；只有确认需要恢复数据库时，才使用部署前的 SQLite 一致性备份。恢复数据库前必须停止 Xboard 容器，避免数据库、WAL 和 SHM 状态不一致。

## 发布原则

- 不直接修改生产容器里的源码。
- 不把 `.env`、私钥、隧道令牌或证书凭据提交到 Git。
- 每次部署前必须保留可验证的数据库备份。
- 优先部署提交专属镜像标签，避免 `prod` 可变标签造成版本不明确。
- 数据库迁移完成后，回滚前必须评估旧镜像与新数据库结构是否兼容。
