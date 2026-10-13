# 供应商合作商品销售建议价修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development，逐任务执行并完成规格/质量审查。用户已授权审查后执行，本计划不追加人工审批门禁。未经明确要求不提交、推送或部署。

**Goal:** 合作商品列表、新增、改价与DISCOUNT商业条款试算统一SKU销售建议价，保留旧L1字段兼容。

**Architecture:** 沿用现有独立供应链数据源、应用服务和交易事件；增量返回SKU建议价，折扣准入由服务端读取SKU。前端只使用明确的新价格字段。后端工作树通过using-git-worktrees创建，最终经校验同步回当前目录；前端遵守用户确认的当前codebase路径。

**Tech Stack:** Java21 / Spring / MyBatis Plus / JUnit / Testcontainers MySQL8.4 / Vue3 / TypeScript / Node原生测试。

---

## Task 1：设计与独立审查

- [x] 已写 `docs/superpowers/specs/2026-10-11-supplier-offer-sale-price-alignment-design.md`，同步09-02接口设计§5.5。
- [x] 独立审查实际实现、错误码、兼容、价格语义与门禁，处理P1/P2后才能开始修改实现。
- [x] 保存审查结论到设计§9，必要时同步精确修订。

## Task 2：后端（工作树 /Users/mac/.codex/worktrees/supplier-sale-price/supply-hub）

修改：API `error/SupplyChainErrorCodeConstants.java`；server `tradecore/supply/application/SupplierCooperativeProductService.java`；`interfaceapi/admin/SupplierAdminController.java`。
测试：`SupplierCooperativeProductServiceTest`、`SupplierAdminRestContractTest`、`SupplierAdminProfileIntegrationTest` 或专用新 `SupplierOfferSalePriceIntegrationTest`，另有错误码契约与架构测试。

- [x] 先新增列表新/旧价格分别回显、SKU正价与客户端提示价独立、0/null拒绝DISCOUNT等用例，运行并保存RED；新字段尚未存在时允许先用JSON/反射取值，使失败定位于缺失功能而不是编译错误。
- [x] 新增 `Long suggestedSalePriceCents`，由已有sku直接读取；旧 `suggestPriceCents`保留旧L1查询。
- [x] `validatePrice` 接收权威SKU价，DISCOUNT仅据SKU非空正价准入；请求提示字段保留并注明兼容。新增稳定业务码178与重试映射，不重复记录异常。
- [x] 跑GREEN、真实MySQL/Liquibase事务与分页验证，检查拒绝零写入，验证不存在L1仍正常显示SKU建议价；契约保持双方金额不同而互不覆盖。
- [x] 受影响纯逻辑/服务/API错误目录/架构GREEN共178项（101+28+9+9+31）；完整命令及XML证据见 `docs/集成测试.md` 本次修复验证记录。
- [x] 使用 `-Psupply-chain-integration verify` 实际Failsafe执行：新 `SupplierOfferSalePriceIntegrationTest`7项、既有 `OfferListOnShelfIntegrationTest`1项、四类供应商契约19项，共27项通过；独占MySQL8.4与64个Liquibase changesets，无业务环境写入。后端目标门禁合计205项。

## Task 3：前端（仅 /Users/mac/codespace/supplychainhubweb）

修改：`src/api/supplier/manage/types.ts`、`src/views/supplier/manage/detail/ProductList.vue`、`ProductSelectDialog.vue`。
测试：新 `tests/offer-sale-price.test.mjs` 并扩展已有 `tests/support/product-list-sfc-harness.mjs`，编译挂载真实SFC与模板，仅HTTP、字典和Element UI壳使用stub。

- [x] 修复前已运行现有Node测试与vue-tsc并保存基线；新增回归RED捕捉旧L1列误用、新增市场价代填、改价缺字段与折扣试算。
- [x] API新增可空新字段；列表/改价/折扣試算只用新字段，0与null分别回显。不要旧字段/市场价兜底。
- [x] 展开选品保留 `sku.suggestedSalePrice`，选中后请求提示字段取该值；市场价显示保持。
- [x] DISCOUNT缺少正建议价时前端明确拒绝保存，后端仍权威；FIXED9500不受建议价缺失影响。
- [x] 供应价列用提示说明为当前商业条款，区别于已发布履约冻结价；不扩展非FIXED履约发布。
- [x] GREEN21/21、`node --test tests/*.test.mjs`186/186、改动文件ESLint与Prettier、test构建通过；vue-tsc修复前后均exit2/54项历史诊断。完整正文与续行比较，忽略移动行列及两类无序联合类型成员顺序后逐条一致；保留原始差异和归一化规则。前端独立规格与质量复审均批准。

## Task 4：规格与质量复审、集成交付

- [x] 独立规格复审逐条核对设计；独立质量复审检查真实SFC、契约、事务/错误映射，不以现有测试全绿覆盖语义问题。
- [x] 两阶段复审均批准，无待处理P1/P2/P3；重要注释补完整设计路径后核对。
- [x] 后端仅同步本任务7个文件到 `/Users/mac/codespace/supply-hub`，逐文件核对原基线防止覆盖其它修改；保留用户AGENTS、既有集成测试文档记录。
- [x] 扩大供应链回归232类/2246项全部通过，独立Failsafe27项通过；保存XML与计数，最终两仓 `git diff --check`。
- [x] `docs/集成测试.md`已记录RED/GREEN、MySQL与契约/架构结果、前端构建/类型基线、独立审查结果和配套发布人工验收步骤。未发布情况下不重放pro143写接口，不声称已修复线上。
