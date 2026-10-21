# Ubuntu 云服务器部署（内部演示）

本文面向全新的 Ubuntu 22.04 amd64 主机，使用 Java 21、PostgreSQL 18、Nginx 和 systemd。建议至少 2 核、2 GB 内存和 1 GB swap；这只是小规模演示配置，未经容量测试。

应用启用 `demo` profile，允许选择演示用户及管理员身份。**必须保留 Nginx 独立访问密码，不能直接将应用端口开放到公网。** 入口密码不是应用的正式用户认证系统。正式运营前需要接入真实身份认证。

## 1. 部署前检查

```bash
cat /etc/os-release
free -m
df -h /
ss -lntp
systemctl is-active nginx apache2 postgresql
ufw status
```

确认 80、443、5432、8443 没有与现有服务冲突。若已有宝塔面板、网站或数据库，先评估其管理方式；不要覆盖其配置。本文使用系统包和 systemd，后续也应通过它们管理这些服务，不要在面板里重复安装 Nginx 或 PostgreSQL。

云安全组允许 SSH 管理端口和 TCP 80/443，**不要开放 5432/8443**。如已启用 UFW，仅按需增加 80/443 规则，保留现有 SSH 管理规则，避免锁死远程连接。没有域名时，本方案使用 IP 自签名证书，浏览器会提示不受信任；通过独立渠道核对证书指纹后再继续。

## 2. 安装运行环境

以下命令以 root 执行。软件源会提供当时可用的安全补丁，部署时记录实际版本。

```bash
apt-get update
apt-get install -y ca-certificates curl gnupg openjdk-21-jre-headless nginx apache2-utils
install -d /usr/share/postgresql-common/pgdg
curl --fail --show-error --location \
  https://www.postgresql.org/media/keys/ACCC4CF8.asc \
  -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc
echo 'deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt jammy-pgdg main' \
  > /etc/apt/sources.list.d/pgdg.list
apt-get update
apt-get install -y postgresql-18
java -version
psql --version
nginx -v
```

PostgreSQL 软件源配置依据：[官方 Ubuntu 安装文档](https://www.postgresql.org/download/linux/ubuntu/)。

## 3. 上传并初始化

先在已验证的源码提交上完成前端构建、后端测试和 JAR 打包。仅上传 JAR 和 [bootstrap.sh](../deploy/linux/bootstrap.sh)，不要上传开发机的 `.local`、数据库副本、密钥、日志或整个工作目录。

在本地终端中把占位符替换为自己的地址；不要将替换后的命令保存到公共仓库：

```bash
scp server/target/campus-buddy-server-2.0.0.jar root@SERVER_IP:/root/campus-buddy.jar
scp deploy/linux/bootstrap.sh root@SERVER_IP:/root/campus-buddy-provision.sh
```

在服务器上核对 SHA-256 与本地一致，再执行：

```bash
sha256sum /root/campus-buddy.jar
bash -n /root/campus-buddy-provision.sh
bash /root/campus-buddy-provision.sh SERVER_IPV4 FULL_GIT_COMMIT
```

`SERVER_IPV4` 为服务器 IPv4；`FULL_GIT_COMMIT` 为构建对应的十六进制 Git 提交号。脚本仅用于首次安装：检测到已有应用配置、发布链接或 Nginx 站点时停止，不覆盖已有安装。失败时可能保留已建立的数据库或目录，应检查失败阶段后修复，不要盲目重复运行。

脚本会创建专用数据库及非 root 应用用户，独立生成数据库密码、AES 加密密钥、游标签名密钥和入口密码。数据库迁移由 Flyway 执行，首次启动自动生成演示数据，不导入本地测试数据。

| 内容 | 服务器路径 |
| --- | --- |
| 版本化 JAR / 当前链接 | `/opt/campus-buddy/releases/` / `/opt/campus-buddy/current.jar` |
| 私密环境变量 | `/etc/campus-buddy/app.env` |
| HTTPS 证书和后端 PKCS12 | `/etc/campus-buddy/` |
| 入口账号密码（root 可读） | `/root/campus-buddy-access.txt` |
| Nginx 入口密码哈希 | `/etc/nginx/campus-buddy.htpasswd` |
| 应用服务 | `/etc/systemd/system/campus-buddy.service` |
| Nginx 站点 | `/etc/nginx/sites-available/campus-buddy` |
| 应用工作目录 | `/var/lib/campus-buddy` |

应用 Java 堆上限 512 MB、Metaspace 上限 192 MB、数据库连接池上限 8；Java 总内存还包含堆外开销。应用仅监听 `127.0.0.1:8443`。Nginx 到后端继续使用 HTTPS 并校验证书。PostgreSQL 使用本机连接，数据库端未配置 TLS。应通过 `ss -lntp` 验证 PostgreSQL 也只监听回环地址。

## 4. 验收

```bash
systemctl is-active campus-buddy nginx postgresql
systemctl is-enabled campus-buddy nginx postgresql
nginx -t
curl --fail --cacert /etc/campus-buddy/tls.crt \
  https://localhost:8443/actuator/health/readiness
ss -lntp
journalctl -u campus-buddy -n 50 --no-pager
openssl x509 -in /etc/campus-buddy/tls.crt -noout -fingerprint -sha256 -dates
```

从外网验证：HTTP 跳转 HTTPS；未认证的首页和 API 返回 401；错误入口密码仍返回 401；正确入口密码可以访问。浏览器检查身份切换、活动列表、发布/报名等必要流程和手机宽度页面。用 `systemctl restart campus-buddy` 验证应用重启后健康状态恢复和数据库记录保留；开机启动状态检查不等于真实服务器重启测试。

自签名证书有效期 365 天。之后有域名时，替换前端证书为受信任证书并安排自动续期；后端证书更换时也须同步 PKCS12、密码及 Nginx 信任证书。不要通过关闭 `proxy_ssl_verify` 来绕过错误。

## 5. 备份与更新

数据库里的部分字段依赖 `APP_CRYPTO_KEY`。**数据库备份必须同时保留对应的加密密钥；重新生成密钥会导致旧数据无法解密。** 以下备份在服务器本地，不代替异地备份：

```bash
umask 077
backup_dir="/root/campus-buddy-backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
runuser -u postgres -- pg_dump -Fc campus_buddy > "$backup_dir/database.dump"
tar -czf "$backup_dir/config.tar.gz" \
  /etc/campus-buddy /etc/nginx/sites-available/campus-buddy \
  /etc/nginx/campus-buddy.htpasswd /etc/systemd/system/campus-buddy.service
readlink -f /opt/campus-buddy/current.jar > "$backup_dir/release.txt"
pg_restore --list "$backup_dir/database.dump" > "$backup_dir/database-toc.txt"
```

定期制作并验证备份，另行配置加密异地存储及保留策略。备份包含秘密，不得提交到 Git。实际恢复演练应先恢复到隔离数据库验证，避免覆盖正在使用的数据。

恢复演练示例（`campus_buddy_restore_check` 必须为新建、未被应用使用的库）：

```bash
runuser -u postgres -- createdb campus_buddy_restore_check
runuser -u postgres -- pg_restore --exit-on-error \
  -d campus_buddy_restore_check < "$backup_dir/database.dump"
runuser -u postgres -- psql -d campus_buddy_restore_check -c \
  'select version, success from flyway_schema_history order by installed_rank;'
```

还应核对关键表记录，并在隔离应用实例中使用对应密钥验证加密字段。仅验证备份目录或 `pg_restore --list` 不能替代真实恢复。

更新前确认测试通过、完成备份并检查 Flyway 迁移的回退兼容性。上传新 JAR，校验散列后保存为新的提交号文件；保留旧 JAR，通过临时符号链接和 `mv -Tf` 原子替换 `current.jar`，随后重启服务并检查 readiness。不要重跑初始化脚本。

## 6. 回滚与停止

- 仅应用版本故障且数据库结构兼容：把 `current.jar` 切回保留的旧 JAR，重启并验证。
- 数据库迁移不兼容：进入维护窗口、停止应用，先将部署前备份恢复到隔离数据库并验证；切换到该库及对应旧 JAR/密钥后启动。恢复会丢失备份之后的写入，应事先确认数据取舍，不能只回退 JAR。
- 首次部署没有旧版本：`systemctl stop campus-buddy`，禁用本项目 Nginx 站点并通过 `nginx -t` 后 reload；保留数据库、配置和 JAR 供排查。不要删除既有面板或其他站点。

入口密码可用 `htpasswd /etc/nginx/campus-buddy.htpasswd demo` 交互更新，同时更新私密密码记录。交付后移除本次临时 SSH 公钥，删除本地对应临时私钥；保留已验证的日常管理登录方式。SSH 密码如果曾出现在聊天或其他记录中，应通过 `passwd` 更换。

## 7. 提交 PR 前的隐私检查

提交范围仅限通用脚本和文档。检查 `git diff --cached`，不得包含真实 IP、域名、用户名密码组合、私钥、环境变量实值、备份、用户数据或本机绝对路径。验证记录描述结果即可，原始日志和截图保存在被忽略的本地目录。

## 本次部署验证记录

- Ubuntu 22.04 amd64、OpenJDK 21.0.12.1、PostgreSQL 18.6、Ubuntu Nginx 1.18.0。
- JAR 上传前后 SHA-256 一致，Flyway V1–V4 迁移成功，readiness 返回 `UP`。
- 应用、Nginx 和 PostgreSQL 运行且启用开机启动；实际执行应用服务重启后再次健康。未重启整台服务器。
- 经 SSH 隧道访问真实 Nginx 入口：6 项 UI 回归、18 项浏览器业务检查通过，页面异常和 HTTP 5xx 均为 0。烟测活动已正常取消，保留业务审计记录。
- 入口无密码或错误密码返回 401；正确密码可访问首页与健康检查，并通过指定证书的 TLS 校验。服务器本机 HTTP 返回 301。
- 已完成数据库和配置备份，数据库备份真实恢复到隔离库，验证 4 条成功迁移及活动记录；未做隔离应用的加密字段解密演练。
- 公网 80/443 直连验收需要云安全组放行，隧道验证不能证明公网入口可达。未进行容量测试、异地自动备份或公开生产认证验收。
