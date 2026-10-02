#!/usr/bin/env bash
# 网关标准订单透传探针（隔离 classpath 实测）。
#
# 目的：以真实 JVM 进程 + application.yaml 实际路由（supply-chain-standard-orders，
# URI 经 AIFULI_GATEWAY_SUPPLY_CHAIN_URI 指向本地挡板）验证 aifuli-gateway 对标准订单路径
# /open-api/supply-chain/project/v1/orders(/query|/assets/query) 的专用分支：
#   1. chunked POST 请求体完整透传到上游（网关不读取、不缓存、不重编码签名正文）；
#   2. 上游响应原样返回客户端（不缓冲卡密响应）；
#   3. 访问日志仅记录安全元数据：requestBody/responseBody 为 "[omitted]"、
#      queryParams 为 null、requestUrl 为去 query 后的注册路径；
#   4. 未注册的兄弟路径（如 /orders/other）不进入标准订单路由（精确路由无保护缺口）；
#   5. 上游故障（连接拒绝）时异常安全：返回脱敏 CommonResult 且响应不泄漏请求正文。
#
# 用法：
#   script/test/gateway-standard-orders-probe.sh [--build]
#     --build  先执行 mvn -pl aifuli-gateway package -DskipTests 构建网关 jar
#
# 前置条件：本地 Maven 仓库已安装 aifuli 依赖模块（与运行其它模块相同要求）。
# 产物：控制台 PASS/FAIL 结论；报告默认写入 script/test/gateway-standard-orders-probe-report.md
#       （可用 PROBE_REPORT 覆盖），报告内含 git HEAD 与网关 jar 的 sha256 源hash，
#       按 docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#5-自动化http闭环与完成判定
#       的要求随源码保存。PASS 时清理临时目录；FAIL 时保留 WORK_DIR 便于排障。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATEWAY_JAR="${REPO_ROOT}/aifuli-gateway/target/aifuli-gateway.jar"
WORK_DIR="$(mktemp -d /tmp/gateway-standard-orders-probe.XXXXXX)"
UPSTREAM_PORT="${UPSTREAM_PORT:-58991}"
GATEWAY_PORT="${GATEWAY_PORT:-58990}"
PROBE_REPORT="${PROBE_REPORT:-${REPO_ROOT}/script/test/gateway-standard-orders-probe-report.md}"

UPSTREAM_PID=""
GATEWAY_PID=""
RESULT="PASS"

cleanup() {
  [[ -n "${GATEWAY_PID}" ]] && kill "${GATEWAY_PID}" 2>/dev/null || true
  [[ -n "${UPSTREAM_PID}" ]] && kill "${UPSTREAM_PID}" 2>/dev/null || true
  # FAIL 时保留 WORK_DIR（网关日志、上游 body 抓取样）便于排障。
  if [[ "${RESULT}" == "PASS" ]]; then
    rm -rf "${WORK_DIR}"
  else
    echo "[probe] 保留排障目录：${WORK_DIR}"
  fi
}
trap cleanup EXIT

fail() {
  RESULT="FAIL"
  echo "FAIL: $1"
}

if [[ "${1:-}" == "--build" ]]; then
  echo "[probe] 构建网关 jar ..."
  (cd "${REPO_ROOT}" && mvn -q -pl aifuli-gateway package -DskipTests)
fi

if [[ ! -f "${GATEWAY_JAR}" ]]; then
  echo "网关 jar 不存在：${GATEWAY_JAR}（先执行一次 --build 或手动 package）"
  exit 1
fi

# ---------- 1. 启动 mock 上游：记录收到的原始 body 并回显 JSON ----------
cat > "${WORK_DIR}/mock_upstream.py" <<'PY'
import http.server, json, sys

BODY_FILE, RESP_FILE = sys.argv[1], sys.argv[2]

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _read_body(self):
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            body = b""
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.readline()
            return body, True
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length), False

    def do_POST(self):
        body, chunked = self._read_body()
        with open(BODY_FILE, "wb") as f:
            f.write(body)
        with open(RESP_FILE, "w") as f:
            json.dump({"path": self.path, "chunked": chunked, "bodyLength": len(body)}, f)
        payload = json.dumps({"ok": True, "echo": body.decode("utf-8", "replace")}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass

http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[3])), Handler).serve_forever()
PY
python3 "${WORK_DIR}/mock_upstream.py" "${WORK_DIR}/upstream_body.bin" "${WORK_DIR}/upstream_meta.json" "${UPSTREAM_PORT}" &
UPSTREAM_PID=$!

# ---------- 2. 启动网关（隔离 classpath：禁用 Nacos；不覆盖路由，使用 application.yaml
# 实际的 supply-chain-standard-orders 路由，URI 经环境变量指向本地挡板上游） ----------
AIFULI_GATEWAY_SUPPLY_CHAIN_URI="http://127.0.0.1:${UPSTREAM_PORT}" \
java -jar "${GATEWAY_JAR}" \
  --server.port="${GATEWAY_PORT}" \
  --spring.cloud.nacos.discovery.enabled=false \
  --spring.cloud.nacos.config.enabled=false \
  --spring.cloud.nacos.config.import-check.enabled=false \
  --spring.boot.admin.client.enabled=false \
  --logging.file.name="${WORK_DIR}/gateway-app.log" \
  > "${WORK_DIR}/gateway.log" 2>&1 &
GATEWAY_PID=$!

echo "[probe] 等待网关就绪 ..."
GATEWAY_READY=0
for _ in $(seq 1 60); do
  if curl -s -o /dev/null "http://127.0.0.1:${GATEWAY_PORT}/actuator/health" 2>/dev/null; then
    GATEWAY_READY=1
    break
  fi
  if ! kill -0 "${GATEWAY_PID}" 2>/dev/null; then
    echo "[probe] 网关进程退出"; tail -40 "${WORK_DIR}/gateway.log"; exit 1
  fi
  sleep 1
done
# actuator 可能未开放，退化为端口探测
if [[ "${GATEWAY_READY}" != "1" ]]; then
  for _ in $(seq 1 30); do
    if nc -z 127.0.0.1 "${GATEWAY_PORT}" 2>/dev/null; then GATEWAY_READY=1; break; fi
    sleep 1
  done
fi
if [[ "${GATEWAY_READY}" != "1" ]]; then
  echo "[probe] 网关未就绪"; tail -40 "${WORK_DIR}/gateway.log"; exit 1
fi

# ---------- 3. 探针请求 ----------
printf '{"buyerOrderNo":"PROBE-CHUNKED-001"}' > "${WORK_DIR}/body.txt"
PROBE_PATH="/open-api/supply-chain/project/v1/orders/query"
HTTP_CODE=$(curl -s -o "${WORK_DIR}/client_resp.json" -w "%{http_code}" \
  -X POST "http://127.0.0.1:${GATEWAY_PORT}${PROBE_PATH}?leak=secret" \
  -H "Transfer-Encoding: chunked" -H "Content-Type: application/json" \
  --data-binary @"${WORK_DIR}/body.txt")

# 校验 1：chunked POST 完整透传到上游
UPSTREAM_BODY=$(cat "${WORK_DIR}/upstream_body.bin" 2>/dev/null || echo "")
if [[ "${UPSTREAM_BODY}" != '{"buyerOrderNo":"PROBE-CHUNKED-001"}' ]]; then
  fail "上游未收到完整透传 body：'${UPSTREAM_BODY}'"
fi
CHUNKED_FLAG=$(python3 -c "import json;print(json.load(open('${WORK_DIR}/upstream_meta.json'))['chunked'])" 2>/dev/null || echo "unknown")
if [[ "${CHUNKED_FLAG}" != "True" ]]; then
  fail "上游收到的请求不是 chunked 编码（chunked=${CHUNKED_FLAG}）"
fi

# 校验 2：响应原样返回客户端
if [[ "${HTTP_CODE}" != "200" ]] || ! grep -qE '"ok"\s*:\s*true' "${WORK_DIR}/client_resp.json"; then
  fail "客户端响应异常 http=${HTTP_CODE} body=$(cat "${WORK_DIR}/client_resp.json" 2>/dev/null)"
fi

sleep 1
# 访问日志写入 logging.file.name 指定的文件（logback 文件 appender），console 可能仅含启动日志。
# 日志正文是 pretty-print JSON（跨多行），用 awk 按 "[writeAccessLog] ... }" 块提取，
# 再取包含 orders/query 的那个完整日志块。
extract_standard_order_access_log() {
  awk '
    /\[writeAccessLog\]/{buf=$0"\n"; inblock=1; next}
    inblock{buf=buf $0"\n"}
    inblock&&/^\}/{
      inblock=0
      if (buf ~ /orders\/query/){ printf "%s", buf; exit }
      buf=""
    }' "$1" 2>/dev/null || true
}
ACCESS_LOG_LINE=""
for GATEWAY_LOG_FILE in "${WORK_DIR}/gateway-app.log" "${WORK_DIR}/gateway.log"; do
  ACCESS_LOG_LINE=$(extract_standard_order_access_log "${GATEWAY_LOG_FILE}")
  [[ -n "${ACCESS_LOG_LINE}" ]] && break
done
if [[ -z "${ACCESS_LOG_LINE}" ]]; then
  fail "网关日志中未找到标准订单访问日志"
elif ! grep -qE '"requestBody"\s*:\s*"\[omitted\]"' <<<"${ACCESS_LOG_LINE}"; then
  fail "访问日志 requestBody 未脱敏：${ACCESS_LOG_LINE}"
elif ! grep -qE '"responseBody"\s*:\s*"\[omitted\]"' <<<"${ACCESS_LOG_LINE}"; then
  fail "访问日志 responseBody 未脱敏：${ACCESS_LOG_LINE}"
elif ! grep -qE '"queryParams"\s*:\s*null' <<<"${ACCESS_LOG_LINE}"; then
  fail "访问日志 queryParams 未置空：${ACCESS_LOG_LINE}"
elif grep -q "PROBE-CHUNKED-001" <<<"${ACCESS_LOG_LINE}"; then
  fail "访问日志泄露请求正文：${ACCESS_LOG_LINE}"
elif grep -q "leak=secret" <<<"${ACCESS_LOG_LINE}"; then
  fail "访问日志泄露 query string：${ACCESS_LOG_LINE}"
fi

# 校验 4：未注册兄弟路径不进入标准订单路由。
# 网关 GlobalExceptionHandler 将 ResponseStatusException(404) 转为 HTTP 200 + CommonResult(code=404)，
# 因此以响应体 code 判定，而非 HTTP 状态码。
UNREGISTERED_CODE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://127.0.0.1:${GATEWAY_PORT}/open-api/supply-chain/project/v1/orders/other" \
  -H "Content-Type: application/json" --data-binary '{"buyerOrderNo":"X"}' || true)
UNREGISTERED_HTTP="${UNREGISTERED_CODE##*$'\n'}"
UNREGISTERED_BODY="${UNREGISTERED_CODE%$'\n'*}"
UNREGISTERED_RESULT_CODE=$(python3 -c "import json,sys;print(json.load(sys.stdin).get('code'))" <<<"${UNREGISTERED_BODY}" 2>/dev/null || echo "parse-failed")
if [[ "${UNREGISTERED_RESULT_CODE}" != "404" ]]; then
  fail "未注册路径 /orders/other 未返回 code=404（http=${UNREGISTERED_HTTP} body=${UNREGISTERED_BODY}）"
fi

# 校验 5：异常安全 —— 停掉上游后请求注册路径，网关必须返回脱敏 CommonResult 且不泄漏请求正文。
kill "${UPSTREAM_PID}" 2>/dev/null || true
UPSTREAM_PID=""
sleep 1
ERROR_RESP=$(curl -s -m 10 -w "\n%{http_code}" \
  -X POST "http://127.0.0.1:${GATEWAY_PORT}${PROBE_PATH}" \
  -H "Content-Type: application/json" --data-binary @"${WORK_DIR}/body.txt" || true)
ERROR_HTTP="${ERROR_RESP##*$'\n'}"
ERROR_BODY="${ERROR_RESP%$'\n'*}"
ERROR_CODE=$(python3 -c "import json,sys;print(json.load(sys.stdin).get('code'))" <<<"${ERROR_BODY}" 2>/dev/null || echo "parse-failed")
if [[ "${ERROR_CODE}" == "parse-failed" || "${ERROR_CODE}" == "None" || "${ERROR_CODE}" == "0" ]]; then
  fail "上游故障时未返回脱敏 CommonResult（http=${ERROR_HTTP} body=${ERROR_BODY}）"
elif grep -q "PROBE-CHUNKED-001" <<<"${ERROR_BODY}"; then
  fail "上游故障响应泄漏请求正文：${ERROR_BODY}"
fi

# ---------- 4. 生成报告（含源hash：git HEAD + 网关 jar sha256，随源码保存） ----------
GIT_HEAD_SHA=$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo "unknown")
GATEWAY_JAR_SHA256=$(shasum -a 256 "${GATEWAY_JAR}" 2>/dev/null | awk '{print $1}' || echo "unknown")
{
  echo "# 网关标准订单透传探针报告"
  echo
  echo "- 时间：$(date '+%F %T')"
  echo "- 结果：**${RESULT}**"
  echo "- git HEAD：\`${GIT_HEAD_SHA}\`"
  echo "- 网关 jar sha256：\`${GATEWAY_JAR_SHA256}\`"
  echo "- 路由来源：application.yaml 实际路由 supply-chain-standard-orders（URI 指向本地挡板 \${AIFULI_GATEWAY_SUPPLY_CHAIN_URI}）"
  echo "- 上游 chunked 编码：${CHUNKED_FLAG}"
  echo "- chunked POST 探针路径：\`${PROBE_PATH}?leak=secret\`（HTTP ${HTTP_CODE}）"
  echo "- 未注册路径 /orders/other：http=${UNREGISTERED_HTTP} code=${UNREGISTERED_RESULT_CODE}"
  echo "- 上游故障异常安全：http=${ERROR_HTTP} code=${ERROR_CODE}"
  echo
  echo '## 访问日志（关键行）'
  echo
  echo '```'
  echo "${ACCESS_LOG_LINE}"
  echo '```'
  echo
  echo '## 校验项'
  echo
  echo "1. chunked POST body 完整透传上游：$([[ "${UPSTREAM_BODY}" == '{"buyerOrderNo":"PROBE-CHUNKED-001"}' ]] && echo PASS || echo FAIL)"
  echo "2. 上游响应原样返回客户端：$([[ "${HTTP_CODE}" == "200" ]] && echo PASS || echo FAIL)"
  echo "3. 日志 requestBody/responseBody=[omitted]、queryParams=null、不泄露正文与 query：$([[ "${ACCESS_LOG_LINE}" =~ \[omitted\] ]] && echo PASS || echo FAIL)"
  echo "4. 未注册兄弟路径不进入标准订单路由：$([[ "${UNREGISTERED_RESULT_CODE}" == "404" ]] && echo PASS || echo FAIL)"
  echo "5. 上游故障异常安全（脱敏 CommonResult、不泄漏正文）：$([[ "${ERROR_CODE}" != "parse-failed" && "${ERROR_CODE}" != "None" && "${ERROR_CODE}" != "0" ]] && echo PASS || echo FAIL)"
} > "${PROBE_REPORT}"

echo "[probe] 结果：${RESULT}（报告：${PROBE_REPORT}）"
[[ "${RESULT}" == "PASS" ]]
