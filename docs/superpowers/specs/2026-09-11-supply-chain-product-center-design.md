# 供应链中台商品中心详细设计

本文根据飞书在线 PRD《供应链中台管理系统一期产品需求文档（PRD）》第八章（8.4 商品列表、8.5 商品分类、8.6 商品品牌、8.7 商品类型）及 09-07《供应链管理端表单字典与标品标签设计》，制定商品中心管理端后端 REST 契约、领域模型、状态机与供销对接机制。

---

## 1. 核心决策与设计基线

| 编号 | 决策项 | 详细说明 | 关联问题修正 |
| --- | --- | --- | --- |
| **d1** | **实施范围与 8.7 闭环** | 完整实现 PRD 8.4~8.7 管理端后端能力：商品列表（SPU+SKU 复合创建/编辑/分页/详情/上下架/逻辑删除）、商品分类树（可见性、同级防重与无限级层级递归）、商品品牌 CRUD、商品类型管理（卡片列表/CRUD/状态），并无缝对接供应商供货配置和分销商选品池。 | 8.7 闭环覆盖 |
| **d2** | **Liquibase 变更集编号** | 追加变更集 `028-platform-product-center-enhancement.sql`（接续仓库中已有的 `025`、`026`、`027`），负责 SPU/SKU/类目扩展、品牌唯一约束、解绑 DB CHECK 约束以及新建商品类型表。 | 变更集序号规范 |
| **d3** | **商品类型与 DB 约束、Java 枚举解耦** | 新建平台商品类型表 `sc_product_type`，预置 5 种基础类型；同时在 `028` SQL 中移除 `sc_platform_spu` 与 `sc_supplier_supply_scope` 上的硬编码 CHECK 约束。所有写入或校验 `productType` 的入口统一通过 `ProductTypeValidationService` 查询 `sc_product_type`，包括商品创建、供应商供货范围、分销商经营范围和存量主数据接口；`ProductTypeTag` 不再作为运行时准入规则。运营新增并启用类型后，无需修改 Java 枚举或重新发布服务即可贯通供销匹配。 | 解决类型扩展与 DB/Java 封闭枚举冲突 |
| **d4** | **业务 Tab 复合解构与动态来源** | 商品类型 Tab 由 `sc_product_type` 动态加载，普通类型 Tab 直接映射为 `productType=typeCode`；一期仅预置需要复合解构的虚拟卡密、虚拟直充和京东 Tab。电影、点餐仅作为普通商品类型数据保留，暂不设计电影快/慢充、点餐专属过滤或履约逻辑。 | 明确本期 Tab 边界 |
| **d5** | **类目无限级支持、同级重名校验与双维度控制** | 严格对齐 PRD 8.5.1「无限级分类」要求，取消人为硬限深度，支持多级树形扩展。在 `sc_product_category` 新增 `visible`（前端可见性：1-显示，0-隐藏）字段；新增/编辑分类时增加同一父类目下 `category_name` 不得重复的校验，并通过仅对未归档记录生效的数据库唯一索引防止并发穿透；父类目隐藏时子类目级联判定为不可见（`effectiveVisible`）；变更类目父级时，事务内递归重算整棵子树的 `category_level` 并强校验防循环挂载。SPU 增加 `secondary_category_ids` 辅助类目。 | 严格对齐 PRD 8.5 |
| **d6** | **删除强校验准确路径（4 表物理主键联合关联）** | 物理/逻辑删除平台 SPU 或 SKU 时，通过底层整数主键 `platform_sku_id` 扫描下游依赖：<br>1. `sc_supplier_offer.platform_sku_id = :skuId AND status != 90`<br>2. `sc_buyer_listing.platform_sku_id = :skuId AND status != 90`<br>3. `sc_buyer_product.platform_sku_id = :skuId AND status = 10`<br>4. `sc_project_listing.buyer_product_id IN (...) AND status = 10`（经由该 SKU 的全部 `sc_buyer_product` 间接关联，不按 BuyerProduct 状态过滤）。任一存在有效引用拒绝删除并抛出具名业务异常。 | 纠正关联列名、间接路径及已移除 BuyerProduct 漏检 |
| **d7** | **状态值域明确与枚举命名对齐** | 严格对齐代码现有枚举：`CatalogEntityStatus` 包含 `DRAFT (0)`、`ACTIVE (10)`、`INACTIVE (20)`、`ARCHIVED (90)`。文档统一使用 `INACTIVE` 替代 `DISABLED`；同时说明 `sc_buyer_product` 与 `sc_project_listing` 仅包含 `(10, 90)` 两态，而供给与上架单包含 `(0, 10, 20, 90)` 四态。 | 消除命名与状态域歧义 |
| **d8** | **跨模块 Mapper 依赖与验证收敛** | 新增/抽取独立商品删除校验器 `ProductDeletionValidator`，明确注入跨模块 Mapper：`SupplierOfferMapper`（属于 `tradecore.supply`）、`BuyerProductMapper` 与 `ProjectListingMapper`（属于 `tradecore.buyer`）、`BuyerListingMapper`（属于 `tradecore.catalog`），保持单向数据所有权并收敛强校验规则。 | 明确跨包依赖边界 |
| **d9** | **存量主数据写接口统一收口** | `M1MasterDataAdminController` 的品牌、分类、SPU、SKU 创建及状态变更端点全部委托到新的统一领域服务；旧契约可保留兼容，但不得保留独立写实现。新旧入口必须共享动态类型、分类防重、Schema 成对校验、状态机与删除引用规则。 | 堵塞全部旧写接口旁路 |
| **d10** | **SPU 与 SKU 状态解耦及全链路有效可售校验** | SPU 与 SKU 维持独立物理状态；SKU 的**有效可售状态（Effective Sellable State）**定义为双亲有效：`SPU.status == ACTIVE (10) && SKU.status == ACTIVE (10)`。新增 `ProductSellabilityService` 作为统一判断入口，商品池查询、供应商报价创建/发布、分销商品加入、采购商上架发布及履约受理均必须调用该校验。SPU 重新上架时绝不盲目恢复被运营单独置为 `INACTIVE` 的 SKU。 | 防止覆盖独立下架状态并封堵交易旁路 |
| **d11** | **复合表单契约、品牌唯一约束与 Schema 安全防线** | SPU+SKU 复合创建/编辑接口采用事务内原子保存；`sc_brand` 在 `028` DDL 中追加 `uk_sc_brand_name` 唯一索引；业务编码（`SPU_`, `SKU_`, `BRD_`, `CAT_`, `PT_`）支持「前端传入或缺省由 `BusinessNoGenerator` 自动生成」。全部管理端 SKU 写请求只暴露业务字段 `fulfillmentType`，不得要求管理前端传递内部 `targetSchemaCode/targetSchemaVersion/resultSchemaCode/resultSchemaVersion`；应用层通过 `PlatformSkuSchemaRegistry` 将封闭履约类型映射为固定的目标与结果 Schema 编码、版本并持久化，避免产生任意组合。详情响应保留四个字段作为只读诊断信息。已废弃的 `M1MasterDataAdminController` 兼容写入口也必须复用同一派生规则，不得保留旁路。 | 防止前端误配内部协议导致商品创建成功但无法履约 |
| **d12** | **详细 Swagger/OpenAPI 文档标准** | 所有管理端 Controller、方法、Query/Path 参数、Request/Response VO 必须提供完整的 OpenAPI/Swagger 3 注解（`@Tag`、`@Operation`、`@Parameter`、`@Schema`）。VO 字段描述必须与 DDL `COMMENT` 严格一致，参数注明枚举取值、单位（CNY分）、示例值与必填约束，并在操作描述中注明前置条件与错误码映射。 | 严格落实规范 |
| **d13** | **权限兼容与菜单授权闭环** | 新增细粒度权限必须集中定义在 `SupplyChainAdminPermissions`，方法表达式统一使用 `hasAnyPermissions('supply-chain:admin', '<细权限>')`，保持旧管理员权限为超集；同步维护供应链菜单 SQL 与权限契约测试，使细权限可实际分配。 | 防止旧管理员升级后 403 |
| **d14** | **类目选项接口向后兼容与管理树筛选** | 保留 `GET /supply-chain/categories` 只返回启用类目树的既有契约；新增 `GET /supply-chain/categories/management-tree` 返回包含草稿、停用节点及管理字段的管理树，支持状态 `status` 与关键字 `keyword` 筛选并采用树枝保留模式（保留命中节点及其祖先链路，剪除无关分支）。避免存量下拉组件把不可用类目重新选入业务表单。 | 防止类目选项接口行为回归 |
| **d15** | **标品选品池销售价投影** | `GET /supply-chain/products/skus` 为兼容 09-02 前端契约继续返回字段名 `marketPriceCents`，但该字段的业务值固定取 `sc_platform_sku.suggested_sale_price`；不得再取 `face_value` 或 `market_price`。`face_value` 是履约面值，`market_price` 是市场划线价，二者都不是 09-12 锁定的 C 端建议售价来源。 | 修复商品中心扩展字段落地后选品池仍沿用旧面值的偏差 |
| **d16** | **分类详情隔离持久化模型** | `GET /supply-chain/categories/{categoryCode}` 返回专用 `CategoryDetail`，保持现有业务字段但不再序列化 `ProductCategoryDO`。响应只含分类编码、名称、商品类型编码、分类描述、父分类 ID、层级、排序、可见性、状态和创建/更新时间；数据库主键、乐观锁版本及操作人 ID 不进入管理端契约。商品中心所有 Query/Path 参数补齐 `@Parameter`，所有响应 DTO 字段补齐与 DDL 语义一致的 `@Schema`。 | 防止数据库内部字段成为前端兼容包袱，并补齐分类编辑回显与 Knife4j 参数说明 |
| **d17** | **品牌与商品类型管理模型原型对齐** | 严格按管理端原型对齐主数据能力：<br>1. `sc_brand` 增加 `short_name`（品牌简称）和 `description`（品牌介绍与说明）；`BrandSaveReqVO.logoUrl` 增加 `@NotBlank` 必填校验；`BrandView` 补充 `shortName`、`description` 并显式投影 `spuCount`。<br>2. `sc_product_type` 增加 `icon_url`（类型图标）和 `description`（类型详细描述）；`ProductTypeSaveReqVO.iconUrl` 必填，支持创建时指定 `status`（10启用/20停用，缺省10）；`ProductTypeView` 补充 `iconUrl`、`description`，并一次性聚合未归档 `spuCount` 与 `skuCount` 两项统计指标，避免 N+1 查询。变更由 `029` 变更集执行。 | 彻底对齐原型字段与卡片双指标展示 |
| **d18** | **旧品牌创建接口终止写入** | 品牌创建统一使用 `POST /supply-chain/brands`。已废弃的 `POST /supply-chain/master-data/brands` 不再委托应用服务、不再执行参数校验或数据库写入，而是固定返回稳定业务错误 `BRAND_LEGACY_API_DEPRECATED`（`1_050_001_106`，不可重试），提示调用方迁移至新接口。其他 M1 兼容接口不受本决策影响。 | 关闭旧品牌入口绕过 Logo 必填约束的写入旁路 |
| **d19** | **商品管理列表端管理端聚合读模型与批量上下架** | 针对运营管理端原型中「SPU 嵌套多 SKU 展开展示」与复杂多维检索诉求：<br>1. 新增管理端聚合读接口 `GET /supply-chain/products/management-page`，整页一次批量拉取 SPU、SKU、生效 Offer 及供应商信息在内存组装，返回包含市场价、销售建议价、供货价、不含税供货价、税率、税额、供应商名称列表、总库存、兑换方式、下架方式的聚合模型。<br>2. 新增批量上下架接口 `PUT /supply-chain/products/batch-status`，支持批量上架、下架并记录 `unshelve_mode`（`MANUAL_UNSHELVE`）。<br>3. `030` 变更集：`sc_platform_spu` 增加 `unshelve_mode`；`sc_platform_sku` 增加 `tax_rate`；存量商品类型回填默认 icon 图标；为 `sc_platform_spu` 补充 `idx_sc_platform_spu_type_status` 复合索引；商品类型卡片双指标重构为数据库 `GROUP BY` 聚合，彻底消除大数据量下的内存风险。 | 支撑商品列表管理端原型与批量操作 |
| **d20** | **分销商/项目通用批量定价** | 2026-09-15 起由 `docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md` 取代：分销价、销售价、服务费、利润隐藏和税务默认归属分销商 + SKU，项目可分组覆盖；持久化公式与结果并在依赖变化时发布新版本。`031` 的平台 SKU 税务列仅保留为商品资料历史字段，不再作为分销结算税务来源。 | 扩展截图中的完整通用定价能力 |
| **d21** | **SKU 限购配置与项目级交易校验** | 限购配置归属平台 SKU。每人限购支持 `LIFETIME`（终身）、`YEAR`（每年）、`MONTH`（每月）、`WEEK`（每周）、`DAY`（每天）五种自然周期，计数维度固定为 `采购项目 + 用户标识（规范化手机号） + 平台 SKU`；每订单限购限制本次请求购买数量。标准履约请求新增必填 `projectCode`，由服务端校验项目属于已解析采购商，禁止把采购商 ID 或外部租户标识替代项目 ID。并发受理在 SKU 行锁内统计有效订单并校验额度；订单失败、取消或全额退款后通过订单状态投影自然退出计数，释放额度。现有一期 `quantity=1` 与单履约目标契约保持不变。 | 对齐商品编辑原型限购区并形成可并发校验闭环 |
| **d22** | **权益商品绑定手机号限购** | 原型「限购绑定手机号数量（权益商品专用）」对齐原卡管 `limit_number`：限制的是履约目标手机号，不是买家 `buyerUserId`，也不是一单绑几个号。配置仍归属平台 SKU，周期复用每人限购五值。计数维度为 `采购项目 + 履约目标手机号（target.accountValue 且 accountType=MOBILE） + 平台 SKU`。仅 `DIRECT_RECHARGE` 与 `ACCOUNT_BOUND_BENEFIT` 允许开启；卡号卡密/兑换码/券链不得开启，也不扩展其无手机号目标 Schema。直充与账户绑定权益订单把规范化手机号写入 `sc_supply_chain_order.fulfillment_account_value` 供行锁内聚合；卡密类该列为空。失败、取消、全额退款释放，部分退款不释放。一期 `quantity=1` 与单履约目标不变。 | 纠正原型「每订单」误读，并对齐卡管直充账号当日限量语义 |
| **d23** | **SKU 展示资料与商品分类映射** | 商品编辑原型中的 SKU 主图、SKU 详情图和商品描述归属平台 SKU，分别持久化为 `main_image_url`、`detail_image_urls` 与 `description`，通过 SPU+SKU 复合创建、编辑和详情接口统一保存与回显。原型「商品分类」唯一映射 `primaryCategoryId`，取值来自供应链独立库 `sc_product_category`，由 `GET /supply-chain/categories` 返回启用树，不是 system 数据字典；`productClassification` 继续作为供货范围命中的 `supply_chain_product_classification` 字典标签，两者不得混用。库存写能力由 d38 补齐，不再保持只读占位。 | 补齐 SKU 展示字段并消除两个“商品分类”的契约歧义 |
| **d24** | **标准履约手机号服务端规范化** | 标准履约 `buyerUserId` 和 `accountType=MOBILE` 的目标账号统一由服务端规范化为中国大陆 11 位手机号：接受裸号码、`+86`、`0086` 前缀及首尾空白，持久化、幂等指纹与限购统计一律使用去前缀后的 11 位号码；非法号码拒绝受理。调用方无需预先规范化，但完整手机号仍禁止写日志。公开履约数量继续固定为 `quantity=1`。 | 防止同一手机号因格式差异绕过项目级限购 |
| **d25** | **商品列表筛选字典与 SKU 状态口径** | system 数据字典新增 `supply_chain_fulfillment_type`（兑换方式）和 `supply_chain_sku_status`（商品状态）。`GET /supply-chain/products/management-page` 的 `status` 明确定义为 SKU 状态；SKU 级筛选命中任一未归档 SKU 后保留所属 SPU，并只返回符合 SKU 状态、兑换方式及市场价条件的 SKU 行。 | 对齐商品列表原型，消除 SPU/SKU 状态歧义 |
| **d26** | **分类商品类型与描述闭环** | `036-category-product-type-description.sql` 为 `sc_product_category` 增加可空 `product_type` 与 `description`。新版分类创建/编辑请求必须提交动态商品类型编码并通过 `ProductTypeValidationService` 校验其处于启用状态；分类描述最多 200 字，空字符串按 `NULL` 保存。历史分类不根据父级或 SPU 标签猜测回填，两个字段保持 `NULL`，由运营编辑后补齐。详情返回 `productType` 与 `description` 供页面完整回显。 | 修复分类编辑详情缺少商品类型和分类描述，避免只补 DTO 造成数据仍丢失 |
| **d27** | **SKU 限购周期数据字典化** | 管理端商品编辑/限购表单中“每人限购周期”与“绑定手机号限购周期”下拉选项统一由 system 模块数据字典 `supply_chain_purchase_limit_period` 统一下发，前端不再硬编码选项；字典值与现有 `PurchaseLimitPeriod`（LIFETIME/YEAR/MONTH/WEEK/DAY）严格保持一致。后端交易与履约校验及数据库 CHECK 约束保持不变。 | 对齐系统标准字典模式，避免前端硬编码选项 |
| **d28** | **SPU 履约方式上移与 SKU 只读继承** | 平台 SPU+SKU 复合创建/编辑接口将 `fulfillmentType`（履约方式/兑换方式）提升至 SPU 根属性，由 SPU 决定商品履约方式；SKU 保存项不再强制要求录入 `fulfillmentType`。数据库层在 `sc_platform_spu` 表新增 `fulfillment_type` 列与索引，并从关联 SKU 洗数回填；`sc_platform_sku.fulfillment_type` 继续保留并由所属 SPU 自动继承双写，保证下游订单、履约边界、路由引擎与数据库行级 CHECK 约束零破坏。SPU 分页与 Tab 过滤由 SPU 表直接等值匹配，消除关联 SKU 子查询。SPU 激活或存在有效外部引用后禁止变更 `fulfillmentType`，草稿态变更时级联更新所有草稿 SKU 及其 Schema。 | 业务模型对齐原型交互，杜绝同 SPU 下履约方式冲突，提升查询性能并保持下游零破坏 |
| **d29** | **SKU 销售建议价规则服务端持久化与计算** | `sc_platform_sku.suggested_sale_price` 继续保存销售建议价物化结果，并新增 `suggested_sale_price_rule_json` 保存该结果的生成规则。规则支持 `EQUAL_MARKET_PRICE`（等于市场价）、`CUSTOM`（`基准价格×百分比` 后执行加减乘除）和 `FIXED`（固定分值）；服务端使用十进制定点计算并最终 `HALF_UP` 到 CNY 分，前端试算结果不作为可信来源。规则写入只收口在 `POST /supply-chain/products/spus` 与 `PUT /supply-chain/products/spus/{spuCode}` 两条 SPU+SKU 复合保存链路，同一事务保存规则与结果，不再提供独立的 `/products/skus/suggested-sale-price` 批量写入口。未传规则但传入已变化的旧字段 `suggestedSalePrice` 时兼容归一为 `FIXED`，市场价与金额均原样回传时保留存量规则，避免旧管理端编辑其它字段造成配置丢失。存量数据迁移统一固定化，禁止根据金额相等猜测历史规则。该规则只生成 SKU 主数据建议价，不替代 09-15 通用定价的 `salesRule`；物化结果变化继续通过 `PricingDependencyChangedEvent` 重算下游不可变价格版本。 | 补齐新增/编辑商品中销售建议价模式、公式和固定值的保存回显，并隔离建议价与最终销售价语义 |
| **d30** | **SKU 销售建议价模式数据字典化** | system 数据字典新增 `supply_chain_suggested_sale_price_mode`，固定下发 `EQUAL_MARKET_PRICE`（等于市场价）、`CUSTOM`（自定义设置）、`FIXED`（固定值）三项，字典值与 `SuggestedSalePriceRule.Mode` 严格一致。system-server 通过 Liquibase `010` 自动迁移，`script/deploy` 同步提供存量环境热更脚本；两处均采用幂等新增与已知项同步，不删除管理员额外数据。供应链运行时仍以领域枚举为准，不查询 `system_*` 表，避免跨库依赖。 | 统一三种建议价规则的管理端展示来源，同时保持供应链独立进程和数据所有权边界 |
| **d31** | **SKU 销售建议价运算符数据字典化** | system 数据字典新增 `supply_chain_suggested_sale_price_operator`，固定下发 `ADD`（加）、`SUBTRACT`（减）、`MULTIPLY`（乘）、`DIVIDE`（除）四项，字典值与 `SuggestedSalePriceRule.Operator` 严格一致。pro143 已执行 `010`，因此必须追加 Liquibase `011`，禁止回改已发布的 `010` 产生 checksum 冲突；`script/deploy` 同步提供独立幂等热更脚本。供应链运行时继续以领域枚举校验，不跨库读取 system 字典。 | 统一 CUSTOM 公式运算符的管理端展示来源，并保证已部署环境可增量升级 |
| **d32** | **SPU 复合保存 Swagger 示例完整性** | `POST /supply-chain/products/spus` 与 `PUT /supply-chain/products/spus/{spuCode}` 共用的 `SpuSaveReqVO`、`SkuItemSaveReqVO`、`SuggestedSalePriceRuleReqVO` 中，每个请求字段必须声明具体且非空的 OpenAPI `example`。列表使用至少一个元素，对象使用可解析的非空 JSON；金额统一使用 CNY 分，CUSTOM 示例的市场价、百分比、运算符、运算数和物化建议价必须可以相互校验。两个端点还必须显式声明同一份完整 `ExampleObject` 请求体，规避 swagger-core 将嵌套对象转换为 `$ref` 后丢失字段级对象示例。即使字段可选或仅适用于特定模式，也必须提供独立字段示例，禁止 Swagger UI 回落为空字符串、空数组或无意义默认值。 | 让联调方可直接从 Swagger 获取完整可用的请求参数示例，避免可选字段因示例缺失被误解 |
| **d33** | **品牌管理端投影补齐主键与 SPU 复合保存支持品牌编码/ID 双轨解析容错** | 1. 品牌管理端响应模型 `BrandView` 补充内部主键 `id`（`Long`），与 `CategoryNode` 保持一致，支撑前端下拉选择组件获取并绑定真实主键。<br>2. SPU 复合保存模型 `SpuSaveReqVO` 支持 `brandId`（Long）与 `brandCode`（String）双轨入参，二选一即可。服务端按优先级解析品牌：传入 `brandCode` 时按业务编码匹配；传入 `brandId` 时先按主键匹配，查不到时自动将纯数字 ID 尝试按 `brand_code` 容错匹配（解决前端将纯数字编码绑定至 ID 导致的误报）；均匹配不到时抛出 `BRAND_NOT_FOUND`（1050001077）。<br>3. SPU 详情 `SpuDetail` 补齐 `brandCode` 字段回显，彻底打通品牌展示、选择与保存回显闭环。 | 解决前端品牌选择后提交报“商品品牌不存在”的契约缺陷与容错缺失 |
| **d34** | **SPU/供货范围 productClassification 接受类目树编码** | 管理端把 `sc_product_category.category_code`（如 `E2E_L1_20260903`）写入 `productClassification` 是合法契约，不得再抛 `ENUM_NOT_OPEN`。写入解析：先认 `ProductClassification` 三枚举名（存量兼容）；否则按类目编码查启用/未归档分类，命中则把该类目编码原样写入 `sc_platform_spu.product_classification` 与 `sc_supplier_supply_scope.product_classification`。若同时传 `primaryCategoryId`，必须与该类目为同一行，否则 `CATEGORY_NOT_FOUND`。未知码同样 `CATEGORY_NOT_FOUND`。供货命中仍是三码字符串全等，不回读类目树。`040` 变更集删除 `ck_sc_spu_product_classification` 与 `ck_sc_supplier_scope_product_classification`，禁止回改 `024`。 | 消除前端类目下拉与供货三码字段混用时被三枚举误拒 |
| **d35** | **复合保存兼容 SKU 图片别名与 SPU 根级限购下沉** | 管理端商品表单会把 SKU 主图、详情图写成 `picUrl`、`detailPics`，并把每人/绑定手机号限购放在 SPU 根级。`SkuItemSaveReqVO` 必须用 `@JsonAlias` 收下这两个别名并写入 `sc_platform_sku.main_image_url` / `detail_image_urls`。`SpuSaveReqVO` 根级限购字段在 SKU 未显式提交时继承到每个 SKU；SKU 已提交的字段优先。绑定手机号限购开启且未传周期时继承每人限购周期，以满足 `ck_sc_sku_phone_bind_limit`。限购仍只落在 SKU 表，不新增 SPU 限购列。详情 `SpuDetail` 按 SKU 完整回显入库字段，并额外投影首个 SKU 的限购到 SPU 根级；`SkuDetail` 在保留 `mainImageUrl`/`detailImageUrls` 的同时必须再序列化同值别名 `picUrl`/`detailPics`，保证编辑表单可按提交结构回填。只回主字段会导致表单图片为空，下一次全量保存把已入库图片写成 NULL。 | 避免前端别名字段与根级限购被 Jackson 静默丢弃，造成创建成功但详情缺图、缺限购 |
| **d36** | **商品管理数据字典 JSON 一律字符串** | 商品管理请求与响应中，所有对应 system 数据字典的字段值必须以 JSON 字符串返回和接收，与字典 `value`（如 `supply_chain_sku_status` 的 `"0"`/`"10"`/`"20"`）对齐。当前主要缺口是 `status` / `targetStatus` 被序列化成数字。Java 存储与数据库列保持 `SMALLINT`/`Short`，仅在管理端 VO/投影上使用 `@JsonFormat(shape = STRING)` 并将 OpenAPI `type` 声明为 `string`。已是字符串的字典码（履约方式、配送模式、限购周期、建议价模式/运算符、可售品类等）保持不变。 | 前端字典组件按字符串匹配选项，数字 `0` 无法命中字典值 `"0"` |
| **d37** | **关闭限购开关时忽略表单残留周期/数量** | 管理端关掉每人限购或绑定手机号限购后仍会提交对应周期、数量。`userLimitEnabled=false` 时忽略 `userLimitPeriod`/`userLimitQuantity`；`phoneBindLimitEnabled=false` 时忽略 `phoneBindLimitPeriod`/`phoneBindLimitQuantity`。落库规范化为 `NULL`，不得返回 `PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID`（1050001107）。开启时周期和正整数数量仍必填。每订单限购仅有开关 `orderLimitEnabled`，数量复用 `maxQuantity`，关闭开关不因 `maxQuantity` 拒绝。 | 避免编辑表单开关关闭后因下拉残留值整单保存失败 |
| **d38** | **SKU 资料库存可写可回显** | 管理端复合创建/编辑在 SKU 上读写 `skuInventory`（Java 字段名固定为此），持久化为 `sc_platform_sku.sku_inventory`。未传时创建落库 0，编辑省略保留原值，负数拒绝。详情 `SkuDetail` 原样回显；管理列表既有 `totalInventory` 改为投影该列，同时输出同值 JSON 别名 `skuInventory`。本列是商品资料数量，不接入 `InventorySource`，本期履约不预占、不扣减，公开契约仍为 `quantity=1`。变更由 `041` 执行。 | 对齐商品编辑表单库存字段，结束列表总库存写死 0 |
| **d39** | **管理端商品列表 productType 可多选且兼容单值** | `GET /supply-chain/products/management-page` 的 Query 参数 `productType` 改为 `List<String>`，HTTP 参数名不变。单个 `productType=PT_CARD` 仍按该类型过滤；重复键 `productType=A&productType=B` 与逗号拼接 `productType=A,B` 均规范化为去空白、去重后的编码列表，以 `product_type IN (...)` OR 命中。显式传入时覆盖 `tabCode` 解出的单个类型；未传、空列表或全空白视为未筛选类型。筛选参数不校验类型是否存在，非法编码得到空页。响应仍按 SPU 返回单个 `productType`/`productTypeName`。`GET /supply-chain/products/spus` 保持单值，不在本决策范围。 | 商品列表支持按多个商品类型筛选，同时兼容存量单值调用 |
| **d40** | **管理端商品列表 categoryId 可多选且兼容单值** | `GET /supply-chain/products/management-page` 的 Query 参数 `categoryId` 改为可多选，HTTP 参数名不变。单个 `categoryId=10` 仍按该主分类过滤；重复键 `categoryId=10&categoryId=20` 与逗号拼接 `categoryId=10,20` 均规范化为去空白、去重后的 Long 主键列表，以 `primary_category_id IN (...)` OR 命中。未传、空列表、全空白或无法解析为 Long 的片段视为未提供该片段；规范化后为空则不按分类过滤。只匹配 SPU 主分类，不扩展辅助分类。子孙展开见 d42。`GET /supply-chain/products/spus` 保持单值，不在本决策范围。 | 商品列表支持按多个主分类筛选，同时兼容存量单值调用 |
| **d41** | **供货配置查询回显 categoryId（归档即视为不可回显）** | `GET /supply-chain/suppliers/{supplierCode}/supply-scopes` 与供应商详情 `scopes[]` 的 `SupplyScopeView` 新增只读字段 `categoryId`：`productClassification` 为未归档类目编码（`sc_product_category.category_code`）时经 `CategoryQueryService.resolveCategoryIdsByCodes` 批量解析回填内部主键；为封闭枚举名、编码已删除或类目已归档时为 `null`（归档即视为不可回显，前端兜底展示编码）。批量解析一次查库。`PUT` 保存不接收该字段，写入口径不变。 | 前端类目树级联组件需内部主键回显，当前只回编码无法定位节点 |
| **d42** | **管理端商品列表 categoryId 含子类目** | 管理端商品列表从类目树勾选父节点时，语义为「该节点及其未归档子孙」下的商品。`GET /supply-chain/products/management-page` 在 d40 规范化出选中主键后，一次加载未归档类目（`status != 90`），按 `parent_id` 在内存 BFS 展开子孙，再与选中主键去重合并，以 `primary_category_id IN (选中 ∪ 未归档子孙)` 过滤。仍只匹配 SPU 主分类，不扩展辅助分类；归档子孙不进入展开集；选中主键即使自身已归档仍保留，以便历史主键精确命中。未传或规范化后为空则不查类目树、不加分类条件。无限级（d5）下用访问集合防环，禁止递归 SQL。 | 勾选父级（如 id=1）时须命中挂在叶子（如 id=3）上的商品，避免精确匹配空页 |
| **d43** | **SKU×供应商履约能力评分人工挂点** | 稳定性/时效评分写在 `sc_supplier_offer` 身份列（Liquibase `042`/`043`），新建默认 80。供应商合作商品与 `GET/PUT /supply-chain/products/skus/{skuCode}/fulfillment-scores` 读写同一事实。`management-page` 的 `suppliers[]` 不加分数。路由引擎本期不读分。权威契约见 `docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md`。 | 对齐新 PRD 8.4.1.1 / 8.1.3.4，纠正独立路由能力配置挂点 |
| **d44** | **最低有效供货价版本优先、身份列回退** | 商品列表、标品 SKU 查询与通用定价共用最低有效供货价：当前报价版本有效则用 `supplier_settlement_unit_price`；否则 `FIXED` 用 `draft_unit_price`，`DISCOUNT` 用建议价×折扣基点并 `HALF_UP` 到分，`REALTIME` 仍为空。不伪造缺连接器的 OfferVersion。权威口径见 `docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md`。 | 管理端合作商品只写身份列时列表供应价不再全空 |
| **d45** | **SPU+SKU 复合保存自动初始化平台默认定价** | `POST /supply-chain/products/spus` 与 `PUT /supply-chain/products/spus/{spuCode}` 在同一供应链事务内，为新增 SKU 或仍无当前平台定价版本的 `UNPRICED` SKU 发布第一版系统默认定价；已有人工定价版本绝不覆盖。系统默认规则固定为：分销价与销售价均直接等于市场价、服务费为零且隐藏自定义文案、分销区与销售区利润隐藏阈值均为 0、税务为不含税且 `NON_TAXABLE`。没有有效供货价时版本正常发布为 `DEPENDENCY_MISSING`；市场价低于供货价时以 0 阈值进入 `MARGIN_HIDDEN`，不得阻断商品主数据保存。已定价 SKU 的市场价或销售建议价变化继续复用原配置重算，禁止同一保存同时初始化和依赖重算而生成双版本。详细约束见 `docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态`。 | 消除前端未额外执行平台定价保存导致新 SKU 长期 `UNPRICED`、分销商品关联被阻断的问题 |
| **d46** | **商品聚合列表按显式参数排除已添加供应商品** | `GET /supply-chain/products/management-page` 同时服务商品列表和新增供应商品选品。`supplierCode` 在省略 `excludeAddedSupplierOffers` 或其为 `false` 时，仍只保留该供应商已有未退役 Offer 的 SPU；供应商不存在或没有未退役 Offer 时返回空页。排除已添加供应商品必须显式传 `excludeAddedSupplierOffers=true`，并同时传入 `supplierCode`。此时从候选和返回明细中去掉该供应商未退役 Offer 的平台 SKU，已退役 SKU 会再次出现；这次请求不再按「仅该供应商已供商品」收窄，也不按供货配置三码收窄。`buyerCode` 排除未移除分销商品的行为不变。`supplierCode` 空白返回 `SUPPLIER_CODE_REQUIRED`（1050001133）；供应商不存在返回 `SUPPLIER_NOT_FOUND`。`POST /supply-chain/suppliers/{supplierCode}/offers` 仍拒绝未退役重复，不把本次新增改成更新。 | 共用列表的供应商筛选保持原义，新增供应商品选品可跳过已添加 SKU |
| **d47** | **商品列表只展示正常供应商及其商品** | 商品列表页供应商下拉 `GET /supply-chain/suppliers?view=simple` 只返回 `ACTIVE`（正常）供应商。`GET /supply-chain/products/management-page` 在普通商品列表及新增分销商品选品场景中，只保留至少关联一个 `ACTIVE` 供应商未退役 Offer 的 SKU；`suppliers[]` 也只展示 `ACTIVE` 供应商，暂停、黑名单、归档供应商的 Offer 不参与商品可见性、供应商筛选或最低有效供货价。为避免首个供应商无法关联任何商品，新增供应商品选品 `excludeAddedSupplierOffers=true` 不施加“必须已有正常供应商 Offer”的准入条件，但返回的 `suppliers[]` 仍只展示正常供应商。显式 `supplierCode` 指向非正常供应商时返回空页。 | 防止商品列表展示暂停/黑名单供应商及其独占商品，同时保留新增供应商品闭环 |

---

## 2. 领域数据模型与表结构设计（028 Changeset）

### 2.1 平台 SPU 扩展 (`sc_platform_spu`)
```sql
-- 变更集：028-platform-product-center-enhancement.sql
-- 1. SPU 增加富文本、轮播图、配送模式、限购及辅助分类
ALTER TABLE sc_platform_spu
    ADD COLUMN secondary_category_ids JSON NULL COMMENT '辅助/扩展分类ID列表(JSON数组)' AFTER product_classification,
    ADD COLUMN slider_image_urls JSON NULL COMMENT '商品轮播图地址列表(JSON数组)' AFTER cover_url,
    ADD COLUMN description LONGTEXT NULL COMMENT '商品富文本详情与图文介绍' AFTER use_instruction,
    ADD COLUMN delivery_type VARCHAR(32) NOT NULL DEFAULT 'VIRTUAL' COMMENT '配送履约模式：VIRTUAL虚拟发货, EXPRESS物流快递, PICKUP自提核销' AFTER status,
    ADD COLUMN min_quantity BIGINT NOT NULL DEFAULT 1 COMMENT 'SPU默认单次最低起购量' AFTER delivery_type,
    ADD COLUMN max_quantity BIGINT NOT NULL DEFAULT 9999999 COMMENT 'SPU默认单次最高限购量' AFTER min_quantity;

-- 2. 移除 SPU 上的硬编码 product_type CHECK 约束（支持 8.7 动态类型）
ALTER TABLE sc_platform_spu DROP CHECK ck_sc_spu_product_type;
```

### 2.2 平台 SKU 扩展 (`sc_platform_sku`)
```sql
-- 3. SKU 增加建议价格与限购配置
ALTER TABLE sc_platform_sku
    ADD COLUMN market_price BIGINT NOT NULL DEFAULT 0 COMMENT '市场划线价/吊牌价，单位为分' AFTER face_value,
    ADD COLUMN suggested_sale_price BIGINT NOT NULL DEFAULT 0 COMMENT '建议零售价/销售建议价，单位为分' AFTER market_price,
    ADD COLUMN min_quantity BIGINT NOT NULL DEFAULT 1 COMMENT 'SKU单次最小起购数量' AFTER suggested_sale_price,
    ADD COLUMN max_quantity BIGINT NOT NULL DEFAULT 9999999 COMMENT 'SKU单次最大限购数量' AFTER min_quantity,
    ADD CONSTRAINT ck_sc_sku_market_price CHECK (market_price >= 0),
    ADD CONSTRAINT ck_sc_sku_suggested_price CHECK (suggested_sale_price >= 0),
    ADD CONSTRAINT ck_sc_sku_quantity CHECK (min_quantity > 0 AND max_quantity >= min_quantity);
```

#### 2.2.1 销售建议价规则（039 Changeset）

`039-sku-suggested-sale-price-rule.sql` 为 `sc_platform_sku` 增加
`suggested_sale_price_rule_json JSON NOT NULL`，COMMENT 为“SKU销售建议价生成规则JSON；物化结果保存在suggested_sale_price”。
该 JSON 是不可拆分的联合配置，不参与检索；应用层必须整体校验、整体写入。存量行统一回填为：

```json
{"mode":"FIXED","fixedPriceCents":9900}
```

不得把 `suggested_sale_price = market_price` 的存量行推断成 `EQUAL_MARKET_PRICE`，因为金额相同不能证明历史配置来源。

规则字段如下：

| 字段 | 语义 |
| --- | --- |
| `mode` | `EQUAL_MARKET_PRICE`、`CUSTOM`、`FIXED` |
| `base` | `CUSTOM` 必填：`MARKET_PRICE` 或 `SUGGESTED_SALE_PRICE`；后者使用本次修改前建议价快照，创建时禁止使用 |
| `percentage` | 大于 0、最多 2 位小数 |
| `operator` | `ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE` |
| `operand` | 加减时单位元且最多 2 位小数；乘除时为无量纲系数且最多 4 位小数；除数不得为 0 |
| `fixedPriceCents` | `FIXED` 必填的非负 CNY 分值 |
| `basePriceSnapshotCents` | 服务端字段；仅 `base=SUGGESTED_SALE_PRICE` 时记录计算使用的修改前建议价快照 |

结果小于 0、参数不完整、精度超限或除零均拒绝整次事务。市场价和固定价允许为 0。复合编辑未传规则且未传
`suggestedSalePrice` 时保留存量规则与结果；旧调用方同时原样回传未变化的市场价与建议价时也保留存量规则，
只有金额或市场价发生变化且未提交规则时才按显式金额落为 `FIXED`。创建时两者都未传则落为固定 0，保持既有默认值行为。

### 2.3 平台商品分类扩展 (`sc_product_category`)
```sql
-- 4. 商品分类增加前端展示可见性与索引
ALTER TABLE sc_product_category
    ADD COLUMN visible TINYINT NOT NULL DEFAULT 1 COMMENT '前端展示可见性: 1-显示, 0-隐藏' AFTER sort,
    ADD COLUMN live_parent_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN status = 90 THEN NULL ELSE COALESCE(parent_id, 0) END
    ) STORED COMMENT '未归档类目的父级唯一键，根类目使用0',
    ADD COLUMN live_category_name VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN status = 90 THEN NULL ELSE category_name END
    ) STORED COMMENT '未归档类目名称唯一键',
    ADD KEY idx_sc_category_visible_status (visible, status),
    ADD UNIQUE KEY uk_sc_category_live_parent_name (live_parent_key, live_category_name);
```

执行唯一索引前必须先审计历史数据；若存在同一父级下的未归档同名类目，变更集应明确失败并输出冲突记录，不得静默合并或归档业务数据。

### 2.4 平台品牌增加名称唯一索引 (`sc_brand`)
```sql
-- 5. 品牌增加名称唯一性物理防线
ALTER TABLE sc_brand
    ADD UNIQUE KEY uk_sc_brand_name (brand_name);
```

### 2.5 新建平台商品类型表 (`sc_product_type`)
```sql
-- 6. 新建商品类型表（覆盖 PRD 8.7）
CREATE TABLE sc_product_type (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品类型内部主键',
    type_code VARCHAR(32) NOT NULL COMMENT '商品类型业务编码',
    type_name VARCHAR(128) NOT NULL COMMENT '商品类型名称',
    sort INT NOT NULL DEFAULT 0 COMMENT '展示排序值，越小越靠前',
    status SMALLINT NOT NULL DEFAULT 10 COMMENT '管理状态：10启用，20停用，90已归档',
    remark VARCHAR(512) NULL COMMENT '类型备注与说明',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_product_type_code (type_code),
    UNIQUE KEY uk_sc_product_type_name (type_name),
    KEY idx_sc_product_type_status_sort (status, sort),
    CONSTRAINT ck_sc_product_type_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台商品类型配置表';

-- 预置历史 5 种基础类型
INSERT INTO sc_product_type (type_code, type_name, sort, status, remark, created_at, updated_at)
VALUES 
('EQUITY_PRODUCT', '虚拟权益商品', 10, 10, '虚拟卡密与直充类权益商品', NOW(3), NOW(3)),
('MOVIE', '电影票', 20, 10, '影院在线选座与兑换券', NOW(3), NOW(3)),
('DINING', '生活点餐', 30, 10, '本地生活餐饮美食点餐', NOW(3), NOW(3)),
('JD', '京东实物', 40, 10, '京东物流履约实物商品', NOW(3), NOW(3)),
('VIP', '大客户权益', 50, 10, '企业专享福利定制权益', NOW(3), NOW(3));

-- 7. 移除供应商供货范围的硬编码 product_type CHECK 约束
ALTER TABLE sc_supplier_supply_scope DROP CHECK ck_sc_supplier_scope_product_type;
```

字段长度与时间注释以 `028` 实际实现为准：`type_name` 取 `VARCHAR(128)` 与 `sc_product_category.category_name`、`sc_brand.brand_name` 对齐，`remark` 取 `VARCHAR(512)` 容纳运营说明；时间列注释统一为 `Asia/Shanghai`，与 `2026-09-04-supply-chain-database-local-time-design.md` 确定的本地时间口径一致，不再使用 UTC 表述。

### 2.6 `028` 中超出前述条目的补充变更与理由
以下改动同属 `028` 变更集，此处显式登记以保证文档与实现一致：

| 变更 | 理由 |
| --- | --- |
| `sc_platform_spu` 增加 `ck_sc_spu_quantity CHECK (min_quantity > 0 AND max_quantity >= min_quantity)` | 与 2.2 节 SKU 侧 `ck_sc_sku_quantity` 对称，把 SPU 默认起购/限购区间的不变量同样下沉到数据库，防止绕过应用层写入非法区间。 |
| `sc_platform_spu.product_type` 由 `NOT NULL` 改为 `NULL` | 配合 d3 解除 Java/DB 封闭枚举：类型编码改由 `sc_product_type` 动态维护，历史数据在类型治理期间可能短暂缺失归属。应用层准入仍由 `ProductTypeValidationService` 强校验，启用 SPU 必须具备有效类型。 |
| `sc_buyer_category_scope.scope_code` 执行 `MODIFY` 刷新列注释 | 该列语义从「可售品类枚举名」扩展为「可售品类枚举名或动态商品类型编码」，注释需与 d3 后的实际取值域一致。 |

### 2.7 品牌与商品类型原型对齐扩展（029 Changeset）
对齐管理端原型图 5/6/7/8 的主数据展示与输入要求，增加品牌简称、介绍与类型图标、描述：

```sql
-- 变更集：029-brand-and-product-type-enhancement.sql
-- 1. 品牌表 sc_brand 增加简称与介绍
ALTER TABLE sc_brand
    ADD COLUMN short_name VARCHAR(64) NULL COMMENT '品牌简称' AFTER brand_name,
    ADD COLUMN description VARCHAR(512) NULL COMMENT '品牌介绍与详细说明' AFTER logo_url;

-- 2. 商品类型表 sc_product_type 增加图标地址与类型描述
ALTER TABLE sc_product_type
    ADD COLUMN icon_url VARCHAR(512) NULL COMMENT '商品类型图标地址' AFTER type_name,
    ADD COLUMN description VARCHAR(512) NULL COMMENT '商品类型详细描述' AFTER status;

-- 3. 数据迁移：将存量商品类型的 remark 复制到 description
UPDATE sc_product_type SET description = remark WHERE description IS NULL;
```

### 2.8 商品管理列表端模型增强与类型优化（030 Changeset）
对齐管理端商品列表原型展示与多维检索诉求，物理落库下架方式与税率，并补齐商品类型存量图标与性能索引：

```sql
-- 变更集：030-product-management-grid-enhancement.sql
-- 1. SPU 表增加下架方式与类型状态复合索引
ALTER TABLE sc_platform_spu
    ADD COLUMN unshelve_mode VARCHAR(32) NULL COMMENT '下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架' AFTER status,
    ADD KEY idx_sc_platform_spu_type_status (product_type, status);

-- 2. SKU 表增加税率配置
ALTER TABLE sc_platform_sku
    ADD COLUMN tax_rate DECIMAL(6,4) NULL COMMENT '税率，如0.0600表示6%，NULL表示不征税/未配置' AFTER suggested_sale_price;

-- 3. 存量商品类型回填默认图标，避免空串
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/card.svg' WHERE type_code = 'PT_CARD' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/recharge.svg' WHERE type_code = 'PT_RECHARGE' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/movie_fast.svg' WHERE type_code = 'PT_MOVIE_FAST' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/movie_slow.svg' WHERE type_code = 'PT_MOVIE_SLOW' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/dining.svg' WHERE type_code = 'PT_DINING' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/jd.svg' WHERE type_code = 'PT_JD' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/default.svg' WHERE icon_url IS NULL OR icon_url = '';
```

### 2.9 分层批量定价与税务元数据隔离（031 Changeset）

L2/L3 批量定价继续复用 `sc_buyer_product_l2_override_version` 与 `sc_project_listing_version` 的不可变版本模型，不新增全局价格列。税务信息归属平台 SKU，仅用于管理端记录和展示：

```sql
-- 变更集：031-batch-pricing-tax-metadata.sql
ALTER TABLE sc_platform_sku
    MODIFY COLUMN tax_rate DECIMAL(6,4) NULL COMMENT '税率，如0.0600表示6%；仅记录展示，不参与价格计算',
    ADD COLUMN tax_included TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否含税：1含税，0不含税；仅记录展示，不参与价格计算' AFTER tax_rate,
    ADD CONSTRAINT ck_sc_sku_tax_rate CHECK (tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 1));
```

`tax_included` 与 `tax_rate` 不进入任何价格版本表，也不进入 `SettlementPriceResolver`。管理列表中的不含税供货价与税额仅是展示投影，不得回写、覆盖或改变市场价、销售建议价、L1 平台价、L2 分销商价、L3 项目价及最终结算价。

### 2.10 SKU 限购与标准履约项目归属（032 Changeset）

```sql
ALTER TABLE sc_platform_sku
    ADD COLUMN user_limit_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用每人限购：1启用，0停用',
    ADD COLUMN user_limit_period VARCHAR(16) NULL COMMENT '每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天',
    ADD COLUMN user_limit_quantity BIGINT NULL COMMENT '每人每周期最多购买数量',
    ADD COLUMN order_limit_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否启用每订单限购：1启用，0停用';

ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD COLUMN project_id BIGINT NULL COMMENT '采购项目内部主键；历史受理回执可为空';

ALTER TABLE sc_supply_chain_order
    ADD COLUMN project_id BIGINT NULL COMMENT '采购项目内部主键；历史订单及非项目场景可为空';
```

`032` 保留标准履约回执与总订单既有的 `(buyer_id, buyer_order_no)` 幂等唯一键，避免未写项目主键的
O2O 等既有订单失去唯一约束；`projectCode` 进入请求指纹，因此同一采购商业务单号切换项目仍按幂等冲突
处理。历史记录的 `project_id` 保持为空，避免从采购商或外部租户错误推导项目；所有新标准履约订单必须
写入请求中 `projectCode` 解析得到的项目主键。SKU 限购配置约束如下：关闭每人限购时落库周期和数量均为空（请求里若仍带周期/数量视为表单残留并忽略，d37）；开启时
周期必须属于上述五值且数量大于零。每订单限购复用 SKU 的 `max_quantity` 作为数量上限，开关关闭时
该值仅保留兼容展示语义。

### 2.11 权益商品绑定手机号限购（033 Changeset）

```sql
ALTER TABLE sc_platform_sku
    ADD COLUMN phone_bind_limit_enabled TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否启用绑定手机号限购：1启用，0停用',
    ADD COLUMN phone_bind_limit_period VARCHAR(16) NULL
        COMMENT '绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天',
    ADD COLUMN phone_bind_limit_quantity BIGINT NULL
        COMMENT '同一履约手机号每周期最多购买数量';

ALTER TABLE sc_supply_chain_order
    ADD COLUMN fulfillment_account_value VARCHAR(128) NULL
        COMMENT '履约目标手机号；仅直充与账户绑定权益写入，卡密类为空';
```

关闭绑定手机号限购时落库周期和数量必须为空（请求里若仍带周期/数量视为表单残留并忽略，d37）；开启时周期必须属于五值、数量大于零，且
`fulfillment_type IN ('DIRECT_RECHARGE', 'ACCOUNT_BOUND_BENEFIT')`。历史订单该列可为空，不回填。
新受理在目标为 `accountType=MOBILE` 时写入 `accountValue` 的规范化值，长度不超过 128；日志不得输出该列。
新增索引 `idx_sc_order_phone_bind_limit (project_id, platform_sku_id, fulfillment_account_value, created_at)`。

### 2.12 SKU 展示资料（034 Changeset）

```sql
ALTER TABLE sc_platform_sku
    ADD COLUMN main_image_url VARCHAR(512) NULL COMMENT 'SKU主图地址',
    ADD COLUMN detail_image_urls JSON NULL COMMENT 'SKU详情图地址列表(JSON数组)',
    ADD COLUMN description VARCHAR(200) NULL COMMENT 'SKU商品描述';
```

三个字段均为 SKU 可选展示资料；主图与每个详情图地址最长 512 个字符，描述最长 200 个字符。复合编辑按
完整 SKU 行语义处理：字段传空即清空原值。`detail_image_urls` 使用 MyBatis JSON TypeHandler 显式映射为
有序字符串列表。库存写能力见 d38 / `041`。

| **d26** | **分类商品类型与描述** | `sc_product_category` 补充 `product_type` 与 `description`。 | 补齐分类编辑回显 |
| **d27** | **分类多商品类型与原型字段对齐** | 严格对齐管理端原型与前端分类表单：<br>1. `sc_product_category` 增加 `type_codes JSON`，存储商品类型编码列表；原 `product_type` 保留作为首选/主商品类型，并通过 `idx_sc_category_product_type` 保持高效检索。<br>2. 保存请求 `CategorySaveReqVO` 接收原型字段 `typeCodes: string[]`（商品类型多选）与 `remark: string`（描述别名），同时完全兼容存量单值 `productType` 与 `description`；在保存时至少提供一项商品类型，并通过 `ProductTypeValidationService` 统一校验所有类型均为启用状态。<br>3. 详情 DTO `CategoryDetail` 与管理树节点 `ManagementCategoryNode` 双向回显 `typeCodes` 与 `productType`、`remark` 与 `description`，彻底消除前后端参数名与单多选分歧。变更由 `037` 变更集执行。 | 支撑分类管理原型多选商品类型与描述回显 |

### 2.13 分类商品类型与描述（036 Changeset）

```sql
ALTER TABLE sc_product_category
    ADD COLUMN product_type VARCHAR(32) NULL
        COMMENT '商品类型编码，由sc_product_type动态维护' AFTER category_name,
    ADD COLUMN description VARCHAR(200) NULL COMMENT '分类描述' AFTER product_type,
    ADD KEY idx_sc_category_product_type (product_type);
```

`product_type` 保持可空是为了兼容迁移前已经存在的分类，禁止按父分类或历史 SPU 的标签推断回填。新版
`POST /supply-chain/categories` 与 `PUT /supply-chain/categories/{categoryCode}` 请求模型对该字段使用
`@NotBlank`，并在事务内通过 `ProductTypeValidationService.requireActiveForUpdate` 规范化和校验；因此新增或
人工编辑后的分类一定绑定一个启用商品类型。`description` 是最长 200 字的可选展示说明，空白值统一保存为
`NULL`。详情 DTO 的两个字段说明与上述 DDL COMMENT 保持一致。

### 2.14 分类多商品类型与原型别名（037 Changeset）

```sql
ALTER TABLE sc_product_category
    ADD COLUMN type_codes JSON NULL
        COMMENT '商品类型编码列表(JSON数组)，由sc_product_type动态维护' AFTER product_type;

UPDATE sc_product_category
    SET type_codes = JSON_ARRAY(product_type)
    WHERE product_type IS NOT NULL AND type_codes IS NULL;
```

针对管理端原型及前端表单中商品类型多选（`typeCodes`）与分类描述（`remark`）：
1. `sc_product_category` 增加 `type_codes JSON`，使用 MyBatis Plus `JacksonTypeHandler` 映射为有序字符串列表。
2. 保存请求 `CategorySaveReqVO` 统一接收 `typeCodes` 与 `productType`（任一非空即可），以及 `remark` 与 `description`；`productType` 字段解除强制 `@NotBlank`，避免多选参数 `typeCodes` 提交时因 `productType` 缺失导致 400 校验错误。
3. `CategoryCommandService` 在事务内遍历校验 `typeCodes` 中的每个商品类型均存在且处于 `ACTIVE` 状态；`product_type` 冗余持久化为列表首项，保证存量基于 `product_type` 的查询与索引不受影响。
4. `CategoryDetail` 与 `ManagementCategoryNode` 同时输出 `typeCodes` 与 `productType`，以及 `description` 与 `remark`，实现原型参数与存量 API 100% 双向兼容。

### 2.15 SPU 履约方式上移（038 Changeset）

```sql
-- 变更集：038-spu-fulfillment-type.sql
-- 平台 SPU 增加履约方式字段与状态联合索引，支撑业务交付模型与高效分页筛选。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#215-spu-履约方式上移038-changeset

ALTER TABLE sc_platform_spu
    ADD COLUMN fulfillment_type VARCHAR(32) NOT NULL DEFAULT 'DIRECT_RECHARGE'
        COMMENT '履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益'
        AFTER delivery_type,
    ADD KEY idx_sc_spu_fulfillment_status (fulfillment_type, status);

-- 存量历史 SPU 通过其挂载的未归档 SKU 履约方式进行洗数回填
UPDATE sc_platform_spu spu
    JOIN sc_platform_sku sku ON sku.spu_id = spu.id
    SET spu.fulfillment_type = sku.fulfillment_type
    WHERE sku.status != 90;
```

针对 SPU+SKU 复合表单中履约方式归属的业务优化：
1. **SPU 根级持有与决定**：`sc_platform_spu` 增加 `fulfillment_type` 字段，作为 SPU 交付的核心属性。SPU 复合创建与更新接口将 `fulfillmentType` 提升至 SPU 根入参 `SpuSaveReqVO`；子项 `SkuItemSaveReqVO` 中的 `fulfillmentType` 改为可选/废弃。
2. **SKU 自动继承双写（零破坏保障）**：`sc_platform_sku.fulfillment_type` 继续保留。在应用层创建或更新 SKU 时，一律强制继承所属 SPU 的 `fulfillment_type`，确保下游订单（`sc_supply_chain_order`）、履约受理（`FulfillmentAcceptanceLifecycle`）、路由规则（`StandardProductRoutingRuleDO`）以及数据库行级 CHECK 约束（`ck_sc_sku_phone_bind_limit`）完全保持无侵入、零破坏。
3. **SPU 列表与 Tab 查询性能跃升**：SPU 分页查询 `page(SpuPageQuery)` 在按业务 Tab（如虚拟卡密、虚拟直充）或履约类型筛选时，直接在 SPU 表上做单表等值匹配，彻底消除先全表扫描 SKU 聚合 `spu_id` 的慢查询隐患。
4. **不可变规则约束**：SPU 处于已激活（`ACTIVE`）状态或存在下游有效业务引用时，禁止修改 `fulfillment_type`（抛出 `1_050_001_113 PRODUCT_FULFILLMENT_TYPE_IMMUTABLE`）；仅草稿态允许变更，并在变更时级联更新所有草稿 SKU 的履约类型及派生的 Target/Result Schemas。

### 2.16 SKU 库存资料（041 Changeset）

```sql
-- changeset: 041-sku-inventory
ALTER TABLE sc_platform_sku
    ADD COLUMN sku_inventory BIGINT NOT NULL DEFAULT 0 COMMENT 'SKU库存数量，管理端创建或编辑商品时设置'
    AFTER phone_bind_limit_quantity;

ALTER TABLE sc_platform_sku
    ADD CONSTRAINT ck_sc_sku_inventory CHECK (sku_inventory >= 0);
```

`sku_inventory` 是商品资料数量，由复合创建/编辑写入、详情与商品管理列表回显。它不是 `InventorySource`，
不参与路由预占与履约扣减。DTO / OpenAPI 描述必须与上述 DDL COMMENT 完全一致。Java 写模型与详情字段名
固定为 `skuInventory`；管理列表继续保留 `totalInventory`，本期改为投影本列，并额外序列化同值别名
`skuInventory`，避免编辑表单与列表列名分叉。

---

## 3. 核心业务规则与状态机设计


### 3.1 SPU 与 SKU 状态解耦与有效可售计算
* **物理状态 (`status`)**：使用代码中现有标准枚举 [`CatalogEntityStatus`](file:///Users/mac/Documents/codeSpace/supply-hub/aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/domain/CatalogEntityStatus.java)：
  * `0`: `DRAFT`（草稿）
  * `10`: `ACTIVE`（启用 / 已上架）
  * `20`: `INACTIVE`（停用 / 已下架）
  * `90`: `ARCHIVED`（已归档 / 逻辑删除）
* **有效可售性 (`effectiveSellable`) 计算法则**：
  $$\text{effectiveSellable}(SKU) \iff SPU.status == 10 \land SKU.status == 10$$
* **统一校验结果语义**：
  * `ProductSellabilityService` 负责加载 SKU 及所属 SPU，并返回统一的可售快照或抛出 `SKU_NOT_SALEABLE` 领域异常。
  * 管理端报价、选品、上架入口将该异常映射为标准业务错误码；履约受理入口必须映射为 `AcceptanceRejectionCode.SKU_NOT_SALEABLE` 回执，不得泄漏为未捕获异常或 HTTP 500。
* **上下架操作语义**：
  * **SPU 下架 (`INACTIVE`)**：仅修改 SPU 本身 `status=20`。下游加购与选品池判定有效可售性时自动熔断；下属各个 SKU 保留自身的 `status`，不进行强制覆写。
  * **SPU 上架 (`ACTIVE`)**：仅将 SPU 本身置为 `10`。此时原先处于 `10` 的 SKU 自动恢复可售；原先处于 `20`（被运营单独下架）的 SKU **继续保持停用**，绝不被盲目“复活”。
  * **批量同步模式（可选）**：仅当操作人员显式勾选“同步上架下属全部 SKU”时，才将下属状态为 `20` 的 SKU 更新为 `10`。

### 3.2 删除强校验规则（4 表物理主键与关联路径检查）
删除平台 SPU 或 SKU 前，通过其主键 `platform_sku_id`（对于 SPU 则是其包含的全部 SKU 的主键集）由 `ProductDeletionValidator` 执行联合扫描：
1. **供应商货源报价** (`sc_supplier_offer`)：
   - 依赖字段：`platform_sku_id = :skuId`
   - 有效状态：`status != 90`（即处于 `0-DRAFT`, `10-PUBLISHED`, `20-SUSPENDED` 的报价均视为有效关联）
   - 命中处理：拒绝删除，抛出 `PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER`，包含关联供应商与报价编码。
2. **分销商上架单** (`sc_buyer_listing`)：
   - 依赖字段：`platform_sku_id = :skuId`
   - 有效状态：`status != 90`（即处于 `0-DRAFT`, `10-PUBLISHED`, `20-SUSPENDED` 的上架单均视为有效关联）
   - 命中处理：拒绝删除，抛出 `PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING`。
3. **分销商选品库** (`sc_buyer_product`)：
   - 依赖字段：`platform_sku_id = :skuId`
   - 有效状态：`status = 10`（`sc_buyer_product` 表 CHECK 约束为 `10-有效, 90-已移除`）
   - 命中处理：拒绝删除，抛出 `PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT`，包含关联分销商。
4. **分销商项目目录** (`sc_project_listing`)：
   - 间接关联路径：`sc_project_listing.buyer_product_id IN (SELECT id FROM sc_buyer_product WHERE platform_sku_id = :skuId)`；此处不得按 `sc_buyer_product.status` 过滤，因为分销商品移除后，历史项目商品仍可能保持有效。
   - 有效状态：`sc_project_listing.status = 10`（`sc_project_listing` 表 CHECK 约束为 `10-可采购, 90-已移除`）
   - 命中处理：拒绝删除，抛出 `PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING`，包含关联项目信息。

### 3.3 商品分类树管理（无限级、同级防重与层级重算）
* **无限级支持**：严格遵循 PRD 8.5.1，不设人为层级上限，系统天然支持 $N$ 级类目树。
* **同级重名校验**：
  - 在创建分类（`createCategory`）和更新分类名称/父级（`updateCategory`）时，检查同一 `parent_id` 下是否存在状态未归档（`status != 90`）且 `category_name` 相同的节点；若存在，抛出 `CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT`（"分类名称在同一父级下已存在"）。
  - `uk_sc_category_live_parent_name` 作为并发写入的最终防线；应用层必须将该索引的 `DuplicateKeyException` 映射为同一业务错误码，不能向管理端返回数据库异常。
* **可见性继承**：
  - 节点对外计算的 `effectiveVisible`：自身 `visible == 1` 且其所有祖先节点的 `visible == 1`。
* **更新请求中「父级」字段的三态语义**：
  - `parentCategoryCode` 缺省或为 `null`：**不变更父级**，保持原 `parent_id` 与 `category_level`，仅更新名称、排序与可见性。
  - `parentCategoryCode` 为空字符串 `""`：**显式挂载到根**，`parent_id` 置空、`category_level` 重算为 1。
  - `parentCategoryCode` 为非空编码：挂载到该父级。
  - 该三态用于避免「只改名的请求把类目静默迁到根并整体上移子树层级」；同级重名校验按最终生效的父级执行。
* **移动父级时的层级维护与防循环**：
  - 当更新分类并按上述语义实际变更了 `parentId` 时：
    1. 目标父类目不能为当前分类自身，也不能是当前分类子树中的任何节点（防循环挂载）。
    2. 计算目标父类目的层级 $L_{parent}$（若为根节点则 $L_{parent} = 0$），当前分类层级更新为 $L_{new} = L_{parent} + 1$。
    3. 在同一事务内，递归计算并更新当前分类下属整棵子树上所有子节点的 `category_level`。
* **删除分类防断裂**：
  - 若存在有效子分类（`status != 90`），拒绝删除（`CATEGORY_HAS_CHILDREN`）。
  - 若存在未归档 SPU 引用该分类（作为 `primary_category_id` 或在 `secondary_category_ids` 中），拒绝删除（`CATEGORY_HAS_PRODUCTS`）。
* **商品类型与描述**：
  - 新增、编辑分类时可提交原型多选参数 `typeCodes`（或兼容单选 `productType`，任一提供即可，至少包含一个有效且启用的商品类型编码，由 `sc_product_type` 维护）；仅启用类型可建立新的分类关联。
  - 多选类型持久化至 `sc_product_category.type_codes`（JSON 数组），首个类型冗余同步至 `product_type` 保障存量索引与查询；编辑时不传或传空数组保持原商品类型不变。
  - 已绑定类型后即使类型被停用，分类详情仍按原编码回显；后续再次显式变更分类商品类型时必须选择当前启用类型。
  - `description` 与原型别名 `remark` 互为别名兼容，最长 200 字，空字符串与纯空白统一保存为 `NULL`；历史数据的 `productType`、`typeCodes`、`description` 为空时原样返回 `null`，不得推断填充。

### 3.4 列表 Tab 复合解构映射与动态来源
PRD 8.4.1 的列表 Tab 支持从 `GET /supply-chain/product-types` 动态拉取卡片标签。普通类型以 `typeCode` 作为 `tabCode` 并直接过滤 `productType`；仅以下复合 Tab 需要服务端解构：
| Tab 代码 (`tabCode`) | Tab 显示名 | 映射 `productType` | 映射 `fulfillmentType` | 扩展过滤条件 |
| --- | --- | --- | --- | --- |
| `ALL` | 全部 | 无 | 无 | 无 |
| `VIRTUAL_CARD` | 虚拟卡密 | `EQUITY_PRODUCT` | `CARD_CODE_SECRET` | 无 |
| `VIRTUAL_RECHARGE` | 虚拟直充 | `EQUITY_PRODUCT` | `DIRECT_RECHARGE` | 无 |
| `JD` | 京东 | `JD` | 无 | 无 |

`MOVIE`、`DINING` 如保持启用，可与其他普通类型一样按 `typeCode` 生成通用 Tab；本期不提供 `MOVIE_FAST`、`MOVIE_SLOW` 等细分 Tab，也不引入电影速度属性、点餐扩展过滤或专属履约分支。

### 3.5 SKU 限购计算、并发与释放规则

1. 限购只读取平台 SKU 当前配置，不复制到 SPU。每人限购关闭时不统计历史订单；开启时以供应链数据库
   `Asia/Shanghai` 当前时间确定自然日、ISO 自然周（周一开始）、自然月、自然年或终身窗口。
2. 用户标识继续使用标准履约契约中的 `buyerUserId`。Facade 在进入幂等指纹、订单落库和限购统计前统一
   规范化为中国大陆 11 位手机号，接受裸号码、`+86`、`0086` 前缀及首尾空白，非法号码拒绝受理；调用方
   无需预先规范化。该敏感值不得写入日志。项目必须通过新增的 `projectCode` 解析，并校验项目属于当前
   外部租户解析出的采购商。Facade 通过 `BuyerProjectAccessResolver` 获取最小项目快照，不得直接依赖项目
   Mapper 或 DO。履约目标 `accountType=MOBILE` 的 `accountValue` 复用相同规范化规则。
3. 计入额度的订单必须同时满足：`CARD` 标准商品、项目/用户/SKU 相同、受理时间落在配置窗口内、
   主状态不是失败或取消、售后状态不是全部退款。部分退款不释放一期数量为 1 的整件额度。
4. 受理事务先以锁定读取得采购商 Listing，再立即锁定其 `sc_platform_sku` 行；这两个锁定读必须发生在
   Listing 版本、库存、SPU 状态及历史订单聚合等任何一致性读之前。这样 MySQL `REPEATABLE READ` 的
   快照只会在取得 SKU 锁后建立，等待同一 SKU 锁的后续事务能够看到前序事务已提交的新订单，并串行越过
   “统计 + 插入”临界区；禁止在普通查询已经建立旧快照后才补锁，也禁止仅依赖无锁 `COUNT/SUM` 导致
   额度超卖。聚合查询使用供应链模块自有
   Mapper XML；独立 `supplyChainSqlSessionFactory` 显式加载 `classpath*:mapper/**/*.xml`，不依赖宿主
   默认 MyBatis 自动配置，也不影响其它七个独立进程的数据源或 Mapper。
5. 超过每人额度返回可重试拒绝 `USER_PURCHASE_LIMIT_EXCEEDED`；当失败、取消或全额退款释放额度后，
  调用方可携带上一回执版本按相同业务键重试。超过每订单额度返回最终拒绝
  `ORDER_PURCHASE_LIMIT_EXCEEDED`。一期仍先遵守 `quantity=1`，不扩展多件履约或多目标 Schema。
6. 绑定手机号限购只读取平台 SKU 当前配置。关闭时不按履约号统计。开启时周期窗口与每人限购相同，计数键为
  `project_id + fulfillment_account_value + platform_sku_id`，不得改用 `buyerUserId`。代买时买家号与履约号
  可以不同，必须按履约号拦截。
7. 仅 `DIRECT_RECHARGE` 与 `ACCOUNT_BOUND_BENEFIT` 允许开启；管理端对卡密、兑换码、券链开启时返回
  `PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID`。受理时若已开启但目标不是 `accountType=MOBILE` 或手机号为空，
  返回最终拒绝 `PHONE_BIND_TARGET_REQUIRED`。超过履约号额度返回可重试拒绝
  `PHONE_BIND_PURCHASE_LIMIT_EXCEEDED`。
8. 直充与账户绑定权益订单无论开关是否开启，只要目标是手机号就写入 `fulfillment_account_value`，以便后续
  打开限购时历史有效订单仍可计入；卡密类保持空值，不把 `buyerUserId` 回填进该列。

---

## 4. 管理端 REST 接口详细契约

### 4.0 权限矩阵与兼容规则

所有权限常量及表达式统一定义在 `SupplyChainAdminPermissions`，Controller 禁止内联权限字符串。每个细权限表达式均包含 `supply-chain:admin` 兜底：

| 资源 | 查询 | 创建 | 更新 | 状态 | 删除 |
| --- | --- | --- | --- | --- | --- |
| 商品 | `product:query` | `product:create` | `product:update` | `product:update-status` | `product:delete` |
| 分类 | `category:query` | `category:create` | `category:update` | `category:update-status` | `category:delete` |
| 品牌 | `brand:query` | `brand:create` | `brand:update` | `brand:update-status` | `brand:delete` |
| 商品类型 | `product-type:query` | `product-type:create` | `product-type:update` | `product-type:update-status` | `product-type:delete` |

表中权限均以 `supply-chain:` 为前缀。例如商品查询的完整权限为 `supply-chain:product:query`，其表达式为 `@ss.hasAnyPermissions('supply-chain:admin', 'supply-chain:product:query')`。新增权限必须同步写入 `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`，并通过方法安全测试验证旧管理员和细权限角色。

### 4.1 平台商品管理：`ProductAdminController`
基路径：`/supply-chain/products`

#### 4.1.1 SPU 分页查询：`GET /supply-chain/products/spus`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_QUERY_EXPRESSION`，其值包含 `supply-chain:admin` 与 `supply-chain:product:query` 二选一授权。
* **Query 参数**：
  * `tabCode` (String, 可选): 业务 Tab 标识（见 3.4 映射表）
  * `keyword` (String, 可选): SPU 名称、副标题或编码模糊搜索
  * `brandId` (Long, 可选): 品牌 ID
  * `categoryId` (Long, 可选): 主类目 ID
  * `sellableCategory` (String, 可选): 可售品类枚举
  * `productType` (String, 可选): 底层商品类型编码
  * `productClassification` (String, 可选): 商品分类枚举
  * `status` (Short, 可选): SPU 状态（0-草稿，10-启用，20-停用）
  * `pageNo` (Integer, 默认 1)
  * `pageSize` (Integer, 默认 20)
* **响应**：`CommonResult<PageResult<SpuListItemVO>>`
  * 包含 SPU 编码、名称、品牌名称、主分类名称、三码标签、封面图、状态、创建时间、挂载 SKU 总数及有效价格区间（`minSuggestedPrice` ~ `maxSuggestedPrice`，分）。

#### 4.1.2 SPU 详情查询：`GET /supply-chain/products/spus/{spuCode}`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_QUERY_EXPRESSION`。
* **响应**：`CommonResult<SpuDetailVO>`
  * SPU 完整信息：编码、名称、副标题、品牌内部主键（`brandId`）、品牌业务编码（`brandCode`）、品牌名称（`brandName`）、主分类、辅助分类列表、三码、封面图、轮播图列表、图文详情、配送模式、履约方式（`fulfillmentType` 与 `fulfillmentTypeDisplay`）、起购/限购、状态、以及从首个 SKU 投影的每人/每订单/绑定手机号限购（d35）。
  * `status` 等数据字典码 JSON 必须是字符串（d36），例如草稿 `"0"`、启用 `"10"`、停用 `"20"`。
  * `skus: List<SkuDetailVO>`：挂载的每个 SKU 详情（编码、名称、主图、详情图、描述、履约模式、面值、市场价、销售建议价与规则、税务展示、起购/限购、每人/每订单/绑定手机号限购、SKU库存数量、有效可售状态、只读 schema 配置等）。SKU `status` 同样返回字符串。主图/详情图除 `mainImageUrl`/`detailImageUrls` 外，必须再输出同值别名 `picUrl`/`detailPics`（d35），供管理端编辑表单按提交字段回填。`skuInventory` 回显 `sc_platform_sku.sku_inventory`（d38）。

#### 4.1.3 SPU+SKU 复合创建：`POST /supply-chain/products/spus`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_CREATE_EXPRESSION`。
* **请求体**：`SpuSaveReqVO`
  * **Swagger 示例约束**：以下 SPU 字段、`skus[]` 全部字段以及 `suggestedSalePriceRule` 全部字段均必须配置
    具体、非空的 `@Schema(example=...)`；列表示例不得为空。CUSTOM 主示例使用市场价 12000 分、50%、ADD 1 元，
    对应物化建议价 6100 分。创建和编辑端点同时使用相同的完整 `ExampleObject` 请求体，确保 Swagger UI 在
    嵌套对象生成 `$ref` 时仍能展示每个字段，复制后可以直接联调。
  * `spuCode` (String, 可选，未传时由系统生成 `SPU_`+ULID)
  * `spuName` (String, 必填, 长度 ≤256)
  * `fulfillmentType` (String, 必填)：兑换/履约方式（`CARD_CODE_SECRET` 卡号卡密、`DIRECT_RECHARGE` 直充、`VOUCHER_CODE` 券码、`VOUCHER_URL` 券链接、`ACCOUNT_BOUND_BENEFIT` 账户绑定权益；字典 `supply_chain_fulfillment_type`）
  * `brandId` (Long, 可选；与 `brandCode` 二选一，优先 `brandCode` 其次 `brandId` 兜底)
  * `brandCode` (String, 可选，长度 ≤32；与 `brandId` 二选一)
  * `primaryCategoryId` (Long, 必填)：原型「商品分类」字段；取值来自 `GET /supply-chain/categories` 返回的
    `sc_product_category` 启用树，不是 system 数据字典。
  * `secondaryCategoryIds` (List<Long>, 可选)
  * `sellableCategory` (String, 必填)
  * `productType` (String, 必填，校验在 `sc_product_type` 中启用存在)
  * `productClassification` (String, 必填)：供货命中码。允许 system 字典
    `supply_chain_product_classification` 的枚举名（`ELECTRONICS` / `VIRTUAL_CARD` / `MOVIE_TICKET`，存量兼容），
    也允许 `GET /supply-chain/categories` 返回的 `categoryCode`（如 `E2E_L1_20260903`）。类目编码按
    `sc_product_category.category_code` 解析并原样落库；与 `primaryCategoryId` 同时传入时必须指向同一分类，
    未知码返回 `CATEGORY_NOT_FOUND`（1050001078），不得返回 `ENUM_NOT_OPEN`。
  * `coverUrl` (String, 可选)
  * `sliderImageUrls` (List<String>, 可选)
  * `description` (String, 可选)
  * `deliveryType` (String, 默认 `VIRTUAL`)
  * `minQuantity` (Long, 默认 1)
  * `maxQuantity` (Long, 默认 9999999)
  * `status` (JSON 字符串，默认 `"10"`)：`"0"` 草稿、`"10"` 启用、`"20"` 停用；库内仍为 Short
  * `userLimitEnabled` / `userLimitPeriod` / `userLimitQuantity` / `orderLimitEnabled` / `phoneBindLimitEnabled` / `phoneBindLimitPeriod` / `phoneBindLimitQuantity`（可选）：SPU 根级限购。SKU 未显式提交对应字段时继承到每个 SKU；绑定手机号限购开启且未传周期时继承 `userLimitPeriod`（d35）
  * `skus` (List<SkuItemSaveReqVO>, 必填且非空):
    * `skuCode` (String, 可选，系统自动生成 `SKU_`+ULID)
    * `skuName` (String, 必填)
    * `mainImageUrl` (String, 可选，长度 ≤512)：SKU 主图地址；兼容别名 `picUrl`
    * `detailImageUrls` (List<String>, 可选)：SKU 详情图地址列表，单项长度 ≤512；兼容别名 `detailPics`
    * `description` (String, 可选，长度 ≤200)：SKU 商品描述
    * `fulfillmentType` (String, 可选/废弃，统一由 SPU 决定并继承；若传值则强校验必须与 SPU 一致)
    * `faceValue` (Long, 必填，单位分)
    * `marketPrice` (Long, 默认 0，分)
    * `suggestedSalePrice` (Long, 默认 0，分)
    * `suggestedSalePriceRule` (Object, 可选)：销售建议价生成规则；其中 `mode` 使用 system 数据字典
      `supply_chain_suggested_sale_price_mode`，提交时由服务端计算 `suggestedSalePrice`。
      同时提交规则与结果时二者必须一致。旧调用方仅提交 `suggestedSalePrice` 时按 `FIXED` 兼容保存；编辑时市场价
      与建议价均未变化则保留已有规则，避免未升级调用方覆盖规则配置。
    * `taxIncluded` (Boolean, 可选): 是否含税；仅记录展示，不参与价格计算。
    * `taxRate` (BigDecimal, 可选): 税率，取值 `[0, 1]`；仅记录展示，不参与价格计算。
    * `minQuantity` (Long, 默认 1)
    * `maxQuantity` (Long, 默认 9999999)
    * `userLimitEnabled` / `userLimitPeriod` / `userLimitQuantity` / `orderLimitEnabled` / `phoneBindLimitEnabled` / `phoneBindLimitPeriod` / `phoneBindLimitQuantity`（可选）
    * `skuInventory` (Long, 可选, ≥0)：SKU库存数量，管理端创建或编辑商品时设置；缺省创建为 0，编辑省略保留原值。本字段是商品资料数量，不接入 InventorySource，本期履约不预占、不扣减。
    * `status` (JSON 字符串，默认继承 SPU)：`"0"`/`"10"`/`"20"`
  * 目标与结果 Schema 不属于前端请求契约。应用层按 SPU 级 `fulfillmentType` 从 `PlatformSkuSchemaRegistry` 获取并写入 SKU；当前映射版本均为 1。查询详情可返回这些字段，但前端只能只读展示，不得回传决定其取值。
* **响应**：`CommonResult<String>`（返回生成的 `spuCode`）

#### 4.1.4 SPU+SKU 复合更新：`PUT /supply-chain/products/spus/{spuCode}`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_UPDATE_EXPRESSION`。
* **请求体**：`SpuSaveReqVO`（全量属性更新，比对 SKU 列表增补新 SKU、更新已有 SKU，已移除的 SKU 若无外部引用置为停用；SPU 已激活或存在外部引用时禁止变更 `fulfillmentType`）。
* **响应**：`CommonResult<Boolean>`

#### 4.1.5 SPU 状态变更（上下架）：`PUT /supply-chain/products/spus/{spuCode}/status`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_UPDATE_STATUS_EXPRESSION`。
* **请求体**：`StatusReqVO` (`targetStatus`: 10-上架, 20-下架)
* **执行**：遵循 3.1 节状态解耦规则，变更 SPU 状态，不暴力覆写 SKU 独立状态。

#### 4.1.6 SPU 逻辑删除：`DELETE /supply-chain/products/spus/{spuCode}`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_DELETE_EXPRESSION`。
* **执行**：执行 3.2 节 4 表强校验；全部无有效引用后置为 `ARCHIVED` (90)。

#### 4.1.7 单 SKU 状态变更：`PUT /supply-chain/products/skus/{skuCode}/status`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_UPDATE_STATUS_EXPRESSION`。

#### 4.1.8 单 SKU 逻辑删除：`DELETE /supply-chain/products/skus/{skuCode}`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_DELETE_EXPRESSION`。
* **执行**：执行 3.2 节 4 表强校验。

#### 4.1.12 SKU 销售建议价规则写入收口

* **唯一入口**：随 SKU 行提交 `suggestedSalePriceRule`，新增使用 `POST /supply-chain/products/spus`，编辑使用
  `PUT /supply-chain/products/spus/{spuCode}`；不提供独立 SKU 建议价批量更新接口。
* **执行**：复合创建/编辑在所属 SPU 的事务和锁边界内，使用每个 SKU 自身的市场价及修改前建议价计算并保存规则
  与物化结果。任一 SKU 规则非法或提交结果与服务端计算不一致时整单回滚。
* **下游影响**：仅物化结果实际变化的既有 SKU 发布 `PricingDependencyChangedEvent`；只改变规则但结果不变时不生成
  无意义的下游价格版本。新 SKU 保持 `UNPRICED`，仍须通过商品默认通用定价流程生成可售价格版本。

#### 4.1.9 管理端商品列表聚合查询：`GET /supply-chain/products/management-page`
* **设计目的**：支撑商品管理后台原型中「SPU 包含多 SKU 行内平铺展开」、「复杂多维度组合检索」与「供货价、税率、税额、供应商换行展示及总库存聚合展示」，避免前端逐条查询详情造成数百次网络请求。
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_QUERY_EXPRESSION`。
* **Query 参数**：
  * `keyword` (String, 可选): 包含匹配 SPU 编码、SPU 名称、副标题、SKU 名称、SKU 编码。省略或空白不筛选。SPU 文本命中时返回该 SPU 下符合其它 SKU 条件的规格；只命中 SKU 时不返回同 SPU 下未命中的规格。
  * `productType` (`List<String>`, 可选): 商品类型编码，可多选。兼容单个 `productType=PT_CARD`；多个使用重复键 `productType=A&productType=B` 或逗号拼接 `productType=A,B`。规范化时 trim、丢空白、去重并保持传入顺序；多个类型为 OR（`product_type IN (...)`）。显式传入时覆盖 `tabCode` 解出的类型；未传、空列表或全空白不按类型过滤。筛选参数不校验类型是否存在。
  * `categoryId` (`List<String>` → Long 主键, 可选): 商品主分类内部主键，可多选。兼容单个 `categoryId=10`；多个使用重复键 `categoryId=10&categoryId=20` 或逗号拼接 `categoryId=10,20`。规范化时 trim、丢空白、去重、解析为 Long 并保持传入顺序；无法解析的片段忽略。规范化后将选中节点展开为自身 ∪ 未归档子孙（d42），多个分类为 OR（`primary_category_id IN (选中 ∪ 未归档子孙)`）。未传、空列表、全空白或规范化后为空不按分类过滤、不加载类目树。只匹配主分类，不扩展辅助分类。
  * `brandId` (Long, 可选): 品牌 ID
  * `status` (Short, 可选): SKU 状态（0-草稿，10-已上架，20-已下架；字典 `supply_chain_sku_status`）
  * `fulfillmentType` (String, 可选): 兑换方式（`CARD_CODE_SECRET` 卡号卡密、`DIRECT_RECHARGE` 直充；字典 `supply_chain_fulfillment_type`）
  * `marketPriceMin` / `marketPriceMax` (Long, 可选): 市场价区间（单位：分）
  * `supplyPriceMin` / `supplyPriceMax` (Long, 可选): 供货价区间（单位：分）
  * `supplierCode` (String, 可选): 供应商编码。省略 `excludeAddedSupplierOffers` 或其为 `false` 时，只保留该正常（`ACTIVE`）供应商已有未退役 Offer 的 SPU；供应商不存在、状态非正常或没有未退役 Offer 时返回空页。`excludeAddedSupplierOffers=true` 时改为排除范围，不再做这层收窄。
  * `excludeAddedSupplierOffers` (Boolean, 可选): 是否排除该供应商已添加且未退役的供应商品。省略或 `false` 不排除。`true` 时必须同时传 `supplierCode`，从候选和返回明细中去掉未退役 Offer 对应的平台 SKU；已退役 SKU 会再次出现。不按供货配置三码收窄，也不改变 `buyerCode` 对未移除分销商品的排除。`supplierCode` 空白返回 `SUPPLIER_CODE_REQUIRED`（1050001133）；供应商不存在返回 `SUPPLIER_NOT_FOUND`。供货价区间在该开关打开时仍按全部未退役 Offer 计算，不限定到该供应商。新增供应商品选品传 `true`。关联写入仍拒绝未退役重复，见 d46。
  * `buyerCode` (String, 可选): 分销商永久业务编码。传入后从候选和返回明细中排除该分销商未移除的分销商品，已移除 SKU 会再次出现。不按分销商档案上的商品类型收窄；类型筛选仍由调用方传 `productType` 或 `tabCode`。分销商不存在时返回 `BUYER_PROFILE_NOT_FOUND`。省略时商品中心列表不排除任何分销商品。
  * `unshelveMode` (String, 可选): 下架方式（`MANUAL_UNSHELVE` 手动下架，`SYSTEM_UNSHELVE` 系统下架）
  * `updateTimeStart` / `updateTimeEnd` (LocalDateTime, 可选): SPU 更新时间区间
  * `pageNo` (Integer, 默认 1)
  * `pageSize` (Integer, 默认 20)
* **响应**：`CommonResult<PageResult<ProductManagementSpuItem>>`
  * `ProductManagementSpuItem`:
    * `spuCode` (String): 平台 SPU 业务编码
    * `spuName` (String): 平台 SPU 标准名称
    * `coverUrl` (String): 商品封面图
    * `productType` (String): 商品类型编码
    * `productTypeName` (String): 商品类型中文名称
    * `primaryCategoryName` (String): 商品分类中文名称
    * `brandName` (String): 品牌中文名称
    * `status` (String): SPU 状态字典值（`"0"`草稿，`"10"`已上架，`"20"`已下架）
    * `unshelveMode` (String): 下架方式（已下架时回显）
    * `updatedAt` (LocalDateTime): SPU 最后更新时间
    * `skus` (List<ProductManagementSkuItem>): 挂载的未归档多 SKU 聚合明细
  * `ProductManagementSkuItem`:
    * `skuCode` (String): 平台 SKU 业务编码
    * `skuName` (String): 规格名称
    * `marketPrice` (Long): 市场价（分）
    * `suggestedSalePrice` (Long): 销售建议价（分）
    * `supplyPrice` (Long): 当前最低有效供货价（分）。供应商启用、Offer 已发布；当前版本在有效期内则用版本单价，否则回退合作商品身份列（`FIXED` 用草稿单价，`DISCOUNT` 按建议价×折扣计算，`REALTIME` 为空），多条取最低；没有有效报价为空，0 是有效价格。口径与通用定价最低供货价相同，见 `docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#3-供货价与利润隐藏`、`docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md`。
    * `supplyPriceExclusive` (Long): 不含税供货价（分）
    * `taxIncluded` (Boolean): 是否含税；仅记录展示，不参与价格计算
    * `taxRate` (BigDecimal): 税率（例如 0.0600 代表 6%）
    * `taxAmount` (Long): 税额（分）
    * `suppliers` (List<SupplierSimpleItem>): 正常（`ACTIVE`）供应商列表（编码与名称，支撑多供应商换行展示）；非正常供应商不得回显
    * `totalInventory` (Long): 总库存；本期投影 `sc_platform_sku.sku_inventory`，缺省 0。JSON 同时输出同值别名 `skuInventory`
    * `fulfillmentType` (String): 兑换方式编码与名称
    * `status` (String): SKU 状态字典值（`"0"`草稿，`"10"`已上架，`"20"`已下架；字典 `supply_chain_sku_status`）

SKU 级条件 `status`、`fulfillmentType`、`marketPriceMin`、`marketPriceMax` 必须同时用于候选 SPU
预过滤和返回 SKU 明细过滤。一个 SPU 只要存在至少一个符合全部 SKU 级条件的未归档 SKU 即可进入分页；响应中
不得夹带不符合这些条件的兄弟 SKU。SPU 自身的 `status` 字段继续按原值返回，但不参与商品状态筛选。

除 `excludeAddedSupplierOffers=true` 的新增供应商品选品外，候选 SPU 预过滤与返回 SKU 明细还必须同时满足：
SKU 至少存在一条供应商状态为 `ACTIVE`、Offer 状态未退役的合作商品。该条件必须在 SPU 分页前参与候选集合，
禁止分页后再丢弃整页数据而造成 `total`、页大小或翻页结果错误。最低有效供货价继续只认 `PUBLISHED` Offer；
因此正常供应商的暂停 Offer 可以证明合作关系并让商品可见，但不会产生供货价。

* **字段字典回显与空值安全不变量**：
  `productType`、`primaryCategoryId`、`brandId` 在历史数据或未完全归类的商品中允许为 `NULL`。在批量加载字典映射（商品类型、分类、品牌、供应商）时，未查到数据时必须返回支持 `null` 键查询的安全空 Map（例如 `Collections.emptyMap()`），并在数据组装回显时显式对 `key == null` 进行空值防护（禁止直接在 `Map.of()` 上执行 `get(null)` 或 `getOrDefault(null, ...)`），回显名称字段安全降级为 `null`，确保全量查询与存量数据兼容。

system-server 通过追加变更集 `006-product-list-filter-dictionaries` 写入以下筛选字典，按字典类型以及
`dict_type + value` 幂等新增或同步已知项，不删除管理员额外数据：

| 字典类型 | 值 | 标签 |
| --- | --- | --- |
| `supply_chain_fulfillment_type` | `CARD_CODE_SECRET` | 卡号卡密 |
| `supply_chain_fulfillment_type` | `DIRECT_RECHARGE` | 直充 |
| `supply_chain_sku_status` | `0` | 草稿 |
| `supply_chain_sku_status` | `10` | 上架 |
| `supply_chain_sku_status` | `20` | 下架 |

#### 4.1.10 批量上下架接口：`PUT /supply-chain/products/batch-status`
* **设计目的**：支撑商品列表页表头「批量上架」与「批量下架」按钮，支持多选 SPU 或多选 SKU 进行批量状态流转，并在下架时统一打上 `MANUAL_UNSHELVE` 标记。
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_UPDATE_STATUS_EXPRESSION`。
* **请求体**：`ProductBatchStatusReqVO`
  * `spuCodes` (List<String>, 可选): 选中的 SPU 编码列表
  * `skuCodes` (List<String>, 可选): 选中的 SKU 编码列表
  * `targetStatus` (Short, 必填): 目标状态（10-上架，20-下架）
  * `unshelveMode` (String, 可选): 下架方式，默认为 `MANUAL_UNSHELVE`
* **响应**：`CommonResult<Boolean>`

#### 4.1.11 SKU 税务元数据批量记录：`PUT /supply-chain/products/skus/tax-metadata`
* **设计目的**：记录并回显商品管理原型中的「是否含税、税率」，不参与任何价格计算。
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_UPDATE_EXPRESSION`。
* **请求体**：`SkuTaxMetadataBatchReqVO`
  * `skuCodes` (List<String>, 必填，1~200 个): 平台 SKU 业务编码列表。
  * `taxIncluded` (Boolean, 必填): 是否含税，`true` 含税、`false` 不含税。
  * `taxRate` (BigDecimal, 可选): 税率，取值区间 `[0, 1]`，例如 `0.0600` 表示 6%。
* **执行**：在单事务内更新所有指定 SKU；任一 SKU 不存在或已归档则全量回滚。保存逻辑不得调用任何价格解析器，也不得修改价格字段或价格版本。
* **响应**：`CommonResult<Boolean>`。

#### 4.1.12 分销商与项目分层批量定价

批量定价沿用现有 L1/L2/L3 价格链：L3 项目价 > L2 分销商价 > L1 平台价。L2 与 L3 是两个不同作用域，不提供跨分销商或跨项目的全局价格覆盖。

* `POST /supply-chain/buyers/{buyerCode}/products/pricing/preview`：批量试算选中分销商品的新 L2 有效价，不写数据库。请求包含 `buyerProductCodes`、`l2AdjustMode`、`percent`、`amountCents`、`displayThresholdPercent`；响应逐项返回 L1、当前 L2 与试算后 L2。
* `PUT /supply-chain/buyers/{buyerCode}/products/pricing`：批量保存该分销商下选中商品的 L2 规则，复用不可变 `BuyerProductL2OverrideVersion`，单事务全量成功或回滚。
* `POST /supply-chain/projects/{projectCode}/products/pricing/preview`：批量试算该项目中选中商品的新 L3 有效价，不写数据库。请求包含 `listingCodes`、`l3AdjustMode`、`percent`、`amountCents`；响应逐项返回 L1、L2、当前最终价与试算后最终价。
* `PUT /supply-chain/projects/{projectCode}/products/pricing`：批量保存该项目下选中商品的 L3 规则，复用不可变 `ProjectListingVersion`，单事务全量成功或回滚。

试算与保存均按路径中的 `buyerCode` / `projectCode` 校验资源归属，禁止仅凭商品编码跨作用域改价。`taxIncluded`、`taxRate` 不出现在上述定价请求中，也不参与试算结果。

#### 4.1.13 SKU 限购保存、回显与标准履约校验

* SKU 保存项新增 `userLimitEnabled`、`userLimitPeriod`、`userLimitQuantity`、`orderLimitEnabled`、
  `phoneBindLimitEnabled`、`phoneBindLimitPeriod`、`phoneBindLimitQuantity`；
  `maxQuantity` 在 `orderLimitEnabled=true` 时作为每订单购买数量上限。详情接口回显相同字段。
* `userLimitPeriod` 与 `phoneBindLimitPeriod` 仅允许 `LIFETIME`、`YEAR`、`MONTH`、`WEEK`、`DAY`。
  每人限购或绑定手机号限购开启时，各自的周期和正整数上限必填；关闭时忽略请求中残留的周期/数量并规范化为空（d37）。绑定手机号限购
  仅允许 `DIRECT_RECHARGE` 与 `ACCOUNT_BOUND_BENEFIT`。存量未携带新字段的编辑请求保留原配置，避免兼容
  调用静默关闭限购。
* `POST /rpc-api/supply-chain/buyer/fulfillment/accept` 请求新增必填 `projectCode`。服务端按
  `sourceSystem + externalTenantId` 解析采购商，再校验项目归属；`projectCode` 进入请求指纹，既有
  `(buyerId, buyerOrderNo)` 幂等键保持不变。受理事务在建单前完成每订单、每人与绑定手机号额度校验。
  Facade 从目标 DTO 提取手机号传入 Lifecycle，不得解密目标密文，也不得依赖订单 DAL。
  回执响应新增 `rejectionCode`，受理成功时为空，拒绝时返回稳定拒绝码；限购分别返回
  `USER_PURCHASE_LIMIT_EXCEEDED`、`ORDER_PURCHASE_LIMIT_EXCEEDED`、
  `PHONE_BIND_PURCHASE_LIMIT_EXCEEDED` 或 `PHONE_BIND_TARGET_REQUIRED`，禁止只返回笼统状态导致调用方
  无法判断重试条件。

#### 4.1.14 SKU 展示资料、商品分类来源与手机号规范化

* `SkuItemSaveReqVO` 与详情 `SkuDetail` 同步增加 `mainImageUrl`、`detailImageUrls`、`description`；创建和编辑
  复用 `SaveSkuLine`、`PlatformSkuDO` 与 `034` 变更集，不另建旁路接口。
* `SkuItemSaveReqVO` 与详情 `SkuDetail` 同步增加 `skuInventory`，对应 `041` 的 `sku_inventory`。创建缺省 0，
  编辑省略保留原值，负数拒绝。管理列表 `totalInventory` 改为投影该列，并输出同值别名 `skuInventory`。
  本列不接入 InventorySource，也不改变公开履约 `quantity=1` 与单目标约束。
* `SpuSaveReqVO.primaryCategoryId` 为必填，服务端必须校验类目存在且满足当前 SPU 状态要求；选项从
  `GET /supply-chain/categories` 获取。`productClassification` 是供货匹配标签，不替代主类目；d34 起允许
  字典枚举名或同一棵分类树的 `categoryCode`。
* 标准履约受理在生成请求指纹与订单摘要前规范化 `buyerUserId`；目标为 `MOBILE` 时同样规范化
  `accountValue`。因此 `13800138000`、`+8613800138000`、`008613800138000` 在同一项目与 SKU 下共享限购
  额度。非法手机号不得降级为不同字符串继续计数。

#### 4.1.15 SKU 履约能力评分：`GET/PUT /supply-chain/products/skus/{skuCode}/fulfillment-scores`

对齐 PRD 8.4.1.1。数据与供应商合作商品共用 `sc_supplier_offer.stability_score` / `timeliness_score`，不在商品中心另存一份。权威契约、错误码与不变量见 `docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md`。

* **GET 权限**：`PRODUCT_QUERY_EXPRESSION`。SKU 不存在返回 `PLATFORM_SKU_NOT_FOUND`；无当前合作供应商返回空 `items`。
* **PUT 权限**：`PRODUCT_UPDATE_EXPRESSION`。只更新提交的供应商行；行内可只改一列分数；未退役 Offer 以外的 `supplierCode` 整单拒绝。
* **管理列表**：`GET /products/management-page` 的 `suppliers[]` 仍只含编码与名称，不加分数。

---

### 4.2 商品分类管理：`CategoryAdminController`
基路径：`/supply-chain/categories`
后端由新建的 `CategoryCommandService`（写）与已有的 `CategoryQueryService`（读）支撑。

* `GET /supply-chain/categories`：保留存量启用类目选项树契约，仅返回 `status=ACTIVE` 的节点。
* `GET /supply-chain/categories/management-tree`：商品中心管理树，支持 `keyword`（编码或名称模糊匹配）与 `status`（分类状态）入参；采用「树枝保留模式」（子节点命中筛选条件时向上保留所有祖先节点以维持树形层级结构，剪除非命中且无命中后代的分支），返回符合条件的未归档节点及 `children`、关联商品计数、`visible`、`status`、`effectiveVisible`，节点扩展回显 `productType`、`typeCodes`、`description`、`remark`、`createdAt` 与 `updatedAt`（支撑原型表格「更新时间」列动态渲染）。
* `GET /supply-chain/categories/{categoryCode}`：分类详情，返回专用 `CategoryDetail`，同时返回 `productType`、`typeCodes`（商品类型编码数组）、`description` 与 `remark`；不得直接暴露数据库 DO、乐观锁版本或操作人 ID。
* `POST /supply-chain/categories`：创建分类（支持自动生成 `CAT_`+ULID、上级类目挂载、原型多选 `typeCodes` 与单选 `productType` 兼容、`remark` 与 `description` 描述兼容、启用商品类型绑定校验、同一父级下名称唯一校验、显示控制）。
* `PUT /supply-chain/categories/{categoryCode}`：更新分类（名称、商品类型多选/单选、分类描述/备注、排序、显示状态）。`parentCategoryCode` 按 3.3 节三态语义处理：`null` 不变更父级、`""` 显式挂到根、非空编码挂到该父级；仅在实际变更父级时校验防循环挂载，并在事务内递归重算整棵子树的 `category_level`。同一父级下名称唯一校验对所有更新请求生效。
* `PUT /supply-chain/categories/{categoryCode}/status`：启用/停用分类状态。
* `DELETE /supply-chain/categories/{categoryCode}`：删除分类（强校验子类目与未归档 SPU 引用）。

---

### 4.3 商品品牌管理：`BrandAdminController`
基路径：`/supply-chain/brands`

* `GET /supply-chain/brands`：品牌分页查询（支持名称/编码检索、状态筛选、排序）。响应 `BrandView` 返回品牌内部主键（`id`）、品牌编码、名称、简称（`shortName`）、英文名、Logo、介绍（`description`）、排序、状态、引用未归档 SPU 数量（`productCount`/`spuCount`）、创建/更新时间，既支撑前端下拉选择器获取并绑定主键，也完全支撑原型卡片视图。
* `GET /supply-chain/brands/{brandCode}`：品牌详情（响应 `BrandView`，包含内部主键 `id` 与业务字段）。
* `POST /supply-chain/brands`：创建品牌（支持自动生成 `BRD_`+ULID、品牌名称全局唯一校验、Logo 必填校验、简称、介绍、英文名、排序、创建状态）。
* `PUT /supply-chain/brands/{brandCode}`：更新品牌（名称、简称、Logo 必填、介绍、英文名、排序，名称变更时防重校验）。
* `PUT /supply-chain/brands/{brandCode}/status`：启用/停用品牌状态。
* `DELETE /supply-chain/brands/{brandCode}`：删除品牌（强校验：若有未归档 SPU 引用该品牌，禁止删除）。

---

### 4.4 商品类型管理（PRD 8.7）：`ProductTypeAdminController`
基路径：`/supply-chain/product-types`

* `GET /supply-chain/product-types`：商品类型卡片列表查询（返回类型编码、名称、图标地址 `iconUrl`、描述 `description`、内部备注 `remark`、排序、状态、关联的未归档 SPU 数量 `spuCount`、关联的未归档 SKU 数量 `skuCount`、创建/更新时间）。通过整页一次加载 SPU 并批量拉取关联 SKU，在内存分组聚合输出 `spuCount` 与 `skuCount`，无 N+1 查询，完全支撑原型卡片双指标展示及商品列表 Tab 动态渲染。
* `GET /supply-chain/product-types/{typeCode}`：类型详情。
* `POST /supply-chain/product-types`：新增商品类型（支持自动生成 `PT_`+ULID 或前端指定，名称唯一性校验，图标 `iconUrl` 必填，描述 `description`，初始状态 `status` 允许指定 10 启用或 20 停用，缺省为 10，排序，内部备注）。
* `PUT /supply-chain/product-types/{typeCode}`：更新商品类型（名称、图标 `iconUrl` 必填、描述 `description`、排序、备注、状态 `status`，名称防重校验；`status` 可选传入，传入非空值时联动变更启用/停用状态，仅允许 10 启用或 20 停用，90 归档必须走删除端点以触发引用强校验）。
* `PUT /supply-chain/product-types/{typeCode}/status`：启用/停用商品类型（停用后新增 SPU 不可选，历史 SPU 不受影响）。
* `DELETE /supply-chain/product-types/{typeCode}`：删除商品类型（强校验：若有未归档 SPU 引用该类型，禁止删除，抛出 `PRODUCT_TYPE_HAS_PRODUCTS`）。

---

### 4.5 存量主数据接口治理：`M1MasterDataAdminController`
* `POST /supply-chain/master-data/brands`：接口保留为明确的退役响应，不再创建品牌；无论请求体内容如何，固定返回 `BRAND_LEGACY_API_DEPRECATED`（`1_050_001_106`），并提示改用 `POST /supply-chain/brands`。该错误不可重试，调用方必须迁移接口。
* `/supply-chain/master-data/categories`、`/spus`、`/skus`：保留旧请求契约作为兼容入口，分别委托 `CategoryCommandService`、`ProductCatalogCommandService` 的统一写方法；不再保留独立校验和持久化逻辑。
* 旧 `/spus`、`/skus` 契约不携带 SKU 列表与限购、辅助类目等新字段，因此按「共享校验与落库、放宽组合要求」方式收口：`createSpu` 先按业务编码解析品牌与主类目，再走 `ProductCatalogCommandService.insertSpu`；`createSku` 走 `insertSku`。两者与复合创建共享动态商品类型准入、品牌与类目有效性、配送模式、限购区间、Schema 成对校验与建议价默认值；差异仅在于兼容入口固定产出 `DRAFT` 状态，且允许先建 SPU 草稿而不强制同时挂载 SKU（复合创建仍强制至少一个 SKU）。
* `/supply-chain/master-data/catalog-entities/status`：委托 `ProductCatalogCommandService.changeSpuStatus` / `changeSkuStatus` / `BrandAdminService.changeStatus` / `CategoryCommandService.changeStatus`，复用统一状态机与引用规则。
* 旧接口统一标注 `@Deprecated` 并在 OpenAPI 描述中指向新端点；仍处于兼容期的接口必须通过 ContractTest 验证新旧入口的核心校验和错误码一致，已终止写入的旧品牌接口则验证其固定返回退役错误且不会调用写服务。

---

## 5. 跨模块依赖与供销对接机制保证

### 5.1 跨模块依赖收拢与供销一致性

1. **跨模块依赖收拢**：
   - 抽取 `ProductDeletionValidator`，注入：
     - `SupplierOfferMapper` (`tradecore.supply`)
     - `BuyerProductMapper` (`tradecore.buyer`)
     - `ProjectListingMapper` (`tradecore.buyer`)
     - `BuyerListingMapper` (`tradecore.catalog`)
   - 严格以 `platform_sku_id` 主键驱动联合查询，避免跨包对象深层侵入。
2. **供应商加购选品池一致性**：
   - 现有的 `PlatformSkuAdminController.page`（`GET /supply-chain/products/skus`）作为选品核心。
   - 当传入 `supplierCode` 时，严格匹配 SPU 的 `(sellableCategory, productType, productClassification)` 三码落在该供应商的供货范围之内，且该 SKU 必须满足 `effectiveSellable == true`（即 `SPU.ACTIVE && SKU.ACTIVE`）。
3. **分销商选品池一致性**：
   - 当传入 `buyerCode` 时，只返回 `effectiveSellable == true` 且该分销商尚未关联平台 SKU（未加入 `sc_buyer_product`）的记录。
   - `keyword` 同时匹配名称与 SPU/SKU 编码；响应另含主类目展示名 `categoryName` 与最低有效供货价 `supplyPriceCents`。筛选与价格口径见 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#44-标品-sku-分页`。
4. **项目目录一致性**：
   - 当传入 `projectCode` 时，只返回所属分销商已有关联分销商品（`sc_buyer_product`）、且该项目尚未关联（未加入 `sc_project_listing`）的 SKU。
   - 选品池响应中的 `marketPriceCents` 是兼容字段名，值必须来自 `PlatformSkuDO.suggestedSalePrice`；测试数据应让 `faceValue`、`marketPrice`、`suggestedSalePrice` 三者不同，防止再次混用。
5. **状态与删除拦截闭环**：
   - 平台 SPU/SKU 被停用时，外部选品池立即过滤不可选；供应商报价创建/发布、分销商品加入、采购商上架发布以及履约受理均通过 `ProductSellabilityService` 拒绝继续流转。
   - 只要有任意未归档的 `SupplierOffer`、`BuyerProduct`、`ProjectListing` 或 `BuyerListing` 引用，严禁删除。

### 5.2 动态商品类型一致性

6. `ProductTypeValidationService` 是 `productType` 准入的唯一入口；`ProductCatalogCommandService`、`SupplierSupplyScopeService`、`BuyerAdminCommandService` 及兼容接口不得再调用 `ClosedEnums.parseRequired(ProductTypeTag.class, ...)`。
   - 新增业务关系时，`ProductTypeValidationService.requireActiveForUpdate(typeCode)` 与类型停用操作必须锁定同一 `sc_product_type` 行，避免“校验通过后并发停用”导致新关系引用已停用类型。
   - 停用类型后禁止新增 SPU 或新增供销范围；历史 SPU 与历史范围保留可读，但不得用于新增关系。重新启用后恢复可选。

### 5.3 商品中心数据字典与配置变更

system-server 通过追加变更集 `007-product-type-and-category-status-dictionaries` 写入以下数据字典，按字典类型以及 `dict_type + value` 幂等新增或同步已知项，不删除管理员额外数据：

| 字典类型 | 字典名称 | 值 | 标签 | 颜色类型 (color_type) | 说明 |
| --- | --- | --- | --- | --- | --- |
| `supply_chain_product_type_status` | 商品类型状态 | `10` | 启用 | `success` | 对应 CatalogEntityStatus.ACTIVE |
| `supply_chain_product_type_status` | 商品类型状态 | `20` | 禁用 | `danger` | 对应 CatalogEntityStatus.INACTIVE |
| `supply_chain_category_status` | 商品分类状态 | `0` | 草稿 | `info` | 对应 CatalogEntityStatus.DRAFT |
| `supply_chain_category_status` | 商品分类状态 | `10` | 启用 | `success` | 对应 CatalogEntityStatus.ACTIVE |
| `supply_chain_category_status` | 商品分类状态 | `20` | 禁用 | `danger` | 对应 CatalogEntityStatus.INACTIVE |

* **幂等写入与非破坏性维护**：脚本采用 `WHERE NOT EXISTS` 插入新记录，并使用 `JOIN` 关联更新既有记录的名称、标签、排序、颜色类型与备注，支持重复执行且不删除管理员手工维护的扩展项。
* **状态码口径对齐**：
  - 商品类型状态仅支持 `10`（启用）与 `20`（禁用），归档状态 `90` 仅作为内部逻辑删除标识，严禁作为业务选项字典暴露；彻底纠偏历史注释中误写「0草稿」的笔误。
  - 商品分类状态支持 `0`（草稿）、`10`（启用）与 `20`（禁用）。
  - OpenAPI/Swagger 参数描述与模型 DTO 字段必须统一补充对应字典标识 `supply_chain_product_type_status` 与 `supply_chain_category_status`。
  - 商品管理接口返回给前端的字典码必须是 JSON 字符串，不得把 `0`/`10`/`20` 序列化成数字；请求体同样接受字符串。库内列类型不变（d36）。

system-server 通过追加变更集 `008-sku-purchase-limit-period-dictionary` 写入限购周期数据字典：

| 字典类型 | 字典名称 | 值 | 标签 | 颜色类型 (color_type) | 说明 |
| --- | --- | --- | --- | --- | --- |
| `supply_chain_purchase_limit_period` | SKU限购周期 | `LIFETIME` | 终身 | `default` | 对应 PurchaseLimitPeriod.LIFETIME |
| `supply_chain_purchase_limit_period` | SKU限购周期 | `YEAR` | 每年 | `default` | 对应 PurchaseLimitPeriod.YEAR |
| `supply_chain_purchase_limit_period` | SKU限购周期 | `MONTH` | 每月 | `default` | 对应 PurchaseLimitPeriod.MONTH |
| `supply_chain_purchase_limit_period` | SKU限购周期 | `WEEK` | 每周 | `default` | 对应 PurchaseLimitPeriod.WEEK |
| `supply_chain_purchase_limit_period` | SKU限购周期 | `DAY` | 每天 | `default` | 对应 PurchaseLimitPeriod.DAY |

* **管理端表单下拉与契约一致性**：
  - 管理端商品编辑/限购表单中“每人限购周期”（`userLimitPeriod`）与“绑定手机号限购周期”（`phoneBindLimitPeriod`）下拉选项统一绑定 `supply_chain_purchase_limit_period` 数据字典，前端不再写死枚举。
  - 字典值严格对应后端 `PurchaseLimitPeriod` 英文大写枚举值，数据库 `sc_platform_sku` 的 CHECK 约束与履约校验逻辑维持不变。

system-server 通过追加变更集 `010-suggested-sale-price-mode-dictionary` 写入 SKU 销售建议价模式数据字典：

| 字典类型 | 字典名称 | 值 | 标签 | 颜色类型 (color_type) | 说明 |
| --- | --- | --- | --- | --- | --- |
| `supply_chain_suggested_sale_price_mode` | SKU销售建议价模式 | `EQUAL_MARKET_PRICE` | 等于市场价 | `default` | 对应 SuggestedSalePriceRule.Mode.EQUAL_MARKET_PRICE |
| `supply_chain_suggested_sale_price_mode` | SKU销售建议价模式 | `CUSTOM` | 自定义设置 | `default` | 对应 SuggestedSalePriceRule.Mode.CUSTOM |
| `supply_chain_suggested_sale_price_mode` | SKU销售建议价模式 | `FIXED` | 固定值 | `default` | 对应 SuggestedSalePriceRule.Mode.FIXED |

system-server 通过追加变更集 `011-suggested-sale-price-operator-dictionary` 写入 CUSTOM 运算符数据字典：

| 字典类型 | 字典名称 | 值 | 标签 | 颜色类型 (color_type) | 说明 |
| --- | --- | --- | --- | --- | --- |
| `supply_chain_suggested_sale_price_operator` | SKU销售建议价运算符 | `ADD` | 加 | `default` | 对应 SuggestedSalePriceRule.Operator.ADD |
| `supply_chain_suggested_sale_price_operator` | SKU销售建议价运算符 | `SUBTRACT` | 减 | `default` | 对应 SuggestedSalePriceRule.Operator.SUBTRACT |
| `supply_chain_suggested_sale_price_operator` | SKU销售建议价运算符 | `MULTIPLY` | 乘 | `default` | 对应 SuggestedSalePriceRule.Operator.MULTIPLY |
| `supply_chain_suggested_sale_price_operator` | SKU销售建议价运算符 | `DIVIDE` | 除 | `default` | 对应 SuggestedSalePriceRule.Operator.DIVIDE |

* **进程与数据所有权边界**：
  - 字典只负责管理端展示与选项下发，由 system-server 写入 system 库；供应链进程不得读取 `system_dict_type` 或 `system_dict_data`，请求校验继续使用 `SuggestedSalePriceRule.Mode` 封闭枚举。
  - `SuggestedSalePriceRuleReqVO` 与 `SuggestedSalePriceRuleView` 的 `mode`、`operator` OpenAPI 描述必须分别声明模式、运算符字典标识；自动化测试同时锁定 Liquibase、热更 SQL、字典值与 Java 枚举一致。
  - 本次不修改前端；前端后续接入时可使用既有 system 字典查询能力读取该字典，并通过既有 SPU+SKU 复合创建/编辑接口提交规则。

## 6. 测试策略

1. `ProductCenterIntegrationTest` 必须在真实 MySQL 8.4 与 Liquibase 完整表结构上覆盖 SPU/SKU 复合创建、状态独立性、分类详情投影及标品池价格读取，不得用 H2 或 Mock Mapper 代替。
2. 标品池价格用例必须让 `face_value`、`market_price`、`suggested_sale_price` 与当前 L1 四个金额彼此不同，并断言 `suggestPriceCents` 取 L1、兼容字段 `marketPriceCents` 取 `suggested_sale_price`。
3. 分类详情用例必须通过 `CategoryQueryService` 读取真实数据，并按实际 Jackson 序列化属性验证响应包含 d16 锁定的十一个业务字段（包括 `productType`、`description`），不得出现 `id`、`version`、`createdBy`、`updatedBy`；同时验证创建时保存的商品类型与分类描述能够完整回显。
4. 商品中心契约测试除检查源码注解外，还必须通过 Swagger `ModelConverters` 验证最终生成的 Schema 属性及中文说明，避免注解存在但 Knife4j 模型不可见。
5. 集成与契约门禁统一通过 Maven profile `supply-chain-integration` 执行；空库必须完整执行全部 Liquibase 变更集（含 `033`）。
6. `ProjectAdminIntegrationTest` 必须在真实 MySQL 8.4 上直接调用 L2/L3 批量试算与保存应用服务，并验证：试算零写入；同一分销商的不同项目可冻结不同 L3；混入其它分销商商品或其它项目 listing 时整批回滚；`FOLLOW_TEMPLATE` / `FOLLOW_L2`、`EQUAL_L1`、`CUSTOM` 的版本与指针语义；后续 L2 变更不回放既有项目版本；同一项目并发改价由项目行锁串行化，版本号连续且当前指针指向最终版本；管理查询与冻结版本一致。
7. `FulfillmentAcceptanceIntegrationTest` 必须覆盖：标准履约回执与总订单写入 `project_id` 与直充
   `fulfillment_account_value`；非法 `projectCode` 返回 `PROJECT_NOT_FOUND`；同一项目不同买家号但同一履约
   手机号共享绑定手机号额度；同一买家号不同履约手机号独立计数；每人限购超限拒绝；绑定手机号限购超限拒绝；
   失败/取消/全额退款释放额度且部分退款不释放；SKU 行锁下并发不超卖；公开契约保持 `quantity=1`。
   `ORDER_PURCHASE_LIMIT_EXCEEDED` 由 `SkuPurchaseLimitServiceTest` 覆盖，不通过放开公开数量契约验证。
8. `SuggestedSalePriceCalculatorTest` 必须覆盖三种模式、四种运算、HALF_UP 到分、0 价格、负结果、百分比和
   operand 精度、除零以及“以修改前建议价为基数”的快照语义。
9. `ProductCatalogCommandServiceTest`、`ProductCenterContractTest` 与 `ProductCenterIntegrationTest` 必须分别覆盖：
   旧金额请求归一为 `FIXED`；复合创建/编辑规则保存、详情回显与多 SKU 原子回滚；物化结果变化触发下游重算；
   规则变化但结果不变不触发重算；MySQL 空库执行 `039` 后存量规则非空且金额不变。
10. `SuggestedSalePriceOperatorDictionaryLiquibaseTest` 必须锁定 `011` 注册、四项字典值与
    `SuggestedSalePriceRule.Operator` 完全一致、迁移与热更脚本幂等且不删除管理员额外数据；真实 MySQL 二次执行
    Liquibase 后字典类型和四项数据不得重复。
11. `ProductCenterContractTest` 必须反射检查 `SpuSaveReqVO`、`SkuItemSaveReqVO`、
    `SuggestedSalePriceRuleReqVO` 的每个声明字段均具有非空 `@Schema.example`；列表和对象示例必须是非空 JSON，
    CUSTOM 组合示例的 `suggestedSalePrice` 必须与服务端计算结果一致。
12. `ProductCenterContractTest` 必须覆盖：前端 `picUrl`/`detailPics` 与 SPU 根级限购能映射进 `SaveSkuLine`；
    详情 `SkuDetail` JSON 同时包含 `mainImageUrl`/`detailImageUrls` 与同值别名 `picUrl`/`detailPics`；
    商品管理 `status` JSON 为字符串且 OpenAPI type 为 `string`。
13. `ProductCatalogCommandServiceTest` 必须覆盖：`userLimitEnabled=false` 且仍携带 `userLimitPeriod`/
    `userLimitQuantity`，以及 `phoneBindLimitEnabled=false` 且仍携带 `phoneBindLimitPeriod`/
    `phoneBindLimitQuantity` 时保存成功，落库周期和数量为 `NULL`，不得抛 `PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID`。
14. `ProductCatalogCommandServiceTest`、`ProductCenterContractTest`、`ProductManagementPageQueryTest` 与
    `ProductCenterIntegrationTest` 必须覆盖：复合创建未传 `skuInventory` 落库 0、显式提交按原值写入；
    编辑省略保留原值、负数拒绝；详情与管理列表回显该值；DTO 注释与 `041` DDL COMMENT 一致。
15. `ProductManagementPageQueryTest` 与 `ProductCenterContractTest` 必须覆盖 d39：管理端聚合列表
    `productType` 参数类型为 `List`；单个编码、重复多个编码、逗号拼接均按 `IN` 过滤；空列表不加类型条件；
    显式 `productType` 覆盖 `tabCode` 解出的类型。
16. `ProductManagementPageQueryTest` 与 `ProductCenterContractTest` 必须覆盖 d40：管理端聚合列表
    `categoryId` 参数类型为 `List`；单个主键、重复多个主键、逗号拼接均按 `primary_category_id IN` 过滤；
    空列表不加分类条件。
17. `SupplierProfileQueryServiceTest` 与 `SupplierAdminRestContractTest` 必须覆盖 d41：类目编码行回填
    `categoryId`、封闭枚举名行为 `null`、类目已删除或已归档行为 `null` 且不抛异常；批量解析只查一次库；
    契约断言 `SupplyScopeView` 存在 `categoryId` 记录组件且 `@Schema` 描述含回填与归档口径。
18. `ProductManagementPageQueryTest` 与 `ProductCenterContractTest` 必须覆盖 d42：勾选父类目时
    `primary_category_id IN` 含选中节点及未归档子孙；归档子孙不进入展开集；未传 `categoryId` 时不加载类目树。
19. `OfferFulfillmentScoreServiceTest`、`ProductCenterContractTest`、`SupplierAdminRestContractTest` 与
    `SupplyChainAdminMethodSecurityContractTest` 必须覆盖 d43：商品履约评分 GET/PUT 权限与字段 Schema；
    供应商列表回显分数；改价不清分；两侧读写同一 Offer 身份列。集成测试见履约评分专文。
