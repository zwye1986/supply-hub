# 华昇 Task4～6 未提交变更评审修复计划

> 用户已授权修复评审问题；仅恢复已确认的生产闭环设计契约，不推进 Task7/8，不提交、推送、部署或调用真实供应商。当前 worktree 保留已有变更，串行执行。

规格：`docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md` §5.3、§6.3、§7.1、§8.2；日志遵守 `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md` §3.2、§5.3。

## 修复与验证顺序

- [x] P1 真实终结链：TAKE 从可信 checkpoint 合并金额与终结分类；回调传完整资产事实；无金额先 QUERY 并隔离资产。用 `HuashengSettlementIntegrationTest` 验证真实 Mapper、资产、资金台账。
- [x] P1 冲突：金额／资产异常同事务保存加密证据和唯一 case；终态回调锁后核实完整事实，不反转资金、不覆盖交付；单证据槽不同内容拒绝并回滚 Inbox。
- [x] P2 恢复：claim 持久计数和期限及列镜像；末次合法结果和到限后的可信回调仍可应用；连续宕机不得突破上限。补纯策略测试和真实 MySQL quota 测试。
- [x] P2 防重：`HuashengCallbackCanonicalTest` 先 RED，JSON encoder 完整包含 timestamp、金额原文／分值、全部 card 三态字段及版本；eventId 无歧义转义 supplierId/Appid/nonce。
- [x] P2 隐私：真实 parser/controller 异常 message 和日志不得包含解密原值；固定安全 HTTP 摘要，不附带受保护原因。补 REST 隐私回归。
- [x] P2 日志与来源：完成事实 afterCommit，回滚没有假成功；originTraceId 保留初始来源，Timer payload 传来源，taskId 不充当 traceId，MDC 退出恢复。补日志与来源回归。
- [x] 更新 `docs/集成测试.md` §7.90.9/新增修复记录，纠正过早完成结论和防重算法／计数。
- [x] 新鲜运行供应链全量纯测/架构、适用集成/契约/Liquibase、共享 common/web/gateway 回归及 diff 检查，记录命令、实际计数、未实机边界。

每片先新增失败断言并确认 RED，再最小实现 GREEN；已有修复和 RED 日志核实后保留，不回退其它未提交工作。完成结论只覆盖真实执行过的门禁，不用局部 GREEN 声称生产闭环。

## 验证执行与真实证据（2026-10-10）

### 1. 供应链单测、策略与架构测试（Surefire 3.5.3）
- 命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Dmaven-surefire-plugin.version=3.5.3 -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false "-Dtest=*Huasheng*Test,!*IntegrationTest,!*ContractTest,SupplyChainModuleArchitectureTest" test`
- 结果：**Tests run: 264, Failures: 0, Errors: 0, Skipped: 0**（含 `SupplyChainModuleArchitectureTest` 31项架构门禁）。

### 2. 华昇出入站契约测试（Failsafe 3.5.3）
- 命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dmaven-surefire-plugin.version=3.5.3 -DskipTests=false -Dit.test="*Huasheng*ContractTest" -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=none test-compile org.apache.maven.plugins:maven-failsafe-plugin:3.5.3:integration-test org.apache.maven.plugins:maven-failsafe-plugin:3.5.3:verify`
- 结果：**Tests run: 80, Failures: 0, Errors: 0, Skipped: 0**（覆盖 `HuashengCallbackRestContractTest`、`HuashengOperationExecutorContractTest`、`HuashengCommunicationFacadeContractTest`、`HuashengTransportCauseContractTest`、`HuashengHttpContractTest`）。

### 3. 华昇端到端集成测试（Failsafe 3.5.3 + MySQL 8.4 Testcontainers + Liquibase 64 changeset）
- 命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration -Dmaven-surefire-plugin.version=3.5.3 -DskipTests=false -Dit.test="*Huasheng*IntegrationTest" -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=none test-compile org.apache.maven.plugins:maven-failsafe-plugin:3.5.3:integration-test org.apache.maven.plugins:maven-failsafe-plugin:3.5.3:verify`
- 结果：**Tests run: 69, Failures: 0, Errors: 0, Skipped: 0**（全部8个集成测试类一次性全量通过：`HuashengContinuationIntegrationTest`、`HuashengOperationPersistenceIntegrationTest`、`HuashengRecoveryIntegrationTest`、`HuashengRestartIntegrationTest`、`HuashengFrozenConfigurationIntegrationTest`、`HuashengAssetPersistenceIntegrationTest`、`HuashengSettlementIntegrationTest`、`HuashengCallbackIntegrationTest`）。

### 4. 共享框架与网关回归测试（Surefire 3.5.3）
- `aifuli-common`：`mvn -o -pl aifuli-framework/aifuli-common -am test` -> **Tests run: 21, Failures: 0, Errors: 0, Skipped: 0**
- `aifuli-spring-boot-starter-web`：`mvn -o -pl aifuli-framework/aifuli-spring-boot-starter-web -am test` -> **Tests run: 12, Failures: 0, Errors: 0, Skipped: 0**
- `aifuli-gateway`：`mvn -o -pl aifuli-gateway -am test` -> **Tests run: 30, Failures: 0, Errors: 0, Skipped: 0**

### 5. 格式与空白检查
- 命令：`git diff --check`
- 结果：无输出（0 whitespace / trailing error）。

### 6. 生产未实机边界声明
- 未启动真实生产或测试多进程实例；
- 未调用真实外部华昇供应商端点；
- 未提交、推送或部署代码，保留全部未提交工作树。
