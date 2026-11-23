# 当前云生产环境（2026-10-23 起）

生产服务器为 `8.135.51.39`，入口 https://crm.emie.cn:9002/，目录 `/opt/emiecrm`。使用 `compose.cloud.yaml` 模板和 CRM 专用 MySQL，数据库 `emiecrm_prod`、账号 `emiecrm_app`、容器 `emiecrm-prod` / `emiecrm-mysql-prod`、数据卷 `emiecrm-mysql-prod-data`，与其他项目隔离。详见 [迁移及运维记录](../docs/DEPLOYMENT-2026-10-23.md)。以下 1Panel 说明仅适用于保留的旧服务器。

## 聚水潭网关与云生产 CRM 同机部署

在 `/opt/emiecrm` 将 `deploy/compose.cloud.jst.yaml` 保存为 `compose.jst.yaml`，与现用的 `compose.production.yaml` 一起使用。将 `deploy/jst-gateway` 的网关源码放在 `/opt/emiecrm/jst-gateway`，将 `deploy/cloud/render-jst-route.sh` 放在 `/opt/emiecrm`。网关沿用现有 Compose 服务定义，只监听宿主机 `127.0.0.1:18766`。CRM 容器将 `crm.emie.cn` 解析到自身 Docker 网桥的宿主机地址，通过现有证书访问 `https://crm.emie.cn:9002/_internal/jst`。

1. **备份并迁移网关数据。** 备份生产 MySQL、现用 Compose、Nginx 配置和 `.env` / `.env.crm`，保留当前 CRM 与网关镜像。冻结旧网关写入，确认在途请求和凭证刷新已结束后停止旧容器。备份并校验旧网关的私密 `.env` 和整个 `data/`，包括 `audit.sqlite3`（含上传、改单去重记录）及 `tokens/`（含最新持久凭证）。复制到 `/opt/emiecrm/jst-gateway/`，保持 `.env` 为 `600`、持久目录及凭证权限和数字属主，确保 `data/` 及 `data/tokens/` 可由 UID/GID `10001` 写入。旧实例保持停止，保留原数据及私密备份。已有生产 MySQL 容器和数据卷继续沿用。
2. **以只读方式启动新网关。** 在网关私密 `.env` 保留正式聚水潭应用和凭证配置。CRM 私密 `.env.crm` 的 `CRM_JST_GATEWAY_TOKEN` 应与网关的 `GATEWAY_TOKEN` 一致，聚水潭应用凭证仅由网关持有。在 `/opt/emiecrm/.env` 将 `CRM_JST_BRIDGE_GATEWAY` 设为下方查询得到的现有网桥 IPv4 网关，并设 `CRM_JST_READ_ONLY=true`、`JST_AUTO_REFRESH=0`、`JST_ALLOW_ORDER_UPLOAD=0`、`JST_ALLOW_ORDER_CHANGE=0`。先核对新网关容器到聚水潭正式 API 的实际公网出口 IP，并将该地址加入正式应用白名单。

   ```sh
   docker network inspect --format '{{range .IPAM.Config}}{{println .Gateway}}{{end}}' emiecrm-prod-network
   ```

   ```sh
   cd /opt/emiecrm
   docker compose -f compose.production.yaml -f compose.jst.yaml config -q
   docker compose -f compose.production.yaml -f compose.jst.yaml up -d --build --no-deps jst-gateway
   curl -fsS http://127.0.0.1:18766/health
   ```

3. **安装并验证内部 HTTPS 路由。** 在生效的 Nginx `crm.emie.cn:9002` TLS `server` 块内加入 `include /opt/emiecrm/nginx/jst/*.conf;`。脚本从现有 `emiecrm-prod-network` 读取网段，仅放行该 Docker 网络，并去除代理请求的 `/_internal/jst/` 前缀。网络改名时传实际网络名，网段变化后更新网关地址并重新生成。先备份生效配置，配置校验通过后再重载，校验失败时恢复配置备份。

   ```sh
   install -d -m 0755 /opt/emiecrm/nginx/jst
   sh /opt/emiecrm/render-jst-route.sh > /opt/emiecrm/nginx/jst/gateway.tmp && \
     mv /opt/emiecrm/nginx/jst/gateway.tmp /opt/emiecrm/nginx/jst/gateway.conf && \
     nginx -t && systemctl reload nginx
   ```

   从公网、且不在允许网段的机器访问 `https://crm.emie.cn:9002/_internal/jst/health`，应返回 `403`。从 CRM 同一 Docker 网络的临时验证容器访问，令 `crm.emie.cn` 解析到 `CRM_JST_BRIDGE_GATEWAY` 并校验证书。内部不带令牌请求 `/_internal/jst/ready` 应为 `401`。带网关令牌应返回 `200`，确认 `environment=production`、上传、改单和自动刷新均关闭。再以相同方式对 `/_internal/jst/v1/query` 发送只读仓库查询 `/open/wms/partner/query`，带 `X-CRM-JST-Environment: production`，确认上游业务 `code=0`。令牌从私密配置读取，避免在终端输出、命令行参数或日志中留下值。
4. **切换 CRM 并保留回退路径。** 上述检查通过后仅重建 CRM 服务，随后核对 `/api/status`、首页与 JS/CSS、飞书登录和产品页只读查询。正式写入经权限和业务验收后单独启用。若要启用自动续期，先确认旧网关不会自动拉起，再为新实例设 `JST_AUTO_REFRESH=1` 并仅重建 `jst-gateway`，避免两个实例刷新同一凭证。

   ```sh
   docker compose -f compose.production.yaml -f compose.jst.yaml up -d --no-build --no-deps crm
   ```

   回退时停止新 `jst-gateway`，恢复旧 `.env.crm` 和 Nginx 路由，仅用原 `compose.production.yaml` 重建 CRM。确认旧网关仍可用且两端均未轮换凭证、没有新的订单写入时，才可恢复旧网关。若已轮换或发生写入，先核对平台状态并迁回最新凭证和去重数据，保留备份，避免旧副本重复提交。全程保留现有数据库和卷。

### 2026 年 11 月 27 日授权恢复与只读切换

生产机上的 `emiecrm-jst-cutover.timer` 单次触发时间为北京时间 12:00。签发前核对暂存授权未变更并保存配置备份。随后只请求一次新凭证，保存结果后经内网 HTTPS 核对 `TEST001` 和仓库 `15136229` 的库存。有有效库存记录时仅重建 CRM 服务，并核对飞书登录配置、页面资源和现有 MySQL 容器。验证失败时保留或恢复原配置；已签发的凭证保存在服务器私密目录，处理不确定结果时先核对签发记录。

```sh
systemctl list-timers --all emiecrm-jst-cutover.timer
journalctl -u emiecrm-jst-cutover.service --no-pager
```

触发前可用 `systemctl disable --now emiecrm-jst-cutover.timer` 取消。上传、改单和自动续期在本次切换中保持关闭。

# 生产部署（现有 1Panel MySQL）

将 deploy/compose.production.yaml 复制为服务器项目根目录的 compose.production.yaml。不要覆盖已有数据库密码和网络配置，不创建旧 mysql 服务，不删除任何数据卷。

生产 `.env`（权限600，禁止提交仓库）至少包含 MYSQL_PASSWORD、CRM_LAN_IP、CRM_DB_HOST（1Panel MySQL 容器名）、CRM_DOCKER_NETWORK（默认1panel-network）。飞书使用独立应用，设置 CRM_FEISHU_ENABLED=1、CRM_FEISHU_APP_ID、CRM_FEISHU_APP_SECRET、CRM_FEISHU_REDIRECT_URI，以及按需启用 CRM_FEISHU_LONG_CONNECTION=1。回调必须使用实际可访问的 HTTPS 域名和端口。

构建发布镜像：

```sh
docker buildx build --pull --platform linux/amd64 -t emiecrm:java-production-amd64 --load .
```

传输镜像至服务器后，先备份现有 MySQL，保存运行镜像的回滚标签及当前编排。然后仅更新应用：

```sh
docker compose -p emiecrm -f compose.production.yaml up -d --no-build --no-deps crm
```

备份脚本读取 `CRM_MYSQL_CONTAINER` 环境变量（1Panel MySQL 容器名），建议在 systemd 服务专用配置中设置。数据库凭据从容器环境读取，不写入脚本。备份和运维记录仅保留在服务器或本地私有目录。

首次启用登录前必须创建管理员，避免对公网开放初始化入口。已有管理员保持不变；首次飞书登录仅创建待授权账号。健康检查、匿名接口拒绝访问、管理员登录/退出、业务只读数据以及前端静态资源全部验证后才算部署完成。飞书长连接不应同时由本地和生产环境消费同一应用的事件。

回滚仅恢复旧应用镜像和配置，不将旧业务快照覆盖新增数据。不要执行 `down -v`。
