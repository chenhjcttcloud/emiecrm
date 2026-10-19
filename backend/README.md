# CRM Java 后端

后端已完整迁移至 Java 21 / Spring Boot 3.5.16，生产运行时仅含 JRE；React 前端由同一应用提供静态资源。Node.js 仅用于前端构建和接口测试工具。

## 标准目录

- `src/main/java/com/emie/crm/controller`：HTTP API、参数入口。
- `service`：订单、审批状态、收款、预收款核销、备份恢复和系统管理业务事务。
- `repository`：JdbcTemplate 持久化。
- `security`：权限、会话、兼容旧系统的 scrypt 密码校验。
- `common/exception`：统一错误响应。
- `config`、`filter`：静态页面路由与请求体大小限制。
- `src/main/resources/application.yml`：环境变量配置；`schema.sql`：幂等初始化。
- `src/test/java`：密码兼容单元测试；根目录 `tests`：真实 MySQL HTTP 合约回归。

## 数据与接口

沿用现有 MySQL 8.4 数据结构，业务快照在 state，收款在 receipts，日志在 audit，用户、角色、部门使用独立表。迁移不清空数据，不重置密码。业务快照仍为 JSON，本次未改成逐业务实体关系表。

API：`/api/status`、`/api/state`、`/api/receipts`、`/api/audit`、`/api/backup`、`/api/restore`；用户初始化与会话接口；`/api/system/meta`、`users`、`roles`、`departments`、`audit`。保持原前端请求合同。

保存使用事务、行锁与 revision 乐观校验；金额使用 BigDecimal；保护已收款订单金额和历史订单，核销检查预收余额，客户改名联动收款记录。

## 构建与运行

从项目根目录：`docker compose up -d --build`。Docker 多阶段构建负责前端打包、Maven 单元测试和可执行 JAR 构建，无需宿主机安装 Java。

已有 JDK 21 / Maven 时：`cd backend && mvn test package`，再 `java -jar target/emie-crm-backend-1.0.0.jar`。独立 Maven 构建不会自动生成前端资源，完整打包使用根 Dockerfile。

环境变量：CRM_HOST、CRM_PORT、CRM_DB_HOST、CRM_DB_PORT、CRM_DB_NAME、CRM_DB_USER、CRM_DB_PASSWORD。容器默认监听 0.0.0.0:5174。CRM_REQUIRE_LOGIN=1 启用登录要求，本地编排默认启用登录。CRM_FEISHU_ENABLED=1 时强制鉴权，飞书配置见 docs/FEISHU-SSO.md。CRM_TEST_MODE=1 仅允许以 `_test` 结尾的数据库。

## 回归测试

先创建独立数据库 emiecrm_java_test，并授权应用用户。禁止使用业务库跑测试。

```sh
docker compose -f compose.java-test.yaml --env-file .env up -d --build java-test
docker compose -f compose.java-test.yaml --env-file .env run --rm contract-test
docker compose -f compose.java-test.yaml --env-file .env down
```

测试覆盖权限、登录、审批、收款和核销、超额回滚、客户改名、并发冲突、备份恢复、用户禁用/重置密码、角色与部门。测试入口同时校验数据库后缀和服务 testMode，避免误清理生产数据。

飞书审批对接仍需企业应用凭据与审批定义，本次迁移不包含尚未实现的外部审批连接。
