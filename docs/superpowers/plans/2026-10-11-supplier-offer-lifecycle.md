# 供应商合作商品独立上下架实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

> 2026-10-11 修订：已有部分代码，先审核差异再增量实现；禁止重置或覆盖用户改动。本次按用户最新要求取消全部存量迁移与启动补偿；修订、独立复审及增量实现已完成，自动化执行与复验证据见文末。真实部署UI联调未执行。

**Goal:** 合作商品默认下架、校验后发布上架，页面操作一致，正式定价只采用当前有效的已发布供给。

**Architecture:** SupplyChainServerApplication 内单事务发布编排与统一有效供货价解析；保留不可变履约/定价/订单版本。前端使用供应商 Offer 状态驱动上下架，以后端资格控制发布按钮。

**Tech Stack:** Java 21、Spring Boot、MyBatis Plus、MySQL 8/Liquibase、JUnit/ArchUnit；Vue 3/TypeScript、Node 测试、Vite。

## Task 1：方案审查

- [x] 对照 specs/2026-10-11-supplier-offer-lifecycle-design.md 审查全部六项用户同意规则、旧 PATCH、四处价格调用、依赖失效及并发锁序。
- [x] 独立只读审查，先修正文档缺口，再写实现代码；在文档中记录审查结果。复审重点为设计§3.4的局部RC、技术创建、全三层批量锁序及正式成本口径；确认无任何迁移/启动补偿残留。

## Task 2：后端状态与有效供给闭环

- [x] 删除本轮未发布的 063 SQL/YAML、迁移原因枚举、启动注册、恢复方法/专用锁方法及对应测试；保留用户其它实现与既有建表变更集。

**Files:** `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/supply/application/{SupplierCooperativeProductService,SupplierOfferPublicationService,ConnectorPublicationService}.java`；新增统一有效供货价/发布资格读服务；`interfaceapi/admin/SupplierAdminController.java`；`tradecore/catalog/pricing/{EffectiveSupplyPrice,GenericBatchPricingService}.java`；`tradecore/catalog/application/{ProductCatalogQueryService,PlatformSkuQueryService}.java`；运行时真实 MySQL 生命周期集成测试；`routing/application/SupplierRoutingApplicationService.java`；必要Mapper当前读方法（Lambda实现）；`SupplyChainModuleArchitectureTest`只运行，不能放宽门禁。

- [x] 测试先行：新增断言 `assertThat(inserted.getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode())`；`assertThat(EffectiveSupplyPrice.of(offer, supplier, null, suggested, now)).isNull()`；过期/错误归属版本返回空；连接器/库存失效返回空，身份价修改继续返回版本价。
- [x] 定向回归入口：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -f aifuli-module-supply-chain/aifuli-module-supply-chain-server/pom.xml -Dtest=SupplierCooperativeProductServiceTest,EffectiveSupplyPriceTest,SupplierOfferPublicationListOnShelfTest test`。已有部分实现，不重写已满足规则；本轮新增守卫、锁序、草稿事件与缓存修正分别保留先RED后GREEN证据，见文末。
- [x] 新增下架身份；旧 PATCH PUBLISHED 进入 `listOnShelf`，SUSPENDED 保留状态迁移。所有正式供货价从统一解析返回有效版本金额；删除身份列回退。发布失败全部回滚；表间归属校验不放宽。
- [x] 扩充 OfferListItem 为 canPublish/publishBlockedReason；中文阻断原因与后端上架守卫一致。Connector publish/retire 对受影响 SKU 去重同步事件；变更发布与下架发同一价格依赖事件。
- [x] 技术端 publish 共用白名单/ACTIVE/能力/归属守卫；routing.application 共用结构有效性。生命周期入口使用局部READ_COMMITTED并拒绝加入已有RR事务；包含SupplierProfileCommandService状态转换与旧PATCH外层，补隔离校验及升级后重算的新视图测试。供应商行先于子对象锁；发布依赖使用锁后当前读，读解析不加排他锁；重算按SKU主键升序，不误用锁商品行的PlatformSkuBatchLock。共享数量谓词：正式价格按一期数量1过滤，路由按实际数量。
- [x] 真实 MySQL 测试覆盖默认下架、旧 PATCH 校验、完整发布/改价/下架链路、依赖退役、RC等待锁后读到退役状态、相交SKU锁序。用专用空测试库，不碰业务数据库。
- [x] 批量平台/分销商/项目定价及默认初始化采用局部RC并拒绝非RC外层，在父配置计算前按目标 SKU ID 升序取得平台定价身份锁；验证反序请求与生命周期重算可以并发完成。
- [x] 默认初始化同步事件的外层 `ProductCatalogCommandService.createProduct/updateProduct` 同样局部 RC+入口拒绝非 RC；回归商品复合保存/初始化及 HTTP/RPC 契约，同事务保留新SKU可见性与原子性。
- [x] 行内定价外层 `BuyerProductSelectionBatchTransactionalWorker.executeBatch`、`ProjectListingCommandService.addProducts` 同样局部RC+入口守卫，逐行处理前预锁整批定价根；复合更新先合并初始化/重算目标集预锁，避免高ID初始化→低ID重算形成反序。
- [x] 独立分销/项目继承初始化入口自身局部RC+守卫，父版本读取与子身份锁定前先取得当前SKU平台定价根；回归自动目录刷新独立委派以及关联批次复用事务。
- [x] 未持SKU行锁的定价入口在全部根锁后重新读取SKU基础价，锁前对象仅定位；真实并发测试验证初始化不使用锁前旧基础价。
- [x] 锁后重读前清除当前供应链SqlSession一级缓存，避免重复selectById命中旧对象；不改全局缓存或事务管理器。
- [x] 新增下架合作关系与商业草稿保存不触发正式定价重算，正式版本/当前指针保持；发布变更后才切换成本及三级定价。
- [x] 修改历史断言与测试种子：需要交易成本的测试显式建立有效版本和依赖；不得靠 mock 新解析器返回任意价掩盖核心回归。
- [x] 真实测试验证无供给→有效供给时三层定价身份恢复 `READY` 且旧失效原因为 NULL；发布新价格版本后显式清 NULL，避免普通 `updateById` 忽略空值。
- [x] 独立规范符合审查通过后，再进行代码质量审查；修复并复审。

## Task 3：前端上下架与发布变更

**Files:** `/Users/mac/codespace/supplychainhubweb/src/views/supplier/manage/detail/ProductList.vue`、`detail/utils/fulfillment.ts`、`src/api/supplier/manage/{index,types}.ts`、`src/views/supplier/product/list/index.vue`、`tests/offer-fulfillment.test.mjs` 和真实 SFC 行操作测试。

- [x] 测试先行：PUBLISHED 操作为下架，SUSPENDED 为上架，缺状态无操作；`canPublish=false` 禁用发布但不禁用下架；所有已上架行保留下架操作并增加发布变更。
- [x] Run: `node --test tests/offer-fulfillment.test.mjs` 以及新增 `tests/offer-product-list.test.mjs` SFC 测试，观察旧按钮和接口分支失败。
- [x] 上下架按 Offer 状态选择操作：下架 `SupplierApi.patchOffer(supplierCode, offerNo, {status:'SUSPENDED'})`；上架/发布变更 `SupplierApi.listOfferOnShelf(supplierCode,offerNo)`。成功刷新，服务端错误使用既有拦截器并避免重复提示。
- [x] 列名改商品履约配置；STALE 改待发布变更；展示发布阻断原因。改价对话框说明仅保存待发布变更、旧版本继续有效。
- [x] 平台商品供应价为空显示“暂无有效供货价”；真实零金额保留 `0.00`。以真实 SFC 验证两者区分。
- [x] Node 全量测试、改动文件 ESLint、新增逻辑/测试 Prettier、`pnpm ts:check`、`pnpm build:dev`；记录既有警告或环境问题。
- [x] 独立规范符合及质量审查，修复所有可复现问题后复审。

## Task 4：文档与全量门禁

**Files:** `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md`、`2026-09-22-effective-supply-price-identity-fallback-design.md`、`2026-10-03-supplier-offer-publication-admin-design.md`、`2026-09-15-generic-batch-pricing-design.md`、`docs/集成测试.md`。

- [x] 当前规则统一引用新设计；既有数据库变更集保持原样，旧业务决策加取代说明。联调按新增下架→未配连接器上架失败→配置成功→上架→改价待发布→发布变更→下架→连接器退役顺序列出前置条件/验证点。
- [x] 依赖安装（仅此阶段跳过全部测试，后续供应链测试单独执行）：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -DskipTests install`。
- [x] API单测：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -f aifuli-module-supply-chain/aifuli-module-supply-chain-api/pom.xml test`。
- [x] Server单测及架构门禁：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -f aifuli-module-supply-chain/aifuli-module-supply-chain-server/pom.xml test`，不加skip、不加-am。
- [x] 集成与契约门禁：使用既有 `supply-chain-integration` profile 与Testcontainers专用空MySQL，`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -f aifuli-module-supply-chain/aifuli-module-supply-chain-server/pom.xml -Psupply-chain-integration -DforkCount=2 verify`；全部118组895项执行，失败组修正后定向复验。无需业务库环境变量；已核对测试配置，仅访问Testcontainers。
- [x] 分别记录API/server单测、架构、integration/contract实际执行数、失败和环境限制。新增运行时集成 `SupplierOfferLifecycleIntegrationTest`；资格纯测 `SupplierOfferEligibilityServiceTest`、事务纯测 `SupplierLifecycleTransactionTest`；无迁移与启动补偿测试。
- [x] `git diff --check`，检查中文注释、DTO 字段语义、所有 HTTP 权限、两仓变更整体审阅。
- [x] 交付方案链接、变更概述、测试证据和未做的实机联调；用户后续已授权commit and push，按中文提交规范分别提交、推送前后端 `codex/supplier-offer-lifecycle` 分支；不部署、不改用户业务环境。

## 本轮复验记录（取消存量迁移后）

- 文档规格复审、实现规格复审与代码质量复审均通过；真实测试进一步发现会话一级缓存旧值，设计先修订再增量修正。
- TDD证据：独立初始化入口 `/tmp/offer-initializer-red.log`（3失败）；草稿不触发定价 `/tmp/offer-draft-events-red2.log`（7失败）；根锁顺序 `/tmp/offer-batch-order-red.log`；外层事务 `/tmp/offer-inline-transaction-red.log`；技术创建 `/tmp/offer-technical-create-red.log`。真实基础价并发 `/tmp/offer-lifecycle-final-targeted2.log`（2旧值失败）；清会话缓存后 `/tmp/offer-cache-current-green.log`（定向单测56、生命周期MySQL10通过）。
- API 170项通过：`/tmp/offer-api-final2.log`。Server全量单测/架构2147项通过（最终四组复验时再次全量通过）：`/tmp/offer-lifecycle-final-fixture-rerun2.log`。
- 全量集成/契约：`/tmp/offer-lifecycle-all-final.log`（JDK21，`-Psupply-chain-integration -DforkCount=2 verify`，独占Testcontainers空库），118组895项：集成83组568项、契约35组327项。首轮114组855项通过；四组40项包含20失败9错误，集中于旧种子/断言，首轮命令为BUILD FAILURE，不能表述为一次全量BUILD SUCCESS。
- 失败组修正及复验：HTTP闭环种子按SKU履约类型冻结主能力并补齐适配器能力；供应商管理断言新增下架及旧PATCH委派；折扣草稿断言正式版本/指针不变、DTO返回NULL单价+建议价+折扣；既有062时间字段计数303（无新DDL）。`/tmp/offer-lifecycle-final-fixture-rerun2.log` 中HTTP30、供应商管理1、时间结构2均通过，折扣4项因误把前端计算报价当DTO固定价失败；修正契约断言后 `/tmp/offer-lifecycle-sale-price-final.log` 的折扣7项全部通过（BUILD SUCCESS）。至此全量已执行组全部有通过证据；修正仅涉及这四组测试，生产代码未再变。
- 最后测试修正已独立复审通过：保留真实DB正式成本为空、价格版本/指针不变、第二行拒绝整批回滚等保障，不放宽生产资格判定。生命周期10项含事务、锁序、锁后基础价及三级定价原子性均在首轮全量通过。
- 前端Node195项通过：`/tmp/offer-frontend-final3.log`；改动文件ESLint通过、build:dev通过（原有Sass/包体警告）；ts:check既有54处诊断，与独立HEAD源码快照按诊断内容比较完全一致：`/tmp/offer-ts-comparison.log`。既有大SFC仅作局部改动，不整文件格式化。
- 实机UI、外部供应商、真实部署联调未执行。取消063与补偿实现后不存在本轮数据库升级步骤。
