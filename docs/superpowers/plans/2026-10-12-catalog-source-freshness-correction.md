# 目录真实来源与状态纠正实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新鲜同源目录不再误报 SOURCE_CHANGED，真实陈旧不能漏报，管理读、RPC输出和分页筛选一致。

**Architecture:** 在 catalog 内共用无持久化依赖的 AUTO 读决策，真实来源从既有 basis JSON 读取。多表 Mapper 先等价迁至 XML，再变更筛选；GET 保持只读且共享 RR 视图与一次库时钟。前端只修状态／原因展示，SKU v2 按另一切片实施。

**Tech Stack:** Java21、Spring事务、MyBatis、MySQL8.4、Liquibase、JUnit5、Testcontainers、Vue3、Element Plus、Node SFC测试。

---

设计依据：`../specs/2026-10-12-catalog-source-freshness-correction-design.md`，已获用户书面通过。后端工作目录 `/Users/mac/codespace/supply-hub`，前端 `/Users/mac/codespace/supplychainhubweb`；在原 dev 工作区实施，不创建或切换其它执行环境，不推送／部署／改共享数据。下述 `J` 为后端 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain`，`T` 为同模块 `src/test/java/com/aifuli/module/supplychain`，`R` 为同模块 `src/main/resources`。

## Task 1：把复杂读模型等价迁至 XML

**Files:**
- Modify: `J/tradecore/catalog/dal/read/BuyerCatalogReadMapper.java`
- Create: `R/mapper/catalog/BuyerCatalogReadMapper.xml`
- Test: `T/tradecore/catalog/application/BuyerCatalogFreshnessIntegrationTest.java`

- [ ] 记录未改实现下既有 freshness 集成结果：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false -Dit.test=BuyerCatalogFreshnessIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false
```

预期目标类4条通过；如有基线环境失败记录原因，不能宣称通过。

- [ ] 将原 `READ_COLUMNS`、`AVAILABLE_ONLY_CONDITION` 和 page/count/byId 原文迁为 XML `<sql>`／`<include>`；接口只保留 `@Mapper`、方法及原 `@Param`。namespace 为 `com.aifuli.module.supplychain.tradecore.catalog.dal.read.BuyerCatalogReadMapper`，resultType 为 `com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow`。不改 JOIN、排序、页大小、字段与参数。生产 mapper-locations 和手工测试 SqlSessionFactory 的 XML 加载必须一起核对。

```xml
<select id="selectByListingId" resultType="com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow">
  <include refid="readColumns"/> WHERE l.id = #{listingId}
</select>
```

- [ ] 重跑同一集成命令，4条行为仍通过后单独提交“迁移采购商目录读模型至XML保持原行为”。

## Task 2：先红后绿修管理与 RPC 真实来源

**Files:**
- Modify: `J/tradecore/catalog/application/BuyerAvailabilityBasis.java`
- Create: `J/tradecore/catalog/application/BuyerCatalogAutoReadPolicy.java`
- Modify: `J/tradecore/catalog/application/BuyerAcceptanceCatalogReadService.java`
- Modify: `J/tradecore/catalog/application/BuyerCatalogQueryService.java`
- Modify: `J/tradecore/catalog/readmodel/BuyerCatalogReadRow.java`
- Modify: `R/mapper/catalog/BuyerCatalogReadMapper.xml`
- Test: `T/tradecore/catalog/application/BuyerAcceptanceCatalogReadServiceTest.java`
- Test: `T/tradecore/catalog/application/BuyerCatalogQueryServiceTest.java`

- [ ] 在既有 fixture 中写入真实 basis JSON，counter取5、source取3。扩展公共 read/page 输出断言；不把预期值从实现算出。

```java
projection.setBasisVersion(5L);
projection.setBasisJson("{\"listingId\":4,\"sourceBuyerPricingVersionId\":3}");
version.setSourceBuyerPricingVersionId(3L);
when(pricing.selectByBuyerProductId(3L)).thenReturn(BuyerSkuPricingDO.builder()
        .id(7L).buyerProductId(3L).currentVersionId(3L).pricingStatus("READY").build());
assertThat(field(read(), "availabilityStatus")).isEqualTo("AVAILABLE");
projection.setBasisVersion(3L);
projection.setBasisJson("{\"listingId\":4,\"sourceBuyerPricingVersionId\":2}");
assertThat(field(read(), "catalogRefreshReason")).isEqualTo("SOURCE_CHANGED");
```

- [ ] 运行并确认当前源码第一例误报、第二例漏报，记录红灯：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false -Dtest=BuyerAcceptanceCatalogReadServiceTest,BuyerCatalogQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] basis 提取先验证 JSON 为对象，再逐字段验证正整数／Long范围／listing归属；来源允许显式 null。正常缺键或缺JSON返回未评估，损坏抛有因果的 IllegalStateException。MANUAL不解析。新增纯决策输入包含 listingId/status、pricing status/current、basis JSON、evaluatedAt、validUntil、commercialPresent/source/from/to、stored availability/reason、now；输出 availability/reason。顺序严格采用设计§4，不接收basisVersion。

```java
// 来源比较必须在期限判断前；商业版本不存在的 DRAFT 不要求商业来源。
if (!Objects.equals(basis.sourceBuyerPricingVersionId(), currentPricingVersionId)
        || commercialPresent && !Objects.equals(commercialSourceVersionId, currentPricingVersionId)) {
    return new Decision(ListingAvailability.UNKNOWN, CatalogRefreshReason.SOURCE_CHANGED.name());
}
```

- [ ] 两读服务委派同一决策；Row及XML追加basisJson、reasonCode、commercialValidFrom/To。RPC page/changes 使用供应链 readOnly RR 事务，同一次now用于buyer解析、list、count、toItem。MANUAL/无管理模式保持原非READY→UNAVAILABLE行为。
- [ ] 追加边界／完整性断言：真实商业失配、显式null、缺键、未评估、READY空current、非READY、暂停/退役、DRAFT有效供给、截止等于now及±1ms、商业开始/结束、错误类型／越界／错归属、MANUAL损坏AUTO JSON仍不受影响。
- [ ] 重跑上述单测，全部绿灯后继续，不把counter重置或新增数据迁移。

## Task 3：SQL筛选与输出一致，真实MySQL验收

**Files:**
- Modify: `R/mapper/catalog/BuyerCatalogReadMapper.xml`
- Test: `T/tradecore/catalog/application/BuyerCatalogFreshnessIntegrationTest.java`
- Test: `T/interfaceapi/rpc/BuyerCatalogFreshnessContractTest.java`

- [ ] 集成fixture正常publish后改变basis计数但保持来源，page(true)的list/total必须都是1；只改变JSON来源为正的旧ID，page(false) UNKNOWN、page(true) list空total0。用null、缺键、字符串"3"、负数、超Long、错listing、未评估、未来商业起点、已结束商业版本逐项验证；损坏普通读项抛系统失败，筛选安全排除。
- [ ] 运行Task1命令，确认旧筛选放过JSON陈旧／类型损坏，记录红灯。
- [ ] XML共用availableOnly片段增加真实来源／期限条件。MySQL JSON_TYPE限定整数并用DECIMAL(65,0)比较，不用宽松字符串转Long；缺键/null不满足筛选。

```xml
AND availability.evaluated_at IS NOT NULL
AND JSON_TYPE(availability.basis_json) = 'OBJECT'
AND JSON_TYPE(JSON_EXTRACT(availability.basis_json, '$.listingId')) IN ('INTEGER', 'UNSIGNED INTEGER')
AND CAST(JSON_UNQUOTE(JSON_EXTRACT(availability.basis_json, '$.listingId')) AS DECIMAL(65,0)) = l.id
AND JSON_TYPE(JSON_EXTRACT(availability.basis_json, '$.sourceBuyerPricingVersionId')) IN ('INTEGER', 'UNSIGNED INTEGER')
AND CAST(JSON_UNQUOTE(JSON_EXTRACT(availability.basis_json, '$.sourceBuyerPricingVersionId')) AS DECIMAL(65,0)) = pricing.current_version_id
AND lv.valid_from IS NOT NULL AND lv.valid_from &lt;= #{now}
AND (lv.valid_to IS NULL OR lv.valid_to &gt; #{now})
```

- [ ] 重跑集成并验证 GET 前后 listing、版本、投影、change、Outbox 行值／数量不变；changes返回当前UNKNOWN/AVAILABLE。并发刷新在list/count间完成时，仍读一个RR快照，不用睡眠碰运气。
- [ ] 运行影响范围门禁：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false -Dit.test=BuyerCatalogFreshnessIntegrationTest,BuyerCatalogFreshnessContractTest,BuyerSelectionCatalogAdminContractTest,ProductCenterContractTest -Dfailsafe.failIfNoSpecifiedTests=false
```

核对实际报告Tests run >0；不存在的类名以已存在目录契约名称修正，不把忽略无匹配当通过。

## Task 4：管理页面中文状态与诊断码

**Files（frontend repo）:**
- Modify: `src/views/supplier/distribution/distributor/detail/ProductList.vue`
- Modify: `tests/catalog-recalculate.test.mjs`（真实SFC测试，复用`tests/support/product-list-sfc-harness.mjs`）。

- [ ] 通过既有真实SFC加载方法，断言UNKNOWN标签“供给待确认”、SOURCE_CHANGED中文原因及title原码；先在旧页面跑出红灯。
- [ ] 修改可见原因表达式为`getCatalogReasonLabel(row.catalogRefreshReason)`，title保留原码；函数按设计§6映射，未知非空码显示“供给条件尚未确认”，CONFIRMED显示“供给条件已确认”。暂停/退役标签优先不变。旧BPD/v1文案不提前更改。

```ts
case 'UNKNOWN':
  return '供给待确认'
```

- [ ] 在实际frontend目录跑既有SFC测试与定向eslint/prettier检查，运行ts:check并区分既有错误。
- [ ] 渲染真实ProductList SFC，截图并用view_media逐个检查默认正常、SOURCE_CHANGED、过期、暂停、退役状态。截图存backend `.amp/in/artifacts/`，不得用手绘替代真实组件。

## Task 5：记录交付与第二切片接续

- [ ] 在`docs/集成测试.md`追加红/绿结果、实际测试数、只读/并发证据、前端截图和检查限制，远端仍NOT_RUN。
- [ ] 更新本计划复选框及第一份设计实际实施状态；用`git diff --check`、diff复核范围并以中文分别提交后端及前端。不推送或部署。
- [ ] 状态切片验证后，创建独立SKU v2实施计划，保留第二设计的格式3／安全接线／兼容与回滚门禁，再实施第二切片；不把第一切片通过当v2完成。
