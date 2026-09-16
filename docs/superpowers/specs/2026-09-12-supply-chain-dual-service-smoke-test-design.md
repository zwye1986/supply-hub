# 供应链双服务实机联调一键自动化烟测详细设计

## 1. 背景与目标

在 2026-09-12 的实机联调中，已手动验证了独立 `SystemServerApplication`（端口 `58081`）与 `SupplyChainServerApplication`（端口 `58190`）的端到端集成链路，包括 system 菜单权限投影、跨服务 Token 校验 RPC、权限判定 RPC、商品中心与订单只读 REST 接口以及 OpenAPI 规范。

为了防止后续版本迭代造成回归，并将该流程固化为 CI/本地开发均可快速运行的自动化门禁，本设计定义**双服务实机联调一键自动化烟测脚本**（`scripts-smoke-test.sh`）。

## 2. 关键决策与设计原则

| 编号 | 决策项 | 设计方案 |
| --- | --- | --- |
| d1 | 脚本形态 | 根目录提供自包含的一键执行脚本 `scripts-smoke-test.sh`，基于 bash + curl + python3（macOS/Linux 自带），无需额外依赖。 |
| d2 | 数据库隔离 | 每次运行使用唯一时间戳与进程 PID 后缀创建独占空数据库（如 `sc_smoke_sys_<timestamp>_$$` 和 `sc_smoke_sc_<timestamp>_$$`），完全避免污染本地或测试环境数据。 |
| d3 | 基础设施探活与兜底 | 探测 MySQL（默认 `127.0.0.1:3306`）与 Redis（默认 `127.0.0.1:6379`）；若本地 Redis 未运行，自动在临时端口（如 `16379`）启动轻量级临时 Redis 实例，并在测试结束后清理。支持环境变量自由覆盖连接参数。 |
| d4 | SQL 执行时序 | system 库按序执行 `sql/mysql/ruoyi-vue-pro.sql`（基线表与用户）、`script/deploy/aifuli-test/sql/system-menu-application-schema.sql`（增加 `application_code` 列）、`script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`（37 个权限与动态菜单树）；supply-chain 库保持空库，由进程启动时 Liquibase 自动执行全量 29 个变更集。 |
| d5 | 进程编排与就绪探测 | 两个应用均未暴露 Actuator 健康端点（`/actuator/health` 返回 404/401），因此采用 TCP 端口轮询探测 + 日志关键标记识别作为服务就绪标准，超时时间可控（默认 60s）。 |
| d6 | 严密契约断言 | 登录后断言返回有效 Token；分别省略 `applicationCode` 和传入未知值请求权限接口，断言两次响应完全一致且包含 37 个供应链细粒度权限与根菜单结构；使用 Token 访问 6 个供应链接口断言业务码 0；匿名访问断言 401；请求 OpenAPI 关键 Schema 精确核对 `CategoryDetail`（11 字段，含 `productType`、`description`）与 `SkuListItem`（10 字段）的字段集合与中文说明。 |
| d7 | 无条件资源清理 | 采用 bash `trap ... EXIT INT TERM` 机制，无论测试成功、断言失败、进程异常还是人为中断，均保证立即杀死已启动的服务进程、停止临时 Redis、删除临时 MySQL 库。 |

## 3. 架构拓扑与交互链路

```text
               +-------------------------------------------+
               |          scripts-smoke-test.sh            |
               +-------------------------------------------+
                 |            |              |           |
        1. 准备独立DB/Redis   |              |           |
                 v            |              |           |
          MySQL (sys_db)      |              |           |
          MySQL (sc_db)       |              |           |
          Redis (:6379/16379) |              |           |
                              |              |           |
                     2. 启动 system-server   |           |
                              | (:58081)     |           |
                              |              |           |
                              v              |           |
                     3. 启动 supply-chain    |           |
                                 (:58190)    |           |
                                      |      |           |
  4. 登录 system-server 取得 Token ---+      |           |
  5. 校验 37 权限与菜单树 ------------------+           |
  6. 带 Token 跨进程请求供应链 6 接口 -------------------+
  7. 匿名请求 6 接口校验 401
  8. 校验 OpenAPI Schema 字段与中文说明
  9. Trap 触发：清理 Java 进程、临时 Redis、删除临时库
```

## 4. 详细执行流程与契约规则

### 4.1 基础设施初始化

1. **MySQL 数据库**：
   - 生成唯一库名：`SYSTEM_DB=sc_smoke_sys_$(date +%s)_$$`，`SC_DB=sc_smoke_sc_$(date +%s)_$$`。
   - 执行：`DROP DATABASE IF EXISTS <db>; CREATE DATABASE <db> CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
   - 对 `SYSTEM_DB` 依次执行：
     1. `sql/mysql/ruoyi-vue-pro.sql`
     2. `script/deploy/aifuli-test/sql/system-menu-application-schema.sql`
     3. `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`
   - 对 `SC_DB` 保持纯空库。

2. **Redis 实例**：
   - 首先探测 `${REDIS_HOST}:${REDIS_PORT}`（默认 `127.0.0.1:6379`）。
   - 若不可达，检测本地 `redis-server` 是否存在，若存在则使用临时目录在动态端口（如 `16379`）启动守护进程；若无本地 Redis 则检测 Docker 容器。
   - 记录是否为脚本拉起的临时实例，以便退出时清理。

### 4.2 进程启动配置与参数覆盖

1. **System Server**：
   - 类名/Jar：`aifuli-module-system-server.jar`
   - 端口：`58081`（可通过 `SYSTEM_PORT` 覆盖）
   - 参数：
     - `--server.port=${SYSTEM_PORT}`
     - `--spring.profiles.active=local`
     - `--spring.cloud.nacos.discovery.enabled=false`
     - `--spring.cloud.nacos.config.enabled=false`
     - `--spring.datasource.dynamic.datasource.master.url=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${SYSTEM_DB}?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true`
     - `--spring.datasource.dynamic.datasource.master.username=${MYSQL_USER}`
     - `--spring.datasource.dynamic.datasource.master.password=${MYSQL_PWD}`
     - `--spring.data.redis.host=${REDIS_HOST}`
     - `--spring.data.redis.port=${REDIS_PORT}`
     - `--spring.data.redis.password=${REDIS_PASSWORD}`
     - `--spring.cloud.openfeign.client.config.system-server.url=http://127.0.0.1:${SYSTEM_PORT}`

2. **Supply Chain Server**：
   - 类名/Jar：`aifuli-module-supply-chain-server.jar`
   - 端口：`58190`（可通过 `SUPPLY_CHAIN_PORT` 覆盖）
   - 参数：
     - `--server.port=${SUPPLY_CHAIN_PORT}`
     - `--spring.profiles.active=local`
     - `--spring.cloud.nacos.discovery.enabled=false`
     - `--spring.cloud.nacos.config.enabled=false`
     - `--aifuli.supply-chain.datasource.url=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${SC_DB}?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true`
     - `--aifuli.supply-chain.datasource.username=${MYSQL_USER}`
     - `--aifuli.supply-chain.datasource.password=${MYSQL_PWD}`
     - `--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver`
     - `--spring.data.redis.host=${REDIS_HOST}`
     - `--spring.data.redis.port=${REDIS_PORT}`
     - `--spring.data.redis.password=${REDIS_PASSWORD}`
     - `--spring.cloud.openfeign.client.config.system-server.url=http://127.0.0.1:${SYSTEM_PORT}`
     - `--AIFULI_SUPPLY_CHAIN_OPENAPI_ENABLED=true`
     - `--aifuli.supply-chain.openapi.enabled=true`
     - `--springdoc.api-docs.enabled=true`
     - `--knife4j.enable=true`

### 4.3 验证断言清单

1. **登录验证**：
   - `POST /admin-api/system/auth/login`，带 `tenant-id: 1`，体 `{"username":"admin","password":"admin123"}`。
   - 断言：HTTP 200，`code == 0`，`data.accessToken` 非空。

2. **权限与菜单树验证**：
   - 先请求 `GET /admin-api/system/auth/get-permission-info`，再请求 `GET /admin-api/system/auth/get-permission-info?applicationCode=ignored-by-server`，均带 Bearer Token。
   - 断言：两次 `code == 0`，且 `data` 完全一致，证明兼容参数不会参与菜单过滤或合法值校验。
   - 断言 `data.permissions` 包含全部 37 个细粒度权限：
     - `supply-chain:admin`
     - 供应商：`query`, `create`, `update`, `delete`, `update-status` (5)
     - 分销商：`query`, `create`, `update`, `delete`, `update-status` (5)
     - 项目：`query`, `create`, `update`, `delete`, `update-status` (5)
     - 订单：`query` (1)
     - 商品：`query`, `create`, `update`, `update-status`, `delete` (5)
     - 分类：`query`, `create`, `update`, `update-status`, `delete` (5)
     - 品牌：`query`, `create`, `update`, `update-status`, `delete` (5)
     - 类型：`query`, `create`, `update`, `update-status`, `delete` (5)
     - 合计 1 + 5 + 5 + 5 + 1 + 5 + 5 + 5 + 5 = 37。
   - 断言 `data.menus` 根菜单名称为 `供应链中台`，路径为 `/supplier`，包含商品中心 4 页面、订单管理、供应商管理、分销商管理、项目管理。

3. **供应链 6 大业务接口鉴权与返回值**：
   - 接口列表：
     1. `GET /admin-api/supply-chain/products/spus?pageNo=1&pageSize=10`
     2. `GET /admin-api/supply-chain/categories/management-tree`
     3. `GET /admin-api/supply-chain/brands?pageNo=1&pageSize=10`
     4. `GET /admin-api/supply-chain/product-types?pageNo=1&pageSize=10`
     5. `GET /admin-api/supply-chain/products/skus?pageNo=1&pageSize=10`
     6. `GET /admin-api/supply-chain/orders?pageNo=1&pageSize=10`
   - 认证请求（带 Bearer Token + `tenant-id: 1`）：断言 HTTP 200，`code == 0`，`data != null`。
   - 匿名请求（不带 Token）：断言业务码 `code == 401`（或 HTTP 401）。

4. **OpenAPI 关键 Schema 校验**：
   - `GET /v3/api-docs/supply-chain-admin`
   - 断言 `components.schemas.CategoryDetail`：
     - 字段必须且仅包含 11 个：`categoryCode`, `categoryName`, `productType`, `description`, `parentId`, `categoryLevel`, `sort`, `visible`, `status`, `createdAt`, `updatedAt`。
     - 严禁泄露 `id`, `version`, `createdBy`, `updatedBy`。
     - 所有字段的 `description` 必须非空。
   - 断言 `components.schemas.SkuListItem`：
     - 字段必须且仅包含 10 个：`id`, `skuCode`, `skuName`, `spuCode`, `spuName`, `sellableCategory`, `productType`, `productClassification`, `suggestPriceCents`, `marketPriceCents`。
     - 所有字段的 `description` 必须非空。

### 4.4 清理保障（Cleanup Invariants）

1. 脚本入口注册 `trap cleanup EXIT INT TERM`。
2. `cleanup` 过程：
   - 发送 `SIGTERM` 终止 `system-server` 与 `supply-chain-server`；若 5 秒内未退出则 `SIGKILL`。
   - 若由脚本拉起临时 Redis，执行 `kill` 停止并清理临时目录与 PID 文件。
   - 执行 MySQL `DROP DATABASE IF EXISTS <SYSTEM_DB>` 与 `DROP DATABASE IF EXISTS <SC_DB>`。
   - 保留本次烟测日志输出于 `target/smoke-test-logs/` 供回溯。
