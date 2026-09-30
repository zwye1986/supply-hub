#!/usr/bin/env bash
# ==============================================================================
# 电影服务 (movie-server) 一键本地编译打包并部署到测试环境 (pro143 / movie-test)
# ==============================================================================
set -euo pipefail

REMOTE_HOST="${1:-movie-test}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${REPO_ROOT}"

echo "=========================================================="
echo "1. 本地 Maven 编译打包 aifuli-module-movie-server ..."
echo "=========================================================="
mvn clean package -DskipTests -pl aifuli-module-movie-server -am

JAR_PATH="${REPO_ROOT}/aifuli-module-movie-server/target/aifuli-module-movie-server.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "[ERROR] 找不到构建产物: ${JAR_PATH}"
  exit 1
fi

echo ""
echo "=========================================================="
echo "2. 备份远程当前运行的 Jar 包并上传新版本 ..."
echo "=========================================================="
TIMESTAMP="$(date +%Y%m%d%H%M%S)"
ssh "${REMOTE_HOST}" "mkdir -p /data/supply-movie/backup; if [ -f /data/supply-movie/app/movie-server.jar ]; then cp -f /data/supply-movie/app/movie-server.jar /data/supply-movie/backup/movie-server-${TIMESTAMP}.jar; fi"
scp "${JAR_PATH}" "${REMOTE_HOST}:/data/supply-movie/app/movie-server.jar"

echo ""
echo "=========================================================="
echo "3. 重启远程 movie-server 服务并验证状态 ..."
echo "=========================================================="
ssh "${REMOTE_HOST}" "/data/supply-movie/bin/restart.sh"
sleep 5
ssh "${REMOTE_HOST}" "/data/supply-movie/bin/status.sh"

echo ""
echo "部署成功！日志查看命令: ssh ${REMOTE_HOST} /data/supply-movie/bin/logs.sh"
