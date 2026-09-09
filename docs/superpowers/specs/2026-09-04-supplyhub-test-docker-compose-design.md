# supplyhub 测试环境 Docker Compose 部署详细设计

> 日期：2026-09-04
>
> 状态：已落地（2026-09-04）。Gateway 端口为现网 **+1000**（`59080` 起）。
>
> 2026-09-07 变更：supplyhub 测试栈**移除 `pay`、`erp`、`mall`、`statistics` 四服务**，仅保留 `gateway`、`system`、`infra`、`member` 四服务。原因：supplyhub 栈不需要商城核心（product/promotion/trade）、支付、ERP、统计能力，只承担供应链管理端联调与 system/infra/member 基座。
>
> 同日后续：代码所有权迁到独立仓 `supply-hub` 后，本仓库不再构建现网 `58080`，gateway yaml **删除** mall/pay/erp/statistics 路由（收回此前「与 mall-pro 共用源码故保留路由」的约束）。现网 systemd 继续只从 `aifuli-mall-pro` 发版。详见 `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`。
>
> 前置：
> - `script/deploy/aifuli-test/测试环境部署与版本发布手册.md`（现网 systemd + Nginx）
> - `docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md`（供应链 Compose / host 网络）
> - 分支 `dev-0812-sc` 上的聚合 mall-server 与供应链管理端 REST

## 1. 背景与已确认决策

现网 `https://aifuli-test.qihaofu.cn` 的后台是 Nginx 静态站 + systemd Jar（gateway 58080 等），供应链是同机 Docker `supply-chain-server-test:58190`。需要**另外部署一套**可独立发版的测试环境，域名 `supplyhubtest.qihaofu.cn`（解析 `47.101.155.197` / pro197）。

已确认：

| 编号 | 决策 |
| --- | --- |
| d1 | 后端 Compose 跑在 **pro143**；Nginx（及日后静态站）跑在 **pro197**。 |
| d2 | 服务：gateway、system、infra、member，全部 Docker Compose（2026-09-07 移除 pay、erp、mall、statistics，supplyhub 栈不需要这些能力）。 |
| d3 | **供应链中台进程不重建、不改端口、不改库。** 经确认 `aifuli-test` **可以断供**：Config/Discovery 从 `aifuli-mall-test` 切到 `aifuli-supplyhub-test` / group `test`，加载新 yaml（含 `admin-ui.url=http://172.19.64.105:59080`）。现网 systemd gateway 不再保证能 grayLb 到供应链。 |
| d4 | 新 gateway **静态转发** `/admin-api/supply-chain/**` 到本机现有 `127.0.0.1:58190`。 |
| d5 | 新 Nacos 命名空间 + 新端口（旧端口 **+1000**，如 gateway `59080`；原先设想的 +10000 会得到非法 TCP 端口 `68080`>65535，已作废）+ 独立 MySQL/Redis/MQ 消费组/XXL-Job 执行器，与现网 systemd **完全隔离**。 |
| d6 | 本轮 **不发布管理端静态文件**；pro197 只反代 API。页面由使用方稍后自行构建上传。 |
| d7 | 不部署 `mall` / `pay` / `erp` / `statistics`（2026-09-07 确认）；product / promotion / trade 不独立部署。 |

## 2. 范围

### 2.1 本期做

- 仓库增加 `script/deploy/supplyhub-test/`：Compose、`.env.example`、Nginx 站点片段、构建与发布脚本。
- Gateway 增加供应链管理端路由（静态 URI 可由环境变量覆盖），Knife4j 聚合增加 `supply-chain-server`。
- 文档：本文 + `docs/集成测试.md` 联调步骤。
- 在 pro143 加载镜像并 `compose up`；在 pro197 增加 HTTPS server。

### 2.2 明确不做

- 不停止、不改端口、不迁移现网 `aifuli-test-*` systemd。
- 不改供应链 Compose / `.env` / 镜像 / 库。
- 不部署 `ai-fuliyun-web` / H5。
- 不把 Nacos、RDS、Redis、OSS、证书私钥写入 Git。
- 不新建 bpm / crm / ai / iot / mp / im / report / wms 等未点名服务。
- 不把新栈注册进 Nacos `aifuli-mall-test`。

## 3. 拓扑

```text
浏览器 / API 客户端
        |
        v
supplyhubtest.qihaofu.cn
  DNS A → 47.101.155.197
  内网 → 172.19.64.46   （pro197）
        |
        |  /usr/local/nginx （源码安装）
        |  HTTPS 443，通配符 *.qihaofu.cn
        |
        |  /admin-api/  /app-api/  /infra/ws/  /doc.html  /v3/api-docs  /webjars/
        |       proxy_pass http://172.19.64.105:59080
        v
pro143  公网 8.133.164.143 / 内网 172.19.64.105
        Docker Compose 项目名 supplyhub-test
        network_mode: host
        |
        +-- gateway-server          :59080
        +-- system-server           :59081
        +-- infra-server            :59082
        +-- member-server           :59087
        |
        +-- 静态路由 /admin-api/supply-chain/** → 127.0.0.1:58190
                现有容器 supply-chain-server-test
                Config/Discovery：aifuli-supplyhub-test（aifuli-mall-test 不再被该进程加载）
```

现网 systemd 继续占用 58080–58103。两套只共享宿主机和供应链进程。supplyhub 栈不部署 pay/erp/mall/statistics。迁入 `supply-hub` 仓后本仓库 gateway 删除对应路由；现网 58080 仍用 mall-pro 源码。

## 4. 端口与进程

| Compose 服务名 | 容器名 | 镜像（标签为 Git 短 SHA） | `SERVER_PORT` | 现网对照 |
| --- | --- | --- | --- | --- |
| gateway | supplyhub-gateway | `aifuli/gateway-server:<sha>-amd64` | 59080 | 58080 |
| system | supplyhub-system | `aifuli/system-server:<sha>-amd64` | 59081 | 58081 |
| infra | supplyhub-infra | `aifuli/infra-server:<sha>-amd64` | 59082 | 58082 |
| member | supplyhub-member | `aifuli/member-server:<sha>-amd64` | 59087 | 58087 |

供应链保持 `58190`，不进本 Compose。

启动顺序：system → infra → member → gateway。Compose `depends_on` 只表达顺序，健康检查通过后再认为 gateway 可对外。

## 5. Compose 运行约定

目录（pro143）：`/data/aifuli-test/supplyhub/`

- `docker-compose.yml`、`.env`（0600，不进 Git）、`logs/`。
- 仓库模板：`script/deploy/supplyhub-test/`。

约定对齐供应链测试 Compose（见旅通部署设计 §9.2），但 **4 服务共用一份 compose**：

- `network_mode: host`：Nacos Discovery IP 固定 `172.19.64.105`，端口即上表。
- `pull_policy: never`；镜像由本机 `docker load`。
- `restart: unless-stopped`，`init: true`，`security_opt: no-new-privileges:true`。
- `env_file: .env`；`SPRING_PROFILES_ACTIVE=test`。
- 日志 json-file，`max-size: 10m`，`max-file: "3"`。
- 应用文件日志：`JAVA_OPTS` 含 `-Duser.home=/data/aifuli-test/supplyhub`，避免写入现网 `/data/aifuli-test/logs/<现网应用名>`。
- 健康检查：Compose `test` 对 `127.0.0.1:$SERVER_PORT` 做 `/dev/tcp` 探测（镜像内不依赖 curl/actuator）。gateway `start_period` 不少于 60s。

### 5.1 内存限额（硬约束）

pro143 已运行现网 8 个 systemd JVM + 供应链约 6GiB heap。新栈必须限额，禁止沿用供应链 6g 基线。

| 服务 | 建议 `JAVA_OPTS` heap |
| --- | --- |
| gateway / system / infra / member | `-Xms512m -Xmx512m` |

若 `free -h` 显示启动后持续 swap 或 OOM，先降 member 为 384m，**不得**去抢现网 systemd 与供应链的内存。pay/erp/mall/statistics 已不部署，不再占用内存。

## 6. 镜像

- 平台：`linux/amd64`（`docker buildx build --platform linux/amd64 --load`）。
- 标签：`aifuli/<服务>:<git短SHA>-amd64`，禁止 `latest`。
- 已有 Dockerfile 的模块（gateway、system、infra、member）**沿用模块内现文件**，不在本期改成非 root 用户（避免扩大 diff）。Jar 名保持 `finalName`：`aifuli-gateway.jar`、`aifuli-module-*-server.jar`。
- pay / erp / mall / statistics 本期不部署，不构建镜像。

## 7. Gateway 与供应链静态路由

供应链管理端映射带 `/admin-api` 前缀（`aifuli.web.admin-api.prefix`），监听 `58190`。网关**不得 StripPrefix**，原样转发。

> 2026-09-07 说明：supplyhub 栈不部署 mall / pay / erp / statistics，命名空间 `aifuli-supplyhub-test` 内无这些服务实例。迁入独立仓 `supply-hub` 后，本仓库 `application.yaml` **删除** mall / pay / erp / statistics 路由（`grayLb://mall-server` 等）及对应 Knife4j 分组——本仓不再构建现网 `58080`。现网 systemd gateway `58080` 仍只从 `aifuli-mall-pro` 发版，路由完整性由 mall-pro 保证。详见 `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`（d6）。

仓库 `aifuli-gateway/src/main/resources/application.yaml` 增加：

```yaml
- id: supply-chain-admin-api
  uri: ${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}
  predicates:
    - Path=/admin-api/supply-chain/**
  filters:
    - RewritePath=/admin-api/supply-chain/v3/api-docs, /v3/api-docs
- id: supply-chain-app-api
  uri: ${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}
  predicates:
    - Path=/app-api/supply-chain/**
```

Knife4j `routes` 增加：

```yaml
- name: supply-chain-server
  service-name: supply-chain-server
  url: /admin-api/supply-chain/v3/api-docs
```

supplyhub 的 `.env`：

```text
AIFULI_GATEWAY_SUPPLY_CHAIN_URI=http://127.0.0.1:58190
```

现网 systemd gateway **不要**设置该变量，继续默认 `grayLb://supply-chain-server`（与供应链同命名空间）。

若新命名空间里从现网拷贝的 `gateway-server-test.yaml` 已含 `grayLb://supply-chain-server`，**必须改成** `http://127.0.0.1:58190` 或删除该段，避免与本地路由重复且指向空实例。

未点名的 bpm/crm/ai 等 `grayLb` 路由可保留在 yaml 中；无实例时仅该前缀失败，不影响已部署服务。

## 8. Nacos 与中间件隔离

- 命名空间 ID / 名称：`aifuli-supplyhub-test`（创建后把 **namespaceId** 写入 pro143 `.env` 的 Config/Discovery `NAMESPACE`）。
- Group：`test`。
- Data ID 仍为 `{spring.application.name}-test.yaml`。
- 从现网 `aifuli-mall-test` 对应 Data ID **复制**后修改，禁止两个命名空间指向同一套业务库或同一 MQ 消费组。
- 供应链 Data ID `supply-chain-server-test.yaml` 已复制到 `aifuli-supplyhub-test` / group `test`；`admin-ui.url` 为 `http://172.19.64.105:59080`。pro143 `/data/aifuli-test/supply-chain/.env` 的 `SPRING_CLOUD_NACOS_CONFIG_NAMESPACE` 与 `SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE` 均为 `aifuli-supplyhub-test`（2026-09-04，用户确认 `aifuli-test` 可断供）。旧 yaml 保留在 `aifuli-mall-test` 但不被该进程加载。
- Discovery：进程 ephemeral 注册到 `aifuli-supplyhub-test` / `172.19.64.105:58190`。切命名空间后若仍残留此前手工登记的持久实例，删除以免与 ephemeral 重复。
- Data ID 清单（group `test`）：gateway / system / infra / member / **`supply-chain-server-test.yaml`**。pay / erp / mall / statistics 已不部署，无需对应 Data ID。

必须改掉的项（Nacos 内，密钥仍只放服务器 `.env`）：

1. `server.port` 与上表一致（也可用环境变量 `SERVER_PORT` 覆盖，Nacos 与 env 冲突时以部署脚本写入的 env 为准，并在 Nacos 写明同值以免误导）。
2. 数据源 URL 的 **database 名** 全部换成 supplyhub 专用库（可同 RDS 实例）。库名建议现网库名加后缀 `_hub`，例如现网 `xxx` → `xxx_hub`。首次用结构导出/导入，不共用表。
3. Redis：独立 `database` 号或独立实例；key 前缀若可配则加 `supplyhub:`。
4. RocketMQ `consumer-group` / 实例名加 `supplyhub-` 前缀。
5. XXL-Job 执行器 AppName 加 `supplyhub-` 前缀（member 等仍部署的服务），并在调度中心单独注册。
6. `logging.file.name` 落到 `/data/aifuli-test/supplyhub/logs/...`。
7. 回调/外网域名改为 `https://supplyhubtest.qihaofu.cn`（支付、OSS 回调、微信等）。`aifuli.web.admin-ui.url` 用内网 `http://172.19.64.105:59080`，不要写现网域名或已废弃的 `48080`。

`.env.example` 列出 Nacos 地址、namespace、group、Discovery IP `172.19.64.105`、各 `SERVER_PORT`、`JAVA_OPTS`、`AIFULI_GATEWAY_SUPPLY_CHAIN_URI`。账号密码留空。

## 9. pro197 Nginx

pro197 Nginx 为 `/usr/local/nginx/sbin/nginx -c /usr/local/nginx/conf/nginx.conf`（非 yum）。落地时先看 `nginx.conf` 的 `include`，把站点文件放到实际会被加载的目录。

仓库提供 `script/deploy/supplyhub-test/nginx/supplyhubtest.qihaofu.cn.conf`：

- `listen 80` → 301 HTTPS。
- `listen 443 ssl`（pro197 为 nginx 1.13.7，不要写 `http2 on;`）；`server_name supplyhubtest.qihaofu.cn`。
- 证书：优先复用 pro197 已有 `*.qihaofu.cn`；若无则从 pro143 `/etc/nginx/certs/aifuli-test/` **拷贝私钥时权限 600**，路径写进站点文件。不得提交证书。
- `client_max_body_size 100m`。
- 不要 `upstream`/`keepalive`；`location` 直接 `proxy_pass http://172.19.64.105:59080`（与现网 `aifuli-test.conf` 在 197 上的写法一致）。
- `location ^~ /admin-api/`、`/app-api/`、`/infra/ws/` 以及 `/doc.html`、`/webjars/`、`/v3/api-docs` 均反代到 59080。
- **本期不配 `root` 静态站**。`location /` 返回简短 200 文本或 404，说明仅 API 已开通。使用方日后上传静态文件时再加 `try_files`，且不可覆盖上述 API location。

安全组：仅放行 pro197 `172.19.64.46` 访问 pro143 **59080**（及其它 59xxx 不必对公网/对 197 开放）。Gateway 在 host 网络会监听 59080；59xxx 仅本机微服务互访。

## 10. 发布流程

脚本：`script/deploy/supplyhub-test/deploy-pro143.sh`（本机执行，默认 SSH `pro143`，支持环境变量 `SUPPLYHUB_DOCKER_HOST` 覆盖）。
用法：`./deploy-pro143.sh [all|system|gateway|infra|member]`，默认全量 4 服务；指定单服务时仅打包、构建、上传并重启目标服务。

1. `git rev-parse --short HEAD` 作为镜像标签。
2. `mvn -pl` 列出目标 server 模块（全量时为 gateway、system、infra、member）`-am package -DskipTests`。
3. 在目标模块目录 `docker buildx build --platform linux/amd64 --load`。
4. `docker save` 镜像标签，gzip 后 scp 到 `/data/aifuli-test/supplyhub/`，远程 `docker load`。
5. 远程不覆盖已有 `.env` 密钥；仅当 `.env` 不存在时从 example 复制并填充配置；若已有 `.env` 则仅增量更新构建过的服务镜像标签。
6. `docker compose -p supplyhub-test --env-file .env up -d [services...]`。
7. 轮询目标容器与 gateway 59080 `/dev/tcp` 与 `docker compose ps`，超时打日志。
8. 不重启、不 `compose down` 供应链项目 `supply-chain-test`。

pro197 站点为一次性（或少次）安装：scp conf、`nginx -t`、向 master 发 reload（用绝对路径 sbin）。改后端镜像不需要 reload Nginx。

回滚：保留上一 SHA 镜像，改 `.env` 标签后 `compose up -d`。数据库不随镜像回滚。

## 11. 验证

不登录管理端页面（本轮无静态站）时：

1. `curl -I https://supplyhubtest.qihaofu.cn/admin-api/system/v3/api-docs` 经 197 到达新 gateway（可 401，不可超时、不可打到 58080 现网）。
2. 在 pro143：`ss -lntp | grep 59080` 为新容器；`58080` 仍为 systemd gateway。
3. `curl -sS http://127.0.0.1:59080/admin-api/supply-chain/v3/api-docs`（本机）应到达现有供应链（OpenAPI 未开可为 401/404 但 access 日志在 `supply-chain-server-test`，不在新 mall）。
4. Nacos `aifuli-supplyhub-test` / group `test` 出现 4 个新栈服务（59xxx）以及 `supply-chain-server` `172.19.64.105:58190`（进程 ephemeral）。`aifuli-mall-test` 中不再有该进程的发现实例（`aifuli-test` 对供应链可断供）。
5. 现网 `https://aifuli-test.qihaofu.cn/admin-api/system/...` 行为不变。

联调步骤写入 `docs/集成测试.md` 新节「supplyhub 测试环境」。

## 12. 测试门禁（仓库）

- Gateway：单测或契约断言 `application.yaml` 含 `supply-chain-admin-api`，默认 URI 为 `grayLb://supply-chain-server`。
- 部署模板：Compose 文件含 4 个服务（gateway/system/infra/member）、`network_mode: host`、不含 `supply-chain-server` 服务定义。
- 不把真实 `.env`、证书、密码加入测试。
- 不运行其它模块与供应链无关的扩大范围单测以外的全仓库测试；供应链 `*Test` 若因 gateway yaml 变更受影响则跑 gateway 相关测试。

## 13. 运维前置清单（未完成则禁止 `compose up`）

- [x] Nacos 已创建 `aifuli-supplyhub-test`（custom ID 同名），4 个业务 Data ID（gateway/system/infra/member）已按第 8 节改完；`supply-chain-server-test.yaml` 已拷入且 `admin-ui.url` 为 `http://172.19.64.105:59080`。现网供应链进程与旧命名空间配置未改。pay/erp/mall/statistics 的 Data ID 不再需要。
- [x] 专用 MySQL 库 `aifuli-mall-test_hub` 已建；从 `aifuli-mall-test` 复制结构+数据，表集合 174/174 一致（导入后约 9.3GiB vs 源 16GiB，属 InnoDB 统计差异）。账号沿用现网 RDS 账号（对 `*.*` 有权限）。
- [x] Redis / RocketMQ / XXL-Job 已在 Nacos 配置层隔离：主 Redis `database` 14→15；京东 token Redis 1→2；RocketMQ producer/consumer 加 `supplyhub-` 前缀；XXL-Job AppName 加 `supplyhub-` 前缀（原 `supplyhub-aifuli-mall-server` 为 mall 配置，2026-09-07 起 mall 不部署，该 AppName 不再注册；member 等仍部署服务的执行器 AppName 仍加前缀，任务绑定需上线后核对）。
- [x] pro143 `.env` 由 `deploy-pro143.sh` 首次生成（0600，不进 Git）；后续发布只更新镜像标签。
- [x] `free -h`：pro143 约 61Gi 内存、当时 available ≈32Gi，加上第 5.1 节 heap 可放下（仍须启动后观察）。
- [x] 安全组：197 → 143:59080（pro197 `curl` 到 `172.19.64.105:59080` 已通）。
- [x] 域名已解析；pro197 证书覆盖 `supplyhubtest.qihaofu.cn`（`install-nginx-pro197.sh` 使用已有 `*.qihaofu.cn` 证书）。

## 14. 风险

- **内存**：同机双栈是第一风险，必须限额。
- **Nacos 合并路由**：拷贝 `gateway-server-test.yaml` 时漏改供应链 URI 会导致新 gateway 在空命名空间里找 `supply-chain-server`。
- **支付/微信回调仍指向旧域名**：新库若沿用旧 Nacos 域名配置，回调会进现网。
- **供应链数据**：静态转发共用现网供应链库，新管理端一旦配好前端，供应商数据与 `aifuli-test` **同一套**。这是 d3 的后果，不是缺陷；若以后要供应链也隔离，另立设计。
