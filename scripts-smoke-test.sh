#!/bin/bash
# ==============================================================================
# 供应链双服务（system-server + supply-chain-server）实机联调一键自动化烟测
#
# 设计依据与详细约束：
#   docs/superpowers/specs/2026-09-12-supply-chain-dual-service-smoke-test-design.md
#   docs/集成测试.md#双服务一键自动化烟测门禁
#
# 验收范围：
#   1. 自动创建并准备独立 MySQL 数据库（system 与 supply-chain 各独占空库）和 Redis。
#   2. 启动独立 SystemServerApplication（58081）与 SupplyChainServerApplication（58190）。
#   3. 自动执行 system 基础表、菜单扩展与供应链 37 权限/菜单 SQL；由 Liquibase 初始化供应链 105 表。
#   4. 登录验证并断言供应链 37 个细粒度权限全集与动态菜单树投影。
#   5. 跨服务验证商品中心（SPU、分类树、品牌、商品类型）、标品池、订单 6 大接口的登录态（code=0）与匿名态（code=401）。
#   6. 校验 OpenAPI 关键 Schema（CategoryDetail 9 字段且无内部主键泄露、SkuListItem 10 字段且字段说明非空）。
#   7. 无论成功失败均由 trap 自动清理所有后台进程与临时数据库。
# ==============================================================================

set -uo pipefail

# ------------------------------------------------------------------------------
# 1. 基础环境与路径探测
# ------------------------------------------------------------------------------
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# 日志输出目录
LOG_DIR="$REPO_ROOT/target/smoke-test-logs"
mkdir -p "$LOG_DIR"

# JDK 探测（遵循 AGENTS.md 约束，编译字节码为 Java 21，必须使用 JDK 21 运行）
if [ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  else
    echo "[ERROR] 未找到 Java 环境，请安装 JDK 21 或配置 JAVA_HOME"
    exit 1
  fi
else
  JAVA_BIN="${JAVA_HOME}/bin/java"
fi

JAVA_VERSION="$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}')"
echo "[INFO] 使用 Java 版本: $JAVA_VERSION ($JAVA_BIN)"
case "$JAVA_VERSION" in
  21*|22*|23*) ;;
  *)
    echo "[ERROR] 项目已被编译为 class 65.0 (Java 21)，当前运行环境 $JAVA_VERSION 低于 JDK 21，请使用 JDK 21！"
    exit 1
    ;;
esac

# MySQL 客户端探测
if [ -z "${MYSQL:-}" ]; then
  if [ -x "/opt/homebrew/opt/mysql-client@8.4/bin/mysql" ]; then
    MYSQL="/opt/homebrew/opt/mysql-client@8.4/bin/mysql"
  elif command -v mysql >/dev/null 2>&1; then
    MYSQL="$(command -v mysql)"
  else
    echo "[ERROR] 未找到 mysql 客户端，请安装 mysql-client 或配置 MYSQL 环境变量"
    exit 1
  fi
fi

# Python 3 探测
if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python3)"
else
  echo "[ERROR] 未找到 python3 命令，烟测断言依赖 Python 3"
  exit 1
fi

# 端口与连接默认配置
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PWD="${MYSQL_PWD:-}"

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"

SYSTEM_PORT="${SYSTEM_PORT:-58081}"
SUPPLY_CHAIN_PORT="${SUPPLY_CHAIN_PORT:-58190}"

# 临时资源标识
TIMESTAMP="$(date +%s)"
RANDOM_SUFFIX="${$}"
SYSTEM_DB="sc_smoke_sys_${TIMESTAMP}_${RANDOM_SUFFIX}"
SC_DB="sc_smoke_sc_${TIMESTAMP}_${RANDOM_SUFFIX}"

TEMP_REDIS_PID=""
TEMP_REDIS_DIR=""
TEMP_REDIS_DOCKER=""

SYSTEM_PID=""
SUPPLY_CHAIN_PID=""

# ------------------------------------------------------------------------------
# 2. 清理机制（无论成败均无条件执行）
# ------------------------------------------------------------------------------
cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  echo ""
  echo "========================================================================"
  echo "[CLEANUP] 开始执行烟测环境自动清理..."
  echo "========================================================================"

  # 1. 终止供应链服务进程
  if [ -n "$SUPPLY_CHAIN_PID" ] && kill -0 "$SUPPLY_CHAIN_PID" 2>/dev/null; then
    echo "[CLEANUP] 停止 supply-chain-server (PID: $SUPPLY_CHAIN_PID)..."
    kill "$SUPPLY_CHAIN_PID" 2>/dev/null || true
    for _ in {1..10}; do
      if ! kill -0 "$SUPPLY_CHAIN_PID" 2>/dev/null; then break; fi
      sleep 0.5
    done
    if kill -0 "$SUPPLY_CHAIN_PID" 2>/dev/null; then
      kill -9 "$SUPPLY_CHAIN_PID" 2>/dev/null || true
    fi
  fi

  # 2. 终止 system 服务进程
  if [ -n "$SYSTEM_PID" ] && kill -0 "$SYSTEM_PID" 2>/dev/null; then
    echo "[CLEANUP] 停止 system-server (PID: $SYSTEM_PID)..."
    kill "$SYSTEM_PID" 2>/dev/null || true
    for _ in {1..10}; do
      if ! kill -0 "$SYSTEM_PID" 2>/dev/null; then break; fi
      sleep 0.5
    done
    if kill -0 "$SYSTEM_PID" 2>/dev/null; then
      kill -9 "$SYSTEM_PID" 2>/dev/null || true
    fi
  fi

  # 3. 停止临时 Redis 实例
  if [ -n "$TEMP_REDIS_PID" ] && kill -0 "$TEMP_REDIS_PID" 2>/dev/null; then
    echo "[CLEANUP] 停止临时 local redis-server (PID: $TEMP_REDIS_PID)..."
    kill "$TEMP_REDIS_PID" 2>/dev/null || true
    for _ in {1..6}; do
      if ! kill -0 "$TEMP_REDIS_PID" 2>/dev/null; then break; fi
      sleep 0.2
    done
    if kill -0 "$TEMP_REDIS_PID" 2>/dev/null; then
      kill -9 "$TEMP_REDIS_PID" 2>/dev/null || true
    fi
  fi
  if [ -n "$TEMP_REDIS_DIR" ] && [ -d "$TEMP_REDIS_DIR" ]; then
    rm -rf "$TEMP_REDIS_DIR"
  fi
  if [ -n "$TEMP_REDIS_DOCKER" ]; then
    echo "[CLEANUP] 停止临时 docker redis 容器 ($TEMP_REDIS_DOCKER)..."
    docker rm -f "$TEMP_REDIS_DOCKER" >/dev/null 2>&1 || true
  fi

  # 4. 删除临时 MySQL 数据库
  echo "[CLEANUP] 删除临时 MySQL 数据库: $SYSTEM_DB, $SC_DB..."
  local mysql_cmd=("$MYSQL" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER")
  if [ -n "$MYSQL_PWD" ]; then
    mysql_cmd+=("-p$MYSQL_PWD")
  fi
  "${mysql_cmd[@]}" -e "DROP DATABASE IF EXISTS \`$SYSTEM_DB\`; DROP DATABASE IF EXISTS \`$SC_DB\`;" 2>/dev/null || true

  echo "========================================================================"
  if [ "${exit_code}" -eq 0 ]; then
    echo "[SUCCESS] 双服务一键自动化烟测全部通过！"
  else
    echo "[FAILURE] 双服务一键自动化烟测失败（退出码: ${exit_code}），详细日志请查阅 $LOG_DIR"
  fi
  echo "========================================================================"
  exit "${exit_code}"
}

trap cleanup EXIT INT TERM

# ------------------------------------------------------------------------------
# 3. 构建产物检查
# ------------------------------------------------------------------------------
SYSTEM_JAR="$REPO_ROOT/aifuli-module-system/aifuli-module-system-server/target/aifuli-module-system-server.jar"
SC_JAR="$REPO_ROOT/aifuli-module-supply-chain/aifuli-module-supply-chain-server/target/aifuli-module-supply-chain-server.jar"

if [ ! -f "$SYSTEM_JAR" ] || [ ! -f "$SC_JAR" ]; then
  echo "[INFO] 未找到已打包的 Jar 产物，开始编译构建..."
  mvn -o package -DskipTests -pl aifuli-module-system/aifuli-module-system-server,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am
fi

# ------------------------------------------------------------------------------
# 4. 准备独立 Redis
# ------------------------------------------------------------------------------
echo "[STEP 1/6] 准备 Redis 实例..."
check_port() {
  local host="$1"
  local port="$2"
  "$PYTHON_BIN" -c "import socket; s = socket.socket(); s.settimeout(1); exit(0 if s.connect_ex(('$host', int($port))) == 0 else 1)" >/dev/null 2>&1
}

if check_port "$REDIS_HOST" "$REDIS_PORT"; then
  echo "[INFO] 检测到现有 Redis 实例运行于 ${REDIS_HOST}:${REDIS_PORT}，直接复用"
else
  TEMP_PORT=16379
  if command -v redis-server >/dev/null 2>&1; then
    echo "[INFO] 本地 Redis 未运行，启动临时 local redis-server (端口: $TEMP_PORT)..."
    TEMP_REDIS_DIR="$(mktemp -d /tmp/sc-smoke-redis-XXXXXX)"
    redis-server --port "$TEMP_PORT" --daemonize yes --pidfile "$TEMP_REDIS_DIR/redis.pid" --dir "$TEMP_REDIS_DIR"
    sleep 0.5
    if [ -f "$TEMP_REDIS_DIR/redis.pid" ]; then
      TEMP_REDIS_PID="$(cat "$TEMP_REDIS_DIR/redis.pid")"
      REDIS_PORT="$TEMP_PORT"
      echo "[INFO] 临时 local redis-server 启动成功 (PID: $TEMP_REDIS_PID, 端口: $REDIS_PORT)"
    fi
  elif command -v docker >/dev/null 2>&1; then
    echo "[INFO] 本地无 redis-server 命令，使用 Docker 启动临时 Redis 容器 (端口: $TEMP_PORT)..."
    TEMP_REDIS_DOCKER="sc-smoke-redis-${RANDOM_SUFFIX}"
    docker run -d --name "$TEMP_REDIS_DOCKER" -p "$TEMP_PORT:6379" redis:alpine >/dev/null 2>&1 || \
      docker run -d --name "$TEMP_REDIS_DOCKER" -p "$TEMP_PORT:6379" redis:7-alpine >/dev/null 2>&1
    sleep 1
    REDIS_PORT="$TEMP_PORT"
    echo "[INFO] 临时 Docker Redis 启动成功 (容器: $TEMP_REDIS_DOCKER, 端口: $REDIS_PORT)"
  else
    echo "[ERROR] 未检测到可用的 Redis 实例，且无法自动启动 local redis-server 或 Docker Redis"
    exit 1
  fi
fi

# ------------------------------------------------------------------------------
# 5. 准备独立 MySQL 数据库并导入 SQL
# ------------------------------------------------------------------------------
echo "[STEP 2/6] 准备独立 MySQL 数据库并执行菜单 SQL..."
MYSQL_EXEC=("$MYSQL" -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER")
if [ -n "$MYSQL_PWD" ]; then
  MYSQL_EXEC+=("-p$MYSQL_PWD")
fi

echo "[INFO] 创建独占数据库 $SYSTEM_DB 与 $SC_DB..."
"${MYSQL_EXEC[@]}" -e "
  DROP DATABASE IF EXISTS \`$SYSTEM_DB\`;
  CREATE DATABASE \`$SYSTEM_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  DROP DATABASE IF EXISTS \`$SC_DB\`;
  CREATE DATABASE \`$SC_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"

echo "[INFO] 为 system 库执行基线 SQL (sql/mysql/ruoyi-vue-pro.sql)..."
"${MYSQL_EXEC[@]}" "$SYSTEM_DB" < "$REPO_ROOT/sql/mysql/ruoyi-vue-pro.sql"

echo "[INFO] 为 system 库执行菜单多应用模式 SQL (system-menu-application-schema.sql)..."
"${MYSQL_EXEC[@]}" "$SYSTEM_DB" < "$REPO_ROOT/script/deploy/aifuli-test/sql/system-menu-application-schema.sql"

echo "[INFO] 为 system 库执行供应链 37 权限与动态菜单 SQL (system-supply-chain-admin-menus.sql)..."
"${MYSQL_EXEC[@]}" "$SYSTEM_DB" < "$REPO_ROOT/script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql"

# 快速验证 SQL 写入行数
MENU_COUNT="$("${MYSQL_EXEC[@]}" -N -s "$SYSTEM_DB" -e "SELECT count(*) FROM system_menu WHERE application_code='supply-chain-admin';")"
echo "[INFO] system 库供应链菜单记录数: $MENU_COUNT (预期 39 条：37 权限节点 + 2 级目录)"
if [ "$MENU_COUNT" -ne 39 ]; then
  echo "[ERROR] 供应链菜单 SQL 执行结果异常，预期 39，实际 $MENU_COUNT"
  exit 1
fi

# ------------------------------------------------------------------------------
# 6. 启动 System Server
# ------------------------------------------------------------------------------
echo "[STEP 3/6] 启动独立 SystemServerApplication (端口: $SYSTEM_PORT)..."
SYS_LOG="$LOG_DIR/system-server.log"

SYS_ARGS=(
  --server.port="$SYSTEM_PORT"
  --spring.profiles.active=local
  --spring.cloud.nacos.discovery.enabled=false
  --spring.cloud.nacos.config.enabled=false
  --spring.datasource.dynamic.datasource.master.url="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${SYSTEM_DB}?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true"
  --spring.datasource.dynamic.datasource.master.username="$MYSQL_USER"
  --spring.data.redis.host="$REDIS_HOST"
  --spring.data.redis.port="$REDIS_PORT"
  --spring.cloud.openfeign.client.config.system-server.url="http://127.0.0.1:${SYSTEM_PORT}"
)
if [ -n "$MYSQL_PWD" ]; then
  SYS_ARGS+=(--spring.datasource.dynamic.datasource.master.password="$MYSQL_PWD")
else
  SYS_ARGS+=(--spring.datasource.dynamic.datasource.master.password="")
fi
if [ -n "$REDIS_PASSWORD" ]; then
  SYS_ARGS+=(--spring.data.redis.password="$REDIS_PASSWORD")
fi

"$JAVA_BIN" -jar "$SYSTEM_JAR" "${SYS_ARGS[@]}" >"$SYS_LOG" 2>&1 &
SYSTEM_PID=$!
echo "[INFO] system-server 已在后台启动 (PID: $SYSTEM_PID)，等待端口就绪..."

WAIT_SECONDS=60
while [ $WAIT_SECONDS -gt 0 ]; do
  if check_port "127.0.0.1" "$SYSTEM_PORT"; then
    echo "[INFO] system-server 端口 $SYSTEM_PORT 已就绪！"
    break
  fi
  if ! kill -0 "$SYSTEM_PID" 2>/dev/null; then
    echo "[ERROR] system-server 进程异常退出，日志如下："
    tail -40 "$SYS_LOG"
    exit 1
  fi
  sleep 1
  WAIT_SECONDS=$((WAIT_SECONDS - 1))
done
if [ $WAIT_SECONDS -le 0 ]; then
  echo "[ERROR] system-server 启动超时，日志末尾："
  tail -40 "$SYS_LOG"
  exit 1
fi

# ------------------------------------------------------------------------------
# 7. 启动 Supply Chain Server
# ------------------------------------------------------------------------------
echo "[STEP 4/6] 启动独立 SupplyChainServerApplication (端口: $SUPPLY_CHAIN_PORT)..."
SC_LOG="$LOG_DIR/supply-chain-server.log"

SC_ARGS=(
  --server.port="$SUPPLY_CHAIN_PORT"
  --spring.profiles.active=local
  --spring.cloud.nacos.discovery.enabled=false
  --spring.cloud.nacos.config.enabled=false
  --aifuli.supply-chain.datasource.url="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${SC_DB}?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true"
  --aifuli.supply-chain.datasource.username="$MYSQL_USER"
  --aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
  --spring.data.redis.host="$REDIS_HOST"
  --spring.data.redis.port="$REDIS_PORT"
  --spring.cloud.openfeign.client.config.system-server.url="http://127.0.0.1:${SYSTEM_PORT}"
  --aifuli.supply-chain.security.key-version=local-v1
  --aifuli.supply-chain.security.envelope-key-base64=MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=
  --aifuli.supply-chain.security.fingerprint-key-base64=MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=
  --aifuli.web.admin-ui.url="http://127.0.0.1:${SUPPLY_CHAIN_PORT}"
  --AIFULI_SUPPLY_CHAIN_OPENAPI_ENABLED=true
  --aifuli.supply-chain.openapi.enabled=true
  --springdoc.api-docs.enabled=true
  --knife4j.enable=true
)
if [ -n "$MYSQL_PWD" ]; then
  SC_ARGS+=(--aifuli.supply-chain.datasource.password="$MYSQL_PWD")
else
  SC_ARGS+=(--aifuli.supply-chain.datasource.password="")
fi
if [ -n "$REDIS_PASSWORD" ]; then
  SC_ARGS+=(--spring.data.redis.password="$REDIS_PASSWORD")
fi

"$JAVA_BIN" -jar "$SC_JAR" "${SC_ARGS[@]}" >"$SC_LOG" 2>&1 &
SUPPLY_CHAIN_PID=$!
echo "[INFO] supply-chain-server 已在后台启动 (PID: $SUPPLY_CHAIN_PID)，等待 Liquibase 执行及端口就绪..."

WAIT_SECONDS=60
while [ $WAIT_SECONDS -gt 0 ]; do
  if check_port "127.0.0.1" "$SUPPLY_CHAIN_PORT"; then
    echo "[INFO] supply-chain-server 端口 $SUPPLY_CHAIN_PORT 已就绪！"
    break
  fi
  if ! kill -0 "$SUPPLY_CHAIN_PID" 2>/dev/null; then
    echo "[ERROR] supply-chain-server 进程异常退出，日志如下："
    tail -40 "$SC_LOG"
    exit 1
  fi
  sleep 1
  WAIT_SECONDS=$((WAIT_SECONDS - 1))
done
if [ $WAIT_SECONDS -le 0 ]; then
  echo "[ERROR] supply-chain-server 启动超时，日志末尾："
  tail -40 "$SC_LOG"
  exit 1
fi

# ------------------------------------------------------------------------------
# 8. 执行全链路端到端功能契约断言 (Python 3 严密校验)
# ------------------------------------------------------------------------------
echo "[STEP 5/6] 运行自动化烟测断言套件..."

"$PYTHON_BIN" - <<PYEOF
import json
import sys
import urllib.request
import urllib.error

SYSTEM_URL = "http://127.0.0.1:${SYSTEM_PORT}"
SC_URL = "http://127.0.0.1:${SUPPLY_CHAIN_PORT}"

def http_json(url, method="GET", data=None, headers=None):
    req_headers = {"Content-Type": "application/json"}
    if headers:
        req_headers.update(headers)
    req_data = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=req_data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body}

print("=== [1/5] 登录并获取管理员 Token ===")
status, res = http_json(
    f"{SYSTEM_URL}/admin-api/system/auth/login",
    method="POST",
    data={"username": "admin", "password": "admin123"},
    headers={"tenant-id": "1"}
)
assert status == 200, f"登录 HTTP 状态码异常: {status}, body={res}"
assert res.get("code") == 0, f"登录业务返回码异常: {res}"
token = res.get("data", {}).get("accessToken")
assert token, f"未能取得有效 accessToken: {res}"
print(f"[OK] 登录成功，Token: {token[:8]}...")

print("\n=== [2/5] 验证统一权限菜单、兼容参数忽略与供应链动态菜单树 ===")
status, perm_res = http_json(
    f"{SYSTEM_URL}/admin-api/system/auth/get-permission-info",
    headers={"Authorization": f"Bearer {token}", "tenant-id": "1"}
)
assert status == 200, f"获取权限信息 HTTP 异常: {status}"
assert perm_res.get("code") == 0, f"获取权限信息业务错误: {perm_res}"

compat_status, compat_res = http_json(
    f"{SYSTEM_URL}/admin-api/system/auth/get-permission-info?applicationCode=ignored-by-server",
    headers={"Authorization": f"Bearer {token}", "tenant-id": "1"}
)
assert compat_status == 200, f"携带兼容参数获取权限信息 HTTP 异常: {compat_status}"
assert compat_res.get("code") == 0, f"携带兼容参数获取权限信息业务错误: {compat_res}"
assert compat_res.get("data") == perm_res.get("data"), \
    "applicationCode 未被忽略：携带参数与省略参数的权限菜单响应不一致"
print("[OK] applicationCode 兼容参数已被忽略，两次权限菜单响应一致")

permissions = set(perm_res.get("data", {}).get("permissions", []))
EXPECTED_37_PERMISSIONS = {
    "supply-chain:admin",
    # 供应商 5 个
    "supply-chain:supplier:query", "supply-chain:supplier:create", "supply-chain:supplier:update",
    "supply-chain:supplier:delete", "supply-chain:supplier:update-status",
    # 分销商 5 个
    "supply-chain:buyer:query", "supply-chain:buyer:create", "supply-chain:buyer:update",
    "supply-chain:buyer:delete", "supply-chain:buyer:update-status",
    # 项目 5 个
    "supply-chain:project:query", "supply-chain:project:create", "supply-chain:project:update",
    "supply-chain:project:delete", "supply-chain:project:update-status",
    # 订单 1 个
    "supply-chain:order:query",
    # 商品 5 个
    "supply-chain:product:query", "supply-chain:product:create", "supply-chain:product:update",
    "supply-chain:product:update-status", "supply-chain:product:delete",
    # 分类 5 个
    "supply-chain:category:query", "supply-chain:category:create", "supply-chain:category:update",
    "supply-chain:category:update-status", "supply-chain:category:delete",
    # 品牌 5 个
    "supply-chain:brand:query", "supply-chain:brand:create", "supply-chain:brand:update",
    "supply-chain:brand:update-status", "supply-chain:brand:delete",
    # 类型 5 个
    "supply-chain:product-type:query", "supply-chain:product-type:create", "supply-chain:product-type:update",
    "supply-chain:product-type:update-status", "supply-chain:product-type:delete"
}

missing = EXPECTED_37_PERMISSIONS - permissions
assert not missing, f"缺少供应链权限项: {missing}"
print(f"[OK] 成功验证全部 37 个供应链权限项！")

menus = perm_res.get("data", {}).get("menus", [])
root_menu = next((m for m in menus if m.get("path") == "/supplier" and m.get("name") == "供应链中台"), None)
assert root_menu is not None, f"未找到供应链根菜单: {menus}"

menu_paths = set()
def collect_paths(node):
    if "path" in node and node["path"]:
        menu_paths.add(node["path"])
    for child in (node.get("children") or []):
        collect_paths(child)
collect_paths(root_menu)

required_sub_paths = {"manage", "distribution", "distributor", "project", "orders", "product-center", "products", "categories", "brands", "product-types"}
missing_sub_paths = required_sub_paths - menu_paths
assert not missing_sub_paths, f"供应链菜单树缺少预期页面路径: {missing_sub_paths}"
print(f"[OK] 成功验证供应链动态菜单树结构！")

print("\n=== [3/5] 验证商品中心、标品池、订单 6 大业务接口鉴权 (带 Token) ===")
ENDPOINTS = [
    ("/admin-api/supply-chain/products/spus?pageNo=1&pageSize=10", "SPU 分页"),
    ("/admin-api/supply-chain/categories/management-tree", "分类管理树"),
    ("/admin-api/supply-chain/brands?pageNo=1&pageSize=10", "品牌分页"),
    ("/admin-api/supply-chain/product-types?pageNo=1&pageSize=10", "商品类型分页"),
    ("/admin-api/supply-chain/products/skus?pageNo=1&pageSize=10", "标品池分页"),
    ("/admin-api/supply-chain/orders?pageNo=1&pageSize=10", "订单分页")
]

auth_headers = {"Authorization": f"Bearer {token}", "tenant-id": "1"}
for path, desc in ENDPOINTS:
    status, res = http_json(f"{SC_URL}{path}", headers=auth_headers)
    assert status == 200, f"[{desc}] HTTP 异常: {status}, body={res}"
    assert res.get("code") == 0, f"[{desc}] 业务返回码异常: {res}"
    print(f"[OK] {desc} 调用成功 (code=0)")

print("\n=== [4/5] 验证未登录匿名请求拦截 (预期 401) ===")
for path, desc in ENDPOINTS:
    status, res = http_json(f"{SC_URL}{path}")
    # 匿名请求必须被拦截：HTTP 401 或业务码 401
    is_unauthorized = (status == 401 or res.get("code") == 401)
    assert is_unauthorized, f"[{desc}] 匿名请求未被 401 拦截: status={status}, body={res}"
    print(f"[OK] {desc} 匿名拦截正常 (code=401)")

print("\n=== [5/5] 验证 OpenAPI 关键 Schema 结构与字段说明 ===")
status, openapi = http_json(f"{SC_URL}/v3/api-docs/supply-chain-admin")
assert status == 200, f"获取 OpenAPI JSON 失败: status={status}"
schemas = openapi.get("components", {}).get("schemas", {})

# 1. CategoryDetail 9 字段严格校验
cat_schema = schemas.get("CategoryDetail")
assert cat_schema is not None, "OpenAPI 中未找到 CategoryDetail Schema"
cat_props = cat_schema.get("properties", {})
EXPECTED_CAT_FIELDS = {
    "categoryCode", "categoryName", "parentId", "categoryLevel",
    "sort", "visible", "status", "createdAt", "updatedAt"
}
assert set(cat_props.keys()) == EXPECTED_CAT_FIELDS, \
    f"CategoryDetail 字段与契约不符，实际: {set(cat_props.keys())}, 预期: {EXPECTED_CAT_FIELDS}"

# 严禁泄露内部主键与审计字段
LEAKED_FIELDS = {"id", "version", "createdBy", "updatedBy"}
leaked = set(cat_props.keys()) & LEAKED_FIELDS
assert not leaked, f"CategoryDetail 泄露了禁止字段: {leaked}"

for prop_name, prop_meta in cat_props.items():
    desc = prop_meta.get("description", "").strip()
    assert desc, f"CategoryDetail.{prop_name} 缺少中文描述"
print("[OK] CategoryDetail Schema 9 字段校验通过，无内部主键泄露，且所有字段具备中文描述！")

# 2. SkuListItem 10 字段严格校验
sku_schema = schemas.get("SkuListItem")
assert sku_schema is not None, "OpenAPI 中未找到 SkuListItem Schema"
sku_props = sku_schema.get("properties", {})
EXPECTED_SKU_FIELDS = {
    "id", "skuCode", "skuName", "spuCode", "spuName", "sellableCategory",
    "productType", "productClassification", "suggestPriceCents", "marketPriceCents"
}
assert set(sku_props.keys()) == EXPECTED_SKU_FIELDS, \
    f"SkuListItem 字段与契约不符，实际: {set(sku_props.keys())}, 预期: {EXPECTED_SKU_FIELDS}"

for prop_name, prop_meta in sku_props.items():
    desc = prop_meta.get("description", "").strip()
    assert desc, f"SkuListItem.{prop_name} 缺少中文描述"
print("[OK] SkuListItem Schema 10 字段校验通过，且所有字段具备中文描述！")

print("\n>>> 所有端到端实机验证断言均已成功收敛！<<<")
PYEOF

PYTHON_EXIT_CODE=$?
if [ "${PYTHON_EXIT_CODE}" -ne 0 ]; then
  echo "[ERROR] Python 自动化断言套件执行失败 (退出码: ${PYTHON_EXIT_CODE})"
  exit "${PYTHON_EXIT_CODE}"
fi

echo "[STEP 6/6] 烟测断言完成，准备退出..."
exit 0
