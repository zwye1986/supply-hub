# 标准项目直接下单与首次价格冻结实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 当前 dev 分支开发，一名实施者，规格审查通过后质量审查。

**Goal:** 项目无需预先调用内部报价接口，真实签名下单自动形成或复用永久冻结价格。

**Architecture:** Facade 在 claim 后、最终 accept 前调用独立定价短事务。已有报价优先复用，消费与订单/资金仍原子提交；不改变共享受理的纯消费职责。

**Tech Stack:** Java21、Spring、MyBatis Plus、MySQL8.4、Liquibase、JUnit5、Failsafe 与真实 HTTP 挡板。

## Task 1：可信首冻与059迁移

文件：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/catalog/pricing/{StandardPricingQuoteService,GenericBatchPricingService,ProjectQuoteKeyContext}.java`、相关 `dal/dataobject/StandardPricingQuoteDO.java`、`dal/mysql/StandardPricingQuoteMapper.java` 和 `tradecore/buyer/dal/mysql/{ProjectListingMapper,BuyerProductMapper}.java`；`src/main/resources/db/changelog/releases/1.0.0/059-standard-project-direct-order-quote-source.sql` 与登记YAML。测试：原 StandardPricingQuoteServiceTest、永久键/并发 IntegrationTest、SupplyChainLiquibaseIntegrationTest 与新增可信首冻测试。

- [ ] 先写 RED：调用 `freezeForProject(new ProjectQuoteKeyContext(buyerId, projectId, skuId, orderNo, 1L))` 后仅一条来源NULL的ISSUED报价，费用快照准确；已有报价不调用重定价；错项目/SKU/数量、停用选品、非READY和系统异常明确拒绝。真实数据库覆盖旧RR快照和新旧入口竞争，059保留原来源。
- [ ] 执行 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server test -DskipTests=false '-Dtest=StandardPricingQuoteServiceTest'`，保留实际断言失败，不能以编译错误替代RED。
- [ ] 实现 `freezeForProject(ProjectQuoteKeyContext)` 的独立供应链短事务、项目/报价当前读、当前选品准入与共享价格计算。来源NULL、绑定冲突稳定错误、已知不可用专用异常、系统异常因果保留；旧RPC锁后读取也改为current read。059两列可空且COMMENT与DO一致。
- [ ] 运行新增单测与真实MySQL定向测试，确认既有RPC永久键/消费行为通过。

## Task 2：HTTP受理接线与生产闭环

文件：`src/main/java/com/aifuli/module/supplychain/application/facade/ProjectStandardOrderCommandFacade.java`、`border/fulfillment/application/{FulfillmentAcceptanceLifecycle,RechargeFrequencyAcceptanceCoordinator}.java`、其真实单测与 `src/test/java/com/aifuli/module/supplychain/interfaceapi/open/project/{ProjectStandardOrderHttpContractTest,StandardProjectHttpClosureIntegrationTest}.java`，受影响集成测试的真实 @Import/依赖装配。Facade 不依赖DAL。

- [ ] 删除主要HTTP闭环场景的预先 issueQuote 后观察 RED；测试首冻后受理失败无业务残留、已冻结原价CAS重试、普通回放和无效目标不首冻。确定性真实MySQL测试首冻后真实retire先提交则最终拒绝；accept准入锁已持有则真实retire等待提交；ProjectListing与BuyerProduct分别覆盖，不用sleep或直接SQL停用代替真实命令服务。
- [ ] 在 claim.executeAcceptance 后接线：仅合法数量、targetValid/Schema可解析时调用 `freezeForProject(new ProjectQuoteKeyContext(context.buyerId(), context.projectId(), targetSchema.platformSkuId(), request.getBuyerOrderNo(), request.getQuantity()))`；随后调用 acceptanceCoordinator.acceptDirectProjectOrder，经Lifecycle.acceptDirectProjectOrder与原RPC accept共用私有实现。最终requireForProject后MANDATORY锁quote→ProjectListing→BuyerProduct并检查当前准入；保留原锁前缀，不锁project/pricing。回放分支直接返回。
- [ ] 运行生产HTTP/HMAC/数据库闭环，至少覆盖卡券、直充、同参并发、跨项目、改价、新号、异常回滚及非法字段。保留至少一项预先RPC报价兼容场景。

## Task 3：最终门禁与双阶段复审

- [ ] 供应链全量 Surefire，API Contract，以及相关 Failsafe：报价永久键/并发/迁移、可信首冻、标准项目受理与HTTP闭环、频控受理/执行、Liquibase。使用JDK21，其他模块单测不无关扩跑。
- [ ] 原实施者自审并中文提交；独立规格审查后独立质量审查，所有重要问题由原实施者修复复审。
- [ ] 更新主卡券计划、原设计中的“必须先报价”正文与集成文档，记录实际命令、准确计数、日志和未执行的真实环境事项。
