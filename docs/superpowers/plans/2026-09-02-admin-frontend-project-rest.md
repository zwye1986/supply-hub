# 项目管理端 REST Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 必须先完成 [foundation](./2026-09-02-admin-frontend-foundation.md) 与 [buyer](./2026-09-02-admin-frontend-buyer-rest.md)（项目 SKU 必须已是 BuyerProduct）。

**Goal:** 新建 `/supply-chain/projects`：档案、CLOSED 状态机、归属冻结、仅 DISTRIBUTOR 结算、项目商品与 L3 冻结版本。

**Architecture:** 命令在 `tradecore.buyer`（项目档案）与 `tradecore.catalog`（ProjectListing）。发布项目商品时在同一本地事务写入 `sc_project_listing_version` 并切换 `current_version_id`。改 L2 覆盖不 UPDATE 已有 version 行。本批不改 `FulfillmentAcceptanceLifecycle`、不返回 app 密钥、不建 `GET /buyers/{code}/projects`。

**Tech Stack:** 见 [总览](./2026-09-02-admin-frontend-rest.md)。

---

## 文件结构

```
tradecore/buyer/application/ProjectAdminCommandService.java
tradecore/buyer/application/ProjectAdminQueryService.java
tradecore/catalog/application/ProjectListingCommandService.java
tradecore/catalog/application/ProjectListingQueryService.java
interfaceapi/admin/ProjectAdminController.java
tests: ProjectAdminCommandServiceTest, ProjectListingCommandServiceTest,
       ProjectAdminRestContractTest, ProjectAdminIntegrationTest
```

`BuyerProjectAccessResolver` 已拒绝非 ACTIVE，CLOSED 项目不会被只读入口当成启用。无需改 resolver。黑名单 Buyer：项目创建/改归属（仅草稿）时若 Buyer 不是 ACTIVE → `BUYER_STATUS_CONFLICT`。

---

### Task 1: 项目档案状态与归属

**Files:**
- Create: `ProjectAdminCommandService.java`
- Create: `ProjectAdminCommandServiceTest.java`

- [ ] **Step 1: 单测**

```java
@Test
void draftMayChangeBuyerAndActiveMayNot() {
    // DRAFT update buyerCode ok
    // ACTIVE update buyerCode → PROJECT_BUYER_IMMUTABLE
    // SUSPENDED/CLOSED 同样不可改
}

@Test
void platformSettlementRejected() {
    assertThatThrownBy(() -> service.create(cmdWithParty("PLATFORM")))
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED.getCode());
}

@Test
void statusMachineUsesClosed() {
    // DRAFT → CLOSED 失败 PROJECT_STATUS_INVALID
    // ACTIVE → CLOSED 成功
    // CLOSED → ACTIVE 成功
}

@Test
void createAllowsDraftOrActiveAndGeneratesPrjCode() {
    when(businessNoGenerator.next(BusinessNoPrefixes.PRJ)).thenReturn("PRJ_TESTCODE000000000000001");
    // body status null → DRAFT
    // status ACTIVE → 10，Buyer 必须 ACTIVE 非黑名单
}

@Test
void endBeforeStartRejected() {
    assertThatThrownBy(() -> service.create(cmdPeriod(endBeforeStart)))
            .extracting(ex -> ((ServiceException) ex).getCode())
            .isEqualTo(PROJECT_PERIOD_INVALID.getCode());
}

@Test
void blacklistedBuyerCannotOwnNewProject() {
    // buyer BLACKLIST → BUYER_STATUS_CONFLICT
}
```

- [ ] **Step 2: 实现**

`catalog_mode` 恒 `MANAGED`。`settlementPartyType` 缺省 `DISTRIBUTOR`；`PLATFORM` 或其它 → `PROJECT_PLATFORM_SETTLEMENT_NOT_ENABLED` 或 `ENUM_NOT_OPEN`（未知走 ENUM，PLATFORM 走 063）。

`projectType` 用 `ClosedEnums.parse`。描述 ≤500（VO `@Size`）。

找不到项目 → `PROJECT_NOT_FOUND`。找不到 Buyer → `BUYER_PROFILE_NOT_FOUND`。

PATCH `{status}` 调 `BuyerProjectStatus.canTransitionTo`，页面允许 `ACTIVE|SUSPENDED|CLOSED`；尝试 `ARCHIVED` 也走状态机（页面不暴露即可，后端仍允许 SUSPENDED→ARCHIVED）。

日志：入口 `projectName,buyerCode,status,operatorId`。

- [ ] **Step 3: 跑测通过并 Commit**（`feat(supply-chain): 项目档案状态机与结算方校验`）

---

### Task 2: 项目查询 + Controller

**Files:**
- Create: `ProjectAdminQueryService.java`
- Create: `ProjectAdminController.java`
- Create: `ProjectAdminRestContractTest.java`

- [ ] **Step 1: Contract** 根路径 `/supply-chain/projects`；无 `invoice`/`create`/`page`/`appKey` 字段出现在详情 record 的 JSON（`assertThat(json).doesNotContain("appKey","appId")`）。

- [ ] **Step 2: API**

| HTTP | 路径 | 行为 |
| --- | --- | --- |
| GET | `/supply-chain/projects` | 筛选 `projectName,projectCode,status[],buyerCode,projectType[],periodFrom,periodTo`；`view=simple` |
| GET | `/{projectCode}` | 详情；只读 `skuCount`,`projectPriceSkuCount`（当前 listing 中 `settlement_price_level=L3` 的数量） |
| POST | `/` | 返回 `projectCode` |
| PUT | `/{projectCode}` | 整档；非草稿改 `buyerCode` 失败 |
| PATCH | `/{projectCode}` | `{status}` |

列表排序 `updatedAt DESC`。详情含 `id`。

不要 `GET /buyers/{code}/projects`。

- [ ] **Step 3: Commit**（`feat(supply-chain): 提供项目管理端REST档案接口`）

---

### Task 3: 项目商品必须已是分销商品，并冻结价

**Files:**
- Create: `ProjectListingCommandService.java`
- Create: `ProjectListingCommandServiceTest.java`

- [ ] **Step 1: 单测**

```java
@Test
void rejectsSkuWithoutLiveBuyerProduct() {
    // buyerProductMapper 返回 null 或 status=90
    // → PROJECT_SKU_NOT_IN_BUYER_CATALOG
}

@Test
void defaultFollowL2FreezesResolvedPriceWithoutWritingL3() {
    // L1=1000, 无覆盖无模板 → 冻结 1000, level L1, source INHERITED, l3_adjust_mode FOLLOW_L2
    // insert listing status=10, version_no=1, current_version_id 指向新行
}

@Test
void customL3FreezesOverrideAndDoesNotUpdatePreviousVersion() {
    // 先发布 FOLLOW_L2 version 1
    // PATCH CUSTOM → insert version 2, 改 current 指针
    // select version 1 单价不变
}

@Test
void changingL2OverrideDoesNotUpdateFrozenListingVersion() {
    // 已有 listing version 冻结 1000
    // 调 BuyerProductCommandService 改 L2
    // listing version 行 buyer_settlement_unit_price 仍 1000
}
```

无 L1 且无 L2 → `PLATFORM_SETTLEMENT_PRICE_MISSING`。

`applyL3(FOLLOW_L2)` 返回 null，然后 `resolve(null, l2o, l2t, l1)` 得到冻结值。`EQUAL_L1`/`CUSTOM` 得到 L3 分，`price_source=PROJECT_OVERRIDE`，`settlement_price_level=L3`。

编号 `PLT_`。POST body `{ items: [ { platformSkuCode 或 buyerProductCode, l3AdjustMode, percent, amountCents } ] }`。锁定：入参用 `platformSkuCode`，服务内解析为该项目所属 Buyer 的有效 BuyerProduct。

DELETE：listing `status=90`，不删 version。

- [ ] **Step 2: Controller**

```java
@GetMapping("/{projectCode}/products")
@PostMapping("/{projectCode}/products")
@PatchMapping("/{projectCode}/products/{listingCode}")
@DeleteMapping("/{projectCode}/products/{listingCode}")
```

列表列：`suggestPriceCents, distPriceCents, projectPriceCents, finalPriceCents, priceSource, projectPriceCovered`。`finalPriceCents` = 当前冻结版本单价（已发布）。`distPriceCents` = 当前有效 L2（覆盖或模板，可与冻结值不同，用于展示「现价」）。`projectPriceCovered` = 当前版本 level 是否 L3。

- [ ] **Step 3: Commit**（`feat(supply-chain): 项目商品必须来自分销目录并冻结结算价`）

---

### Task 4: 集成测试

**Files:**
- Create: `ProjectAdminIntegrationTest.java`（同 Buyer IT 的 Spring+MySQL 模式，`@Import` 项目与分销商品服务、定价解析、目录命令）

- [ ] **Step 1: 场景**

```java
@Test
void projectSkuRequiresBuyerProductAndFreezeIgnoresLaterL2() {
    // 建 Buyer 期初、L1=9800、BuyerProduct FOLLOW_TEMPLATE
    // POST project products 无 BuyerProduct 的其它 SKU → 065
    // POST 已有分销商品 → listing PUBLISHED，冻结 9800
    // PATCH BuyerProduct CUSTOM L2=8500
    // 再读 listing 当前版本仍 9800
    // PATCH listing CUSTOM L3 → 新版本，指针切换，旧版本行仍 9800
}

@Test
void cannotChangeBuyerAfterStart() {
    // create ACTIVE project
    // PUT 换 buyerCode → 062
}
```

种子类目/SKU 复制供应商 IT 的 `seedCatalog`。L1 用 `PlatformSettlementPriceMapper` + Version insert，再把 identity `current_version_id` 指过去。

- [ ] **Step 2:**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ProjectAdminIntegrationTest,BuyerAdminIntegrationTest,SupplierAdminProfileIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=true
```

Expected: 全绿。

- [ ] **Step 3: Commit**（`test(supply-chain): 覆盖项目商品冻结价与归属不可变集成路径`）

---

### Task 5: SKU 池 `projectCode` 过滤回归 + 架构 + 文档

- [ ] **Step 1:** `PlatformSkuQueryServiceTest` 增加：有 BuyerProduct 且无项目 listing 的 SKU 在 `projectCode` 过滤中出现；无 BuyerProduct 的不出现。

- [ ] **Step 2:** ArchUnit 已禁止 admin→adapter、catalog→ledger。再跑：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration \
  -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=true
```

Expected: 单测全绿；集成 profile 全绿（含既有 instore IT，changeset=21）。

- [ ] **Step 3:** 确认 `docs/集成测试.md` 第 5 节已在 foundation Task 13 更新。若本计划有新的手工验收步骤，追加：

```
管理端项目：创建草稿→启动→添加已存在的分销商品→改 L2 后项目列表冻结价不变→结束→恢复。
```

- [ ] **Step 4: Commit** 若有测试/文档改动。

---

## 四份计划合入后的规格对照

| 规格 | 落点 |
| --- | --- |
| §3 REST 名词路径 | 各 Controller + ContractTest |
| §4.1–4.4 枚举/类目/SKU | foundation + supplier/buyer/project 过滤 |
| §5 供应商 | supplier 计划 |
| §6 分销商/树/L2 | buyer 计划 |
| §7 项目/CLOSED/L3 | 本计划 |
| §8 定价 | SettlementPriceResolver + listing 冻结 |
| §9 错误码 | foundation Task 2 |
| §10 测试门禁 | 各 *Test / IT / Contract / ArchUnit |
| §11 前端 | 不改仓库；文档第 11 章对照 |
| 开票/物理删除/按树拆账/履约读新 listing | 明确不做 |
