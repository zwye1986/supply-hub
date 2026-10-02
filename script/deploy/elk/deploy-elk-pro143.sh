#!/usr/bin/env bash

set -euo pipefail

# 部署目标配置
TARGET_HOST="${SUPPLYHUB_DOCKER_HOST:-pro143}"
TARGET_DIR="${TARGET_DIR:-/data/elk}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== [1/6] 检查目标服务器连接与内核参数 (${TARGET_HOST}) ==="
ssh "${TARGET_HOST}" "
  set -euo pipefail
  # 检查并设置 ES 运行所需的 max_map_count
  CURRENT_MAP=\$(sysctl -n vm.max_map_count || echo 0)
  if [ \"\${CURRENT_MAP}\" -lt 262144 ]; then
    echo \"设置 vm.max_map_count=262144\"
    sysctl -w vm.max_map_count=262144
    grep -q '^vm.max_map_count' /etc/sysctl.conf && sed -i 's/^vm.max_map_count.*/vm.max_map_count=262144/' /etc/sysctl.conf || echo 'vm.max_map_count=262144' >> /etc/sysctl.conf
  fi
"

echo "=== [2/6] 在目标服务器创建数据与日志目录 ==="
ssh "${TARGET_HOST}" "
  mkdir -p \"${TARGET_DIR}/elasticsearch/data\"
  mkdir -p \"${TARGET_DIR}/elasticsearch/logs\"
  mkdir -p \"${TARGET_DIR}/elasticsearch/config\"
  mkdir -p \"${TARGET_DIR}/kibana/config\"
  mkdir -p \"${TARGET_DIR}/logstash/config\"
  mkdir -p \"${TARGET_DIR}/logstash/pipeline\"
  mkdir -p \"${TARGET_DIR}/logstash/data\"
"

echo "=== [3/6] 同步配置文件到目标服务器 ==="
tar -C "${SCRIPT_DIR}" -cf - \
  docker-compose.yml \
  .env.example \
  elasticsearch/config/elasticsearch.yml \
  kibana/config/kibana.yml \
  logstash/config/logstash.yml \
  logstash/pipeline/logstash.conf \
  | ssh "${TARGET_HOST}" "tar -C \"${TARGET_DIR}\" -xf -"

echo "=== [4/6] 初始化环境与设置目录属主权限 (UID 1000) ==="
ssh "${TARGET_HOST}" "
  cd \"${TARGET_DIR}\"
  if [ ! -f .env ]; then
    cp .env.example .env
  fi
  chmod 600 .env
  # Elasticsearch 与 Logstash 镜像使用 UID 1000 运行
  chown -R 1000:1000 \"${TARGET_DIR}/elasticsearch\" \"${TARGET_DIR}/logstash\"
"

echo "=== [5/6] 启动 ELK 容器栈 ==="
ssh "${TARGET_HOST}" "
  cd \"${TARGET_DIR}\"
  docker compose up -d
"

echo "=== [6/6] 验证 ELK 容器运行状态 ==="
ssh "${TARGET_HOST}" "
  cd \"${TARGET_DIR}\"
  docker compose ps
"

echo "=========================================="
echo "ELK 部署指令执行完成！"
echo "Elasticsearch 地址: http://${TARGET_HOST}:9201"
echo "Kibana 控制台地址 : http://${TARGET_HOST}:5601"
echo "Logstash Beats端口: ${TARGET_HOST}:5044"
echo "=========================================="
