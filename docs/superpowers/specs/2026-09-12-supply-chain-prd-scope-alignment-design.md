# 供应链中台 PRD 一期范围对齐

## 1. 文档定位

本文把 2026-09-12 对照《供应链中台管理系统一期产品需求文档（PRD）》的架构评审结论，收成**本期实际交付范围**。它裁剪的是「本仓供应链进程接下来做什么 / 明确不做什么」，不是重写 PRD。

**冲突时以本文为准的主题：** 售后聚合位置与状态机范围、路由节点（谁走路由 / 谁不走）、下单前选源 API、点餐实现责任、运营数据权限。通用批量定价、销售价、分销价、服务费和税务归属自 2026-09-15 起以 `docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md` 为准。

其余主题仍沿用既有权威：

| 主题 | 权威文档 |
| --- | --- |
| 供应商 / 分销商 / 项目管理端 REST 字段、枚举、状态机 | `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` |
| 开票查询与保存 | `docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md` |
| 商品中心 8.4–8.7 | `docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md` |
| 运营功能 RBAC（不含数据范围） | `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md` |
| 拆仓后进程形态 | `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`、仓库 `AGENTS.md` |
| BuyerOrder / 资金预留 / 受理幂等 / Outbox·Timer / 路由纯计算 | `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md` |

`docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md` 的 **g1–g6、g8–g10、第 4.1 节「加价模板 / 路由四级 / CARD 售后」、第 6–8 章、第 10 章 `tradecore.pricing`** 被本文取代，不再作为本期实施依据。该文第 15 章供应商 REST 字段仍有效。

不改变的内核：BuyerOrder / SupplierOrder 状态机、资金预留不变量、受理回执永久幂等、Outbox/Timer、项目应用身份、O2O/INSTORE 渠道金额规则、`SupplierRoutingEngine` 无数据库/HTTP/时钟。

PRD 来源：飞书文档本地导出
`/Users/mac/Documents/捷荣金服项目文档/供应链中台管理系统一期产品需求文档（PRD）/供应链中台管理系统一期产品需求文档（PRD）.md`
（2026-09-12 核对：Markdown 丢失全部表格；第七章功能清单、第九章性能、第十章验收及各节「筛选条件 / 列表字段 / 字段说明」不能当无需求。字段级仍以墨刀 + 已落地 REST 设计为准，缺字段禁止猜测。）

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| s1 | 场景加价模板（电影快/慢充、点餐品牌、H5 渠道、城市级）**本期不做**。不新建 `tradecore.pricing`，不新增模板表。`021-admin-frontend-alignment.sql` 已发布的 `sc_buyer_product_settlement_price*` 历史预留表保留但停用，不参与本期价格读写。 |
| s2 | 中台**同时管理** C 端销售价与 B 端分销价（结算价）。销售价不交给商城私自改算。 |
| s3 | 销售价来源 = 平台 SKU `suggested_sale_price`（及商品行内调价）。无区域差、服务费、品牌差。利润阈值自动下架本期不做。 |
| s4 | `L2AdjustMode.FOLLOW_TEMPLATE` **不表示运行时存在模板层**。真实语义：清除该 SKU 的手工 L2 覆盖，结算价回退到 L1。枚举名历史遗留，实现时必须在枚举与管理端 OpenAPI 写明；运行时不得查询历史预留模板表。 |
| s5 | 平台售后新建 `border.aftersale` 聚合。不得把运营售后单写进 `sc_o2o_refund_case` / `sc_instore_refund_case`（渠道协议回执）。 |
| s6 | 本期售后轻量：类型仅 `REFUND_ONLY` / `OTHER`；状态 `待平台审核 → 待分销商处理 → 已完成 / 已拒绝 / 已撤销`。不建邮寄、供应商确认收货、用户确认收货、上门取件、退货地址、包裹表。 |
| s7 | 实物履约（京东/唯品会 Adapter、包裹、发货、退货退款物流）**本期不做**。只做虚拟卡券/直充 + 已有 O2O/到店。 |
| s8 | **大润发（`INSTORE`/`RT_MART`）与淘宝闪购（`O2O`）不走供应商路由。** 固定商户：大润发靠渠道绑定；闪购由用户在 H5 选店，收银锁定，失败不换供。 |
| s9 | 点餐领域模型 = 肯德基/麦当劳券进入平台商品池、多家供应商抢单、**下单后**走标准商品路由。`DINING` 仅作为 `sc_product_type` 字典参与 Tab / 供货范围 / 经营范围。**本仓本期不实现**点餐履约、点餐连接器、点餐专属路由配置。 |
| s10 | **不做下单前选源 API。** 此前「中台暴露选源、商城下单前调用」整段作废。 |
| s11 | `sc_scenario_routing_rule` / `sc_scenario_routing_candidate` 继续空表。本期不为闪购、大润发、点餐补场景路由应用服务。 |
| s12 | 标准商品路由保持现有静态优先级（`candidatePriority` → `supplierCode` → `offerId`，原因码 `SELECTED_BY_STABLE_PRIORITY`）。综合排序、稳定性/时效评分、权重、topN、`PRICE_ASC` 运营配置本期不做。 |
| s13 | 运营数据权限 / 多租户数据隔离本期不做。维持 `docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md` 的 r4「本轮落地 B：功能权限 + 数据全量」，ArchUnit 继续禁止管理端 `@DataPermission`。PRD 11.2 记为已知偏差。 |
| s14 | 术语双轨：PRD「分销商」= 代码 `Buyer`。代码、表名、日志不改名。 |

## 3. 术语

| PRD / 管理端 | 代码 / 表 | 本期口径 |
| --- | --- | --- |
| 分销商 | `Buyer`（`sc_buyer`） | 不变 |
| 分销价 | L1/L2/L3 `buyerSettlementUnitPrice` | 仍是 B 端采购结算价；取价 L3 > L2 > L1 |
| 销售价 | `sc_platform_sku.suggested_sale_price` | C 端建议零售价，CNY 分；**不是**模板算价结果 |
| 供应价 | `SupplierOfferVersion.supplierSettlementUnitPrice` | 不变 |
| 跟模板（页面） | `L2AdjustMode.FOLLOW_TEMPLATE` | **清手工 L2，继承 L1**；无模板可跟 |
| 下单前路由 | — | 本期无此节点 |
| 平台售后单 | 规划中的 `border.aftersale` | 挂 `sc_supply_chain_order`；渠道退款 Case 不是它 |

O2O / INSTORE 的 B 端冻结金额继续 `buyerSettlementAmount = channelPayAmount`，不读三级结算价，也不读 `suggested_sale_price`。

## 4. 范围

### 4.1 本期做（相对 PRD 的可交付裁剪）

已有、继续维护：

- 供应商 / 分销商 / 项目档案与管理端 REST（含开票、供货配置、合作商品、分销商品、项目商品）。
- 商品中心：SPU/SKU、分类树、品牌、动态 `sc_product_type`。
- 三级结算价数据骨架与手工 L2/L3 覆盖。
- `FOLLOW_TEMPLATE` 已统一为清手工覆盖并回退 L1，应用层禁止读取历史模板价预留表。
- 标准商品受理、资金冻结、下单后静态路由（仅卡券/直充等已接入履约类型）。
- O2O（淘宝闪购）与 INSTORE（大润发）固定商户链路：入口、建单、资金、渠道退款 Case。
- 平台订单管理端只读列表/类型化详情（虚拟 + O2O/到店安全字段；不接包裹与履约资产），详见 `docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md`。

待建设（本文锁定边界，实施另立变更集）：

- `border.aftersale` 轻量售后头表 + 商品行 + 状态日志 + 管理端列表/详情/审核命令。

### 4.2 本期明确不做

- 加价模板引擎与所有场景模板页（PRD 8.8–8.10）。
- 实时报价 API（`BuyerSellingPriceQuoteApi`）、区域/影院/品牌/渠道加价、服务费。
- 下单前选源 API、路由节点区分管理界面。
- 路由四级配置组物化、综合排序、运营评分、人工处理队列实体。
- 点餐履约与点餐抢单路由落地（模型见 s9，实现不在本仓本期）。
- 电影快充/慢充 Tab、电影定价模板；电影仍是独立进程，不得并入供应链容器。
- 京东 VOP、实物发货/物流/退货退款 8 状态里的实物环节。
- 供应商自助入驻审核（运营代录维持现状）。
- 输出服务 H5/API/SaaS 模板（PRD 已划二期）。
- 管理端前端页面（后端 REST 契约除外）。
- yudao `@DataPermission`、按供应商/分销商/项目 code 的数据范围（RBAC 目标 C）。
- 将 `Buyer` 重命名为 Distributor。
- O2O/INSTORE 改走标准商品路由。

## 5. 销售价与分销价（无模板）

### 5.1 两套价格，两种存储

```text
分销价（B 端结算）  L3 项目覆盖 > L2 手工覆盖 > L1 平台结算价
销售价（C 端建议）  sc_platform_sku.suggested_sale_price（行内可改）
```

禁止：用销售价充当 L1/L2/L3；用加价公式从供应价推导销售价；把 O2O/INSTORE 渠道实付写成销售价后再冻结。

SKU `market_price` 仍是划线价，不是结算价，也不是本期对外「销售价」口径。

### 5.2 L2 与 `FOLLOW_TEMPLATE`

无模板发布事务。021 已发布的模板价预留表为保持 Liquibase checksum 稳定而保留，但应用服务不得读取、写入或把其中历史值作为 L2。L2 只可能来自：

- 缺省：无手工覆盖 → 解析时等同 L1。
- `EQUAL_L1`：粘性覆盖，值 = 当时 L1 结算分。
- `CUSTOM`：手填覆盖。

`FOLLOW_TEMPLATE`：把覆盖指针清掉，之后再解析即为 L1。`BuyerProductCommandService` 保持「只清指针、不碰模板表」；查询与项目发布同样禁止读取模板行。历史预留表即使存在数据，也不得改变有效 L2 或项目冻结价。

与 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` a5「L2 默认来自加价模板」冲突时，以本节为准。

### 5.3 对商城的含义

标准商品目录投影可以带 `suggested_sale_price`。商城不得按区域或渠道改算。场景实时取价本期无接口；电影/点餐/闪购/大润发不得因为「没有报价 API」去中台套标准 SKU 销售价当渠道应付。

## 6. 售后：`border.aftersale`

### 6.1 为什么新聚合

PRD 6.5 / 8.11 要的是跨履约类型的平台售后工作台。现有 `sc_o2o_refund_case`、`sc_instore_refund_case` 是渠道协议回执，状态机跟渠道命令绑定，不能当运营售后单。CARD 侧也还没有平台售后头表。

新建 `border.aftersale`：

- 挂 `sc_supply_chain_order`（及商品行）。
- Facade 编排；**禁止** `border` 访问 `tradecore.account.dal` / `tradecore.ledger`（既有 ArchUnit）。退款入账走既有资金 Port（与 INSTORE 返还同一条纪律：先确认用户侧退款成功再动采购账户）。
- 电影 / 点餐凭证只引用渠道或履约详情，售后单不复制取票码、取餐码。

### 6.2 一期状态机

类型：`REFUND_ONLY`（仅退款）、`OTHER`（其它，仅登记，不驱动物流）。

```text
待平台审核
    ├─ 通过 → 待分销商处理 → 已完成
    ├─ 拒绝 → 已拒绝
    └─ 撤销 → 已撤销
待分销商处理
    ├─ 完成退款协同 → 已完成
    ├─ 拒绝 → 已拒绝
    └─ 撤销 → 已撤销
```

主订单状态不因售后回退；只推进售后状态。`FULFILLING` 期间采购方取消仍拒绝（既有 `CANCEL_NOT_SUPPORTED_AFTER_ACCEPTANCE`）。建议仅 `SUCCEEDED` 后允许建售后单（与 08-28 §7.1 一致）。

CARD 仅退款仍依赖供应商 `VOID_ASSET` / `UNBIND_ASSET`：无能力则拒绝建单或停在待处理并记原因，不得假装完成。O2O/INSTORE 渠道发起的退款继续走原 Case；是否投影到平台售后单一并展示，实施时另开设计，默认本期**分开展示**，避免把渠道状态硬映射进轻量四态。

### 6.3 明确砍掉的 PRD 8 状态环节

「待用户邮寄 / 待供应商确认 / 待用户确认收货」无实物对象，本期不建。接口若被问到 `RETURN_REFUND`，返回明确错误（可复用 `AFTERSALE_TYPE_NOT_ENABLED`），不建空环节。

`docs/superpowers/specs/2026-08-31-after-sale-batch-operations-design.md` 属于已删除的 mall/trade 管理端，与中台 `border.aftersale` 无关，禁止复用其路径或权限串。

## 7. 路由

### 7.1 谁走路由

| 场景 | 本期 | 决策核 |
| --- | --- | --- |
| 权益 / 卡券 / 直充 | **下单后**走现有标准路由 | `SupplierRoutingEngine` 静态优先级 |
| 大润发 `INSTORE` | **不路由** | `sc_supplier_channel_binding` 固定商户 |
| 淘宝闪购 `O2O` | **不路由** | 用户选店，收银锁定 |
| 点餐 `DINING` | 模型为下单后标准路由；**不实现** | 后人可复用标准规则表，不新建选源 API |
| 电影 | 独立进程比价 / 场景表预留；**本仓本期不补服务** | 不得并入供应链容器 |
| 京东 / 唯品会 / 实物 | **不实现** | — |

PRD 8.6.4.1「下单前路由（点餐/大润发/淘宝闪购）」按 s8–s10 整段否定。`docs/供应链中台多场景概要设计.md` 中「大润发是固定商户，不走供应商路由」「盒马是固定商户，不自动更换」保持有效。

### 7.2 标准商品路由本期边界

- 规则表继续 `buyer_id` + `platform_sku_id` + `fulfillment_type` + 数量区间等已有维度。
- **不要**给 `sc_standard_product_routing_rule` 加品类列去模拟 PRD 四级配置组。
- **不要**启用或补齐 `PRICE_ASC` / 综合权重。引擎保持 `M1_ORDER`。
- 无可用供应商：保持现有失败原因码，不建人工队列实体。
- `sc_scenario_routing_*`：表结构保留，Java 应用服务本期不写。禁止把 O2O/INSTORE 商户绑定改写成场景路由候选。

## 8. 数据权限

PRD 11.2 要求数据权限与多租户隔离。中台 `sc_*` 无 `tenant_id`。本期继续：

- 功能权限：`@PreAuthorize` + `supply-chain:{resource}:{action}`。
- 数据全量：通过功能权限即可看到全部供应商 / 分销商 / 项目。
- 禁止管理端使用框架 `@DataPermission`。
- yudao 租户只约束谁能登录，不隔离业务行。

目标 C（按供应商/分销商/项目 code 过滤）必须另立设计，并同时改 ArchUnit。本期验收不得以「按部门看不到别人的供应商」为通过条件。

## 9. 模块边界（相对 08-28 第 10 章的修正）

| 模块 | 本期职责 | 禁止 |
| --- | --- | --- |
| `tradecore.catalog` | L1/L2/L3 结算价、SKU 销售建议价 | 不建模板聚合 |
| `tradecore.pricing` | **不建** | — |
| `routing.domain` | 保持纯函数静态优先级 | 不读库、不引入评分字段 |
| `border.o2o` / `border.instore` | 固定商户履约与渠道退款 Case | 不调用标准路由引擎选供 |
| `border.aftersale` | 平台售后轻量状态机 | 不访问账户/账本 dal；不建物流表 |
| `border.fulfillment` | 既有 CARD 履约 | 不把渠道退款 Case 塞进这里冒充平台售后 |
| `interfaceapi.admin` | 既有主数据 REST；后续订单只读 + 售后命令 | 每个 HTTP 映射必须有 `@PreAuthorize` |
| 电影 / 淘宝闪购渠道进程 | 独立进程独立库 | 不得并入 `SupplyChainServerApplication` |

出站 Feign 仍仅显式注册会员相关 API；售后若需调商城收银台，必须先在 `RpcConfiguration` 显式注册，不得靠包扫描。

## 10. 对旧设计条款的逐条取代

| 旧条款 | 处置 |
| --- | --- |
| 08-28 g1/g2 本期做加价模板、路由配置组 | 否定。见 s1、s12 |
| 08-28 g3/g4/g5 模板算销售价与 L2 | 否定。见 s2、s3、§5 |
| 08-28 g6 实物退货退款预留类型 + CARD 仅退款走 fulfillment Case | 售后聚合改为 s5/s6；实物类型调用拒绝 |
| 08-28 g8/g9 `PRICE_ASC` 本期做 | 推迟。引擎保持静态优先级 |
| 08-28 g10 四级配置组对齐 PRD | 推迟。不做配置组物化 |
| 08-28 §6 整章加价模板 | 二期文档；本期不实施 |
| 08-28 §9 `/markup-templates` | 不建 |
| 08-07 / 08-28 场景路由应用服务 | 表保留、服务不写 |
| 09-02 a5「L2 默认来自加价模板」 | 改为默认继承 L1（s4） |
| 09-11 d4 电影/点餐无专属履约 | 维持，与 s9 一致 |
| CONTEXT.md「L2 默认来自加价模板」「销售价由模板实时计算」 | 以本文 §3 / §5 为准，同步修订 CONTEXT |

## 11. 建议实施顺序

本文只锁范围，不代替变更集计划。推荐顺序：

1. 本文 + CONTEXT 词汇（本次）。
2. `FOLLOW_TEMPLATE` 注释与管理端 Schema 文案与 s4 对齐。
3. 平台订单管理端只读查询（虚拟 + O2O/到店；已按 `2026-09-12-supply-chain-order-admin-readonly-design.md` 落地）。
4. `border.aftersale` 表结构、状态机单测、管理端审核 REST、资金 Port 协同。
5. 商品/供应商/分销/项目相对 PRD 的剩余交互缺口（仍以 09-02 / 09-11 字段为准），不改分层。

点餐、电影模板、加价引擎、路由评分各自另开设计，禁止搭顺风车改本期表。

## 12. 测试门禁（增量，实施对应变更时）

- `*Test`：L3>L2>无覆盖时回 L1；`FOLLOW_TEMPLATE` 清覆盖后等于 L1；售后非法类型拒绝；`FULFILLING` 禁售后；路由引擎原因码仍为 `SELECTED_BY_STABLE_PRIORITY`。
- `*ArchitectureTest`：不出现 `tradecore.pricing`；`border.aftersale` 不依赖账户/账本 dal；管理端仍禁止 `@DataPermission`；O2O/INSTORE 应用服务不依赖 `SupplierRoutingEngine` 选供（渠道绑定保持）。
- 不加「模板命中 / 实时报价 / 四级配置组 / PRICE_ASC」单测作为本期门禁。

## 13. 关键验收（本期）

1. 管理端能改 SKU 销售建议价；目录/详情返回的销售价与 `suggested_sale_price` 一致，不存在模板或区域改写。
2. 分销商品选择「跟模板」后该 SKU 结算价回到 L1，库中无模板行也可完成。
3. 大润发建单、淘宝闪购收银锁商户，均不写入标准路由 round/record。
4. 商品类型列表含 `DINING`，但不能点餐下单、不能配点餐路由。
5. 无 `@DataPermission`；功能权限不足 403，权限足够则数据全量。
6. 实物退货类型接口拒绝；轻量售后未上线前，不把渠道 Case 冒充平台售后单交付。
