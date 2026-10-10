# 自动目录评审六项修复实施计划

> **For agentic workers:** 使用 executing-plans 在本会话顺序执行；用户要求不启动子代理，不提交、推送或部署。

**Goal:** 修复最终 AUTO 定价准入、项目首冻准备、版本引用、MANUAL 有效期、前端契约及受理回归装配六项缺口。

**Architecture:** 维持原两段事务与受理锁前缀，最终 BPD 锁后追加 BuyerPricing 当前锁并复用生命周期完整性检查；首冻准备使用真实项目资格及商业版本，回执引用只取目录版本。前端直接消费后端 RefreshResult。

**Tech Stack:** JDK21、Maven 离线、Spring/MyBatis/MySQL Testcontainers、Vue3/TypeScript、Node 内置测试。

## 执行边界

JAVA/TEST 为 supply-chain-server 下 com/aifuli/module/supplychain；前端为 `/Users/mac/Documents/codeSpace/supplychainhubweb`。所有 Java 命令使用 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home PATH=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH`。

## 修复顺序与检查点

- [x] 准备红灯：扩展 `TEST/tradecore/catalog/application/AcceptanceCatalogPreparationServiceTest.java`，真实项目 UNKNOWN 不被汇总放行、DRAFT 拒绝版本 NULL、PUBLISHED 拒绝版本取 current、MANUAL 未来/过期商业版拒绝。运行 `mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dtest=AcceptanceCatalogPreparationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，确认断言红灯，再修改 `JAVA/tradecore/catalog/application/AcceptanceCatalogPreparationService.java`，同命令绿灯。
- [x] 最终红灯：扩展生命周期/直接受理测试，非 READY/来源变更拒绝、坏来源/摘要系统失败、MANUAL/RPC 不走 AUTO 守卫；验证当前锁读。修改 `JAVA/tradecore/catalog/application/BuyerSelectionCatalogLifecycle.java` 与 `JAVA/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`，运行 `-Dtest=BuyerSelectionCatalogLifecycleTest,FulfillmentAcceptanceDirectProjectOrderAdmissionTest,SupplyChainModuleArchitectureTest` 取得红绿证据。
- [x] 前端红灯：在实际前端新增 Node 组件回归测试，用 Vue 编译器编译真实 script setup，通过 stub API/message 执行重算，覆盖后端 reason 字段、暂停/退役、成功与错误。命令 `node --test tests/catalog-recalculate.test.mjs`；修复 `src/api/supplier/distribution/types.ts` 与分销详情 `ProductList.vue`，同命令绿灯；运行定向 ESLint/Prettier、类型检查与 dev 构建。（9项通过；全局类型/构建仍阻断，见联调证据，不宣称构建通过。）
- [x] 受理装配：复用评审 `catalog-review-acceptance-integration.log` 五项装配失败为红灯，逐项修复实际被调用的依赖与 fixture；加入真实首冻及 AUTO 定价并发回归。使用 `-Psupply-chain-integration -Dtest=NoSuchTestClass -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ProjectStandardAcceptanceIntegrationTest,FulfillmentAcceptanceIntegrationTest,StandardProjectHttpClosureIntegrationTest,BuyerSelectionCatalogLifecycleIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false verify`，禁止外部 JDBC 覆盖。（全量门禁 `catalog-fix-final-gate.log` 内四套装配全绿：ProjectStandard 5、Fulfillment 4、BuyerSelectionCatalogLifecycle 8、HTTP 闭环 30；HTTP 闭环唯一失败为首冻后价格变更用例的测试数据构造，修复后定向复跑 30/30 通过，见 §8.13.7。）
- [x] 完成门禁：运行供应链 API/server 全量纯测/契约/架构及受影响集成（其它模块单测不运行），核验 XML 计数、失败与跳过；更新 `docs/集成测试.md` §8.13.7 和原 Task 6，不把定向测试说成全仓/远端通过。两仓 `git diff --check`，仅删除本轮测试新生成的日志文件。（`catalog-fix-final-gate-2.log` BUILD SUCCESS：API 123、server Surefire 1987、Failsafe 43 套件 427 项，零失败/错误/跳过；XML 计数已核验，证据见 §8.13.7。）
