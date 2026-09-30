#!/usr/bin/env bash
#
# 将 supplyhubtest.qihaofu.cn 站点装到 pro197（源码 Nginx vhost）。
# 设计见 docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md#9-pro197-nginx
#
set -euo pipefail

REMOTE_HOST="${SUPPLYHUB_NGINX_HOST:-root@47.101.155.197}"
CONF_NAME="supplyhubtest.qihaofu.cn.conf"
LOCAL_CONF="$(cd "$(dirname "$0")" && pwd)/nginx/${CONF_NAME}"
REMOTE_CONF="/usr/local/nginx/conf/vhost/${CONF_NAME}"
NGINX_BIN="/usr/local/nginx/sbin/nginx"
NGINX_MAIN="/usr/local/nginx/conf/nginx.conf"

if [[ ! -f "${LOCAL_CONF}" ]]; then
  echo "missing ${LOCAL_CONF}" >&2
  exit 1
fi

scp "${LOCAL_CONF}" "${REMOTE_HOST}:${REMOTE_CONF}.next"
ssh "${REMOTE_HOST}" bash -s <<REMOTE
set -euo pipefail
if [[ -f "${REMOTE_CONF}" ]]; then
  cp -a "${REMOTE_CONF}" "${REMOTE_CONF}.bak"
fi
install -m 644 "${REMOTE_CONF}.next" "${REMOTE_CONF}"
rm -f "${REMOTE_CONF}.next"
if ! "${NGINX_BIN}" -t -c "${NGINX_MAIN}"; then
  if [[ -f "${REMOTE_CONF}.bak" ]]; then
    mv "${REMOTE_CONF}.bak" "${REMOTE_CONF}"
  else
    rm -f "${REMOTE_CONF}"
  fi
  echo "nginx -t failed; restored previous vhost" >&2
  exit 1
fi
"${NGINX_BIN}" -s reload
rm -f "${REMOTE_CONF}.bak"
echo "nginx reloaded: ${REMOTE_CONF}"
REMOTE
