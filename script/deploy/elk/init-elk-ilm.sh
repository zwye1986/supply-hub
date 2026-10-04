#!/usr/bin/env bash

set -euo pipefail

TARGET_HOST="${SUPPLYHUB_DOCKER_HOST:-pro143}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

POLICY_JSON=$(cat "${SCRIPT_DIR}/elasticsearch/ilm/app-logs-ilm-policy.json")
TEMPLATE_JSON=$(cat "${SCRIPT_DIR}/elasticsearch/ilm/app-logs-template.json")

echo "=== [1/4] 初始化/更新 Elasticsearch ILM 策略 (app-logs-ilm-policy: 7天自动删除) ==="
ssh "${TARGET_HOST}" "curl -s -f -X PUT 'http://127.0.0.1:9201/_ilm/policy/app-logs-ilm-policy' \
  -H 'Content-Type: application/json' -d '${POLICY_JSON}'"
echo ""

echo "=== [2/4] 初始化/更新 Composable Index Template (app-logs-template) ==="
ssh "${TARGET_HOST}" "curl -s -f -X PUT 'http://127.0.0.1:9201/_index_template/app-logs-template' \
  -H 'Content-Type: application/json' -d '${TEMPLATE_JSON}'"
echo ""

echo "=== [3/4] 存量 app-logs-* 索引追溯绑定 ILM 策略与零副本单节点优化 ==="
ssh "${TARGET_HOST}" "curl -s -X PUT 'http://127.0.0.1:9201/app-logs-*/_settings' \
  -H 'Content-Type: application/json' -d '{
    \"index\": {
      \"lifecycle.name\": \"app-logs-ilm-policy\",
      \"number_of_replicas\": 0
    }
  }'" || true
echo ""

echo "=== [4/4] 验证当前索引状态 ==="
ssh "${TARGET_HOST}" "curl -s 'http://127.0.0.1:9201/_cat/indices?v' | grep -E 'index|app-logs'"
