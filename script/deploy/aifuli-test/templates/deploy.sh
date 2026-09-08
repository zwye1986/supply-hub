#!/usr/bin/env bash

set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 或具有 systemctl 权限的账号执行"
  exit 1
fi

if [[ $# -gt 2 ]]; then
  echo "用法: $0 [待发布Jar路径] [版本号]"
  exit 1
fi

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_KEY="$(basename "${APP_DIR}")"
UNIT="aifuli-test-${SERVICE_KEY}"

case "${SERVICE_KEY}" in
  gateway) JAR_NAME="aifuli-gateway.jar" ;;
  system) JAR_NAME="aifuli-module-system-server.jar" ;;
  infra) JAR_NAME="aifuli-module-infra-server.jar" ;;
  member) JAR_NAME="aifuli-module-member-server.jar" ;;
  *)
    echo "未知服务目录: ${SERVICE_KEY}"
    exit 1
    ;;
esac

INCOMING_DIR="${APP_DIR}/upload"
if [[ $# -eq 0 ]]; then
  SOURCE_JAR="${INCOMING_DIR}/${JAR_NAME}"
  VERSION="$(date +%Y%m%d_%H%M%S)"
elif [[ $# -eq 1 ]]; then
  SOURCE_JAR="$1"
  VERSION="$(date +%Y%m%d_%H%M%S)"
else
  SOURCE_JAR="$1"
  VERSION="$2"
fi

if [[ ! -f "${SOURCE_JAR}" ]]; then
  echo "未找到待发布 Jar: ${SOURCE_JAR}"
  echo "默认发布方式：将 ${JAR_NAME} 上传到 ${INCOMING_DIR}/ 后执行 $0"
  exit 1
fi

if [[ ! "${VERSION}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "版本号只能包含字母、数字、点、下划线和连字符"
  exit 1
fi

RELEASE_DIR="${APP_DIR}/releases/${VERSION}"
CURRENT_LINK="${APP_DIR}/current"
if [[ -e "${RELEASE_DIR}" ]]; then
  echo "发布目录已存在: ${RELEASE_DIR}"
  exit 1
fi

PREVIOUS_TARGET=""
if [[ -L "${CURRENT_LINK}" ]]; then
  PREVIOUS_TARGET="$(readlink -f "${CURRENT_LINK}")"
fi

install -d -o aifuli -g aifuli -m 750 "${RELEASE_DIR}"
install -o aifuli -g aifuli -m 640 "${SOURCE_JAR}" "${RELEASE_DIR}/${JAR_NAME}"
ln -sfn "${RELEASE_DIR}" "${APP_DIR}/current.next"
mv -Tf "${APP_DIR}/current.next" "${CURRENT_LINK}"

systemctl daemon-reload
systemctl enable "${UNIT}"

rollback() {
  echo "新版本启动失败，开始回滚"
  if [[ -n "${PREVIOUS_TARGET}" && -d "${PREVIOUS_TARGET}" ]]; then
    ln -sfn "${PREVIOUS_TARGET}" "${APP_DIR}/current.next"
    mv -Tf "${APP_DIR}/current.next" "${CURRENT_LINK}"
    systemctl restart "${UNIT}" || true
  fi
}

if ! systemctl restart "${UNIT}"; then
  rollback
  systemctl status "${UNIT}" --no-pager || true
  exit 1
fi

sleep 10
if ! systemctl is-active --quiet "${UNIT}"; then
  rollback
  systemctl status "${UNIT}" --no-pager || true
  exit 1
fi

echo "发布成功: ${UNIT} -> ${RELEASE_DIR}"
systemctl status "${UNIT}" --no-pager
