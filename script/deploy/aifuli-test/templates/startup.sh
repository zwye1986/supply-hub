#!/usr/bin/env bash

set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 或具有 systemctl 权限的账号执行"
  exit 1
fi

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_KEY="$(basename "${APP_DIR}")"
UNIT="aifuli-test-${SERVICE_KEY}"
ACTION="${1:-status}"

case "${SERVICE_KEY}" in
  gateway) APPLICATION_NAME="gateway-server" ;;
  system) APPLICATION_NAME="system-server" ;;
  infra) APPLICATION_NAME="infra-server" ;;
  member) APPLICATION_NAME="member-server" ;;
  *)
    echo "未知服务目录: ${SERVICE_KEY}"
    exit 1
    ;;
esac

case "${ACTION}" in
  start|stop|restart|status)
    exec systemctl "${ACTION}" "${UNIT}"
    ;;
  logs)
    exec journalctl -u "${UNIT}" -f --no-pager
    ;;
  app-logs)
    exec tail -f "/data/aifuli-test/logs/${APPLICATION_NAME}/${APPLICATION_NAME}.log"
    ;;
  *)
    echo "用法: $0 {start|stop|restart|status|logs|app-logs}"
    exit 1
    ;;
esac
