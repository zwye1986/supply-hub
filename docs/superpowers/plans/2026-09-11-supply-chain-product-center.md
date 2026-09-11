# 供应链中台商品中心实施计划

> **执行约束：** 严格按任务顺序实施；每个任务先完成对应测试，再进入下一任务。步骤使用 checkbox（`- [ ]`）跟踪状态。

**Goal:** 完整实现商品中心管理端后端能力，包括 SPU+SKU 复合创建/编辑/详情/分页/上下架/逻辑删除、分类树形 CRUD（无限级、同级防重、可见性与层级递归）、品牌 CRUD 与引用校验、商品类型管理（PRD 8.7 卡片列表/CRUD/状态），并无缝对接供应商供货配置和分销商选品池。

**Architecture:** 
- 严格遵循 `docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md`。
- 追加 Liquibase `028-platform-product-center-enhancement.sql`：扩展 SPU/SKU/Category 字段，为 `sc_brand` 增加 `brand_name` 唯一约束，新建 `sc_product_type` 表并预置 5 大类型，解绑 DB CHECK 硬编码约束。
- 业务编码支持「前端传入或缺省由 `BusinessNoGenerator` 自动生成」（`SPU_`, `SKU_`, `BRD_`, `CAT_`, `PT_`）。
- 4 表强校验与跨模块依赖隔离：新建 `ProductDeletionValidator`，通过 `platform_sku_id` 主键驱动扫描 `SupplierOfferMapper`、`BuyerListingMapper`、`BuyerProductMapper` 以及经由分销商品间接关联的 `ProjectListingMapper`，状态值域精确匹配。
- SPU 与 SKU 状态解耦：新增 `ProductSellabilityService`，以 `SPU.ACTIVE && SKU.ACTIVE` 作为选品、报价、上架发布和履约受理的统一前置条件；SPU 上下架不盲目覆写 SKU 独立状态。
- 分类无限级与同级防重：新建 `CategoryCommandService` 实现写操作分离，取消层级上限，通过应用校验与数据库唯一索引共同保证同父级未归档分类名称唯一，事务内递归更新子树 `category_level`。
- 商品列表 Tab：普通类型直接按动态 `typeCode` 查询；复合 Tab 仅实现虚拟卡密、虚拟直充和京东。电影、点餐本期只保留普通类型数据，不实现专属业务逻辑。
- 动态商品类型全链路：新增 `ProductTypeValidationService`，替换商品、供应商供货范围、分销商经营范围和兼容接口中的 `ProductTypeTag` 运行时准入校验。
- 存量主数据写接口治理：`M1MasterDataAdminController` 的创建与状态端点全部委托统一服务，堵塞旁路漏洞。
- 权限兼容：新增细权限始终保留 `supply-chain:admin` 超集语义，并同步更新系统菜单 SQL。
- 同步维护 `docs/集成测试.md` 联调与验收场景。
- 详细 Swagger/OpenAPI 3 文档：所有 Controller、方法、Query/Path 参数及 Request/Response VO 均具备完整的 `@Tag`、`@Operation`、`@Parameter`、`@Schema`，字段描述严格与 DDL `COMMENT` 一致，包含示例值、单位（CNY分）、枚举范围与前置校验提示。

**Tech Stack:** Java 21, Spring Boot 3.5, MyBatis-Plus (LambdaWrapper), Liquibase, JUnit 5 + Mockito + AssertJ, Testcontainers (MySQL 8.4).

**测试命令门禁（严格遵守 AGENTS.md 规范第 9 条）：**
所有集成与契约测试验证必须使用带 profile 的 Maven 命令：
`mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration`

---

## File Structure

| 文件 | 职责 |
| --- | --- |
| `aifuli-module-supply-chain/.../db/changelog/releases/1.0.0/028-platform-product-center-enhancement.sql` | 新建：SPU/SKU/类目扩展 DDL，品牌唯一约束，`sc_product_type` 表及 CHECK 约束解绑 |
| `aifuli-module-supply-chain/.../db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml` | 修改：注册 `028` 变更集 |
| `aifuli-module-supply-chain/.../infrastructure/identity/BusinessNoPrefixes.java` | 修改：增加 `SPU_`, `SKU_`, `BRD_`, `CAT_`, `PT_` 前缀 |
| `aifuli-module-supply-chain/.../tradecore/catalog/dal/dataobject/PlatformSpuDO.java` | 修改：增加 SPU 扩展属性（轮播图、富文本、配送模式、限购、辅助类目）映射 |
| `aifuli-module-supply-chain/.../tradecore/catalog/dal/dataobject/PlatformSkuDO.java` | 修改：增加 SKU 扩展属性（市场价、建议销售价、限购）映射 |
| `aifuli-module-supply-chain/.../tradecore/catalog/dal/dataobject/ProductCategoryDO.java` | 修改：增加 `visible` 字段映射 |
| `aifuli-module-supply-chain/.../tradecore/catalog/dal/dataobject/ProductTypeDO.java` | 新建：平台商品类型 DO |
| `aifuli-module-supply-chain/.../tradecore/catalog/dal/mysql/ProductTypeMapper.java` | 新建：平台商品类型 Mapper |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/ProductTypeValidationService.java` | 新建：动态商品类型统一准入校验，供商品、供应商与分销商写入口复用 |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/ProductTypeAdminService.java` | 新建：商品类型卡片列表、CRUD、状态与商品引用校验 |
| `aifuli-module-supply-chain/.../interfaceapi/admin/ProductTypeAdminController.java` | 新建：商品类型 REST 接口（PRD 8.7） |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/CategoryCommandService.java` | **新建**：分类写操作（增删改、防循环、同级重名校验、可见性及子树层级递归重算） |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/CategoryQueryService.java` | 修改：保留启用类目选项树，新增带 `visible` 与 `effectiveVisible` 的管理树查询 |
| `aifuli-module-supply-chain/.../interfaceapi/admin/CategoryAdminController.java` | 修改：扩展分类详情、创建、更新、状态、删除 REST |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/BrandAdminService.java` | 新建：品牌分页、增删改、状态、品牌名称防重与 SPU 关联校验 |
| `aifuli-module-supply-chain/.../interfaceapi/admin/BrandAdminController.java` | 新建：品牌分页、详情、创建、更新、状态、删除 REST |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/ProductDeletionValidator.java` | **新建**：商品删除校验器（跨模块注入 4 张事实表 Mapper，精确匹配主键与状态域） |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/ProductSellabilityService.java` | **新建**：统一校验 SPU 与 SKU 双亲有效可售状态 |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/ProductCatalogCommandService.java` | 修改：SPU+SKU 复合创建/编辑、状态流转、集成 `ProductDeletionValidator` 与存量接口委托 |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/ProductCatalogQueryService.java` | 新建：SPU 分页（Tab 复合解构）、聚合详情与有效可售状态计算 |
| `aifuli-module-supply-chain/.../interfaceapi/admin/ProductAdminController.java` | 新建：SPU/SKU 复合管理端 REST 接口 |
| `aifuli-module-supply-chain/.../interfaceapi/admin/M1MasterDataAdminController.java` | 修改：品牌、分类、SPU、SKU 创建与状态接口全部委托统一服务 |
| `aifuli-module-supply-chain/.../interfaceapi/admin/SupplyChainAdminPermissions.java` | 修改：增加商品、分类、品牌、商品类型细粒度权限 |
| `aifuli-module-supply-chain-api/.../error/SupplyChainErrorCodeConstants.java` | 修改：增加商品中心具名业务错误码与重试策略映射 |
| `aifuli-module-supply-chain/.../tradecore/supply/application/SupplierSupplyScopeService.java` | 修改：商品类型改用动态准入校验 |
| `aifuli-module-supply-chain/.../tradecore/buyer/application/BuyerAdminCommandService.java` | 修改：分销商商品类型范围改用动态准入校验 |
| `aifuli-module-supply-chain/.../tradecore/supply/application/SupplierOfferPublicationService.java` | 修改：报价创建、发布接入双亲有效可售校验 |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/BuyerProductCommandService.java` | 修改：分销商品加入接入双亲有效可售校验 |
| `aifuli-module-supply-chain/.../tradecore/catalog/application/BuyerListingPublicationService.java` | 修改：采购商上架发布接入双亲有效可售校验 |
| `aifuli-module-supply-chain/.../border/fulfillment/application/FulfillmentAcceptanceLifecycle.java` | 修改：履约受理接入双亲有效可售校验 |
| `aifuli-module-supply-chain/.../border/fulfillment/application/FulfillmentTargetSchemaRegistry.java` | 修改：增加仅校验 Schema 编码与版本是否受支持的 `requireSupportedSchema` 方法 |
| `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql` | 修改：注册商品、分类、品牌和商品类型菜单及细权限 |
| `docs/集成测试.md` | 修改：补充商品中心联调场景、前置条件、验证点与排障记录 |
| `SupplyChainLiquibaseIntegrationTest.java` | 修改：真实 MySQL 验证 028，更新 changeSet 数量及新表、列、约束断言 |
| 各 `*Test.java` / `*IntegrationTest.java` / `*ContractTest.java` | 新建/更新自动化测试套件 |

---

## Tasks

### Task 1: 数据库与实体扩展（Liquibase 028 与 DO 增强）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/028-platform-product-center-enhancement.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/identity/BusinessNoPrefixes.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/dataobject/PlatformSpuDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/dataobject/PlatformSkuDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/dataobject/ProductCategoryDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/dataobject/ProductTypeDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/dal/mysql/ProductTypeMapper.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/identity/BusinessNoPrefixesTest.java`
- Modify Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`

- [x] **Step 1: 编写 028 Liquibase SQL**
  - 为 `sc_platform_spu` 增加 `secondary_category_ids` (AFTER `product_classification`), `slider_image_urls`, `description`, `delivery_type`, `min_quantity`, `max_quantity`。
  - 为 `sc_platform_sku` 增加 `market_price`, `suggested_sale_price`, `min_quantity`, `max_quantity` 及 CHECK 约束。
  - 为 `sc_product_category` 增加 `visible` 字段、复合查询索引及只约束未归档节点的同父级名称唯一索引；建索引前检测并报告历史冲突数据。
  - 为 `sc_brand` 增加 `uk_sc_brand_name` 唯一约束。
  - 新建 `sc_product_type` 表并预置 5 种基础类型数据。
  - DROP CHECK 解除 `sc_platform_spu` 与 `sc_supplier_supply_scope` 上的硬编码 `product_type` 约束。
  - 在 `db.changelog-1.0.0.yaml` 末尾（`027` 之后）注册 `028` 变更集。
- [x] **Step 2: 扩展 `BusinessNoPrefixes` 并测试**
  - 增加 `SPU_`, `SKU_`, `BRD_`, `CAT_`, `PT_` 前缀常量并加入 `ALL` 集合，运行 `BusinessNoPrefixesTest`。
- [x] **Step 3: 扩展 DO 与 Mapper 映射**
  - 更新 `PlatformSpuDO`, `PlatformSkuDO`, `ProductCategoryDO`，新建 `ProductTypeDO` 与 `ProductTypeMapper`。
- [x] **Step 4: 更新并运行真实 Liquibase 集成测试**
  - 更新 `SupplyChainLiquibaseIntegrationTest` 的 changeSet 数量断言（追加 028 后为 29），增加 `sc_product_type`、扩展列、生成列、唯一索引及 CHECK 解绑断言。
  - 执行 `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration`，由 MySQL 8.4 Testcontainers 验证空库迁移、二次启动幂等与实际 Schema。

---

### Task 2: 商品类型管理（PRD 8.7 后端完整实现）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductTypeAdminService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductTypeValidationService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/ProductTypeAdminController.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierSupplyScopeService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/buyer/application/BuyerAdminCommandService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/dto/producttype/*VO.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductTypeAdminServiceTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/ProductTypeAdminControllerTest.java`

- [x] **Step 1: 实现 `ProductTypeAdminService` 业务编排**
  - 卡片列表：支持按状态与关键字筛选，统计每个类型关联的未归档 SPU 数量（供管理端列表及商品列表 Tab 动态获取）。
  - 创建类型：支持传入或自动生成 `PT_` 编码，类型名称全局唯一校验。
  - 更新类型：更新名称与排序，名称防重校验。
  - 状态流转：启用/停用商品类型（使用 `CatalogEntityStatus.INACTIVE`）。
  - 删除类型：强校验是否存在未归档 SPU 引用该类型，若有拒绝删除并抛出 `PRODUCT_TYPE_HAS_PRODUCTS`。
- [x] **Step 2: 暴露 `ProductTypeAdminController` REST 端点**
  - 提供 `GET /supply-chain/product-types`、`GET /{typeCode}`、`POST /`、`PUT /{typeCode}`、`PUT /{typeCode}/status`、`DELETE /{typeCode}`。
  - 补充完整 Swagger 3 注解与权限控制 `@PreAuthorize`。
- [x] **Step 3: 动态类型准入全链路收口**
  - 新建 `ProductTypeValidationService`，提供启用类型校验和历史类型只读解析；新增关系使用 `requireActiveForUpdate(typeCode)` 锁定类型行，类型停用锁定同一行，避免校验与停用并发穿透。
  - `ProductCatalogCommandService`、`SupplierSupplyScopeService`、`BuyerAdminCommandService` 及兼容入口不得再调用 `ClosedEnums.parseRequired(ProductTypeTag.class, ...)`。
  - 类型停用后拒绝新增 SPU、供应商范围和分销商范围，既有数据保持可读；重新启用后恢复可选。
- [x] **Step 4: 运行商品类型单元与集成测试验证通过**

---

### Task 3: 商品分类（Category）全生命周期与无限级管理

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryCommandService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryQueryService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/CategoryAdminController.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/dto/category/*VO.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/CategoryCommandServiceTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/CategoryAdminControllerTest.java`

- [x] **Step 1: 新建 `CategoryCommandService` 实现分类写操作分离**
  - 创建类目：支持传参或自动生成 `CAT_` 编码，层级根据父类目自动推导（支持无限级），默认 `visible=1`；**强校验同一父级下分类名称唯一性**（`CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT`）。
  - 更新类目：支持更新名称、排序、`visible`；若变更名称或父级，校验同一父级下名称唯一、防循环挂载，并在事务内递归重算整棵子树的 `category_level`。
  - 状态流转：启用/停用分类状态。
  - 删除类目：强校验有效子类目（存在则报错），强校验关联未归档 SPU（存在则报错 `CATEGORY_HAS_CHILDREN_OR_PRODUCTS`）。
- [x] **Step 2: 扩展 `CategoryQueryService`**
  - 保留原 `tree()` 仅查询启用类目的选项契约；新增管理树查询，返回全部未归档节点、`visible` 并计算祖先级联的 `effectiveVisible`。
- [x] **Step 3: 扩展 `CategoryAdminController` REST 端点**
  - 保留 `GET /` 启用类目选项树；新增 `GET /management-tree` 管理树，并提供 `GET /{categoryCode}`、`POST /`、`PUT /{categoryCode}`、`PUT /{categoryCode}/status`、`DELETE /{categoryCode}`。
  - 补充完整 Swagger 3 注解与权限控制。
- [x] **Step 4: 验证分类唯一性与接口兼容**
  - 单测覆盖应用层防重、唯一索引异常映射、防循环和层级重算。
  - 真实 MySQL 并发测试验证同父级同名创建仅一个成功，根类目同名也受约束。
  - `CategoryAdminContractTest` 验证原 `GET /supply-chain/categories` 仍只返回启用节点。

---

### Task 4: 商品品牌（Brand）全生命周期管理

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BrandAdminService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/BrandAdminController.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/dto/brand/*VO.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/BrandAdminServiceTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/BrandAdminControllerTest.java`

- [x] **Step 1: 实现品牌应用层服务**
  - 分页列表与关键字过滤（名称/编码）。
  - 创建品牌：支持传入或自动生成 `BRD_` 编码，品牌名称全局唯一性校验（应用层双重校验 + 028 DDL 唯一索引）。
  - 更新品牌：更新名称、英文名、Logo、排序，名称变更时防重校验。
  - 变更状态：草稿、启用、停用（`INACTIVE`）。
  - 删除品牌：强校验是否存在未归档 SPU 引用该品牌，若存在拒绝删除。
- [x] **Step 2: 暴露 `BrandAdminController` REST 端点**
  - `GET /supply-chain/brands`（分页列表）、`GET /{brandCode}`、`POST /`、`PUT /{brandCode}`、`PUT /{brandCode}/status`、`DELETE /{brandCode}`。
  - 补充完整 Swagger 3 注解与权限控制。
- [x] **Step 3: 运行品牌单测验证通过**

---

### Task 5: 平台商品（SPU+SKU）复合表单、4表校验器、状态解耦与存量收口

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductDeletionValidator.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductSellabilityService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductCatalogQueryService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductCatalogCommandService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/ProductAdminController.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/M1MasterDataAdminController.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/SupplyChainAdminPermissions.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/SupplierOfferPublicationService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BuyerProductCommandService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/application/BuyerListingPublicationService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/FulfillmentTargetSchemaRegistry.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`
- Modify: `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/admin/dto/product/*VO.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductDeletionValidatorTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/application/ProductCatalogCommandServiceTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/ProductAdminControllerTest.java`

- [x] **Step 1: 实现 `ProductDeletionValidator` 独立删除校验器**
  - 注入 `SupplierOfferMapper`、`BuyerListingMapper`、`BuyerProductMapper`、`ProjectListingMapper`。
  - 按 `platform_sku_id` 主键扫描：
    - `SupplierOffer`: `platform_sku_id = :skuId AND status != 90`
    - `BuyerListing`: `platform_sku_id = :skuId AND status != 90`
    - `BuyerProduct`: `platform_sku_id = :skuId AND status = 10`
    - `ProjectListing`: `buyer_product_id IN (SELECT id FROM sc_buyer_product WHERE platform_sku_id = :skuId) AND status = 10`，不得按 BuyerProduct 状态过滤。
  - 拦截并抛出精准业务异常及关联方信息。
- [x] **Step 2: 实现 SPU+SKU 复合创建与更新业务编排**
  - 复合创建：校验三码合规，从 `sc_product_type` 动态校验商品类型是否启用有效，校验品牌与分类；原子插入 SPU 并批量插入 SKU。自动生成 `SPU_`/`SKU_` 编码；`targetSchemaCode/targetSchemaVersion` 成对校验，ACTIVE SKU 必须提供，并调用新增的 `FulfillmentTargetSchemaRegistry.requireSupportedSchema(code, version)` 验证；`resultSchemaCode/resultSchemaVersion` 必须显式提供非空编码及正版本，禁止自动写入 `DEFAULT` 占位。
  - 复合更新：原子更新 SPU 基础与扩展字段；比对 SKU 列表增补新 SKU、更新已有项。
  - 状态解耦：SPU 上下架仅操作 SPU 自身，不盲目覆写独立置为 `INACTIVE` 的 SKU；对外提供 `effectiveSellable` 计算。
  - 逻辑删除：集成 `ProductDeletionValidator` 强校验通过后归档为 90。
- [x] **Step 3: 存量主数据全部写接口收口**
  - 品牌、分类、SPU、SKU 创建及状态端点全部委托新的统一服务；旧端点标注 `@Deprecated`，不得保留独立持久化路径。
  - ContractTest 验证新旧入口对动态类型、分类防重、Schema 校验和状态机返回一致的业务错误码。
- [x] **Step 4: 实现 SPU 分页列表（Tab 复合解构）与聚合详情**
  - 普通动态 Tab 直接按 `typeCode` 过滤；复合 `tabCode` 仅实现虚拟卡密、虚拟直充和京东。电影、点餐不增加专属分支。
  - 返回 SKU 数量统计与有效价格区间。
- [x] **Step 5: 接入全链路有效可售校验**
  - `ProductSellabilityService` 同时加载并校验 SPU、SKU 状态。
  - 商品池查询、供应商报价创建/发布、分销商品加入、采购商上架发布及 `FulfillmentAcceptanceLifecycle` 履约受理全部复用该服务。
  - 管理入口统一映射为 `SKU_NOT_SALEABLE` 业务错误；履约入口映射为 `AcceptanceRejectionCode.SKU_NOT_SALEABLE` 回执，不得返回 500。
- [x] **Step 6: 配置权限、菜单与 Swagger**
  - 暴露 `ProductAdminController` 端点并补齐详细 Swagger 注解。
  - 所有细权限表达式使用 `hasAnyPermissions('supply-chain:admin', '<细权限>')`，并更新 `system-supply-chain-admin-menus.sql`。
- [x] **Step 7: 编写单测与契约测试验证通过**

---

### Task 6: 联调验证、集成测试文档与全量测试门禁

**Files:**
- Modify: `docs/集成测试.md`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/catalog/ProductCenterIntegrationTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/interfaceapi/admin/ProductCenterContractTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`

- [x] **Step 1: 编写商品中心集成测试**
  - 验证真实 MySQL 与 Liquibase 028 执行无误。
  - 验证 SPU+SKU 创建、Tab 查询、有效可售性计算。
  - 验证 `ProductDeletionValidator` 4 表强校验拦截与错误码。
  - 验证 BuyerProduct 已移除但 ProjectListing 仍有效时，平台 SKU 删除仍被拦截。
  - 验证 SPU 停用、SKU 保持启用时，报价发布、分销加购、采购商上架和履约受理全部拒绝。
  - 验证动态新增类型可贯通 SPU、供应商范围、分销商范围和商品池；类型停用后禁止新增关系。
  - 验证新增业务关系与类型停用并发时，不会生成引用停用类型的新关系。
  - 验证目标 Schema 编码/版本缺失任一项、未知 Schema 或结果 Schema 占位值均无法启用 SKU。
  - 验证供应商加购池三码过滤与分销商选品池对接。
- [x] **Step 2: 更新 `docs/集成测试.md`**
  - 补充商品中心管理端（商品/分类/品牌/商品类型）与供销联调的端到端集成测试用例、前置条件、请求示例与预期结果。
- [x] **Step 3: 运行全量集成测试门禁**
  - 执行 `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify -Psupply-chain-integration` 确保全部测试绿灯。
