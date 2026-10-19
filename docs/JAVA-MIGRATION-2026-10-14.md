# Java 业务迁移记录 — 2026-10-14

## 交付范围

全部现有后端接口和业务规则从 Node.js 迁入 Java 21 / Spring Boot 3.5.16。包含订单、审批状态、客户、产品、收款、预收款核销、并发保存、备份恢复，以及用户、角色权限、部门和日志管理。React 前端保留，静态文件打入 Java JAR；生产运行容器使用 JRE，不运行 Node 服务。

标准 Maven 目录位于 backend，Controller / Service / Repository / Security 分层。统一错误响应、30MB 请求限制、数据库事务、金额 BigDecimal、revision 冲突409均已实现。当前继续免登录。

## 数据兼容

保留 MySQL 8.4 数据库和原表，schema.sql 幂等建表，不重建业务库。业务仍以 JSON 快照存储；本次未进行实体表规范化。旧 scrypt 密码摘要兼容，已有账户无需重置密码。

切换前基线：18订单、23客户、9产品、0用户、3角色、0部门。生产 state、receipts、system/users、system/roles、system/departments、system/audit 六组响应在临时 Java 实例、正式切换后及重启后均与切换前完全一致。

## 验证记录

- Maven 打包及密码摘要兼容单元测试通过。
- 独立 MySQL 测试库 HTTP 合约测试通过：登录与权限、审批、收款、核销、超额回滚、改名联动、并发冲突、备份恢复、用户禁用与密码重置、角色与部门。
- 生产状态接口 backend=java、database=mysql、testMode=false。
- React 首页及 JS/CSS 资源均返回200；浏览器检查订单、产品和用户管理页面加载正常。
- 正式 Java 容器重启后数据完全一致。
- systemd 手动触发每日备份成功（Result=success、ExecMainStatus=0），SQL gzip 完整性检查通过。
- 本地 OrbStack 主服务也已切换 Java；测试库单独隔离。

## 部署与备份

生产地址 http://192.168.200.124:8088/ ，目录 /home/emie/emiecrm。

镜像 emiecrm:java-production-amd64，容器 emiecrm；数据库容器 emiecrm-mysql，原卷 emiecrm_mysql-data 保留。先启动临时容器连接原数据库进行只读核对，再替换正式应用容器；数据库容器无需替换。

切换前已执行 SQL 备份，旧应用镜像保留为 emiecrm:node-rollback-20261014。旧编排位于服务器 backups/compose.node-20261014.yaml。旧 Node 源码已归档本地 backups/java-migration-20261014/node-source 并同步服务器迁移备份目录。

回滚：先重新备份当前数据库，停止和删除应用容器，使用旧编排并把 crm image 指向 node-rollback-20261014，以同一 Compose 项目名 emiecrm 启动。数据库格式兼容，勿导入旧快照覆盖新增业务数据。

## 后续边界

飞书审批外部连接尚未实现，需要企业应用凭据和审批定义后对接；本次交付不声称包含飞书审批调用。登录能力已在后端迁移并测试，按现有需求不启用。

日常操作与发布命令见 deploy/README.md，开发与测试见 backend/README.md。敏感凭据仅在 .env，不记录在文档或源码包。
