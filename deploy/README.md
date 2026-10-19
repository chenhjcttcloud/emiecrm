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
