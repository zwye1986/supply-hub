# supplyhub 测试环境 Docker Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在仓库落地 supplyhub 测试栈（4 个 Docker 服务：gateway/system/infra/member + 网关供应链静态路由 + pro197 Nginx），不改现网 systemd 与供应链容器。2026-09-07 移除 pay/erp/mall/statistics，supplyhub 栈不需要这些能力。

**Architecture:** Compose 在 pro143 使用 host 网络与 59xxx 端口，Nacos `aifuli-supplyhub-test`；gateway 通过 `AIFULI_GATEWAY_SUPPLY_CHAIN_URI` 把 `/admin-api/supply-chain/**` 转到本机 58190。pro197 Nginx 只反代 API 到 `172.19.64.105:59080`。

**Tech Stack:** Docker Compose、Spring Cloud Gateway、Nacos、Nginx、eclipse-temurin:21-jre

**Spec:** `docs/superpowers/specs/2026-09-04-supplyhub-test-docker-compose-design.md`

---

### Task 1: 契约测试（先红）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyhubDeployContractTest.java`

- [ ] 断言 gateway `application.yaml` 含 `supply-chain-admin-api`，默认 URI `grayLb://supply-chain-server`
- [ ] 断言 compose 含 4 服务（gateway/system/infra/member）、`network_mode: host`、无 `supply-chain-server` 服务
- [ ] 跑测试确认失败后再写实现

### Task 2: Gateway 路由

**Files:**
- Modify: `aifuli-gateway/src/main/resources/application.yaml`

- [ ] 增加 supply-chain admin/app 路由与 Knife4j 条目
- [ ] 再跑 Task 1 相关断言至绿灯

### Task 3: Compose 模板

**Files:**
- Create: `script/deploy/supplyhub-test/docker-compose.yml`
- Create: `script/deploy/supplyhub-test/.env.example`
- Create: `script/deploy/supplyhub-test/nginx/supplyhubtest.qihaofu.cn.conf`
- Create: `script/deploy/supplyhub-test/README.md`
- Create: `script/deploy/supplyhub-test/deploy-pro143.sh`

> 2026-09-07：不创建 `aifuli-module-mall/aifuli-module-mall-server/Dockerfile`，mall 不部署。

- [ ] 跑 Task 1 compose 断言至绿灯

### Task 4: 联调文档

**Files:**
- Modify: `docs/集成测试.md`

- [ ] 增加 supplyhub 联调节

### Task 5: 发布到 pro143 / Nginx 到 pro197

- [x] 构建 4 镜像（gateway/system/infra/member）、scp、compose up（不 down 供应链）
- [x] 在 pro197 `vhost/` 安装站点并 reload（不要重复 `map`）
- [x] 验证 59080 与现网 58080 并存
