# 正式账号版本部署与回滚

本流程将已按 [云部署文档](cloud-deployment.md) 安装的演示站升级为公开注册站点。沿用 Java 21、PostgreSQL、Nginx、systemd 和现有 HTTPS 证书，不重装服务器。执行者须拥有目标服务器管理权限。

正式站允许访客浏览、注册后参与；正式 profile 禁止演示身份接口和种子数据。管理员由离线命令创建，首次登录必须修改临时密码。不包含短信、邮箱找回、外部推送和高并发容量保障。无域名时可以沿用 IP 自签 HTTPS，但浏览器仍提示证书不受信任。

## 准备与验收库

先完成前端构建、后端测试和 JAR 打包，记录源码提交与 JAR SHA256。只上传 JAR 以及本目录引用的通用脚本；不上传开发数据库、`.local`、凭据、日志或截图。密码通过 SSH 交互输入或使用自己管理的既有 SSH 认证，不写入命令参数或脚本。

将 JAR 上传为 `/root/campus-buddy-formal.jar`，并上传：

- [prepare-formal.sh](../deploy/linux/prepare-formal.sh)
- [promote-formal.sh](../deploy/linux/promote-formal.sh)
- [verify-formal-admin.py](../deploy/linux/verify-formal-admin.py)

在服务器执行：

```bash
bash -n /root/prepare-formal.sh
bash -n /root/promote-formal.sh
bash /root/prepare-formal.sh YOUR_COMMIT YOUR_JAR_SHA256
```

准备脚本会校验 JAR、备份原库及配置，建立独立角色 `campus_buddy_formal`、正式库 `campus_buddy_formal` 和专用验收库 `campus_buddy_release_check`。新服务 `campus-buddy-formal` 起初只连接验收库，监听回环 8444，公网原服务不变。

新配置 `/etc/campus-buddy/formal.env` 权限为 `640 root:campus-buddy`，使用独立应用加密密钥和游标密钥。只复用已安装 TLS 证书。正式运行目录 `/var/lib/campus-buddy-formal` 与原演示目录分离。数据库及应用端口均不要加入公网安全组。

脚本拒绝覆盖已存在的正式配置。若中途失败，应先检查服务、数据库和私密备份状态再人工恢复；不要直接删除已有配置重跑，也不要对正在使用的正式库重复初始化。

通过只绑定本机的 SSH 隧道访问验收服务，例如 `127.0.0.1:9444 → 服务器127.0.0.1:8444`。确认仍连接验收库后，执行 [正式浏览器脚本](../scripts/formal-ui-regression.mjs) 及 [界面回归](../scripts/ui-regression.mjs)。前者会创建测试账号、活动和聊天，禁止在切换为正式库后继续运行。

还应将演示备份恢复到独立临时库，确认迁移记录和活动可读；验证完成后只删除该临时库，不动原演示库。

## 验证离线管理员

在验收库初始化测试管理员并验证改密：

```bash
(
  set -a
  . /etc/campus-buddy/formal.env
  set +a
  java -Dloader.main=edu.campusbuddy.identity.application.AdminBootstrap \
    -cp /opt/campus-buddy/formal.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    release_admin /root/campus-buddy-release-check-admin.txt
  python3 /root/verify-formal-admin.py \
    /root/campus-buddy-release-check-admin.txt --change-test-password
)
```

管理员输出文件只能由 root 读取（0600），不得提交或打印。验证脚本校验首次改密门槛、改密后的管理访问和退出；`--change-test-password` 只允许用于验收库。

## 切换空白正式库与公网

```bash
bash /root/promote-formal.sh production
python3 /root/verify-formal-admin.py /root/campus-buddy-admin-initial.txt
```

此步骤将新服务改接全新正式库，启用调度器，运行 V1–V5 迁移并离线创建 `platform_admin`。正式库不迁入验收或演示数据。第二条命令只验证登录、首次改密限制和退出，保留临时密码供站点所有者首次使用。

管理员凭据通过受保护文件私密交付。首次登录后由所有者修改密码，不在工单、PR 或截图中分享密码。

切换前可执行下节回滚命令验证演示入口恢复保护，再执行：

```bash
bash /root/promote-formal.sh cutover
```

切换脚本先确认正式服务健康和 `session.demo=false`，再将 Nginx 转发至回环 8444，取消入口 Basic Auth。`X-Forwarded-For` 必须覆盖为 `$remote_addr`，配合 `CAMPUS_AUTH_TRUST_LOOPBACK_PROXY=true`；不能追加客户端提供的转发链。Nginx 仍校验后端 TLS。新服务启用开机启动，旧演示服务停止并禁用自动启动。

切换后的公网验收：

- HTTP 跳转 HTTPS，匿名首页、活动接口和健康状态正常。
- 会话 `demo=false`；演示账号列表返回404；匿名访问管理接口返回401。
- 手机注册表单可用，管理员初次登录强制改密、管理接口返回403，退出后恢复访客。
- 正式库仅初始管理员、无演示账号或测试活动；原演示库仍保留。
- 创建正式库和配置基线备份，确认 `campus-buddy-formal` 与 Nginx 服务及开机启动状态。

完整注册—报名—审核—聊天在云端验收库验证；公网只做不污染正式业务数据的检查。验收完成后关闭 SSH 隧道，并在确认正式配置已指向正式库后删除本次专用验收库。不要清理原演示库或正式库。

## 回滚

```bash
bash /root/promote-formal.sh rollback
```

回滚启动原演示服务，先恢复入口 Basic Auth，再将 Nginx 恢复到原配置，最后停止正式服务。正式库、正式 JAR、密钥和管理员凭据全部保留。再次执行 `cutover` 可恢复正式服务，不需要重新创建账号。

若正式版已产生业务数据，回滚意味着这些数据暂时无法在演示站访问，绝不应把正式库覆盖为演示备份。回滚后先安排恢复正式服务，不能丢弃新增数据。

基线备份保存在服务器 root 专用目录。它不能代替异地备份和持续监控；本次未配置异地备份，也未重启整台服务器或执行压力测试。

## 本轮验证记录

- 部署应用源码：`6f85cd240731f9904a7dc79e07175e69947d753b`。
- JAR SHA256：`04a8e681d060c66198f03ea7ff31fb7095570eb080a2acfc63ab7dfb77a935b7`。
- 本地后端107项、前端类型检查/构建和打包通过；云验收库正式浏览器10项及原界面回归6项通过。
- Linux管理员0600文件、首次改密和改密后的权限验证通过。单次管理员登录约0.8–1秒，只代表功能验收，不代表吞吐量测试。
- 原库备份恢复成功；回滚命令恢复演示保护，匿名访问401；再切换正式版后公网匿名访问200、身份及管理员限制验证通过。
- 正式服务经历停止/重新启动后管理员数据仍在，开机启动已启用；正式库5条迁移、1个初始管理员、0个活动、0个演示账号。未迁入测试数据。

验证记录不包含真实主机、私密配置或凭据。
