#!/usr/bin/env bash
#
# 构建 supplyhub 四服务（gateway/system/infra/member）amd64 镜像并发布到 pro143 Compose。
# 2026-09-07：移除 pay/erp/mall/statistics，supplyhub 栈不需要这些能力。
# 不停止、不操作供应链容器 supply-chain-server-test。
# 设计见 docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md#10-发布流程
#
# 用法: ./deploy-pro143.sh
#
set -euo pipefail

REMOTE_HOST="${SUPPLYHUB_DOCKER_HOST:-root@8.133.164.143}"
REMOTE_DIR="/data/aifuli-test/supplyhub"
MONOREPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HEALTH_WAIT_SECONDS=900

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

# module_dir|image_name|env_key
SERVICES=(
  "aifuli-gateway|aifuli/gateway-server|GATEWAY_IMAGE"
  "aifuli-module-system/aifuli-module-system-server|aifuli/system-server|SYSTEM_IMAGE"
  "aifuli-module-infra/aifuli-module-infra-server|aifuli/infra-server|INFRA_IMAGE"
  "aifuli-module-member/aifuli-module-member-server|aifuli/member-server|MEMBER_IMAGE"
)

MVN_PL=""
IMAGES=()
for spec in "${SERVICES[@]}"; do
  IFS='|' read -r module image key <<<"${spec}"
  if [[ -n "${MVN_PL}" ]]; then
    MVN_PL="${MVN_PL},"
  fi
  MVN_PL="${MVN_PL}${module}"
  IMAGES+=("${image}:${SHA_TAG}")
done

log "commit=${COMMIT_HASH} remote=${REMOTE_HOST} dir=${REMOTE_DIR}"

log "Step 1/6: Maven package (skip tests)"
mvn -pl "${MVN_PL}" -am package -DskipTests -q

log "Step 2/6: docker buildx amd64"
for spec in "${SERVICES[@]}"; do
  IFS='|' read -r module image key <<<"${spec}"
  tag="${image}:${SHA_TAG}"
  log "build ${tag}"
  docker buildx build --platform linux/amd64 --load -t "${tag}" "${MONOREPO_ROOT}/${module}"
done

TARBALL="/tmp/supplyhub-${SHA_TAG}.tar.gz"
log "Step 3/6: save+scp ${TARBALL}"
docker save "${IMAGES[@]}" | gzip > "${TARBALL}"
ssh "${REMOTE_HOST}" "install -d -m 755 ${REMOTE_DIR}/logs"
scp "${TARBALL}" "${REMOTE_HOST}:${REMOTE_DIR}/$(basename "${TARBALL}")"
scp "${MONOREPO_ROOT}/script/deploy/supplyhub-test/docker-compose.yml" \
  "${REMOTE_HOST}:${REMOTE_DIR}/docker-compose.yml"

log "Step 4/6: remote load + .env"
NACOS_YAML="${MONOREPO_ROOT}/aifuli-gateway/src/main/resources/application-test.yaml"
python3 - "${NACOS_YAML}" "${SHA_TAG}" "${MONOREPO_ROOT}/script/deploy/supplyhub-test/.env.example" <<'PY' | ssh "${REMOTE_HOST}" "cat > ${REMOTE_DIR}/.env.generated && chmod 600 ${REMOTE_DIR}/.env.generated"
import re, sys
yaml_path, sha, example = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(yaml_path, encoding="utf-8").read()
addr = re.search(r"server-addr:\s*(\S+)", text).group(1).split("#")[0].strip()
user = re.search(r"username:\s*(\S+)", text).group(1).split("#")[0].strip()
pwd = re.search(r'password:\s*"([^"]+)"', text).group(1)
keys = {
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
out = []
for line in open(example, encoding="utf-8"):
    if line.startswith("#") or "=" not in line:
        out.append(line)
        continue
    key, _, val = line.partition("=")
    key = key.strip()
    if key in keys:
        out.append(f"{key}={keys[key]}\n")
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
seen = set()
for line in lines:
    replaced = False
    for key in keys:
        if line.startswith(key + "="):
            g = grab(gen, key)
            if g:
                out.append(g if g.endswith("\n") else g + "\n")
                seen.add(key)
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

log "Step 5/6: compose up (project supplyhub-test only)"
ssh "${REMOTE_HOST}" "cd ${REMOTE_DIR} && docker compose -p supplyhub-test up -d --remove-orphans"

log "Step 6/6: wait gateway :59080"
ELAPSED=0
STATUS="starting"
while [[ ${ELAPSED} -lt ${HEALTH_WAIT_SECONDS} ]]; do
  STATUS=$(ssh "${REMOTE_HOST}" "docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' supplyhub-gateway 2>/dev/null" || echo "not-found")
  if [[ "${STATUS}" == "healthy" ]]; then
    log "gateway healthy"
    break
  fi
  printf "."
  sleep 5
  ELAPSED=$((ELAPSED + 5))
done
echo ""
if [[ "${STATUS}" != "healthy" ]]; then
  warn "gateway 未在 ${HEALTH_WAIT_SECONDS}s 内 healthy (当前: ${STATUS})"
  ssh "${REMOTE_HOST}" "docker logs --tail 80 supplyhub-gateway 2>&1 || true"
  ssh "${REMOTE_HOST}" "docker compose -p supplyhub-test -f ${REMOTE_DIR}/docker-compose.yml ps || true"
  exit 1
fi

ssh "${REMOTE_HOST}" "docker ps --filter name=supplyhub- --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'"
ssc=$(ssh "${REMOTE_HOST}" "docker inspect --format='{{.State.Status}}' supply-chain-server-test 2>/dev/null || echo missing")
log "supply-chain-server-test still: ${ssc}"
rm -f "${TARBALL}"
log "done ${SHA_TAG}"
