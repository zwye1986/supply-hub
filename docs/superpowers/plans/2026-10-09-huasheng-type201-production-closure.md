# 华昇 type201 单实例生产闭环实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本期优先当前会话串行执行；没有用户选择，不自行派生代理。

**Goal:** 接通第一张华昇 FIXED、quantity=1、type201 的生产履约、原单恢复、卡密查询与资金闭环。

**Architecture:** A1～A5通信保持独立纯对象，由显式配置建Bean；新增华昇专用runner/lifecycle/executor，复用现有Operation、PersistentTimer、聚合锁和资金终结。外呼事务外执行，持久认领代次/watchdog/checkpoint保障原单恢复；不建设通用工作流或分布式token平台，不考虑QNM接线或专项回归。

**Tech Stack:** Java21、Spring Boot、MyBatis Plus、MySQL8.4/Liquibase、现有OkHttp与HTTPS测试挡板、JUnit5/ArchUnit；管理端Vue3/TypeScript/pnpm。

---

## 0. 执行纪律与文件定位

权威规格：`docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md`。用户已同意最小方案及单实例，并追加“先不考虑QNM”；本文不是Git提交、推送、部署或真实供应商调用授权。每片按RED→最小实现→GREEN，记录真实输出，不一次改完再补测试。需要更改金额／重发／卡型／多实例业务语义先停下确认。

文件路径以下使用固定前缀以避免重复；拼接后的路径就是实际仓库相对路径，不是模糊搜索：

```text
S = aifuli-module-supply-chain/aifuli-module-supply-chain-server
A = aifuli-module-supply-chain/aifuli-module-supply-chain-api
J = S/src/main/java/com/aifuli/module/supplychain
T = S/src/test/java/com/aifuli/module/supplychain
R = S/src/main/resources
AJ = A/src/main/java/com/aifuli/module/supplychain/api
F = /Users/mac/Documents/codeSpace/supplychainhubweb
```

**文件职责地图（顺序锁定）：**

1. `J/border/fulfillment/application/huashengflow/HuashengCheckpointCodec.java`：V1封闭编码、AAD与身份校验；配套纯值`HuashengCheckpoint.java`。
2. 同目录`HuashengExecutionPermit.java`、`HuashengFlowTrigger.java`、`HuashengStepFact.java`、`HuashengFlowDecision.java`、`HuashengFlowPolicy.java`、`HuashengFlowCodes.java`：不可变资格／事实／决策／本地策略常量，不拥有存储或外呼。
3. 同目录`HuashengType201AssetCodec.java`：协议三态→加密标准payload与指纹canonical输入；不访问DAL。
4. 同目录`HuashengCallContextFactory.java`／`HuashengOperationExecutor.java`：冻结Ticket→A5输入、单次端点分派与安全事实，不写库；`J/infrastructure/security/FrozenCredentialSetResolver.java`只提供冻结身份核实后的指定凭据材料，不建历史密钥仓库。
5. 同目录`HuashengFulfillmentLifecycle.java`：短事务认领、watchdog、唯一下一步、事实校验、委派终结；`HuashengFulfillmentRunner.java`：事务外编排／进程锁。
6. 同目录`HuashengExecuteTimerHandler.java`／`HuashengRecoveryTimerHandler.java`／`HuashengLeaseWatchdogHandler.java`／`HuashengPreparationWatchdogHandler.java`：不同taskType的薄入口，调用lifecycle/runner，不直写领域表。
7. 同目录`HuashengCallbackApplicationService.java`／`HuashengCallbackInbox.java`：冻结身份认证、持久nonce去重、同一事实收敛；`J/supplieradapter/huasheng/protocol/HuashengCallbackParser.java`：纯协议校验。
8. `J/infrastructure/configuration/HuashengFulfillmentConfiguration.java`：显式组装通信、缓存及新模块；启用默认false，不污染A5零Spring对象。
9. `J/supplieradapter/metadata/HuashengOrderV1Metadata.java`及三个Schema：封闭能力／凭据／默认画像／发布限制。
10. 现有AcceptanceLifecycle／ExecuteHandler／TicketFactory／SupplierOrderLifecycle只加必需接线与终结guard；现有锁序、资金服务和Timer引擎不重构。
11. API资产DTO、项目decoder、管理OrderViewQueryService和实际前端订单映射：原始validTime保真查询，不扩通知／售后。

所有新增重要类／方法中文注释链接规格相应章节。DO字段注释与规格DDL COMMENT逐字对齐。A1～A5现有文件不重写；既有QNM实现不删除、不扩展。

## 1. 验证命令与证据规则

执行前在当前shell设置实际前缀（不是修改仓库）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export S=aifuli-module-supply-chain/aifuli-module-supply-chain-server
export A=aifuli-module-supply-chain/aifuli-module-supply-chain-api
```

先`mvn -pl "$S" -am compile -DskipTests`只构建前置产物，不作为测试通过；compile不把依赖安装到本地Maven仓库。以下单模块命令要求本地依赖已经可解析；否则采用本节末尾的reactor命令，只选择供应链类而不运行其它模块无关单测，不以全局skipTests完成门禁。

Task1定向纯测：`mvn -pl "$S" test -DskipTests=false -Dtest=HuashengCheckpointCodecTest`。其余片使用各Task的Test清单中具体类名替换，不能选中0个用例。契约／真实MySQL：

```bash
mvn -pl "$S" test-compile failsafe:integration-test failsafe:verify \
  -Psupply-chain-integration -DskipTests=false -DskipITs=false \
  '-Dit.test=HuashengOperationPersistenceIntegrationTest'
```

其它IT以对应具体类名替换`-Dit.test`，每类独占Testcontainers空库；若指定外部MySQL，按`docs/集成测试.md`§1.1提供独占空库。不得多个测试类共享固定业务编码库，不连接test/prod业务库。缺数据库/Docker则记BLOCKED，不以JUnit跳过当通过。

最终供应链默认门禁：分别`mvn -pl "$A" test -DskipTests=false`与`mvn -pl "$S" test -DskipTests=false`。所有本期契约由Failsafe profile跑，不误以Surefire默认排除的ContractTest已经运行。Expected GREEN为BUILD SUCCESS且报告中选中用例数>0、failure/error/skipped=0；RED须为目标行为／缺目标实现失败，而非依赖、TLS或数据库环境失败。

每片记录受测HEAD+工作区diff、命令、退出码、Surefire/Failsafe XML、RED/GREEN与未执行范围。各片保存检查点后请求审阅；Git提交仅获授权后逐片进行，中文标题，不自动暂存整个仓库或跨前端仓提交。

无需本地安装依赖的最终reactor命令（源依赖同步构建）：

```bash
mvn -pl "$S" -am test -DskipTests=false \
  '-Dtest=%regex[com/aifuli/module/supplychain/.*Test.class],!%regex[.*IntegrationTest.class],!%regex[.*ContractTest.class]' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl "$S" -am test-compile failsafe:integration-test failsafe:verify \
  -Psupply-chain-integration -DskipTests=false -DskipITs=false \
  '-Dit.test=HuashengOperationPersistenceIntegrationTest,HuashengAssetPersistenceIntegrationTest,HuashengFrozenConfigurationIntegrationTest,HuashengOperationExecutorContractTest,HuashengContinuationIntegrationTest,HuashengSettlementIntegrationTest,HuashengRecoveryIntegrationTest,HuashengRestartIntegrationTest,HuashengCallbackIntegrationTest,HuashengCallbackRestContractTest,HuashengAssetQueryContractTest,HuashengProductionClosureIntegrationTest,SupplyChainLiquibaseIntegrationTest' \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

failIfNoSpecifiedTests=false只允许依赖模块没有供应链类；必须单独确认API/server报告真实包含目标类，选中类零用例不是GREEN。每片RED/GREEN按上面命令替换为该片具体类名，其余参数不变。

## 2. Task 1：Operation持久字段与checkpoint基础

**Files**
- Create: `R/db/changelog/releases/1.0.0/062-huasheng-operation-recovery.sql`。
- Modify: `R/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`、`J/border/fulfillment/dal/dataobject/FulfillmentOperationDO.java`、`J/infrastructure/inbox/InboxEventDO.java`（摘要字段注释）、`J/border/fulfillment/domain/AdapterStepCode.java`、`T/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`（062后的模式计数）。
- Create: `J/border/fulfillment/application/huashengflow/HuashengCheckpoint.java`、`HuashengCheckpointCodec.java`、`HuashengFlowCodes.java`。
- Test: `T/border/fulfillment/application/huashengflow/HuashengCheckpointCodecTest.java`、`HuashengOperationPersistenceIntegrationTest.java`。

- [x] 重新核YAML末尾与Git状态；062未占用，保留既有7份文档修改，沿用当前隔离worktree。
- [x] 先写真实迁移IT：空库全量Liquibase、重复启动不重复DDL、checkpoint_key_version为VARCHAR(64)/String、列COMMENT、默认generation/count=0及CHECK负数拒绝；确认不能在旧DDL上找到这些列。恢复索引复用既有一份，Inbox摘要COMMENT扩展为SHA-256族且不改列长度。
- [x] 定向Failsafe运行`HuashengOperationPersistenceIntegrationTest`，记录缺列的RED（1项失败，0错误/跳过）。
- [x] 追加规格§4.1原样DDL与YAML登记，DO增加对应字段，不修改历史001～061；Liquibase快照计数同步64变更集/303个DATETIME列。

```yaml
  - changeSet:
      id: 062-huasheng-operation-recovery
      author: supply-chain
      changes:
        - sqlFile:
            path: 062-huasheng-operation-recovery.sql
            relativeToChangelogFile: true
            encoding: UTF-8
            splitStatements: true
            stripComments: false
```

- [x] 写checkpoint测试：同一AAD往返、换operation/step/版本/sequence失败、未知schema、缺key、configDigest漂移、原始金额不改、数组防御复制及安全toString；使用真实AesGcmEnvelopeCrypto，不用恒等“加密”。
- [x] 运行`HuashengCheckpointCodecTest`，因目标类缺失RED后实施规格§4.2/§4.2.1封闭codec；schema=1、数字十进制、step枚举，AAD构造如下（输入全部由锁后实体供给）：

```java
String aadText = String.join("|", "HUA_SHENG_CHECKPOINT", "1",
    Long.toString(bOrderId), operationNo, Integer.toString(sequenceNo), step.name(),
    Long.toString(connectorVersionId), Long.toString(offerVersionId));
byte[] aad = aadText.getBytes(StandardCharsets.UTF_8);
```

- [x] GREEN：最终全量供应链API123/server1911项（checkpoint17项）全绿；真实MySQL新IT1项/Liquibase3项全绿，包含Mapper读取；不测试账户副作用，不注册metadata。详见`docs/集成测试.md`§7.90.4。

执行补正：自审新增billno与bOrderNo不同负例，先RED再删除错误相等约束；冻结outOrderSn取billno，不改业务单号生成。全量门禁发现业务侧引用A内部结果载体，保留原架构规则，以规格§4.2.1本地金额不变量检查及对照测试替代，不改A1～A5。实际命令/日志/最终计数持续登记到`docs/集成测试.md`§7.90.4。

## 3. Task 2：type201资产无损桥接与指纹

**Files**
- Create: `J/border/fulfillment/application/huashengflow/HuashengType201AssetCodec.java`。
- Modify: `J/border/fulfillment/domain/OperationAssetFact.java`、`J/border/fulfillment/application/SupplierOrderLifecycle.java`（仅persistAssets封闭payload支持）。
- Test: `T/border/fulfillment/application/huashengflow/HuashengType201AssetCodecTest.java`、`HuashengAssetPersistenceIntegrationTest.java`。

- [x] 使用A parser产生卡片，不反射构造协议DTO；写四种validTime（ABSENT/null/空串/中文）及首尾空白的测试，code/pass逐字不变。
- [x] RED运行`HuashengType201AssetCodecTest`；新增封闭payload，按规格§7逐字段处理三态，id只写protectedPayload，externalAssetId为null。核心编码必须保留null而非Optional丢弃：

```java
if (field instanceof HuashengField.Null<?>) node.putNull(name);
else if (field instanceof HuashengField.Value<?> value) {
    if (value.value() instanceof String text) node.put(name, text);
    else if (value.value() instanceof Long number) node.put(name, number);
    else throw new IllegalArgumentException("不支持的华昇资产字段类型");
}
```

此片codec内部局部变量field/name/node来自对应协议字段与固定白名单；不能接收调用者任意Map。type固定201；其它六型/新增非空载荷拒绝。

- [x] OperationAssetFact增可选protectedPayload、防御复制和安全toString；华昇专用工厂跳过trim，旧构造入口不统一改造。持久层验证标准字段与supplierPayload相等后全量加密；两套身份不得不同。
- [x] 写IT并先RED：真实encrypt→insert→decrypt逐字一致；跨AAD搬移失败；重复相同整套幂等；跨单同凭证指纹冲突；相同卡片id但不同卡密不错误冲突；冒号分隔碰撞负例。指纹固定JSON顺序supplierId/type/code/pass，使用HmacFingerprintPort，不裸SHA哈希卡密。
- [x] 定向GREEN纯测和`HuashengAssetPersistenceIntegrationTest`；assert数据库没有明文卡密，validFrom/Until都NULL，不提交资金。

Task2最终本地证据：`huasheng-task2-regression-final-evidence.log`退出0/BUILD SUCCESS，Surefire API123＋server1921（含codec10），Failsafe资产8＋Operation1＋Liquibase3，均零失败/错误/跳过。保持原架构规则、历史DDL、A1～A5、QNM源码和资金算法不变；自审builder脱敏另有RED→GREEN，事务成功日志只afterCommit、回滚/幂等无新增成功日志，临时加密/HMAC输入擦除已测。真实命令/XML与范围见`docs/集成测试.md`§7.90.5；资产基础不等同生产资金/查询闭环，Task3～8待实施，本轮未自动提交/推送/部署或真实外呼。

## 4. Task 3：冻结配置投影、metadata与生产executor

**Files**
- Create: `J/supplieradapter/metadata/HuashengOrderV1Metadata.java`、`R/supply-chain/adapter-schemas/huasheng-order-v1-connector.schema.json`、`huasheng-order-v1-offer.schema.json`、`huasheng-order-v1-extra.schema.json`。
- Modify: `J/supplieradapter/metadata/PublishedConnectorConfigurationResolver.java`、`J/tradecore/supply/application/ConnectorValidationService.java`、`J/tradecore/supply/application/ConnectorCredentialBindingValidator.java`、`J/tradecore/supply/application/SupplierOfferPublicationService.java`、`J/border/fulfillment/domain/OperationTicket.java`、`J/border/fulfillment/domain/OperationOutcome.java`。
- Create: `J/border/fulfillment/domain/FulfillmentProtocolFact.java`、`J/border/fulfillment/domain/FrozenCredentialItemIdentity.java`、`J/infrastructure/security/FrozenCredentialSetResolver.java`；新flow目录的`HuashengStepFact.java`、`HuashengCallContextFactory.java`、`HuashengOperationExecutor.java`。
- Test: `T/supplieradapter/metadata/HuashengOrderV1MetadataTest.java`、`T/border/fulfillment/application/huashengflow/HuashengFrozenConfigurationIntegrationTest.java`、`HuashengOperationExecutorContractTest.java`。

- [ ] 写metadata纯测：凭据模式/恰三项/能力与画像一致；未知JSON字段、第二appId、非法expireTime拒绝；外部SUCCESS不得terminal。RED运行metadata测试。
- [ ] connector与offer空对象schema采用`additionalProperties:false`；extra仅expireTime integer/outMobile string，不向有限Schema加不支持关键字。实施规格§3.2显式范围与模板安全检查。
- [ ] 写冻结投影IT：真实createDraft/validate/publish，resolver返回冻结snapshot，修改当前项身份失败关闭、不被读取代替；出站只两项，callbackPublicKey不进context；merchantAccount为唯一appId。先RED再投影不可变集合。新增FrozenCredentialSetResolver按规格§3.3在独立短事务锁集/项、核身份后调用已有provider，仅解析requestedKeys；测试verify/解析之间竞争改版本不能把新材料配旧身份。材料finally擦除；不修改旧resolveSet行为，不承诺旧集原地变更后仍可执行。
- [ ] 定义纯领域标记接口与事实carrier，Outcome添加可选protocolFact，保留已有builder。事实不含资金动作；Ticket添加冻结身份/generation，拒绝明文toString和意外序列化。

```java
public interface FulfillmentProtocolFact {}
// HuashengStepFact实现该接口；字段仅用端点/订单/金额/状态纯值、
// byte[]封闭资产载荷与安全观察，全部防御复制，不携带HuashengCallResult。
```

- [ ] 写executor ContractTest并先RED：每次execute只一个PLACE/CONFIRM/TAKE/QUERY facade业务调用；Parsed转纯事实；BusinessError/HttpFailure/TransportFailure/InvalidResponse/LocalFailure与安全HuashengProtocolException皆不伪造REJECTED；TAKE没有自生金额。复用HuashengCommunicationFacade而非手写HTTP/token，保留其完整安全attempts。
- [ ] GREEN对应纯测与两类Failsafe。此片尚不启用生产可售Bean，未创建真实订单。

## 5. Task 4：短事务资格、下一步与资金guard

**Files**
- Create: 新flow目录`HuashengExecutionPermit.java`、`HuashengFlowTrigger.java`、`HuashengFlowDecision.java`、`HuashengFlowPolicy.java`、`HuashengFulfillmentLifecycle.java`。
- Modify: `J/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`、`J/border/fulfillment/application/FulfillmentOperationTicketFactory.java`、`J/border/fulfillment/application/SupplierOrderLifecycle.java`、`J/border/fulfillment/domain/FulfillmentTimerTaskTypes.java`。
- Test: `T/border/fulfillment/application/huashengflow/HuashengFlowPolicyTest.java`、`HuashengContinuationIntegrationTest.java`、`HuashengSettlementIntegrationTest.java`。

- [ ] 先写纯决策表测试：WAIT→CONFIRM；SUCCESS→TAKE而非订单成功；FAILED合法退款→释放候选；CANCEL缺退款→保留；QUERY7014→UNKNOWN；SUCCESS/isTake=Y→TAKE；金额不符/身份冲突/六型→人工。决策枚举NEXT_CONFIRM/NEXT_TAKE/WAIT_QUERY/FINAL_SUCCESS/FINAL_REJECTION/MANUAL，不允许返回“重下PLACE”。
- [ ] RED后实现纯policy与常量，金额按A解析值核身份、连续原元数值相等、分值等于冻结成本；不double、不容差、不兜底。
- [ ] 写真实MySQL IT：Request→Order→BOrder→Operation锁后认领generation递增；旧permit/租约过期/非活动尝试结果无副作用；两线程同事实只有sequence+1一个下一步。先RED。
- [ ] 实施规格§5的claim/apply事务与permit；全部FOR UPDATE重读，不用锁前DO更新。认领提交前创建watchdog/marker；HTTP始终在事务外，lifecycle不持有facade。
- [ ] 首受理根据冻结adapter显式PLACE_ORDER，准备checkpoint并注册准备watchdog；TicketFactory解密checkpoint，不能继续直传ciphertext。单号/notifyUrl/参数摘要此后不变。
- [ ] 锁后显式核checkpoint.placeParameters.outOrderSn等于BOrder冻结billno（两者均不可用bOrderNo替代），原单orderSn/配置版本/摘要分别核对；codec不自行从密文定位原单。
- [ ] 华昇TicketFactory专用入口接收进程锁内、claim事务前通过FrozenCredentialSetResolver核实的材料及permit，不能在持有聚合锁时再锁凭据集；回调同样先准备认证材料。测试当前下一步仍PENDING/marker为空但首PLACE已发送时，可信回调可收敛原单。
- [ ] 下一步的旧完成/新行/checkpoint/Timer一起提交；注入Timer登记失败，断言全回滚、资金未提交；不能依赖旧Outbox再次消费才能补下一步。

```java
// 下一步调度输入仅稳定身份；在同一supplyChainTransactionManager事务内。
ObjectNode payload = objectMapper.createObjectNode();
payload.put("operationNo", nextOperationNo);
payload.put("trigger", HuashengFlowTrigger.EXECUTE.name());
timer.scheduleOnce(HuashengFlowCodes.EXECUTE_TASK,
    "HS:EXEC:" + nextOperationNo, payload, databaseClock.toInstant(now));
```

- [ ] 资金IT先RED再新增SupplierOrderLifecycle受控permit入口：完整type201且可信金额匹配才commitByReference；可信失败无资产才releaseByReference；无permit通用入口不能推进华昇。guard不反向依赖lifecycle，避免Bean循环。最终资产/状态/资金同事务，CAS失败回滚。
- [ ] GREEN三类纯测/IT；观察commit/release真实流水和Reservation状态，不只mock调用次数。

## 6. Task 5：生产Runner、Timer原单恢复与重启

**Files**
- Create: 新flow目录`HuashengFulfillmentRunner.java`、`HuashengExecuteTimerHandler.java`、`HuashengRecoveryTimerHandler.java`、`HuashengLeaseWatchdogHandler.java`、`HuashengPreparationWatchdogHandler.java`。
- Create: `J/infrastructure/configuration/HuashengFulfillmentConfiguration.java`。
- Modify: `J/border/fulfillment/application/FulfillmentOperationExecuteHandler.java`（华昇分派必须在旧EXECUTING/UNKNOWN提前跳过与普通claim之前）。
- Test: `T/border/fulfillment/application/huashengflow/HuashengRunnerTest.java`、`HuashengRecoveryIntegrationTest.java`、`HuashengRestartIntegrationTest.java`。

- [ ] 写Runner单测并RED：互斥锁在claim前尝试，覆盖claim/HTTP/apply，忙不占资格；finally异常/中断释放；同一进程不同冻结版本不同时外呼；MDC保存/新traceId和executionId/退出恢复；不能用taskId冒充traceId。该唯一锁由显式配置注入runner和callback，不各自new一把锁。
- [ ] 显式配置Bean连接A4传输、A5缓存/TokenManager/facade与专用executor；启用属性`aifuli.supply-chain.huasheng-fulfillment.enabled=false`；metadata、executor、runner和必要Handlers同条件。纯通信类仍无@Component。测试property=true真实装配，禁mock executor。
- [ ] 先写恢复IT：PLACE丢响应→QUERY原outOrderSn；CONFIRM丢响应→QUERY原orderSn；TAKE丢响应→QUERY SUCCESS/Y后同orderSn取同卡；7014一直不PLACE、不释放；WAIT才受控CONFIRM；marker前回滚允许首次执行。RED后实现runner与不同taskType恢复触发。
- [ ] 规格§5.3默认限额写命名常量并累计checkpoint：120s调用/150s资格/30s间隔/30min截止/20QUERY/3CONFIRM/5TAKE，A5 61物理重试独立计数。到限MANUAL与唯一case、资金保持。
- [ ] 写真实停启IT，分别在认领后未发、响应后结果落库前、下一步已创建前后、QUERY认领后宕机；关闭Spring上下文、保留同隔离MySQL、重新建上下文和空token缓存。不能仅重建codec称为重启。
- [ ] 断言持久watchdog能驱动新代次QUERY，旧Task/permit迟到跳过；准备Timer在claim前死信也被准备watchdog收尾，Task成功返回不吞恢复需求。模拟持锁调用超过lease时，apply只观察，watchdog锁忙不得返回成功，退出锁后仍有重试驱动QUERY；不得用Future超时释放锁而让原外呼后台继续发送。
- [ ] GREEN Runner纯测及两IT；保存挡板按原单号去重的外部计数，与本地资金流水同时核对。

## 7. Task 6：冻结版本回调与持久nonce防重

**Files**
- Create: `J/supplieradapter/huasheng/protocol/HuashengCallbackParser.java`、新flow目录`HuashengCallbackApplicationService.java`、`HuashengCallbackInbox.java`、`J/interfaceapi/callback/HuashengCallbackController.java`。
- Modify: `J/infrastructure/security/SupplyChainSecurityConfiguration.java`、`J/infrastructure/inbox/InboxEventDO.java`；必要`J/infrastructure/inbox/InboxEventMapper.java`（华昇窄接口，不改变通用InboxService）。
- Modify: `aifuli-framework/aifuli-common/src/main/java/com/aifuli/framework/common/util/http/ApiAccessLogPathUtils.java`及对应路径测试。
- Modify Test: `aifuli-framework/aifuli-common/src/test/java/com/aifuli/framework/common/util/http/ApiAccessLogPathUtilsTest.java`、`aifuli-framework/aifuli-spring-boot-starter-web/src/test/java/com/aifuli/framework/web/core/handler/GlobalExceptionHandlerStandardOrderTest.java`、`aifuli-gateway/src/test/java/com/aifuli/gateway/filter/logging/SupplierCredentialAccessLogTest.java`。
- Create Test: `T/border/fulfillment/application/huashengflow/HuashengCallbackPrivacyTest.java`（实际Servlet访问日志过滤器及拦截器保护，不只测路径谓词）。
- Test: `T/supplieradapter/huasheng/protocol/HuashengCallbackParserTest.java`、`T/border/fulfillment/application/huashengflow/HuashengCallbackIntegrationTest.java`、`HuashengCallbackRestContractTest.java`。
- Modify Test: `T/architecture/SupplyChainModuleArchitectureTest.java`；直接受影响Servlet/Gateway保护测试沿现有测试类添加。

- [ ] Parser RED：有界读取2MiB原hex密文、超限在验签/AES前拒绝；Appid/X-Sign长度限制、原密文验签后解密；Appid错/签名错/重复key/尾随/时间/nonce/数量/六型/FAILED非空list拒绝；成功卡片使用A已有验证规则而非宽松JSON。
- [ ] 实施规格§8.1冻结版本定位；不取最新凭据、不遍历公钥；临时秘密和解密数组finally擦除。
- [ ] Inbox IT先RED：同nonce同内容只一次；同nonce异内容或改投版本409；已提交同内容过时间窗200；未处理旧内容400；事务回滚后可重投；不同nonce同资产幂等；同步响应与回调竞态不重扣；CONFIRM认领后至结果落库期间回调不能抢先释放，认证后等进程锁超3秒返回503且没有Inbox/领域写入。
- [ ] 按进程执行锁→Inbox→聚合→资金锁序实现华昇窄接口，eventId与payloadDigest按规格§8.2生成，不能直接调用不校验payloadDigest的通用tryStart就宣称防重完整；迁移中的Inbox摘要COMMENT和DO注释一致，不改其它consumer算法。
- [ ] HTTP契约RED后薄Controller：400/404/409/500/503不套业务200；只有提交完成或已成功幂等返回`ResponseEntity.ok("success")`。callback成功/失败事实使用lifecycle统一规则，不伪造同步permit。
- [ ] 只开放精确回调根/版本子路径，登录豁免不等于供应商认证豁免。新增独立callback敏感路径判断，复用上下文路径段匹配；相邻`huasheng-other`不受影响。
- [ ] GREEN parser/IT/RestContract及shared common/web/gateway直接保护回归；架构新增Controller不依赖DAL/外呼主体，协议parser无Spring/MyBatis。公网网关路由保持实机待验，不擅自部署。

直接受影响共享模块定向回归（不运行其它模块无关单测）：

```bash
mvn -pl aifuli-framework/aifuli-common,aifuli-framework/aifuli-spring-boot-starter-web,aifuli-gateway -am test \
  -DskipTests=false \
  '-Dtest=ApiAccessLogPathUtilsTest,GlobalExceptionHandlerStandardOrderTest,SupplierCredentialAccessLogTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl "$S" -am test -DskipTests=false \
  '-Dtest=HuashengCallbackPrivacyTest,HuashengApplicationArchitectureTest,SupplyChainModuleArchitectureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

共享类报告逐份检查，error/failure/skipped=0且用例数>0才GREEN。隐私测试明确断言正文/参数/响应与异常日志中不存在夹具token、X-Sign、hex密文、code/pass；相邻路径负控制仍按既有规则记录安全内容。

## 8. Task 7：项目／管理端资产查询及真实前端有效期

**Files**
- Modify: `AJ/fulfillment/dto/ProjectStandardOrderAssetRespDTO.java`；`J/border/fulfillment/application/ProjectStandardAssetPayloadDecoder.java`；`J/border/order/application/OrderViewQueryService.java`（CardInfo及解密映射）。
- Test: `T/border/fulfillment/application/ProjectStandardAssetPayloadDecoderTest.java`、`T/border/fulfillment/application/huashengflow/HuashengAssetQueryContractTest.java`。
- Modify Frontend: `F/src/api/order/types.ts`、`F/src/api/order/index.ts`、`F/src/views/supplier/order/components/ExclusiveOrderInfo.vue`。
- Create Frontend: `F/src/api/order/asset-valid-time.ts`（纯映射函数，由index.ts调用）。
- Create Frontend Test: `F/tests/huasheng-valid-time.test.mjs`，复用该仓现有node:test／TS直接导入测试模式，不安装第二套工具。

- [ ] 后端测试先RED：四种有效期state原样；两端cardCode/secret逐字一致；NULL/ABSENT不猜日期；schema/字段冲突拒绝；跨项目拒绝且保留敏感审计。
- [ ] decoder显式读取payloadSchemaVersion=1和supplierPayload；只向授权DTO新增validTimeRaw/validTimeState。字段注释与规格§7一致，不改现有DB日期含义；内部原始payload查询继续完整返回。
- [ ] 写前端映射测试先RED，page直接使用expireTime展示；原文优先、不Date解析、不trim，三态缺值显示稳定文案，旧响应没有state仍用原格式化范围。纯函数置于asset-valid-time.ts方便node:test导入，index.ts实际调用，不仅测试孤立副本。映射核心是：

```ts
const expireTime = card.validTimeState === 'VALUE'
  ? (card.validTimeRaw === '' ? '供应商未提供' : card.validTimeRaw)
  : card.validTimeState
    ? '供应商未提供'
    : [formatDateTime(card.validFrom), formatDateTime(card.validUntil)]
        .filter(Boolean).join(' ~ ')
```

validTimeState=VALUE却缺raw在后端契约拒绝；TS类型使用判别联合或先验证，不能靠非空断言掩盖坏响应。页面密码/卡号不改变原值。

- [ ] GREEN后端纯测/Failsafe；前端工作目录F运行`node --experimental-strip-types --test tests/huasheng-valid-time.test.mjs`、`pnpm exec eslint src/api/order/index.ts src/api/order/types.ts src/api/order/asset-valid-time.ts src/views/supplier/order/components/ExclusiveOrderInfo.vue`、`pnpm ts:check`和`pnpm build:local`。基线已有失败单独记录，不擅自全仓修复。

## 9. Task 8：从真实配置到生产链完整有限验收

**Files**
- Create: `T/border/fulfillment/application/huashengflow/HuashengProductionClosureIntegrationTest.java`、`T/testsupport/HuashengHttpsFixture.java`。
- Modify: `docs/集成测试.md`§7.90及§8.12，按实际命令/结果更新；本设计/计划只修真实差异。
- Review: `script/deploy/supply-chain-test/docker-compose.yml`（单实例证据），不自动部署或改远端配置。

- [ ] 写从真实管理配置服务发布到生产executor的IT，RED须证明现在没有该装配/流程。所有业务数据通过真实服务/Controller创建，不SQL补发布/路由/资产或用假metadata过校验。
- [ ] HTTPS fixture用本地RSA/AES、A4 test-onlyTLS装配、严格真实协议字段与独立去重账本；不替代A生产parser。一次受理沿PLACE→CONFIRM→TAKE，另一case PLACE直接SUCCESS；下单业务参数摘要逐字稳定。
- [ ] 运行成功、明确失败/退款、未知恢复、重复/迟到/重启/回调、冻结配置变更、金额/资产冲突、跨项目隔离及上限人工场景；断言Reservation/资产/资金流水与挡板履约计数，不只响应。
- [ ] 全量供应链API/server单测与架构；Failsafe运行本期全部ContractTest/IntegrationTest及Liquibase；common/web/gateway直接受影响保护回归。只跳过其它模块无关单测，不为QNM另加专项验收任务，也不排除全量已有用例。
- [ ] 按规格§8.3检查日志：新执行traceId/executionId不同、origin真实、finally恢复；提交回滚无成功日志；无卡密/token/密文泄露。结构化顶层输出／真实ELK联调未测就保持未验。
- [ ] 更新§7.90实际证据，区分静态/自动化/实机；若目录自动发布等另片未交付，明确不是“从零页面全链通过”，不SQL补状态。
- [ ] 提交前/完成声明前加载verification-before-completion技能，重新核工作区、报告和diff。用户未授权不commit/push；真实供应商单次外呼及部署仍单独确认。

## 10. 自审覆盖与交付检查点

- 规格§1～3 → Task3/4/5；单实例及QNM退出在文件范围与全量测试边界固定。
- 规格§4 → Task1/4；没有密文直传／跨操作复用AAD。
- 规格§5～6 → Task4/5/8；每次业务／QUERY认领都有持久watchdog、有限恢复、原单号、代次围栏和最终资金guard。
- 规格§7 → Task2/7/8；type201全字段三态保真／唯一键／授权查询／实际前端。
- 规格§8 → Task6/8；公网回调必须存在、冻结版本认证、nonce持久、同事务收敛和安全保护。
- 规格§9 → Task8；本地挡板和真实供应商分开，不先等其它六型或基础设施专项。

推荐三个审阅检查点：Task1～3完成后检查冻结配置/资产契约；Task4～6完成后检查生产执行/恢复/回调与资金；Task7～8完成后检查两端查询与有限自动化证据。任何检查点失败只修本片因果，不扩大为平台工程。
