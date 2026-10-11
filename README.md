# 同行 · 校园搭子与搭伙行动平台 MVP 2.0

第 5 周原型交付，2026-10-12。完成团队文档中的主要业务闭环，供本机演示、代码评审和后续几周集成。按本轮范围约定，性能、生产运维和真实外部系统接入继续保留为后续验收任务，未宣称已满足全部 NFR。

项目目录：`D:\Project\Campus_buddy_and_partner-up_action_platform_MVP2.0`。旧 Node.js + SQLite 项目保留在原目录；2.0 使用独立 PostgreSQL 数据库，没有自动导入旧 SQLite 数据。

## 已有环境直接运行

本机交付时服务已启动，浏览器打开 `https://localhost:8444`。这是原生 Windows Nginx 的 HTTPS 入口；使用本地自签证书，浏览器可能显示证书未受信任，可核对地址确为 localhost 后继续。未把证书加入操作系统信任库。

以下命令在 **PowerShell 7** 中执行，Windows PowerShell 5.1 不适用。`java`、`node`、`npm.cmd` 应在 PATH 中。

```powershell
Set-Location 'D:\Project\Campus_buddy_and_partner-up_action_platform_MVP2.0'
# 服务未运行时启动
pwsh -NoProfile -File .\scripts\start.ps1
# 结束演示
pwsh -NoProfile -File .\scripts\stop.ps1
```

完整重建、验证和再次启动：

```powershell
pwsh -NoProfile -File .\scripts\stop.ps1
pwsh -NoProfile -File .\scripts\build.ps1
pwsh -NoProfile -File .\scripts\start.ps1
node .\scripts\smoke.mjs
```

`build.ps1` 执行 npm ci、TypeScript 检查、Vite 构建、真实数据库测试，最后跳过重复测试打包 JAR。`-SkipTests` 只供临时开发使用，不能作为验收结果。仅运行测试可使用 `pwsh -NoProfile -File .\scripts\test.ps1`。

后端直连为 `https://localhost:8443`；`start.ps1 -NoProxy` 可用于后端排查。应用和 Nginx 仅监听回环地址。启动失败查看 `.local/app.log`、`.local/app-error.log` 及 `.local/nginx-1.30.5/logs/error.log`。readiness 地址为 `/actuator/health/readiness`。

## 演示范围

- 选择演示身份，编辑资料与隐私；未认证身份无法发布、申请。
- 发布和筛选活动，申请、审核、拒绝、撤回；容量含发起人，批准时校验剩余名额及时间冲突。
- 临时群、文字消息、置顶；确认、请假、退出、移除、自报签到；退出立即撤销群权限。
- 活动变更、取消、结束和归档，持久化提醒与通知；结束成员快照及 24 小时反馈窗口。
- 举报、受理、处置、申诉、复核、审计；24 小时受理逾期记录及管理员通知。
- 管理员查看聚合统计并导出 CSV。

默认 `demo` profile 使用虚拟身份和模拟外部推送。站内通知、业务数据、会话、投递任务和审计是真实入库；没有接通学校认证、微信或短信。停用 demo profile 后仍需实现正式适配器才可完成真实登录和推送。

右上角可选择林同学、陈同学、周同学、许同学、待认证同学或平台管理员。演示步骤见 [演示手册](docs/demo.md)。示例活动的时间以首次初始化为准，重启不会重置时间、反馈或数据库；之后演示可正常新建活动。

## 验证记录

本次完成 **41 项测试，0 失败、0 错误、0 跳过**；包含 39 项使用真实 PostgreSQL 的集成测试及 2 项规则/架构测试。最终 Edge 浏览器烟测 **13 项通过**，页面异常和 HTTP 5xx 均为 0。

- [测试结果](docs/evidence/tests.json) 与 `server/target/surefire-reports/TEST-*.xml`
- [浏览器烟测结果](docs/evidence/browser-smoke.json) 与 [桌面截图](docs/evidence/desktop-discovery.png)、[360px 截图](docs/evidence/mobile-360-discovery.png)
- [烟测启动失败防护验证](docs/evidence/smoke-failure-guard.json)：故意指定不存在的 Playwright 模块，进程退出 1，原成功报告被替换为失败；恢复依赖后才生成本次通过记录。

数据库测试只允许使用 `campus_buddy_mvp2_test`，会清空该专用测试库的业务数据。浏览器烟测通过真实页面写入演示库，完成后取消本轮活动，保留申请、消息、举报和审计记录。重跑反馈步骤会如实标记“本轮新提交”或“已有提交核验”。当前脚本选择初始化的已结束读书会，超过该样本24小时反馈窗口后会真实失败；需要通过正常业务流程准备新的结束样本并更新脚本选取，后续将把时间相关演示夹具独立化。完整覆盖范围与未验证指标见 [验收对照](docs/acceptance.md)。

## 工程与运行配置

| 组件 | 当前使用版本 / 位置 |
| --- | --- |
| Vue / TypeScript / Vite | 3.5.22 / 5.9.3 / 7.1.9，`web/` |
| Java / Spring Boot | 编译 release 21，本机 JDK 25.0.1 / Boot 3.5.16，`server/` |
| PostgreSQL / Flyway | 18.6 / 11.14.0 |
| Maven | Wrapper 自带 Maven 3.9.11，无需全局 Maven |
| Node / npm | 24.15.0 / 11.12.1 |
| Nginx | 原生 Windows 1.30.5，版本与下载校验值见 `deploy/versions.json` |
| 测试浏览器 | 本机 Microsoft Edge；Playwright 默认使用当前 Codex bundled 安装，也可设置 `PLAYWRIGHT_MODULE` 指向本机模块目录 |

本项目不需要 Docker、Redis 或独立 MQ。Vue 生产构建输出至 `server/src/main/resources/static`，随 JAR 一起交付。运行时复制 JAR 到 `.local/run`，避免 Windows 进程锁住 `server/target`。

数据库采用独立非超级用户：演示库 `campus_buddy_mvp2`、测试库 `campus_buddy_mvp2_test`；`campus_buddy_mvp2_restore` 仅预留，尚未演练恢复。凭据、加密密钥和证书密码在 `.local/settings.json`，已忽略；不要提交该文件或 `.local` 下私钥。保留原密钥才能解密已有私密字段。

本机 PostgreSQL 已启用 TLS，应用连接使用 `verify-full`，Nginx 也校验后端证书。原 PostgreSQL TLS 配置保存在 `.local/pg-tls-before.json`。环境变更及回滚说明见 [本地运行注意事项](docs/architecture.md#本地运行注意事项)。

## 移到另一台机器

当前交付已在本机跑通，尚未执行干净机器的一键安装验收。所需步骤如下，不能把单独运行 setup 当作完整安装：

1. 准备 PowerShell 7、JDK 21 或更高版本、Node 24、PostgreSQL 和 Edge；检查 JAVA_HOME/PATH。`setup.ps1` 默认 PG 路径是 `D:\PostgreSQL18.6\bin`，可用 `-PgBin` 修改。
2. 执行 `pwsh -NoProfile -File .\scripts\setup.ps1`，交互输入数据库管理员密码。它创建专用角色/数据库及本地 p12、crt，不安装 Nginx，也不导出 PEM 私钥。
3. 从 `deploy/versions.json` 中的固定来源取得 Nginx ZIP，核对 SHA-256 后解压到 `.local`。从本机 p12 导出 `.local/localhost.key`（可用 PowerShell 7 的 X509Certificate2 和 RSA `ExportRSAPrivateKeyPem()`），限制私钥访问。不要公开复制已有机器的密码和密钥。
4. 如果项目路径变化，更新 `deploy/nginx.conf` 中的绝对路径。执行 `scripts/enable-db-tls.ps1` 为本机 PostgreSQL 配置证书、reload 并验证连接；这会修改该 PostgreSQL 实例的三个 TLS 设置。
5. 执行 build、start；烟测需要安装 Playwright 并将 `PLAYWRIGHT_MODULE` 设为实际模块路径。本机默认路径只适用于当前环境。

新机初始化自动化、证书生命周期和生产部署归后续集成工作。已有环境不必重复运行 setup。

## 团队交接文档

- [实现架构与关键规则](docs/architecture.md)
- [API 使用说明](docs/api.md)
- [FR / NFR / T-01～T-26 验收对照](docs/acceptance.md)
- [课堂演示手册](docs/demo.md)
- [后续优化与集成任务](docs/next-steps.md)
