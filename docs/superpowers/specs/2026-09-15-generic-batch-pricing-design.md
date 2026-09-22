# 通用批量定价与项目报价详细设计

本文取代 `2026-09-12-supply-chain-prd-scope-alignment-design.md` 中“本期无模板、销售价仅取平台
SKU 建议价”的限制。本文的“模板”是一次性应用到当前所选 SKU 的通用规则，不是可命名、复用或自动套用
未来 SKU 的模板资产。

## 1. 范围与不变量

1. 一期仅适用于标准商品（卡券、直充等）。淘宝闪购 O2O 与大润发 INSTORE 继续冻结渠道实付金额。
2. 单次操作只允许位于商品默认、一个分销商或一个项目作用域，最多 200 个 SKU；同一事务全部成功或全部回滚。
3. 三层解析顺序为“平台商品/SKU 默认定价 → 分销商 + SKU 继承/覆盖 → 项目 + SKU 继承/覆盖”。三层均按
   分销价、销售价、服务费、利润隐藏、税务五组配置；税务也纳入商品默认层。下游每组可整组继承或整组覆盖，
   税务覆盖时 `taxIncluded` 与 `taxRateCode` 必须同时显式传入，继承时不得携带任一税务字段。
4. 新平台 SKU 没有商品默认定价时为 `UNPRICED`。创建 SKU 时同步插入商品定价身份；创建分销商品时必须同步
   插入 `sc_buyer_sku_pricing` 身份行（`pricing_status=UNPRICED`，`current_version_id` 为空）；创建项目
   商品时同步插入 `sc_project_sku_pricing` 身份行。采购商目录 `availableOnly=true` 只返回分销商定价
   `READY` 的标准商品；全量目录对非 `READY` 身份标记为不可用。报价与标准履约只接受项目定价 `READY`。
   新建或复活下游商品时，若直接上游已有定价版本，则在同一事务立即按全继承配置生成下游版本；若上游尚未
   定价则保持 `UNPRICED`，待上游首次保存时由级联重算自动生成。
5. 持久化公式和当时计算结果。依赖或上游有效配置变化时逐层创建新不可变版本，不修改历史版本；已签发报价及订单继续引用
   原版本。
6. 价格重算期间、依赖缺失、最低有效供货价为零、利润低于阈值时，仅受影响的商品、分销商及项目定价不可售，
   不修改平台 SPU/SKU 的上下架状态。条件恢复后自动恢复。
7. 平台 SKU 的 `suggested_sale_price_rule_json` 只负责生成商品主数据字段 `suggested_sale_price`；本文的
   `salesRule` 以该物化字段作为可选依赖并生成最终 `sales_price_cents`。两套规则不得共表、互相覆盖或在接口层
   复用同一业务名称。建议价物化结果变化后仍按本节同步事件机制逐层重算通用定价版本。

## 2. 公式与精度

分销价、销售价统一使用：

`(价格基数 × 百分比) 运算符 固定值`

- 价格基数：`MARKET_PRICE`、`SUGGESTED_SALE_PRICE`、`SUPPLY_PRICE`。
- 百分比必须大于 0，最多 2 位小数，不设上限；大于 100 仅由前端提示亏损风险。
- 运算符：`ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE`。
- `ADD/SUBTRACT` 的固定值是元，最多 2 位小数且不得为负；`MULTIPLY/DIVIDE` 的固定值是无量纲
  系数，最多 4 位小数且不得为负；`DIVIDE` 的固定值不得为 0。
- 使用 `BigDecimal` 从左到右计算，中间过程不按分舍入，最终统一 `HALF_UP` 到 CNY 分。
- 最终结果允许为 0；任一所选 SKU 结果小于 0，整批回滚。
- 直接模式 `DIRECT_MARKET_PRICE` 分别表示分销价或销售价直接等于市场价。

服务费公式为 `(商品小计 × 百分比) 运算符 固定值`，商品小计为 `销售价 × 数量`，其精度、运算符
和非负规则与价格公式一致。用户实付为 `商品小计 + 服务费`；服务费归平台。服务费文案显示开关只控制
自定义说明文案，结算页、支付页和订单详情必须始终展示服务费金额与用户实付总额。

## 3. 供货价与利润隐藏

最低供货价由 `EffectiveSupplyPrice` 解析，商品列表、标品 SKU 查询与本节依赖读取必须共用。对每条
已发布且供应商启用的 Offer：当前版本已到 `valid_from` 且未到 `valid_to` 时，读取
`sc_supplier_offer_version.supplier_settlement_unit_price`；否则回退合作商品身份列——`FIXED` 用
`draft_unit_price`，`DISCOUNT` 用 `suggested_sale_price × discount_bp / 10000`（中间不按分舍入，最终
`HALF_UP` 到 CNY 分），`REALTIME` 不是候选。多个有效报价取最低价。管理端合作商品不写报价版本，见
`docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md`。
路由仍只使用带当前版本且连接器有效的报价。

市场价、销售建议价为 0 是合法价格。没有有效供应商品或最低供货价为 0 时仍可保存规则，但状态为
`DEPENDENCY_MISSING`，保留最后一次有效结果供管理端审计，用户端不可售；依赖恢复后发布新版本。
以 `SUPPLY_PRICE` 为基数的公式在供货价缺失或为 0 时不得先算出负数再整批失败，应直接进入
`DEPENDENCY_MISSING`。非供货价基数的负数结果仍整批回滚。

单品分销利润率为 `(分销价 - 最低供货价) / 最低供货价 × 100%`，服务费不参与。分销区阈值和销售区
阈值分别保存原值，均允许空或 0～100（最多 2 位小数）；实际阈值取非空值的最大值。隐藏判定必须使用
未舍入交叉相乘 `(分销价 - 供货价) × 100 < 阈值 × 供货价`，不得先把利润率四舍五入到 4 位再比较；
四位小数只作为持久化展示快照。利润率严格小于实际阈值时状态为 `MARGIN_HIDDEN`，等于阈值时保持可售；
依赖变化后自动重新判断并恢复。

## 4. 税务展示属性

税务信息只用于结算展示，不参与定价、利润、服务费或路由。平台商品/SKU 保存默认税务配置，分销商 + SKU 可整体继承或
整体覆盖，项目 + SKU 再可整体继承或整体覆盖。有效税务解析顺序为“项目覆盖 > 分销商覆盖 > 商品默认”。税率来自 system 字典 `supply_chain_tax_rate`，由 system 服务自身的 Liquibase 变更集 `releases/1.0.0/009-supply-chain-tax-rate-dictionary.sql`（主清单 `db.changelog-1.0.0.yaml`）在服务启动时自动向主数据源执行迁移与幂等初始化。供应链通过显式注册的
`DictDataCommonApi` 实时读取启用项校验，不跨库读取 `system_*`。RPC 不可用时保存失败并整批回滚；既有
价格读取和下单不依赖字典 RPC。

字典值采用稳定编码，字典标签用于展示；初始值为 `RATE_6`、`RATE_9`、`RATE_13`、`NON_TAXABLE`，
并在字典备注中保存可解析的十进制税率（如 `0.06`、`0.09`、`0.13`，`NON_TAXABLE` 为 `0`）。新增税率无需
发布供应链服务。`taxIncluded=false` 时税率编码必须为 `NON_TAXABLE`；`taxIncluded=true` 时必须选择一个
启用字典项，包括 `NON_TAXABLE`。领域 `TaxMetadata.taxIncluded` 为可空布尔，JSON 缺字段不得默认成不含税。

## 5. 版本、继承与可售状态

商品默认配置由 `sc_platform_sku_pricing` 指向不可变 `sc_platform_sku_pricing_version`；分销商配置由 `sc_buyer_sku_pricing`
指向不可变 `sc_buyer_sku_pricing_version`；项目配置由
`sc_project_sku_pricing` 指向不可变 `sc_project_sku_pricing_version`。分销商与项目版本都同时保存每组继承标记、
覆盖规则和解析后的完整有效快照，因此商品默认、分销商规则或基础价格变化时，会逐层为受影响对象生成新版本。

有效配置解析顺序：

- 分销价、销售价、服务费：项目覆盖 > 分销商覆盖 > 商品默认。
- 利润隐藏：项目覆盖 > 分销商覆盖 > 商品默认；组内仍取两个输入阈值的较高者。
- 税务：项目整体覆盖 > 分销商整体覆盖 > 商品默认。
- 商品默认尚未定价时，下游继承组无法解析，该 SKU 保持 `UNPRICED`；旧 `sc_platform_settlement_price` L1 仅保留兼容，不是新三层通用定价的上游。

可售状态取 `READY`、`UNPRICED`、`RECALCULATING`、`DEPENDENCY_MISSING`、`CALCULATION_FAILED`、
`MARGIN_HIDDEN`。管理端批量保存发现负数等确定性非法结果时整批失败；仅依赖缺失允许落规则并进入不可售。
商品基础价格、供应商报价价格/状态变化时，同一事务把受影响聚合标记为 `RECALCULATING` 并 `SELECT … FOR UPDATE`
定价身份，随后生成新版本。其它报价会话等待行锁，提交后直接看到新版本，而不是先读到已提交的中间
`RECALCULATING`。报价入口还会按当前时间检查报价有效期并惰性补偿重算，避免定时边界漏算。

商品与供应商应用服务通过 Spring 同步事件发布 `platformSkuId`，定价监听器在原事务内完成重算。这样既保持
主数据写入与新价格版本的事务一致性，也避免商品/供应商服务反向依赖完整定价编排服务；监听失败必须向上
传播并回滚上游主数据事务，不能捕获后改写为 `CALCULATION_FAILED` 再提交，也不能异步吞掉或延迟到事务
提交后才处理。计算器以状态码返回的 `DEPENDENCY_MISSING`、`MARGIN_HIDDEN` 是可提交的不可售结果，不属于
监听失败。

## 6. 管理端接口

新增商品默认定价路径，并保留分销商/项目路径：

- `GET /supply-chain/products/skus/{skuCode}/pricing`
- `POST /supply-chain/products/skus/pricing/preview`
- `PUT /supply-chain/products/skus/pricing`
- `POST /supply-chain/buyers/{buyerCode}/products/pricing/preview`
- `PUT /supply-chain/buyers/{buyerCode}/products/pricing`
- `POST /supply-chain/projects/{projectCode}/products/pricing/preview`
- `PUT /supply-chain/projects/{projectCode}/products/pricing`

商品请求携带所选 `platformSkuCodes` 和五组完整默认配置；分销商请求携带 `buyerProductCodes`，项目请求携带 `listingCodes`，两个下游层每组都包含
`inherit=true` 或完整覆盖配置。预览不落库，逐 SKU 返回三个依赖价格、计算结果、利润率和预期可售状态。
保存由服务端重新计算，不能信任预览结果。标品尚未发布默认定价时，分销商或项目保存返回 `PLATFORM_DEFAULT_PRICING_MISSING` 并整批回滚，不落成半成品覆盖。`POST /supply-chain/buyers/{buyerCode}/products` 的行内 `pricing` 与上述保存使用同一覆盖对象，并与分销商品关联处在同一事务。

商品默认定价单条查询按平台 SKU 永久业务编码读取，不要求 SKU 当前为启用状态，便于停用商品审计和编辑回显。
响应返回当前版本主键与版本号、定价状态与不可售原因、分销价/销售价/服务费/利润隐藏/税务五组完整配置、
计算时三个依赖价格快照、分销价与销售价结果、利润率、实际隐藏阈值、币种和发布信息。尚未定价的 SKU
正常返回 `pricingStatus=UNPRICED`，当前版本、五组配置、计算快照与发布信息均为空；SKU 不存在时返回
`PLATFORM_SKU_NOT_FOUND`。查询只读取当前指针指向的不可变版本，不调用税率字典、不触发重算，也不生成报价凭证。

## 7. 项目报价凭证与标准履约

新增项目级标准商品报价 RPC，输入绑定 `sourceSystem + externalTenantId + projectCode + platformSkuCode +
quantity + buyerUserId + buyerOrderNo`，输出分销结算单价、销售单价、商品小计、服务费、用户实付总额、税务
展示、服务费文案、综合价格版本及随机报价凭证。一期 `buyerUserId` 与标准履约目标使用同一套大陆手机号规范化。
报价成功即写入不可变凭证，有效期 15 分钟。结算页、支付页读取报价响应；订单详情读取订单上冻结的
`service_fee_cents` 与 `user_payable_amount_cents`。

重算或不可售期间不签发新凭证。已签发且未消费的凭证在有效期内可由绑定的同一订单幂等复用；不能换用户、
项目、SKU、数量或订单号复用。未消费且已过期的订单号允许按当前价格版本重新签发。已消费凭证无论是否过期，
都禁止为同一 `buyerOrderNo` 重新签发，避免拿到基于新价格版本的误导性凭证。标准履约受理新增必填报价凭证，
先锁定并校验凭证，再冻结综合价格版本和上述金额快照到订单；O2O/INSTORE 不读取该凭证。

本期不新增标准商品退款入口，但订单保存 `service_fee_cents` 与 `refundable_service_fee_cents`（单位 CNY 分），
后续标准售后首次进入退款时按“服务费全退且最多退一次”消费该快照。

## 8. 自动化测试与联调门禁

- `*Test` 覆盖四运算精度、除零、负结果、零价、利润阈值严格小于、继承合并和凭证绑定/过期。
- `*IntegrationTest` 覆盖 Liquibase、批量事务回滚、最低有效供货价、不可变版本切换和并发凭证消费。
- `*ContractTest` 覆盖管理端请求、动态税率 RPC 失败关闭、报价与履约契约。
- 人工联调步骤同步写入 `docs/集成测试.md`。
