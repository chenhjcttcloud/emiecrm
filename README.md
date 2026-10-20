# 亿觅 CRM

Java 21 / Spring Boot 3.5.16 + React + MySQL 8.4。本地使用 OrbStack Docker，服务器使用 Docker Compose。订单、待回款、预收款、客户、产品、系统管理均由 Java API 提供业务能力，仅支持飞书 SSO 登录。

生产访问地址由部署环境配置；不要将服务器凭据或业务数据提交仓库。

## 运行

复制 `.env.example` 为 `.env`，设置 MYSQL_PASSWORD、MYSQL_ROOT_PASSWORD、CRM_LAN_IP 后执行：

```sh
docker compose up -d --build
docker compose ps
```

浏览器访问 localhost:8088 或所配置内网 IP:8088。MySQL 仅在容器网络开放，数据保存在 mysql-data 卷。不要执行 `down -v`，这会删除数据库卷。

前端开发：`npm ci && npm run dev`，Vite 将 API 转发至本机 5174，需自行启动 Java 后端并配置数据库。完整前后端推荐通过 Docker 启动。

## 项目结构

- `backend/`：标准 Maven Java 后端，含 controller/service/repository/security/resources/test。
- `src/`：React 页面和样式。
- `tests/`、`compose.java-test.yaml`：独立数据库接口回归。
- `deploy/`：服务器编排、备份脚本和运维说明。
- `docs/`：部署与迁移记录。

生产 JRE 镜像不包含 Node 后端；Node 只用于构建前端和运行测试。原 Node 源码与镜像已保留在迁移备份中用于回滚。

详细说明见 backend/README.md、deploy/README.md、docs/JAVA-MIGRATION-2026-10-14.md。

飞书 SSO 使用独立应用与官方 Java SDK。认证配置、回调地址和首次管理员绑定流程见 docs/FEISHU-SSO.md。本地默认要求飞书登录；未配置时提示联系管理员。
