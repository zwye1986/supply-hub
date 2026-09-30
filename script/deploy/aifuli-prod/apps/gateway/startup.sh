#!/usr/bin/env bash

set -euo pipefail

# 生产环境应用根目录，可在执行前通过 APP_ROOT 覆盖。
APP_ROOT="${APP_ROOT:-/data/aifuli-prod}"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_KEY="$(basename "${APP_DIR}")"
SPRING_PROFILE="${SPRING_PROFILE:-prod}"
ACTION="${1:-status}"

case "${SERVICE_KEY}" in
  gateway)
    APP_NAME="gateway-server"
    JAR_NAME="aifuli-gateway.jar"
    DEFAULT_JAVA_OPTS="-Xms256m -Xmx512m"
    ;;
  system)
    APP_NAME="system-server"
    JAR_NAME="aifuli-module-system-server.jar"
    DEFAULT_JAVA_OPTS="-Xms512m -Xmx512m"
    ;;
  infra)
    APP_NAME="infra-server"
    JAR_NAME="aifuli-module-infra-server.jar"
    DEFAULT_JAVA_OPTS="-Xms512m -Xmx512m"
    ;;
  member)
    APP_NAME="member-server"
    JAR_NAME="aifuli-module-member-server.jar"
    DEFAULT_JAVA_OPTS="-Xms512m -Xmx512m"
    ;;
  pay)
    APP_NAME="pay-server"
    JAR_NAME="aifuli-module-pay-server.jar"
    DEFAULT_JAVA_OPTS="-Xms512m -Xmx512m"
    ;;
  mall)
    APP_NAME="mall-server"
    JAR_NAME="aifuli-module-mall-server.jar"
    DEFAULT_JAVA_OPTS="-Xms1024m -Xmx1024m"
    ;;
  statistics)
    APP_NAME="statistics-server"
    JAR_NAME="aifuli-module-statistics-server.jar"
    DEFAULT_JAVA_OPTS="-Xms512m -Xmx512m"
    ;;
  *)
    echo "未知服务目录: ${SERVICE_KEY}"
    exit 1
    ;;
esac

LOG_DIR="${APP_ROOT}/logs/${APP_NAME}"
PID_FILE="${LOG_DIR}/${APP_NAME}.pid"
OUT_FILE="${LOG_DIR}/${APP_NAME}.out"
APP_LOG_FILE="${LOG_DIR}/${APP_NAME}.log"
JAVA_OPTS="${JAVA_OPTS:-${DEFAULT_JAVA_OPTS}}"

if [[ -f "${APP_DIR}/current/${JAR_NAME}" ]]; then
  JAR_FILE="${APP_DIR}/current/${JAR_NAME}"
else
  JAR_FILE="${APP_DIR}/${JAR_NAME}"
fi

get_pid() {
  if [[ -f "${PID_FILE}" ]]; then
    local pid
    pid="$(cat "${PID_FILE}")"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      echo "${pid}"
      return 0
    fi
  fi
  pgrep -f "${JAR_FILE}" | head -n 1 || true
}

start() {
  mkdir -p "${LOG_DIR}" "${APP_ROOT}/tmp/${APP_NAME}"

  if [[ ! -f "${JAR_FILE}" ]]; then
    echo "未找到 Jar 文件: ${JAR_FILE}"
    echo "请将 ${JAR_NAME} 上传到 ${APP_DIR}/ 或 ${APP_DIR}/current/"
    exit 1
  fi

  local pid
  pid="$(get_pid)"
  if [[ -n "${pid}" ]]; then
    echo "${APP_NAME} 已在运行，PID=${pid}"
    exit 0
  fi

  echo "启动 ${APP_NAME}，Jar=${JAR_FILE}"
  nohup java ${JAVA_OPTS} \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Shanghai \
    -Djava.io.tmpdir="${APP_ROOT}/tmp/${APP_NAME}" \
    -jar "${JAR_FILE}" \
    --spring.profiles.active="${SPRING_PROFILE}" \
    > "${OUT_FILE}" 2>&1 &

  echo $! > "${PID_FILE}"
  sleep 3

  pid="$(get_pid)"
  if [[ -n "${pid}" ]]; then
    echo "${APP_NAME} 启动命令已提交，PID=${pid}"
    echo "控制台日志: ${OUT_FILE}"
    echo "业务日志: ${APP_LOG_FILE}"
  else
    echo "${APP_NAME} 启动失败，请查看: ${OUT_FILE}"
    exit 1
  fi
}

stop() {
  local pid
  pid="$(get_pid)"
  if [[ -z "${pid}" ]]; then
    echo "${APP_NAME} 未运行"
    rm -f "${PID_FILE}"
    return 0
  fi

  echo "停止 ${APP_NAME}，PID=${pid}"
  kill "${pid}"

  for _ in {1..30}; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      rm -f "${PID_FILE}"
      echo "${APP_NAME} 已停止"
      return 0
    fi
    sleep 1
  done

  echo "${APP_NAME} 未在 30 秒内退出，执行强制停止"
  kill -9 "${pid}" >/dev/null 2>&1 || true
  rm -f "${PID_FILE}"
}

status() {
  local pid
  pid="$(get_pid)"
  if [[ -n "${pid}" ]]; then
    echo "${APP_NAME} 运行中，PID=${pid}"
  else
    echo "${APP_NAME} 未运行"
  fi
}

case "${ACTION}" in
  start)
    start
    ;;
  stop)
    stop
    ;;
  restart)
    stop
    start
    ;;
  status)
    status
    ;;
  logs)
    tail -f "${OUT_FILE}"
    ;;
  app-logs)
    tail -f "${APP_LOG_FILE}"
    ;;
  *)
    echo "用法: $0 {start|stop|restart|status|logs|app-logs}"
    exit 1
    ;;
esac
