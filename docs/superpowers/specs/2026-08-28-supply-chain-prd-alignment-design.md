# 供应链中台 PRD 对齐详细设计

## 1. 文档定位

本文把《供应链中台管理系统一期产品需求文档（PRD）》与当前采购履约内核对齐为可实施基线。领域代码标识仍以仓库根目录 `CONTEXT.md` 为准；管理端文案与对外文档使用 PRD 用语。

与下列文档冲突时，**本期实际交付范围**（加价模板是否建设、销售价来源、售后聚合与状态机、路由节点、选源 API、点餐实现责任、数据权限）以 `docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md` 为准；本文 g1–g6、g8–g10、第 4.1 节对应条目、第 6–8 章与第 10 章 `tradecore.pricing` 不再作为本期实施依据。**供应商 / 分销商 / 项目的管理端 REST 字段、枚举、状态机与前端对齐**以 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` 为准。**开票查询与保存**以 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` 为准（取代本文 g13）。

- `docs/superpowers/specs/2026-08-04-supply-chain-core-design.md`
- `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md`
- `docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md`
- `docs/供应链中台多场景概要设计.md`

不改变的内核：BuyerOrder / SupplierOrder 状态机、资金预留不变量、受理回执永久幂等、Outbox/Timer、项目应用身份、O2O/INSTORE 渠道金额规则、路由纯计算（无数据库/HTTP/时钟）。

演进策略：现有模块前向扩展（新表、新字段、新 REST）。已发布 Liquibase 变更集只追加，禁止回改。历史订单继续使用冻结快照，不回放新定价或新路由。

本期交付：**后端领域能力 + 管理端 REST 契约**（列表/筛选/分页/详情/命令）。**不建设、不联调管理端前端页面、布局与样式。**

**字段与契约来源（同时对照 PRD 与墨刀）：**

| 优先级 | 来源 | 用来定什么 |
| --- | --- | --- |
| 1 | 已确认决策 g1–g12 | 覆盖 PRD 中本期不做项（综合排序、人工队列、供应商自助入驻审核等） |
| 2 | 墨刀截图 | 字段名、显隐、枚举选项、按钮对应的命令 |
| 3 | PRD 正文（`…/供应链中台管理系统一期产品需求文档（PRD）.md` 第八章交互说明） | 校验、预填、独立保存、状态机；即使 md 里「字段：」表格为空也生效 |
| 4 | PRD 内嵌示意图 | 加价/路由等已贴图的场景 |

PRD 第八章大量「字段说明」在转换后为空，**不以空表为无需求**；缺字段以墨刀为准。PRD 与墨刀冲突时停下来确认，禁止猜测。布局、Tab 样式后端不实现。

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| g1/g2 | 按 PRD 扩展业务模型：三级 B 端结算价、加价模板、售后与路由配置组；保留履约/资金/幂等/路由内核。 |
| g3 | 三级定价 = B 端采购结算价；加价模板计算 C 端销售价，并产出 L2 分销价。 |
| g4 | 标准商品销售价随目录静态推送；场景商品（电影/点餐/外卖生活服务/商超）实时取价。 |
| g5 | 加价模板「分销价」即 L2；L3 为项目覆盖；取价 L3 > L2 > L1。 |
| g6 | 售后按商品类型：实物支持退货退款（适配器未接入前接口拒绝）；卡券/直充仅「仅退款」，且依赖供应商 `VOID_ASSET` / `UNBIND_ASSET`。 |
| g7 | 双轨术语：代码 `Buyer` = 管理端「分销商」。 |
| g8/g9 | 路由排序只做手动优先级与价格优先；价格 = `supplierSettlementUnitPrice` 升序。不做综合排序、权重、稳定性/时效评分。 |
| g10 | 配置组对齐 PRD 四级匹配。不做人工处理队列管理界面、下单前/后路由节点区分。 |
| g11 | 只补管理端 REST（列表/筛选/分页/详情/编辑），**不做前端**；字段来自墨刀 + PRD 交互，见第 1 章 |
| g12 | 渐进扩展，保留旧模型兼容。 |
| g13 | 原「开票本期不做」。已被 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` 取代：独立开票表、GET 预填不落库、PUT 不回写档案。 |
| g14 | 原型未给出的下拉：见 15.5 冻结。本轮只落地供应商档案/供货配置/合作商品 REST；分销商、加价、售后、路由另立计划。 |

## 3. 术语双轨

代码、表名、Java 类型、日志字段继续使用 `Buyer` / `BuyerProject` / `buyerSettlementUnitPrice`。管理端 REST 的中文 `summary`/`description`、错误文案、对外联调文档使用「分销商」「项目」。

映射：

| PRD / 管理端 | 代码 / 表 |
| --- | --- |
| 分销商 | `Buyer`（`sc_buyer`） |
| 分销项目 / 合作项目 | `BuyerProject`（`sc_buyer_project`） |
| 平台默认定价 L1 | 平台 SKU 默认结算价版本 |
| 分销商基础价 L2 | 采购商商品结算价版本（加价模板发布结果） |
| 项目专属价 L3 | `ProjectListingVersion.buyerSettlementUnitPrice` 且来源为 `PROJECT_OVERRIDE` |
| 销售价 | C 端展示/下单用单价，不等于结算价 |
| 供应价 | `SupplierOfferVersion.supplierSettlementUnitPrice` |

一个采购商主体仍可拥有多个项目。管理端「分销商」页展示主体；「项目」页展示 `BuyerProject`。禁止把项目伪装成第二个 `Buyer`。

## 4. 范围

### 4.1 本期做

- 三级 B 端结算价模型、发布与订单冻结。
- 加价模板（电影快/慢充、点餐、外卖/生活服务、商超）及 C 端取价。
- 标准商品「仅退款」售后（卡券/直充）。
- 路由四级配置组 + `MANUAL` / `PRICE_ASC` 排序。
- 供应商/分销商/项目/商品/分类/品牌/模板/订单/售后/路由的管理端 REST（读写）。
- 供应商与分销商运营档案扩展：多联系人、供货/分销商品关联；字段语义与 DDL COMMENT 对齐。供应商列表、新增页、基础信息已按原型 + PRD 8.1 写入第 15 章。
- 供应商合作状态增加可逆「黑名单」；运营在新增时可录入供应商结算账户**期初余额**（一次性）。
- 供应商/分销商开票信息查询与保存，见 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。

### 4.2 本期明确不做

- 管理端前端页面、布局、样式、二次确认弹窗、左右分栏等展示逻辑；墨刀仅作字段与校验来源。
- 输出服务 H5/API 模板配置页。
- 综合排序、稳定性/时效评分、人工处理队列 UI、下单前/后路由节点。
- 京东/唯品会 Adapter、实物退货物流闭环（接口预留类型，调用返回 `AFTERSALE_TYPE_NOT_ENABLED`）。
- 将 `Buyer` 重命名为 Distributor；历史数据双写。
- O2O/INSTORE 改走标准商品路由或读取项目销售价作为渠道应付金额。
- 采购方在履约中（`FULFILLING`）取消/撤销已受理单。

## 5. 三级 B 端结算价

### 5.1 口径

`buyerSettlementUnitPrice` 仍表示采购项目向中台结算一个计价单位应付的 CNY 分，不是 C 端售价、不是积分、不是供应商成本。

三级只解释**同一口径**的结算单价来源：

```text
L3 项目专属价  >  L2 分销商基础价  >  L1 平台默认定价
```

解析结果写入已发布 `ProjectListingVersion`，受理只读该冻结值。禁止下单时回源 L1/L2 当前行。

O2O、INSTORE 的 B 端金额继续：

```text
buyerSettlementAmount = channelPayAmount
```

不读取三级结算价。

### 5.2 存储

不把 L1 写进 `sc_platform_sku`（SKU 不承载采购价）。三张只追加的价格版本表：

| 表 | 作用域 | 含义 |
| --- | --- | --- |
| `sc_platform_settlement_price_version` | `platform_sku_id` + `version_no` | L1。当前指针在身份表 `sc_platform_settlement_price`。 |
| `sc_buyer_product_settlement_price_version` | `buyer_id` + `buyer_product_id` + `version_no` | L2。由加价模板发布事务写入，禁止运营手填与模板结果分叉。 |
| `sc_project_listing_version`（已有） | 项目商品不可变版本 | 解析后的最终结算单价 + `settlement_price_level`（`L1`/`L2`/`L3`）+ 来源版本主键。 |

L2 身份表 `sc_buyer_product_settlement_price` 与 `BuyerProduct` 1:1。无 L2 时项目发布允许回退 L1。无 L1 时标准商品不得发布为可采购，受理失败码 `PLATFORM_SETTLEMENT_PRICE_MISSING`。

L3：仅当项目目录项显式覆盖时 `settlement_price_level=L3` 且 `price_source=PROJECT_OVERRIDE`。未覆盖则发布事务解析 L2 否则 L1，写入同一 `buyer_settlement_unit_price` 列，`price_source=INHERITED`。

### 5.3 发布与受理

1. 发布 L1 / 加价模板产出 L2 / 项目目录批次时，在同一本地事务写入不可变版本、切换当前指针、追加目录游标与 Outbox。
2. 项目目录发布必须把解析后的单价、level、来源版本 id 写入 `ProjectListingVersion`；任一步失败整批回滚。
3. `acceptFulfillment` 只使用订单请求命中的已发布 `ProjectListingVersion`。调价不改在途单。
4. 资金冻结金额 = 冻结单价 × quantity（一期 quantity=1）。

## 6. 加价模板与 C 端销售价

### 6.1 职责

加价模板是封闭规则引擎，一次发布同时产出：

- **分销价规则** → 物化为该模板绑定范围内 BuyerProduct 的 L2。
- **销售价规则** → C 端单价（及可选服务费）。标准商品写入目录投影；场景商品只存规则，查询时计算。

禁止页面配置脚本、SpEL、动态 SQL。规则字段与 PRD 8.4–8.8 场景对齐，由 Adapter 级封闭 Schema 校验低频差异 JSON。

### 6.2 场景与取价优先级

| `scene_code` | 加价维度 | 取价优先级 | 销售价消费 |
| --- | --- | --- | --- |
| `MOVIE_FAST` / `MOVIE_SLOW` | 全国、指定区域、指定影院 | 影院 > 区域 > 全国 | 实时 |
| `DINING` | 品牌、服务费、全国、区域 | 区域 > 全国；服务费与加价按场景公式叠加 | 实时 |
| `TAKEOUT_LIFE` | 仅服务费（淘宝闪购/高德/滴滴） | 渠道绑定的服务费模板 | 实时 |
| `SUPERMARKET` | 全国、指定渠道 | 渠道 > 全国 | 实时 |
| 标准卡券/直充（非上表） | 全国加价或固定加价（模板 `STANDARD`） | 模板唯一规则 | 目录静态推送 |

点餐品牌白名单首期 7 个，以枚举发布，后续加品牌走新枚举值而非自由字符串。淘宝闪购/高德/滴滴/大润发为渠道编码枚举。大润发销售价模式含「按订单金额 100%」；B 端仍用渠道实付，不把虚高销售价写入 `FundReservation`。

### 6.3 模板模型

```text
MarkupTemplate（身份、scene_code、status）
  └── MarkupTemplateVersion（不可变：销售价公式、分销价公式、服务费、发布审计）
        ├── NationalMarkupRule
        ├── RegionMarkupRule
        ├── CinemaMarkupRule      -- 仅电影
        ├── BrandMarkupRule       -- 仅点餐
        └── ChannelMarkupRule     -- 仅外卖生活/商超
```

生命周期：`DRAFT → PUBLISHED → RETIRED`。发布后禁止改版本行；调整即复制为新草稿。模板绑定 `buyer_id`（必填）与可选 `primary_category_id`。同一 Buyer + scene_code + 品类当前只能有一个 `PUBLISHED` 模板。

分销价公式与销售价公式分离存储，枚举封闭，例如 `FIXED`（固定分）、`PERCENT_OF_SUPPLY`（供应价×百分比，百分比以基点存储）、`SUPPLY_PLUS_FEE`。百分比大于 10000 基点（即 >100%）的亏本警告只在管理端校验提示，不作为硬拒绝，除非结果单价 < 0。

### 6.4 C 端消费

标准商品：发布 Listing 时计算销售价，写入 `ProjectListingVersion` 的 `selling_unit_price`（可空：场景商品为空）。目录查询与 `CATALOG_CHANGED` 投影包含销售价；商城不得改算。

场景商品：新增 `BuyerSellingPriceQuoteApi.quote`。入参由项目认证上下文提供身份，业务字段含 `buyerSkuCode` 或场景键（影院/区域/品牌/渠道）、数量、业务时间。出参含 `sellingAmount`、`serviceFeeAmount`、`currency`、`templateVersionId`、`ruleHitCode`。报价不创建订单、不冻结资金。下单后场景履约仍按既有 O2O/INSTORE/标准受理链路；报价金额不得覆盖渠道 `payAmount`。

商城积分换算仍在采购商 Adapter 内，中台只出 CNY 分。

## 7. 标准商品售后

### 7.1 与「受理不可逆」的关系

`FULFILLING` 期间采购方取消仍返回 `CANCEL_NOT_SUPPORTED_AFTER_ACCEPTANCE`，不改订单、Operation、资金预留。

仅当公共主状态已为 `SUCCEEDED` 时，允许发起售后。主状态不回退；只推进 `aftersale_status`。这与 O2O/INSTORE「退款只动售后」一致。

### 7.2 类型与能力

| 商品/履约 | 允许售后类型 | 前置 |
| --- | --- | --- |
| 卡券/直充/账户绑定权益 | `REFUND_ONLY` | 有效成功 SupplierOrder 的 Connector 已启用 `VOID_ASSET` 或 `UNBIND_ASSET`（按 SKU `fulfillment_type` 映射） |
| 实物（京东/唯品会等，Adapter 未上线） | `RETURN_REFUND` | 本期调用返回 `AFTERSALE_TYPE_NOT_ENABLED` |
| O2O / INSTORE | 维持既有渠道退款 Case | 不走本节 CARD 售后 |

无作废/解绑能力时拒绝，错误码 `AFTERSALE_SUPPLIER_CAPABILITY_MISSING`，不建 Case。

### 7.3 CARD 售后协同

新增 `sc_fulfillment_refund_case`，永久键 `(projectId, aftersaleNo)`；同一 `buyerOrderNo` 可多笔，累计申请金额不得超过已确认采购结算金额。

状态：`OPEN → SUPPLIER_VOIDING → BUYER_REFUNDING → SUCCEEDED`，失败/能力冲突转 `MANUAL`。资金：先完成供应商作废/解绑（事务外 Operation），权威成功后再走与 INSTORE 相同的 `BuyerAccountReturnService`（`REFUND_RETURN:{refundCaseNo}`）。供应商结果未知不得释放资金、不得向采购商退款。

售后投影复用总表 `aftersale_status`：`NONE/PROCESSING/PARTIALLY_REFUNDED/FULLY_REFUNDED/MANUAL`。

管理端 REST 提供售后列表、详情、时间线（追加 `sc_fulfillment_refund_event`）。运营「申请售后」与采购商侧申请共用 Lifecycle；身份分别为管理权限与项目认证。

## 8. 路由扩展

### 8.1 保持的内核

`SupplierRoutingEngine` 仍为纯函数。无候选、规则冲突不得随机兜底。O2O/INSTORE 不进标准商品路由。项目运行时**禁止跨项目**读取另一项目的 `PROJECT*` 规则。

### 8.2 四级配置组

规则增加 `scope_type` 与可空 `primary_category_id`。匹配当前项目订单、SKU 主类目：

```text
1. BUYER_CATEGORY   当前 Buyer + SKU 主类目
2. BUYER            当前 Buyer、类目为空
3. CATEGORY         Buyer 为空 + SKU 主类目
4. GLOBAL           Buyer 与类目皆空
```

同级仍按 `rule_priority` 升序；同优先级重叠禁止发布。运行时再冲突则 `CONFLICT`，订单 FAILED。

策略仍以项目为**发布与冻结作用域**：GLOBAL/CATEGORY/BUYER 规则必须在项目激活或策略发布时**物化复制**进该项目策略版本。运行只读本项目已发布版本，从而同时满足 PRD 四级优先级与「禁止跨项目回退」。

SKU 级专属规则（现有 `platform_sku_id` 非空）特异性高于 `BUYER_CATEGORY`，避免无法为单 SKU 指定供应商。发布校验：同一输入不得被两条同优先级规则同时命中。

### 8.3 排序模式

`sc_supplier_routing_policy_version.ranking_mode`：

- `MANUAL`：`candidate_priority` 升序，再 `supplier_code`、`offer_id`。
- `PRICE_ASC`：当前有效 `supplierSettlementUnitPrice` 升序，再 `candidate_priority`、`supplier_code`、`offer_id`。

一个已发布版本只允许一种 `ranking_mode`。价格来自事实快照中的 Offer 单价，Engine 不查库。OPTIONAL 动态事实仍不得改变名次。

不做综合权重。无可用候选时保持现有 FAILED + `NO_ROUTABLE_SUPPLIER`，不建人工队列实体。

## 9. 管理端 REST

现有 `/supply-chain/master-data`、`/supplier-config`、`/routing-policies` 仅有写命令。本期按资源拆分，每个资源提供分页列表、详情、创建、更新、状态变更；禁止一个「任意 JSON 配置」接口覆盖多聚合。

接口契约对齐原型字段与操作，**不绑定页面布局**。详情页多个 Tab 对应同一供应商聚合的多个查询接口（或详情一次返回分块），由前端自行组页。金额存储 CNY 分；「元」仅前端展示。

最小资源（Controller 映射 `/supply-chain/{resource}`，与现有 `master-data` 一致；网关已加 admin-api。规格口语「管理端 /admin/supply-chain」不另套路径）：

| 资源 | 能力 |
| --- | --- |
| `/suppliers` | 列表筛选、详情（供货配置/合作商品）、开票子资源、录入编辑、状态（含黑名单）、期初开户 |
| `/buyers` | 分销商列表详情（关联项目/分销商品/层级只读）、开票子资源、录入编辑 |
| `/projects` | 项目列表/详情/目录项与 L3 覆盖 |
| `/products`、`/categories`、`/brands` | 商品/分类树/品牌；分类删除仍校验子节点与关联商品 |
| `/markup-templates` | 四类场景模板 CRUD 与发布 |
| `/orders`、`/supplier-orders` | 只读列表详情；发货类操作仅实物启用后开放 |
| `/aftersales` | 售后列表详情、运营发起仅退款 |
| `/routing-policies` | 四级规则、候选、`ranking_mode`、发布；保留无副作用模拟路由 |

管理端运营权限见 `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md`（本轮按资源拆功能权限，`supply-chain:admin` 为超集；未拆接口仍只认 `admin`）。供应商/分销商档案管理端 GET 在通过对应查询权限后按原值回传手机号、银行账号、法人身份证号及证件 fileKey（见 15.11）；库内信封加密与日志脱敏不变。查询接口不得在请求线程调用供应商。

列表默认排序：供应商/分销商按 `updated_at` 倒序；商品按 `created_at` 倒序；默认 pageSize=20。状态变更二次确认由前端承担，后端保持幂等条件更新。

## 10. 模块边界

| 模块 | 新增职责 |
| --- | --- |
| `tradecore.catalog` | L1/L2 价格版本、销售价冻结、模板绑定校验 |
| `tradecore.pricing`（新深模块） | 加价模板聚合、封闭算价、报价 Facade；不写订单与账本 |
| `tradecore.buyer` | 分销商档案扩展字段；不改 appId 语义 |
| `tradecore.supply` | 供应商档案、联系人、黑名单状态、结算账户期初；不写采购商账本 |
| `routing` | `scope_type`、`ranking_mode`；Engine 增加价格键排序 |
| `border.fulfillment` | CARD 售后 Case 与 Lifecycle |
| `interfaceapi.admin` | 第 9 章 REST；不编排供应商调用 |

`tradecore.pricing` 禁止依赖 `border` Mapper。算价单测不启 Spring。

## 11. 错误码（增量）

| 码 | 是否建单 | 含义 |
| --- | --- | --- |
| `PLATFORM_SETTLEMENT_PRICE_MISSING` | 标准商品可建 FAILED | 无 L1 且无可用继承价 |
| `MARKUP_TEMPLATE_INVALID` | 否 | 模板规则校验失败 |
| `SELLING_PRICE_SCENE_MISMATCH` | 否 | 实时取价场景键与模板不符 |
| `AFTERSALE_NOT_ALLOWED_IN_FULFILLING` | 否 | 履约中禁止售后 |
| `AFTERSALE_SUPPLIER_CAPABILITY_MISSING` | 否 | 无作废/解绑能力 |
| `AFTERSALE_TYPE_NOT_ENABLED` | 否 | 实物退货本期未启用 |
| `ROUTING_RANKING_MODE_INVALID` | 否 | 非法 ranking_mode |
| `SUPPLIER_OPENING_ALREADY_DONE` | 否 | 供应商期初已入账，禁止再改余额字段 |
| `SUPPLIER_CREDIT_CODE_DUPLICATE` | 否 | 公司名称+统一社会信用代码联合重复 |
| `SUPPLIER_ENUM_NOT_OPEN` | 否 | 发货时效/结算方式/对接方式本期未开放枚举 |

## 12. 测试门禁（增量）

`*Test`：L3>L2>L1 解析；模板全国/区域/影院命中；`PERCENT_OF_SUPPLY`；报价不写库；售后 FULFILLING 拒绝；VOID 成功后才返还账户；`PRICE_ASC` 乱序候选稳定；四级特异性与重叠禁止发布。

`*IntegrationTest`：L2 发布与项目目录同事务；CARD 售后与账本幂等；项目策略物化后改 GLOBAL 不影响已发布项目版本。

`*ArchitectureTest`：`tradecore.pricing` 不访问订单 Mapper；Admin Controller 不访问供应商 Adapter。

`*ContractTest`：目录投影含标准商品销售价、不含供应商结算价；报价 API 与受理互不影响。

## 13. 实施顺序

本轮（g14）：供应商档案 / 供货配置 / 合作商品草稿 REST。加价、分销商、售后、路由仍按下列总序后续单独立项。

1. CONTEXT 与本文（已完成）。
2. Liquibase：供应商档案扩展、黑名单、结算账户期初、供货配置、Offer 草稿商业列；`022-supplier-supply-scope-mode` 将供货模式迁到子表（已有子表则跳过）。
3. 管理端 `/admin/supply-chain/suppliers` REST。
4. （后续）`tradecore.pricing` + 三级价、路由四级、CARD 仅退款、分销商/项目 REST。

## 14. 关键验收

1. 同一 Buyer 两项目：一项目 L3 覆盖、另一继承 L2，互不影响。
2. 改 L1 不改已发布项目版本，直至项目重新发布目录。
3. 电影区域加价覆盖全国加价；报价与下单资金仍按场景既有金额规则。
4. 卡券成功单仅退款：无 VOID/UNBIND 被拒；有能力则主状态保持 SUCCEEDED，售后推进。
5. `PRICE_ASC` 选供应价最低且资格满足的 Offer；MANUAL 不因供应价变化换序。
6. 管理端可分页查询供应商/分销商；无前端也可经 REST 完成发布。
7. 新增供应商可写期初（含 0）；同一供应商第二次提交余额被拒；编辑页余额只读。黑名单供应商不进入新路由，在途履约可收敛。

## 15. 供应商管理端（原型已确认）

来源：墨刀供应商列表 + 新增供应商上下屏 + 详情供货配置/合作商品。编号自动生成；列表默认按更新时间倒序。

本节约束 **Admin REST 的字段、筛选、校验与状态机**。页面布局、Tab 样式、左右分栏仅为原型示意，后端按资源拆接口即可。

### 15.1 合作状态

管理端展示与 `SupplierStatus` 对应：

| 原型文案 | 枚举 | 新路由 | 可逆 |
| --- | --- | --- | --- |
| 正常 | `ACTIVE` | 允许 | 可暂停、可拉黑 |
| 暂停供货 | `SUSPENDED` | 禁止 | 可恢复为正常、可拉黑 |
| 黑名单 | `BLACKLIST`（新增） | 禁止 | 可移出为正常或暂停 |
| （无对应原型按钮） | `ARCHIVED` | 禁止 | 不可逆，不在新增页提供 |

列表操作：正常 → 详情/编辑/暂停/移入黑名单；暂停 → 详情/编辑/恢复/移入黑名单；黑名单 → 详情/编辑/移出黑名单。支持按勾选批量暂停、批量恢复。状态变更需二次确认（前端），后端条件更新。

`PUT /supply-chain/suppliers/{supplierCode}/status` 与 `PUT /supply-chain/suppliers/status/batch` 的 JSON 合作状态字段与列表筛选、档案保存同名，固定为 `status`。兼容旧字段 `targetStatus`；二者同时出现时以 `status` 为准。`resolvedTargetStatus` / `isTargetStatusPresent` 不得进入 OpenAPI。

黑名单不是归档：历史订单、回调、对账继续。不得把 `ARCHIVED` 当成黑名单。

### 15.2 列表

筛选：供应商名称、供应商编号、可售品类、供货模式、合作状态、更新时间区间。

列：名称、编号、可售品类、商品类型、商品分类、供货模式、合作商品 SKU 数、合作状态、接入时间、更新时间。同一供应商多条供货配置在单元格内折行展示，数据来自供货配置（详情页维护），不把品类写死在供应商主表。

列表「供应商名称」只取 `sc_supplier.supplier_name`（JSON `supplierName` 与 yudao 惯用 `name` 同值）。**不得**用 `legalEntityName`（公司名称）、`primaryContactName`（联系人）或 `shortName` 顶替。写入时请求体 `name` 优先于 `supplierName`，避免新增页把供应商名称打到 `name`、把公司名称打到 `supplierName` 后列表对不上。

### 15.3 新增/编辑表单

| 分组 | 字段 | 约束 |
| --- | --- | --- |
| 基础信息 | 供应商名称 | 必填；对应 `sc_supplier.supplier_name` |
| 基础信息 | 供应商编号 | 创建时系统生成，只读；对应 `sc_supplier.supplier_code` |
| 联系信息 | 姓名、手机号、邮箱 | 多行；见 15.9 |
| 能力标签 | 配送区域 | 全国 / 指定区域；详情同时返回 `deliveryRegionAdcodes[]` 与 `deliveryRegions[{adcode,name}]`，避免页面只显示行政区划编码；见 15.9 |
| 能力标签 | 发货时效 | 可空；非空拒绝，见 15.5 |
| 能力标签 | 是否支持售后 | 是 / 否；见 15.9 |
| 商务信息 | 结算方式、结算周期（天）、对接方式、接入时间、合作状态、合作说明 | 结算方式/对接方式可空，见 15.5；说明 ≤200 字 |
| 公司及法人 | 公司名称、统一社会信用代码 | 必填；二者联合唯一 |
| 公司及法人 | 营业执照、法人身份证正反面、法人身份证号、法人姓名/手机号、联系地址 | 证件 fileKey 信封加密存储；身份证号明文列；管理端 GET 明文回传，见 15.11 |
| 账户 | 开户行、开户账号 | 账号信封加密存储；管理端 GET 明文回传，见 15.11 |
| 账户 | 当前账户余额 | **仅创建时可写期初**；详情只读返回 `accountBalanceCents`，兼容保留 `settlementBalanceCents`，见 15.4 |
| 资质 | 资质文件 | 加密存储 |

供货配置、合作商品不在新增表单，放详情标签页（供货配置见 15.6）。

`POST/PUT /supply-chain/suppliers` 的 `SupplierSaveReqVO`（及嵌套联系人）必须给每个请求字段写 `@Schema(description)`，文案与对应 DDL COMMENT 一致，供 Knife4j「参数说明」展示。`name` 无独立列，说明为「供应商标准名称」并注明与 `supplierName` 同义、同时出现时以 `name` 为准。请求侧手机号、开户账号、证件为明文/`fileKey`，说明不带「密文」。`resolvedSupplierName` / `isSupplierNamePresent` 不得进入 OpenAPI。JSON 接入时间字段名固定 `onboardAt`（见 15.11）。

### 15.4 供应商结算账户期初

运营可在新增时录入期初余额（CNY 分，允许 0，禁止负数）。语义：中台为该供应商登记的**预存可用额度**（还可用于向该供应商采购的账面额度），不是银行账户余额，也不是 Connector 远程观察（`sc_supplier_account_balance_snapshot`）。

规则对齐采购商开户，但**不得写入采购商唯一账本**：

1. 创建供应商的同一本地事务内：创建 `sc_supplier_settlement_account`，写入期初，追加唯一 `entry_key=OPENING_BALANCE:{supplierId}:CNY` 的期初流水。只允许成功一次。
2. 编辑页展示当前余额为只读；详情响应以 `accountBalanceCents` 返回当前账本余额，单位为 CNY 分，并兼容保留同值的历史字段 `settlementBalanceCents`。请求体若再带余额则 `SUPPLIER_OPENING_ALREADY_DONE`。
3. 本期履约成功**不自动扣减**该账户，也不据此硬拒单。路由若启用供应商余额 `REQUIRED`，仍只使用 Connector 观察快照，不读本账户。后续充值、履约扣减、应付对账另立变更集。
4. 开户行/账号与余额分列：改银行资料不改期初流水。

### 15.5 未给截图项的冻结（g14）

不再等待墨刀枚举截图。禁止自由字符串落库。

| 项 | 冻结 |
| --- | --- |
| 供货模式 | 封闭：`API_DIRECT`（API 直发）、`DROP_SHIP`（一件代发） |
| 可售品类 / 商品类型 / 商品分类 | 三列均存 `sc_product_category.id`，且必须是已启用类目的祖先链：品类为 1 级、类型为其子、分类为类型之子。选项来自类目树查询，不另造品类枚举 |
| 指定区域 | `DESIGNATED` 时至少 1 个 6 位数字行政区划码（国标 adcode，省或市）；`NATIONWIDE` 时必须空。不实现省市区控件 |
| 发货时效 / 结算方式 / 对接方式 | **可空**。若传入非空值则拒绝（`SUPPLIER_ENUM_NOT_OPEN`）。字典 GET 返回空列表。补枚举只追加，不改语义 |
| 证件 / 资质 | 接口收文件引用 `fileKey`（非 multipart）；库内加密存储引用。无引用则空 |
| 管理端创建的供应商类型 | 固定 `STANDARD_SUPPLIER`。O2O 商户仍走既有渠道绑定命令 |
| `short_name` | 无原型字段；写入时等于 `supplierName` 截断至 64 字 |

开票见 15.10 与 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md`。

### 15.6 详情 — 供货配置

详情页顶栏（各标签页共用）：供应商名称、合作状态、编辑、编号、可售品类汇总、主联系人、手机号、合作商品数（SPU）、供货配置组数、接入时间。顶栏返回 `spuCount`：以未退役 Offer 关联的平台 SKU 为范围，按 `sc_platform_sku.spu_id` 去重计数。列表「合作商品 SKU 数」及合作商品页标题「共 N 个」继续返回 `skuCount`，按未退役 Offer 行计数；SPU 与 SKU 两种口径不得混用。

页签：基础信息、开票信息、供货配置、合作商品。开票契约见 15.10。

供货配置说明：「配置供应商可供货的商品范围与供货规则，新增商品时将根据此配置筛选可添加的商品。」

行字段（可多行，「+ 添加供货配置」增行，行内删除）：

| 字段 | 默认 | 约束 |
| --- | --- | --- |
| 可售品类 | 空 | 必填；1 级已启用类目 id |
| 商品类型 | 空 | 必填；品类的直接子类目 |
| 商品分类 | 空 | 必填；类型的直接子类目 |
| 供货模式 | 空 | 必填；`API_DIRECT` / `DROP_SHIP` |
| 供货上限 | 9999999 | 必填，整数 ≥ 1；不与预留的最低起订量比较 |
| 允许最低起订量 | 1 | 必填，整数 ≥ 1；预留字段，仅存储和回显，当前不参与业务判断 |

保存时四段选择（品类 → 类型 → 分类 → 模式）及两个数量均须有值，缺一拦截；两个数量分别校验正整数，不建立大小关系。允许最低起订量是兼容后续能力的预留字段，不参与范围命中、商品筛选、供货上限校验、合作商品默认值或下单校验。同一供应商下 `(可售品类, 商品类型, 商品分类)` 唯一，重复则冲突，不得靠供货模式拆成两行绕过。

供货配置是筛选合作商品的资格，不是 SupplierOffer。新增合作 SKU 只能落在已配置范围内。改配置不改已发布 Offer 与在途单；收窄范围只阻止新关联。

列表页的可售品类/商品类型/商品分类/供货模式列由本表聚合折行，不冗余写在 `sc_supplier`。

**供货模式存子表（与共享测试库对齐）**

共享测试库已被另一分支执行 `021-admin-frontend-alignment`：`sc_supplier_supply_scope.supply_mode` 已删除，模式落在 `sc_supplier_supply_scope_mode`（`supply_scope_id` + `supply_mode`，UNIQUE `(supply_scope_id, supply_mode)`）。本分支**禁止**把 `supply_mode` 加回主表。

本分支 REST 仍按上表一行一个 `supplyMode` 字符串读写，不改为 `supplyModes` 数组。保存时主表只写真值类目与数量，模式插入子表一行。列表、详情、按供货模式筛选、`GET .../supply-scopes` 只读子表；若子表对同一配置有多行（他分支写入），按 `id` 升序取第一项回填 `supplyMode`。

空库由 Liquibase `022-supplier-supply-scope-mode` 建子表、从主表列回填后删除主表列及 `ck_sc_supplier_scope_mode`。若子表已存在（测试库已跑 alignment），该变更集前置条件失败并 `MARK_RAN`，禁止再 `DROP COLUMN`。不得复用 id `021-admin-frontend-alignment`（checksum 会与已执行记录冲突，且该文件还含本分支未实现的买方/对接方式等变更）。

### 15.7 详情 — 合作商品

标题「合作商品列表（共 N 个）」统计当前供应商关联 SKU 数（与顶栏、列表页同一口径，见 15.6）。快捷筛选：权益商品、京东、电影快充、点餐；选项由已启用的供货配置动态生成。「+ 新增商品」打开 15.8 标品池。

供应价三模式（管理端说明条与下拉选项由 system 数据字典 `supply_chain_offer_price_mode` 下发；代码用封闭枚举 `OfferPriceMode` 校验，禁止自由文本，供应链进程不查询 system 字典表）：

| 原型文案 | 枚举 | 结算单价如何进 OfferVersion / 订单 |
| --- | --- | --- |
| 固定值 | `FIXED` | 录入供应价（>0）写入不可变 `supplierSettlementUnitPrice` |
| 实时更新 | `REALTIME` | OfferVersion 只固化模式，不把瞬时价当永久单价；受理/路由采集时写入该次 `expectedSupplierSettlementAmount` |
| 按供货折扣 | `DISCOUNT` | 固化销售建议价与折扣率，发布时计算 `建议价 × 折扣率` 写入单价；折扣率 0.01%～100% |

金额接口与存储仍为 CNY 分；列表「元」由展示层除 100。

表格按 SPU 分组，每 SKU 一行：商品名称+SPU 编号、SKU 规格、SKU 编号、类型/分类、销售建议价、供应价模式、供应价、供货模式、供货上限、最低起购量、状态、操作（编辑、移除）。合作商品自身仍要求供货上限 ≥ 最低起购量；SKU 上限未填时继承所属供货配置行的 `maxQuantity`，SKU 最低起购量未填时固定取 1，不读取供货配置的预留 `minQuantity`。

状态与 `SupplierOffer` 对齐：草稿=`DRAFT`，已上架=`PUBLISHED`，已下架=`SUSPENDED`。移除二次确认后 `RETIRED`，列表不再展示，历史订单仍引用已冻结 OfferVersion。编辑已上架必须发新版本，禁止改已发布行上的单价/模式。

合作商品 = 该供应商对平台 SKU 的 Offer，必须落在 15.6 配置范围内。实时价不替代固定报价的不可变版本语义。

### 15.8 新增供应商品（标品池）

左右分栏。顶部说明：「从平台标品（SPU→SKU）中选择供货商品。根据供应商的供货配置筛选可售品类、商品类型、商品分类。」

**左侧选择区**

- 快捷标签：全部商品 + 由供货配置动态生成的品类（原型示例：权益商品、点餐、电影快票、京东）。未配置的品类不出现，也不可搜到。
- 搜索：名称/编号、商品分类下拉、搜索、重置。
- 表格列：勾选、商品名称（SPU 编号）、SKU 规格、类型/分类、销售建议价（元）。
- 按 SPU 分组；勾选 SPU 即选中其下全部可选 SKU，也可只勾单个 SKU。
- 候选仅含平台已启用、且命中该供应商供货配置、且尚未作为未退役 Offer 关联本供应商的 SKU。点餐/电影等建议价可为 0，表示走实时价，不表示免费供货。

**右侧已选区**

- 标题「已选商品 N」为已选 SKU 数。
- 批量配置：供应价模式、供应价/折扣、供货模式（原型默认一件代发）、供货上限（默认 9999999）、最低起购量（默认 1）。供应价模式下拉读取 system 数据字典 `supply_chain_offer_price_mode`。【清空】去掉全部已选；【应用】把批量值写到当前已选每一行（不提交保存）。
- 已选列表按 SPU 分组，每 SKU 必配：供应价模式、供应价或折扣、供货模式、供货上限、最低起购量；支持移除单 SKU。
- 模式联动：`FIXED` 显示供应价输入；`DISCOUNT` 显示折扣%；`REALTIME` 供应价/折扣只读展示「API 实时获取」，禁止填固定价。
- 底部再次展示供应价三模式说明。按钮：取消、确定。

**确定校验**

- 至少 1 个 SKU。
- `FIXED`：供应价 > 0（分）。
- `DISCOUNT`：折扣率 ∈ [0.01%, 100%]；销售建议价必须 > 0，否则拒绝（建议价为 0 的场景 SKU 不得用折扣模式）。
- 供货上限 ≥ 最低起购量。
- 每个 SKU 必须能唯一命中一条 15.6 供货配置，否则拒绝。
- 成功则按 SKU 创建 `DRAFT` Offer（或直接发布规则待编辑页约定；原型确定后默认创建为草稿，上架在列表编辑）。已存在未退役 Offer 的 SKU 不得重复添加。

### 15.9 详情 / 编辑 — 基础信息

来源：墨刀「供应商基础信息」屏 + PRD 8.1.2。PRD 6.1：供应商自助提交资料与资料审核本期不做，由运营在管理端录入。

本屏只含三组：**基础信息、联系信息、能力标签**。商务/公司及法人/账户/资质仍按 15.3，不在本屏。新增与详情/编辑共用同一字段契约；创建时编号回「自动生成」，已创建后回实际 `supplier_code`、只读。

开票预填与独立保存见 15.10。

**基础信息**

| 原型 | 接口 / 列 | 约束 |
| --- | --- | --- |
| 供应商名称 * | `supplierName` | 必填 |
| 供应商编号 | `supplierCode` | 系统生成，请求体忽略 |

**联系信息**

独立子表 `sc_supplier_contact`（`supplier_id` + `sort_no`），不把多联系人塞进 `sc_supplier` 单列。

| 列 | 约束 |
| --- | --- |
| 姓名 | 文本 |
| 手机号 | 文本；管理端 GET 按原值回显，日志继续脱敏（15.11） |
| 邮箱 | 文本，可空 |
| 操作 | 行内删除；「+ 添加联系人」追加一行 |

规则（PRD 8.1.2 + 本屏）：

- 默认至少一行空白行；联系人数量不限。
- 禁止删到 0 行；删最后一行返回业务错误。
- 保存时剔除三字段皆空的行后再校验「至少一行」。
- 非空行：姓名、手机号必填（顶栏主联系人依赖）；邮箱选填。PRD 未标红星，若墨刀后续对邮箱标必填则改契约。
- 顶栏/列表「主联系人、手机号」取 `sort_no` 最小的一行。

**能力标签**

| 原型 | 接口 / 列 | 约束 |
| --- | --- | --- |
| 配送区域 | `deliveryRegionType` | 封闭：`NATIONWIDE`（全国）/ `DESIGNATED`（指定区域） |
| 指定区域 | `deliveryRegionAdcodes` | 仅 `DESIGNATED` 必填；6 位数字 adcode 列表，见 15.5 |
| 发货时效 | `shippingSlaCode` | 第一期只允许 null，见 15.5 |
| 是否支持售后 | `aftersalesSupported` | `true`=是 / `false`=否 |

`aftersalesSupported=false` 时：对该供应商履约单发起售后返回已有错误码 `AFTERSALE_SUPPLIER_CAPABILITY_MISSING`；不改在途单、不改 Offer。本字段不是售后单类型。

REST：`GET/PUT /admin/supply-chain/suppliers/{id}` 读写以上字段（联系人为数组）。不单独为三组再拆写接口。

### 15.10 开票信息

PRD 8.1.3.1 / 8.2.3.1：开票标签页、从基础信息预填、个人/企业抬头、独立保存。具体表、路径、校验与预填映射以
`docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` 为准。档案接口仍不返回开票块。

### 15.11 管理端档案原值回显

供应商与分销商管理端用于运营建档和编辑回填。调用方通过对应资源 `query` 权限后，列表主联系人手机号及详情中的联系人手机号、法人手机号、开户账号、法人身份证号、证件 fileKey 均按保存原值返回，不提供 `*Masked` 响应字段。该回显口径不改变库内信封加密，也不授权日志、第三方调用或导出功能记录/传播这些原值；日志仍不得打印完整手机号、账号、证件号或 fileKey。

| 字段 | JSON | 存储 | GET |
| --- | --- | --- | --- |
| 接入时间 | `onboardAt`（毫秒时间戳，与全局 `LocalDateTime` 序列化一致）；请求兼容 `onboardedAt` | `sc_supplier.onboarded_at` | 原样回传。`null`、Unix 0、年份 `<=1970` 视为未填：创建回落当前 UTC 时间，更新忽略该字段；GET 对历史脏值回落到 `created_at` |
| 法人身份证号 | `legalPersonIdNo` | `sc_supplier.legal_person_id_no`，可空，`VARCHAR(32)` COMMENT `法人身份证号` | 明文 |
| 法人手机号 | `legalPersonMobile`（不再使用 `legalPersonMobileMasked`） | 信封密文 | 解密明文 |
| 开户账号 | `bankAccount`（不再使用 `bankAccountMasked`） | 信封密文 | 解密明文 |
| 联系人/主联系人手机 | `contacts[].mobile`、`primaryContactMobile` | 联系人表明文 | 明文 |
| 证件 fileKey | `licenseFileKey`、`idFrontFileKey`、`idBackFileKey`、`qualificationFileKey` | 信封密文 | 解密回传，供详情回显图片 |
| 编辑省略密文字段 | 请求体手机号/账号/fileKey 为空白 | 保留原密文，禁止用空值覆盖 | — |

## 16. 原型补录清单（后端字段）

只收字段、枚举、校验与操作；不收布局。补全后写入对应章节，再开对应 REST。

| 模块 | 页面 | 状态 |
| --- | --- | --- |
| 供应商 | 列表、新增、供货配置、合作商品、新增供应商品 | 已写入第 15 章 |
| 供应商 | 详情「基础信息」（名称/编号/联系人/能力标签） | 已写入 15.9 |
| 供应商 | 详情「开票信息」 | 见 `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` |
| 供应商 | 指定区域省市区；各下拉完整枚举 | 已按 g14 冻结（15.5），不再等截图 |
| 分销商 | 列表、录入/编辑、详情（关联项目/分销商品/层级；开票见上） | 待截图 |
| 项目 | 列表、创建/编辑、详情（基础信息/分销商品） | 待截图 |
| 商品/分类/品牌 | 列表、编辑、分类树 | 待截图 |
| 加价模板 | 电影快/慢充、点餐、外卖生活服务、商超 | 待截图 |
| 订单/售后/路由 | 列表、详情、配置 | 待截图 |
