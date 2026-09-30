# 供应商管理端 REST Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md` 第 15 章，落地供应商档案、供货配置、合作商品草稿的管理端 REST（不含开票、不含前端）。

**Architecture:** 只扩展 `tradecore.supply`。新表只追加 Liquibase `020`。供应商结算账户独立于采购商 `sc_buyer_account`。路由仍只认 `SupplierStatus.ACTIVE`，黑名单与暂停同样不可进新路由。Admin Controller 不访问 Adapter。

**Tech Stack:** Spring Boot 3、MyBatis Plus Lambda、Liquibase SQL、JUnit 5、Testcontainers MySQL 8.4、ArchUnit。金额 CNY 分。

---

## 环境前置

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro
```

单测：`mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api,aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false`

集成：Maven profile `supply-chain-integration`（与现有 `*IntegrationTest` 相同）。

路径：Controller `@RequestMapping("/supply-chain/suppliers")`（网关 admin-api）。权限 `supply-chain:admin`。

---

## 文件结构

```
aifuli-module-supply-chain-api/.../error/SupplyChainErrorCodeConstants.java
aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/
  020-supplier-admin-profile.sql
  db.changelog-1.0.0.yaml
tradecore/supply/domain/
  SupplierStatus.java          # 增加 BLACKLIST=30
  DeliveryRegionType.java      # NATIONWIDE / DESIGNATED
  SupplyMode.java               # API_DIRECT / DROP_SHIP
  OfferPriceMode.java          # FIXED / REALTIME / DISCOUNT
tradecore/supply/application/
  SupplierProfileCommandService.java
  SupplierProfileQueryService.java
  SupplierSupplyScopeService.java
  SupplierCooperativeProductService.java
  SupplierSettlementAccountOpeningService.java
interfaceapi/admin/SupplierAdminController.java
```

既有 `SupplierConfigurationCommandService.createSupplier` 保留给连接器引导，不改签名；管理端建档走新服务（自动编号 `SPL_`）。

---

### Task 1: `SupplierStatus` 黑名单状态机

**Files:**
- Modify: `.../tradecore/supply/domain/SupplierStatus.java`
- Create: `.../test/.../tradecore/supply/domain/SupplierStatusTest.java`

- [ ] **Step 1: 写失败测试**

```java
@Test
void blacklistIsReversibleAndNotArchive() {
    assertThat(SupplierStatus.ACTIVE.canTransitionTo(SupplierStatus.BLACKLIST)).isTrue();
    assertThat(SupplierStatus.SUSPENDED.canTransitionTo(SupplierStatus.BLACKLIST)).isTrue();
    assertThat(SupplierStatus.BLACKLIST.canTransitionTo(SupplierStatus.ACTIVE)).isTrue();
    assertThat(SupplierStatus.BLACKLIST.canTransitionTo(SupplierStatus.SUSPENDED)).isTrue();
    assertThat(SupplierStatus.BLACKLIST.canTransitionTo(SupplierStatus.ARCHIVED)).isFalse();
    assertThat(SupplierStatus.ACTIVE.canTransitionTo(SupplierStatus.ARCHIVED)).isTrue();
    assertThat(SupplierStatus.fromCode((short) 30)).isEqualTo(SupplierStatus.BLACKLIST);
}
```

- [ ] **Step 2: 跑测确认失败** → 实现 `BLACKLIST((short) 30, "黑名单")` 并改 `canTransitionTo`：ACTIVE↔SUSPENDED；ACTIVE/SUSPENDED→BLACKLIST；BLACKLIST→ACTIVE/SUSPENDED；ARCHIVED 不可逆；禁止 BLACKLIST→ARCHIVED。
- [ ] **Step 3: 跑测通过**

---

### Task 2: 错误码

**Files:**
- Modify: `SupplyChainErrorCodeConstants.java` 与 `SupplyChainErrorMappingTest.java`

从 `1_050_001_048` 起追加（全部 `DO_NOT_RETRY`，除非注明）：

| 常量 | 文案 |
| --- | --- |
| `SUPPLIER_NOT_FOUND` | 供应商不存在 |
| `SUPPLIER_STATUS_CONFLICT` | 供应商状态不允许该操作 |
| `SUPPLIER_OPENING_ALREADY_DONE` | 供应商期初已入账，禁止再改余额字段 |
| `SUPPLIER_CREDIT_CODE_DUPLICATE` | 公司名称+统一社会信用代码联合重复 |
| `SUPPLIER_ENUM_NOT_OPEN` | 发货时效/结算方式/对接方式本期未开放枚举 |
| `SUPPLIER_CONTACT_REQUIRED` | 至少保留一行有效联系人 |
| `SUPPLIER_SCOPE_CONFLICT` | 同一供应商下品类/类型/分类组合重复 |
| `SUPPLIER_REGION_INVALID` | 配送区域与行政区划码不一致 |
| `SUPPLIER_OFFER_OUT_OF_SCOPE` | SKU 不在该供应商供货配置范围内 |

`RETRY_DIRECTIVES` 必须覆盖每个新 `ErrorCode`，否则 `everyDeclaredErrorCodeHasRetryDirective` 失败。

---

### Task 3: Liquibase 020

**Files:**
- Create: `020-supplier-admin-profile.sql`
- Modify: `db.changelog-1.0.0.yaml` 追加 changeset `020-supplier-admin-profile`
- Modify: `SupplyChainLiquibaseIntegrationTest`：`changeSetCount` 19→20；`tableNames` 含 `sc_supplier_contact`、`sc_supplier_supply_scope`、`sc_supplier_settlement_account`、`sc_supplier_settlement_ledger`、`sc_supplier_delivery_region`

`ALTER TABLE sc_supplier`：DROP `ck_sc_supplier_status` 后改为 `IN (10, 20, 30, 90)`；追加档案列（均可空，创建时由服务填）：

- `credit_code VARCHAR(32)` + `UNIQUE uk_sc_supplier_legal (legal_entity_name, credit_code)`（允许 credit_code NULL 的历史行；新档必填）
- `delivery_region_type VARCHAR(16)` CHECK `NULL OR IN ('NATIONWIDE','DESIGNATED')`
- `aftersales_supported TINYINT` 0/1
- `settlement_cycle_days INT` NULL，`>=1` 若非空
- `onboarded_at DATETIME(3)` 接入时间
- `cooperation_note VARCHAR(200)`
- `legal_person_name VARCHAR(64)`、`legal_person_mobile_cipher VARBINARY`、`contact_address VARCHAR(512)`
- `bank_name VARCHAR(128)`、`bank_account_cipher VARBINARY`、`bank_account_key_version VARCHAR(32)`
- 证件 fileKey 密文：`license_file_cipher`、`id_front_cipher`、`id_back_cipher`、`qualification_file_cipher` + 共用 `document_key_version`

子表（逻辑引用，无 FK）：

```sql
CREATE TABLE sc_supplier_contact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    supplier_id BIGINT NOT NULL,
    sort_no INT NOT NULL,
    contact_name VARCHAR(64) NOT NULL,
    contact_mobile VARCHAR(32) NOT NULL,
    contact_email VARCHAR(128) NULL,
    ...
    UNIQUE KEY uk_sc_supplier_contact_sort (supplier_id, sort_no)
);

CREATE TABLE sc_supplier_delivery_region (
    id BIGINT NOT NULL AUTO_INCREMENT,
    supplier_id BIGINT NOT NULL,
    adcode CHAR(6) NOT NULL,
    UNIQUE KEY uk_sc_supplier_region (supplier_id, adcode),
    CONSTRAINT ck_sc_supplier_adcode CHECK (adcode REGEXP '^[0-9]{6}$')
);

CREATE TABLE sc_supplier_supply_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    supplier_id BIGINT NOT NULL,
    category_l1_id BIGINT NOT NULL,
    category_l2_id BIGINT NOT NULL,
    category_l3_id BIGINT NOT NULL,
    supply_mode VARCHAR(32) NOT NULL,
    max_quantity BIGINT NOT NULL,
    min_quantity BIGINT NOT NULL,
    UNIQUE KEY uk_sc_supplier_scope_triple (supplier_id, category_l1_id, category_l2_id, category_l3_id),
    CONSTRAINT ck_sc_supplier_scope_mode CHECK (supply_mode IN ('API_DIRECT','DROP_SHIP')),
    CONSTRAINT ck_sc_supplier_scope_qty CHECK (min_quantity >= 1 AND max_quantity >= min_quantity)
);

CREATE TABLE sc_supplier_settlement_account (
    -- 镜像 sc_buyer_account 结构，buyer_id 改为 supplier_id
    UNIQUE KEY uk_sc_supplier_settlement_currency (supplier_id, currency)
);

CREATE TABLE sc_supplier_settlement_ledger (
    -- 镜像 sc_account_ledger，account_id 指向结算账户
    -- entry_type 本期仅 DEPOSIT
    UNIQUE KEY uk_sc_ssl_entry (account_id, entry_key)
);
```

`sc_supplier_offer` 追加草稿列（合作商品）：`supply_scope_id`、`price_mode VARCHAR(16)` CHECK `NULL OR IN ('FIXED','REALTIME','DISCOUNT')`、`draft_unit_price BIGINT`、`discount_bp INT`、`offer_supply_mode`、`draft_max_quantity`、`draft_min_quantity`。

编号前缀：`BusinessNoPrefixes` 增加 `SPL_`（供应商编号）、`SSA_`（结算账户）、`SSL_`（结算流水）。

---

### Task 4: 档案命令/查询服务 + 期初

**Files:** 上表 application + DO/Mapper

规则（规格 15.4 / 15.9）：

- 创建：生成 `SPL_`；`supplier_type=STANDARD_SUPPLIER`；`short_name=截断名称`；同一事务开结算账户（期初允许 0）+ `entry_key=OPENING_BALANCE:{supplierId}:CNY`。审计：`operatorId` + `audit_note=供应商建档期初` + `evidence_ref=supplier-opening:{supplierCode}` + SHA-256。
- 更新：请求带 `openingBalance` → `SUPPLIER_OPENING_ALREADY_DONE`。
- 联系人：剔空行后至少 1 行；姓名+手机号必填；禁止 0 行。
- `DESIGNATED` 必须 ≥1 个 adcode；`NATIONWIDE` 必须 0 条区域行。
- `shippingSlaCode` / `settlementMethod` / `integrationMode` 非空 → `SUPPLIER_ENUM_NOT_OPEN`。
- 公司名称+信用代码冲突 → `SUPPLIER_CREDIT_CODE_DUPLICATE`。
- 银行账号 / 证件 fileKey / 法人手机：`EnvelopeCryptoPort.encrypt`，AAD=`supplier:{id}:{field}`；管理端 GET **暂时明文**（见规格 15.11），不再返回 `*Masked` 字段。
- 法人身份证号：`legalPersonIdNo` ↔ `legal_person_id_no`，明文列，详情必回。
- 接入时间 JSON 名 `onboardAt`；0 / 1970 视为未填。
- 状态：条件更新 + `canTransitionTo`；批量暂停/恢复循环同一方法。
- 列表：`updated_at DESC`，默认 pageSize=20；筛选名称/编号/状态/接入时间/供货配置聚合列。

单测覆盖：状态机拒绝非法迁移、期初 0、二次余额拒绝、联系人删光拒绝、全国带 adcode 拒绝。

集成测：Liquibase 后创建供应商，查账户余额与唯一流水。

---

### Task 5: 供货配置 REST

`PUT /supply-chain/suppliers/{supplierCode}/supply-scopes` 整表替换（先删后插同一事务）。校验类目祖先链（L1.level=1 且 parent 空；L2.parent=L1；L3.parent=L2；三者 status=启用）。`GET` 返回行列表。

---

### Task 6: 合作商品草稿

`POST /supply-chain/suppliers/{supplierCode}/offers/batch-draft`：SKU 必须启用且命中唯一供货配置（按 SKU 的 SPU.primary_category 走到 L3，再匹配 scope）。已存在未 `RETIRED` Offer 拒绝。`FIXED` 价>0；`DISCOUNT` 建议价>0 且折扣 1–10000 基点（0.01%–100%）；`REALTIME` 禁止填固定价。成功 `OfferStatus.DRAFT`。上架仍走既有 `SupplierOfferPublicationService.publish`（本计划不改连接器发布前置）。

`GET` 列表按 SPU 分组字段由查询组装；移除=`RETIRED`。

---

### Task 7: Controller + 架构

`SupplierAdminController`：`GET` 分页、`GET/{code}`、`POST`、`PUT/{code}`、`PUT/{code}/status`、`PUT/{code}/status/batch`、scopes、offers。`@PreAuthorize supply-chain:admin`。入口日志打 supplierCode/status/金额分。

ArchUnit：Admin Controller 不得依赖 `..supplieradapter..`。

---

## 规格覆盖

| 规格 | Task |
| --- | --- |
| 15.1 黑名单 | 1, 4 |
| 15.2 列表 | 4 |
| 15.3–15.4 表单与期初 | 3, 4 |
| 15.5 冻结枚举 | 4 |
| 15.6 供货配置 | 5 |
| 15.7–15.8 合作商品草稿 | 6 |
| 15.10 开票 | 不做 |

完成后跑 api+server 单测与 `SupplyChainLiquibaseIntegrationTest`、新建 `SupplierAdminProfileIntegrationTest`。
