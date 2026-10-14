# 分销商品通用定价无损编辑实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 按任务依次实施；规格审阅通过后进行代码质量审阅。用户已授权修复。

**Goal:** 保存隐藏阈值 2 后重开仍回显 2，无修改保存保留五组配置的业务语义。

**Architecture:** 现有列表从当前版本追加原始五组配置，前端以结构字段回填并复制未编辑配置。显式继承开关区分继承与覆盖，旧列退出编辑链。保存沿用完整五组 PUT 与既有目录刷新结果。

**Tech Stack:** Java21/Spring/MyBatis Plus/MySQL/Liquibase/Maven；Vue3/TypeScript/Element Plus/Node 原生测试。

## 任务 1：后端编辑快照

- [x] 修改 `BuyerProductQueryServiceTest`：当前新版本隐藏 2、旧列 null/7 均返回原始 2；销售区 5 不被最大值覆盖；五组继承和公式完整；无版本 UNPRICED；版本归属/JSON损坏失败关闭。先运行新增测试观察失败。
- [x] 修改 `tradecore/catalog/application/BuyerProductQueryService.java`，新增应用 DTO：响应 `currentPricingVersionId/pricingStatus/buyerPricingConfig`，读取当前版本与 `override_json`；未定价返回 null/UNPRICED/null；不完整配置拒绝，避免旧字段回退。
- [x] 配置 DTO 字段为 `distributionInherited/distributionRule/salesInherited/salesRule/serviceFeeInherited/serviceFeeRule/hideInherited/hideRule/taxInherited/taxMetadata`。规则成员使用现有领域语义，隐藏原始字段为 `distributionThresholdPercent/salesThresholdPercent`。保存规则不变。
- [x] 追加真实 HTTP 契约及 MySQL 事务测试，使用已有 `BuyerSelectionCatalogAdminIntegrationTest` 的 Spring/Testcontainers/Liquibase 配置，真实保存后查询再保存；验证原始两区阈值、完整配置、历史不变、读取无写入及错误回滚。
- [x] 命令：JDK21 `mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=BuyerProductQueryServiceTest,GenericPricingRequestMapperTest,GenericBatchPricingServiceTest,GenericPricingCalculatorTest,ProjectPricingConfigMergerTest -Dsurefire.failIfNoSpecifiedTests=false test`；红灯后最小实现，再绿灯。集成契约使用 `verify -Psupply-chain-integration -Dit.test=BuyerProductPricingRoundtripIntegrationTest,BuyerProductPricingReadContractTest -Dfailsafe.failIfNoSpecifiedTests=false`。
- [x] 规格审阅、质量审阅；记录改动和结果，不自动提交或推送。

## 任务 2：前端完整回填与提交

- [x] 当前仓库 `/Users/mac/codespace/supplychainhubweb`。新增 `tests/distributor-pricing-roundtrip.test.mjs`：编译真实Vue/TypeScript函数、表单和API，先观察当前代码失败。2→重开→再保存保持2，旧字段无效；0/2.25/100/两区/销售区单独/双null/继承；销售价服务费保持；公式四种运算与基数；不含税NON_TAXABLE；缺字段/配置损坏禁用；未定价初始化。
- [x] 修改 `src/api/supplier/distribution/types.ts`：追加当前版本、状态与完整 `BuyerPricingConfigView`；使用可选字段识别旧服务字段缺失，明确 null 未定价。
- [x] 修改 `distributor/detail/distributionPrice.ts`：建立编辑配置解析和完整请求序列化，基于原始快照只替换分销价、隐藏组和税务；保持原销售区阈值、销售价与服务费。覆盖不含税编码复用 `PRICING_TAX_RATE_CODES.NON_TAXABLE`。
- [x] 修改 `distributor/detail/ProductList.vue`：停止文案/旧列回填，使用新增配置；保存前拒绝未加载/缺损快照，列表失败释放编辑资格；无修改请求完整。沿用保存提示，不重复请求。
- [x] 修改 `DistributionPriceForm.vue`：仅编辑模式提供分销价、隐藏组、税务的显式继承开关，继承时跳过相应覆盖校验；共享创建流程保持当前行为。
- [x] `node --test tests/distributor-pricing-roundtrip.test.mjs` 红→绿；随后全套 `node --test tests/*.test.mjs`、受影响源文件 ESLint，TypeScript如遇既有错误对比未改HEAD基线。
- [x] 规格审阅、质量审阅；不自动提交或推送。

## 任务 3：最终门禁与交付

- [x] 后端供应链普通/架构全套，及新增和直接受影响 MySQL/契约门禁；其它模块单测不运行。前端完成全套回归及基线对比：327/328 通过，唯一既有路由失败不属于本次改动；类型诊断 53/基线54，无新增。
- [x] 更新 `docs/集成测试.md`，记录红绿证据、源码/自动化/实机区别，发布后浏览器及用户端目录验收步骤。更新本计划状态。
- [x] 检查 `git diff --check`，交付前后端修改、测试结果及未部署边界。

## 完成记录（2026-10-14）

前后端实现、红绿回归、规格与质量复审、最终门禁及文档已完成。后端2434项通过，前端新增87项与受影响112项通过；全量前端既有1项路由失败及53项类型诊断按基线如实记录。未提交、推送或部署；真实浏览器与远端目录验收作为发布后步骤，不冒充本次已完成测试。详见核查报告第9节与 `docs/集成测试.md` 本次修复记录。
