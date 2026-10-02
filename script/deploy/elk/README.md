# pro143 ELK Docker Compose 部署包

## 1. 概述

本目录提供在 `pro143` 测试机上独立部署的 ELK（Elasticsearch + Kibana + Logstash）栈，基于 Docker Compose 编排。

- 目标路径：`/data/elk`
- 适用环境：`pro143`（`8.133.164.143` / `172.19.64.105`）
- 镜像版本：8.17.0
- 安全策略：`xpack.security.enabled=false`（免密直连）

## 2. 端口规划

- **Elasticsearch**: `9201` (HTTP API) / `9301` (Transport)
  - 注：原独立 ES 占用 9200 端口，本套 ELK 采用 9201 避免冲突。
- **Kibana**: `5601` (Web 控制台)
- **Logstash**: `5044` (Beats 接入端口) / `50000` (TCP JSON 日志流端口) / `9600` (状态监控)

## 3. 一键部署

在本地执行：
```bash
./deploy-elk-pro143.sh
```

或在 `pro143` 服务器的目标目录执行：
```bash
cd /data/elk
docker compose up -d
```

## 4. 验证命令

```bash
# 验证 ES 状态
curl http://localhost:9201

# 验证 Kibana 健康状态
curl -I http://localhost:5601/api/status

# 查看容器运行状态
docker compose ps
```
