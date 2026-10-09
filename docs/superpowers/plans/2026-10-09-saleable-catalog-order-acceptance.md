# 真实选品可售与签名下单受理 TDD 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 `executing-plans` 在当前会话逐任务实施，步骤以复选框记录。当前请求不自动授权提交、推送、部署或真实供应商调用，不沿用旧计划的 subagent 授权。

**Goal:** 管理选品／定价真实提交后自动形成商业目录，后台条件恢复后自动重评估，新签名 HTTP 订单按本项目新供给快照受理，已受理订单按冻结身份回放。

**Architecture:** 复用 A 身份成果及现有定价、永久报价、路由和资金事务。独立只读资格 Reader 为共享判定入口，保存后单 LST 刷新及内存有界扫描补偿；最终新供给视图与订单写事务分工，不以投影授权。不接通 BCS 账本商业通道。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis Plus、MySQL 8.4、Liquibase、JUnit 5／AssertJ／ArchUnit；管理端 Vue 3／TypeScript／Element Plus。

---

## 1. 基线、文件路径与执行边界

- 规范入口：`docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md`（下称设计）。用户本次指示按该文档拆计划并开始代码，作为实施依据；不继续旧 B0 路线。
- 当前在 harness 独立 worktree、detached HEAD 工作，不另建工作树。已有四份修改文档及未跟踪设计文档保留，不自动暂存或提交。
- `J/` 指 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`；`T/` 指同模块 `src/test/java/com/aifuli/module/supplychain/`；`R/` 指同模块 `src/main/resources/`。以下均按此前缀展开。
- `W/` 指 `/Users/mac/Documents/codeSpace/supplychainhubweb/`。编辑前须读取该仓 AGENTS.md／Git 状态。已核对实际 API 为 `DistributorApi.createBuyerProducts/saveBuyerProductPricing`、`ProjectApi.createProducts/saveProductPricing`。
- 首批执行 Task 1。Reader 真实事务门禁完成前不装配刷新、扫描或新 HTTP 准入。每片只声明其实际有限成果，不以单片代替业务整链。
- 060 已有来源／摘要／投影期限列，061 为当前末尾；不重复建列，不修改已交付 SQL 校验和。

## 2. 统一 TDD 命令与证据

每项必须测试先失败→最小实现→同一断言通过→直接回归。编译失败、Docker不可用、fixture失败不计业务 RED。后续片在动生产代码前补齐该片精确测试代码及调用签名，禁止以本总拆分的验收清单替代测试。

定向纯测试（TESTS 使用各任务类名；不运行其它模块单测）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false test
```

真实集成／契约（ITS 使用各任务类名；每类独占 Testcontainers MySQL 空库）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration -Dtest=NoSuchTestClass \
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test="$ITS" \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

原日志/XML保存批准临时目录，实际命令、计数、失败归因及联调维护 `docs/集成测试.md`。未执行标 NOT_RUN。只有用户授权才按单任务白名单 git add／中文git commit，不使用git add .。

## Task 1：真实首选资格纯映射（设计 §7.1）

**Files:** Create `J/tradecore/catalog/application/SupplyQualification.java`、`CurrentSupplyQualificationPolicy.java`；Test `T/tradecore/catalog/application/CurrentSupplyQualificationPolicyTest.java`；Modify `docs/集成测试.md`。

- [x] RED：真实引擎选中高优先 UNKNOWN，低位 CONFIRMED不能替换；映射保留原decision和首选，不授予下单资格。首轮3项中2个资格断言失败、0 error；缺类型的编译失败另计，不作为行为RED。
- [x] GREEN：封闭CONFIRMED／UNKNOWN／UNAVAILABLE枚举；只读原决策，不再次路由或排序，结果不可变。
- [x] RED：全部候选仅REQUIRED事实缺失／过期／UNKNOWN淘汰，映射UNKNOWN并保留九种真实原因。第二轮26项中20个断言失败、0 error，含原淘汰原因尚未保留的失败。
- [x] GREEN：精确封闭原因集合，混合结构故障／ERROR／明确不可用仍UNAVAILABLE，不泛匹配字符串。空候选无供给，无效类码技术失败。
- [x] 回归资格测试＋`SupplierRoutingEngineTest,SupplierRoutingRuleMatchingTest,SupplyChainModuleArchitectureTest`。定向74项全绿；供应链默认命名范围API123+server1920项全绿（不含Integration/Contract），见集成测试记录。

首片具体断言示例（完整fixture写在测试类，不mock内核）：

```java
RoutingCandidate unknown = candidate(1L, "UNKNOWN", true, List.of(), 90);
RoutingCandidate confirmed = candidate(2L, "CONFIRMED", true, List.of(), 60);
RoutingDecision decision = route(List.of(confirmed, unknown));
assertThat(decision.selectedCandidate()).isSameAs(unknown);
var result = CurrentSupplyQualificationPolicy.evaluate(decision);
assertThat(result.qualification()).isEqualTo(SupplyQualification.UNKNOWN);
assertThat(result.routingDecision()).isSameAs(decision);
assertThat(result.confirmed()).isFalse();
```

```java
RoutingDecision decision = route(List.of(candidate(1L, "UNAVAILABLE", false,
        List.of(reason), 90)));
var result = CurrentSupplyQualificationPolicy.evaluate(decision);
assertThat(result.qualification()).isEqualTo(SupplyQualification.UNKNOWN);
assertThat(result.reasonCodes()).containsExactly(reason);
```

实现接口：`evaluate(RoutingDecision)`返回`Result(SupplyQualification qualification, RoutingDecision routingDecision, List<String> reasonCodes)`；`confirmed()`仅枚举CONFIRMED返回true。选中UNKNOWN用原decision原因，无首选收集原顺序去重淘汰原因；无原因时保留decision.reasonCode。编译骨架不算实现成功，必须看到断言失败。

## Task 2：独立新 RR 资格 Reader（设计 §3、§7.1）

**Files:** Create `J/tradecore/catalog/application/CurrentSupplyEligibilityReader.java`、`J/routing/application/SupplyQualificationUnavailableException.java`；Modify `J/routing/application/SupplierRoutingApplicationService.java`、`J/tradecore/buyer/dal/mysql/ProjectListingMapper.java`；Test `T/tradecore/catalog/application/CurrentSupplyEligibilityReaderIntegrationTest.java`、`T/routing/application/SupplierRoutingQualificationFailureTest.java`。精确接口与封闭异常分类见设计 §7.1「Task 2 实施接口与异常边界」。

- [x] RED：真实代理REQUIRES_NEW/readOnly/REPEATABLE_READ；外层先建旧RR视图，独立连接提交停用，用latch固定顺序，Reader看到停用；无写入/锁供给/外呼/等待外层LST/BPD/PLT锁。Reader首轮有效RED为10 failure、0 error；后补视图建立后停用的线性化保护直接GREEN。
- [x] RED：OPTIONAL首选UNKNOWN、REQUIRED全缺失及固定UNSUPPORTED+REMOTE_ON_DEMAND真实资格；另项目通过不授权本项目。
- [x] RED：无配置的可恢复缺条件用专用异常；已有指针损坏/重复生效策略/跨归属/坏JSON技术失败，不能泛catch IllegalStateException。分类测试两轮有效RED分别13和2 failure、0 error；最后新增重复生效版本／真实SQL／非对象JSON集成保护直接GREEN，不伪称新增RED。
- [x] GREEN：首个业务实体读建立新视图并读取库时钟，真实route加入该只读事务；结果包含完整RoutingSnapshot/真实原因/来源/下一事实边界，包括策略、Offer、事实期限和配额自然日边界。无快照也保留已知策略边界；仅专用缺条件异常豁免参与事务回滚。
- [x] GREEN：聚合ACTIVE且有效PLT项目的真实首选，任一CONFIRMED可买家AVAILABLE；没有项目按projectId=null默认规则，不伪造项目身份。
- [x] `ITS=CurrentSupplyEligibilityReaderIntegrationTest,SupplierRoutingApplicationServiceIntegrationTest`；真实门禁与架构通过才推进Task3。最终补核及收尾复验均为分类20＋架构31、Reader17＋原路由1，0失败/错误/跳过，BUILD SUCCESS；本轮不实施Task3。

## Task 3：单 LST 自动刷新及人工恢复（设计 §3～§4）

**Files:** Create `J/tradecore/catalog/application/BuyerSelectionCatalogRefreshService.java`、`BuyerSelectionCatalogRefreshTransactionalWorker.java`、`BuyerSelectionCatalogRefreshResult.java`、`J/tradecore/catalog/domain/BuyerCatalogRefreshOutcome.java`；Modify `J/tradecore/catalog/application/BuyerListingPublicationService.java`、`J/tradecore/catalog/dal/mysql/BuyerListingVersionMapper.java`、`BuyerListingAvailabilityMapper.java`；Test `T/tradecore/catalog/application/BuyerSelectionCatalogRefreshIntegrationTest.java`、`BuyerSelectionCatalogRefreshTransactionTest.java`。

- [ ] RED：同来源重复/并发不新增版本/游标，迟到读当前；版本/current CAS/availability/本地change任一步失败全回滚。
- [ ] RED：MANUAL零写、RETIRED终态、SUSPENDED不发布/恢复、DRAFT缺条件等待、PUBLISHED失效保留历史，BPD移除/复活身份不变，PLT移除仅影响其项目。
- [ ] RED：READY标记但依赖失效不发布，重算退出LST事务后委派已有定价并确认提交再刷；缺合法系统operator不填0/冒充源发布人；人工expectedVersion恢复在同一LST锁内核价和资格。
- [ ] GREEN：NEVER门面→独立REQUIRED worker，LST→BPD→BuyerPricing→availability锁序，不锁SKU/PLT/project/供给。商业价来自真实READY分销价CNY非负，稳定摘要含来源/商业字段/冻结有效期，不含刷新时间。
- [ ] GREEN：AUTO只同事务追加本地目录游标，无fanout；无变化可更新评估期限不新增游标；技术失败不延期旧AVAILABLE，旧MANUAL保持。
- [ ] `ITS=BuyerSelectionCatalogRefreshIntegrationTest`＋现有发布回归＋架构；SQL/锁序用真实MySQL证明。

## Task 4：AUTO 陈旧投影读侧（设计 §6.4）

**Files:** Modify `J/tradecore/catalog/application/BuyerCatalogQueryService.java`、`J/tradecore/catalog/dal/read/BuyerCatalogReadMapper.java`、`J/tradecore/catalog/readmodel/BuyerCatalogReadRow.java`及其已有XML；Test `T/tradecore/catalog/application/BuyerCatalogFreshnessIntegrationTest.java`、`T/interfaceapi/rpc/BuyerCatalogFreshnessContractTest.java`。

- [ ] RED：AUTO期限为空/等于库时钟/已过期、当前价格来源不匹配→UNKNOWN；availableOnly同规则排除，MANUAL不变。
- [ ] GREEN：valid_until=min(未来真实事实边界, evaluatedAt+最大有效期30秒)，详情和筛选一致；摘要不含evaluatedAt/滚动30秒截止/basisVersion。技术失败不延时。
- [ ] `ITS=BuyerCatalogFreshnessIntegrationTest,BuyerCatalogFreshnessContractTest`；增量/分页/旧MANUAL契约回归。

## Task 5：管理保存提交后编排（设计 §5）

**Files:** Create `J/tradecore/catalog/application/BuyerSelectionCatalogAdminCoordinator.java`、`J/interfaceapi/admin/vo/BuyerSelectionCatalogSaveRespVO.java`；Modify `J/interfaceapi/admin/BuyerAdminController.java`、`ProjectAdminController.java`；Test `T/interfaceapi/admin/BuyerSelectionCatalogAdminIntegrationTest.java`、`BuyerSelectionCatalogAdminContractTest.java`。

- [ ] RED：四个POST/PUT保留URL/请求/权限，响应COMMITTED+真实savedCodes（分销BPD/项目PLT）和逐目录结果，不改领域签名/旧L2 PATCH。
- [ ] RED：保存回滚不刷新；已保存部分刷新失败不重跑worker；定位失败返回已保存+FAILED；提交未确认不产COMMITTED。
- [ ] RED：失败事务状态/时间不进入响应；失败后真实已提交快照可附，读取也失败字段为空，无假PUBLISHED/AVAILABLE，traceId诊断无SQL/堆栈泄露。
- [ ] GREEN：无活动事务编排确认原代理提交后刷新去重BPD/LST；七种outcome与生命周期分开，automaticRecoveryEnabled读配置。
- [ ] `ITS=BuyerSelectionCatalogAdminIntegrationTest,BuyerSelectionCatalogAdminContractTest`；原整批/级联原子性回归。

## Task 6：有界公平扫描（设计 §6.1～§6.3）

**Files:** Create `J/tradecore/catalog/application/BuyerSelectionCatalogSweepService.java`；Modify `J/tradecore/catalog/dal/mysql/BuyerListingMapper.java`；Test `T/tradecore/catalog/application/BuyerSelectionCatalogSweepServiceTest.java`、`BuyerSelectionCatalogSweepIntegrationTest.java`。

- [ ] RED：>100条、首条持续失败、持续新增高ID、空页后新增、重启/退出/多实例；查询失败不推进，单条尝试即推进且失败不阻断其余。
- [ ] GREEN：普通有限ID查询AUTO非RETIRED全部状态，cursor<id<=upperId，每遍开始读上界，到上界/空页重置；无跨批事务/页表/claim/租约/选主。
- [ ] RED→GREEN：无用户操作补价/Offer/路由真实自动发布，供给失效/恢复，人工暂停不恢复；保存后退出和多实例靠重扫+摘要唯一幂等。
- [ ] `ITS=BuyerSelectionCatalogSweepIntegrationTest`；停止/中断后不启动新条目，不伪造成功。

## Task 7：扫描生命周期、配置与日志（设计 §6、§9）

**Files:** Create `J/tradecore/catalog/application/BuyerSelectionCatalogSweepLifecycle.java`、`J/infrastructure/configuration/BuyerSelectionCatalogProperties.java`；Modify `R/application.yaml`；Test `T/tradecore/catalog/application/BuyerSelectionCatalogSweepLifecycleTest.java`、`T/infrastructure/configuration/BuyerSelectionCatalogConfigurationTest.java`。

- [ ] RED：默认enabled、fixed-delay30秒、batch100、最大评估30秒，非正拒绝；关闭无线程/自动重试承诺，系统operator缺失真实报未就绪。
- [ ] RED→GREEN：每轮新traceId/executionId、完整MDC恢复/异常/嵌套/线程复用；低基数有界限频首次因果/持续汇总/恢复，ServiceException单行WARN。
- [ ] GREEN：供应链专用单线程调度器启动即扫描，批结束再delay；不改共享/nonce/Timer/Outbox或八进程。提交后发布成功、无变化DEBUG。
- [ ] 生命周期/配置与供应链根日志配置回归；不将message键值当全系统顶层JSON契约已实现。

## Task 8：冻结身份优先与回放（设计 §8）

**Files:** Create `J/border/fulfillment/application/FrozenAcceptanceIdentityReader.java`；Modify `J/application/facade/ProjectStandardOrderCommandFacade.java`、`BuyerFulfillmentCommandFacade.java`、`J/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`；Test `T/border/fulfillment/FrozenAcceptanceReplayIntegrationTest.java`、`T/application/facade/ProjectStandardOrderFrozenReplayTest.java`。

- [ ] RED：ACCEPTED后SKU/LST删除、Schema注册/价格/供给变化同参回放；改原编码即使同SKU冲突，项目隔离/主体ACTIVE前置保持。
- [ ] RED：格式0/缺可信绑定原历史错误，不补当前；PROCESSING/REJECTED_RETRYABLE不得套ACCEPTED绕准入。
- [ ] GREEN：两门面当前Schema前读可信projectId+buyerOrderNo绑定，以冻结SKU/Schema/原规范化规则算格式2指纹，claim当前锁裁决；不再次要求当前Schema可用，Facade无DAL。
- [ ] `ITS=FrozenAcceptanceReplayIntegrationTest`；ACCEPTED不刷新/首冻/route/重复预留，永久订单/报价唯一。

## Task 9：首冻前准备（设计 §7.2）

**Files:** Create `J/tradecore/catalog/application/BuyerSelectionCatalogRequestCoordinator.java`；Modify `J/application/facade/ProjectStandardOrderCommandFacade.java`、`J/tradecore/catalog/pricing/StandardPricingQuoteService.java`、`J/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`；Test `T/border/fulfillment/StandardCatalogRequestPreparationIntegrationTest.java`。

- [ ] RED：claim后仅有效新执行尝试AUTO刷新，MANUAL只核商业发布/有效性及真实项目资格，手工商业/状态/投影零写。
- [ ] RED：未发布/暂停/退役/明确不可用无新报价，真实可重试拒绝；缺价保留STANDARD_PRICING_QUOTE_MISSING/PROCESSING，技术失败不伪落拒绝。
- [ ] GREEN：READY沿永久项目键首冻，核真实当前定价依赖，永久旧报价不重定价；买家聚合不授权本项目。
- [ ] `ITS=StandardCatalogRequestPreparationIntegrationTest`；MANUAL PUBLISHED/UNKNOWN新HTTP凭CONFIRMED受理，旧RPC原拒绝。

## Task 10：最终当前供给与原子受理（设计 §3、§7.2）

**Files:** Modify `J/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`及真实受理协调入口；Test `T/border/fulfillment/StandardCurrentSupplyAcceptanceIntegrationTest.java`；Regression `T/border/fulfillment/FulfillmentAcceptanceIntegrationTest.java`。

- [ ] RED：旧外层RR后停用已提交最终Reader见停用；新视图后停用遵守线性化；SKU锁等待后见前序配额，用latch控制提交顺序。
- [ ] RED：BPD/PLT移除、报价/Schema绑定不符、UNKNOWN/UNAVAILABLE无订单/消费/预留；各写步骤注入失败全回滚。
- [ ] GREEN：身份事务外定位，receipt→LST→SKU→quote→PLT→BPD锁序；建单/消费/资金前新Reader仅CONFIRMED，用其RoutingSnapshot持久路由/BOrder，不在旧视图再route。所有受理效果/ACCEPTED同事务，金额来自永久报价。
- [ ] `ITS=StandardCurrentSupplyAcceptanceIntegrationTest,FulfillmentAcceptanceIntegrationTest`＋架构。

## Task 11：前端仅结果提示/类型（设计 §5.3）

**Files:** Modify `W/src/api/supplier/distribution/index.ts`、`W/src/views/supplier/distribution/distributor/detail/ProductSelectDialog.vue`、`ProductList.vue`及项目实际对应组件；Create `W/src/views/supplier/distribution/utils/catalogSaveResult.ts`。

- [ ] RED：纯提示函数测已发布/待就绪/部分失败身份原因/MANUAL/暂停/退役/扫描关闭/无响应未确认，沿前端已有可运行测试方式，不无必要引入框架。
- [ ] GREEN：四API同次改结构类型；COMMITTED关表单/刷新列表，刷新失败不重存；超时提示查列表只确认选品/价格事实，不自动重发。
- [ ] 不增加列/按钮/项目BPD或PLT/菜单/轮询。`pnpm ts:check`、定向eslint/prettier、真实页面验证分开记录。

## Task 12：真实管理→签名受理整链（设计 §10）

**Files:** Create `T/interfaceapi/admin/SaleableCatalogSignedOrderIntegrationTest.java`；Modify `docs/集成测试.md`。

- [ ] RED→GREEN：真实管理Controller选品/定价→自动目录→签名HTTP→真实route和预留，金额永久报价；禁止SQL补价/AVAILABLE绕链。
- [ ] 全供应链Test、profile Integration/Contract、Architecture真实门禁；原日志/XML/受测范围/配置审阅/实机结果分别归档。
- [ ] 规格覆盖：设计§10场景1→Task5；2→3；3/4→6/7；5→4；6→1/2；7→2/10；8→9；9→8/10；10→12；11→11；12→7。未执行仍未验收。
- [ ] 只声明到受理，华昇生产执行/资产/通知/售后不属本片，真实供应商不自动获调用授权。

## 3. 本轮执行记录

- 初始相关基线：JDK21，`SupplierRoutingEngineTest,SupplierRoutingRuleMatchingTest,SupplyChainModuleArchitectureTest` 共48项、0失败/错误/跳过，BUILD SUCCESS；不是全量门禁。
- Task1纯映射已实现并通过本机TDD及直接回归；新资格测试26项、定向74项，供应链默认单元/架构命名范围共2043项，0失败/错误/跳过。当前未做独立代码审查，不将内联自审称为独立审查。
- Task2已实现独立新RR资格Reader及封闭缺条件／损坏引用分类，并完成本机真实事务门禁。扩大回归默认单元／架构命名范围API123＋server1940，共2063项；该轮集成Reader14＋原路由1。随后补充3项技术异常集成保护，最终补核和收尾复验均分类20＋架构31、Reader17＋原路由1全绿；命令、RED归因及XML归档见 `docs/集成测试.md` 的Task2记录。仅内联自审，没有独立审查结论。
- 下一检查点Task3：单LST自动刷新及人工恢复。Task3～12均NOT_RUN，Reader尚未接入商业刷新、扫描或HTTP新准入；旧Lifecycle宽泛路由catch仍属于Task10范围。不宣称整体业务链完成，未提交、推送或部署。
