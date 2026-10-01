# 直充手机号与 SPU 限频实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现全中台手机号+平台 SPU 的持久化 FIFO 直充队列，接入资金与履约，并提供仅内部运营使用的限频监控。

**Architecture:** 数据库保存队列、订单队列项和运营操作审计，PersistentTimer 负责可恢复唤醒。受理事务同时预占资金和入队；出站执行经过认领与可能发送两道事务边界，结果经统一履约入口释放或保留占用。管理端查询/操作与队列推进分别封装，供应链独立库不访问 system/member 表。

**Tech Stack:** Java 21、Spring Boot、MyBatis Plus、MySQL 8.4、Liquibase、JUnit 5、Testcontainers；Vue 3、TypeScript、Element Plus、pnpm。

---

## 工作区与范围

- 后端：`/Users/mac/.codex/worktrees/project-app-access/supply-hub`，分支 `codex/project-app-access`。
- 前端：`/Users/mac/.codex/worktrees/project-app-access/supplychainhubweb`，分支 `codex/project-app-access`。
- 用户已确认设计并要求继续，在上述现有隔离工作区实施，不另建工作区、不修改主工作区。
- 依据：`docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md`，主 HTTP 设计 §10。
- 本计划交付限频与监控。标准下单 HTTP、buyerUserId 移除及内部冻结报价适配有独立范围；对外真实下单开放仍受主设计 §8 约束，不能通过这份计划重新要求报价 token/paidAt。
- 以下 Java 路径均从 server 的 `src/main/java/com/aifuli/module/supplychain/` 起算；测试路径从同模块 `src/test/java/com/aifuli/module/supplychain/` 起算，二者实际模块均为 `aifuli-module-supply-chain/aifuli-module-supply-chain-server`。资源路径明确列出。新文件使用对应包，重要中文注释链接设计章节。
- 时间统一使用 SupplyChainDatabaseClock 的 Asia/Shanghai，不使用 Instant.now/LocalDateTime.now 驱动持久化窗口。

## 执行与测试命令

在后端执行命令前设置 Java 21：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

定向单测通过 `-Dtest` 选择供应链新测试，避免运行其他模块测试：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dtest='RechargeFrequency*Test,!RechargeFrequency*IntegrationTest,!RechargeFrequency*ContractTest,FulfillmentTargetFieldsTest,SupplierOrderLifecycleTest,SupplyChainModuleArchitectureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

真实 MySQL 与契约测试由 Failsafe 运行，不能用普通 surefire 的成功代替：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify \
  -Psupply-chain-integration \
  -Dtest='RechargeFrequency*Test,!RechargeFrequency*IntegrationTest,!RechargeFrequency*ContractTest,SupplyChainModuleArchitectureTest' \
  -Dit.test='RechargeFrequency*IntegrationTest,RechargeFrequency*ContractTest,SupplyChainLiquibaseIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

每个行为先加失败测试并运行，再写实现，再运行验证；每阶段独立中文提交。任务未通过对应审查不得标为完成。保持审查期间无新的实现者并行改同一仓库。

## Task 1：纯领域身份、占用窗口与释放规则

状态：已完成，提交 `84deed4`。设计符合性与代码质量审查均通过；定向 38 项测试通过（新规则9、既有目标规范化2、架构27）。

**文件：**
- 新建 `border/fulfillment/domain/RechargeFrequencyPolicy.java`。
- 新建 `border/fulfillment/domain/RechargeFrequencyEntryStatus.java`、`RechargeFrequencyDispatchPhase.java`、`RechargeFrequencyConstants.java`。
- 新建测试 `border/fulfillment/domain/RechargeFrequencyPolicyTest.java`。
- 复用 `border/fulfillment/domain/FulfillmentTargetFields.java` 的规范化逻辑，不再新增手机号解析器。

- [x] 写入成功时间边界、未知结果、PREPARED 撤销与 SEND_POSSIBLE 拒绝的失败测试，使用固定 LocalDateTime 而不是 sleep。

```java
@Test
void successfulOrderReleasesAtAcquiredPlusFiveSeconds() {
    LocalDateTime acquired = LocalDateTime.of(2026, 9, 30, 10, 0);
    assertThat(RechargeFrequencyPolicy.canReleaseSuccess(acquired, acquired.plusSeconds(4))).isFalse();
    assertThat(RechargeFrequencyPolicy.canReleaseSuccess(acquired, acquired.plusSeconds(5))).isTrue();
    assertThat(RechargeFrequencyPolicy.canReleaseSuccess(acquired, acquired.plusSeconds(30))).isTrue();
}
```

- [x] 运行 `mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test -Dtest=RechargeFrequencyPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`，确认缺失规则导致失败。
- [x] 实现有明确含义的枚举与常量：WAITING/HOLDING/FINISHED，PREPARED/SEND_POSSIBLE；成功窗口5秒、超时1800秒、积压严格大于5。
- [x] 实现下列核心纯规则，其他条件由 Lifecycle 基于锁定数据提供，领域层不得读取 Mapper/Spring：

```java
public static boolean canReleaseSuccess(LocalDateTime acquiredAt, LocalDateTime now) {
    return acquiredAt != null && now != null && !now.isBefore(acquiredAt.plusSeconds(
            RechargeFrequencyConstants.SUCCESS_MINIMUM_HOLD_SECONDS));
}

public static boolean canRevokePrepared(boolean noUnresolvedEarlierSend,
                                       RechargeFrequencyDispatchPhase phase) {
    return noUnresolvedEarlierSend && phase == RechargeFrequencyDispatchPhase.PREPARED;
}
```

- [x] 增补未知结果不能释放、1800秒严格边界、积压5/6单、非MOBILE与卡券不适用、+86/0086归一用例，运行 Task 1 测试与架构测试。
- [x] 提交：`明确直充限频占用窗口与可信释放规则`。

## Task 2：三张持久化表、Mapper 与真实约束

状态：已完成，提交 `c46c630`。设计符合性与代码质量审查均通过；36项策略/架构测试、8项持久化集成测试与3项Liquibase集成测试全部通过，无失败或跳过。

**文件：**
- 新建资源 `src/main/resources/db/changelog/releases/1.0.0/048-recharge-frequency.sql`，注册到同目录 `db.changelog-1.0.0.yaml`；执行前确认048尚未被其他变更占用。
- 新建 `border/fulfillment/dal/dataobject/RechargeFrequencyQueueDO.java`、`RechargeFrequencyEntryDO.java`、`RechargeFrequencyActionAuditDO.java`。
- 新建 `border/fulfillment/dal/mysql/RechargeFrequencyQueueMapper.java`、`RechargeFrequencyEntryMapper.java`、`RechargeFrequencyActionAuditMapper.java`。
- 新建 `src/main/resources/mapper/fulfillment/RechargeFrequencyQueueMapper.xml` 与 `RechargeFrequencyEntryMapper.xml`，仅用于控制行行锁与复杂聚合，不新增注解SQL。
- 新建测试 `border/fulfillment/RechargeFrequencyPersistenceIntegrationTest.java`。
- 修改 `infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`，校验新增表时间语义及变更集数，不改旧文件校验和。

- [x] 使用现有 MySQLContainer/mysql:8.4 与 Liquibase 建库方式，先验证缺表失败。
- [x] 按设计 §3 创建完整字段；QUEUE 唯一 `(mobile_digest,platform_spu_id)`，ENTRY 唯一 `order_id` 与 `(queue_id,enqueue_sequence)`，AUDIT 唯一 `action_no`。所有时间 DATETIME(3)/Asia/Shanghai，摘要 BINARY(32)，版本BIGINT，物理外键为零。
- [x] 在048补正046的两列created_at注释为Asia/Shanghai，保留原字段定义与值、原changeset校验和。真实数据库当前既有49个changeset/117张表/277个时间列，本次新增后为50/120/289（12个新时间列含持久化告警），按实际快照验证，不以文件编号推断计数。
- [x] 控制行允许等待阶段 holder/acquiredAt/minimumHoldUntil 均NULL；持有时三者同时非空；next_enqueue_sequence初值1，代次初值0；状态 CHECK 与枚举一致，SPU/序号正数、版本非负。
- [x] Mapper 简单查询/更新用 Lambda；需要行锁的 XML 精确按主键或已有唯一键 `FOR UPDATE`，不对尚不存在的唯一键先锁后插入。

```sql
SELECT * FROM sc_recharge_frequency_queue WHERE id = #{id} FOR UPDATE;
SELECT * FROM sc_recharge_frequency_entry
WHERE queue_id = #{queueId} AND status = #{waitingStatus}
ORDER BY enqueue_sequence LIMIT 1 FOR UPDATE;
```

- [x] 验证同组合唯一、同手机号不同SPU独立、同订单不能重复入队、错误状态与时间空值组合被拒、版本CAS失败不改变其他列。
- [x] 运行 Task 2 的 Failsafe 定向验证与 SupplyChainLiquibaseIntegrationTest；期望真实MySQL无失败，不接受H2替代。
- [x] 提交：`新增直充限频队列与运营审计持久化模型`。

## Task 3：稳定手机号摘要、服务端SPU解析与入队

状态：已完成，提交 `f68cd50`。设计符合性与代码质量审查均通过；新增23项单测、既有11项纯逻辑及27项架构测试，共61项通过；新增3项与既有2项真实受理集成测试通过，无失败/错误/跳过。

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyIdentityService.java`、`RechargeFrequencyLifecycle.java`。
- 新建 `border/fulfillment/application/RechargeFrequencyAcceptanceCoordinator.java`，在事务外协调最多3次队列身份创建竞争重试；修改 `application/facade/BuyerFulfillmentCommandFacade.java` 的受理调用以委派协调入口，不访问DAL。
- 新建专用密钥解析端口/实现于 `infrastructure/security/RechargeFrequencyIdentityKeyProvider.java` 与 `ConfiguredRechargeFrequencyIdentityKeyProvider.java`。
- 新建 `tradecore/catalog/application/RechargeFrequencyProductResolver.java`，负责平台SKU→SPU身份及履约方式；不能把供应商商品当SPU。
- 修改 `border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`，在首次操作和资金预占同事务内入队；将所需目标身份随 PreparedAcceptance 传递，不从 buyerUserId 恢复。
- 新建测试 `border/fulfillment/application/RechargeFrequencyIdentityServiceTest.java`、`border/fulfillment/RechargeFrequencyAcceptanceIntegrationTest.java`。
- 修改新流程所依赖的既有测试上下文，显式导入新服务并提供测试专用密钥解析实现；不在生产配置填测试密钥。

- [x] 先写不同前缀同摘要、不同手机号不同摘要、不合法号码拒绝、日志/DTO不输出明文、密钥缺失不绕过的失败测试。
- [x] 专用HMAC-SHA256用途域 `RECHARGE_FREQUENCY_MOBILE`，32字节稳定密钥由密钥引用解析，不复用项目appKey，不随凭证轮换；摘要实现如下，Mac每次创建避免线程共享：

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(identityKeyProvider.keyBytes(), "HmacSHA256"));
mac.update(RechargeFrequencyConstants.HMAC_PURPOSE.getBytes(StandardCharsets.UTF_8));
mac.update((byte) 0);
byte[] digest = mac.doFinal(normalizedMobile.getBytes(StandardCharsets.UTF_8));
```

- [x] 应用服务仅传递摘要、maskedMobile和冻结spuId。手机号仍从经过验证的实际目标取得，忽略非手机号直充/卡券；非法MOBILE失败关闭。
- [x] 入队按orderId幂等；首次不存在QUEUE时普通插入由唯一键裁决，冲突整个事务回滚后重新读取；不在已失败事务继续业务。锁定控制行分配序号，新增WAITING项，同事务持久化唤醒意图。在既有首次Operation创建后、fundReservationService.freeze之前调用入队，统一队列→资金的锁序；不能在已锁资金账户后取得队列锁。
- [x] 在协调入口仅捕获队列身份唯一键创建竞争的专用异常，每次重新调用Lifecycle事务代理，最多3次（集中常量），保留因果异常；其他完整性异常不重试/不吞掉。测试跨SKU并发首次入同队列双方均可正常受理，失败事务不存在资金/订单残留。
- [x] 真实测试跨买家/项目同组合入同队列、同手机号不同SPU两队列、入队失败时资金/建单全回滚、重复受理不重复预占/入队。
- [x] 运行 IdentityServiceTest 和 AcceptanceIntegrationTest 后提交：`按真实手机号与平台SPU原子入队并保留资金预占`。

## Task 4：FIFO认领、发送边界与可信终态推进

状态：已完成，执行接入 `d099deb`、可信拒绝续履约 `90b2932`、未发送资格风险及双因果修复 `b392692`。两级独立复评均通过。原完整定向77项单测/架构+20项MySQL通过；请求装配追加36+10、续履约整组36+9、活动指针追加36+4、撤销风险与双因果追加37+5均通过，0失败/错误/跳过。分次范围不相加为去重总数；证据保存于 `/tmp/task4-full-verify-reports`、`/tmp/task4-continuation-reports`、`/tmp/task4-quality-reports`。

**文件：**
- 修改 `border/fulfillment/application/RechargeFrequencyLifecycle.java`。
- 修改 `border/fulfillment/application/FulfillmentOperationExecuteHandler.java`、`SupplierOrderLifecycle.java`。
- 新建 `border/fulfillment/application/RechargeFrequencyExecutionPermit.java`。
- 新建测试 `border/fulfillment/RechargeFrequencyExecutionIntegrationTest.java`、`RechargeFrequencyLifecycleIntegrationTest.java`；修改 `border/fulfillment/application/SupplierOrderLifecycleTest.java`。

- [x] 定义执行资格快照，必须携带首次认领的代次和操作版本，旧执行者不允许重新读取新代次替换自己的资格：

```java
public record RechargeFrequencyExecutionPermit(long queueId, long entryId,
        String operationNo, long generation, long entryVersion, long operationVersion) { }
```

- [x] 先用真实MySQL并发测试：多个节点只能认领队首一项，同手机号不同SPU可并发；非队首正常返回等待、不调用适配器、不增加外呼失败次数。
- [x] 验证历史MOBILE直充缺队列项、目标类型无法可靠解析时不能走普通claim绕过限频；仅可靠确认不适用的目标走普通执行，已冻结队列项不因商品后续变更而失效。
- [x] 完成同事务QUEUE+ENTRY+Operation认领，状态PREPARED。执行/结果/恢复锁序统一QUEUE→ENTRY→Request/Order→BOrder/Operation→既有资金服务，加锁后重新校验关联，不在结果处理入口取得商品限购写锁。
- [x] 实现外呼前事务 PREPARED→SEND_POSSIBLE，条件包含首次资格全部字段。必须返回成功才执行适配器；失败返回稳定跳过结果。
- [x] 加两线程屏障测试：人工撤销先提交则旧外呼0次；标记可能发送先提交则释放失败，节点失联仍保留占用。不靠sleep判定先后。
- [x] 回调/查询统一事实收敛：ACCEPTED/UNKNOWN/MANUAL保持占用；可信成功待acquired+5秒；总订单可信失败释放；继续履约仍同订单持有。
- [x] 针对已MANUAL操作补充受控可信事实恢复，复用原资金与观察写入，区别禁止重发与允许核实；无可信事实不能调用该入口。重复/相互矛盾事实保留审计，迟到事实不能清除新holder。
- [x] 区分单次拒绝与整单耗尽：由内部受控可信REJECTED入口显式指定已存在合法下一Operation，验证同Request/Order、序列/尝试、活动BOrder及无未决风险，原结果事务保留资金和占用并切换引用；无指示保持M1耗尽语义。删除失败释放后的断开绑定接口，测试必须走真实结果入口，不用SQL伪造旧失败终态。
- [x] 测试资金预占保留、成功只扣一次、明确失败只释放一次、重路由不更换队列、旧回调不会释放新代次。
- [x] 执行定向真实MySQL与既有SupplierOrderLifecycleTest，提交：`接入FIFO直充执行与可信结果占用释放`。

## Task 5：持久化唤醒、成功窗口检查和补偿告警

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyWakeHandler.java`、`RechargeFrequencyRecoveryService.java`。
- 新建 `border/fulfillment/application/RechargeFrequencyQueryService.java`、`FulfillmentOperationTicketFactory.java`，后者抽取既有执行Handler的冻结配置/凭证装配以供发送和查询复用；查询不解密充值目标。修改 `FulfillmentOperationExecuteHandler.java` 委派装配，保留Task4发送资格校验。
- 修改 `border/fulfillment/domain/FulfillmentTimerTaskTypes.java`，新增受控队列唤醒类型。
- 新建 `border/fulfillment/application/RechargeFrequencyRecoveryConfiguration.java`，注册受控定期补偿，不改变Timer全局行为。
- 新建 `RechargeFrequencyWakeScheduler.java`、`RechargeFrequencyTimerStartupCheck.java`，分别负责事务内调度和单例装配结束后的Timer完整性校验；启动校验不承载事务方法，避免JDK接口代理影响具体类注入。
- 新建测试 `border/fulfillment/RechargeFrequencyTimerIntegrationTest.java`、`border/fulfillment/application/RechargeFrequencyRecoveryServiceTest.java`。

- [x] 写同代次重复安排只有一任务、上一代已完成仍能新唤醒、重启恢复队首、成功窗口不重置的失败测试。
- [x] Task载荷限定queueId/wakeGeneration/payloadVersion=1；新意图增wakeGeneration并同时安排任务。幂等键使用 `queueId:wakeGeneration`，不使用明文手机号。

```java
String key = queue.getId() + ":" + queue.getWakeGeneration();
timer.scheduleOnce(FulfillmentTimerTaskTypes.RECHARGE_FREQUENCY_WAKE,
        key, payload, databaseClock.toInstant(fireAt));
```

- [x] Handler委派队列应用入口。检查holder：未知只计划下一次观察/告警，成功在minimumHoldUntil释放；无holder选择FIFO队首并安排既有OPERATION_EXECUTE，由执行Handler取得资格，不预先claim后再二次claim。执行任务幂等键包含唤醒代次，旧任务完成后新意图仍可唤醒同一业务操作。Timer用延迟解析端口避免构造环，不把等待当失败。
- [x] 补全现有标准履约缺失的主动查单恢复：仅已配置支持QUERY_ORDER的供应商，在事务外以原operationNo和冻结配置查询，由统一可信结果入口收敛；处理中/未知沿用30秒恢复基线，查询失败或NOT_FOUND继续占用，MANUAL只接受可信终态恢复。无查询能力不能退化为SEND。测试查询不带目标账号、不新建资金预占或SEND、迟到结果不释放新holder。
- [x] 补偿扫描有next_check_at索引、批量上限、仅恢复缺失/死信唤醒；同队列有效未来任务不重复新增。扫描不直接删除holder，不使用Redis租约作为授权依据。
- [x] 30分钟占用与积压>5按组合聚合告警，首次跨阈值记录、受控重复与恢复记录，日志仅队列ID/SPU/订单号，监控查询异常不伪装0值。
- [x] 真实测试崩溃、死信补偿、窗口剩余时间、重复任务和UTC+8转换。提交：`实现直充队列Timer恢复与积压占用告警`。

实施记录（2026-10-01）：`e583c3d` 实现代次唤醒、查单与补偿，`70e4984` 修复真实QNM鉴权错误与终态交叉导致误收敛，`3dfe337` 保留补偿循环安全因果链。独立规格与代码质量复评均通过。回归基础95项单测/架构+36项MySQL，最终告警修正8+2、查询鉴权修正74+1、循环日志修正9项单测分别全部通过；范围有重叠，不累加为去重总数。原始证据分别保存在 `/tmp/task5-final-gate2-reports`、`/tmp/task5-after-commit-green-reports`、`/tmp/task5-qnm-auth-green-reports`、`/tmp/task5-loop-cause-green-reports`。监控API/页面和迁移仍在后续任务中。

## Task 6：内部运营查询、重试、释放与审计

积压项级筛选已确认（2026-10-01）：定位存在匹配WAITING项的队列，展示整条队列数量与最早等待时间；同一等待项满足全部项级条件。详见设计§7。Task8两级审查关闭后，后端补齐提交`9f9e5d2`，规格与质量独立审查均通过：共享EXISTS仅定位队列，完整聚合不裁剪，GET/POST契约一致；72项逻辑/架构/真实MySQL/Contract全绿，来源`/tmp/frequency-backlog/green-manifest.json`。后端Task6代码范围完成；前端控件与说明仍在Task7实施，人工环境验收单列。

独立范围实施记录：`8f6861b` 提供监控与受控命令，`3b28e9d` 修正未知结果展示和脱敏幂等冲突定位，`42ff463` 为错误日志API增加独立失败计数与采样。当前独立范围规格、代码质量复评均通过；BACKLOGS待确认项不计完成。各次验证分别为基础86项单测/架构+10项MySQL+7项Contract、共享Web31+8Contract、风险投影31+2MySQL、展示/冲突修复31+3MySQL+9Contract、错误日志修复31+10Contract，均零失败/错误/跳过；重叠范围不累计。证据目录见集成文档§7.74。继续Task7已确定范围，最终完成门禁仍保留待确认项。

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyMonitorQueryService.java`、`RechargeFrequencyAdminCommandService.java`、`RechargeFrequencyActionAuditService.java`。
- 新建 `interfaceapi/admin/RechargeFrequencyAdminController.java`、`interfaceapi/admin/vo/RechargeFrequencyAdminVO.java`。
- 修改 `interfaceapi/admin/SupplyChainAdminPermissions.java`。
- 修改 API 模块 `src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`，登记稳定错误和RetryDirective。
- 新建资源 `src/main/resources/mapper/fulfillment/RechargeFrequencyMonitorMapper.xml` 与DAL Mapper，复杂聚合仅XML。
- 修改共享web框架 `ApiAccessLogPathUtils` 登记限频管理端敏感路径，以及 `GlobalExceptionHandler` 的敏感路径请求参数/校验与解析异常日志；影响评估限定明确敏感路径，普通路径行为保持既有契约。供应链ContractTest覆盖正常、非法JSON/手机号、存储故障的日志与错误记录不含正文/手机号。
- 修改 `ApiAccessLogInterceptor`：敏感路径在读取参数前分流，限频管理路径在MVC绑定前设置no-store，避免日志消费正文及错误响应缓存。
- 修改 `ApiAccessLogFilter`：限频路径的过滤器异常resultMsg及日志存储失败记录只保留安全类别/因果/调用位置，使用受控失败计数与采样；真实CacheRequestBodyFilter+访问日志Filter契约测试验证不消费正文或泄漏日志DTO。
- 新建共享 `web/apilog/core/util/SensitiveExceptionLogUtils`，复用安全异常类型与调用位置提取，不输出原message，限定既有敏感路径调用。
- 新建测试 `interfaceapi/admin/RechargeFrequencyAdminControllerTest.java`、`RechargeFrequencyAdminContractTest.java`、`border/fulfillment/RechargeFrequencyAdminIntegrationTest.java`。

- [x] 先测试GET statistics/waiting-orders/occupancies/backlogs、POST search、POST entries/{id}/retry、POST queues/{id}/release全部有独立授权；query不能替代retry/release，项目HMAC上下文不能调用。
- [x] 实现统计全表聚合、分页最大100、observedAt；展示脱敏手机号/冻结SPU，活跃按队列计，积压按WAITING项计。POST search body精确手机号，禁止URL/mobile模糊查询，响应no-store，搜索body不进入普通日志。
- [x] 所有命令获取内部登录用户，不接受客户端operatorId。actionNo全局唯一并记录请求指纹；同号异参冲突，同号同参返回原结果。
- [x] retry仅WAITING+正确entryVersion，持久化新唤醒代次后返回触发检查；不得跳过队首或重复建立Operation。
- [x] release校验holderOrderNo/generation/queueVersion及可信依据。PREPARED撤销资格并恢复原序号WAITING；可信结束置FINISHED，成功不足5秒拒绝提前释放，SEND_POSSIBLE未知拒绝。
- [x] 成功审计与命令事务同时保存；失败/冲突独立事务审计，保留因果异常。客户端actionNo并发冲突先回滚再新事务查原结果。
- [x] 用两线程验证旧版本释放不能清除新holder、重放命令不重做操作、拒绝审计不随回滚丢失；查询故障向客户端报错，不返回空集合。
- [x] Failsafe运行契约/真实审计事务测试，提交：`提供内部运营直充限频监控与受控操作接口`。

## Task 7：监控前端与部署权限

执行范围补充（2026-10-01）：依赖已通过两级审查的Task6独立接口范围；BACKLOGS口径现已确认，待后端补齐后启用项级控件，详见设计§7/§8。切换页签按适用契约传参，保留既有未知操作意图保护与分页/迟到响应处理；页面说明数值统计整条匹配队列。补齐范围继续以实际SFC探针、局部检查、构建及类型基线差异验证，不将真实登录人工验收冒充已执行。

Task7代码范围完成（2026-10-01）：后端`9f9e5d2`两级审查通过后，前端`2bdaf796`启用积压完整筛选及整队列说明，`d418afb`修复切换页签绕过ID校验及旧查询回填。最终规格与代码质量独立复评均通过；实际SFC/Router探针18项（包含原9项未知意图保护）全绿，UTC环境也全绿，两阶段先写测试分别RED6/RED3，旧SHA重现对应缺口。ESLint含CJS、Prettier、语法与dev构建通过，真实类型基线前后13349条逐条一致、改动文件零诊断；全仓类型检查仍失败，不能称通过。证据`/tmp/frequency-backlog-frontend/manifest.json`及`reviewfix/manifest.json`。真实登录UI、system SQL及部署节点操作保留人工门禁；代码完成不等于已部署。

独立范围实施记录：后端`b72eaf2`提供局部上海时间编码、实际应用Jackson字段契约和菜单；前端`1d50a11`提供真实监控/操作页面，`3b6cd8d`修复权限撤销与同路由更新丢失未知意图。规格及质量两级独立复评通过，真实SFC/Router探针9项通过，旧SHA复现7项失败。13项Contract+27项Architecture全零失败/错误/跳过；前端语法、ESLint、Prettier与构建通过。全仓类型检查仍失败：基线13350到13349，归一化无新增；最终修复前后13349精确无新增，新文件零报错。证据见集成文档§7.74。真实登录页面、system SQL实库执行及BACKLOGS待确认控件不计完成。

**前端文件（相对前端仓库）：**
- 新建 `src/api/order/rechargeFrequency.ts`：精确接口/类型，金额按已有分单位，不新增Mock。
- 修改共享 `src/config/axios/service.ts`：仅显式选择的限频调用保留安全业务code/message，不丢为字符串；敏感请求调试日志不打印配置/正文，其他调用及认证刷新契约保持不变。
- 新建 `src/views/supplier/order/recharge-frequency/index.vue`。
- 新建 `src/views/supplier/order/recharge-frequency/components/FrequencyActionDialog.vue`。
- 复用 `src/views/supplier/order/platform/detail/index.vue` 与 `src/api/order/index.ts`，不扩大资产权限。
- 新建轻量回归脚本 `scripts/recharge-frequency-intent-probe.cjs`：仅使用现有Vue/compiler-sfc/TypeScript和真实memory Router，编译实际SFC并验证权限撤销、同路由fullPath变化的未知意图保护，不新增测试框架、网络或敏感存储；该逻辑探针不替代真实登录页面联调。

**后端部署文件：**修改 `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`，菜单组件 `supplier/order/recharge-frequency/index`，组件名 `RechargeFrequencyMonitor`；三个权限独立，幂等插入。

HTTP字段对齐补充：共享LocalDateTime时间戳转换依赖JVM默认区域，与供应链固定上海时钟不一致，Task7在供应链infrastructure/time新增局部JSON时间序列化/反序列化器，显式注解到限频响应时间和搜索时间字段；不修改其他进程或共享配置。应用Jackson契约测试验证UTC/上海JVM下相同epoch与相同GET/POST筛选边界，前端上海展示。结果字段使用实际resultCode；Long主键/版本/代次保留number|string以支持现有安全数字/字符串序列化。

- [x] 先补后端HTTP契约测试覆盖前端消费字段/分页/操作返回，冻结TypeScript对应接口，不引入新的前端测试框架。
- [x] 页面顶部四卡全局统计，三页签等待/占用/积压，表格显示maskedMobile、SPU、归属、单号、时长、状态。筛选结果不改变“全局统计”口径。
- [x] 请求序号或AbortController阻止迟到响应覆盖当前页签/筛选；失败显示错误并标识旧数据过期，不能赋正常零值。
- [x] 未知意图承载弹窗持续挂载，不随query权限条件卸载；权限撤销仅停止新读取/操作。保护route leave和同路由query/hash/params update，覆盖AppView以fullPath为key的重新挂载；使用现有Vue/Router依赖实际行为探针验证，不新增测试框架或以源码字符串断言冒充行为测试。
- [x] reason必填，actionNo每次意图固定直至结果确定，等待项触发重试不承诺已发送；释放弹窗携带期望holder/generation/version，仅canRelease允许。未知状态提示查单核实，无force按钮。
- [x] 权限指令分别控制query/retry/release，详情单独校验既有订单权限；手机号不写URL、localStorage或复制明文。
- [x] 执行以下改动文件检查及构建，全仓类型既有错误与新文件错误分别记录：

```bash
pnpm exec eslint src/api/order/rechargeFrequency.ts src/views/supplier/order/recharge-frequency
pnpm exec prettier --check src/api/order/rechargeFrequency.ts src/views/supplier/order/recharge-frequency
pnpm ts:check
pnpm build:dev
```

- [x] 前后端代码及自动化行为验证完成，前端提交 `新增内部运营直充限频监控页面`、后端提交 `部署直充限频菜单与分离操作权限`，以及两次积压筛选补齐提交；实际SQL聚合由真实MySQL测试验证，组件探针不替代实库。
- [ ] 真实登录页面验收：筛选分页、SPU区分、三个权限组合、拒绝释放、旧响应、CAS冲突刷新、调用失败提示、统计超过200单仍准确；system菜单SQL实际首次/二次执行与角色授权记录。

## Task 8：存量迁移、全节点门禁与联调收尾

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyMigrationService.java`，只提供受控离线/部署任务入口，不开放公开API。
- 新建 `script/deploy/aifuli-test/sql/supply-chain-recharge-frequency-preflight.sql`，只读预检，用于列出非终态手机号直充候选和冲突。
- 新建测试 `border/fulfillment/RechargeFrequencyMigrationIntegrationTest.java`。
- 更新 `docs/集成测试.md` §7.74 和已确认设计的实现状态。

迁移细化依据为设计§10.1：新增独立Liquibase增量为队列项保存migration_acquired_at/migration_time_basis及DO注释，不能改048旧变更集；离线预检全候选再按可信受理时间+orderId稳定入队，已有冻结项复用/更早漏项拒绝。可靠解密与关联缺失、同单多未决操作拒绝，禁止旧购买人兜底。多单风险无任意holder，受控可信结果仅结束对应项并沿既有资金生命周期，全部风险结束且最大成功窗口到期后才由离线CAS清阻塞；未知/旧代次不放行。单风险无发送时间按迁移时钟持久化依据，不冒用QUERY/回调审计时间。阻塞队列超时只告警。全节点停止/版本/密钥部署检查须真实记录，未执行不得宣称通过。

发布风险补充：同增量新增sc_recharge_frequency_migration_gate单行CLOSED/READY、verified_artifact/identity_key_fingerprint/verified_at/version及Mapper/应用门禁，普通MOBILE入队和发送marker控制行前核对READY与启动key指纹。离线部署证据端口无默认通过，先真实证据委派，再独立短事务CLOSED，全候选覆盖与READY同事务提交；失败不回滚恢复旧READY。空库也需空候选核验；已完整冻结的blocked组合仍由自身禁SEND，不新增长期暂停政策。实际Liquibase确认51个变更集/121张表/291个时间列，旧50个SQL均未变化。

受理与密钥核验补充：claim在写回执前核对门禁，enqueue再次核对，临时关闭拒绝不留下受理副作用，READY后同单可重试；accepted幂等重放复用既有结果。已绑定指纹不可被不同当前密钥覆盖；首次绑定且已有活跃冻结项时，仅为密钥一致性核验可信target的HMAC与既有摘要，不重新分配身份/SPU/序号，无法核验或不符则保持关闭。

- [x] 先写已有多单可能外呼的迁移必须阻塞、历史无法可靠解密不能从旧购买人手机号回填的失败测试。
- [x] 实现以暂停新受理和SEND的真实部署证据为前置的离线扫描，恢复可信MOBILE目标与SPU，按可信受理顺序+orderId稳定入队；已发送或未知置SEND_POSSIBLE，无法证明未发不写PREPARED。同组合多可能处理中订单置blocked_reason并保留每单事实。实际环境暂停/接线仍在下项人工门禁。
- [x] 迁移不新建资金预占、账本或供应商请求；幂等重跑不改已冻结SPU/序号。以覆盖核验和全节点切换证据保护恢复受理；密钥缺失、节点旧版本或未处理迁移冲突不得绕过队列。
- [x] 执行供应链全部单测、契约/集成门禁与架构门禁，依照仓库AGENTS仅跳过供应链之外模块测试，禁止 `-DskipTests`。
- [ ] 联调覆盖全局跨项目/供应商同组合、不同SPU并发、5秒窗口、30分钟只告警、资金保留、PREPARED竞争、迟到回调、MANUAL核实、200单以上统计、权限/页面操作；将真实结果、环境和失败原因写入集成文档。
- [x] 核对原标准HTTP设计的报价依赖仍未被误称完成；限频发布不等于标准下单已可对外启用。
- [x] 后端提交迁移实现/审查修复及门禁记录，工作区保持仅本任务变更，汇报真实测试证据与尚未完成的环境验收。

Task8代码范围完成（2026-10-01）：`40a4aca`核心、`06a57f7`发送资格/只读查单恢复修复、`3806e86`全风险归属修复，最终规格与质量两级独立审查通过，无遗留P1/P2。完整全SC门禁在`06a57f7`：API117、server1237、Integration/Contract351，额外APIContract27；最终离线条件变更在`3806e86`补完整Migration25与逻辑/查询/架构46，分次不累计也不称最终SHA单次全量。来源见集成文档§7.74。真实部署证据接线、全节点操作、人工页面/system SQL仍未执行，以上代码完成不解除人工发版门禁。后续Task6/7已确认的BACKLOGS筛选现已完成并通过两级审查，整个限频变更最终代码审查亦通过。

## 计划自检与完成定义

优先级调整（2026-10-01）：用户明确要求将实际测试环境迁移、菜单部署、真实页面与供应商联调暂缓并降低优先级，已记录至`docs/待办列表.md`。本计划未勾选的人工环境验收依此延后；当前主线改为标准下单、查单、资产查询HTTP闭环。后续接口实现仍按已确认规则校验限频覆盖门禁，实际发版前恢复环境验收。

- [x] 设计§1所有已确认决策均对应Task 1/3/4/6；§3持久化对应Task 2；§4～6执行恢复对应Task 4/5；§7～9运营/前端/告警对应Task 5/6/7；§10迁移/门禁对应Task 8。
- [x] 新接口、枚举、字段注释与DDL意义一致，操作日志/审计不输出手机号、密钥或目标。
- [x] 每阶段均完成规格审查后再完成代码质量审查，无遗留阻断问题；代码/设计/联调文档同批对齐。
- [x] 真实MySQL/Failsafe成功证据齐全，前端改动文件检查/构建成功，人工未执行的页面验收不得标为已通过。

最终代码审查（2026-10-01）：独立检查后端`c069e3f..1770ade`与前端`7c5dbfc..d418afb`整期差异，结论Approved，无Critical/Important/Minor问题。覆盖受理、资金与执行资格、可信结果、恢复调度、迁移门禁、管理审计、日志与前端意图保护的跨阶段衔接，并核对实际测试XML与被测源码。后续文档收尾不修改代码，保留`codex/project-app-access`工作树。Task1～8代码范围完成；真实部署/登录UI/system SQL/供应商联调保留上述未勾选人工门禁，标准订单HTTP与报价适配仍属另外的未实施范围。
