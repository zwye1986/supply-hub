#!/usr/bin/env bash
#
# 构建 supplyhub 四服务（gateway/system/infra/member）amd64 镜像并发布到 pro143 Compose。
# 2026-09-07：移除 pay/erp/mall/statistics，supplyhub 栈不需要这些能力。
# 不停止、不操作供应链容器 supply-chain-server-test。
# 设计见 docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md#10-发布流程
#
# 用法: ./deploy-pro143.sh [all|system|gateway|infra|member]
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

REMOTE_HOST="${SUPPLYHUB_DOCKER_HOST:-pro143}"
REMOTE_DIR="/data/aifuli-test/supplyhub"
# pro143 默认 SSH 加密算法传输极慢（约 12KB/s）；aes128-gcm 可达约 7.5MB/s。
SCP_OPTS=(-c aes128-gcm@openssh.com -o Compression=no)
MONOREPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HEALTH_WAIT_SECONDS=900
TARGET_SERVICE="${1:-all}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
log()  { echo -e "${GREEN}[supplyhub]${NC} $*"; }
warn() { echo -e "${YELLOW}[warn]${NC} $*"; }
err()  { echo -e "${RED}[error]${NC} $*" >&2; }

cd "${MONOREPO_ROOT}"
COMMIT_HASH="$(git rev-parse --short HEAD)"
UNCOMMITTED=$(git status --short | wc -l | tr -d ' ')
if [[ ${UNCOMMITTED} -gt 0 ]]; then
  warn "检测到 ${UNCOMMITTED} 个未提交变更，将一并打入镜像"
fi
SHA_TAG="${COMMIT_HASH}-amd64"

# module_dir|image_name|env_key|compose_service|container_name
ALL_SERVICES=(
  "aifuli-gateway|aifuli/gateway-server|GATEWAY_IMAGE|gateway|supplyhub-gateway"
  "aifuli-module-system/aifuli-module-system-server|aifuli/system-server|SYSTEM_IMAGE|system|supplyhub-system"
  "aifuli-module-infra/aifuli-module-infra-server|aifuli/infra-server|INFRA_IMAGE|infra|supplyhub-infra"
  "aifuli-module-member/aifuli-module-member-server|aifuli/member-server|MEMBER_IMAGE|member|supplyhub-member"
)

SERVICES=()
for spec in "${ALL_SERVICES[@]}"; do
  IFS='|' read -r module image key comp_srv cont_name <<<"${spec}"
  if [[ "${TARGET_SERVICE}" == "all" || "${TARGET_SERVICE}" == "${comp_srv}" || "${TARGET_SERVICE}" == "${module}" ]]; then
    SERVICES+=("${spec}")
  fi
done

if [[ ${#SERVICES[@]} -eq 0 ]]; then
  err "未找到匹配服务: ${TARGET_SERVICE} (支持: all, gateway, system, infra, member)"
  exit 1
fi

MVN_PL=""
IMAGES=()
ENV_KEYS=()
COMPOSE_SERVICES=()
for spec in "${SERVICES[@]}"; do
  IFS='|' read -r module image key comp_srv cont_name <<<"${spec}"
  if [[ -n "${MVN_PL}" ]]; then
    MVN_PL="${MVN_PL},"
  fi
  MVN_PL="${MVN_PL}${module}"
  IMAGES+=("${image}:${SHA_TAG}")
  ENV_KEYS+=("${key}")
  COMPOSE_SERVICES+=("${comp_srv}")
done

log "target=${TARGET_SERVICE} commit=${COMMIT_HASH} remote=${REMOTE_HOST} dir=${REMOTE_DIR}"

log "Step 1/6: Maven package (skip tests)"
mvn -pl "${MVN_PL}" -am package -DskipTests -q

log "Step 2/6: docker buildx amd64"
for spec in "${SERVICES[@]}"; do
  IFS='|' read -r module image key comp_srv cont_name <<<"${spec}"
  tag="${image}:${SHA_TAG}"
  log "build ${tag}"
  docker buildx build --platform linux/amd64 --load -t "${tag}" "${MONOREPO_ROOT}/${module}"
done

TARBALL="/tmp/supplyhub-${SHA_TAG}.tar.gz"
log "Step 3/6: save+scp ${TARBALL}"
docker save "${IMAGES[@]}" | gzip > "${TARBALL}"
ssh "${REMOTE_HOST}" "install -d -m 755 ${REMOTE_DIR}/logs"
scp "${SCP_OPTS[@]}" "${TARBALL}" "${REMOTE_HOST}:${REMOTE_DIR}/$(basename "${TARBALL}")"
scp "${SCP_OPTS[@]}" "${MONOREPO_ROOT}/script/deploy/supplyhub-test/docker-compose.yml" \
  "${REMOTE_HOST}:${REMOTE_DIR}/docker-compose.yml"

log "Step 4/6: remote load + .env"
NACOS_YAML="${MONOREPO_ROOT}/aifuli-gateway/src/main/resources/application-test.yaml"
ENV_KEYS_STR=$(IFS=','; echo "${ENV_KEYS[*]}")
python3 - "${NACOS_YAML}" "${SHA_TAG}" "${MONOREPO_ROOT}/script/deploy/supplyhub-test/.env.example" "${ENV_KEYS_STR}" <<'PY' | ssh "${REMOTE_HOST}" "cat > ${REMOTE_DIR}/.env.generated && chmod 600 ${REMOTE_DIR}/.env.generated"
import re, sys
yaml_path, sha, example, env_keys_str = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
active_keys = set(env_keys_str.split(",")) if env_keys_str else set()
text = open(yaml_path, encoding="utf-8").read()
addr = re.search(r"server-addr:\s*(\S+)", text).group(1).split("#")[0].strip()
user = re.search(r"username:\s*(\S+)", text).group(1).split("#")[0].strip()
pwd = re.search(r'password:\s*"([^"]+)"', text).group(1)
all_keys = {
    "GATEWAY_IMAGE": f"aifuli/gateway-server:{sha}",
    "SYSTEM_IMAGE": f"aifuli/system-server:{sha}",
    "INFRA_IMAGE": f"aifuli/infra-server:{sha}",
    "MEMBER_IMAGE": f"aifuli/member-server:{sha}",
    "SPRING_CLOUD_NACOS_SERVER_ADDR": addr,
    "SPRING_CLOUD_NACOS_USERNAME": user,
    "SPRING_CLOUD_NACOS_PASSWORD": pwd,
    "SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR": addr,
    "SPRING_CLOUD_NACOS_CONFIG_USERNAME": user,
    "SPRING_CLOUD_NACOS_CONFIG_PASSWORD": pwd,
    "SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR": addr,
    "SPRING_CLOUD_NACOS_DISCOVERY_USERNAME": user,
    "SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD": pwd,
}
keys = {}
for k, v in all_keys.items():
    if k.endswith("_IMAGE"):
        if k in active_keys:
            keys[k] = v
    else:
        keys[k] = v
out = []
for line in open(example, encoding="utf-8"):
    if line.startswith("#") or "=" not in line:
        out.append(line)
        continue
    key, _, val = line.partition("=")
    key = key.strip()
    if key in keys:
        out.append(f"{key}={keys[key]}\n")
    elif key.endswith("_IMAGE"):
        continue
    else:
        out.append(line)
sys.stdout.write("".join(out))
PY

ssh "${REMOTE_HOST}" bash -s <<REMOTE
set -euo pipefail
cd "${REMOTE_DIR}"
docker load < "$(basename "${TARBALL}")"
rm -f "$(basename "${TARBALL}")"
if [[ ! -f .env ]]; then
  mv .env.generated .env
  chmod 600 .env
  echo "[remote] created .env"
else
  python3 - <<'PY'
from pathlib import Path
env = Path(".env").read_text()
gen = Path(".env.generated").read_text()
keys = [
    "GATEWAY_IMAGE","SYSTEM_IMAGE","INFRA_IMAGE","MEMBER_IMAGE",
]
def grab(text, key):
    for line in text.splitlines():
        if line.startswith(key + "="):
            return line
    return None
lines = env.splitlines(True)
out = []
for line in lines:
    replaced = False
    for key in keys:
        if line.startswith(key + "="):
            g = grab(gen, key)
            if g and "replace-with-git-sha" not in g:
                out.append(g if g.endswith("\n") else g + "\n")
                replaced = True
                break
    if not replaced:
        out.append(line if line.endswith("\n") else line + "\n")
Path(".env").write_text("".join(out))
PY
  rm -f .env.generated
  chmod 600 .env
  echo "[remote] updated image tags in existing .env"
fi
echo "[remote] images:"
grep -E '_IMAGE=' .env
REMOTE

UP_SERVICES=""
if [[ "${TARGET_SERVICE}" != "all" ]]; then
  UP_SERVICES="${COMPOSE_SERVICES[*]}"
fi
log "Step 5/6: compose up (project supplyhub-test, services: ${UP_SERVICES:-all})"
ssh "${REMOTE_HOST}" "cd ${REMOTE_DIR} && docker compose -p supplyhub-test up -d --remove-orphans ${UP_SERVICES}"

log "Step 6/6: wait healthy"
for spec in "${SERVICES[@]}"; do
  IFS='|' read -r module image key comp_srv cont_name <<<"${spec}"
  log "waiting for ${cont_name} to be healthy"
  ELAPSED=0
  STATUS="starting"
  while [[ ${ELAPSED} -lt ${HEALTH_WAIT_SECONDS} ]]; do
    STATUS=$(ssh "${REMOTE_HOST}" "docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' ${cont_name} 2>/dev/null" || echo "not-found")
    if [[ "${STATUS}" == "healthy" ]]; then
      log "${cont_name} healthy"
      break
    fi
    printf "."
    sleep 5
    ELAPSED=$((ELAPSED + 5))
  done
  echo ""
  if [[ "${STATUS}" != "healthy" ]]; then
    warn "${cont_name} 未在 ${HEALTH_WAIT_SECONDS}s 内 healthy (当前: ${STATUS})"
    ssh "${REMOTE_HOST}" "docker logs --tail 80 ${cont_name} 2>&1 || true"
    ssh "${REMOTE_HOST}" "docker compose -p supplyhub-test -f ${REMOTE_DIR}/docker-compose.yml ps || true"
    exit 1
  fi
done

ssh "${REMOTE_HOST}" "docker ps --filter name=supplyhub- --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'"
ssc=$(ssh "${REMOTE_HOST}" "docker inspect --format='{{.State.Status}}' supply-chain-server-test 2>/dev/null || echo missing")
log "supply-chain-server-test still: ${ssc}"
rm -f "${TARBALL}"
log "done ${SHA_TAG}"
