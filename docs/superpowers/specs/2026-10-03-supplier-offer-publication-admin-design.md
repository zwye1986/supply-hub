# 供应商履约技术配置与合作商品上架即发布（管理端）

> 2026-10-11 当前规则修订：合作商品新增默认下架；上下架按钮按 Offer.status 驱动，待发布条款使用独立发布变更。本文 §2 的商业上架但无履约版本、§8.6 的仅按就绪度提供上架/重新上架行为已被取代。NOT_CONFIGURED 是商品未发布版本，STALE 是待发布变更，不代表供应商整体配置状态。 以[《供应商合作商品独立上下架与有效供货价》](./2026-10-11-supplier-offer-lifecycle-design.md)为准。本次尚未部署。

> 2026-10-03 后续闭环切片：多密钥适配器新增显式 `credential_set_id`，不复用既有 `credential_slot_id`；凭据密文存储扩容及 UI 条件绑定契约见 `docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置`。本文已交付 QNM 单槽路径保持，054/055/056/057 历史 SQL 不修改；新功能按该节增量实施。

> 状态：定案（v4，已通过逐项决策评审，可进入 §13 落地）· 修订日期：2026-10-03
> 版本说明：本文为 v4，取代同日 v1/v2/v3。v1/v2 假设「独立履约配置 Tab + 分步向导」；v3 收敛为「合作商品列表行内薄对话框发布 + 连接器只读 + 砍连接器向导」。v4 依新一轮评审（Q1–Q9）**再次重开并定案**：连接器相关技术配置**必须在前端 UI 可配**、独立成**技术菜单**、与合作商品业务配置分离；合作商品**上架即发布**可履约版本（不再有独立发布动作）；供应商缺能力时**拒绝上架并明确提示**。被推翻的 v3 决策见 §0「本轮调整」。
> 命名约定：供应商/适配器一律用 `HUA_SHENG`、`QNM` 表示（对应 `adapterType` `HUA_SHENG_ORDER_V1`/`QNM_GAME_ORDER_V1`、`supplier_code` `HUASHENG`/`QNM`），prose 不用中文名；DB 种子 `supplier_name` 展示值（如 `'华昇卡券'`）保留中文，不在约定覆盖内。
> 关联设计：`2026-08-07-supply-chain-phase1-detailed-design.md` §7.3 §7.4 §10.1 §10.2 §17
> 关联设计（价格口径）：`2026-09-22-effective-supply-price-identity-fallback-design.md`
> 关联设计（适配器实现，共建地基）：`2026-10-02-huasheng-fulfillment-adapter-design.md`（下称「HUA_SHENG 文」）。**本文 §5 是 `AdapterMetadata` 扩展 + AD-3 凭据集 + AD-5 `extra_config` 共享地基规格的唯一出处**，HUA_SHENG 文引用本文、不重复描述（决策②）。

本文设计两块管理端能力，分属两个菜单、通过 `supplierCode`+`connectorVersionId` 关联：

1. **供应商履约技术配置（新顶级技术菜单，A1）**：让技术运营在 UI 上配置连接器（草稿→校验→发布→退役）、凭据集、能力声明、状态/错误码映射，产出「已发布连接器版本」这一技术资产。以 `HUA_SHENG` 为参照，但设计**适配器无关**（新增适配器只需后端注册元数据，前端零改动）。
2. **合作商品上架即发布（业务菜单，B1）**：运营选合作商品时**必填供应商侧商品编码 `externalSkuCode`**（f1），点「上架」即**一步发布可履约供给版本**——服务端自动引用该供应商唯一已发布连接器版本（C3）、自动开通库存（c3）、继承结算价（g3）、注入适配器默认执行画像（d2）；若连接器缺少该商品所需能力，则**拒绝上架并给出明确提示**（要求 #2）。

闭合的缺口：当前「合作商品新增即 PUBLISHED、但 `current_version_id` 为空、路由 `SupplierRoutingApplicationService.resolveOffer` 直接跳过（`routing/application/SupplierRoutingApplicationService.java:385`）、无法履约」。上架即发布后，`current_version_id` 非空，路由命中，执行侧经 `FulfillmentOperationTicketFactory.java:51` 注入 `externalSkuCode`，适配器（QNM `supplieradapter/qnm/QnmSupplierAdapter.java:123`）作为供应商 `productId` 发出。

## 0. 已确认决策

### 0.1 本轮（技术菜单 + 上架即发布）

| 编号 | 决策 |
| --- | --- |
| **A1** | 新建**顶级技术菜单「供应商履约技术配置」**，按 `supplierCode` 组织，含 4 子页（连接器 / 凭据 / 能力 / 映射）；业务侧（供应商管理 `SupplierManageDetail`）只留合作商品。两侧仅通过 `supplierCode`+`connectorVersionId` 关联，不互相内嵌表单。技术菜单产出「已发布连接器版本」资产，业务菜单消费它。 |
| **B1** | **取消独立「发布履约版本」动作**。「选合作商品并上架」一步即发布可履约版本：服务端自动引用连接器版本、自动开通库存、继承结算价、注入执行画像，写入 `sc_supplier_offer_version` 并置 `current_version_id`。**能力校验前置到上架**：缺所需能力则拒绝上架 + 明确提示。v3 的 n1「两写入口」合并为一个上架动作。 |
| **C3** | 一个供应商同一时刻**至多一个已发布连接器**，「最新已发布连接器版本」天然唯一，无需多连接器选择规则。 |
| **D1** | C3 强制机制：`sc_supplier_connector` 加生成列 `published_flag`（**`current_version_id` 非空时 1、否则 NULL**）+ `UNIQUE KEY uk_sc_connector_supplier_published (supplier_id, published_flag)`（MySQL 唯一键允许多行 NULL，草稿/退役不受限）；`ConnectorPublicationService.publish`（`:44`）加**服务层预检**，同供应商已有其它已发布连接器则抛清晰业务异常；迁移（换适配器）**显式先退役再发布**，**不在 publish 事务内自动退役**（旧连接器可能有在途履约，自动退役会危及在途单）。**修正（原拟用 `status=10`）**：`createConnectorDraft`（`SupplierConfigurationCommandService.java:115`）建草稿即置 `status=10`，同供应商多个草稿都 `status=10`，用它驱动唯一键会误伤草稿；`publish`（`ConnectorPublicationService.java:75`）置 `current_version_id`、`retire`（`:122-124`）清空它，故「已发布连接器」的唯一真信号是 `current_version_id IS NOT NULL`。 |
| **E1** | 连接器编辑形态：**公共字段结构化表单**（`baseUrl`/`merchantAccount`/`protocolVersion`/`callbackPath`/`connectTimeoutMs`/`readTimeoutMs`/`rateLimit`/`concurrencyLimit`）+ **adapterType 下拉**（自 `AdapterMetadataRegistry` 注册表）+ **能力勾选**（选项＝该 adapterType 的 `capabilities()`）+ **凭据引用下拉** + **适配器私有 JSON**（`adapter_config_json`/`extra_config`）用**带 Schema 校验的 JSON 编辑器**（保存时后端 `ClosedJsonSchemaValidator` 按 `connectorSchemaResource` 校验，旁展示 Schema 作提示）。**前端零适配器专属代码**。 |
| **F1-hybrid** | 凭据统一到 **AD-3 凭据集模型**（`sc_credential_set`/`sc_credential_item`，§5.2）；`provider_type` 双路：`ENV`（只录 `env:` 引用，密钥不入库）+ `ENVELOPE`（UI 录密钥值 → `EnvelopeCryptoPort` 信封加密入库，满足自助诉求）。所需凭据项由 `AdapterMetadata.credentialKeys()` 声明，UI 按适配器渲染对应行数。**凭据护栏强制且不受 H2 影响**：录入字段只写不回显、任何 GET/列表只返回 `masked_hint`、AAD 绑定凭据项身份防密文搬移、永不入日志、KMS 主密钥版本化+轮换、每次写入脱敏审计。 |
| **G2** | 映射：`AdapterMetadata.mappingTemplates()` 声明状态/错误码映射模板（协议知识留代码单一来源）；技术菜单映射页提供「从模板初始化」动作（把 QNM 专用 `initializeQnmStatusMappings`（`SupplierConfigurationAdminController.java:82`）泛化为按 adapterType 从元数据初始化）+ 映射行表格查看 + 追加/调整单条（复用 `addErrorMapping`（`:90`）+ 新增状态映射追加端点），应对线上发现新错误码免发版补录。 |
| **H2** | **MVP 权限：技术菜单与业务侧统一用 `supply-chain:admin`**（`ADMIN_EXPRESSION`），本阶段不做细粒度 RBAC 分离（H1 `supply-chain:supplier-config:{query,create,validate,publish,credential}` 推迟到后续阶段）。所有新端点仍须各挂 `@PreAuthorize`（满足 `SupplyChainModuleArchitectureTest`「每个管理端映射都有 `@PreAuthorize`」）。**H2 只收敛权限粒度，不放松 F1-hybrid 凭据数据护栏**。 |
| **I1** | **共享地基先行、单一共建、一次实现**：`AdapterMetadata` 扩展（`defaultExecutionProfile()`/`credentialKeys()`/`mappingTemplates()`，与 HUA_SHENG 文 `fulfillmentSteps()` 同批）+ AD-3 凭据集 + AD-5 `extra_config` 抽为一份地基变更（§5），两文共同引用。技术菜单 UI + B1 上架即发布**对 QNM dogfood + 集成测试**（QNM 元数据最简：配置 `{}`、1 个凭据键、既有状态映射模板）；`HUA_SHENG` 适配器随后落地，因实现同一签名、映射走 G2 模板、凭据走 F1 集模型，**技术菜单 UI 零改动**即可配 `HUA_SHENG`。AD-3 凭据集表**提到地基层**（Phase 1 凭据 UI 依赖它，不再等 HUA_SHENG 适配器）。 |

### 0.2 沿用前轮（仍有效）

| 编号 | 决策 |
| --- | --- |
| **f1** | `sc_supplier_offer` 新增 `external_sku_code` 列，选合作商品时（`ProductSelectDialog`）**强制必填**，`(supplier_id, external_sku_code)` 在未退役身份行间唯一（057 修正，见 §15.4）；上架发布时从身份行冻结进版本行。 |
| **c3** | 发布事务内**自动开通** `REMOTE_ON_DEMAND` 库存来源（复用 `SupplierOfferPublicationService.createRemoteOnDemandInventory:82` + `activateInventorySource:98`）；**触发点由 v3 的独立发布改到 B1 上架动作**。 |
| **d2** | 执行画像（completionMode/idempotency/unknownResult/partialDelivery/multiplicity）由 `AdapterMetadata.defaultExecutionProfile()` 服务端注入；`primaryCapabilityCode` 由 SKU `fulfillmentType` 推导（§9）。前端不暴露。 |
| **g3** | Phase 1 仅 `FIXED` 定价；结算单价只读继承身份行 `draft_unit_price`；上架后编辑合作商品商业条款 → 履约配置置『过期』（i-b 捕获）→ 需重新上架。 |
| **i-b** | 履约就绪度＝后端计算三态 `NOT_CONFIGURED/READY/STALE`（`未配置/就绪/过期`），不加标志列：`current_version_id==null`→未配置；否则 diff 身份列 vs 当前版本冻结字段，有差异→过期，无差异→就绪。 |
| **k1** | `validFrom` 默认＝上架时刻（后端 `now()`）；`validTo` 默认＝空（长期有效）。再次上架＝新建不可变版本、隐式取代旧版本（旧版本行原样保留，`publish:190`）；`expectedOfferVersion` 从列表行 `offerVersion` 自动带入、用户不可见；并发依赖后端乐观锁（`:133`+`:194`）。 |

### 0.3 本轮调整（推翻 v3）

- **推翻 a3/R5**：连接器配置不再是 Phase 2 / 不再砍向导改 SQL 种子——**连接器/凭据/能力/映射进 Phase 1 技术菜单 UI**（A1/E1/F1/G2）。
- **推翻 m1 的 `current-connector` GET**：B1 无发布对话框，连接器版本由服务端按 C3 自动引用，前端不需要该端点（技术菜单另有自己的读取面 §6.1）。
- **推翻 n1「两写入口」**：B1 合并为「上架即发布」单一业务动作。
- **推翻 v3 §5.6 白名单归 QNM-only 的表述**：白名单灰度保留（§6.8），但技术菜单 UI 本身适配器无关、对 QNM 与 HUA_SHENG 一致。

### 0.4 收尾确认

- **①变更集编号**（现有链至 `052`）：`053`＝f1 `external_sku_code`；`054`＝AD-3 凭据集/项；`055`＝AD-5 `extra_config`；`056`＝D1 `published_flag`+唯一键；`057`＝退役 Offer 释放外部编码唯一槽（§15.4）；058 为后续显式凭据绑定；HUA_SHENG 配置经管理端服务创建，不预占 raw SQL 种子编号。
- **②地基规格出处**：`AdapterMetadata` 扩展 + AD-3 + AD-5 以**本文 §5 为唯一出处**，HUA_SHENG 文引用。
- **③展示名**：DB 种子 `supplier_name='华昇卡券'` 保留中文（面向运营列表显示）；命名约定只约束 prose/代码标识符。

## 1. 范围

**Phase 1 做（本文）：**

- 共享地基（§5，与 HUA_SHENG 文共建、一次实现）：`AdapterMetadata` 扩展（`defaultExecutionProfile`/`credentialKeys`/`mappingTemplates`）、AD-3 凭据集表 + `ENVELOPE` provider、AD-5 `extra_config` 列、f1 `external_sku_code` 列、D1 `published_flag` 唯一键。
- 技术菜单后端（§6.1–6.5）：只读 GET 面（当前控制器零 GET，全部新增）+ 连接器生命周期写（含 D1 供应商级守卫）+ 凭据写（ENV/ENVELOPE）+ 能力声明 + 映射模板初始化/追加。
- 上架即发布后端（§6.6）：合并 `createPublished`+`publish`；C3 连接器自动引用 + 能力缺口校验（专用错误码/文案）+ c3 库存自动开通 + d2 画像注入 + f1/g3 带入；`listOffers` 投影扩展 + i-b 三态计算（§6.7）；adapterType 白名单灰度（§6.8）。
- 前端（§8）：新技术菜单 4 子页 + api 模块；`ProductSelectDialog` 必填 `externalSkuCode`；`ProductList` 增 `供应商商品编码`/`履约状态` 两列 + `上架` 动作（**无发布对话框**）。
- 更新 `docs/集成测试.md`（§12）。

**Phase 1 不做（→ 后续）：**

- H1 细粒度技术权限（`supplier-config:{query,create,validate,publish,credential}`）与凭据双人复核——MVP 用 H2 统一 `supply-chain:admin`。
- `HUA_SHENG` 适配器实现本体（T2–T14，属 HUA_SHENG 文）——本文只保证其 metadata 实现同一地基签名、UI 零改动可配。
- 非 FIXED 定价、`validTo` 到期自动下架扫描任务。
- 改履约执行链路（Ticket / Adapter 出网 / 路由引擎）——`extraParameters` 传递链打通属 AD-5/HUA_SHENG 文。

## 2. 领域不变量

1. 供给版本（`sc_supplier_offer_version`）一经发布**不可变**；改条款＝重新上架发布新版本（`version_no+1`），旧版本保留（`SupplierOfferPublicationService.java:172-190`）。
2. 一条 Offer **可履约** 当且仅当 `status==PUBLISHED 且 current_version_id!=null` 且版本在 `now` 落 `[valid_from, valid_to)`（`valid_to` 空＝长期）。合作商品商业状态 `PUBLISHED` 与履约就绪度是**正交两维**（R②-a 沿用）：就绪度只由 i-b 三态单一表达。
3. **上架即发布是设置 `current_version_id` 的唯一业务入口**（B1，底层仍走 `SupplierOfferPublicationService.publish:194-201`）；任何其它路径不得写 `current_version_id`。
4. **一个供应商至多一个已发布连接器**（C3/D1，DB 唯一键 + 服务层预检兜底）；换适配器须显式先退役旧连接器。
5. `externalSkuCode` 录入源＝身份行 `sc_supplier_offer.external_sku_code`（f1，选品必填，`(supplier_id, external_sku_code)` 在未退役身份行间唯一（057 修正，见 §15.4））；上架时冻结进版本行并 upsert `sc_supplier_external_sku_binding`（`uk_sc_external_sku_connector (connector_id, external_sku_code)`、`uk_sc_external_sku_offer (offer_id)`，仅允许回收已退役身份的绑定，其他冲突拒绝，见 §15.4）。身份行改值后与当前版本冻结值不一致 → i-b 判 `STALE`，须重新上架。
6. **能力缺口拒绝上架**：`primaryCapabilityCode` 由 SKU `fulfillmentType` 推导（§9），必须在 C3 自动引用的连接器版本上 `enabled=1`（`requireCapability:238-240`、`requireCapabilityMatchesSku:257-266`）。缺失则上架失败并返回**明确文案**（区分「无已发布连接器」与「连接器缺能力」两种）。
7. 执行画像由 `AdapterMetadata.defaultExecutionProfile()` 注入（d2），注入后仍须自洽通过原跨字段校验（`requireExecutionCapabilities:163`）：`QUERY/QUERY_OR_CALLBACK` 或 `unknownResultPolicy==QUERY_ORDER`→须启用 `QUERY_ORDER`；`CALLBACK/QUERY_OR_CALLBACK` 或 `WAIT_CALLBACK`→须启用 `RECEIVE_CALLBACK`；`REPLAY_SAME_KEY`→`REPLAYABLE_RESULT`。适配器默认值不自洽属实现缺陷，测试 fail-fast。
8. 库存来源必须 `ACTIVE`+`REMOTE_ON_DEMAND` 且 supplier/platformSku 匹配（`:153-159`）；c3 之下由上架事务自动开通并启用后立即满足；同 offer 已有匹配 ACTIVE 来源时**复用不重建**（`LambdaQueryWrapper` 查，避免多次上架堆积来源，AGENTS.md §8）。
9. 乐观锁：`publish` 要求 `expectedOfferVersion==offer.version`（`:133`）；前端从列表行 `offerVersion` 自动带入，每次上架 `version+1`（`:193-201`）。
10. 金额 `supplierSettlementUnitPrice` 单位 CNY 分、`>=0`、`currency` 固定 `CNY`（`ck_sc_offer_price`/`ck_sc_offer_currency`）；g3 之下只读继承 `draft_unit_price`。
11. **凭据永不存明文、永不回显、永不入日志**（F1-hybrid 护栏）：`ENV` 只存 `env:` 引用；`ENVELOPE` 只存 `EnvelopeCryptoPort` 密文（AAD 绑定凭据项身份）；任何读取只返回 `masked_hint`；`CredentialProvider.resolve→char[]` 调用方不得记录返回值（端口契约 + AGENTS.md §6）。
12. `adapter_config_json`/`extra_config` 分别按适配器 `connectorSchemaResource`/`extraConfigSchemaResource` 封闭 Schema 校验（`:167-170`）；**密钥类禁止入 `extra_config`**（AD-5）。DTO `@Schema`/前端注释与 DDL `COMMENT` 同表述（AGENTS.md §5）。

## 3. 术语与三层编码

| 层 | 字段 | 表 | 由谁录入 |
| --- | --- | --- | --- |
| 采购商侧 | `buyerSkuCode` | `sc_buyer_listing` | 外部下单请求 |
| 平台侧 | `platformSkuCode` / `platformSkuId` | `sc_platform_sku` | 商品中心 |
| 供应商侧 | **`externalSkuCode`** | `sc_supplier_offer`（身份行，f1 录入源）→ `sc_supplier_offer_version`（上架冻结快照） | **选合作商品时必填（`ProductSelectDialog`）** |

下单时经标准平台 SKU → 命中 offer → 取版本行 `externalSkuCode` → 适配器供应商商品编号（落实要求 #5）。

## 4. 数据流

```
【技术菜单】供应商履约技术配置(A1, 技术运营)
  连接器: 选 adapterType(注册表) → 填公共字段 + 能力勾选 + 凭据引用 + 私有JSON(Schema校验)
          → 草稿 → 校验(validate, capabilities) → 发布(publish, D1 供应商级唯一守卫) → 已发布连接器版本
  凭据:   凭据集 + 凭据项(credentialKeys() 声明行数; ENV 引用 或 ENVELOPE 录入加密)
  映射:   从模板初始化(mappingTemplates()) → 查看/追加单条
  ⇒ 产出资产: 该供应商唯一『已发布连接器版本』(C3)

【业务菜单】合作商品(SupplierManageDetail, 运营)
  选品: ProductSelectDialog 必填 externalSkuCode(f1) + 商业条款(draft_unit_price 等)
  上架(B1, 一步即发布):
     服务端事务内:
       - C3 自动引用该供应商唯一已发布连接器版本(叠加 §6.8 白名单)
       - 能力校验: SKU fulfillmentType→primaryCapability(§9) 是否在连接器版本 enabled
           缺 → 拒绝上架 + 明确提示(要求#2); 无已发布连接器 → 另一明确提示
       - c3 自动开通+启用 REMOTE_ON_DEMAND 库存来源(或复用)
       - f1 externalSkuCode 从身份行带入; g3 结算价继承 draft_unit_price; d2 画像=适配器默认
       - k1 validFrom=now, validTo=null
       - 插入 sc_supplier_offer_version; upsert external_sku_binding
       - 更新 offer{current_version_id=新版本, status=PUBLISHED, version+1}   ← 履约『就绪』
  ⇒ 路由命中(resolveOffer) → B单冻结 offerVersionId → 执行取 externalSkuCode → 适配器 productId
  上架后改商业条款/externalSkuCode(g3) → i-b 判『过期』 → 重新上架
```

## 5. 共享地基规格（唯一出处，与 HUA_SHENG 文共建，I1/②）

### 5.1 `AdapterMetadata` 扩展

`supplieradapter/metadata/AdapterMetadata.java` 现有 `adapterType/capabilities/connectorSchemaResource/offerSchemaResource/connectorSchemaVersion/offerSchemaVersion/validateConnectorConfig/validateOfferConfig`。新增方法（QNM 与 HUA_SHENG 一致实现）：

```java
/** 该适配器默认的封闭执行画像，服务端在发布时注入，前端只读（d2）。须自洽通过 requireExecutionCapabilities。 */
ExecutionProfileDefaults defaultExecutionProfile();

/** 该适配器所需的凭据项键，UI 据此渲染凭据表单行数（F1）。QNM=[secretKey]，HUA_SHENG=[appSecret,privateKey,callbackPublicKey]。 */
Set<String> credentialKeys();

/** 该适配器的状态映射与错误码映射模板，UI『从模板初始化』据此生成映射行（G2）。协议知识单一来源。 */
MappingTemplates mappingTemplates();

/** 多步骤履约编排声明（HUA_SHENG 文 AD-1/§5.2.2 使用；QNM 单步）。 */
default List<String> fulfillmentSteps() { return List.of(); }
```

- `ExecutionProfileDefaults`＝封闭记录 `{completionMode, createIdempotencyMode, unknownResultPolicy, partialDeliveryMode, resultMultiplicity, fulfillmentConfigJson}`。
- `MappingTemplates`＝`{statusMappings: List<...>, errorCodeMappings: List<...>}`，字段对齐 `sc_status_mapping_definition`/`sc_supplier_error_code_map`。
- `SupplierOfferPublicationService` 硬编码 `Set`（`:56-63`）重构为可枚举常量或由元数据/枚举派生（消除前后端漂移根因）。
- QNM 实现：`defaultExecutionProfile` 返回自洽组合 + `fulfillmentConfigJson="{}"`（`QnmGameOrderV1Metadata.validateOfferConfig` 要求空对象）；`credentialKeys=[secretKey]`；`mappingTemplates` 迁移自现 `initializeQnmStatusMappings` 的 QNM 一期封闭映射。

### 5.2 AD-3 凭据集（变更集 `054`）+ `ENVELOPE` provider

- 新增 `sc_credential_set`（`supplier_id, set_code, status 10/20/90`，`uk (supplier_id,set_code)`）+ `sc_credential_item`（`credential_set_id, item_key, provider_type, secret_ref, masked_hint, active_secret_version, status`，`uk (credential_set_id,item_key)`）。字段语义见 HUA_SHENG 文 §7.1（本文不重复 DDL 全文，仅定编号与用途）。
- **`provider_type` 支持 `ENV` 与 `ENVELOPE`**（F1-hybrid）：
  - `ENV`：`secret_ref` 存 `env:NAME`，密钥不入库（今日 `EnvironmentCredentialProvider` 模型）。
  - `ENVELOPE`：`secret_ref` 存 `EnvelopeCryptoPort` 加密后的密文；新增**集/项解析端口** `CredentialSetResolver`（`resolveSet(credentialSetId) → Map<itemKey, char[]>`，调用方不得记录返回值）+ `EnvelopeCredentialItemResolver`（`supports("ENVELOPE")`），用 KMS DEK 解密、**AAD 绑定 `credential_set_id|item_key`** 防密文搬移（仿 HUA_SHENG 文 AD-2 checkpoint AAD）。**决策（本轮确认，选项甲）**：旧「槽位制」`CredentialProvider`（`resolve(CredentialSlotDO)`，3 个在跑消费方：`ConnectorValidationService`/`MovieSupplierConfigService`/`FulfillmentOperationTicketFactory`）**保持不动**——`CredentialSlotDO` 无 `credential_set_id|item_key`，无法承载 ENVELOPE 的 AAD 绑定；ENVELOPE 走新集/项端口，与旧槽位端口并存。
- **2026-10-03 修正**：`credential_slot_id` 永远只引用 `sc_credential_slot.id`，禁止扩展成集 ID。后续058新增独立 `credential_set_id`，两者恰一非空；元数据 `credentialBindingMode` 默认 SINGLE_SLOT 保持已交付 QNM，CREDENTIAL_SET 才使用当前供应商的凭据集。完整绑定、冻结和并发契约见 §5.6，不将 legacy slot 批量改为单项集。

- 护栏（不变量 11）：录入只写不回显、读取只返回 `masked_hint`、永不入日志、KMS 版本化+轮换、写入脱敏审计。

### 5.3 AD-5 `extra_config`（变更集 `055`）

- `sc_supplier_connector_version` 加 `extra_config JSON NULL`（非秘密可调参数，如 HUA_SHENG `expireTime`/`num`/`outMobile`；**密钥类禁入**）。运行时经 `extraParameters` 传递链注入 ticket（打通属 HUA_SHENG 文 AD-5，本文只负责 UI 可编辑该列，E1）。

- **2026-10-03 运行时扩展**：新 `extraConfigSchemaResource()` 默认沿用 connector Schema 保持 QNM `{}`；多密钥适配器注册时声明专属非秘密 Schema。创建、校验、运行时均执行封闭校验，并投影为不可变标量字符串 Map。token/秘密不得入列或配置摘要。

### 5.4 f1 `external_sku_code`（变更集 `053`）

- `ALTER TABLE sc_supplier_offer ADD COLUMN external_sku_code VARCHAR(128) NULL COMMENT '供应商外部商品编码，选合作商品时录入' AFTER platform_sku_id;` + `ADD UNIQUE KEY uk_sc_supplier_offer_external_sku (supplier_id, external_sku_code);`
  - 列可空：历史行无值；MySQL 唯一键允许多行 NULL，不阻塞历史数据。前端对**新建**合作商品强制必填。
  - `SupplierOfferDO` 加 `private String externalSkuCode;`（注释与 DDL COMMENT 同表述）。
- 选品写入：`SupplierAdminController` 的 `POST /suppliers/{supplierCode}/offers`（`createOffers:297-306`）请求体 + `SupplierCooperativeProductService.insertPublished`（`:244-280`）补 `externalSkuCode`；`toDraftLine`（`:368-373`）投影补该字段。

### 5.5 D1 `published_flag` 唯一键（变更集 `056`）

- `sc_supplier_connector` 加生成列 `published_flag TINYINT GENERATED ALWAYS AS (CASE WHEN current_version_id IS NOT NULL THEN 1 END) STORED` + `UNIQUE KEY uk_sc_connector_supplier_published (supplier_id, published_flag)`。
- **为何用 `current_version_id` 而非 `status`**：`status=10`（启用）在 `createConnectorDraft`（`SupplierConfigurationCommandService.java:115`）建草稿时即置位，同供应商可有多个 `status=10` 草稿；真正区分「已发布」的是 `publish`（`ConnectorPublicationService.java:75`）写入的 `current_version_id`，`retire`（`:122-124`）退役当前版本时清空它。故生成列以 `current_version_id IS NOT NULL` 为条件，草稿/退役行 `published_flag` 为 NULL（MySQL 唯一键允许多行 NULL），每供应商至多一行 `published_flag=1`。
- `ConnectorPublicationService.publish`（`:44`，现无供应商级守卫）加预检：同供应商已有其它已发布连接器（`current_version_id` 非空的其它行）→ 抛清晰业务异常（不依赖 DB 唯一键报错兜底）。

### 5.6 后续显式绑定切片（058）

本次在已交付 QNM 单槽实现上增量扩展，不修改054–057。完整实施契约以 HUA_SHENG 文 §12.3 为唯一来源：新增 credential_set_id 与 credential_item_snapshot、slot/set XOR、长密文 TEXT、按元数据条件引用、ACTIVE/供应商归属/精确项键、项身份和版本冻结、绑定后不可变、集→连接器→版本→项锁序及 RR 当前读。

元数据目录新增 credentialBindingMode。连接器表单 SINGLE_SLOT 使用 T6 平台槽，CREDENTIAL_SET 使用 T4 当前供应商 ACTIVE 且完整的集；切换供应商/适配器清空旧引用。草稿请求额外允许 credentialSetCode 与 extraConfig，后者JSON语法由前端提示、Schema由服务端裁定；详情只显示脱敏引用编号与模式。ENVELOPE 明文输入为多行，字符上限8192、UTF-8字节上限16384；不回显已保存秘密。运行时通过冻结版本和凭据集解析材料，临时数组成功/失败均擦除，保持 legacy slot API 兼容。暂不注册未完成的 HUA_SHENG 执行元数据，先以隔离测试 SET 元数据验证本切片。

## 6. 后端改动

### 6.1 技术菜单只读 GET 面（`SupplierConfigurationAdminController`，当前**零 GET**）

补 import `GetMapping`/`PathVariable`/`RequestParam`；所有响应 `CommonResult<T>` 投影 VO（不暴露 DO）；每端点挂 `@PreAuthorize(ADMIN_EXPRESSION)`（H2）。

| # | 方法 | 路径 | 用途 |
| --- | --- | --- | --- |
| T1 | GET | `/supply-chain/supplier-config/adapter-types` | 列出注册表 `AdapterMetadataRegistry` 的适配器类型 + 各自 `connectorSchemaResource`/schema 版本/`capabilities()`/`credentialKeys()`/`mappingTemplates()` 摘要（供 E1 下拉、能力勾选、凭据行数、映射初始化） |
| T2 | GET | `/supply-chain/supplier-config/{supplierCode}/connectors` | 该供应商连接器列表（connectorNo/code/adapterType/status/currentVersionId + **A1 追加 `latestVersionId`/`latestVersionNo`/`latestVersionStatus`**：Phase 1 连接器与版本 1:1，暴露最新版本使前端刷新后仍能对草稿/已验证态驱动 校验→发布→退役，草稿态 `currentVersionId` 为 null 时据 `latestVersionId` 操作） |
| T3 | GET | `/supply-chain/supplier-config/connectors/{connectorNo}/versions/{versionId}` | 连接器版本详情（公共字段 + `adapter_config_json`/`extra_config` + 校验态 + 能力启用态；**不含凭据明文**） |
| T4 | GET | `/supply-chain/supplier-config/{supplierCode}/credential-sets` | 凭据集/项列表（**只返回 `item_key`/`provider_type`/`masked_hint`/`status`，绝不返回 `secret_ref` 值或密文**） |
| T5 | GET | `/supply-chain/supplier-config/connectors/versions/{versionId}/mappings` | 该版本状态映射 + 错误码映射列表 |
| T6 | GET | `/supply-chain/supplier-config/credential-slots` | **（A1 新增）** 平台级可用凭据槽列表（连接器「凭据引用下拉」数据源）；**只返回 `slot_no`/`provider_type`/`masked_hint`/`status`，绝不返回 `secret_ref` 值或密文**。凭据槽无 `supplier_id`（全局），故不带 `supplierCode`，只列 `status=ACTIVE`（`CredentialStatus.ACTIVE`） |

分页：一期数据量小返回全量 `List`；超阈值再补 `PageResult`（§12 TODO）。

### 6.2 连接器生命周期写（复用现有端点 + D1 守卫）

现有 `createConnectorDraft`（`:68`，`CreateConnectorDraftReqVO:194-212`）、`validateConnector`（`:101`）、`publishConnector`（`:111`）、`retireConnector`（`:120`）保留；`publish` 加 D1 供应商级预检（§5.5）。`adapterConfigJson` 与新增 `extraConfig`（AD-5）分别按相应 Schema 校验。

**`credentialSlotNo` 语义**：已交付 QNM 仍绑定 legacy 平台级槽，不改为集编号。后续058切片按 §5.6 增加 credentialSetCode；两种模式条件必填且只能提交一种。创建、校验、发布及运行时同一 guard 核对模式、归属、状态、项身份/版本和精确键集合，不再将集路径列为暂不消费的前瞻 UI。

### 6.3 凭据写（F1-hybrid）

- 新增 `POST /supply-chain/supplier-config/credential-sets`（建集）+ `POST /supply-chain/supplier-config/credential-items`（建项）。项请求体：`setCode`、`itemKey`（须在 `credentialKeys()` 内）、`providerType`（`ENV`/`ENVELOPE`）、`secretRef`（`ENV` 时 `env:` 前缀）或 `secretValue`（`ENVELOPE` 时明文，**仅入参、服务端立即信封加密、不回显不落日志**）、`maskedHint`、`activeSecretVersion`。
- **`itemKey` 封闭集校验范围（定案）**：因凭据集/项模型（变更集 054）无 `adapter_type` 列、建项请求体亦不含 `adapterType`，`itemKey` 须落在**所有已注册适配器 `credentialKeys()` 的并集**内（经 `AdapterMetadataRegistry.all()` 聚合，封闭集，杜绝魔法值 AGENTS.md §7）。生产目录当前只注册 QNM，并集为 `{secretKey}`；完整 HUA_SHENG 注册后才扩展其三键，不能提前注册未可执行的元数据。跨适配器的键互相被接受是此方案的已知取舍，精确键集合在连接器创建、校验、发布与运行时均按 §5.6 校验。
- `createCredentialSlot` 保留 legacy 单槽兼容；058 不将其收敛为集特例。

### 6.4 能力声明

能力在 `validateConnector`（`:101`，`ValidateConnectorReqVO.capabilities:Set<String>`）时声明并落 `sc_supplier_connector_capability`（`uk_sc_connector_capability (connector_version_id, capability_code)`）。UI 勾选项来自 T1 的 `capabilities()`（封闭集），前端不自由输入。

### 6.5 映射（G2）

- 泛化 `initializeQnmStatusMappings`（`:82`）为 `POST /supply-chain/supplier-config/connectors/mappings/initialize`（入参 `connectorVersionId`，按该版本 adapterType 从 `mappingTemplates()` 初始化状态 + 错误码映射）。
- 保留 `addErrorMapping`（`:90`）追加单条错误码；新增 `POST /supply-chain/supplier-config/connectors/status-mappings` 追加单条状态映射。

### 6.6 上架即发布服务（B1，核心）

合并 `SupplierCooperativeProductService.insertPublished`（`:244-280`）与 `SupplierOfferPublicationService.publish`（`:129`）为一条上架编排（新应用服务方法，`@Transactional(supplyChainTransactionManager)`）：

1. 校验合作商品身份行（`externalSkuCode` 非空 f1、供应商 ACTIVE、SKU 可售）。
2. **C3 连接器自动引用**：查该供应商唯一已发布连接器版本（`sc_supplier_connector.current_version_id` 非空的那一行，即 `published_flag=1`），叠加 §6.8 白名单过滤。
   - 无 → 抛 `SUPPLIER_NO_PUBLISHED_CONNECTOR`，文案「供应商[X]尚无已发布连接器版本，请先在『履约技术配置』发布连接器」。
3. **能力缺口校验**：SKU `fulfillmentType`→`primaryCapability`（§9），`requireCapability`（`:238-240`）校验其在连接器版本 `enabled=1`。
   - 缺 → 抛 `OFFER_CAPABILITY_MISSING`，文案「供应商[X]当前连接器未启用『{能力中文名} {code}』能力，该商品无法上架，请先在『履约技术配置』启用能力并重新发布连接器版本」（落实要求 #2）。
4. c3 库存自动开通/复用（§2 不变量 8）。
5. 组装发布参数：`externalSkuCode`←身份行、`supplierSettlementUnitPrice`←`draft_unit_price`（g3）、执行画像←`defaultExecutionProfile()`（d2）、`fulfillmentConfigJson`←适配器默认、数量←身份行 `draft_min/max_quantity`、`validFrom=now`/`validTo=null`（k1）、`connectorVersionId`←步骤 2。
6. 调 `publish` 写版本行 + upsert binding + 置 `current_version_id`/`version+1`（`:190-201`）+ outbox。
7. 重新上架（`STALE`→`READY`）：带新 `expectedOfferVersion`，新建版本隐式取代旧版本（k1）。

`PublishOfferReqVO`（`:273-294`）在上架编排下**不再由前端逐字段提交**：前端上架只传 `offerNo`（+ 可选 `validTo`）；其余服务端派生。原 `POST /offers/publish` 端点可保留为内部/测试入口（§12 TODO 定去留）。

### 6.7 `listOffers` 投影扩展（m1 沿用 + i-b）

`OfferListItem`（`SupplierCooperativeProductService.java:455`，构建于 `toListItem:338-362`）追加 4 字段：`externalSkuCode`（身份行 f1）、`currentVersionId`、`offerVersion`（聚合 version）、`fulfillmentStatus`（三态，后端计算）。`fulfillmentStatus` 计算抽纯函数便于 `*Test`：`current_version_id==null`→`NOT_CONFIGURED`；否则 diff 身份列（`external_sku_code`/`draft_unit_price`/`draft_min_quantity`/`draft_max_quantity`）与当前版本冻结字段（`external_sku_code`/`supplier_settlement_unit_price`/`min_quantity`/`max_quantity`），任一不一致→`STALE`，全一致→`READY`。

### 6.8 adapterType 白名单灰度（j3 沿用）

上架步骤 2 的连接器候选叠加 adapterType 白名单（配置来源，非硬编码字面量，AGENTS.md §7）：Phase 1 含 `QNM_GAME_ORDER_V1`（dogfood/集成测试）；`HUA_SHENG_ORDER_V1` 待完整适配器、恢复与回调、真实配置及验收就绪后加入白名单翻牌。QNM 无部署种子（仅测试态），故生产可履约供给实际随 HUA_SHENG 上线；白名单确保 UI 可先对 QNM 开发验证而不误导生产。

## 7. REST 契约汇总（Phase 1 前端使用）

**技术菜单（H2 全 `supply-chain:admin`）：** T1–T6 只读（§6.1，T6＝`GET /credential-slots` 连接器凭据引用下拉源）；`POST /connectors/drafts`、`POST /connectors/validate`、`POST /connectors/publish`、`PUT /connectors/retire`（§6.2）；`POST /credential-sets`、`POST /credential-items`（§6.3）；`POST /connectors/mappings/initialize`、`POST /connectors/error-mappings`、`POST /connectors/status-mappings`（§6.5）。

**业务菜单：** `POST /supply-chain/suppliers/{supplierCode}/offers`（选品，f1 必填 `externalSkuCode`，权限 `hasAnyPermissions('supply-chain:admin','supply-chain:supplier:update')`）；**上架动作**（B1，新端点或复用 offer 上架语义，前端只传 `offerNo`+可选 `validTo`）；`GET`（已有）`listOffers`（投影扩展 §6.7）。

## 8. 前端设计（`supplychainhubweb`）

### 8.1 技术菜单（A1，新顶级菜单）

- 新顶级菜单「供应商履约技术配置」，由 `aifuli-module-system-server` 的 Liquibase 变更集 `013-supplier-fulfillment-config-menu`（`013-supplier-fulfillment-config-menu.sql`）管理并在下次部署时自动执行落库；同时保留 `script/deploy` 下的同名幂等部署脚本；供应链服务不跨库访问 system 表（AGENTS.md）。菜单/按钮按 `supply-chain:admin` 显隐（H2，`checkPermi`/`v-hasPermi`）。
- 入口按 `supplierCode` 维度（选供应商 → 4 子页）。
- 新 api 模块 `src/api/supplier/config/`（`index.ts`+`types.ts`，按 `src/api/supplier/manage/` 约定，`import request from '@/config/axios'`，URL 前缀 `/supply-chain/supplier-config`，每方法 JSDoc 注明后端路由；`as const` 权限常量）。

### 8.2 连接器子页（E1）

列表（T2）+ **新建草稿表单**（连接器版本一经创建即不可变，无「编辑草稿」端点；改配置＝新建连接器）：公共字段结构化控件（`baseUrl`/`merchantAccount`/`protocolVersion`/`callbackPath`/`connectTimeoutMs`/`readTimeoutMs`/`rateLimit`/`concurrencyLimit`）；`adapterType` 下拉（T1，封闭集）；凭据引用下拉按 §5.6 模式显示（SINGLE_SLOT 用 T6；CREDENTIAL_SET 用 T4 当前供应商完整可用集）；`adapter_config_json`/`extra_config` 使用 JSON 文本域 + 前端语法提示，保存时服务端执行各自封闭 Schema 校验。T1 不必返回完整 Schema 文本，保持适配器无关。**能力勾选不在草稿表单**（`CreateConnectorDraftReqVO` 无 capabilities 字段），而在**校验步骤**声明（`ValidateConnectorReqVO.capabilities`，§6.4/§8.4）。生命周期按钮按 T2 `latestVersionStatus` 显隐：草稿(0)→**校验**、已验证(5)→**发布**、已发布(10)→**退役**、已退役(90)→仅**详情**；非法跃迁由后端守卫拒绝、错误经 axios 拦截器透传。版本详情（T3）只读。

### 8.3 凭据子页（F1-hybrid）

凭据集列表（T4）+ 新建凭据项：`item_key`（按所选连接器 adapterType 的 `credentialKeys()` 渲染行数）、`provider_type` 单选 `ENV`/`ENVELOPE`；`ENV`→填 `secret_ref`（`env:` 前缀校验）；`ENVELOPE`→填密钥值（多行文本域，maxlength8192、**只写不回显**、提交后仅显示 `masked_hint`）。列表/详情**永不展示** `secret_ref` 值或密文。

### 8.4 能力子页

展示连接器版本能力启用态（T3/T1），勾选用在连接器草稿的 `validate` 步骤（§6.4）；本页主要为可视化查看 + 引导到连接器校验。

### 8.5 映射子页（G2）

「从模板初始化」按钮（`mappings/initialize`）→ 映射行表格（T5，状态映射 + 错误码映射）+ 追加单条（`error-mappings`/`status-mappings`）。

### 8.6 业务侧（B1/f1）

- `ProductSelectDialog`：选品表单**新增必填 `供应商商品编码 externalSkuCode`**（`el-input` maxlength=128，f1）。
- `ProductList.vue`：新增列 `供应商商品编码`（`row.externalSkuCode`）+ `履约状态`（`el-tag` 三态：就绪 success / 未配置 info / 过期 warning）；行操作 `上架`（`未配置`/`过期` 可点，`就绪` 显示「重新上架」），点击调上架端点（只传 `offerNo`），**无发布对话框**。上架失败若返回 `OFFER_CAPABILITY_MISSING`/`SUPPLIER_NO_PUBLISHED_CONNECTOR` → `message.error` 透传后端明确文案（要求 #2）。
- **不再有** v3 的 `OfferPublishDialog`/`current-connector` 调用。

## 9. `fulfillmentType → primaryCapabilityCode` 推导表（服务端，能力缺口判定依据）

来源：`requireCapabilityMatchesSku`（`:257-266`）、`Capability`、`FulfillmentType`。d2 之下服务端推导，前端不选。

| 平台 SKU `fulfillmentType` | 推导 `primaryCapabilityCode` |
| --- | --- |
| `DIRECT_RECHARGE` | `CREATE_RECHARGE` |
| `VOUCHER_CODE` / `CARD_CODE_SECRET` / `VOUCHER_URL` | `ISSUE_ASSET` |
| `ACCOUNT_BOUND_BENEFIT` | `BIND_BENEFIT` |

`Capability` 全集（10）：`CREATE_RECHARGE, ISSUE_ASSET, BIND_BENEFIT, QUERY_ORDER, QUERY_ASSET, VOID_ASSET, UNBIND_ASSET, QUERY_BALANCE, QUERY_INVENTORY, RECEIVE_CALLBACK`（HUA_SHENG 文拟增 `CANCEL_ASSET`，共建时同步本表与全集）。推导出的 primary 须在连接器版本 `enabled=1`，否则 §6.6 步骤 3 拒绝上架。

## 10. 校验规则（后端为准）

前端仅即时提示（必填/格式/`validTo>validFrom`）；其余由后端裁决：`externalSkuCode` 非空且身份行唯一 + binding 同连接器唯一；结算价 `>=0`；`primaryCapability` 由 SKU 推导且连接器启用（缺→拒绝上架）；执行画像自洽；连接器版本为该供应商唯一已发布（C3/D1）且在白名单；库存自动开通后 ACTIVE+REMOTE_ON_DEMAND+匹配；乐观锁 `expectedOfferVersion==offer.version`；凭据 `ENV` 前缀/`ENVELOPE` 加密与护栏。错误码/文案以服务端 `ServiceException` 为准，前端 `message.error` 透传。

## 11. 测试与门禁

按 `2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁`（仅跳过供应链以外模块单测）：

- `*Test`：`AdapterMetadata.defaultExecutionProfile()` 自洽性（注入后过 `requireExecutionCapabilities`）；`fulfillmentStatus` 三态计算各分支；`fulfillmentType→primaryCapability` 推导；硬编码 Set 重构后枚举一致性；C3 连接器选择（唯一已发布）；能力缺口错误码分支；`fulfillmentConfigJson` 按适配器 Schema 校验。
- `*IntegrationTest`（真实 MySQL+Liquibase，profile `supply-chain-integration`）：① `053`–`056` 迁移生效（`external_sku_code` 唯一、`published_flag` 唯一）；② 技术菜单链路：建凭据集/项（ENV+ENVELOPE，断言密文入库、读取只回 `masked_hint`）→ 建连接器草稿 → validate → publish（断言 D1：同供应商二次发布另一连接器被拒）→ 映射模板初始化；③ 上架即发布：合作商品(未配置) → 上架 → 断言自动开通库存、版本行冻结 `external_sku_code`、结算价==`draft_unit_price`、画像==适配器默认、`current_version_id` 非空、`version+1`、binding upsert、路由由跳过变命中；④ 能力缺口：连接器未启用所需能力 → 上架拒绝 + 错误码；⑤ 改身份列 → `fulfillmentStatus` READY→STALE → 重新上架；⑥ 再次上架带新 `expectedOfferVersion` 成功、旧号失败。
- `*ContractTest`：T1–T5 GET 与新写端点契约；凭据端点**任何响应不含 `secret_ref`/密文/明文**（安全契约）；`mappingTemplates()`/`credentialKeys()` 与元数据一致；`OfferListItem` 4 新字段序列化。
- `*ArchitectureTest`：新 GET/写端点仍限 `interfaceapi.admin` 且各挂 `@PreAuthorize`；投影 VO 不泄漏 DO；凭据密文/明文不出现在日志与响应（可加规则）；不触发既有约束（Facade 不依赖 `..dal..` 等）。
- 前端：连接器 JSON 编辑器 Schema 错误回显、凭据只写不回显、上架能力缺口提示（若前端测试体系覆盖），否则以 §12 手工联调为准。

## 12. 集成测试文档与未决项

- 更新 `docs/集成测试.md`：技术菜单配置链路（连接器/凭据/能力/映射）+ 合作商品上架即发布 + 能力缺口拒绝 + 路由命中 + 下单 `productId==externalSkuCode`；QNM dogfood 与 HUA_SHENG 翻牌（白名单）。
- TODO（附本文锚点，禁止裸 TODO）：
  - H1 细粒度技术权限 + 凭据双人复核（MVP 用 H2）——见 `#0-已确认决策`。
  - legacy slot 保留兼容；后续轮换与在途部署协调见 HUA_SHENG 文 §12.3，058不覆盖原集项。
  - 原 `POST /offers/publish` 端点在 B1 下的去留（内部/测试保留 or 下线）——见 `#66-上架即发布服务b1核心`。
  - T2/T3/T5 数据量增长后的分页化——见 `#61-技术菜单只读-get-面`。
  - `validTo` 到期自动下架扫描任务（k1 默认长期）——见 `#0-已确认决策`。

## 13. 落地顺序（Phase 1）

1. **共享地基**（与 HUA_SHENG 文共建，§5）：`AdapterMetadata` 扩展（`defaultExecutionProfile`/`credentialKeys`/`mappingTemplates`）+ 硬编码 Set 重构 + AD-3 凭据集表(`054`)+`ENVELOPE` provider + AD-5 `extra_config`(`055`) + f1 `external_sku_code`(`053`) + D1 `published_flag`(`056`)，含 `*Test`。
2. 技术菜单后端：T1–T5 GET + 连接器生命周期（D1 守卫）+ 凭据写 + 映射模板初始化/追加（§6.1–6.5），含 `*ContractTest`/`*IntegrationTest`。
3. 上架即发布后端：B1 编排 + C3 引用 + 能力缺口 + c3 库存 + 投影扩展 + i-b 三态 + 白名单（§6.6–6.8），含 `*IntegrationTest`。
4. 前端：`src/api/supplier/config/` + 技术菜单 4 子页（§8.1–8.5）。
5. 前端：`ProductSelectDialog` 必填 `externalSkuCode` + `ProductList` 两列 + 上架动作（§8.6）。
6. 文档：更新 `docs/集成测试.md`；本文与实现同一次变更内对齐（AGENTS.md §1）。
7. HUA_SHENG 适配器（HUA_SHENG 文 T2–T14）随后落地，翻白名单，UI 零改动。

## 14. 与 HUA_SHENG 文协调（I1/②）

- **地基单一出处**：`AdapterMetadata` 扩展 + AD-3 凭据集 + AD-5 `extra_config` 规格以**本文 §5 为准**，HUA_SHENG 文引用本文、不重复描述；两文对 `defaultExecutionProfile()`/`credentialKeys()`/`mappingTemplates()`/`fulfillmentSteps()` 签名与语义达成一致。
- **变更集编号**：本文占 `053`–`057`（含评审修复）；058新增显式凭据绑定，后续迁移按实际编号；配置由服务创建，不提供绕过校验的种子；落地前对齐 `db.changelog-1.0.0.yaml` 登记顺序。
- **上线排序**：QNM 仅测试态，本文 UI 对 QNM dogfood；生产可履约供给随 HUA_SHENG（完整适配器与恢复/回调 + 显式凭据绑定 + 真实配置与验收）落地后由白名单翻牌。技术菜单 UI 与 HUA_SHENG 适配器可并行推进，仅在 §5 地基与变更集编号上协调。

## 15. 评审修复（2026-10-03）

### 15.1 凭据写入日志保护

`/supply-chain/supplier-config/credential-items` 与 legacy `credential-slots`（含 `/admin-api` 前缀及部署 context path）统一作为敏感写入路径。Servlet 访问日志 Filter 不读取/保存请求正文与查询参数、不保存响应正文；非生产 Interceptor 只记路径与耗时；异常处理使用既有安全异常路径，不记录原始参数、异常消息与拒绝值。网关按相同 common 路径规则省略请求/响应正文与查询参数，转发正文保持原样。网关 JSON 脱敏集合补充 `secretValue`/`secretRef` 作为防御补充。审计保留非秘密业务字段；不关闭整站日志，不改变其它模块安全或 RPC 配置。

后续显式凭据绑定切片的质量审查发现：connectors/drafts 的新增 extraConfig 是 JSON字符串，普通结构化日志脱敏不能处理字符串内 token/密钥。草稿写入精确根及子路径（含/admin-api前缀/context，排除drafts-other）同样纳入上述共享保护；合法、拒绝和畸形请求均省略正文/参数/响应/敏感异常，原字节透传。实现与跨进程验证契约见 HUA_SHENG文§12.3，不扩大到整个supplier-config目录，不修改权限或认证。

### 15.2 技术子页刷新

连接器创建、校验、发布、退役成功后，连接器面板发出 `changed`，父页提升配置修订号；能力与映射子页监听修订号并重新加载连接器列表及当前选中详情。切换技术标签同样提升修订号，以便加载其他会话产生的新配置。保留有效选择，若连接器已不存在则清空选择与详情。供应商切换仍通过已有 key 隔离。此方案不依赖首次懒挂载，已访问的标签也会刷新。

### 15.3 能力声明契约

沿用后端 `ConnectorValidationService` 的完整集合契约：校验声明必须等于所选适配器 `capabilities()`。前端打开校验对话框时复制完整能力集合，展示只读勾选与明确说明，提交前再次验证集合一致且非空；适配器目录加载失败时不得发送空声明。此次不引入可选能力子集，不改变执行画像和能力缺口语义。

### 15.4 退役商品释放外部编码

不修改已交付 053 的内容或校验和。新增 `057-offer-live-external-sku-code.sql`：生成列 `live_external_sku_code` 对 `status != RETIRED(90)` 返回原 `external_sku_code`，退役行返回 NULL；同一 ALTER 中替换原唯一键为 `(supplier_id, live_external_sku_code)`。草稿、发布、暂停状态均占用编码；退役行保留原编码和不可变历史版本，但允许新身份重用。053 的全行唯一约束已经保证存量数据满足新约束，无需删除或重写历史数据。后续058用于显式凭据绑定，不预占 HUA_SHENG 种子/取消能力 CHECK 编号。

已上架商品退役后仍可能保留 `sc_supplier_external_sku_binding`。新商品发布时，在同一发布事务中核对冲突绑定所指身份：仅原身份存在且为 RETIRED 时移除旧绑定并写入新绑定；非退役或身份缺失仍拒绝。绑定为当前可用供给索引，历史履约依据原 Offer 版本，不修改历史版本或订单冻结字段。唯一键仍裁决并发；发布后续失败时旧绑定删除与新版本一并回滚。

### 15.5 回归门禁

供应链自动化覆盖：真实 MySQL/Liquibase 057 升级与重复迁移、未退役冲突/跨供应商/NULL/退役释放；未上架商品移除→同码重建；已上架商品移除→同码重建→上架，新绑定指向新版本且旧身份/版本仍在；发布失败时旧绑定回滚。安全链路用供应链测试直接执行 Servlet Filter/Interceptor/异常处理及网关实际日志组装，验证模拟密钥在成功/失败、非法 JSON、带 context path 时均不落日志，转发正文不变。共享 common 与网关模块编译；供应链全量单测和架构门禁照常执行。前端使用真实 Vue SFC setup + renderer 验证修订号触发列表/详情刷新、保留/清空选择，以及校验提交完整集合、目录缺失拒绝提交；再跑现有 Node 测试、改动文件 lint 与构建。联调步骤维护于 `docs/集成测试.md`。
