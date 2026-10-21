#!/usr/bin/env bash
# Prepare a separate formal service and databases beside an existing demo installation.
set -euo pipefail
umask 077
release=${1:?release commit required}
checksum=${2:?JAR SHA256 required}
[[ "$release" =~ ^[a-f0-9]{7,40}$ && "$checksum" =~ ^[a-f0-9]{64}$ ]]
test -s /root/campus-buddy-formal.jar
test -s /etc/campus-buddy/app.env
test ! -e /etc/campus-buddy/formal.env
test ! -e /etc/systemd/system/campus-buddy-formal.service
printf '%s  %s\n' "$checksum" /root/campus-buddy-formal.jar | sha256sum -c -
backup=/root/campus-buddy-backups/before-formal-$(date -u +%Y%m%dT%H%M%SZ)
install -d -m 700 "$backup"
runuser -u postgres -- pg_dump -Fc campus_buddy > "$backup/demo.dump"
tar -czf "$backup/config.tar.gz" /etc/campus-buddy /etc/nginx/sites-available/campus-buddy /etc/systemd/system/campus-buddy.service 2>/dev/null
readlink -f /opt/campus-buddy/current.jar > "$backup/demo-release.txt"
install -m 600 /etc/nginx/sites-available/campus-buddy "$backup/nginx.conf"
printf '%s\n' "$backup" > /root/campus-buddy-formal-backup-path
install -m 644 /root/campus-buddy-formal.jar "/opt/campus-buddy/releases/$release.jar"
ln -s "releases/$release.jar" /opt/campus-buddy/formal.jar
install -d -m 750 -o campus-buddy -g campus-buddy /var/lib/campus-buddy-formal
password=$(openssl rand -hex 32)
runuser -u postgres -- psql -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE campus_buddy_formal LOGIN PASSWORD '$password';
CREATE DATABASE campus_buddy_formal OWNER campus_buddy_formal;
CREATE DATABASE campus_buddy_release_check OWNER campus_buddy_formal;
REVOKE ALL ON DATABASE campus_buddy_formal FROM PUBLIC;
REVOKE ALL ON DATABASE campus_buddy_release_check FROM PUBLIC;
SQL
# Reuse the installed TLS certificate, but generate separate application secrets.
tls_password=$(sed -n 's/^TLS_PASSWORD=//p' /etc/campus-buddy/app.env)
test -n "$tls_password"
cat > /etc/campus-buddy/formal.env <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:5432/campus_buddy_release_check
DB_USER=campus_buddy_formal
DB_PASSWORD=$password
APP_CRYPTO_KEY=$(openssl rand -base64 32)
APP_CURSOR_KEY=$(openssl rand -base64 32)
TLS_ENABLED=true
TLS_KEYSTORE=file:/etc/campus-buddy/backend.p12
TLS_PASSWORD=$tls_password
PORT=8444
SPRING_PROFILES_ACTIVE=formal
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=8
CAMPUS_AUTH_TRUST_LOOPBACK_PROXY=true
SCHEDULER_ENABLED=false
ENV
chown root:campus-buddy /etc/campus-buddy/formal.env
chmod 640 /etc/campus-buddy/formal.env
cat > /etc/systemd/system/campus-buddy-formal.service <<'SERVICE'
[Unit]
Description=Campus Buddy formal accounts
After=network.target postgresql.service
Wants=postgresql.service

[Service]
User=campus-buddy
Group=campus-buddy
WorkingDirectory=/var/lib/campus-buddy-formal
EnvironmentFile=/etc/campus-buddy/formal.env
ExecStart=/usr/bin/java -Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -jar /opt/campus-buddy/formal.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=45
SuccessExitStatus=143
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/campus-buddy-formal
UMask=0077

[Install]
WantedBy=multi-user.target
SERVICE
chmod 644 /etc/systemd/system/campus-buddy-formal.service
systemctl daemon-reload
systemctl start campus-buddy-formal
for attempt in $(seq 1 60); do
  if curl --silent --fail --cacert /etc/campus-buddy/tls.crt https://localhost:8444/actuator/health/readiness; then
    printf '\nRelease-check service ready on loopback 8444. Public site unchanged.\n'; exit 0
  fi
  sleep 2
done
echo 'Formal service failed readiness; public demo remains unchanged.' >&2
exit 1
