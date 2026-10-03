#!/usr/bin/env bash
#
# 供应链中台 — 构建并部署到 pro143 的一键脚本
#
# 用法:
#   ./deploy-pro143.sh [commit-hash]
#
# 不传 commit-hash 时，使用当前代码库最新代码（含未提交的修改），
# 镜像标签自动取当前 HEAD 短 hash。
# 传 commit-hash 时，先 git checkout 到该 commit 再构建。
#
# 前置条件:
#   - 本机已安装 mvn、docker、docker buildx、ssh、scp
#   - SSH 免密登录 root@8.133.164.143 已配置
#   - Maven 依赖已安装
#
set -euo pipefail

if [[ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]]; then
  if [[ -z "${JAVA_HOME:-}" || "${JAVA_HOME}" != *"21"* ]]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

# ─── 配置 ────────────────────────────────────────────────────────────────────
REMOTE_HOST="${SUPPLYHUB_DOCKER_HOST:-pro143}"
REMOTE_DIR="/data/aifuli-test/supply-chain"
IMAGE_NAME="aifuli/supply-chain-server"
MONOREPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERVER_MODULE="aifuli-module-supply-chain/aifuli-module-supply-chain-server"
DOCKERFILE_DIR="${MONOREPO_ROOT}/${SERVER_MODULE}"
HEALTH_WAIT_SECONDS=180
SSH_OPTS=(-o ServerAliveInterval=15 -o ServerAliveCountMax=60)
# pro143 默认 SSH 加密算法传输极慢（约 12KB/s）；aes128-gcm 可达约 7.5MB/s。
SCP_OPTS=(-c aes128-gcm@openssh.com -o Compression=no)
# ─────────────────────────────────────────────────────────────────────────────

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[deploy]${NC} $*"; }
warn() { echo -e "${YELLOW}[warn]${NC} $*"; }
err()  { echo -e "${RED}[error]${NC} $*" >&2; }

# 获取 commit hash 或使用当前代码库最新代码
cd "${MONOREPO_ROOT}"
if [[ $# -ge 1 ]]; then
    COMMIT_HASH="$1"
    log "指定 commit: ${COMMIT_HASH}，正在 checkout..."
    git checkout "${COMMIT_HASH}"
else
    COMMIT_HASH="$(git rev-parse --short HEAD)"
    UNCOMMITTED=$(git status --short | wc -l | tr -d ' ')
    if [[ ${UNCOMMITTED} -gt 0 ]]; then
        warn "检测到 ${UNCOMMITTED} 个未提交的变更，将一并打入镜像"
    fi
fi
IMAGE_TAG="${IMAGE_NAME}:${COMMIT_HASH}-amd64"
TARBALL_NAME="supply-chain-server-${COMMIT_HASH}-amd64.tar.gz"
LOCAL_TARBALL="/tmp/${TARBALL_NAME}"
REMOTE_TARBALL="${REMOTE_DIR}/${TARBALL_NAME}"

log "commit hash : ${COMMIT_HASH}"
log "image tag   : ${IMAGE_TAG}"
log "monorepo    : ${MONOREPO_ROOT}"
log "remote host : ${REMOTE_HOST}"
echo ""

# ─── Step 1: Maven 构建 JAR ──────────────────────────────────────────────────
log "Step 1/6: Maven 构建 JAR (skip tests)"
mvn -pl "${SERVER_MODULE}" -am clean package -DskipTests -q
JAR_PATH="${DOCKERFILE_DIR}/target/aifuli-module-supply-chain-server.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
    err "JAR not found: ${JAR_PATH}"
    exit 1
fi
JAR_SIZE=$(du -h "${JAR_PATH}" | cut -f1)
log "JAR built: ${JAR_PATH} (${JAR_SIZE})"
echo ""

# ─── Step 2: Docker 构建 amd64 镜像 ─────────────────────────────────────────
log "Step 2/6: Docker 构建 amd64 镜像"
cd "${DOCKERFILE_DIR}"
docker buildx build --platform linux/amd64 --load -t "${IMAGE_TAG}" .
log "镜像构建完成: ${IMAGE_TAG}"
echo ""

# ─── Step 3: 打包并传输镜像到 pro143 ─────────────────────────────────────────
log "Step 3/6: 打包并传输镜像到 pro143"
log "正在打包镜像..."
docker save "${IMAGE_TAG}" | gzip > "${LOCAL_TARBALL}"
LOCAL_SIZE=$(du -h "${LOCAL_TARBALL}" | cut -f1)
log "本地镜像包: ${LOCAL_TARBALL} (${LOCAL_SIZE})"

log "正在传输到 pro143..."
scp "${SCP_OPTS[@]}" "${SSH_OPTS[@]}" "${LOCAL_TARBALL}" "${REMOTE_HOST}:${REMOTE_TARBALL}"
log "传输完成"
echo ""

# ─── Step 4: 远程加载镜像并更新 .env ────────────────────────────────────────
log "Step 4/6: 远程加载镜像并更新 .env"
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" bash -s <<REMOTE_SCRIPT
set -euo pipefail
cd "${REMOTE_DIR}"

echo "[remote] 加载 Docker 镜像..."
docker load < "${REMOTE_TARBALL}"
echo "[remote] 镜像加载完成"

echo "[remote] 备份 .env"
cp .env ".env.bak-$(date +%Y%m%d%H%M%S)"

echo "[remote] 更新 .env 中的镜像标签、时间戳容差与 Nacos 命名空间"
sed -i 's|^SUPPLY_CHAIN_IMAGE=.*|SUPPLY_CHAIN_IMAGE='"${IMAGE_TAG}"'|' .env
sed -i 's|^AIFULI_SUPPLY_CHAIN_INSTORE_RTMART_INBOUND_TIMESTAMP_TOLERANCE_SECONDS=.*|AIFULI_SUPPLY_CHAIN_INSTORE_RTMART_INBOUND_TIMESTAMP_TOLERANCE_SECONDS=180|' .env
sed -i 's|^SPRING_CLOUD_NACOS_CONFIG_NAMESPACE=.*|SPRING_CLOUD_NACOS_CONFIG_NAMESPACE=aifuli-supplyhub-test|' .env
sed -i 's|^SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE=.*|SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE=aifuli-supplyhub-test|' .env
echo "[remote] 检查直充限频身份密钥"
if ! grep -q '^AIFULI_SUPPLY_CHAIN_RECHARGE_FREQUENCY_IDENTITY_KEY=' .env; then
  KEY=$(python3 -c 'import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())')
  echo "AIFULI_SUPPLY_CHAIN_RECHARGE_FREQUENCY_IDENTITY_KEY=${KEY}" >> .env
  echo "[remote] 自动生成并写入 AIFULI_SUPPLY_CHAIN_RECHARGE_FREQUENCY_IDENTITY_KEY"
fi

echo "[remote] 当前配置:"
grep -E 'SUPPLY_CHAIN_IMAGE|TIMESTAMP_TOLERANCE|NACOS_.*NAMESPACE|RECHARGE_FREQUENCY' .env

echo "[remote] 清理远程镜像包"
rm -f "${REMOTE_TARBALL}"
REMOTE_SCRIPT
log "远程配置更新完成"
echo ""

# ─── Step 5: 重启容器 ────────────────────────────────────────────────────────
log "Step 5/6: 重启容器"
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "cd ${REMOTE_DIR} && docker compose down && docker compose up -d"
log "容器已重启"
echo ""

# ─── Step 6: 等待健康检查 ───────────────────────────────────────────────────
log "Step 6/6: 等待容器健康 (最长 ${HEALTH_WAIT_SECONDS}s)"
ELAPSED=0
while [[ ${ELAPSED} -lt ${HEALTH_WAIT_SECONDS} ]]; do
    STATUS=$(ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "docker inspect --format='{{.State.Health.Status}}' supply-chain-server-test 2>/dev/null" || echo "not-found")
    if [[ "${STATUS}" == "healthy" ]]; then
        log "容器已健康运行!"
        break
    fi
    printf "."
    sleep 5
    ELAPSED=$((ELAPSED + 5))
done
echo ""

if [[ "${STATUS}" != "healthy" ]]; then
    warn "容器在 ${HEALTH_WAIT_SECONDS}s 内未达到 healthy 状态 (当前: ${STATUS})"
    log "查看最近日志:"
    ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "docker logs --tail 30 supply-chain-server-test 2>&1"
    exit 1
fi

# 打印最终状态
log "最终状态:"
ssh "${SSH_OPTS[@]}" "${REMOTE_HOST}" "docker ps --filter name=supply-chain-server-test --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'"
echo ""

# 清理本地临时文件
rm -f "${LOCAL_TARBALL}"
log "已清理本地临时文件"

log "部署完成! ${IMAGE_TAG} 已在 pro143 上运行"
