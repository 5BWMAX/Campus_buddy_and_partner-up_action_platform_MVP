# 正式账号认证：后端阶段

本阶段提供注册、登录、改密、会话检查和管理员初始化能力。注册登录界面及正式生产切换尚未完成；**不要仅切换 profile 后就移除现有 Nginx 入口密码**。

## 模式与参与资格

- 默认 `demo` 模式保留原演示账号与校内认证规则，供原测试和内部演示使用。
- 显式启用 `SPRING_PROFILES_ACTIVE=formal` 后，演示账号列表和演示登录接口返回 404，禁止种子数据初始化。
- 同时启用 `formal,demo` 会使启动失败，避免配置失误开放演示管理员入口。
- 正式模式下有凭据的正常注册用户可发布、报名、审核自己活动的申请及聊天。注册不设置 `verified=true`，不声称用户经过校内认证。
- 用户名 3–32 位 ASCII 字母、数字或下划线，忽略大小写；昵称独立，最长 40 字。密码 15–128 个 Unicode 字符，保留空格，不在日志/响应中输出。
- 普通注册固定 `USER`，额外提交 `role`、`verified`、`demo`、`status` 等字段返回 400。首位注册者也不是管理员。
- 正式模式只提供站内通知。未配置真实外部推送通道时不创建/执行外部投递任务；原演示投递测试保留。未来接入真实通道后才可显式启用 `campus.external-push-enabled`。

## HTTP 接口

所有接口前缀为 `/api/v1`，沿用 `{data,requestId}` 成功响应和 `{code,message,requestId}` 错误响应。写请求必须是 JSON，并提供 `/auth/session` 返回的 `X-CSRF-Token`；浏览器保持同源 cookie。

| 接口 | 请求 | 行为 |
| --- | --- | --- |
| `GET /auth/session` | 无 | 返回 user、csrf、demo；正式模式 demo=false |
| `POST /auth/register` | username、password、nickname | 201，原子创建账号/凭据/隐私设置并登录 |
| `POST /auth/login` | username、password | 200，验证密码后登录；不存在的账号和错误密码统一 401 |
| `POST /auth/password` | currentPassword、newPassword | 验证当前密码，禁止与旧密码相同；成功后撤销所有会话并返回 loggedOut=true |
| `POST /auth/logout` | 空对象 | 注销当前会话 |
| `DELETE /me/account` | confirm=true | 沿用业务注销，删除凭据并撤销所有会话，历史匿名保留 |

注册/登录成功会替换整个旧会话及其 CSRF token；客户端必须重新请求 `/auth/session` 后再写入业务数据。改密后需要重新登录。

正式会话使用独立 `CAMPUS_SESSION` cookie，生产配置沿用 Secure、HttpOnly、SameSite=Lax。密码不会存入 cookie 或浏览器 localStorage。凭据表记录 session_version，每次 API 请求校验；因此改密与并发登录/会话保存竞态不会恢复旧版本权限。

正式 profile 的 user 数据额外包含 username、passwordChangeRequired。管理员临时密码未更换时只允许会话查询、改密、退出；其他 API 返回 `PASSWORD_CHANGE_REQUIRED`。受限账号仍可登录处理申诉，但不能发布活动等普通业务。

## 凭据与限流

V5 迁移新增独立 `account_credential` 表，避免通用用户资料查询获取密码哈希。密码使用 Spring Security PBKDF2-HMAC-SHA256，16 字节随机盐、600000 次迭代、256 位派生密钥，带编码版本标识；不引入新密码算法依赖。参数应在目标机器上测量，不能用本机耗时推断云端容量。

认证限流独立于普通业务限流，计数保存在数据库、拒绝请求也提交计数，重新获取 cookie 或重启进程不会重置窗口：

- 同一 IP：注册 10 次/15 分钟；登录或改密各 60 次/15 分钟。
- 同一用户名的登录、同一用户的改密：各 20 次/15 分钟。
- 返回 429 `AUTH_RATE_LIMITED`；窗口过期自动清理。当前策略限制所有尝试，包括成功尝试；共享出口用户可能共同触发 IP 限额。

默认忽略客户端转发头。只有后端保持仅监听回环地址，且本机 Nginx 覆盖 `X-Forwarded-For` 为 `$remote_addr` 时，才设置 `CAMPUS_AUTH_TRUST_LOOPBACK_PROXY=true`。该开关仅信任回环代理给出的单个字面 IP，不信任代理链或任意主机名。正式 profile 禁用自动 forwarded-header 重写，避免绕过可信来源检查。

## 独立测试数据库

原回归库继续使用 `campus_buddy_mvp2_test`；新增正式测试只接受本机 5432 的 `campus_buddy_auth_test`，测试会清空其测试账号及会话，禁止指向业务库。

`scripts/setup.ps1` 会为新环境创建该库，并在已有设置缺少时补充 `TEST_AUTH_DB_URL`，不覆盖原有密码。手动配置时由数据库管理员创建：

```bash
createdb -h localhost -p 5432 -U postgres -O YOUR_TEST_DB_ROLE campus_buddy_auth_test
```

为现有测试环境额外设置：

```powershell
$env:TEST_AUTH_DB_URL='jdbc:postgresql://127.0.0.1:5432/campus_buddy_auth_test'
# 沿用 TEST_DB_USER / TEST_DB_PASSWORD。数据库角色须能访问两个测试库。
./mvnw.cmd -B '-Dspring.test.context.cache.maxSize=2' test
```

测试涵盖并发重复注册回滚、加盐哈希、普通用户越权、CSRF/会话轮换、全会话改密撤销、晚到旧版本会话、注销、身份切换接口关闭、限流、离线管理员初始化及首次改密，以及真实 HTTP 发布—报名—审核—聊天。原演示测试继续使用原独立库。

## 管理员初始化（供后续正式部署使用）

正式部署使用独立新库、独立密钥，不迁入演示账号和会话；原演示库保留。先完成 V5 迁移，再使用 Linux 上的离线入口初始化管理员。该入口不会启动 Web 服务，不开放 HTTP 初始化接口，也不将首个注册用户提升为管理员。

以 root 在服务器本机执行，环境变量 DB_URL/DB_USER/DB_PASSWORD 从受保护的正式配置文件加载，不要在命令行中明文填写密码：

```bash
set -a
. /etc/campus-buddy/app.env
set +a
java -Dloader.main=edu.campusbuddy.identity.application.AdminBootstrap \
  -cp /opt/campus-buddy/current.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  YOUR_ADMIN_USERNAME /root/campus-buddy-admin-initial.txt
unset DB_PASSWORD APP_CRYPTO_KEY APP_CURSOR_KEY TLS_PASSWORD
```

输出文件必须不存在，目录必须受保护；工具仅支持 POSIX 文件系统，以 0600 创建文件，生成随机临时密码，拒绝覆盖已有管理员。首次正式登录须改密。若操作异常，检查数据库是否已提交及私密输出文件，不要盲目重建或覆盖；工具不会自动删除可能已对应已提交账号的凭据文件。

本阶段测试覆盖管理员数据库事务及首次改密 HTTP 行为；Linux 文件权限与完整部署命令仍需后续部署阶段验收。不得提交管理员文件、真实环境变量、密码哈希或测试原始日志。

## 第一阶段验证结果

- 完整后端测试 107 项通过，0 失败、0 错误、0 跳过，其中新增正式认证与配置测试 14 项。
- JAR 打包通过；已验证通过 PropertiesLauncher 加载管理员入口，缺少参数时安全失败且不输出凭据。Windows PowerShell 调用 `-Dloader.main=...` 时需要将整个参数加引号；真正初始化仍须在 POSIX 环境执行。
- 测试数据库与正在运行的演示库隔离，测试完成后专用 PostgreSQL 实例已停止。
- 尚未实现注册登录前端，未切换线上服务或移除网站入口保护；Linux 管理员文件权限、云端哈希性能和完整公网流程留到后续阶段验证。

## 第二阶段：正式账号界面

- 正式模式提供注册、登录、个人资料改密；访客仍可浏览活动，演示模式保留原身份选择以兼容演示测试。
- 注册用户显示“注册用户”，不标记为已认证校内身份。首次登录须改密的管理员只显示改密表单，不请求活动、通知或资料接口。
- 登录和注册后重新获取会话及 CSRF；改密后重新登录，其他设备的失效会话在刷新页面时恢复为访客。身份变化会清空此前的收藏缓存和活动、小组视图。
- 账号输入只保存在组件内存中，不写浏览器持久存储；离开表单即卸载。密码支持粘贴，注册和改密按 Unicode 字符计数并检查两次输入一致。
- 前端类型检查、生产构建和 JAR 打包通过；完整后端再次验证 107 项通过。
- `scripts/formal-ui-regression.mjs` 使用真实本地正式服务验证注册、登录、改密、多设备撤销、两用户发布—报名—审核—聊天以及移动布局；管理员首次改密的界面采用会话夹具，真实管理员权限由后端测试覆盖。

浏览器脚本会创建测试账号、活动和聊天，仅接受本机地址。请先启动连接独立测试库的正式模式服务，再设置环境变量：

```powershell
$env:FORMAL_TEST_URL='https://localhost:8446'
# 可选：PLAYWRIGHT_MODULE 指向已有 Playwright 模块，BROWSER_CHANNEL 默认为 msedge。
node scripts/formal-ui-regression.mjs
$env:BASE_URL=$env:FORMAL_TEST_URL
node scripts/ui-regression.mjs
```

只向独立测试服务运行；报告和截图存于被 Git 忽略的 `.local`。本阶段尚未执行云端正式库切换或 Linux 管理员初始化。

## 后续上线顺序

完成界面与浏览器测试 → 备份旧库/配置 → 建正式库与独立密钥 → 在受保护入口验证正式模式与管理员 → 移除 Nginx 入口密码 → 公网验收。继续保留 HTTPS；无域名、自签名证书警告不会因登录改造消失。

回滚必须先恢复入口密码保护，再切回原演示配置与 JAR，保留正式库新增数据。新密码账号不能直接回退为无密码演示身份。不要在本阶段改动正在运行的服务。
