# 单步直充商户共享限额实施计划

> 执行方式：用户已批准细化设计，使用executing-plans在当前Dev-1031隔离工作树内串行TDD实施；无子代理工具，不另开工作树。不得自动提交/推送或操作共享环境。

**目标：** 完成MySQL商户统一配置、认领原子准入及PersistentTimer容量等待的后端切片。
**架构：** tradecore.supply拥有额度/调用租约；border拥有等待，原单锁后调用额度应用服务，HTTP在事务外。缺配置失败关闭，不默认生产数值。
**技术栈：** Java21、Spring事务、MyBatis Plus、Liquibase、MySQL8.4/Testcontainers、既有Timer。
**设计：** `docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md`，用户已确认§9全部细节。

路径前缀J/T分别为`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`与同模块`src/test/java/com/aifuli/module/supplychain/`，R为同模块`src/main/resources/`。

## 1. 配置、策略与三表迁移

文件：J/tradecore/supply/domain/MerchantQuotaPlatform.java、MerchantQuotaPolicy.java；J/tradecore/supply/application/MerchantQuotaService.java；J/tradecore/supply/dal/dataobject/MerchantQuotaDO.java、MerchantCallLeaseDO.java及dal/mysql对应Mapper；J/border/fulfillment/dal/dataobject/SingleStepCapacityWaitDO.java及对应Mapper；R/db/changelog/releases/1.0.0/069-single-step-merchant-quota.sql及清单。

- [ ] 新建`T/tradecore/supply/domain/MerchantQuotaPolicyTest.java`，以固定时刻、速率/并发不足时最早到期点验证策略；先可编译桩RED，再实现。非法上限、账号原值、并发与速率同时不足、1秒开区间边界均断言。
- [ ] 新建`T/border/fulfillment/MerchantQuotaIntegrationTest.java`，复用隔离MySQL受理夹具，先通过公开配置命令与TransactionTemplate调用MANDATORY准入取得RED；不以缺表/编译失败当业务RED。
- [ ] 追加069三表迁移，不改历史changeSet；配置精确账号唯一、操作+代次唯一、时间注释/精度及无外键。配置命令`configure(platform,account,rate,concurrency,expectedVersion,operator,reason)`以null版本创建、非空版本CAS调额；`admit(platform,account,operationNo,generation,leaseUntil)`只能在认领事务内调用；`release(operationNo,generation)`独立事务幂等。
- [ ] GREEN后验证两个独立容器/连接池共享额度、不同平台/账号隔离、缺配置/非法上限/CAS拒绝、滚动速率不因释放退还、5→2调额与原窗口不重置、过期回收及认领事务回滚无租约。

示例断言（以测试实际命令记录为准）：
```java
quota.configure(new Configure(RENYUE, "Merchant", 10, 1, null, 1L, "隔离夹具"));
transaction.execute(status -> quota.admit(RENYUE, "Merchant", "OP-A", 1, clock.now().plusSeconds(60)));
assertThatThrownBy(() -> transaction.execute(status -> quota.admit(RENYUE, "Merchant", "OP-B", 1, clock.now().plusSeconds(60))))
    .isInstanceOf(CapacityUnavailable.class);
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease", Long.class)).isEqualTo(1);
```

## 2. 原单认领与释放接线

文件：J/supplieradapter/metadata/AdapterMetadata.java（显式平台选择，默认按三家代码类型映射；其他旧协议不启用）；J/border/fulfillment/application/singlestep/SingleStepFulfillmentLifecycle.java、SingleStepFulfillmentRunner.java；既有测试夹具仅给实际启用RENYUE路径配置隔离额度，不改TEST_SINGLE_STEP旧测试语义。

- [ ] 新建`T/border/fulfillment/SingleStepCapacityIntegrationTest.java`，经公开受理/Runner验证CREATE额度不足不外呼、无marker/频控holder/预算；QUERY不足保留代次/计数/资金。
- [ ] 原聚合锁后核等待未到期，额度不足抛封闭异常使整个认领回滚；缺配置由Runner转原单准备失败Case；数据库异常继续传播，不伪装容量不足。
- [ ] 成功认领将额度租约与generation/marker/watchdog原子提交，关闭同代等待。Runner实际调用结束或跳过后finally释放并发，结果事务前完成；释放故障保留已拿到结果，以安全诊断和过期兜底，不能触发重发。
- [ ] 注入认领保存失败/结果失败，检查租约与原单原子性、成功保护、一次资金及HTTP数量。

## 3. 持久等待与Handler

文件：J/border/fulfillment/application/singlestep/SingleStepCapacityWaitService.java；SingleStepFulfillmentConfiguration.java；J/border/fulfillment/domain/FulfillmentTimerTaskTypes.java；步骤2测试文件扩展。

- [ ] Runner捕获容量不足后调用等待服务新事务：原频控聚合锁后校验代次/action、CREATE无marker、QUERY截止/预算、原成功证据/Case，再写等待行/Timer。若等待失败向原入口传播，不假接管。
- [ ] `SINGLE_STEP_CAPACITY_WAKE` payload含operationNo/action/expectedGeneration/wakeSequence，generation允许CREATE的0，序次必须正；核持久行精确匹配及到期才调用同一Runner。重复未到期不增序；到期再不足递增序次，完成旧任务不复用永久幂等键。
- [ ] 等待nextWakeAt不改原nextRecoverAt/事实/NOT_FOUND计数/日历截止；QUERY唤醒截断到原截止，截止原Case保留资金。CREATE准备watchdog识别真实容量等待，不把正常等待转人工。
- [ ] GREEN矩阵覆盖等待回滚、重开容器仅持久payload恢复、旧序次跳过、QUERY预算0增量、先成功回调旧等待不复活、正常FIFO不变。

## 4. 门禁、证据与文档

- [ ] `SupplyChainLiquibaseIntegrationTest`按069合同更新71 changeSet、132张表、319时间列（额度2+租约3+等待3=8列），保留全精度/时区及无外键断言。
- [ ] 全供应链单测/架构及适用真实MySQL/HTTP组合，核实际执行类/XML，不汇总历史报告。
- [ ] 对照设计§8逐项自审，将C-04第20～22项已覆盖/尚缺证据分别记录`docs/集成测试.md`，不把本地通过等同生产容量/外部准入。
- [ ] 无新增HTTP/前端/生产默认额度，不接通知，不修改到店延期测试或华昇合同；提交、推送、部署另获授权。

## 5. 续作：释放失败注入与持续故障日志（2026-10-19）

依据设计§6.4，在同一Dev-1031工作树执行；本轮不提前推进真实多实例HTTP/管理端/完整历史审计。

- [x] 原55项定向基线退出0，日志`merchant-quota-release-baseline.log`，耗时3分41秒。
- [x] 4项新增真实MySQL用例验证：释放UPDATE后抛异常回滚，SUCCESS一次结算、叠加资金失败后重启纯本地补交、网络未知不重发、租约过期放行及跨订单持续故障/恢复。容量集成现15项。
- [x] 5项确定性诊断单测先RED后GREEN：单桶/60秒窗口、两万订单、8线程执行1000个竞争任务、首因/持续计数/恢复/新故障、安全异常与真实兼容JSON；未输出ES顶层计数。临时恢复旧逐次ERROR行为后，Runner集成反例出现3条日志而期望1条（1失败/0错误）；恢复收敛实现后通过。空租约返回false，不作为恢复。
- [x] 2026-10-19 02:32最终verify退出0、BUILD SUCCESS，耗时23分09秒；供应链全单测/架构2505项/252类、相关集成/契约213项/11类均无失败/错误/跳过。另完整根配置28＋TraceFilter2共30项通过。按当次日志逐类核XML，保全`merchant-quota-release-reports/summary.json`；实际命令、夹具问题及范围见docs/集成测试.md末尾与§8.12。
- [x] 全部仍为91ab277d上的本地未提交变更，无推送/部署/共享迁移/真实供应商调用；C-04第20～22及24项维持PARTIAL。完整MySQL门禁、真实多实例物理HTTP/动态调额/持续容量、管理HTTP/前端/完整审计及生产准入仍未完成；八进程/真实ELK未验收。独立子代理评审不可用，已自审事务边界、日志计数/安全及变更范围。

## 验证命令

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=MerchantQuotaPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration -Dtest=SupplyChainModuleArchitectureTest,MerchantQuotaPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=MerchantQuotaIntegrationTest,SingleStepCapacityIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=true verify
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**.*IntegrationTest,!**.*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=MerchantQuotaIntegrationTest,SingleStepCapacityIntegrationTest,SupplyChainLiquibaseIntegrationTest,SingleStepRecoveryIntegrationTest,SingleStepCallbackIntegrationTest,RenyueSingleStepIntegrationTest,SingleStepFrozenConfigurationIntegrationTest,RechargeFrequencyTimerIntegrationTest,SingleStepCallbackRestContractTest,RenyueSupplierAdapterContractTest,QnmSupplierAdapterContractTest,HuashengHttpContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=true verify
```

每个RED/GREEN日志保存`.amp/in/artifacts/merchant-quota-*`，新测试未收集/0项不能算通过。完整门禁需包括未执行项的明确说明，不自动排除供应链其它单测。

## 本轮执行结果（2026-10-19）

以上复选项覆盖最终完整验收目标，未因后端切片通过自动全部勾选。

- [x] 配置CAS、滚动速率/并发策略、069三表、准入/释放及原子认领接线完成。
- [x] 持久等待/独立唤醒序次、重启及旧任务围栏、准备watchdog豁免、终态关闭完成。
- [x] 7项额度集成、11项容量集成通过：含双Spring容器同库竞争、5→2调额、跨Connector/退役原单、Timer/认领回滚、QUERY预算/截止及迟到回调；不将容器重开称OS强杀。
- [x] 迁移对应5类数量/精度/时区/重启门禁通过。最终组合2500项单测/架构、223项集成/契约（16类）全部通过，退出0，耗时23分46秒。证据与实际命令见`docs/集成测试.md`末尾。
- [ ] 尚未覆盖所有真实多实例物理HTTP在途+调额/速率组合、持续容量压测、释放失败注入、全部安全资格和截止精确时刻排列。C-04第20～22项只记PARTIAL，不记全部PASS。
- [ ] 后续补管理HTTP/前端及完整配置历史审计、取得生产额度/逐家准入证据；当前不开放生产路径。

本批开发期间只有本地未提交修改，无共享迁移、推送、部署或真实供应商交易；独立子代理评审工具不可用，已做逐文件自审，不编造外部审查结论。

**提交前复验／2026-10-19 01:52／PASS**：用户已授权中文本地提交本批全部相关改动。以JDK21执行定向verify，主架构31、纯策略6、商户额度集成7、容量集成11，共55项全部通过，失败/错误/跳过均0、退出0、BUILD SUCCESS，耗时3分47秒；日志`.amp/in/artifacts/merchant-quota-before-commit.log`。本次只复验上述范围，不冒充重跑2500/223组合；本批源码、069迁移、测试及设计/计划/集成记录一并提交，不包含本机忽略证据，不推送、不部署。第20～22项和第24项仍PARTIAL。
