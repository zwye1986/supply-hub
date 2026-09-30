# 供应链管理端 REST（对齐前端）Implementation Plan 总览

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **禁止跳过子计划顺序。** 四份子计划各自可测，但 schema / 枚举 / 错误码必须先合入。

**Goal:** 按 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` 落地管理端 REST，供 `supplychainhubweb` 关掉 mock 后对接。本批不改前端仓库、不加价模板页面、不开票、不物理删除主体。

**Architecture:** 领域内核不动（金额 CNY 分、身份用业务 code、不按树拆账）。对外契约是名词资源 + HTTP 方法。已有 `PUT /suppliers/{code}/status` 等动词路径只保留兼容，新测试只打 REST。`BuyerProduct` / `ProjectListing` 用新表，不改造既有 `sc_buyer_listing`（履约受理仍读旧表，本批不改 `FulfillmentAcceptanceLifecycle`）。管理端合作商品 `PUBLISHED` 表示运营上架，**不**伪造缺少连接器的 `SupplierOfferVersion`（可路由仍走既有发布服务）。

**Tech Stack:** Java 21、Spring Boot 3、MyBatis Plus Lambda、Liquibase SQL changeset `021`、JUnit 5、Testcontainers MySQL 8.4、ArchUnit。权限 `supply-chain:admin`。

---

## 执行顺序（必须）

| 顺序 | 计划 | 独立可验证产物 |
| --- | --- | --- |
| 1 | [2026-09-02-admin-frontend-foundation.md](./2026-09-02-admin-frontend-foundation.md) | 枚举/错误码/Liquibase 021/定价解析/类目树与标品 SKU 查询 |
| 2 | [2026-09-02-admin-frontend-supplier-rest.md](./2026-09-02-admin-frontend-supplier-rest.md) | 供应商 REST 对齐（筛选、PATCH、5 模式多选、合作商品上架） |
| 3 | [2026-09-02-admin-frontend-buyer-rest.md](./2026-09-02-admin-frontend-buyer-rest.md) | 分销商档案、组织树、期初、分销商品与 L2 覆盖 |
| 4 | [2026-09-02-admin-frontend-project-rest.md](./2026-09-02-admin-frontend-project-rest.md) | 项目档案、CLOSED、项目商品与 L3 冻结 |

做完 4 再更新 `docs/集成测试.md` 第 5 节（foundation 计划最后一任务已含文案，实施时写入）。

---

## 环境前置

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro
```

单测（每个 Task 的 Step 2/4 都用这个，只换 `-Dtest=`）：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false -Dtest=FQCN
```

集成（Surefire 排除 `*IntegrationTest`，必须走 failsafe）：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ClassName -Dfailsafe.failIfNoSpecifiedTests=true
```

提交信息必须中文（`AGENTS.md` 第 10 条）。示例：

```bash
git commit -m "$(cat <<'EOF'
feat(supply-chain): 扩展供货模式与管理端错误码

为管理端 REST 对齐前端页面准备封闭枚举与稳定错误目录。
EOF
)"
```

---

## 实现锁定（与 09-02 规格一致，避免执行时猜测）

1. **REST：** 新路径禁止动词片段。兼容别名不删除、不扩。
2. **ENUM_NOT_OPEN：** 与现有 `SUPPLIER_ENUM_NOT_OPEN`（`1_050_001_052`）同一 `ErrorCode` 实例，只改文案为「未知枚举值」。新代码抛 `ENUM_NOT_OPEN`。
3. **供货配置：** 主表去掉 `supply_mode`，子表 `sc_supplier_supply_scope_mode`；唯一键仍是类目三元组。foundation 与 scope 写入必须同一次合入，否则 changeset 会弄坏现有 IT。
4. **合作商品上架：** `OfferStatus.PUBLISHED` 写在身份行上；`current_version_id` 仍只由 `SupplierOfferPublicationService` 在有连接器时填充。管理端改价写身份上的 `price_mode` / `draft_*` 列。
5. **L2：** 手工覆盖优先于模板指针；本批不实现加价模板发布。无模板、无覆盖时有效 L2 视为空，解析回退 L1。
6. **L1：** 新建价格版本表。本批无 L1 管理页；集成测试用 Mapper 插入。无 L1 时标品 `suggestPriceCents` 为 null；发布项目商品且三级皆空 → `PLATFORM_SETTLEMENT_PRICE_MISSING`。
7. **分销商创建：** 系统生成 `BYR_`；**不**写 `sc_buyer_tenant_binding`；`buyer_type` 仍为 `SAAS_MALL`；表单「分销商类型」落 `distributor_type`。期初走既有 `BuyerAccountOpeningService`，证据由服务端生成，`entry_key` 保持该服务已有的 `OPENING_BALANCE:{buyerId}:CNY`（与规格「ADMIN_OPENING」等价：不向前端要 evidence 文件）。编辑再带 `openingBalanceCents` → `BUYER_OPENING_ALREADY_DONE`。
8. **项目编号：** `PRJ_` + ULID。`catalog_mode` 固定 `MANAGED`。不返回 `appId`/`appKey`。
9. **履约：** 本批不改 `acceptFulfillment` 读哪张 listing。项目商品冻结价用新表 `sc_project_listing_version` 自测「改 L2 不改已发布版本」。
10. **开票：** 不映射 `/invoice`。
11. **文件：** 证件只收 `fileKey`；执照/资质改为数组（最多 5），落 `sc_supplier_file_ref` / `sc_buyer_file_ref`。
12. **类目：** 禁止 `equity`/`movie` 字符串入库；保存时校验祖先链，失败 `CATEGORY_CHAIN_INVALID`。

---

## 文件结构（跨计划）

```
aifuli-module-supply-chain-api/.../error/SupplyChainErrorCodeConstants.java
aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/
  021-admin-frontend-alignment.sql
  db.changelog-1.0.0.yaml
tradecore/profile/domain/          # 共享封闭枚举 + ClosedEnums
tradecore/supply/                  # 5 模式、scope 子表、供应商 REST 扩展
tradecore/buyer/                   # 分销商档案、组织树、项目状态机
tradecore/catalog/                 # BuyerProduct、L1/L2/L3、ProjectListing、定价解析、类目/SKU 查询
tradecore/account/                 # 仅复用 BuyerAccountOpeningService
interfaceapi/admin/
  CategoryAdminController.java
  PlatformSkuAdminController.java
  SupplierAdminController.java     # 扩展
  BuyerAdminController.java        # 新建
  ProjectAdminController.java      # 新建
```
