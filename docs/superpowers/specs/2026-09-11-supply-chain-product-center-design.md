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
| **d11** | **复合表单契约、品牌唯一约束与 Schema 安全防线** | SPU+SKU 复合创建/编辑接口采用事务内原子保存；`sc_brand` 在 `028` DDL 中追加 `uk_sc_brand_name` 唯一索引；业务编码（`SPU_`, `SKU_`, `BRD_`, `CAT_`, `PT_`）支持「前端传入或缺省由 `BusinessNoGenerator` 自动生成」。SKU 的 `targetSchemaCode/targetSchemaVersion` 必须成对为空或成对存在，启用 SKU 时必须存在，并通过 `FulfillmentTargetSchemaRegistry.requireSupportedSchema(code, version)` 校验编码与版本受支持；`resultSchemaCode/resultSchemaVersion` 必须显式提供非空编码及正版本，禁止自动写入 `DEFAULT/1` 占位。 | 防止创建成功但无法履约的 SKU |
| **d12** | **详细 Swagger/OpenAPI 文档标准** | 所有管理端 Controller、方法、Query/Path 参数、Request/Response VO 必须提供完整的 OpenAPI/Swagger 3 注解（`@Tag`、`@Operation`、`@Parameter`、`@Schema`）。VO 字段描述必须与 DDL `COMMENT` 严格一致，参数注明枚举取值、单位（CNY分）、示例值与必填约束，并在操作描述中注明前置条件与错误码映射。 | 严格落实规范 |
| **d13** | **权限兼容与菜单授权闭环** | 新增细粒度权限必须集中定义在 `SupplyChainAdminPermissions`，方法表达式统一使用 `hasAnyPermissions('supply-chain:admin', '<细权限>')`，保持旧管理员权限为超集；同步维护供应链菜单 SQL 与权限契约测试，使细权限可实际分配。 | 防止旧管理员升级后 403 |
| **d14** | **类目选项接口向后兼容** | 保留 `GET /supply-chain/categories` 只返回启用类目树的既有契约；新增 `GET /supply-chain/categories/management-tree` 返回包含草稿、停用节点及管理字段的全量树，避免存量下拉组件把不可用类目重新选入业务表单。 | 防止类目选项接口行为回归 |
| **d15** | **标品选品池销售价投影** | `GET /supply-chain/products/skus` 为兼容 09-02 前端契约继续返回字段名 `marketPriceCents`，但该字段的业务值固定取 `sc_platform_sku.suggested_sale_price`；不得再取 `face_value` 或 `market_price`。`face_value` 是履约面值，`market_price` 是市场划线价，二者都不是 09-12 锁定的 C 端建议售价来源。 | 修复商品中心扩展字段落地后选品池仍沿用旧面值的偏差 |
| **d16** | **分类详情隔离持久化模型** | `GET /supply-chain/categories/{categoryCode}` 返回专用 `CategoryDetail`，保持现有业务字段但不再序列化 `ProductCategoryDO`。响应只含分类编码、名称、父分类 ID、层级、排序、可见性、状态和创建/更新时间；数据库主键、乐观锁版本及操作人 ID 不进入管理端契约。商品中心所有 Query/Path 参数补齐 `@Parameter`，所有响应 DTO 字段补齐与 DDL 语义一致的 `@Schema`。 | 防止数据库内部字段成为前端兼容包袱，并补齐 Knife4j 参数说明 |

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

### 3.4 列表 Tab 复合解构映射与动态来源
PRD 8.4.1 的列表 Tab 支持从 `GET /supply-chain/product-types` 动态拉取卡片标签。普通类型以 `typeCode` 作为 `tabCode` 并直接过滤 `productType`；仅以下复合 Tab 需要服务端解构：
| Tab 代码 (`tabCode`) | Tab 显示名 | 映射 `productType` | 映射 `fulfillmentType` | 扩展过滤条件 |
| --- | --- | --- | --- | --- |
| `ALL` | 全部 | 无 | 无 | 无 |
| `VIRTUAL_CARD` | 虚拟卡密 | `EQUITY_PRODUCT` | `CARD_CODE_SECRET` | 无 |
| `VIRTUAL_RECHARGE` | 虚拟直充 | `EQUITY_PRODUCT` | `DIRECT_RECHARGE` | 无 |
| `JD` | 京东 | `JD` | 无 | 无 |

`MOVIE`、`DINING` 如保持启用，可与其他普通类型一样按 `typeCode` 生成通用 Tab；本期不提供 `MOVIE_FAST`、`MOVIE_SLOW` 等细分 Tab，也不引入电影速度属性、点餐扩展过滤或专属履约分支。

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
  * SPU 完整信息：编码、名称、副标题、品牌、主分类、辅助分类列表、三码、封面图、轮播图列表、图文详情、配送模式、起购/限购、状态。
  * `skus: List<SkuDetailVO>`：挂载的每个 SKU 详情（编码、名称、履约模式、面值、市场价、销售建议价、起购/限购、有效可售状态、schema 配置等）。

#### 4.1.3 SPU+SKU 复合创建：`POST /supply-chain/products/spus`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_CREATE_EXPRESSION`。
* **请求体**：`SpuSaveReqVO`
  * `spuCode` (String, 可选，未传时由系统生成 `SPU_`+ULID)
  * `spuName` (String, 必填, 长度 ≤256)
  * `brandId` (Long, 必填)
  * `primaryCategoryId` (Long, 可选)
  * `secondaryCategoryIds` (List<Long>, 可选)
  * `sellableCategory` (String, 必填)
  * `productType` (String, 必填，校验在 `sc_product_type` 中启用存在)
  * `productClassification` (String, 必填)
  * `coverUrl` (String, 可选)
  * `sliderImageUrls` (List<String>, 可选)
  * `description` (String, 可选)
  * `deliveryType` (String, 默认 `VIRTUAL`)
  * `minQuantity` (Long, 默认 1)
  * `maxQuantity` (Long, 默认 9999999)
  * `status` (Short, 默认 10)
  * `skus` (List<SkuItemSaveReqVO>, 必填且非空):
    * `skuCode` (String, 可选，系统自动生成 `SKU_`+ULID)
    * `skuName` (String, 必填)
    * `fulfillmentType` (String, 必填)
    * `faceValue` (Long, 必填，单位分)
    * `marketPrice` (Long, 默认 0，分)
    * `suggestedSalePrice` (Long, 默认 0，分)
    * `minQuantity` (Long, 默认 1)
    * `maxQuantity` (Long, 默认 9999999)
    * `targetSchemaCode` (String, 可选；与 `targetSchemaVersion` 成对出现，SKU 启用时必填)
    * `targetSchemaVersion` (Integer, 可选；与 `targetSchemaCode` 成对出现，当前已发布版本为 1)
    * `resultSchemaCode` (String, 必填，不允许自动填入 `DEFAULT` 占位值)
    * `resultSchemaVersion` (Integer, 必填且 ≥1)
    * `status` (Short, 默认与 SPU 保持一致)
* **响应**：`CommonResult<String>`（返回生成的 `spuCode`）

#### 4.1.4 SPU+SKU 复合更新：`PUT /supply-chain/products/spus/{spuCode}`
* **权限**：使用 `SupplyChainAdminPermissions.PRODUCT_UPDATE_EXPRESSION`。
* **请求体**：`SpuUpdateReqVO`（全量属性更新，比对 SKU 列表增补新 SKU、更新已有 SKU，已移除的 SKU 若无外部引用置为停用）。
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

---

### 4.2 商品分类管理：`CategoryAdminController`
基路径：`/supply-chain/categories`
后端由新建的 `CategoryCommandService`（写）与已有的 `CategoryQueryService`（读）支撑。

* `GET /supply-chain/categories`：保留存量启用类目选项树契约，仅返回 `status=ACTIVE` 的节点。
* `GET /supply-chain/categories/management-tree`：商品中心管理树，返回全部未归档节点及 `children`、关联商品计数、`visible`、`status`、`effectiveVisible`。
* `GET /supply-chain/categories/{categoryCode}`：分类详情，返回专用 `CategoryDetail`；不得直接暴露数据库 DO、乐观锁版本或操作人 ID。
* `POST /supply-chain/categories`：创建分类（支持自动生成 `CAT_`+ULID、上级类目挂载、同一父级下名称唯一校验、显示控制）。
* `PUT /supply-chain/categories/{categoryCode}`：更新分类（名称、排序、显示状态）。`parentCategoryCode` 按 3.3 节三态语义处理：`null` 不变更父级、`""` 显式挂到根、非空编码挂到该父级；仅在实际变更父级时校验防循环挂载，并在事务内递归重算整棵子树的 `category_level`。同一父级下名称唯一校验对所有更新请求生效。
* `PUT /supply-chain/categories/{categoryCode}/status`：启用/停用分类状态。
* `DELETE /supply-chain/categories/{categoryCode}`：删除分类（强校验子类目与未归档 SPU 引用）。

---

### 4.3 商品品牌管理：`BrandAdminController`
基路径：`/supply-chain/brands`

* `GET /supply-chain/brands`：品牌分页查询（支持名称/编码检索、状态筛选、排序）。
* `GET /supply-chain/brands/{brandCode}`：品牌详情。
* `POST /supply-chain/brands`：创建品牌（支持自动生成 `BRD_`+ULID、品牌名称唯一校验、Logo、英文名、排序）。
* `PUT /supply-chain/brands/{brandCode}`：更新品牌（名称、英文名、Logo、排序，名称变更时防重校验）。
* `PUT /supply-chain/brands/{brandCode}/status`：启用/停用品牌状态。
* `DELETE /supply-chain/brands/{brandCode}`：删除品牌（强校验：若有未归档 SPU 引用该品牌，禁止删除）。

---

### 4.4 商品类型管理（PRD 8.7）：`ProductTypeAdminController`
基路径：`/supply-chain/product-types`

* `GET /supply-chain/product-types`：商品类型卡片列表查询（返回类型编码、名称、排序、状态、备注、关联的 SPU 数量、创建/更新时间，用于后台列表及商品列表 Tab 动态渲染）。
* `GET /supply-chain/product-types/{typeCode}`：类型详情。
* `POST /supply-chain/product-types`：新增商品类型（支持自动生成 `PT_`+ULID 或前端指定，名称唯一性校验，排序，备注）。
* `PUT /supply-chain/product-types/{typeCode}`：更新商品类型（名称、排序、备注，名称防重校验）。
* `PUT /supply-chain/product-types/{typeCode}/status`：启用/停用商品类型（停用后新增 SPU 不可选，历史 SPU 不受影响）。
* `DELETE /supply-chain/product-types/{typeCode}`：删除商品类型（强校验：若有未归档 SPU 引用该类型，禁止删除，抛出 `PRODUCT_TYPE_HAS_PRODUCTS`）。

---

### 4.5 存量主数据接口治理：`M1MasterDataAdminController`
* `/supply-chain/master-data/brands`、`/categories`、`/spus`、`/skus`：保留旧请求契约作为兼容入口，但分别委托 `BrandAdminService`、`CategoryCommandService`、`ProductCatalogCommandService` 的统一写方法；不再保留独立校验和持久化逻辑。
* 旧 `/spus`、`/skus` 契约不携带 SKU 列表与限购、辅助类目等新字段，因此按「共享校验与落库、放宽组合要求」方式收口：`createSpu` 先按业务编码解析品牌与主类目，再走 `ProductCatalogCommandService.insertSpu`；`createSku` 走 `insertSku`。两者与复合创建共享动态商品类型准入、品牌与类目有效性、配送模式、限购区间、Schema 成对校验与建议价默认值；差异仅在于兼容入口固定产出 `DRAFT` 状态，且允许先建 SPU 草稿而不强制同时挂载 SKU（复合创建仍强制至少一个 SKU）。
* `/supply-chain/master-data/catalog-entities/status`：委托 `ProductCatalogCommandService.changeSpuStatus` / `changeSkuStatus` / `BrandAdminService.changeStatus` / `CategoryCommandService.changeStatus`，复用统一状态机与引用规则。
* 旧接口统一标注 `@Deprecated` 并在 OpenAPI 描述中指向新端点；兼容期内必须通过 ContractTest 验证新旧入口的核心校验和错误码一致。

---

## 5. 跨模块依赖与供销对接机制保证

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
4. **项目目录一致性**：
   - 当传入 `projectCode` 时，只返回所属分销商已有关联分销商品（`sc_buyer_product`）、且该项目尚未关联（未加入 `sc_project_listing`）的 SKU。
   - 选品池响应中的 `marketPriceCents` 是兼容字段名，值必须来自 `PlatformSkuDO.suggestedSalePrice`；测试数据应让 `faceValue`、`marketPrice`、`suggestedSalePrice` 三者不同，防止再次混用。
5. **状态与删除拦截闭环**：
   - 平台 SPU/SKU 被停用时，外部选品池立即过滤不可选；供应商报价创建/发布、分销商品加入、采购商上架发布以及履约受理均通过 `ProductSellabilityService` 拒绝继续流转。
   - 只要有任意未归档的 `SupplierOffer`、`BuyerProduct`、`ProjectListing` 或 `BuyerListing` 引用，严禁删除。
6. **动态商品类型一致性**：
   - `ProductTypeValidationService` 是 `productType` 准入的唯一入口；`ProductCatalogCommandService`、`SupplierSupplyScopeService`、`BuyerAdminCommandService` 及兼容接口不得再调用 `ClosedEnums.parseRequired(ProductTypeTag.class, ...)`。
   - 新增业务关系时，`ProductTypeValidationService.requireActiveForUpdate(typeCode)` 与类型停用操作必须锁定同一 `sc_product_type` 行，避免“校验通过后并发停用”导致新关系引用已停用类型。
   - 停用类型后禁止新增 SPU 或新增供销范围；历史 SPU 与历史范围保留可读，但不得用于新增关系。重新启用后恢复可选。

## 6. 测试策略

1. `ProductCenterIntegrationTest` 必须在真实 MySQL 8.4 与 Liquibase 完整表结构上覆盖 SPU/SKU 复合创建、状态独立性、分类详情投影及标品池价格读取，不得用 H2 或 Mock Mapper 代替。
2. 标品池价格用例必须让 `face_value`、`market_price`、`suggested_sale_price` 与当前 L1 四个金额彼此不同，并断言 `suggestPriceCents` 取 L1、兼容字段 `marketPriceCents` 取 `suggested_sale_price`。
3. 分类详情用例必须通过 `CategoryQueryService` 读取真实数据，并按实际 Jackson 序列化属性验证响应仅包含 d16 锁定的九个业务字段，不得出现 `id`、`version`、`createdBy`、`updatedBy`。
4. 商品中心契约测试除检查源码注解外，还必须通过 Swagger `ModelConverters` 验证最终生成的 Schema 属性及中文说明，避免注解存在但 Knife4j 模型不可见。
5. 集成与契约门禁统一通过 Maven profile `supply-chain-integration` 执行；本批不修改 DDL，Liquibase 只验证现有变更集可在空库完整执行。
