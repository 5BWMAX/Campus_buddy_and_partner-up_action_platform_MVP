#!/usr/bin/env bash
set -euo pipefail
umask 077
mode=${1:?production, cutover or rollback required}
backup=$(cat /root/campus-buddy-formal-backup-path)
[[ "$backup" == /root/campus-buddy-backups/before-formal-* ]]
test -s "$backup/nginx.conf"
ready() {
  for attempt in $(seq 1 60); do
    if curl --silent --fail --cacert /etc/campus-buddy/tls.crt "https://localhost:$1/actuator/health/readiness" >/dev/null; then return; fi
    sleep 2
  done
  echo 'Readiness timed out' >&2; return 1
}
case "$mode" in
production)
  test ! -e /root/campus-buddy-admin-initial.txt
  grep -qx 'DB_URL=jdbc:postgresql://127.0.0.1:5432/campus_buddy_release_check' /etc/campus-buddy/formal.env
  systemctl stop campus-buddy-formal
  install -m 600 /etc/campus-buddy/formal.env "$backup/release-check.env"
  sed -i -e 's|^DB_URL=.*|DB_URL=jdbc:postgresql://127.0.0.1:5432/campus_buddy_formal|' -e 's/^SCHEDULER_ENABLED=.*/SCHEDULER_ENABLED=true/' /etc/campus-buddy/formal.env
  chown root:campus-buddy /etc/campus-buddy/formal.env
  chmod 640 /etc/campus-buddy/formal.env
  systemctl start campus-buddy-formal
  ready 8444
  set -a
  . /etc/campus-buddy/formal.env
  set +a
  java -Dloader.main=edu.campusbuddy.identity.application.AdminBootstrap -cp /opt/campus-buddy/formal.jar org.springframework.boot.loader.launch.PropertiesLauncher platform_admin /root/campus-buddy-admin-initial.txt
  test "$(stat -c %a /root/campus-buddy-admin-initial.txt)" = 600
  echo 'Fresh production database and initial administrator ready; public routing unchanged.'
  ;;
rollback)
  # Protect the demo before sending any public traffic back to it. Keep formal data intact.
  systemctl start campus-buddy
  ready 8443
  sed 's/^[[:space:]]*auth_basic .*/    auth_basic "Campus Buddy internal demo";/' "$backup/nginx.conf" > "$backup/rollback-nginx.conf"
  install -m 600 /etc/nginx/sites-available/campus-buddy "$backup/before-rollback-nginx.conf"
  install -m 644 "$backup/rollback-nginx.conf" /etc/nginx/sites-available/campus-buddy
  if ! nginx -t || ! systemctl reload nginx; then
    install -m 644 "$backup/before-rollback-nginx.conf" /etc/nginx/sites-available/campus-buddy
    nginx -t && systemctl reload nginx
    exit 1
  fi
  systemctl enable campus-buddy
  systemctl disable --now campus-buddy-formal
  echo 'Demo restored behind access password; formal database retained.'
  ;;
cutover)
  grep -qx 'DB_URL=jdbc:postgresql://127.0.0.1:5432/campus_buddy_formal' /etc/campus-buddy/formal.env
  systemctl start campus-buddy-formal
  ready 8444
  curl --silent --fail --cacert /etc/campus-buddy/tls.crt https://localhost:8444/api/v1/auth/session | python3 -c 'import sys,json; assert json.load(sys.stdin)["data"]["demo"] is False'
  install -m 600 /etc/nginx/sites-available/campus-buddy "$backup/before-cutover-nginx.conf"
  sed -e 's/^[[:space:]]*auth_basic .*/    auth_basic off;/' \
      -e 's|proxy_pass https://127.0.0.1:8443;|proxy_pass https://127.0.0.1:8444;|' \
      -e 's|proxy_set_header X-Forwarded-For .*;|proxy_set_header X-Forwarded-For $remote_addr;|' \
      "$backup/nginx.conf" > "$backup/formal-nginx.conf"
  grep -q 'proxy_pass https://127.0.0.1:8444;' "$backup/formal-nginx.conf"
  grep -q 'proxy_set_header X-Forwarded-For \$remote_addr;' "$backup/formal-nginx.conf"
  install -m 644 "$backup/formal-nginx.conf" /etc/nginx/sites-available/campus-buddy
  if ! nginx -t || ! systemctl reload nginx; then
    install -m 644 "$backup/before-cutover-nginx.conf" /etc/nginx/sites-available/campus-buddy
    nginx -t && systemctl reload nginx
    exit 1
  fi
  sleep 2
  if ! curl --silent --fail --cacert /etc/campus-buddy/tls.crt https://localhost/api/v1/auth/session | python3 -c 'import sys,json; assert json.load(sys.stdin)["data"]["demo"] is False'; then
    install -m 644 "$backup/before-cutover-nginx.conf" /etc/nginx/sites-available/campus-buddy
    nginx -t && systemctl reload nginx
    exit 1
  fi
  systemctl enable campus-buddy-formal
  systemctl disable --now campus-buddy
  echo 'Formal site public; demo stopped, retained for protected rollback.'
  ;;
*) echo 'Unknown mode' >&2; exit 1 ;;
esac
