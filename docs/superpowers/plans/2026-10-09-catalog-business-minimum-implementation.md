# 自动受理目录与项目当前准入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本次在现有隔离工作区内顺序实施，不启动子代理，不自动提交或部署。

**Goal:** 真实选品/定价提交后生成有真实来源的自动商业目录，HTTP 新尝试按当前供给准入，成功请求按冻结身份回放。

**Architecture:** 保存接口通过 NEVER 应用服务调用已有整批领域代理，再逐条独立刷新。刷新只锁 LST→BPD→BuyerPricing，供给经独立 REQUIRES_NEW/readOnly/RR Reader 评估。HTTP 在首冻前准备目录、最终事务再次评估并复用 RoutingSnapshot，旧 RPC 可用性门禁不扩展。

**Tech Stack:** Java 21、Spring、MyBatis Plus、MySQL/Liquibase、JUnit 5/Mockito/ArchUnit；实际前端 Vue 3/TypeScript/pnpm。

## 执行边界与命令

- 后端根目录为当前工作区；下文 `JAVA` 为 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain`，`TEST` 为对应 `src/test/java/com/aifuli/module/supplychain`。
- 前端根目录 `/Users/mac/Documents/codeSpace/supplychainhubweb`；保留 `src/views/Login/components/* 2.*`。
- 每项先写行为测试并记录红灯，再实现并运行绿灯；不修改旧 Liquibase changeSet，不启用消费者，不远端清库。
- Java 命令统一前缀：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH`。
- 定向纯测：`mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test`；预期 Tests run 非零且 failures/errors 均为零。
- 集成：`mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false verify`。使用隔离本地 MySQL/Testcontainers，禁止给破坏性 fixture 指向 pro143 原测试库。

## Task 1：供给读点精确分类与当前资格 Reader

**Files:**
- 新建 `JAVA/routing/application/RoutingConfigurationMissingException.java`。
- 修改 `JAVA/routing/application/SupplierRoutingApplicationService.java`。
- 新建 `JAVA/tradecore/catalog/application/CurrentSupplyEligibilityReader.java`、`JAVA/tradecore/catalog/domain/CurrentSupplyEligibility.java`。
- 新建 `TEST/routing/application/SupplierRoutingConfigurationClassificationTest.java`、`TEST/tradecore/catalog/application/CurrentSupplyEligibilityReaderTest.java`。

- [x] 红灯：空策略/空生效版本/无匹配规则返回封闭缺条件异常；重复策略、损坏 Offer/connector/inventory 指针仍系统失败。测试使用真实 service + Mapper mock，不按消息文本匹配。
- [x] 最小实现：缺条件原因枚举为 `NO_ACTIVE_POLICY / NO_EFFECTIVE_POLICY_VERSION / NO_MATCHED_RULE / NO_CONFIGURED_SUPPLY`；只在对应读点抛该类型；已发布引用不存在或归属错误不再静默 filter。
- [x] 红灯：首选 OPTIONAL UNKNOWN 与低优先 CONFIRMED 同时存在时，Reader 不改选；REQUIRED 缺失/过期/UNKNOWN 无首选时为 UNKNOWN；明确停用/配额等为 UNAVAILABLE。

```java
assertThat(CurrentSupplyEligibility.fromRouting(snapshot, now).confirmed()).isFalse();
assertThat(CurrentSupplyEligibility.fromRouting(snapshot, now).availability()).isEqualTo(ListingAvailability.UNKNOWN);
```

- [x] Reader 以 `@Transactional(transactionManager="supplyChainTransactionManager", propagation=REQUIRES_NEW, readOnly=true, isolation=REPEATABLE_READ)` 读取库时钟、SPU/SKU、buyer/project 及真实 route；不行锁、不外呼。汇总仅遍历 ACTIVE 项目的 AVAILABLE 选品；没有有效项目时评估 null 项目。
- [x] 运行上述两类测试及既有 `SupplierRoutingRuleMatchingTest`，确认红绿与旧规则裁决兼容。（最新定向60项通过，见本地catalog-lifecycle-green.log；真实代理事务未验收。）

## Task 2：单 LST 自动刷新与商业摘要

**Files:** 新建 `JAVA/tradecore/catalog/application/BuyerSelectionCatalogLifecycle.java`、`JAVA/tradecore/catalog/domain/CatalogRefreshReason.java`、`TEST/tradecore/catalog/application/BuyerSelectionCatalogLifecycleTest.java`；必要时修改现有 `BuyerListingMapper` / `BuyerListingAvailabilityMapper`。

- [x] 红灯覆盖 MANUAL 无写、SUSPENDED 无自动恢复、RETIRED 终态、DRAFT 缺价、READY 发布、坏来源系统失败、同摘要无新商业版本或游标。
- [x] 实现 `refreshListing(String listingNo, Long operatorId)` 真实代理短事务；锁序 LST→BPD→BuyerSkuPricing；来源身份/版本同时 READY、价格非负/CNY且归属一致。
- [x] 商业快照价只来自当前分销版本；同源同内容沿用当前 validFrom，新源/内容才插入商业版本。HTTP operator 为空时只允许来源 publishedBy，缺失审计错误。
- [x] basisJson 仅来源/路由引用和原因。validUntil 取库时钟+30秒、下一自然日、政策/供给/事实时间边界最小值；价格/版本/投影 CAS 与 BuyerCatalogChange 同事务，不追加 Outbox。（当前外部事实为空，未验远程事实时效。）

```java
assertThat(second.sourceBuyerPricingVersionId()).isEqualTo(first.sourceBuyerPricingVersionId());
verify(versionMapper, times(1)).insert(any(BuyerListingVersionDO.class));
verify(changeMapper, times(1)).insert(any(BuyerCatalogChangeDO.class));
```

- [x] 定向运行生命周期测试；建立真实 MySQL/Liquibase 的发布原子/并发迟到/刷新失败无残留测试。（生命周期7项真实集成通过；完整受理并发门禁仍属Task 6。）

## Task 3：保存后编排、重算端点与管理读模型

**Files:** 新建 `JAVA/tradecore/catalog/application/BuyerCatalogManagementService.java`、`JAVA/tradecore/catalog/application/BuyerAcceptanceCatalogReadService.java`、对应测试；修改 `JAVA/interfaceapi/admin/BuyerAdminController.java`、`JAVA/tradecore/catalog/application/BuyerProductQueryService.java`。

- [x] 红灯验证领域保存异常不刷新；已提交保存遇某条刷新失败仍返回原 string[]/boolean 并继续其他条；显式重算技术失败上抛且跨 buyer 拒绝。
- [x] 实现 NEVER 编排，选品编码只取成功 worker 返回，定价仍调用原整批 `saveBuyer`。提交后才补身份及逐条 refresh；降级边界一次记录因果与 BPD/LST，正常业务异常无 ERROR 堆栈。
- [x] 两个 Controller 保存入口委派新服务；添加 `POST /{buyerCode}/products/{buyerProductCode}/acceptance-catalog/recalculate`、方法级 BUYER_UPDATE 权限，不接受价格/状态/秘密。
- [x] GET 新增九个只读字段，使用 BPD 作为 canonicalBuyerSkuCode；AUTO 过期/来源失配显示 UNKNOWN/待重算，读操作不发布；MANUAL 原读契约保持。
- [x] 运行管理编排纯测、Controller 契约和 `SupplyChainModuleArchitectureTest`。（151项定向纯测/契约/架构、25项真实集成通过，证据见§8.13.7。）

## Task 4：冻结身份读、HTTP 请求准备与最终当前准入

**Files:** 新建 `JAVA/border/fulfillment/application/FrozenAcceptanceIdentityReader.java`、`JAVA/tradecore/catalog/application/AcceptanceCatalogPreparationService.java`；修改 `JAVA/application/facade/ProjectStandardOrderCommandFacade.java`、`BuyerFulfillmentCommandFacade.java`（实际 RPC 门面）、`JAVA/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java` 与对应 HTTP/RPC/受理测试。

- [x] 红灯：已有格式2完整绑定回执先使用冻结 SKU/Schema 构造指纹；ACCEPTED 同参无准备/首冻/路由/资金；异参冲突；格式0/损坏冻结身份失败关闭。
- [x] border 只读服务按项目永久键返回不可变冻结身份，不向 Facade 暴露 DAL。HTTP/RPC 共用；认证 ACTIVE 前置保持。
- [x] claim 需执行且目标/数量合法后才准备 AUTO 目录或核 MANUAL 当前供给；缺价仍 `STANDARD_PRICING_QUOTE_MISSING`/PROCESSING；目录/供给未就绪存稳定可重试拒绝且不新建报价。
- [x] 最终 HTTP 分支不以共享 AVAILABLE 为授权，仍验证商业版/Schema/AUTO 当前来源 READY；保持 receipt→LST→SKU→quote→PLT→BPD 锁前缀。
- [x] 资金/订单/报价消费前再次 Reader，本次真实认证 project CONFIRMED 才继续，直接使用 Reader 的 RoutingSnapshot；RPC 保留旧 AVAILABLE 与行为。
- [x] 验证 MANUAL PUBLISHED/UNKNOWN 在 HTTP 凭当前 CONFIRMED 受理而旧 RPC 拒绝，已成功冻结回放不受当前变更影响。

## Task 5：RPC 读过滤与实际前端

**Files:** 修改 `JAVA/tradecore/catalog/application/BuyerCatalogQueryService.java`、`JAVA/tradecore/catalog/dal/read/BuyerCatalogReadMapper.java`（实际分页是既有注解SQL，必要时迁移为XML以共用过滤条件）、`JAVA/tradecore/catalog/readmodel/BuyerCatalogReadRow.java`；前端 `src/api/supplier/distribution/index.ts`、`src/views/supplier/distribution/distributor/detail/ProductList.vue`、`ProductSelectDialog.vue`，必要项目编码展示组件。

- [x] 红灯验证 AUTO 过期/当前来源不匹配同时影响列表过滤、返回与 total；MANUAL 原 RPC 商业字段不变，GET 无写。
- [x] 页查询/count 共用 SQL eligibility 条件，并在映射处应用相同状态规则；以同一库时钟判定期限。
- [x] 前端 types/API 添加读字段与同步重算，原保存返回类型不变。保存提示“商品已保存，请查看目录状态”；列表区分已发布/待价格/待供给/暂停/退役/待重算，不把目录投影当项目实时授权。
- [x] 重算操作沿 BUYER_UPDATE 权限、失败展示真实错误；项目下单展示 BPD，不冒用 PLT；不修改 mock 开关或 Login 副本。
- [x] 在实际前端运行 `pnpm ts:check`、定向 eslint/prettier 与 `pnpm build:dev`，记录既有错误与本片新增错误的区别。

## Task 6：真实事务与完成门禁

**Files:** 新建/扩展对应 `*IntegrationTest`、`*ContractTest`；更新 `docs/集成测试.md` §8.13 与本计划勾选状态。

- [x] 真实代理 RR 视图看到外层快照建立后已提交停用；锁序/同 SKU 配额/并发 BPD、PLT 停用验证。（2026-10-10 评审撤回完成标记；独立 Reader/刷新锁序已有证据，最终受理并发经 HTTP 闭环首冻后价格变更回归与 `BuyerSelectionCatalogLifecycleIntegrationTest` 重新验证通过，证据见 §8.13.7。）
- [x] 领域整批回滚、已提交保存保留、refresh 技术失败无版本/投影/游标残留；重复迟到调用读取当前来源。
- [x] 完整供应链 API/server 纯测、契约、架构与受影响集成测试；不以只跑 mock 宣称真实事务通过。（2026-10-10 原有 ProjectStandardAcceptanceIntegrationTest 五项装配失败，经 `2026-10-10-catalog-review-fixes.md` 修复；`catalog-fix-final-gate-2.log` BUILD SUCCESS，计数与范围见 §8.13.7。）
- [x] §8.13 按命令/受测范围/原始输出登记本地证据，远端060/061 checksum、真实 Controller/页面下单、华昇履约资产分别保持待验收，不预填通过。
- [x] 最后 `git diff --check`；明确未部署、未启用消费者、未完成华昇资产交付。由用户另行决定提交/部署。
