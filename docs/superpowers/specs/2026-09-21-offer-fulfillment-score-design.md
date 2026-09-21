# 合作商品履约能力评分（人工挂点）

本文把新 PRD 8.1.3.3 / 8.1.3.4 / 8.4.1.1 的稳定性、时效性评分落到 **SupplierOffer 身份**，作为路由的预备输入。供应商合作商品与商品列表履约能力弹窗读写同一行事实；本期 **不** 用分数改选供。

冲突时：字段、路径、不变量以本文为准；供应商其它 REST 仍以 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` 为准；商品中心其它 REST 仍以 `docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md` 为准；一期路由排序仍以 `docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md` s12 拆分后的口径为准。

## 1. 范围

本期做：

- 运营对「某供应商供应某平台 SKU」人工录入稳定性评分、时效性评分（整数 0–100）。
- 数据挂在 `sc_supplier_offer` 身份列，不挂供应商档案、不挂 Offer 版本、不建独立路由能力配置模块。
- 供应商详情合作商品列表回显；批量评分接口写入；商品列表履约能力弹窗读写。两侧无同步作业。

本期不做：

- 综合排序、权重、topN、`PRICE_ASC`、自动学习、从发货时效枚举推导分数。
- 把分数注入 `routing.domain` / `RoutingCandidate`，或改变 `M1_ORDER`。
- 管理端前端页面（`supplychainhubweb`）。
- 供货上限从 `draft_max_quantity` 打通到路由硬门槛（另一条输入问题）。

## 2. 领域不变量

1. 评分粒度是 **未退役 Offer**，业务上等于 SKU × 供应商。未退役 Offer 在 `(supplier_id, platform_sku_id)` 上唯一，商品侧可用 `supplierCode` 寻址。
2. 稳定性与时效性必须成对，都是 0–100 的整数。新建合作商品两列默认 **80**；80 是默认分，不是「未评分」。
3. `PUT .../offers/{offerNo}` 改价 **不碰** 评分。存量 `NULL` 由 Liquibase `043` 回填为 80。
4. 展示与写入范围 = `PUBLISHED` + `SUSPENDED`。`RETIRED` 不展示、不可写。
5. 单一写入服务 `OfferFulfillmentScoreService` 位于 `tradecore.supply`。商品侧 Controller 只调该服务，catalog 不得直接 UPDATE Offer 评分列。
6. `sc_supplier_offer_version.primary_capability_code` 是履约协议能力码，禁止复用为分数。

## 3. 存储

Liquibase `042-offer-fulfillment-score.sql` 先加可空列；`043-offer-fulfillment-score-default.sql` 回填并把列改为非空默认 80：

| 列 | 类型 | COMMENT |
| --- | --- | --- |
| `stability_score` | TINYINT NOT NULL DEFAULT 80 | 稳定性评分，人工录入，0-100，默认80 |
| `timeliness_score` | TINYINT NOT NULL DEFAULT 80 | 时效性评分，人工录入，0-100，默认80 |

CHECK：两列均 `BETWEEN 0 AND 100`。

Java：`SupplierOfferDO.stabilityScore` / `timelinessScore`，类型 `Integer`。DTO `@Schema` 必须与上表 COMMENT 同一表述。常量 `OfferFulfillmentScore.DEFAULT = 80`。

## 4. 管理端 REST

路径遵守 09-02 a12：名词资源，不用 `/batch`。网关前缀 `/admin-api`。

### 4.1 合作商品列表回显

`GET /supply-chain/suppliers/{supplierCode}/offers` 的 `OfferListItem` 增加：

- `stabilityScore`：稳定性评分，人工录入，0-100，默认80
- `timelinessScore`：时效性评分，人工录入，0-100，默认80

权限不变：`supplier:query`（含 `supply-chain:admin` 超集）。

### 4.2 供应商批量评分（PRD 8.1.3.4）

`PUT /supply-chain/suppliers/{supplierCode}/fulfillment-scores`

权限：`supplier:update`。

请求：

- `stabilityScore`（必填，0–100，请求 VO `@Min`/`@Max`）
- `timelinessScore`（必填，0–100，请求 VO `@Min`/`@Max`）
- `platformSkuCodes`（可选 `List<String>`）：省略、null 或空列表 = 覆盖该供应商全部未退役 Offer；非空 = 只覆盖这些 SKU，整批校验，任一 SKU 不存在或该供应商当前无未退役 Offer 则整单拒绝。

响应：`{ "updatedCount": n }`。供应商不存在 → `SUPPLIER_NOT_FOUND`。无未退役 Offer 且未指定 SKU 列表 → `updatedCount=0`，不报错。

### 4.3 商品履约能力弹窗（PRD 8.4.1.1）

基路径挂在 `ProductAdminController`。`GET /products/management-page` 的 `SupplierSimpleInfo` **不加分数**。

`GET /supply-chain/products/skus/{skuCode}/fulfillment-scores`

权限：`product:query`。

- SKU 不存在 → `PLATFORM_SKU_NOT_FOUND`。
- SKU 存在但无未退役 Offer → 返回头信息 + `items: []`，不 404。

响应：

- `skuCode` / `skuName` / `spuCode` / `spuName`
- `items[]`：`supplierCode`、`supplierName`、`offerNo`、`stabilityScore`、`timelinessScore`（整数，默认 80）

`PUT /supply-chain/products/skus/{skuCode}/fulfillment-scores`

权限：`product:update`。

请求：`{ "items": [{ "supplierCode", "stabilityScore", "timelinessScore" }] }`。行内分数请求 VO `@Min`/`@Max` 约束 0–100，`items` 加 `@Valid` 级联校验。

- `items` 必填且非空。
- 只更新提交行；未出现的当前合作供应商保持原值。
- `supplierCode` 对该 SKU 无未退役 Offer → `FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING`，整单回滚。
- 同一请求重复 `supplierCode` → `FULFILLMENT_SCORE_INVALID`。

响应：`{ "updatedCount": n }`。

## 5. 错误码

占用 `1_050_001_116` 起，均为 `DO_NOT_RETRY`。

| 码 | 常量 | 文案 |
| --- | --- | --- |
| 1_050_001_116 | `FULFILLMENT_SCORE_INVALID` | 稳定性评分与时效性评分必须同时给出且为 0-100 的整数 |
| 1_050_001_117 | `FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING` | 该供应商当前未合作供应此 SKU |
| 1_050_001_118 | `FULFILLMENT_SCORE_ITEMS_REQUIRED` | 履约能力评分至少提交一行 |

SKU / 供应商不存在继续用既有 `PLATFORM_SKU_NOT_FOUND` / `SUPPLIER_NOT_FOUND`。

## 6. 日志

写入口记录 `supplierCode` 或 `skuCode`、`updatedCount`、`operatorId`。高频失败按错误码聚合。禁止把完整请求体当对象打印。

## 7. 与路由的边界

Offer 身份上的分数是路由的**预备输入**。`SupplierRoutingEngine` 本期仍只按 `candidatePriority → supplierCode → offerId` 排序，原因码 `SELECTED_BY_STABLE_PRIORITY`。`routing.domain` 不得新增分数字段，不得依赖 `OfferFulfillmentScoreService`。

## 8. 测试门禁

- `*Test`：新建默认 80、分数成对校验、批量覆盖全部/子集、RETIRED 不可见不可写、改价不清分、商品侧非合作供应商拒绝、空 items 拒绝、两侧互读同一值。
- `*IntegrationTest`（profile `supply-chain-integration`）：真实 MySQL + Liquibase `042`/`043`；新建为 80；供应商 PUT 后商品 GET 一致，反向亦然。
- `*ContractTest`：`OfferListItem` 新字段 Schema；新映射 `@PreAuthorize`；DTO 注释与 DDL COMMENT 一致。
- `*ArchitectureTest`：`routing.domain` 不依赖新服务、不出现稳定性/时效分字段。
