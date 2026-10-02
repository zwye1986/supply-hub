# pro143 ELK Docker Compose 部署详细设计

> 日期：2026-10-01  
> 状态：设计与实施中  
> 目标节点：`pro143`（公网 `8.133.164.143` / 内网 `172.19.64.105`）  
> 部署目录：`/data/elk`

---

## 1. 背景与决策确认

### 1.1 背景
在 `pro143` 测试服务器上需要部署一套标准的 ELK（Elasticsearch + Logstash + Kibana）日志与检索系统，通过 Docker Compose 进行集中编排与容器化管理。

### 1.2 既有环境与关键决策
经服务器环境探查，`pro143` 当前已运行一个独立的单节点 Elasticsearch 容器（8.17.0，占用宿主机 9200 端口，数据挂载于 `/data/es/data`，供 `mall-server` 等进程连接）。基于此现状与用户确认，形成以下关键决策：

| 编号 | 决策项 | 决策内容与依据 |
| :--- | :--- | :--- |
| **d1** | **部署拓扑隔离** | 独立新部署一套完整 ELK，目录置于 `/data/elk`。保留现存 ES 容器（9200 端口）不动，互不冲突。 |
| **d2** | **端口映射规划** | 新 ES 暴露宿主机端口 `9201`（HTTP）与 `9301`（Transport）；Kibana 暴露 `5601`；Logstash 暴露 `5044`（Beats 日志接入）与 `9600`（监控 API）。 |
| **d3** | **组件技术栈** | 采用官方标准 ELK 三件套，版本锁定为 **8.17.0**（与服务器现有基础镜像体系一致，减少镜像拉取差异）。 |
| **d4** | **认证与安全策略** | 测试环境设置为免密访问（`xpack.security.enabled=false`），简化排查调试与轻量化调用。 |
| **d5** | **资源配额** | pro143 约 61Gi 内存、空闲约 20Gi。为防止 OOM，ES 分配 Heap 2GiB（上限 4GiB 容器限制）；Logstash 分配 Heap 1GiB；Kibana 默认 Node.js 内存（上限 1GiB）。 |

---

## 2. 目录与端口规划

### 2.1 宿主机目录布局 (`/data/elk`)

```text
/data/elk/
├── docker-compose.yml          # Compose 编排文件
├── .env                        # 环境变量（镜像版本、内存限制等）
├── elasticsearch/
│   ├── config/
│   │   └── elasticsearch.yml   # ES 节点与集群配置
│   ├── data/                   # 数据持久化目录（chown 1000:1000）
│   └── logs/                   # 日志目录（chown 1000:1000）
├── kibana/
│   └── config/
│       └── kibana.yml          # Kibana 界面与 ES 连接配置
└── logstash/
    ├── config/
    │   └── logstash.yml        # Logstash 基础配置
    ├── pipeline/
    │   └── logstash.conf       # 管道配置（Beats 5044 输入 -> ES 9200 输出）
    └── data/                   # 队列与状态数据
```

### 2.2 端口监听与网络

| 组件 | 容器内端口 | 宿主机端口 | 协议 | 外部访问权限 / 建议 |
| :--- | :--- | :--- | :--- | :--- |
| **Elasticsearch** | 9200 | `9201` | HTTP | 仅供内网 / 本机微服务检索使用 |
| **Elasticsearch** | 9300 | `9301` | TCP | 集群节点间通讯（单节点保留） |
| **Kibana** | 5601 | `5601` | HTTP | 管理端可视化控制台（可通过安全组或反代访问） |
| **Logstash** | 5044 | `5044` | TCP | Beats（Filebeat/Metricbeat）日志上报输入 |
| **Logstash** | 9600 | `9600` | HTTP | Logstash 运行状态监控端点 |

- **Docker 网络**：创建专用桥接网络 `elk-network`。Kibana 与 Logstash 在容器网络内直接通过服务名 `http://elasticsearch:9200` 互通，不受宿主机 9201 端口映射影响。

---

## 3. 组件配置与编排规格

### 3.1 Elasticsearch (`elasticsearch.yml`)
- `cluster.name: elk-cluster`
- `node.name: elk-node-01`
- `discovery.type: single-node`
- `network.host: 0.0.0.0`
- `xpack.security.enabled: false`
- `xpack.security.http.ssl.enabled: false`
- `bootstrap.memory_lock: true`

### 3.2 Kibana (`kibana.yml`)
- `server.name: kibana`
- `server.host: "0.0.0.0"`
- `server.port: 5601`
- `elasticsearch.hosts: [ "http://elasticsearch:9200" ]`
- `i18n.locale: "zh-CN"`（中文本地化界面）

### 3.3 Logstash (`logstash.yml` & `pipeline/logstash.conf`)
- `http.host: "0.0.0.0"`
- `pipeline.workers: 2`
- 管道流程：
  - **Input**：`beats { port => 5044 }`
  - **Filter**：解析 JSON（如日志为 JSON 结构）并增加时间戳处理
  - **Output**：写入 Elasticsearch `http://elasticsearch:9200`，索引模式为 `%{[@metadata][beat]}-%{+YYYY.MM.dd}` 或回退至 `logstash-%{+YYYY.MM.dd}`

---

## 4. 落地与验证步骤

1. 在仓库 `script/deploy/elk/` 下沉淀完整的 Compose 编排模版、配置文件与部署脚本 `deploy-elk-pro143.sh`。
2. 在 `pro143` 创建对应目录并下发配置，初始化目录权限（UID 1000）。
3. 检查系统内核参数（确认 `vm.max_map_count >= 262144`）。
4. 拉取官方镜像并启动容器栈：`docker compose up -d`。
5. 验证服务健康状况：
   - `curl http://127.0.0.1:9201`：验证 ES 8.17.0 集群信息；
   - `curl -I http://127.0.0.1:5601/api/status`：验证 Kibana 服务就绪；
   - 验证 5044 端口监听以及日志索引写入链路。
