# 供应商履约配置与 Offer 版本发布（管理端）

本文设计「供应商履约配置 / Offer 版本发布」管理端能力：在 `supplychainhubweb` 供应商详情抽屉（`SupplierManageDetail`）新增「履约配置」Tab，串起后端 `/supply-chain/supplier-config` 这条纯写链路，让运营能把一条「合作商品」（`sc_supplier_offer`）真正发布成**可履约**的供给版本（`sc_supplier_offer_version`，携带供应商外部商品编码 `externalSkuCode`），从而闭合当前「合作商品新增即 PUBLISHED、但 `current_version_id` 为空、路由直接跳过、无法履约」的缺口。

履约执行侧读取 `externalSkuCode` 的链路（不在本文改动范围，仅作为约束背景）：路由 `SupplierRoutingApplicationService.resolveOffer` 要求 `offer.status==PUBLISHED 且 current_version_id!=null` 并加载版本行（`routing/application/SupplierRoutingApplicationService.java:385`）→ 受理把 `offerVersionId` 冻结进 `sc_b_order`（`border/fulfillment/application/FulfillmentAcceptanceLifecycle.java:614`）→ 执行 `FulfillmentOperationExecuteHandler.java:120` 查回版本 → `FulfillmentOperationTicketFactory.java:51` 注入 `OperationTicket.externalSkuCode` → `supplieradapter/qnm/QnmSupplierAdapter.java:123` 作为供应商 `productId` 发出，为空则 `:506` 抛错。

写契约、请求体校验、发布事务、GET 投影字段以本文为准。金额单位沿用 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#a2-身份与金额`（路径身份用业务编码、金额为 CNY 分）。合作商品商业身份维护仍以 `SupplierAdminController`（`/supply-chain/suppliers/{supplierCode}/offers`）为准，本文不改其写语义，仅新增「履约就绪」投影与发布入口。

## 0. 已确认决策

| 编号 | 决策 |
| --- | --- |
| d1 | 覆盖范围＝**完整配置向导 + 合作商品缺口修复**。向导串起连接器（草稿→校验→发布）、库存来源（创建→启用）、Offer（草稿→发布版本）；并修复「合作商品新增即 PUBLISHED 却不可履约」的语义缺口。 |
| d2 | `connectorVersionId` / `inventorySourceNo` / 能力码等候选数据当前**后端无 GET 接口**，本期**新增只读查询端点**（§5.1），前端下拉正常选择，不采用「透传上一步写入返回值」或「手填 ID」。 |
| d3 | 界面落点＝`SupplierManageDetail` 抽屉**新增「履约配置」Tab**（`views/supplier/manage/detail/`），不新建独立路由页。Tab 内所有写操作按钮用 `checkPermi(['supply-chain:admin'])` 控制显隐。 |
| d4 | 交付物＝本 spec 设计文档；编码在本文评审通过后进行（遵循仓库「设计先行」规则）。 |
| d5 | 权限门槛统一到 `supply-chain:admin`（`/supplier-config` 类级 `@PreAuthorize(ADMIN_EXPRESSION)`）。拥有 `supply-chain:supplier:update`（可编辑合作商品）**不足以**发布供给版本。 |
| d6 | 「合作商品缺口修复」不采用「新增即写版本行」——因为合作商品表单不采集 `externalSkuCode`/连接器/库存来源，无法在新增时构造合法版本。改为：合作商品列表新增**履约就绪标识**（是否有 `current_version_id`），并提供跳转到「履约配置」Tab 发布的入口（§5.2）。 |

## 1. 范围

本期做：

- 后端在 `SupplierConfigurationAdminController`（`/supply-chain/supplier-config`）新增只读 GET 端点：连接器及当前已发布版本、库存来源、Offer 列表（含 `currentVersionId`/`version`/`status`）、Offer 版本历史、以及一个「发布表单元数据」聚合端点（能力码与各封闭枚举合法值）。
- 后端在合作商品列表投影 `OfferListItem` 增加履约就绪字段（是否已发布可履约版本），不改写语义。
- 前端新增 `src/api/supplier/config/`（`index.ts` + `types.ts`），封装 supplier-config 读写接口。
- 前端在 `SupplierManageDetail` 新增「履约配置」Tab：`FulfillmentConfig.vue`，内含分步向导（连接器 / 库存来源 / Offer 发布）与 Offer 版本发布表单弹窗 `OfferPublishDialog.vue`。
- 前端在合作商品 Tab（`ProductList.vue`）增加「履约就绪」列与「发布履约配置」入口，跳转到履约配置 Tab 并预选该 Offer。
- 更新 `docs/集成测试.md`：新增「合作商品→发布供给版本→路由命中→履约下单携带 externalSkuCode」的联调场景与验证点。

本期不做：

- 不改履约执行链路（Ticket / Adapter / 路由引擎）。
- 不改 `SupplierCooperativeProductService.insertPublished` 的「新增即 PUBLISHED」商业语义（仅补投影字段）。
- 连接器错误码映射（`/connectors/error-mappings`）、QNM 状态映射（`/connectors/qnm-status-mappings`）的可视化编辑（本期向导仅调用，不做映射行编辑器；一期奇那梦映射由 `qnm-status-mappings` 一次性初始化）。
- `fulfillmentConfigJson` 的动态 Schema 表单：奇那梦一期固定 `{}`，前端只读展示不可编辑（§7.4）。
- 凭证槽（credential-slots）的列表查询与可视化新建：本期连接器草稿步骤仍需运维提供 `credentialSlotNo`（§11 TODO）。
- 多适配器类型：一期仅 `QNM_GAME_ORDER_V1`。

## 2. 领域不变量

1. 供给版本（`sc_supplier_offer_version`）一经发布**不可变**；改条款＝发布新版本（`version_no+1`），旧版本保留。
2. 一条 Offer **可履约** 当且仅当 `status==PUBLISHED 且 current_version_id!=null` 且该版本在 `now` 落在 `[valid_from, valid_to)` 内。合作商品「上架」只保证 `status==PUBLISHED`，不保证可履约。
3. `publish` 是设置 `current_version_id` 的**唯一**入口（`SupplierOfferPublicationService.publish` 194-201 行）；任何其它路径都不得写入 `current_version_id`。
4. `externalSkuCode` 在同一 connector 下唯一：`sc_supplier_external_sku_binding` 唯一键 `uk_sc_external_sku_connector (connector_id, external_sku_code)`；冲突时业务拒绝「External SKU is already bound to another offer」。
5. `primaryCapabilityCode` 必须是封闭枚举 `Capability` 之一，且**必须匹配平台 SKU 的 `fulfillmentType`**（§8 表），并在所选连接器版本上 `enabled=1`。
6. 执行能力联动：`completionMode∈{QUERY,QUERY_OR_CALLBACK}` 或 `unknownResultPolicy==QUERY_ORDER` → 连接器版本必须启用 `QUERY_ORDER`；`completionMode∈{CALLBACK,QUERY_OR_CALLBACK}` 或 `unknownResultPolicy==WAIT_CALLBACK` → 必须启用 `RECEIVE_CALLBACK`。
7. 跨字段：`unknownResultPolicy==REPLAY_SAME_KEY` 要求 `createIdempotencyMode==REPLAYABLE_RESULT`。
8. 连接器版本必须是该 supplier 的**当前** `PUBLISHED` 版本（`connector.current_version_id==connectorVersionId`）；库存来源必须 `ACTIVE`+`REMOTE_ON_DEMAND` 且 supplier/platformSku 匹配。
9. 乐观锁：`publish` 要求 `expectedOfferVersion==offer.version`；新草稿/未发布合作商品传 `0`，每成功发布一次聚合 `version+1`，再次发布须带新 `version`。
10. 金额 `supplierSettlementUnitPrice` 单位为 CNY 分、`>=0`、`currency` 固定 `CNY`（DDL `ck_sc_offer_price`/`ck_sc_offer_currency`）。
11. `fulfillmentConfigJson` 一期必须是字符串 `"{}"`（`@NotBlank`，非空 JSON object 会被 `QnmGameOrderV1Metadata.validateOfferConfig` 拒绝）。
12. DTO `@Schema` 与前端字段注释必须与 DDL `COMMENT` 语义一致（同一业务含义同一表述）。

## 3. 术语与三层编码

| 层 | 字段 | 表 | 由谁录入 |
| --- | --- | --- | --- |
| 采购商侧 | `buyerSkuCode` | `sc_buyer_listing` | 外部下单请求 |
| 平台侧 | `platformSkuCode` / `platformSkuId` | `sc_platform_sku` | 商品中心 |
| 供应商侧 | **`externalSkuCode`** | `sc_supplier_offer_version` | **本文的发布表单** |

## 4. 数据流（发布一条可履约供给）

```
合作商品新增(SupplierAdminController, 已有)
  → sc_supplier_offer{status=PUBLISHED, version=0, current_version_id=null}  ← 不可履约
履约配置 Tab(本文新增)
  1) 连接器：drafts → validate → publish    → 得 connectorVersionId(PUBLISHED, 当前版本)
  2) 库存来源：remote-on-demand → activate  → 得 inventorySourceNo(ACTIVE, REMOTE_ON_DEMAND)
  3) Offer 发布：POST /offers/publish(offerNo=合作商品号, expectedOfferVersion=0,
        externalSkuCode, connectorVersionId, inventorySourceNo, 商业条款, 能力/模式, "{}", 有效期)
        → 插入 sc_supplier_offer_version, upsert external_sku_binding,
          更新 offer{current_version_id=新版本, status=PUBLISHED, version=1}   ← 可履约
路由命中(resolveOffer) → B 单冻结 offerVersionId → 执行取 externalSkuCode → QNM productId
```

说明：合作商品已由 `SupplierAdminController` 建好（`offerNo`、`platformSkuCode`、`status=PUBLISHED`、`version=0`），因此履约配置 Tab 的 Offer 发布步骤**直接以合作商品的 `offerNo` 为目标**，不再走 supplier-config 的 `POST /offers`（那条会新建 `status=DRAFT` 的第二条 offer，与合作商品重复）。`POST /offers`（createOffer）仅保留给「无合作商品的纯配置态」场景，本期向导默认不触发（§11 TODO 记录二选一）。

## 5. 后端改动

### 5.1 新增只读 GET 端点（`SupplierConfigurationAdminController`，类级 `@PreAuthorize(ADMIN_EXPRESSION)`）

控制器当前只 import 了 `PostMapping`/`PutMapping`，需补 `GetMapping`/`RequestParam`/`PathVariable`。所有响应包 `CommonResult<T>`，返回投影 VO（不暴露 DO）。金额字段 CNY 分。

| # | 方法 | 路径 | 用途 / 查询参数 | 响应 VO |
| --- | --- | --- | --- | --- |
| G1 | GET | `/supply-chain/supplier-config/connectors` | `supplierCode`（必填） | `List<ConnectorOptionView>`：`connectorNo, connectorCode, adapterType, status, currentVersionId, currentVersionNo, enabledCapabilities(List<String>)` |
| G2 | GET | `/supply-chain/supplier-config/inventory-sources` | `supplierCode`（必填）、`platformSkuCode`（可选，过滤） | `List<InventorySourceOptionView>`：`sourceNo, externalSourceCode, sourceType, status, platformSkuCode, supplierCode` |
| G3 | GET | `/supply-chain/supplier-config/offers` | `supplierCode`（必填）、`platformSkuCode`（可选） | `List<ConfigOfferView>`：`offerNo, offerCode, platformSkuCode, platformSkuName, fulfillmentType, status, version, currentVersionId, fulfillmentReady(boolean), externalSkuCode(当前版本，可空)` |
| G4 | GET | `/supply-chain/supplier-config/offers/{offerNo}/versions` | 路径 `offerNo` | `List<OfferVersionView>`：版本历史，字段对齐 §7.4 表单 + `versionNo, publishedAt, validFrom, validTo` |
| G5 | GET | `/supply-chain/supplier-config/offer-publish-options` | 无 | `OfferPublishOptionsView`：一次性返回所有封闭枚举合法值与能力码，供表单渲染，避免前后端漂移（§5.3） |

设计要点：
- G1 的 `enabledCapabilities` 来自连接器版本能力表（`connectorVersion` 上 `enabled=1` 的 `Capability`），前端据此做不变量 5/6 的**前置联动校验**（禁用不合法选项），最终以后端 `publish` 校验为准。
- G3 的 `fulfillmentReady = (status==PUBLISHED && currentVersionId!=null)`；`fulfillmentType` 用于前端预判 `primaryCapabilityCode`（§8）。
- 投影 VO 定义为控制器内部静态类（与现有 `CreateXxxReqVO` 同风格），`@Schema` 描述与 DDL COMMENT 对齐。
- 分页：G1/G2/G3 一期数据量小，返回全量 `List`；若后续超阈值再补 `PageResult`（§11 TODO）。

### 5.2 合作商品缺口修复（投影 + 入口，不改写语义）

- `SupplierAdminController.listOffers` 返回的 `OfferListItem` 增加只读字段：
  - `fulfillmentReady: boolean`（`current_version_id != null`）
  - `currentVersionId: Long`（可空）
  - `externalSkuCode: String`（可空，取当前版本）
  由 `SupplierCooperativeProductService.toListItem(offer)` 关联 `offer.currentVersionId` → 版本行填充。
- **不改** `insertPublished`（`SupplierCooperativeProductService.java:244-280`）的「新增即 PUBLISHED、`version=0`、不写 `current_version_id`」行为（决策 d6）。
- 前端 `ProductList.vue` 据此渲染「履约就绪」列与「发布履约配置」按钮（§7.5）。

> 备注：`insertPublished` 产出的 offer 与 supplier-config `createOffer` 产出的 offer 是**同一张表的不同入口**。本期统一以合作商品入口建 offer、以 supplier-config `publish` 发布版本，`createOffer` 不在向导主流程使用。

### 5.3 发布表单元数据聚合端点 G5 的响应结构

```
OfferPublishOptionsView {
  capabilities: [{code, name, description}]        // Capability 枚举全集(10)，含中文名
  completionModes: [{code, name}]                  // SYNCHRONOUS/QUERY/CALLBACK/QUERY_OR_CALLBACK
  createIdempotencyModes: [{code, name}]           // NONE/DEDUP_ONLY/REPLAYABLE_RESULT
  unknownResultPolicies: [{code, name}]            // QUERY_ORDER/WAIT_CALLBACK/REPLAY_SAME_KEY/MANUAL
  partialDeliveryModes: [{code, name}]             // FORBIDDEN/ALLOWED
  resultMultiplicities: [{code, name}]             // NONE/SINGLE/MULTIPLE
  fulfillmentConfigSchemaVersion: 1                // 服务端生成，前端只读
  fulfillmentConfigJsonFixed: "{}"                 // 一期固定值，前端只读回填
}
```

后端这些合法值当前散落在 `SupplierOfferPublicationService` 的硬编码 `Set`（56-63 行）与 `Capability` 枚举。G5 实现须**复用同一份来源**（把 Set 提为可枚举的常量或直接从枚举/元数据派生），禁止在前端和 G5 各写一份导致漂移。`name` 中文取 DDL COMMENT 里的中文（如 `SYNCHRONOUS同步`）。

## 6. REST 写契约（复用现有端点，不改）

向导调用以下**已存在**的写端点（`SupplierConfigurationAdminController`），本文仅明确前端调用顺序与字段来源，不改其契约：

1. `POST /connectors/drafts`（`CreateConnectorDraftReqVO`）→ `DraftConnectorResult{connectorNo, connectorVersionId, versionNo}`
2. `POST /connectors/qnm-status-mappings`（`InitializeQnmMappingsReqVO{connectorVersionId}`）→ 写入条数（一期奇那梦必调）
3. `POST /connectors/error-mappings`（`AddErrorMappingReqVO`，可选，逐条）
4. `POST /connectors/validate`（`ValidateConnectorReqVO{connectorVersionId, capabilities[], validMinutes, reason}`）→ `ValidationResult`
5. `POST /connectors/publish`（`PublishConnectorReqVO{connectorNo, connectorVersionId, expectedConnectorVersion, reason}`）→ `PublishResult{connectorVersionId,...}`
6. `POST /inventory-sources/remote-on-demand`（`CreateInventorySourceReqVO{supplierCode, platformSkuCode, externalSourceCode}`）→ `sourceNo`
7. `PUT /inventory-sources/activate`（`ActivateInventorySourceReqVO{sourceNo, expectedVersion}`）
8. `POST /offers/publish`（`PublishOfferReqVO`，§7.4 逐字段）→ `PublishResult{offerNo, offerVersionId, offerVersionNo, aggregateVersion}`

前置：供应商主体（`POST /suppliers`）与凭证槽（`POST /credential-slots`）本期假定已存在（向导第一步只做「选择已有供应商」+ 连接器 `credentialSlotNo` 手填/下拉，见 §11 TODO）。

## 7. 前端设计

### 7.1 落点与 Tab 注册

`views/supplier/manage/detail/index.vue` 现有 `el-tabs`（`basic`/`invoice`/`supply`/`product`，L96-312）。新增第 5 个 Tab：

```
<el-tab-pane label="履约配置" name="fulfillment" lazy v-if="supplierCode">
  <FulfillmentConfig ref="fulfillmentConfigRef" :supplier-code="supplierCode" @success="getDetail" />
</el-tab-pane>
```

`handleTabChange`（L448）切到 `fulfillment` 时 `nextTick` 后 `fulfillmentConfigRef.value?.reload?.()`（与 `product` Tab 同款）。

### 7.2 API 模块 `src/api/supplier/config/`

按 `src/api/supplier/manage/` 约定：`index.ts`（`export const SupplierConfigApi = {...}` + re-export types）+ `types.ts`（`interface` + JSDoc + `Cents` 字段注明 CNY 分）。`import request from '@/config/axios'`，URL 前缀 `/supply-chain/supplier-config/...`。每个方法 JSDoc 注明后端路由。示例：

```ts
/** 连接器候选：GET /supply-chain/supplier-config/connectors */
getConnectors: async (supplierCode: string) =>
  request.get<ConnectorOptionView[]>({ url: '/supply-chain/supplier-config/connectors', params: { supplierCode } }),
/** 发布供给版本：POST /supply-chain/supplier-config/offers/publish */
publishOffer: async (data: PublishOfferReqVO) =>
  request.post<PublishResult>({ url: '/supply-chain/supplier-config/offers/publish', data }),
```

权限常量（沿用 `rechargeFrequency.ts` 的 `as const` 风格，放 `config/index.ts`）：

```ts
export const SUPPLIER_CONFIG_PERMISSIONS = { admin: 'supply-chain:admin' } as const
```

### 7.3 `FulfillmentConfig.vue` 结构

顶部 `el-steps`（连接器 → 库存来源 → Offer 发布），下方对应卡片/表格。三块均可独立操作（非强制线性），因为连接器/库存来源可复用、Offer 可多条：

- **连接器区**：`getConnectors(supplierCode)` 列表；「新建连接器」按钮打开 `ConnectorWizardDialog`（草稿→QNM映射→校验→发布，字段见 §6.1-6.5）。仅 `supply-chain:admin` 可见。
- **库存来源区**：`getInventorySources(supplierCode, platformSkuCode?)` 列表；「新建库存来源」按钮（`remote-on-demand` → `activate`）。
- **Offer 发布区**：`getConfigOffers(supplierCode)` 列表，列含 `platformSkuCode/Name`、`status`、`fulfillmentReady`、`currentVersionId`、`externalSkuCode`；每行「发布版本 / 查看历史」按钮 → 打开 `OfferPublishDialog`（§7.4）/ `OfferVersionHistoryDialog`（G4）。

从合作商品 Tab 跳转进来时（§7.5）带 `prefillOfferNo`，自动定位 Offer 发布区并打开该 Offer 的发布弹窗。

### 7.4 `OfferPublishDialog.vue` 表单字段（对应 `PublishOfferReqVO`）

控件约定沿用仓库：金额 `el-input-number :controls="false" :precision="2"` + `centsToYuan/yuanToCents`（`views/supplier/utils/constants.ts`）；数量 `:precision="0" :min="1"`；下拉 `el-select` + `el-option`；`el-form ref :model :rules label-width`；提交 `formRef.validate()` → 业务补充校验 `message.warning` → `SupplierConfigApi.publishOffer` → `message.success` → `emit('success')`。

| 表单字段(label) | 提交字段 | 控件 | 来源/候选 | 校验 |
| --- | --- | --- | --- | --- |
| 目标合作商品 | `offerNo` | 只读（来自列表行） | G3 | 必填 |
| 期望版本号 | `expectedOfferVersion` | 隐藏（来自 G3 `version`） | G3 | 乐观锁，原样回传 |
| 供应商外部商品编码 | `externalSkuCode` | `el-input` maxlength=128 | 手填 | 必填、≤128、同连接器唯一（后端校验，前端提示） |
| 结算单价(元) | `supplierSettlementUnitPrice` | `el-input-number` | 手填 | `>=0`；提交 `yuanToCents` |
| 连接器版本 | `connectorVersionId` | `el-select` | G1（仅当前 PUBLISHED 版本） | 必填 |
| 库存来源 | `inventorySourceNo` | `el-select` | G2（ACTIVE+REMOTE_ON_DEMAND） | 必填 |
| 最小/最大/步长 | `minQuantity`/`maxQuantity`/`quantityStep` | `el-input-number` | 手填 | `min>=1`、`max>=min`、`step>=1` |
| 主履约能力 | `primaryCapabilityCode` | `el-select` | G5 `capabilities` | 必填；按 SKU `fulfillmentType` 联动过滤（§8）；须在连接器版本 `enabledCapabilities` 内 |
| 完成模式 | `completionMode` | `el-select` | G5 | 必填；联动能力校验（不变量6） |
| 创建幂等模式 | `createIdempotencyMode` | `el-select` | G5 | 必填 |
| 结果未知策略 | `unknownResultPolicy` | `el-select` | G5 | 必填；`REPLAY_SAME_KEY` 时强制 `createIdempotencyMode=REPLAYABLE_RESULT`（不变量7） |
| 部分交付模式 | `partialDeliveryMode` | `el-select` | G5 | 必填 |
| 结果资产数量 | `resultMultiplicity` | `el-select` | G5 | 必填 |
| 履约私有配置 | `fulfillmentConfigJson` | 只读文本，固定 `{}` | G5 `fulfillmentConfigJsonFixed` | 一期不可编辑 |
| 生效时间 | `validFrom` | `el-date-picker` datetime | 手填 | 必填 |
| 失效时间 | `validTo` | `el-date-picker` datetime | 手填 | 可空；填则 `> validFrom` |
| 发布原因 | `publishReason` | `el-input` textarea maxlength=512 | 手填 | 必填、≤512 |

联动实现：`primaryCapabilityCode` 的可选集 = `capabilities ∩ 连接器版本.enabledCapabilities ∩ fulfillmentType 约束(§8)`；`completionMode`/`unknownResultPolicy` 变更时重新校验连接器是否具备 `QUERY_ORDER`/`RECEIVE_CALLBACK`，缺失则 `message.warning` 并禁用提交。

### 7.5 合作商品 Tab 入口（`ProductList.vue`）

- 新增列「履约就绪」：`fulfillmentReady ? 标签「已配置」: 标签「未发布版本」`（`el-tag` type success/info）。
- 行操作新增「发布履约配置」按钮：`v-if="checkPermi([SUPPLIER_CONFIG_PERMISSIONS.admin])"`，点击 `emit('publish-fulfillment', row.offerNo)`；`detail/index.vue` 接住后切到 `fulfillment` Tab 并把 `prefillOfferNo` 传给 `FulfillmentConfig`。

### 7.6 字典与枚举兜底

优先 `getStrDictOptions('supply_chain_*')`（若后端已配对应字典），否则用 G5 端点返回值渲染下拉；`views/supplier/utils/constants.ts` 增补 `as const` 兜底常量（`COMPLETION_MODE_OPTIONS` 等）与 `getXxxLabel`，遵循「字典/端点优先 + constants 兜底」既有模式。状态枚举中文（`OfferStatus`/`ConnectorVersionStatus`/`InventorySourceStatus`）用于列表标签。

## 8. `fulfillmentType → primaryCapabilityCode` 约束表

来源：`SupplierOfferPublicationService.requireCapabilityMatchesSku`（257-266）；`Capability`（`supplieradapter/metadata/Capability.java`）；`FulfillmentType`（`tradecore/catalog/domain/FulfillmentType.java`）。

| 平台 SKU `fulfillmentType` | 允许的 `primaryCapabilityCode` |
| --- | --- |
| `DIRECT_RECHARGE` | `CREATE_RECHARGE` |
| `VOUCHER_CODE` / `CARD_CODE_SECRET` / `VOUCHER_URL` | `ISSUE_ASSET` |
| `ACCOUNT_BOUND_BENEFIT` | `BIND_BENEFIT` |

`Capability` 全集（10）：`CREATE_RECHARGE, ISSUE_ASSET, BIND_BENEFIT, QUERY_ORDER, QUERY_ASSET, VOID_ASSET, UNBIND_ASSET, QUERY_BALANCE, QUERY_INVENTORY, RECEIVE_CALLBACK`（仅上表 3 个可作 primary，其余用于连接器能力声明/联动校验）。

## 9. 校验规则（前后端一致，后端为准）

前端做即时提示，后端 `publish`/`validateCommercialTerms` 为最终裁决。二者必须对齐以下规则（不变量 4-11）：`externalSkuCode` 非空且同连接器唯一；`max>=min>=1`、`step>=1`；`primaryCapabilityCode` 匹配 SKU 且在连接器版本启用；`QUERY/CALLBACK` 联动能力；`REPLAY_SAME_KEY→REPLAYABLE_RESULT`；`validTo>validFrom`；`fulfillmentConfigJson=="{}"`；连接器版本为当前 PUBLISHED；库存来源 ACTIVE+REMOTE_ON_DEMAND+匹配。错误码/文案以服务端 `ServiceException` 为准，前端 `message.error` 透传。

## 10. 测试与门禁

按 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁`：

- `*Test`（纯逻辑）：G5 元数据聚合的枚举派生、`fulfillmentReady` 计算、`primaryCapabilityCode` 匹配 SKU 的过滤逻辑。
- `*IntegrationTest`（真实 MySQL + Liquibase，profile `supply-chain-integration`）：合作商品(PUBLISHED,current_version_id=null) → `publish` → 断言写入版本行、`current_version_id` 指向新版本、`offer.version+1`、`external_sku_binding` upsert、`externalSkuCode` 冲突拒绝；再断言路由 `resolveOffer` 由「跳过」变为「命中」。
- `*ContractTest`：新增 5 个 GET 端点与 `PublishOfferReqVO` 的字段/校验一致性；G5 返回的枚举集合与后端硬编码 Set 完全相等（防漂移）。
- `*ArchitectureTest`：新增 GET 端点仍限 `interfaceapi.admin`，投影 VO 不泄漏 DO；确认不触发 `SupplyChainModuleArchitectureTest` 既有约束（Facade 不依赖 `..dal..` 等）。
- 前端：`OfferPublishDialog` 联动校验单测（若前端测试体系覆盖）；否则以 §11 手工联调为准。

## 11. 集成测试文档与未决项

- 更新 `docs/集成测试.md`：新增场景「合作商品发布供给版本后履约链路贯通」，前置（供应商/凭证槽/连接器已发布、库存来源已启用、平台 SKU 有 `fulfillmentType`），步骤（合作商品新增→履约配置 Tab 发布版本→下单），验证点（`sc_supplier_offer_version.external_sku_code` 写入、路由命中、QNM 请求 `productId==externalSkuCode`）。
- TODO（附本文锚点，禁止裸 TODO）：
  - 凭证槽列表 GET 与可视化新建（本文 §5.1 未含）——见 `docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#51-新增只读-get-端点`。
  - 连接器错误码/状态映射可视化编辑器（本文 §1 不做）。
  - supplier-config `POST /offers`（createOffer）与合作商品入口二选一的收敛（本文 §4 说明）——避免同表双入口产生重复 offer。
  - G1/G2/G3 数据量增长后的分页化（本文 §5.1）。

## 12. 落地顺序（评审通过后）

1. 后端：新增 5 个 GET 端点 + 投影 VO + `OfferListItem` 履约就绪字段 + G5 枚举来源收敛（含单测/契约测试）。
2. 后端：`*IntegrationTest` 覆盖 publish→可履约→路由命中。
3. 前端：`src/api/supplier/config/` 模块。
4. 前端：`FulfillmentConfig.vue` + `ConnectorWizardDialog` + `OfferPublishDialog` + 版本历史弹窗 + Tab 注册。
5. 前端：`ProductList.vue` 履约就绪列与跳转入口。
6. 文档：更新 `docs/集成测试.md`；本文与实现同一次变更内对齐（仓库规则）。
