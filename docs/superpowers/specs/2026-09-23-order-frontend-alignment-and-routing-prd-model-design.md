# 订单管理前端契约对齐 与 路由规则 PRD 模型重构 详细设计

- 日期：2026-09-23
- 状态：待评审
- 需求基准：前端仓库 `supplychainhubweb`（`src/api/order`、`src/api/routing` 的 `types.ts` + mock 行为）即需求规格；飞书 PRD 不可访问，已与需求方确认以前端契约为准
- 取代关系：本文 §5 取代 `2026-09-21-routing-rule-admin-write-design.md` 与 `2026-09-21-routing-rule-admin-readonly-design.md` 中「强类型规则匹配（buyer + sku + fulfillmentType）」「项目/商品类型本期不开」「权重锁死 40/30/30」的约束；`2026-09-12-supply-chain-order-admin-readonly-design.md` 的 REST 契约由本文 §3 扩展。评分边界变更同时修订 `2026-09-21-offer-fulfillment-score-design.md` §7（见 §5.9）。

## 1. 背景与目标

前端「订单管理」（平台订单 / 供应商订单 / 路由记录弹窗 / 导出）与「订单智能路由」（规则管理页）目前均为 mock 模式（`USE_ORDER_MOCK = true`、`USE_ROUTING_MOCK = true`）。本次开发：

1. 后端订单管理端接口对齐前端契约（URL、查询参数、响应 VO 结构、枚举值），使前端可切换 mock 关闭直接联调。
2. 路由规则配置模型从「强类型精确匹配（buyer + platformSku + fulfillmentType + 数量区间）」重构为 PRD 四级范围模型（分销商 / 项目 / 商品类型 / 供应商，各自 ALL|SPECIFIED）+ AUTO/FIXED 路由模式 + 三个可排序优先级策略（STABLE/PRICE/COMPOSITE）+ 全局共享综合权重。
3. 路由引擎三策略真实生效：STABLE 用 offer 人工录入稳定性评分、PRICE 用 offer 冻结供货价、COMPOSITE 用全局权重合成三维评分。

### 1.1 已确认的范围决策（grill 记录）

| 决策点 | 结论 |
|---|---|
| 需求基准 | 前端契约（types.ts + mock 行为）；PRD 不可访问 |
| 订单范围 | 只读（分页 / 详情 / 路由记录）+ 导出；售后不做、发货不做、退款与一键补发不做（前端已禁用） |
| 商城侧字段（收货人、支付方式/流水号、优惠券、运费、下单人姓名） | 本期返回空；代码 `TODO` 注释 + Swagger 详细说明；后续版本扩展 accept 契约 |
| 卡密 / 取票码等敏感资产 | 管理端先明文展示；`TODO` 注释 + Swagger 说明；安全性后续版本考虑 |
| 路由规则模型 | 原地重构替换：URL 不变，底层换四级范围模型；候选保留 offer 级结构（报价 / 连接器 / 库存源 / 动态检查）；策略 / 版本发布与 round/record 审计保留 |
| 三策略 | 本期全生效；STABLE/COMPOSITE 数据源为供应商详情页人工录入的 `sc_supplier_offer.stability_score / timeliness_score` |
| 枚举映射 | 后端推导映射表写入本文 §4，评审确认；拿不准处标「待确认」 |

## 2. 现状与差距

### 2.1 订单侧差距

现有 `OrderAdminController`（`/supply-chain/orders` 分页 / 详情 / routing-records）与 `SupplierOrderAdminController`（`/supply-chain/supplier-orders`）为后端自定契约，与前端契约差异：

| 项 | 前端契约 | 后端现状 | 动作 |
|---|---|---|---|
| 列表筛选 | `bizTypes`（前端枚举多选）、`statuses`（前端枚举多选）、`supplierNames`/`distributorNames`（名称）、`productName`（模糊）、`buyerKeyword`、`createFrom/createTo`、`supplierOrderNo`/`distributorOrderNo` | `orderType`/`orderStatus`（后端枚举单值）、`buyerCode`/`supplierCode`/`skuCode`（精确） | 参数扩展 + 枚举映射（§4） |
| 列表响应 | `OrderVO`（`bizType`、`status`、金额、`items[]`、`supplierOrders[]`…） | `OrderSummary`（后端字段名 + 分为单位） | 新增前端契约 VO（§3.2） |
| 详情 | `GET /orders/{orderNo}?source=PLATFORM\|SUPPLIER`，供应商视角路径身份为 B 端单号 | `GET /orders/{orderNo}` 仅平台视角；供应商详情在 `/supplier-orders/{bOrderNo}`，VO 结构不同 | `source` 参数适配（§3.3） |
| 路由记录 | `GET /orders/{orderNo}/route-record`，返回 `OrderRouteRecordVO`（概要 + steps[]，含 request/response JSON） | `GET /orders/{orderNo}/routing-records`，返回轮次/候选审计结构 | 新增前端契约端点（§3.4），旧端点保留 |
| 导出 | `GET /orders/export`（download） | 无 | 新增 Excel 导出（§3.5） |
| 详情敏感数据 | `cardInfos`/`rechargeInfo`/`ticketCode`/`pickupCode` | 管理端刻意不返回资产 | 新增资产投影 + 敏感访问审计（§3.6） |

### 2.2 路由侧差距

`RoutingRuleAdminController` 的请求 VO 已是前端形态，但 `RoutingRuleSaveSupport` 关闭了大部分能力：

- 强制单分销商（`ROUTING_RULE_BUYER_REQUIRED`）、强制 `skuCode + fulfillmentType` 精确匹配；
- 项目 / 商品类型范围必须 ALL（`ROUTING_RULE_SCOPE_NOT_OPEN`）；
- 供应商范围必须 SPECIFIED 且 AUTO 至少 2 个（保留该规则，PRD 语义）；
- 三优先级必须 STABLE 打头（`ROUTING_RULE_STRATEGY_NOT_OPEN`）且引擎不消费；
- 权重锁死 40/30/30 且不落库；
- 规则无 ENABLED/DISABLED 真实状态（DISABLED 等同删除）、无受保护默认规则、无同范围唯一性校验。

引擎 `SupplierRoutingEngine` 仅按 `candidatePriority → supplierCode → offerId` 稳定排序；`SupplierRoutingApplicationService.route()` 按「唯一生效策略版本 → 恰好一条精确匹配规则」执行，找不到唯一规则即抛异常。

## 3. 订单管理端对齐设计

### 3.1 原则

- 新增对齐端点/参数响应**不动已有端点的既有行为**（已部署、有契约测试）：`/orders` 旧参数继续可用；新参数（`bizTypes` 等）与旧参数（`orderType` 等）同传时以后端原生参数为准并记 warn 日志。
- 新响应 VO 独立定义（`OrderAdminFeVO` 命名待定，见 §3.2），不复用 `OrderSummary`，避免破坏既有契约。
- 金额单位：**分**（与供应链管理端全部既有接口一致）；前端展示层负责换算。前端 mock 数据使用元，切换真实接口时前端需要做适配（联调项，写入 `docs/集成测试.md`）。【待确认：若要求接口直接返回元，则改为 BigDecimal 元，影响所有新端点】
- 时间格式：ISO `LocalDateTime`（UTC），与现有管理端一致。

### 3.2 分页 `GET /supply-chain/orders`（扩展）

新增查询参数（在现有参数基础上追加）：

| 参数 | 类型 | 语义 |
|---|---|---|
| `bizTypes` | `List<String>` 前端枚举 | 映射 `order_type` + `fulfillment_type` + O2O 场景（§4.1），多选 OR |
| `statuses` | `List<String>` 前端枚举 | 映射 `order_status`（§4.2），多选 OR |
| `supplierNames` | `List<String>` | `display_supplier_name_snapshot` IN |
| `distributorNames` | `List<String>` | `buyer_name_snapshot` IN |
| `productName` | `String` | `sku_name_snapshot` LIKE |
| `buyerKeyword` | `String` | `buyer_user_id` LIKE（手机号；供应链侧无下单人姓名） |
| `createFrom`/`createTo` | `LocalDateTime` | 兼容前端参数名（等价现有 `createdFrom/createdTo`，两者都接受） |
| `supplierOrderNo`/`distributorOrderNo` | `String` | B 端单号 / `buyer_order_no` 精确（平台视角按子单反查总单） |

响应新增 `frontend=true` 开关？——否。为避免同一 URL 双形态响应，设计为：**分页响应保持 `PageResult<OrderSummary>` 不变，前端契约 VO 通过 `Accept` 或独立子资源提供**复杂度不值得。最终决策：**新增独立前端端点** `GET /supply-chain/order-views`（复用同一查询服务，返回前端 `OrderVO` 结构），前端 `OrderApi.getOrderPage` 的 URL 由前端从 `/supply-chain/orders` 改为 `/supply-chain/order-views`（一行改动，联调项）。旧 `/orders` 完全不动。【待确认：也可以反过来改 `/orders` 响应并同步改契约测试，取「URL 完全不动前端」优先级最高的原则，推荐前者】

`OrderView`（对齐前端 `OrderVO`）关键映射：

| 前端字段 | 来源 |
|---|---|
| `orderNo` | `sc_supply_chain_order.order_no` |
| `bizType` | §4.1 映射 |
| `status` | §4.2 映射（平台视角） |
| `amount` | `buyer_settlement_amount`（分） |
| `qty` | `quantity` |
| `supplierName`/`distributorName`/`projectName` | 总表快照；`projectName`=`project_name_snapshot`（044 变更集已加列并完成受理写入与视图回显闭环） |
| `buyerMobile` | `buyer_user_id`（规范化手机号） |
| `buyerName`/`payMethod`/`payTradeNo`/`receiver*`/`couponAmount`/`freightAmount` | 本期空，`TODO` + Swagger 说明（§1.1） |
| `goodsAmount` | `product_subtotal_cents`；`serviceFee` = `service_fee_cents`；`salePrice`（item 级）= `sales_unit_price_cents`；`distPrice` = 报价凭证 `distribution_unit_price_cents`（联表 `sc_standard_pricing_quote`，受理时已冻结） |
| `distributorPayAmount` | `buyer_settlement_amount`；`projectPayAmount` 本期空【待确认语义】 |
| `items[]` | CARD 场景由总表 + 报价凭证 + 资产组装（单商品单行）；`cardInfos`/`rechargeInfo`/`ticketCode`/`pickupCode` 见 §3.6 |
| `supplierOrders[]` | 该总单下全部 B 端尝试（`sc_b_order`）：`supplierOrderNo`=`b_order_no`、`status`=§4.3 映射 |
| `traces[]` | 虚拟履约无物流，本期空 |

### 3.3 详情 `GET /supply-chain/order-views/{orderNo}?source=PLATFORM|SUPPLIER`

- `source=PLATFORM`（默认）：`orderNo` 为总单号，返回 `OrderView`（含 `items`、`supplierOrders`、资产区）。
- `source=SUPPLIER`：`orderNo` 为 B 端单号（`b_order_no`），内部复用 `SupplierOrderAdminQueryService` 数据组装成 `OrderView`：`relatedPlatformOrderNo` 回填总单号、`supplyMode` 按 §4.5 映射、`shipTime`/`deliveryMode` 本期空。
- 旧 `GET /orders/{orderNo}`、`GET /supplier-orders/{bOrderNo}` 保留不动。

### 3.4 路由记录 `GET /supply-chain/order-views/{orderNo}/route-record`

对齐前端 `OrderRouteRecordVO`（注意前端 URL 是 `route-record` 单数；放在 order-views 子资源下）：

- 概要：`productName`=`sku_name_snapshot`、`productType`=§4.1 映射、`tryCount`=B 端尝试总数、`failCount`=失败尝试数、`status`=全部成功即 SUCCESS 否则 FAILED、`finalSupplierName`/`supplierOrderNo`=活动或最后成功尝试、`durationSec`=首次路由轮次 `computed_at` 至总单终态时间。
- `steps[]`：按 `attempt_no` 排序的每个 B 端尝试一条：`supplierName`、`result`（fulfillment_status 40→SUCCESS 否则 FAILED）、`time`、`supplyPrice`=`supplier_settlement_unit_price`、`assignQty`=`requested_quantity`、`failReason`=§4.4 映射（取该尝试最终失败原因码）、`request`/`response`=关联 `sc_fulfillment_call_log` 脱敏摘要 JSON（无记录则空）。
- O2O/INSTORE 订单：返回概要 + 空 `steps`（前端已有「未进入路由」空态）。
- 旧 `/orders/{orderNo}/routing-records` 保留（审计原始结构）。

### 3.5 导出 `GET /supply-chain/order-views/export`

- 复用 §3.2 全部查询参数；EasyExcel（yudao 惯例）同步导出，列 = 平台订单列表列（订单号、类型、状态、供应商、分销商、金额分、数量、下单时间等），导出权限 `supply-chain:order:export`。
- 入口日志记录筛选参数（脱敏）与导出行数；导出上限 1 万行，超限报错提示缩小范围。

### 3.6 敏感资产明文展示（本期先明文）

- 详情/视图接口在 `items[].cardInfos`（卡号/卡密/有效期）、`rechargeInfo`（充值账号=总表 `fulfillment_account_value`、结果、凭证号）、`ticketCode`/`pickupCode`（券码资产）返回解密明文。
- 解密复用 `BuyerFulfillmentQueryService` 的 KMS 信封解密逻辑（`encrypted_payload` → JSON → `cardCode/cardSecret/voucherCode/voucherUrl`）。
- 每次明文返回写敏感访问审计（对齐采购商 RPC 通道的 `SensitiveAccessAudit` 标准：操作人、资产号、时间、入口=ADMIN_REST）。
- 代码 `TODO(安全)` 注释 + Swagger `@Operation.description` 说明「明文返回为过渡方案，后续版本将改为授权查询 + 脱敏默认」。

## 4. 枚举映射表（评审重点）

### 4.1 `bizType`（前端 `OrderBizType`）

| 前端值 | 后端来源 | 说明 |
|---|---|---|
| `VIRTUAL_CARD` | `order_type=CARD` 且 `fulfillment_type ∈ {CARD_CODE_SECRET, VOUCHER_CODE, VOUCHER_URL}` | 虚拟卡密/券码 |
| `VIRTUAL_RECHARGE` | `order_type=CARD` 且 `fulfillment_type ∈ {DIRECT_RECHARGE, ACCOUNT_BOUND_BENEFIT}` | 虚拟直充/账户权益 |
| `DINING` | `order_type=O2O` 且场景码 `DINING` | O2O 点餐 |
| `MOVIE` | `order_type=O2O` 且场景码 `MOVIE` | O2O 电影【待确认：电影票在 CARD 场景（取票码）也存在——按「O2O 场景码优先，CARD+电影类目 SKU 归 MOVIE」还是仅 O2O？建议先查 `sc_o2o_supplier_order` 实际场景码分布再定】 |
| `HEMA` | `order_type=O2O` 且场景码 `HEMA` | 盒马 |
| （无对应） | `order_type=INSTORE` | 到店购无前端枚举值【待确认：映射到 `HEMA` 或新增前端值？建议前端补 `INSTORE` 值，不进订单 Tab】 |

反向（筛选 `bizTypes=[X]` → SQL 条件）同表推导；`INSTORE` 类订单在前端契约下默认不出现在任何 Tab，联调时确认是否可接受。

### 4.2 平台订单状态（前端 `PlatformOrderStatus`）

| 前端值 | 后端 `order_status` | 说明 |
|---|---|---|
| `WAIT_PAY` | —（永不出现） | 受理即已支付；保留枚举位，Swagger 说明 |
| `PROCESSING` | 0 已创建 / 10 处理中 / 20 部分成功 / **90 人工**【待确认 90 归属】 | 建议 90→PROCESSING（人工处理中非终态）；另一派观点归 FAILED |
| `SUCCESS` | 30 成功 | |
| `FAILED` | 40 失败 | |
| `CLOSED` | 50 已取消 | |

### 4.3 供应商订单状态（前端 `SupplierOrderStatus`，B 端 `fulfillment_status`）

| 前端值 | 后端 | 说明 |
|---|---|---|
| `WAIT_PAY`/`CLOSED` | —（永不出现） | B 端无支付/取消概念 |
| `WAIT_SHIP` | 0 待执行 / 90 人工 | 「待发货」语义化为「待供应商执行」 |
| `SHIPPED` | 10 执行中 / 20 查询中 | 「已发货」语义化为「已提交供应商执行」【待确认：10 是否应归 WAIT_SHIP】 |
| `FINISHED` | 40 成功 | |
| `FAILED` | 50 确定失败 | |

### 4.4 路由失败原因（前端 `RouteFailReason`）

| 前端值 | 后端来源（B 端尝试失败原因 / operation result_class / 错误映射） |
|---|---|
| `API_TIMEOUT` | adapter 超时错误码 / operation `RESULT_UNKNOWN` 超时收敛 |
| `PRICE_OVER_THRESHOLD` | 【待确认：后端无「利润阈值」配置。建议本期在规则上新增可选「最高可接受供货价（分）」字段，路由资格检查时超价候选记 `PRICE_OVER_THRESHOLD` 淘汰；不配则不检查】 |
| `STOCK_SHORT` | 供应商拒单码映射（`sc_supplier_error_code_map`）到库存不足类 |
| `SUPPLIER_REJECT` | 供应商明确拒单（REJECTED） |
| `API_ERROR` | 其余接口异常（ERROR / 未映射失败） |
| 兜底 | 无法归类的失败 → `API_ERROR` + 原始原因码放 `remark` |

### 4.5 供货模式（前端 `SupplyMode`）

后端 `tradecore/supply/domain/SupplyMode` 现有 `API_DIRECT / DROP_SHIP / BATCH`；前端另有 `STORE_PICKUP / H5_DIRECT`。订单级 `supplyMode` 来源【待确认：建议取该供应商对该 SKU 的 `sc_supplier_supply_scope.mode`（已有 `mode` 列），取不到则为空】；后端缺的两个值按原文返回（scope 表 mode 为自由字符串，非封闭枚举）。

### 4.6 路由规则字典（前端路由页商品类型）

前端路由字典：`VIRTUAL_CARD / VIRTUAL_RECHARGE / DINING / MOVIE / JD / RT_MART / TB_FLASH`。后端商品类型字典为 `sc_product_type.type_code`（现网有 DINING/MOVIE/FOOD/EQUITY_PRODUCT…）。**建议：路由规则的商品类型范围直接引用 `sc_product_type` 字典编码，前端字典值改为字典接口下发**（前端常量与后端字典对齐是联调项）；`VIRTUAL_CARD/VIRTUAL_RECHARGE` 若需出现在路由维度，则在匹配时按 §4.1 的 fulfillment_type 归并规则解释。【待确认：路由匹配时商品类型的判定键——平台 SKU 的 `primary_category`、类目树还是 fulfillment_type 归并？建议 `sc_platform_spu.primary_category` 所属商品类型；CARD 订单经 SKU→SPU 解析，O2O 场景订单经场景码映射】

## 5. 路由规则 PRD 模型重构

### 5.1 模型总览

保留：`sc_supplier_routing_policy`（策略身份与启停）+ `sc_supplier_routing_policy_version`（不可变版本、config_digest、补丁发布）+ `sc_supplier_routing_round/record`（审计）。

替换：`sc_standard_product_routing_rule` / `sc_standard_product_routing_candidate` → 新表（§5.2）。旧表随本次变更集 DROP（测试环境无需要保留的存量；若 DBA 要求保留则改标记废弃，`deprecated` 列【待确认，默认 DROP】）。

新增全局权重：挂 `sc_supplier_routing_policy`（每 routing_type 一个活动策略，天然全局）：`stability_weight / price_weight / timeliness_weight SMALLINT`，CHECK 和=100。任一规则保存携带 COMPOSITE 时同步更新并全规则生效（前端语义「权重在所有规则中保持一致」）。

### 5.2 新表 DDL（变更集 `044-routing-rule-prd-model.sql`）

```sql
-- 四级范围规则主表
CREATE TABLE sc_std_routing_rule (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则内部主键',
    policy_version_id BIGINT NOT NULL COMMENT '路由策略版本主键',
    rule_code VARCHAR(64) NOT NULL COMMENT '规则稳定编码，RR+yyyyMMdd+3位序号',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则中文名称',
    route_mode VARCHAR(16) NOT NULL COMMENT '路由模式：AUTO自动路由，FIXED固定供应商',
    primary_supplier_id BIGINT NULL COMMENT 'FIXED模式主供应商；AUTO为空',
    distributor_mode VARCHAR(16) NOT NULL COMMENT '适用分销商范围：ALL/SPECIFIED',
    project_mode VARCHAR(16) NOT NULL COMMENT '适用项目范围：ALL/SPECIFIED',
    product_type_mode VARCHAR(16) NOT NULL COMMENT '适用商品类型范围：ALL/SPECIFIED',
    supplier_mode VARCHAR(16) NOT NULL COMMENT '适用供应商范围：ALL/SPECIFIED（AUTO+SPECIFIED至少2个）',
    strategy_priority_1 VARCHAR(16) NOT NULL COMMENT '第1优先级策略：STABLE/PRICE/COMPOSITE',
    strategy_priority_2 VARCHAR(16) NOT NULL COMMENT '第2优先级策略（与1、3不可重复）',
    strategy_priority_3 VARCHAR(16) NOT NULL COMMENT '第3优先级策略',
    rule_priority INT NOT NULL DEFAULT 100 COMMENT '范围特异性相同时的裁决优先级，小者优先',
    status SMALLINT NOT NULL COMMENT '规则状态：10启用，20停用',
    protected_rule TINYINT NOT NULL DEFAULT 0 COMMENT '受保护规则标记：1系统预置不可删除不可改范围',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_by BIGINT NULL, created_at DATETIME(3) NOT NULL,
    updated_by BIGINT NULL, updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_std_rule_code (policy_version_id, rule_code),
    KEY idx_sc_std_rule_match (policy_version_id, status, rule_priority),
    CONSTRAINT ck_sc_std_rule_mode CHECK (route_mode IN ('AUTO','FIXED')),
    CONSTRAINT ck_sc_std_rule_scopes CHECK (distributor_mode IN ('ALL','SPECIFIED') AND project_mode IN ('ALL','SPECIFIED')
        AND product_type_mode IN ('ALL','SPECIFIED') AND supplier_mode IN ('ALL','SPECIFIED')),
    CONSTRAINT ck_sc_std_rule_strategy CHECK (strategy_priority_1 IN ('STABLE','PRICE','COMPOSITE')
        AND strategy_priority_2 IN ('STABLE','PRICE','COMPOSITE') AND strategy_priority_3 IN ('STABLE','PRICE','COMPOSITE')),
    CONSTRAINT ck_sc_std_rule_fixed CHECK ((route_mode='FIXED' AND primary_supplier_id IS NOT NULL) OR (route_mode='AUTO' AND primary_supplier_id IS NULL)),
    CONSTRAINT ck_sc_std_rule_status CHECK (status IN (10,20)),
    CONSTRAINT ck_sc_std_rule_protected CHECK (protected_rule IN (0,1))
) COMMENT='标准商品路由的四级范围规则（PRD模型）';

-- 范围明细（scope_kind：DISTRIBUTOR/PROJECT/PRODUCT_TYPE/SUPPLIER）
CREATE TABLE sc_std_routing_rule_scope (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL COMMENT '规则主键',
    scope_kind VARCHAR(16) NOT NULL COMMENT '范围维度',
    scope_code VARCHAR(64) NOT NULL COMMENT '范围编码（buyer_code/project_code/type_code/supplier_code）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_std_rule_scope (rule_id, scope_kind, scope_code),
    KEY idx_sc_std_rule_scope_code (scope_kind, scope_code),
    CONSTRAINT ck_sc_std_rule_scope_kind CHECK (scope_kind IN ('DISTRIBUTOR','PROJECT','PRODUCT_TYPE','SUPPLIER'))
) COMMENT='路由规则四级范围明细';

-- 候选（保留 offer 级动态检查策略；candidate_priority 降级为同分裁决键）
CREATE TABLE sc_std_routing_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    candidate_priority INT NOT NULL COMMENT '同分裁决优先级，小者优先',
    inventory_check_mode VARCHAR(16) NOT NULL COMMENT 'REQUIRED/OPTIONAL/UNSUPPORTED',
    inventory_max_age_seconds INT NULL,
    balance_check_mode VARCHAR(16) NOT NULL,
    balance_max_age_seconds INT NULL,
    health_check_mode VARCHAR(16) NOT NULL,
    health_max_age_seconds INT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NULL, created_at DATETIME(3) NOT NULL,
    updated_by BIGINT NULL, updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_std_candidate_supplier (rule_id, supplier_id),
    -- 检查模式 CHECK 与旧表一致，略
) COMMENT='规则下供应商候选与动态检查策略';

-- 策略表加全局权重
ALTER TABLE sc_supplier_routing_policy
    ADD COLUMN stability_weight SMALLINT NOT NULL DEFAULT 40 COMMENT '综合排序稳定性权重%，三权和=100',
    ADD COLUMN price_weight SMALLINT NOT NULL DEFAULT 30 COMMENT '综合排序价格权重%',
    ADD COLUMN timeliness_weight SMALLINT NOT NULL DEFAULT 30 COMMENT '综合排序时效权重%',
    ADD CONSTRAINT ck_sc_routing_policy_weight
        CHECK (stability_weight + price_weight + timeliness_weight = 100);

-- 预置受保护全局默认规则（GLOBAL_DEFAULT，四级全 ALL，AUTO，候选空集由发布脚本按现网供应商填充或保持空）
```

存量数据迁移：测试环境旧规则数据无保留价值（强类型模型语义已废），不迁移，`044` 内直接 DROP 旧两表并预置 `GLOBAL_DEFAULT`。

### 5.3 规则匹配语义（运行时）

**2026-09-24 修订：本节的特异性得分算法已由 `2026-09-24-standard-order-routing-execution-design.md#3-规则匹配` 的五级逐级匹配替代；停用规则跳过继续匹配。** 以下保留初版设计记录。

路由输入扩展：`RoutingInput` 增加 `projectId`（受理链路已落库 projectId，可直接传入）与「商品类型判定键」（§4.6，CARD 经 SKU→SPU 解析、O2O 经场景码）。

匹配算法（在 `SupplierRoutingApplicationService`，结果仍委托纯引擎）：

1. 取活动 STANDARD_PRODUCT 策略的生效版本内全部 `status=10` 规则；
2. 逐规则判定四维命中：`SPECIFIED` 需 `scope_code` 包含输入值（DISTRIBUTOR→buyer_code、PROJECT→project_code、PRODUCT_TYPE→商品类型判定键）；`ALL` 恒命中；SUPPLIER 维度不参与「命中」判定，而是对候选集做限定（§5.4）；
3. 无任何命中 → 落到 `GLOBAL_DEFAULT`（其四级全 ALL 恒命中）；仍无（默认规则被停用）→ `NO_MATCHED_RULE` 拒绝受理，审计轮次记 `decision_status=20`；
4. 多条命中时按「特异性得分」（SPECIFIED 维度个数多者优先）→ `rule_priority` 小者 → `rule_code` 字典序，取唯一；特异性并列且 `rule_priority` 并列时记 `decision_status=30` 规则冲突拒绝（同范围唯一性校验本应阻止，此处为兜底）。

### 5.4 候选解析与三策略排序（引擎）

**2026-09-24 修订：候选累计供货配额与配送区域适用边界见 `2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发`、`#5-配送区域`；排序截断仍沿用本节三级确定性比较。**

候选集构造：FIXED 模式 = 主供应商单候选；AUTO 模式 = `supplier_mode=ALL` 时取该 SKU 全部有效 offer 供应商，`SPECIFIED` 时取范围内供应商。每候选沿用现有 offer 解析（PUBLISHED offer + 有效版本 + 连接器 + 库存源），淘汰原因码不变（`SUPPLIER_NOT_ACTIVE`/`NO_EFFECTIVE_OFFER`/`AMBIGUOUS_EFFECTIVE_OFFER`/事实检查原因）。

`RoutingCandidate` 扩展两个评分快照字段：`stabilityScore`、`timelinessScore`（来自 `sc_supplier_offer` 身份列，由 application 层读出注入——**domain 不依赖评分服务**，ArchUnit 纯度保持）。

`SupplierRoutingEngine.decide()` 排序键按规则三优先级逐级比较，全同级再按 `candidatePriority → supplierCode → offerId` 稳定裁决：

- `STABLE`：`stabilityScore` 降序；
- `PRICE`：`supplierSettlementUnitPrice` 升序（null——即无有效报价的候选——已在资格检查淘汰，不进排序）；
- `COMPOSITE`：综合分 = `stability_weight×stabilityScore + timeliness_weight×timelinessScore + price_weight×priceScore`，其中 `priceScore = maxPrice==minPrice ? 100 : (maxPrice−price)×100/(maxPrice−minPrice)`（候选集内归一化，确定性、无时钟依赖）；综合分降序。

选中原因码：`SELECTED_BY_STRATEGY_<策略序列>`（如 `SELECTED_BY_STABLE_PRICE_COMPOSITE`）；无合格候选维持 `NO_ELIGIBLE_CANDIDATE`。失败降级语义不变：单轮仍只选一个供应商，B 端尝试失败后的受限重路由产生新轮次（round_sequence 递增），路由记录 API 聚合多轮为前端 steps。

### 5.5 保存校验（`RoutingRuleSaveSupport` 重写）

打开/新增的校验（对齐前端表单规则与 mock 行为）：

1. 规则名称必填、≤128、**全局唯一**（启用集内）；
2. 四级范围：`SPECIFIED` 时对应 codes 非空且去重；`DISTRIBUTOR/PROJECT` 校验编码存在于 `sc_buyer`/`sc_buyer_project`；`PRODUCT_TYPE` 校验在 `sc_product_type`；`SUPPLIER` 校验在 `sc_supplier`；
3. AUTO + `supplierMode=SPECIFIED` → 供应商 ≥2（失败降级）；FIXED → 必须指定主供应商且 `supplierCodes=[primary]`；
4. 三优先级必须为 STABLE/PRICE/COMPOSITE 的不重复全排列（不再要求 STABLE 打头）；
5. 权重：任一规则含 COMPOSITE 时 weights 必填且和=100，写入 policy 全局列并同步所有规则投影；不含 COMPOSITE 时接受默认 40/30/30；
6. **同范围唯一性**：启用集内不允许两条规则四维范围（mode+codes 规范化后）完全相同；
7. 受保护规则（`protected_rule=1`）：不可删除、名称可改、范围与路由模式不可改、可停用（停用后无兜底规则，保存时给出确认语义由前端承担）；
8. `DISABLED` 不再等同删除：成为规则真实状态；删除最后一条启用规则允许（有 GLOBAL_DEFAULT 兜底）但删除 GLOBAL_DEFAULT 拒绝。

错误码新增/调整：`ROUTING_RULE_NAME_DUPLICATED`、`ROUTING_RULE_SCOPE_DUPLICATED`（同范围唯一性）、`ROUTING_RULE_PROTECTED`（受保护规则受限操作）、沿用 `ROUTING_RULE_CANDIDATE_INVALID` 等；删除 `ROUTING_RULE_SCOPE_NOT_OPEN`/`ROUTING_RULE_STRATEGY_NOT_OPEN`（不再关门）。

### 5.6 REST 契约（URL 不变，VO 对齐前端 `RouteRuleVO`）

`/supply-chain/routing-rules`：GET 分页（含 `projectCodes/productTypeCodes` 真过滤）、GET `/{ruleCode}`、POST、PUT `/{ruleCode}`、DELETE `/{ruleCode}`、PUT `/{ruleCode}/status`。

响应 `RoutingRuleView` 对齐前端 `RouteRuleVO`：`ruleCode/name/protectedRule`、四级 `*Mode/*Codes/*Names`、`routeMode`、`primarySupplierCode/Name`、`priorities[{id,strategy}]`（3 项有序）、`weights{stability,price,timeliness}`、`status(ENABLED/DISABLED)`、`updater`（用户名，经 system 用户服务解析——管理端已有先例）、`updateTime`。分页参数 `distributorCodes/projectCodes/productTypeCodes/supplierCodes/status/updaters/updateFrom/updateTo` 全部生效。

只读投影批量补供应商档案时，先汇总当前版本全部规则的候选供应商 ID 与 FIXED 主供应商 ID。`AUTO + supplierMode=ALL`（包括预置 `GLOBAL_DEFAULT`）的候选在订单运行时按 SKU 动态解析，规则表中可以没有候选行；若汇总 ID 为空，直接使用空供应商映射，不调用批量查询，规则仍正常返回 `supplierMode=ALL`、空 `supplierCodes/supplierNames`。此边界同时适用于分页与详情，不能通过给默认规则补固定候选来规避空集合。

写路径沿用补丁发布：保存 → 克隆当前生效版本 → 应用补丁（规则 + 范围 + 候选 + 策略 + 权重）→ 重算 `config_digest` → 发布新版本。`changeStatus` 变为真实启停（不再 DISABLED=删除）。

### 5.7 受影响调用方

- `FulfillmentAcceptanceLifecycle.route()`：由「恰好一条精确规则否则抛异常」改为 §5.3 匹配；RoutingInput 构造增加 projectId 与商品类型键。
- `RoutingPolicyPublicationService`：config_digest 规范化范围内容纳入摘要。
- 契约测试重写：`RoutingRuleAdminControllerTest`、`RoutingRuleAdminCommandServiceTest`、`RoutingRuleSaveSupportTest`、`RoutingRuleAdminQueryServiceTest`、`RoutingRuleAdminIntegrationTest`、`RoutingRuleAdminWriteIntegrationTest`、`SupplierRoutingApplicationServiceIntegrationTest`、`SupplierRoutingEngineTest`。

### 5.8 ArchUnit 变更

`SupplyChainModuleArchitectureTest`：

- 删除/修订「`routing.domain` 不得有 stability/timeliness score 字段」——改为允许 `RoutingCandidate` 携带评分**快照字段**，仍禁止依赖 `OfferFulfillmentScoreService` 与 `..dal..`（评分读取只发生在 application 层装配候选时）；
- 其余规则（domain 无 Spring/MyBatis、Facade 无 dal、admin 每 HTTP 有 `@PreAuthorize`）不变，新增端点按规矩带 `@PreAuthorize`。

### 5.9 评分设计文档修订

`2026-09-21-offer-fulfillment-score-design.md` §7「引擎本期不读分」修订为：引擎自本设计起通过 `RoutingCandidate` 快照消费 `sc_supplier_offer.stability_score/timeliness_score`（STABLE/COMPOSITE 排序），分数写入仍仅 `OfferFulfillmentScoreService` 单一入口。

## 6. 菜单与权限（script/deploy）

`system-supply-chain-admin-menus.sql` 增补：

- 「订单管理」下按钮：`supply-chain:order:export`（订单导出）；订单查询沿用 `supply-chain:order:query`；
- 「订单智能路由」目录（新增，挂「供应链中台」下）：「路由规则」页面 `supply-chain:routing-rule:query` + 按钮 `create/update/delete/status`；`RoutingRuleAdminController` 由 `ADMIN_EXPRESSION` 改为方法级 `@PreAuthorize`（沿用 `SupplyChainAdminPermissions` 模式）；
- 实施修正：`script/deploy` 下仅 `aifuli-test/sql/system-supply-chain-admin-menus.sql` 承载 system 库菜单 SQL（其余环境目录无 SQL，见仓库实际结构），本次只增补该文件。

## 7. 测试计划（AGENTS §9 门禁）

| 类型 | 范围 |
|---|---|
| `*Test` | 枚举映射纯函数（§4 全表）、三策略排序（STABLE/PRICE/COMPOSITE + 三级优先级组合 + 归一化边界 max==min、同分裁决）、范围匹配特异性得分、保存校验（同范围唯一、权重和、受保护规则、AUTO≥2 供应商） |
| `*IntegrationTest` | 新表 Liquibase 变更集、规则 CRUD 补丁发布（含 config_digest 变化）、受理→路由→默认规则兜底、价格阈值淘汰（若采纳 §4.4 建议）、路由记录聚合 |
| `*ContractTest` | order-views 分页/详情/路由记录/导出 OpenAPI 契约、routing-rules 新 VO 契约、RBAC 权限码契约（新增权限码全覆盖） |
| `ArchitectureTest` | §5.8 回归 |

联调步骤与前端 mock 切换清单（`USE_ORDER_MOCK=false`、`USE_ROUTING_MOCK=false`、URL 改 order-views、金额分转元展示、前端路由商品类型字典对齐）写入 `docs/集成测试.md`。

只读投影回归增加：`RoutingRuleAdminQueryServiceTest` 验证仅有 `AUTO + ALL` 且无主供应商、无候选时分页和详情不查询供应商档案并返回空供应商列表；`RoutingRuleAdminIntegrationTest` 在真实 MySQL/Liquibase 预置的 `GLOBAL_DEFAULT` 上验证同一请求不会生成 `IN ()`，有候选规则的既有测试继续验证批量补档案。

## 8. 实施顺序

1. DDL 变更集 `044` + DO/Mapper + 预置 GLOBAL_DEFAULT；
2. 枚举映射纯函数 + 单测（先冻结 §4 映射表）；
3. 路由域重构：SaveSupport 重写 → CommandService/QueryService → ApplicationService 匹配与候选装配 → 引擎三策略 → 受理链路接入 → ArchUnit 修订；
4. 订单侧：order-views 查询服务（分页/详情/资产解密审计/路由记录聚合）→ Controller → 导出；
5. 菜单权限 SQL + 契约测试 + 集成测试 + `docs/集成测试.md`；
6. 前端联调（前端仓库两行 mock 开关 + URL/单位适配）。

## 9. 待确认清单（评审时定）

| # | 问题 | 建议 |
|---|---|---|
| 1 | 金额单位：分（后端惯例，前端换算）还是元（前端零改动） | 分 |
| 2 | 前端契约 VO 端点：新 `order-views`（旧端点不动）还是改造 `/orders` | order-views |
| 3 | `order_status=90 人工` 归 PROCESSING 还是 FAILED | PROCESSING |
| 4 | B 端 `fulfillment_status=10 执行中` 归 WAIT_SHIP 还是 SHIPPED | SHIPPED |
| 5 | MOVIE bizType：仅 O2O 场景码，还是含 CARD 电影类目 | 查场景码分布后定 |
| 6 | INSTORE 订单在前端无枚举值的处理 | 前端补 INSTORE 值 |
| 7 | PRICE_OVER_THRESHOLD：新增规则级「最高可接受供货价」字段 | 采纳 |
| 8 | 供货模式取数来源（供应商供货范围 mode） | 采纳 |
| 9 | 路由商品类型判定键（SPU 主类目 vs fulfillment_type 归并） | SPU 主类目 |
| 10 | 总表是否加项目编码/名称快照列 | 加 |
| 11 | 旧路由规则表 DROP 还是标记废弃 | DROP |
