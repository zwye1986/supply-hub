#!/usr/bin/env bash

set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 执行此脚本"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="/data/aifuli-test/apps"
SERVICES=(gateway system infra member)

for service in "${SERVICES[@]}"; do
  app_dir="${APP_ROOT}/${service}"
  install -d -o aifuli -g aifuli -m 750 "${app_dir}/releases"
  install -d -o aifuli -g aifuli -m 750 "${app_dir}/upload"
  install -m 750 "${SCRIPT_DIR}/templates/startup.sh" "${app_dir}/startup.sh"
  install -m 750 "${SCRIPT_DIR}/templates/deploy.sh" "${app_dir}/deploy.sh"
  install -m 644 "${SCRIPT_DIR}/systemd/aifuli-test-${service}.service" \
    "/etc/systemd/system/aifuli-test-${service}.service"
done

for application_name in gateway-server system-server infra-server member-server; do
  install -d -o aifuli -g aifuli -m 750 "/data/aifuli-test/logs/${application_name}"
done

systemctl daemon-reload
echo "systemd 服务文件已安装。发布 Jar 后通过对应 deploy.sh 启动服务。"
