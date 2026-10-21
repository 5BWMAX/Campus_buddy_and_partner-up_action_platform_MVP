# 同行 · 校园搭子与搭伙行动平台 MVP 2.0

第 5 周原型交付，2026-10-12；2026-10-19 完成 V3 缺陷修复与补测。完成团队文档中的主要业务闭环，供本机演示、代码评审和后续几周集成。按本轮范围约定，性能、生产运维和真实外部系统接入继续保留为后续验收任务，未宣称已满足全部 NFR。

项目目录：当前仓库根目录（以下命令均在根目录执行）。旧 Node.js + SQLite 项目保留在原目录；2.0 使用独立 PostgreSQL 数据库，没有自动导入旧 SQLite 数据。

## 已有环境直接运行

执行 `start.ps1` 启动服务后，浏览器打开 `https://localhost:8444`。这是原生 Windows Nginx 的 HTTPS 入口；使用本地自签证书，浏览器可能显示证书未受信任，可核对地址确为 localhost 后继续。未把证书加入操作系统信任库。

以下命令在 **PowerShell 7** 中执行，Windows PowerShell 5.1 不适用。`java`、`node`、`npm.cmd` 应在 PATH 中。

```powershell
Set-Location '<项目根目录>'
# 服务未运行时启动；已在运行时会提示并复用，不会重复启动
pwsh -NoProfile -File .\scripts\start.ps1
# 需要重启时先停止，再启动
pwsh -NoProfile -File .\scripts\stop.ps1
pwsh -NoProfile -File .\scripts\start.ps1
```

### 在 IDEA 中直接运行

IDEA 的 Spring Boot 运行配置默认不会加载 `.local/settings.json`，直接运行主类会缺少 `TLS_PASSWORD`、数据库和加密密钥环境变量。首次打开项目或更换机器后，在项目根目录执行一次：

```powershell
pwsh -NoProfile -File .\scripts\make-local-config.ps1
```

它会从 `.local/settings.json` 生成 `.local/application-local.yml`（已被 Git 忽略，包含本机密码）。`application.yml` 会以 optional 方式自动导入该文件，随后在 IDEA 中直接运行 `CampusApplication` 即可。建议把运行配置的 Working directory 设为项目根目录；设为 `server` 时也会尝试 `../.local/application-local.yml`。

如果不使用该文件，则必须在 IDEA 运行配置中自行注入 `DB_URL`、`DB_USER`、`DB_PASSWORD`、`APP_CRYPTO_KEY`、`APP_CURSOR_KEY`、`TLS_PASSWORD`（以及可选的 `TLS_KEYSTORE`）。不要把 `.local/application-local.yml` 或密码提交到 Git。

完整重建、验证和再次启动：

```powershell
pwsh -NoProfile -File .\scripts\stop.ps1
pwsh -NoProfile -File .\scripts\build.ps1
pwsh -NoProfile -File .\scripts\start.ps1
node .\scripts\smoke.mjs
```

`build.ps1` 执行 npm ci、TypeScript 检查、Vite 构建、真实数据库测试，最后跳过重复测试打包 JAR。`-SkipTests` 只供临时开发使用，不能作为验收结果。仅运行测试可使用 `pwsh -NoProfile -File .\scripts\test.ps1`。

后端直连为 `https://localhost:8443`；`start.ps1 -NoProxy` 可用于后端排查。默认模式下应用和 Nginx 仅监听回环地址，需要手机访问时见下节。启动失败查看 `.local/app.log`、`.local/app-error.log` 及 `.local/nginx-1.30.5/logs/error.log`。readiness 地址为 `/actuator/health/readiness`。

## 局域网与手机演示

默认模式只监听回环，手机无法访问。`scripts/start-lan.ps1` 会签发局域网专用证书、生成局域网版 Nginx 配置并启动服务，手机与电脑处于同一网络后即可用浏览器打开。

```powershell
# 首次运行会列出本机候选地址供选择，可直接回车取推荐项
pwsh -NoProfile -File .\scripts\start-lan.ps1
# 也可直接指定手机要访问的地址；-OpenFirewall 需在管理员 shell 中使用
pwsh -NoProfile -File .\scripts\start-lan.ps1 -Address 192.168.1.23
```

对外入口为 `https://<电脑局域网IP>:8444`。链路为手机 → Nginx（监听该局域网地址）→ 后端（仍为 `127.0.0.1:8443`），**应用进程本身不暴露到网络**。局域网模式的生成物 `.local/lan.crt`、`.local/lan.key`、`.local/lan.json`、`.local/nginx-lan.conf` 均在忽略范围内，不进入仓库。

- 该模式不修改 `application.yml`、`deploy/nginx.conf` 与既有脚本；`stop.ps1` 同样可以停止局域网模式。
- 需要放行本机防火墙 8444 入站：用管理员 PowerShell 执行 `New-NetFirewallRule -DisplayName "CampusBuddy LAN 8444" -Direction Inbound -Protocol TCP -LocalPort 8444 -Action Allow -Profile Private -RemoteAddress LocalSubnet`，或以管理员身份运行脚本并加 `-OpenFirewall`。
- 证书绑定所选 IP，有效期 365 天；换网络导致 IP 变化后重新运行脚本即可，会自动重新签发。
- 校园网等受管网络可能启用客户端隔离，此时同一网络下的手机仍连不上；改用手机开热点、电脑连该热点，两端即处于同一私有网络。
- 本机经局域网地址自测通过，不代表手机一定能连：防火墙与客户端隔离都会拦截。
- 该通道使用 demo 身份适配器，能打开该地址的人均可直接选择任一演示身份（含平台管理员）。仅限演示期间开启，结束后立即执行 `stop.ps1`。

恢复到默认回环模式：先 `pwsh -NoProfile -File .\scripts\stop.ps1`，再 `pwsh -NoProfile -File .\scripts\start.ps1`。

验证状态（2026-10-19）：本机经 `https://<局域网IP>:8444/actuator/health/readiness` 自测通过；手机浏览器实机访问由人工确认可用，该结论没有自动化证据文件，也不构成浏览器/机型矩阵验证。桌面与 360px 视口的自动化证据见「验证记录」一节。

## 演示范围

- 选择演示身份，编辑资料与隐私；未认证身份无法发布、申请。
- 发布和筛选活动，申请、审核、拒绝、撤回；容量含发起人，批准时校验剩余名额及时间冲突。
- 临时群、文字消息、置顶；确认、请假、退出、移除、自报签到；退出立即撤销群权限。
- V3：活动收藏与“我的收藏”页、搜索历史、群图片消息（≤5MB）、2 分钟内消息撤回、通知偏好（含待审提醒/群消息）。
- 活动变更、取消、结束和归档，持久化提醒与通知；结束成员快照及 24 小时反馈窗口。
- 举报、受理、处置、申诉、复核、审计；24 小时受理逾期记录及管理员通知。
- 管理员查看聚合统计并导出 CSV。

默认 `demo` profile 使用虚拟身份和模拟外部推送。站内通知、业务数据、会话、投递任务和审计是真实入库；没有接通学校认证、微信或短信。停用 demo profile 后仍需实现正式适配器才可完成真实登录和推送。

右上角可选择林同学、陈同学、周同学、许同学、待认证同学或平台管理员。演示步骤见 [演示手册](docs/demo.md)。示例活动的时间以首次初始化为准，重启不会重置时间、反馈或数据库；之后演示可正常新建活动。

## 验证记录

本次完成 **48 项测试，0 失败、0 错误、0 跳过**；包含 46 项使用真实 PostgreSQL 的集成测试及 2 项规则/架构测试。最终 Edge 浏览器烟测 **18 项通过**，页面异常和 HTTP 5xx 均为 0。新增 `V3FeaturesTest` 覆盖图片 multipart 上传、搜索历史 200 契约、撤回内容清除、解散/撤销小组、通知偏好、注销清理与密文字段防篡改；浏览器烟测新增 5 个页面检查：收藏出现、收藏移除、搜索历史清空、图片上传和消息撤回。

本次复跑环境的 PostgreSQL 未启用 SSL，`browser-smoke.json` 记录 `pg_stat_ssl=f`；应用仍支持通过 `DB_URL`/`PGSSLMODE` 启用数据库 TLS，但本轮没有把数据库 TLS 计为已验证。

- [测试结果](docs/evidence/tests.json) 与 `server/target/surefire-reports/TEST-*.xml`
- [浏览器烟测结果](docs/evidence/browser-smoke.json) 与 [桌面截图](docs/evidence/desktop-discovery.png)、[360px 截图](docs/evidence/mobile-360-discovery.png)
- [烟测启动失败防护验证](docs/evidence/smoke-failure-guard.json)：故意指定不存在的 Playwright 模块，进程退出 1，原成功报告被替换为失败；恢复依赖后才生成本次通过记录。

数据库测试只允许使用 `campus_buddy_mvp2_test`，会清空该专用测试库的业务数据。浏览器烟测通过真实页面写入演示库，完成后取消本轮活动，保留申请、消息、举报和审计记录。重跑反馈步骤会如实标记“本轮新提交”或“已有提交核验”。当前脚本选择初始化的已结束读书会，超过该样本24小时反馈窗口后会真实失败；需要通过正常业务流程准备新的结束样本并更新脚本选取，后续将把时间相关演示夹具独立化。完整覆盖范围与未验证指标见 [验收对照](docs/acceptance.md)。

## 工程与运行配置

| 组件 | 当前使用版本 / 位置 |
| --- | --- |
| Vue / TypeScript / Vite | 3.5.22 / 5.9.3 / 7.1.9，`web/` |
| Java / Spring Boot | 编译 release 21，本机 JDK 21.0.12.1 / Boot 3.5.16，`server/` |
| PostgreSQL / Flyway | 18.6 / 11.14.0 |
| Maven | Wrapper 自带 Maven 3.9.11，无需全局 Maven |
| Node / npm | 24.15.0 / 11.12.1 |
| Nginx | 原生 Windows 1.30.5，版本与下载校验值见 `deploy/versions.json` |
| 测试浏览器 | 本机 Microsoft Edge；烟测需设置 `PLAYWRIGHT_MODULE` 指向本机 Playwright 模块目录，或安装 playwright 使 Node 能解析 |

本项目不需要 Docker、Redis 或独立 MQ。Vue 生产构建输出至 `server/src/main/resources/static`，随 JAR 一起交付。运行时复制 JAR 到 `.local/run`，避免 Windows 进程锁住 `server/target`。

数据库采用独立非超级用户：演示库 `campus_buddy_mvp2`、测试库 `campus_buddy_mvp2_test`；`campus_buddy_mvp2_restore` 仅预留，尚未演练恢复。凭据、加密密钥和证书密码在 `.local/settings.json`，已忽略；不要提交该文件或 `.local` 下私钥。保留原密钥才能解密已有私密字段。

应用与 Nginx 之间使用 HTTPS 并校验后端证书；数据库 TLS 由 `.local/settings.json` 中的 `DB_URL`/`PGSSLMODE` 与 PostgreSQL 实例配置共同决定。本次复跑的本机 PostgreSQL 未启用 SSL，因此未把数据库段 TLS 计为通过。原 PostgreSQL TLS 配置保存在 `.local/pg-tls-before.json`，启用与回滚说明见 [本地运行注意事项](docs/architecture.md#本地运行注意事项)。

## 移到另一台机器

当前交付已在本机跑通，尚未执行干净机器的一键安装验收。所需步骤如下，不能把单独运行 setup 当作完整安装：

1. 准备 PowerShell 7、JDK 21 或更高版本、Node 24、PostgreSQL 和 Edge；检查 JAVA_HOME/PATH。执行 `setup.ps1` 时用 `-PgBin "C:\path\to\PostgreSQL\bin"` 指定 PostgreSQL 的 bin 目录，或先设置 `PG_BIN`。
2. 执行 `pwsh -NoProfile -File .\scripts\setup.ps1`，交互输入数据库管理员密码。它创建专用角色/数据库及本地 p12、crt，不安装 Nginx，也不导出 PEM 私钥。如果要在 IDEA 中直接运行主类，再执行 `pwsh -NoProfile -File .\scripts\make-local-config.ps1` 生成本地配置。
3. 从 `deploy/versions.json` 中的固定来源取得 Nginx ZIP，核对 SHA-256 后解压到 `.local`。从本机 p12 导出 `.local/localhost.key`（可用 PowerShell 7 的 X509Certificate2 和 RSA `ExportRSAPrivateKeyPem()`），限制私钥访问。不要公开复制已有机器的密码和密钥。
4. `deploy/nginx.conf` 使用相对 Nginx prefix 的证书路径（`../localhost.crt`、`../localhost.key`），项目目录变化时不需要改绝对路径。执行 `scripts/enable-db-tls.ps1` 为本机 PostgreSQL 配置证书、reload 并验证连接；这会修改该 PostgreSQL 实例的三个 TLS 设置。
5. 执行 build、start；烟测需要安装 Playwright 并将 `PLAYWRIGHT_MODULE` 设为实际模块路径，或让 Node 能解析 `playwright`。

Windows 已有环境不必重复运行 setup。Ubuntu 内部演示部署参见[云服务器部署指南](docs/cloud-deployment.md)，包含首次初始化脚本、入口访问保护、备份与回滚；正式身份认证和受信任证书生命周期仍需另行配置。

## 隐私与提交注意

- `.local/`、`.idea/`、`.vscode/`、`*.p12`、`*.key`、`*.pem`、`*.log`、`*.tsbuildinfo` 和 `application-local.yml` 已加入 `.gitignore`；不要用 `git add -f` 强制加入。
- 仓库中的账号、活动、举报和截图均为虚拟演示数据；真实数据库密码、TLS 密码和加密密钥只在 `.local/settings.json` / `.local/application-local.yml`，不会提交。
- 提交前执行 `git status --short`，确认没有 `.local`、`.idea`、密钥库、私钥或本机绝对路径。
- 如果历史提交曾包含真实凭据，公开前应先轮换凭据，再清理 Git 历史；仅删除当前文件不足以消除历史中的内容。

## 团队交接文档

- [实现架构与关键规则](docs/architecture.md)
- [API 使用说明](docs/api.md)
- [FR / NFR / T-01～T-26 验收对照](docs/acceptance.md)
- [课堂演示手册](docs/demo.md)
- [Ubuntu 云服务器部署与运维](docs/cloud-deployment.md)
- [正式账号认证后端（界面与上线待完成）](docs/formal-auth.md)
- [后续优化与集成任务](docs/next-steps.md)
