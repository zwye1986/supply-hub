# 供应价驱动建议价自动重算实施计划

> 执行方式：本会话按 writing-plans、test-driven-development 执行；前端与读取/事件盘点按 subagent-driven-development 委派，后端核心依赖任务顺序实施。前端规格/质量已通过；后端规格复核发现两项版本完整性缺口，补RED回归并修复后复核通过，质量复核通过。

**目标：** 完整实现 `../specs/2026-10-19-suggested-sale-price-supply-base-design.md`，包含自动重算、无供给待计算、到期恢复、全部价格读取入口与前端展示。

**架构：** SKU JSON 是公式来源；独立计算状态保存动态结果，统一解析器按数据库时间给出可空有效金额。主动事件在根锁内先计算状态、再发布三层定价；持久化 Timer 补时间边界，读侧即时校验。

**技术栈：** JDK21 / Spring / MyBatis Plus / Liquibase / MySQL8.4 / PersistentTimer；Vue3 / TypeScript / 真实 SFC Node 测试。

## 任务1：纯计算与空依赖

- [x] 在 `tradecore/catalog/domain/SuggestedSalePriceCalculatorTest.java` 增加供应价80元×110%+1元=89元、真实0、缺失、非法参数、后续负数/溢出断言。
- [x] 在 `tradecore/catalog/pricing/GenericPricingCalculatorTest.java` 增加 null 建议价仅使依赖该基数的公式不可用。
- [x] 执行 `mvn -o -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=SuggestedSalePriceCalculatorTest,GenericPricingCalculatorTest -Dsurefire.failIfNoSpecifiedTests=false test`，先观察失败再修改。
- [x] 扩展 `SuggestedSalePriceRule.Base.SUPPLY_PRICE`，为纯计算器增加供应价输入、参数单独校验及动态结果状态；`PriceDependencies` 建议价改为 Long。保留旧三参数计算 API。
- [x] 相同命令验证通过。

## 任务2：迁移和统一解析

- [x] 新增字典与数据库迁移测试并先验证失败；保持018和所有已执行变更集内容不变。
- [x] system 新增019字典增量；供应链新增068状态表及三层快照nullable增量。新类 `SkuSuggestedPriceStateDO/Mapper` 仅负责持久化，Lambda更新明确写NULL。
- [x] 新类 `SuggestedSalePriceResolver` 提供批量只读 `resolve(List<PlatformSkuDO>, LocalDateTime)` 和单行解析，结果包含当前金额/状态/原因/基数/上次成功金额；即时计算不写库。
- [x] 新类 `SuggestedSalePriceStateService` 在定价根锁下显式写状态，不回写SKU；规则摘要不含服务端快照，无变化不增加revision。
- [x] Testcontainers验证迁移、约束、幂等、null快照及状态恢复。

## 任务3：商品保存与全部读取入口

- [x] 对 `ProductCatalogCommandService` 增加无供给公式保存测试，非法参数拒绝及模式切换旧金额保护。
- [x] 保存SUPPLY_PRICE只规范化公式，原兼容金额不作为计算结果；新建/编辑统一触发状态刷新与首版初始化，不重复发布版本。
- [x] 修改 `ProductCatalogQueryService`、`PlatformSkuQueryService`、`BuyerProductQueryService`、`ProjectListingQueryService`、`SupplierCooperativeProductService`，批量读取经resolver返回有效金额，原列不得泄漏0或旧值。
- [x] 扩展详情/列表状态原因、历史金额时间、supplyPrice字段及Schema合同。
- [x] 真实HTTP/Swagger与复合保存集成测试验证null与历史兼容。

## 任务4：供给联动与定价顺序

- [x] 增加供给80→90、草稿无影响、最低候选切换、全部失效/恢复、规则不变结果不变、供应价负结果状态测试。
- [x] 单一同步编排收口 `PricingDependencyChangedEvent`，取得根锁和锁后新输入，调用状态刷新再计算平台→分销商→项目；保留RC、SKU升序和无根初始化协议。
- [x] 审查并补齐供应商、Offer、连接器、能力、库存、SPU/SKU状态变化事件；不发布草稿事件。
- [x] `GenericBatchPricingService` 全部依赖与预览使用有效建议价，交易首次冻结前按根→子顺序刷新。已有永久报价分支仍重放。
- [x] 真实MySQL并发覆盖商品编辑/供给/报价/根锁等待与回滚。

## 任务5：有效期 Timer

- [x] 新增持久化handler、边界调度与恢复测试；先验证未来生效/到期不会自动刷新的失败。
- [x] 在当前版本发布以及首次保存供应价公式时登记所有候选validFrom/validTo；幂等键包含版本ID和边界类型，使用数据库时钟和原PersistentTimer/账本。
- [x] Handler仅调用事务编排。延迟/旧/重复任务重读当前事实，已切换规则无害结束。
- [x] 验证停机到期/重启恢复、Timer幂等、读GET零写与报价不冻结过期金额。

## 任务6：前端（独立文件范围）

- [x] 在真实SFC测试增加SUPPLY_PRICE选择、无供给可保存、历史参考独立、失败状态、服务端结果显示和不回传旧金额的失败断言。
- [x] 修改 `/Users/mac/codespace/supplychainhubweb/src/views/supplier/product/list/form/index.vue`、`src/api/product/types.ts`、`src/views/supplier/product/list/utils/suggested-sale-price.ts` 及列表/详情，字典允许两个新基数、历史规则仍只读。
- [x] 执行 `node --test tests/product-suggested-price-base.test.mjs tests/product-suggested-sale-price.test.mjs tests/product-shelf-sync.test.mjs tests/product-pricing.test.mjs tests/product-detail.test.mjs`；定向lint、类型检查及开发构建区分原有诊断。

## 任务7：规格/质量复核与交付

- [x] 逐条对照设计§2～10审核实现及测试，规格通过后进行代码质量审查，修复并复验。
- [x] 供应链普通/架构与适用集成/契约门禁；system仅执行本次迁移的真实MySQL验证。
- [x] 更新 `docs/集成测试.md` 为本地实测与未执行实机边界，记录命令、结果和失败修复。两个仓库diff检查。
- [x] 完整目标审计证明所有要求后才标记完成；本轮不自动部署、不写共享环境。

## 执行结果（2026-10-19）

- 全部任务已完成；后端规格、代码质量及时间戳补丁复核通过。两个版本完整性问题和真实时间戳展示问题均先补失败回归后修复。
- 普通/架构2360、HTTP/RPC契约348、相关真实MySQL集成82、前端115全部通过；前端生产构建13.09秒通过。全量前端类型/lint基线限制与命令见 `docs/集成测试.md#2026-10-19-sku供应价基准自动重算本地实现与验证共享环境未部署`。
- 本轮代码留在两个指定仓库待评审；未提交、推送、部署或写pro143，保留merchant-quota其它任务文件。
