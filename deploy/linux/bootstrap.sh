#!/usr/bin/env bash
set -euo pipefail
umask 077
server_ip=${1:?server IPv4 required}
release=${2:?release required}
[[ "$server_ip" =~ ^[0-9.]+$ && "$release" =~ ^[a-f0-9]+$ ]]
test ! -e /etc/campus-buddy/app.env
test ! -e /opt/campus-buddy/current.jar
test ! -e /etc/nginx/sites-available/campus-buddy
test ! -e /etc/nginx/sites-enabled/campus-buddy
test -s /root/campus-buddy.jar
id campus-buddy >/dev/null 2>&1 || useradd --system --home-dir /var/lib/campus-buddy --shell /usr/sbin/nologin campus-buddy
install -d -m 750 -o root -g campus-buddy /etc/campus-buddy
install -d -m 750 -o campus-buddy -g campus-buddy /var/lib/campus-buddy
install -d -m 755 /opt/campus-buddy/releases
install -m 644 /root/campus-buddy.jar "/opt/campus-buddy/releases/$release.jar"
ln -s "releases/$release.jar" /opt/campus-buddy/current.jar
db_password=$(openssl rand -hex 32)
tls_password=$(openssl rand -hex 32)
gate_password=$(openssl rand -hex 16)
crypto_key=$(openssl rand -base64 32)
cursor_key=$(openssl rand -base64 32)
runuser -u postgres -- psql -v ON_ERROR_STOP=1 <<SQL
CREATE ROLE campus_buddy LOGIN PASSWORD '$db_password';
CREATE DATABASE campus_buddy OWNER campus_buddy;
REVOKE ALL ON DATABASE campus_buddy FROM PUBLIC;
SQL
cat > /etc/campus-buddy/app.env <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:5432/campus_buddy
DB_USER=campus_buddy
DB_PASSWORD=$db_password
APP_CRYPTO_KEY=$crypto_key
APP_CURSOR_KEY=$cursor_key
TLS_ENABLED=true
TLS_KEYSTORE=file:/etc/campus-buddy/backend.p12
TLS_PASSWORD=$tls_password
PORT=8443
SPRING_PROFILES_ACTIVE=demo
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=8
ENV
openssl req -x509 -newkey rsa:3072 -nodes -days 365 -sha256 \
  -keyout /etc/campus-buddy/tls.key -out /etc/campus-buddy/tls.crt \
  -subj '/CN=campus-buddy-demo' \
  -addext "subjectAltName=IP:$server_ip,IP:127.0.0.1,DNS:localhost" 2>/dev/null
printf '%s' "$tls_password" | openssl pkcs12 -export -in /etc/campus-buddy/tls.crt \
  -inkey /etc/campus-buddy/tls.key -name campus-buddy \
  -out /etc/campus-buddy/backend.p12 -passout stdin
chown root:campus-buddy /etc/campus-buddy/app.env /etc/campus-buddy/backend.p12
chmod 640 /etc/campus-buddy/app.env /etc/campus-buddy/backend.p12
chmod 644 /etc/campus-buddy/tls.crt
printf '%s\n' "$gate_password" | htpasswd -iBc /etc/nginx/campus-buddy.htpasswd demo
chown root:www-data /etc/nginx/campus-buddy.htpasswd
chmod 640 /etc/nginx/campus-buddy.htpasswd
cat > /root/campus-buddy-access.txt <<ACCESS
URL=https://$server_ip
USERNAME=demo
PASSWORD=$gate_password
ACCESS
cat > /etc/systemd/system/campus-buddy.service <<'SERVICE'
[Unit]
Description=Campus Buddy demo
After=network.target postgresql.service
Wants=postgresql.service

[Service]
User=campus-buddy
Group=campus-buddy
WorkingDirectory=/var/lib/campus-buddy
EnvironmentFile=/etc/campus-buddy/app.env
ExecStart=/usr/bin/java -Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -jar /opt/campus-buddy/current.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=45
SuccessExitStatus=143
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/campus-buddy
UMask=0077

[Install]
WantedBy=multi-user.target
SERVICE
cat > /etc/nginx/sites-available/campus-buddy <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    return 301 https://$host$request_uri;
}
server {
    listen 443 ssl default_server;
    server_name _;
    ssl_certificate /etc/campus-buddy/tls.crt;
    ssl_certificate_key /etc/campus-buddy/tls.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    server_tokens off;
    client_max_body_size 6m;
    auth_basic "Campus Buddy internal demo";
    auth_basic_user_file /etc/nginx/campus-buddy.htpasswd;
    location / {
        proxy_pass https://127.0.0.1:8443;
        proxy_ssl_verify on;
        proxy_ssl_trusted_certificate /etc/campus-buddy/tls.crt;
        proxy_ssl_server_name on;
        proxy_ssl_name localhost;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Authorization "";
        proxy_connect_timeout 5s;
        proxy_read_timeout 60s;
    }
}
NGINX
chmod 644 /etc/nginx/sites-available/campus-buddy /etc/systemd/system/campus-buddy.service
if [ -L /etc/nginx/sites-enabled/default ]; then unlink /etc/nginx/sites-enabled/default; fi
ln -s /etc/nginx/sites-available/campus-buddy /etc/nginx/sites-enabled/campus-buddy
nginx -t
systemctl daemon-reload
systemctl enable --now campus-buddy nginx postgresql
systemctl reload nginx
for attempt in $(seq 1 60); do
    if curl --silent --fail --cacert /etc/campus-buddy/tls.crt https://localhost:8443/actuator/health/readiness; then
        echo; echo READY; exit 0
    fi
    sleep 2
done
journalctl -u campus-buddy -n 40 --no-pager
exit 1
