# CRM 飞书 SSO

参考 emieproduct 的官方 Java SDK 登录流程，使用独立飞书应用，不复用产品系统的 App ID、Secret 或用户绑定。

## 本地配置

在未纳入版本管理的 `.env` 中配置：

```dotenv
CRM_REQUIRE_LOGIN=1
CRM_FEISHU_ENABLED=1
CRM_FEISHU_APP_ID=填写CRM应用的AppID
CRM_FEISHU_APP_SECRET=填写CRM应用的AppSecret
CRM_FEISHU_REDIRECT_URI=http://127.0.0.1:8088/api/auth/feishu/callback
```

在飞书开放平台创建企业自建应用，启用网页应用，配置可用人员范围和登录所需用户信息权限，并发布版本。安全设置中的重定向 URL 必须与 `CRM_FEISHU_REDIRECT_URI` 完全一致，包括协议、端口及路径。本地测试地址和正式域名回调可分别加入允许列表。

正式环境若使用 9002 入口，回调为 `https://crm.emie.cn:9002/api/auth/feishu/callback`；若改用标准 443 则为 `https://crm.emie.cn/api/auth/feishu/callback`。必须以实际可访问的 HTTPS 入口为准，不能混用。

配置后执行 `docker compose up -d --build crm`。系统仅提供飞书登录，账号密码登录和公开初始化接口默认关闭。已有管理员应先由运维核对并绑定飞书身份，再切换正式环境。首次飞书登录创建“待授权”账号；管理员在系统设置 → 用户管理中给该账号分配销售、财务或其他角色。不得通过姓名或邮箱自动关联管理员。账号与飞书身份按 App ID + Open ID 唯一绑定，飞书 User ID 仅作辅助身份字段。停用账号立即阻止后续请求。

登录会话不按时间自动过期；手动退出、管理员撤销登录（包括重置密码）或账号停用后失效。会话保存在服务内存中，服务重启后需要重新登录，浏览器关闭标签页后 sessionStorage 中的令牌也会清除。OAuth state 5分钟内单次有效；回调不在 URL 传递访问令牌，通过绑定同一浏览器会话的一次性票据（60秒）兑换。Secret 只在后端环境中使用，SDK 请求调试日志关闭。

## 上线

当前服务器已迁移至 1Panel MySQL，生产根目录编排文件与本地 deploy 旧模板不同。部署时只更新应用镜像和新增认证环境变量，保留服务器现有数据库连接、1panel-network 及备份任务，不能用旧模板整体覆盖。生产发布另行授权。

切换前确认至少有一个启用且已绑定飞书身份的管理员。不得按姓名或邮箱自动授予管理员权限。启用飞书登录会同时强制所有业务接口鉴权，不能通过 `CRM_REQUIRE_LOGIN=0` 绕过。

## 验证

Docker 镜像构建运行 Java 单元测试，覆盖 state、浏览器绑定票据、重复兑换、停用账号与首次授权。HTTP 回归使用独立 `_test` 数据库；不能向业务库注入测试身份。真实飞书跳转和回调必须在配置独立应用凭据及允许列表后，由有应用使用权限的用户完成。

## SDK 长连接

飞书后台“事件与回调 → 使用长连接接收回调”的连接校验需要运行中的 SDK WebSocket 客户端，和 SSO 登录回调是两个独立通道。将 `CRM_FEISHU_LONG_CONNECTION=1` 写入 `.env` 后重建应用。连接由后台任务建立，断线自动重连，不阻塞 CRM HTTP 启动。通过 `/api/auth/feishu/config` 的 `longConnection` 字段可查看 `connected` / `reconnecting` / `retrying` 等状态。连接成功后可在飞书后台点击“重新验证”。

目前未订阅或实现卡片交互、消息接收等业务处理器；建立连接不会自动增加飞书权限，也不会发送消息或处理审批。新增业务订阅应先实现并测试对应处理器。SSO 重定向仍在飞书“安全设置”单独配置，不能以长连接代替。独立回归测试环境默认关闭长连接，避免与真实应用竞争事件。

## 仅飞书登录

登录页不再提供账号密码或公开初始化入口。`/api/login`、`/api/setup` 默认返回403。正式编排固定 `CRM_PASSWORD_LOGIN_ENABLED=0`；独立回归测试环境可设为1以运行原账户与权限合约测试，此测试开关不影响网页仅展示飞书入口。已有用户与密码摘要保留，禁用密码登录不会删除账号或业务数据。切换前需要核对飞书管理员身份绑定。

## 首次登录与超时排查

- 登录按钮使用配置回调 URL 的 origin 发起 `/api/auth/feishu/start`。从 `localhost`、IP 或其他入口打开页面时，先在回调域名建立浏览器会话，避免 state 对应的 Cookie 留在另一个域名。
- 前端对一次页面初始化只兑换一次票据，取得新令牌后才读取当前用户。普通打开页面发现旧令牌已过期时正常回到登录页；迟到的旧请求 401 不会清除新令牌。无效 SSO 票据不会回退到旧账号。
- 错误或过时的 state / ticket 只拒绝该请求，不销毁当前仍有效的凭据；正确 state 和 ticket 仍为单次使用，原有效期和浏览器绑定校验保持不变。
- 服务日志使用 `Feishu login rejected/failed at` 和 `Feishu ticket exchange rejected` 标记阶段：`browser-session` / `browser-session-missing`（没有原浏览器会话）、`state-expired`（超过五分钟）、`state-mismatch`、`exchange-code`（飞书授权或用户信息接口）、`resolve-user`（本地用户绑定）、`ticket-expired-or-consumed`、`ticket-mismatch`。日志不包含授权码、state 值、票据或访问令牌。排查时按失败时间查询这些标记，不应仅凭“第二次成功”推断具体原因。
