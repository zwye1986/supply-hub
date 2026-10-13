# 供应链Redis／MySQL协作订单发号实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 新建CARD/O2O/INSTORE总订单号及标准履约billno使用24位数字编号，Redis消费MySQL永久预留专属段，历史订单和原单恢复保持身份。

**Architecture:** 所有创建场景先通过事务外协调器取号。单一实例持有专属号段及已返回floor，Redis脚本在上海日期内检查范围并原子消费；缺段才通过现有供应链数据源短事务预留。未知Redis结果弃段，新启动不恢复旧段，不增加连接池或外呼重下能力。

**Tech Stack:** JDK21、Spring Boot3.5、MyBatis Plus/Liquibase、MySQL8.4、Redis7+、现有Redisson4.4/StringRedisTemplate、JUnit5/AssertJ/Testcontainers。

**工作目录：** `/Users/mac/.codex/worktrees/8941/supply-hub`。前端检查仅使用 `/Users/mac/codespace/supplychainhubweb`。设计经用户于本线程2026-10-13确认通过，实施不等于获准共享数据库迁移、部署或真实交易。

## 接口与文件职责

以下路径均以 `aifuli-module-supply-chain/aifuli-module-supply-chain-server` 为模块根。`infrastructure/identity` 为main/java下完整包 `com/aifuli/module/supplychain/infrastructure/identity`。

- `OrderNoPurpose`：`ORDER_ONLY`/`ORDER_AND_BILL`封闭用途，内部数量1/2。
- `PreparedOrderNumbers`：不可变内部号码，不参与指纹；公共读取接口固定为 `orderNo()`、`billno()`。
- `OrderNoFormatter`：上海Redis时刻、随机两位、八位流水，严格ASCII24位。
- `OrderNoProperties`：配置段长1000（2～10000）、明确env/scope及有界等待/命令超时，不修改全局Redis客户端配置。
- `OrderNoSegmentStore`/`MySqlOrderNoSegmentStore`：现有供应链事务管理器短事务，日期高水位与owner区间同提交，不调用Redis。
- `OrderNoRedisOperations`/`RedisOrderNoOperations`：共享Redis客户端执行TIME及参数化Lua、每次调用预算、无未知结果透明重试。
- `OrderNoAllocationService`：外层事务守卫、本实例互斥、单段状态/floor、预留/跨日/弃段；唯一公开取号接口 `prepare(OrderNoPurpose)`。
- `infrastructure/configuration/OrderNoConfiguration`：显式现有数据源、Redis与事务绑定，缺少必要客户端/有效配置失败关闭。
- `dal/dataobject/OrderNoSequenceDO`、`OrderNoSegmentDO`与对应Mapper/XML：字段注释与设计DDL一致，不新增注解SQL。
- `main/resources/identity/order-no-consume.lua`：单个显式KEYS，TIME日期校验、SET NX初始化/向上floor修复、范围校验、INCRBY、7天绝对到期；返回状态、时刻和流水区间。
- `main/resources/db/changelog/releases/1.0.0/065-order-number-segments.sql`：新增两张sc表，通过追加changeSet接入主清单。

核心调用契约：

```java
public enum OrderNoPurpose { ORDER_ONLY, ORDER_AND_BILL }
public record PreparedOrderNumbers(String orderNo, String billno) {}
// 实际构造器校验24位；ORDER_ONLY的billno为空。
public PreparedOrderNumbers prepare(OrderNoPurpose purpose);
// 场景Lifecycle只接收协调器生成的不可变结果；绝不在事务内补号。
public AcceptanceSnapshot accept(long receiptId, PreparedAcceptance command, PreparedOrderNumbers numbers);
public AcceptanceSnapshot acceptDirectProjectOrder(long receiptId, PreparedAcceptance command, PreparedOrderNumbers numbers);
public PreparedPayment createPaymentPreparation(PreparePaymentCommand command, PreparedOrderNumbers numbers);
public PreparedPayment createPaymentOrder(CreatePaymentOrderCommand command, PreparedOrderNumbers numbers);
```

现有公共Facade/RPC/HTTP签名保持不变；删除或拒绝缺少号码的Lifecycle创建回退，测试迁移为显式准备/协调调用。测试专用隔离号码通过测试配置提供，不能在生产服务保留ULID回退。

## Task 1：发号核心、永久预留与Redis消费

**Files:** 上述identity核心、DO/Mapper/XML/Lua、OrderNoConfiguration、065迁移及清单；API模块 `src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`；测试 `infrastructure/identity/OrderNoAllocationServiceTest.java`、`OrderNoRedisIntegrationTest.java`、`OrderNoSegmentIntegrationTest.java`。

- [x] 写核心行为红灯，先从编号格式与现有BusinessNoGenerator当前ULID输出不满足新规则的可运行断言证明差异；再写分配器测试，覆盖同段双号、floor、段末、异常弃段、事务入口拒绝。

```java
assertThat(numbers.orderNo()).matches("[0-9]{24}");
assertThat(numbers.billno()).matches("[0-9]{24}").isNotEqualTo(numbers.orderNo());
// 第一次Redis消费超时后，下一次使用新段而非重发第一段。
assertThat(nextSequence).isGreaterThan(abandonedSegmentEnd);
```

- [x] 用JDK21运行红灯，区分预期行为失败与编译/配置错误，记录日志；新类型可先定义最小接口以让行为断言运行，但不得先实现算法。
- [x] 实现格式/配置/封闭用途、独立短事务区间预留。数据库初始化upsert使用XML，锁读/条件更新Lambda；容量错误新增独立错误常量并登记现有重试映射。

```text
事务外守卫 → 同实例有界互斥
  → 无可用段：Redis TIME定日期 → MySQL短事务锁日期/推进高水位/插入owner区间 → 提交释放连接
  → Lua TIME日期守卫 → 以max(redisCursor, localFloor)为基准检查容量 → INCRBY1/2 → 固定到期
  → 校验时刻/段身份/连续区间 → 先推进floor → 格式化并返回
任何不确定结果：清除本地段，再释放互斥并传播带cause的系统异常
```

- [x] 实现Redis预算。复用现有客户端；按当前实际Jar公开API配置每对象命令超时与0透明重试，不改全局配置、不建executor。若使用异步命令，等待受剩余预算控制，取消/迟到不得返回弃用段。只允许明确未执行NOSCRIPT重载一次。
- [x] 新增两张表与065，字段和COMMENT逐项对齐设计§4.1；旧迁移不改，段记录永久保留，无外键。
- [x] 用真实MySQL8.4/Redis7+跑段竞争、500批双号一次预留、删除/回退cursor、NOSCRIPT、响应不确定、重启新owner、容量与事务拒绝；单元测试提供低层故障测试双替身，不能只证明mock调用。
- [x] 自审接口、预算、错误因果与无新增池；提交中文说明，需求审查通过后再做代码质量审查。

命令（本计划全部Maven使用相同模块选择和JDK）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false -Dtest=OrderNoAllocationServiceTest,OrderNoFormatterTest,OrderNoPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false -Dit.test=OrderNoRedisIntegrationTest,OrderNoSegmentIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false
```

## Task 2：三个场景事务外接入与历史兼容

**Files:** `border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`、`RechargeFrequencyAcceptanceCoordinator.java`；`border/o2o/application/O2OChannelLifecycle.java`、新增 `O2OOrderCreationCoordinator.java`；`border/instore/application/InstoreChannelLifecycle.java`、新增 `InstoreOrderCreationCoordinator.java`；`application/facade/O2OChannelCommandFacade.java`、`InstoreChannelCommandFacade.java`；新增 `O2OOrderCreationCoordinatorTest`、`InstoreOrderCreationCoordinatorTest`、`ChannelOrderNumberIntegrationTest`，扩展现有 `InstoreChannelLifecycleTest`、`InstoreChannelCommandFacadeTest`、CARD集成与 `OrderNoTestConfiguration` 测试支持。仓库没有既有O2O Lifecycle/Facade单测，需在新增场景测试中覆盖该逻辑；前端仅核对现有字符串适配。

- [x] 先扩展当前协调器/场景回放测试形成红灯：新创建取数字号，已接受同参回放不调用取号服务；原队列唯一冲突有限重试保持同一对号码。

```java
verify(allocator, times(1)).prepare(OrderNoPurpose.ORDER_AND_BILL);
verify(lifecycle, times(2)).accept(receiptId, command, sameNumbers);
// 已有终态可信回放不应访问发号Redis/号段。
verifyNoInteractions(allocator);
```

- [x] CARD保持claim/首冻/准备与最终锁顺序；创建点使用 `numbers.orderNo()` 与 `numbers.billno()`。新增可信回放只读入口必须核完整请求指纹和已接受状态，不无条件replay。取号失败后尝试可信竞争回放，若没有可信结果传播原异常。
- [x] O2O事务外协调：短事务已启用绑定→已有交易/金额回放探测→取ORDER_ONLY→原子建单。INSTORE：保持已有流水金额/project/profile/buyerUser回放顺序，新建才取ORDER_ONLY，业务事务重新核订单头/流水并允许沿原规则复用旧头。

```text
tryReplay(command) 返回可信现有结果则直接返回
否则 prepare(ORDER_ONLY)
随后 lifecycle.create...(command, numbers)
已知业务永久键首创竞争：原事务结束后再核可信获胜结果
编号唯一冲突、其它唯一冲突或提交不确定：不得盲回放、换号或重复外呼
```

- [x] 保持O2O现有回放仅金额比较，不能改成新完整指纹合同；两场景取号/预留/外呼都在业务事务之外。收银台抢占、资金冻结、支付/退款语义不改。
- [x] 迁移直接Lifecycle调用的测试为显式内部号码参数；业务集成用测试配置提供隔离号码，独立发号集成仍必须真实Redis/MySQL。所有Spring测试fixture装配明确，没有生产测试专用降级。
- [x] 验证CARD v1/v2/RPC、O2O、INSTORE数字总单、同参/异参/竞争、限购/限频、资金回滚、华昇旧BILL恢复；编号保持字符串，操作/checkpoint沿已绑定号。核前端复制/搜索代码不转Number，不为未展示字段增加独立页面功能。
- [x] 运行适用业务Test/IntegrationTest/ContractTest与架构测试；提交中文说明，通过需求与代码质量审查。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false -Dtest=RechargeFrequencyAcceptanceCoordinatorTest,O2OOrderCreationCoordinatorTest,InstoreOrderCreationCoordinatorTest,InstoreChannelLifecycleTest,InstoreChannelCommandFacadeTest,SupplyChainModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false -Dit.test=FulfillmentAcceptanceIntegrationTest,ProjectStandardAcceptanceIntegrationTest,RechargeFrequencyAcceptanceIntegrationTest,ChannelOrderNumberIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false
```

新建渠道综合集成类与现有CARD类均应实际运行；不能把无匹配当通过。现有INSTORE会员/退款集成继续按直接受影响范围回归。

## Task 3：完整门禁、装配故障与交付证据

**Files:** 新增 `OrderNoArchitectureTest`、`OrderNoConfigurationTest`及真实Redis故障/预算集成；直接受影响既有测试；`docs/集成测试.md`、编号设计、时间设计、一期设计待评审索引改为准确实施状态。

- [x] 写装配与入口约束红灯：启用数字发号但缺Redis/非法env/scope必须失败，生产三个SC点和BILL点不能继续调用通用ULID生成器；Facade不访问DAL，未增加池。
- [x] 真实Redis故障门禁覆盖已有段回退修复与未知状态弃段、迟到结果不返回旧号、Redis不可用无MySQL逐号轨道、MySQL阻断时旧段可取号/新段失败；暂停/恢复旧实例与新owner区间不重叠。
- [x] 运行供应链全部适用单元/架构及受影响契约/真实MySQL/Redis集成，确认通过总数、失败数、跳过数和JDK/服务版本。已有业务失败与新失败分开归因，不跳过供应链自动化。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -DskipTests=false '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home mvn -q -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test-compile failsafe:integration-test failsafe:verify -Psupply-chain-integration -DskipTests=false -DskipITs=false '-Dit.test=OrderNo*IntegrationTest,SupplyChainLiquibaseIntegrationTest,*AcceptanceIntegrationTest,*O2O*IntegrationTest,*Instore*IntegrationTest,StandardProjectHttpClosureIntegrationTest,*Order*ContractTest' -Dfailsafe.failIfNoSpecifiedTests=false
```

- [x] 审查日志事实/因果，按日志规范区分号段预留、号码返回、业务提交；正常DEBUG，继续上抛不重复堆栈，不新增凭据/敏感正文。
- [x] `git diff --check`，检查前端字符串兼容；维护联调文档自动化证据与尚未部署/共享Redis核对/真实供应商人工验收项，不能把本地通过写成已上线。
- [x] 最终整体审查并处理所有实质问题；创建 `codex/` 分支保存隔离工作树实现，中文提交。本次不推送、不建PR、不部署；用户没有要求这些外部动作。

## 执行记录

- [x] 已获用户详细设计评审通过。
- [x] 已确认工作树隔离、Docker/MySQL测试容器与本机Redis工具可用。
- [x] Task1实施与双阶段审查。
- [x] Task2实施与双阶段审查。
- [x] Task3门禁、整体审查和交付。

### 完成记录（2026-10-13）

已完成三个任务。最终全量供应链单测/架构/契约为API171、server2598，零失败/错误/跳过；真实MySQL/Redis及受影响集成/契约分批最终为API3、server121，零失败/错误/跳过。不是一条集成命令首次全绿，也未执行所有供应链IntegrationTest。详细类目、命令和纠错证据见`docs/集成测试.md`本次记录及本机`.amp/in/order-no-delivery-summary.json`。

原子段、预算与异常弃段采用需求符合性审阅，再检查实现质量与全量门禁；父代理完成整体人工复核。子代理两次受到服务额度限制中断，后续实现和审查由父代理接手，未声称独立子代理审查通过。复核确认无专用数据源/池、无生产测试降级、无任意唯一冲突盲回放、无原单换号或新供应商重下。实际测试发现并修正O2O既有DDL快照/总单字段问题、INSTORE总单永久键竞争识别、065排序规则及迁移夹具断言；相应设计先行补记。

工作保存在`codex/order-number-redis-mysql`。共享迁移、部署、目标Redis ACL/域核验与真实供应商人工验收未执行；这些不在本次本地实施授权范围。
