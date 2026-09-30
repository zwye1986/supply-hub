# 最低有效供货价：报价版本优先、合作商品身份列回退

## 1. 问题

`GET /supply-chain/products/management-page`、`GET /supply-chain/products/skus` 与通用定价的最低供货价只读
`sc_supplier_offer.current_version_id` → `sc_supplier_offer_version.supplier_settlement_unit_price`。

管理端合作商品（`POST/PUT /supply-chain/suppliers/{supplierCode}/offers`）新增即 `PUBLISHED`，单价写在身份列
`price_mode` / `draft_unit_price` / `discount_bp`，**不**插入 `SupplierOfferVersion`、**不**填写 `current_version_id`。
这是 09-02 管理端 REST 的既有决策：不伪造缺少连接器的报价版本，可路由发布仍走 `SupplierOfferPublicationService`。

测试环境因此出现：合作商品已填固定供应价，商品列表 `supplyPrice` 全空。

## 2. 决策

**d1 读写分离保持不变。** 合作商品保存仍只更新身份列，禁止为此造连接器/库存/履约配置皆空的 `OfferVersion`。
路由继续只解析带当前版本且连接器、库存有效的报价。

**d2 最低有效供货价一套口径。** 商品列表、标品 SKU 查询、通用定价依赖读取共用 `EffectiveSupplyPrice`，禁止各写一套。

**d3 版本优先，身份列回退。** 对每条已发布且供应商启用的 Offer：

1. 当前版本已到 `valid_from` 且未到 `valid_to`、`supplier_settlement_unit_price` 非空：用版本单价。
2. 否则按身份列：
   - `FIXED`：`draft_unit_price`（含 0）
   - `DISCOUNT`：`suggested_sale_price × discount_bp / 10000`，中间不按分舍入，最终 `HALF_UP` 到 CNY 分；建议价缺失或 ≤0、折扣不在 `[1,10000]` 则该 Offer 不是候选
   - `REALTIME`：不是候选（不把瞬时价当永久单价）
3. 多条候选取最低。没有候选则为空。0 是有效价格。

有有效版本时忽略同一 Offer 上较新的身份列改价。无有效版本时身份列立即参与列表、筛选与定价。

**d4 合作商品写路径发定价事件。** `createPublished` 与 `updateCommercial` 必须在同一事务发布
`PricingDependencyChangedEvent`，否则列表已有供货价、下游定价仍停在 `DEPENDENCY_MISSING`。
上下架、移除已有事件，保持不变。

**d5 查询不得先按 `current_version_id IS NOT NULL` 丢掉身份列报价。**
`selectPublishedBySku`、`selectPublishedBySupplier` 以及 SKU 列表供货价装配只要求 `PUBLISHED`。
路由用的 `selectPublishedBySupplierAndSku` 仍要求当前版本非空。

## 3. 非目标

- 不把 `sc_supplier_offer_version` 的连接器、库存、履约配置列改成可空。
- 不做存量版本回填。
- 不改变 `REALTIME` 为空、供应商暂停/Offer 非已发布不计入的规则。
- 不把退役 Offer 的草稿价显示出来。

## 4. 文档对齐

- `2026-09-15-generic-batch-pricing-design.md` §3
- `2026-09-11-supply-chain-product-center-design.md` d44、§4.1.9
- `2026-09-02-supply-chain-admin-frontend-api-design.md` §4.4 金额字段、§5.5
