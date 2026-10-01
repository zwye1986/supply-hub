# 内部首次报价永久冻结实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: subagent-driven-development逐任务执行，规格通过后质量审查；禁止并发实施者。

**目标：** 沿用StandardPricingQuoteApi，按项目订单永久冻结首次报价，去除报价用户/支付时间绑定，提供HTTP受理内部查取入口。

**依据：** `docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md`全文。该设计承接用户已确认方案，不新增外部报价HTTP。

## Task 3：内部报价适配与051增量

在Task2查单/资产规格和质量均通过后开始。本阶段不宣称整个标准流程用户身份已删除；受理/资产生产/共享订单的清理是Task4。

### 文件职责

API `aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/`：

- `main/java/com/aifuli/module/supplychain/api/pricing/dto/StandardPricingQuoteReqDTO.java`删除buyerUserId，数量校验1；请求其余6字段保留。
- 同目录`StandardPricingQuoteRespDTO.java`永久冻结语义、expiresAt可空且明确历史兼容，价格/币种字段保留。
- `api/pricing/StandardPricingQuoteApi.java`及`StandardPricingQuoteContractTest`同步接口文档/删除身份/可空字段契约；新增STANDARD_PRICING_QUOTE_MISSING稳定缺报价错误与RETRY_SAME_KEY_AFTER_CONDITION_CHANGE映射，号码由实际占用清单选择，供Task4可条件重试拒绝使用。

Server `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/`：

- `tradecore/catalog/pricing/StandardPricingQuoteService.java`项目行锁＋永久查取、首次READY定价、重放完全冻结、不因过期/消费重签、真实事务内唯一消费。
- `tradecore/catalog/dal/mysql/StandardPricingQuoteMapper.java`Lambda项目键查询与ForUpdate，不再使用source/tenant最新一条；保留token索引精确查询供过渡核对。
- `tradecore/catalog/dal/dataobject/StandardPricingQuoteDO.java`删除buyerUserId、expiresAt注释对齐DDL。
- `tradecore/catalog/pricing/QuoteConsumptionContext.java`删除buyerUserId/paidAt，保留token+buyer/project/sku/order/quantity过渡；新增无token的项目键消费上下文/方法。不要把旧字段以null/假手机号/假时间填充到新上下文。
- `border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`仅调整构造报价上下文的实参，用户/paidAt不再传给报价；该类其余受理/指纹/限购/资金逻辑在Task4统一调整，本阶段不擅自换受理状态机。
- 新`db/changelog/releases/1.0.0/051-standard-quote-permanent-key.sql`及主changelog带HALT前置检查（必须在任何破坏性DDL前），035不改。
- 对应`StandardPricingQuoteServiceTest`、`StandardPricingQuoteConcurrencyIntegrationTest`及新增`StandardPricingQuotePermanentKeyIntegrationTest`；`SupplyChainLiquibaseIntegrationTest`最新精确schema断言同步。
- 因删除报价DO/上下文字段导致其他测试fixture无法编译时，只调整对应报价fixture与合法新永久键，保留原业务断言；给最终报告列清所有额外测试文件，不删除测试规避编译。

### 实施步骤

- [ ] 行为RED：旧报价超过15分钟会重签、同来源不同项目同号串报价、强制用户/paidAt、已消费无法重放、Mapper无永久约束等。缺类/签名改动编译错误不是RED，先有可编译业务失败断言。
- [ ] 改API与DO删除报价身份，expiresAt可空；Service移除手机号normalize与15分钟常量/有效期。已有同键报价不调用当前定价，不改变任何价格/token/首次时刻。
- [ ] 新无token消费按项目永久键ForUpdate取得报价，缺失不重定价；验证buyer/project/SKU/quantity/引用。过渡token入口必须核对同一个唯一报价，不复用source租户旧查法。
- [ ] require/markConsumed生产MANDATORY供共享事务使用，markConsumed行锁同order幂等/不同order拒绝、消费不一致失败。消费回滚恢复ISSUED，原首次消费时间不被重放覆盖。
- [ ] 051先检查重复项目业务键、项目/Listing/SKU归属及消费引用/金额，不自动删除多报价/转移消费；失败HALT且旧列/旧数据未改。合法历史与空库升级，唯一项目键及用户列删除/注释符合设计。
- [ ] 真实并发首次报价只一条、同买方跨项目同号各一条、数据库等价号冲突、超过15分钟不重签、已消费报价重放、价格变动不影响原值、缺报价无计价调用，所有消费锁/回滚/无事务门禁。
- [ ] 增量升级失败场景用真实Liquibase停在050/或035合法基线后插入非法历史、继续051，核实HALT前数据不变；不要在全量升级成功后才假装测试precondition。

### 验证命令

Java21/Maven串行，API Contract单独指定，供应链外模块单测按原配置跳过，禁止-DskipTests：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
/opt/homebrew/bin/mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am \
-Dtest=StandardPricingQuoteContractTest -Dsurefire.failIfNoSpecifiedTests=false test

JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
/opt/homebrew/bin/mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
-Psupply-chain-integration \
-Dtest=StandardPricingQuoteServiceTest,SupplyChainModuleArchitectureTest \
-Dsurefire.failIfNoSpecifiedTests=false \
-Dit.test=StandardPricingQuoteConcurrencyIntegrationTest,StandardPricingQuotePermanentKeyIntegrationTest,FulfillmentAcceptanceIntegrationTest,RechargeFrequencyAcceptanceIntegrationTest,SupplyChainLiquibaseIntegrationTest \
-Dfailsafe.failIfNoSpecifiedTests=false verify
```

按实际需要补充既有受理整合测试以验证构造变动，不缩掉原并发消费和schema门禁；真正完整应用所需随机32B测试身份key只注入进程，不保存/打印。证据`/tmp/standard-internal-quote-freeze`命令、源commit、RED/GREEN新鲜XML、hash、DDL基线hash；报告每类真实计数，不把需要未来发布协调称为完成。

- [ ] 注释中文带设计链接；日志入口/结果含业务号和版本、不含token/密钥/手机号，外部接口不加字段。代码自审仅owned文件中文提交，root文档不要暂存。
- [ ] 独立规格→质量审查通过，原实施者修复复评；root维护计划和`docs/集成测试.md`，进入共享受理标准身份移除/项目永久键/手机限购阶段。

实际部署发现历史多报价的业务清理和外部调用方发布协调列低优先级待办；自动化必须证明安全阻断，不为方便HTTP联调取最新报价或跳过唯一绑定。
