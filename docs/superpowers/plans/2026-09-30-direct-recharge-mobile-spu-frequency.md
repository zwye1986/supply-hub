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
  -Dtest='RechargeFrequency*Test,FulfillmentTargetFieldsTest,SupplierOrderLifecycleTest,SupplyChainModuleArchitectureTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

真实 MySQL 与契约测试由 Failsafe 运行，不能用普通 surefire 的成功代替：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify \
  -Psupply-chain-integration \
  -Dtest='RechargeFrequency*Test,SupplyChainModuleArchitectureTest' \
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

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyIdentityService.java`、`RechargeFrequencyLifecycle.java`。
- 新建 `border/fulfillment/application/RechargeFrequencyAcceptanceCoordinator.java`，在事务外协调最多3次队列身份创建竞争重试；修改 `application/facade/BuyerFulfillmentCommandFacade.java` 的受理调用以委派协调入口，不访问DAL。
- 新建专用密钥解析端口/实现于 `infrastructure/security/RechargeFrequencyIdentityKeyProvider.java` 与 `ConfiguredRechargeFrequencyIdentityKeyProvider.java`。
- 新建 `tradecore/catalog/application/RechargeFrequencyProductResolver.java`，负责平台SKU→SPU身份及履约方式；不能把供应商商品当SPU。
- 修改 `border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`，在首次操作和资金预占同事务内入队；将所需目标身份随 PreparedAcceptance 传递，不从 buyerUserId 恢复。
- 新建测试 `border/fulfillment/application/RechargeFrequencyIdentityServiceTest.java`、`border/fulfillment/RechargeFrequencyAcceptanceIntegrationTest.java`。
- 修改新流程所依赖的既有测试上下文，显式导入新服务并提供测试专用密钥解析实现；不在生产配置填测试密钥。

- [ ] 先写不同前缀同摘要、不同手机号不同摘要、不合法号码拒绝、日志/DTO不输出明文、密钥缺失不绕过的失败测试。
- [ ] 专用HMAC-SHA256用途域 `RECHARGE_FREQUENCY_MOBILE`，32字节稳定密钥由密钥引用解析，不复用项目appKey，不随凭证轮换；摘要实现如下，Mac每次创建避免线程共享：

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(identityKeyProvider.keyBytes(), "HmacSHA256"));
mac.update(RechargeFrequencyConstants.HMAC_PURPOSE.getBytes(StandardCharsets.UTF_8));
mac.update((byte) 0);
byte[] digest = mac.doFinal(normalizedMobile.getBytes(StandardCharsets.UTF_8));
```

- [ ] 应用服务仅传递摘要、maskedMobile和冻结spuId。手机号仍从经过验证的实际目标取得，忽略非手机号直充/卡券；非法MOBILE失败关闭。
- [ ] 入队按orderId幂等；首次不存在QUEUE时普通插入由唯一键裁决，冲突整个事务回滚后重新读取；不在已失败事务继续业务。锁定控制行分配序号，新增WAITING项，同事务持久化唤醒意图。在既有首次Operation创建后、fundReservationService.freeze之前调用入队，统一队列→资金的锁序；不能在已锁资金账户后取得队列锁。
- [ ] 在协调入口仅捕获队列身份唯一键创建竞争的专用异常，每次重新调用Lifecycle事务代理，最多3次（集中常量），保留因果异常；其他完整性异常不重试/不吞掉。测试跨SKU并发首次入同队列双方均可正常受理，失败事务不存在资金/订单残留。
- [ ] 真实测试跨买家/项目同组合入同队列、同手机号不同SPU两队列、入队失败时资金/建单全回滚、重复受理不重复预占/入队。
- [ ] 运行 IdentityServiceTest 和 AcceptanceIntegrationTest 后提交：`按真实手机号与平台SPU原子入队并保留资金预占`。

## Task 4：FIFO认领、发送边界与可信终态推进

**文件：**
- 修改 `border/fulfillment/application/RechargeFrequencyLifecycle.java`。
- 修改 `border/fulfillment/application/FulfillmentOperationExecuteHandler.java`、`SupplierOrderLifecycle.java`。
- 新建 `border/fulfillment/application/RechargeFrequencyExecutionPermit.java`。
- 新建测试 `border/fulfillment/RechargeFrequencyExecutionIntegrationTest.java`、`RechargeFrequencyLifecycleIntegrationTest.java`；修改 `border/fulfillment/application/SupplierOrderLifecycleTest.java`。

- [ ] 定义执行资格快照，必须携带首次认领的代次和操作版本，旧执行者不允许重新读取新代次替换自己的资格：

```java
public record RechargeFrequencyExecutionPermit(long queueId, long entryId,
        String operationNo, long generation, long entryVersion) { }
```

- [ ] 先用真实MySQL并发测试：多个节点只能认领队首一项，同手机号不同SPU可并发；非队首正常返回等待、不调用适配器、不增加外呼失败次数。
- [ ] 完成同事务QUEUE+ENTRY+Operation认领，状态PREPARED。执行/结果/恢复锁序统一QUEUE→ENTRY→Request/Order→BOrder/Operation→既有资金服务，加锁后重新校验关联，不在结果处理入口取得商品限购写锁。
- [ ] 实现外呼前事务 PREPARED→SEND_POSSIBLE，条件包含首次资格全部字段。必须返回成功才执行适配器；失败返回稳定跳过结果。
- [ ] 加两线程屏障测试：人工撤销先提交则旧外呼0次；标记可能发送先提交则释放失败，节点失联仍保留占用。不靠sleep判定先后。
- [ ] 回调/查询统一事实收敛：ACCEPTED/UNKNOWN/MANUAL保持占用；可信成功待acquired+5秒；总订单可信失败释放；继续履约仍同订单持有。
- [ ] 针对已MANUAL操作补充受控可信事实恢复，复用原资金与观察写入，区别禁止重发与允许核实；无可信事实不能调用该入口。重复/相互矛盾事实保留审计，迟到事实不能清除新holder。
- [ ] 测试资金预占保留、成功只扣一次、明确失败只释放一次、重路由不更换队列、旧回调不会释放新代次。
- [ ] 执行定向真实MySQL与既有SupplierOrderLifecycleTest，提交：`接入FIFO直充执行与可信结果占用释放`。

## Task 5：持久化唤醒、成功窗口检查和补偿告警

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyWakeHandler.java`、`RechargeFrequencyRecoveryService.java`。
- 修改 `border/fulfillment/domain/FulfillmentTimerTaskTypes.java`，新增受控队列唤醒类型。
- 新建 `border/fulfillment/application/RechargeFrequencyRecoveryConfiguration.java`，注册受控定期补偿，不改变Timer全局行为。
- 新建测试 `border/fulfillment/RechargeFrequencyTimerIntegrationTest.java`、`border/fulfillment/application/RechargeFrequencyRecoveryServiceTest.java`。

- [ ] 写同代次重复安排只有一任务、上一代已完成仍能新唤醒、重启恢复队首、成功窗口不重置的失败测试。
- [ ] Task载荷限定queueId/wakeGeneration/payloadVersion=1；新意图增wakeGeneration并同时安排任务。幂等键使用 `queueId:wakeGeneration`，不使用明文手机号。

```java
String key = queue.getId() + ":" + queue.getWakeGeneration();
timer.scheduleOnce(FulfillmentTimerTaskTypes.RECHARGE_FREQUENCY_WAKE,
        key, payload, databaseClock.toInstant(fireAt));
```

- [ ] Handler只调用Lifecycle。检查holder：未知只计划下一次观察/告警，成功在minimumHoldUntil释放，无holder认领FIFO队首；不把等待当失败。
- [ ] 补偿扫描有next_check_at索引、批量上限、仅恢复缺失/死信唤醒；同队列有效未来任务不重复新增。扫描不直接删除holder，不使用Redis租约作为授权依据。
- [ ] 30分钟占用与积压>5按组合聚合告警，首次跨阈值记录、受控重复与恢复记录，日志仅队列ID/SPU/订单号，监控查询异常不伪装0值。
- [ ] 真实测试崩溃、死信补偿、窗口剩余时间、重复任务和UTC+8转换。提交：`实现直充队列Timer恢复与积压占用告警`。

## Task 6：内部运营查询、重试、释放与审计

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyMonitorQueryService.java`、`RechargeFrequencyAdminCommandService.java`、`RechargeFrequencyActionAuditService.java`。
- 新建 `interfaceapi/admin/RechargeFrequencyAdminController.java`、`interfaceapi/admin/vo/RechargeFrequencyAdminVO.java`。
- 修改 `interfaceapi/admin/SupplyChainAdminPermissions.java`。
- 修改 API 模块 `src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java`，登记稳定错误和RetryDirective。
- 新建资源 `src/main/resources/mapper/fulfillment/RechargeFrequencyMonitorMapper.xml` 与DAL Mapper，复杂聚合仅XML。
- 新建测试 `interfaceapi/admin/RechargeFrequencyAdminControllerTest.java`、`RechargeFrequencyAdminContractTest.java`、`border/fulfillment/RechargeFrequencyAdminIntegrationTest.java`。

- [ ] 先测试GET statistics/waiting-orders/occupancies/backlogs、POST search、POST entries/{id}/retry、POST queues/{id}/release全部有独立授权；query不能替代retry/release，项目HMAC上下文不能调用。
- [ ] 实现统计全表聚合、分页最大100、observedAt；展示脱敏手机号/冻结SPU，活跃按队列计，积压按WAITING项计。POST search body精确手机号，禁止URL/mobile模糊查询，响应no-store，搜索body不进入普通日志。
- [ ] 所有命令获取内部登录用户，不接受客户端operatorId。actionNo全局唯一并记录请求指纹；同号异参冲突，同号同参返回原结果。
- [ ] retry仅WAITING+正确entryVersion，持久化新唤醒代次后返回触发检查；不得跳过队首或重复建立Operation。
- [ ] release校验holderOrderNo/generation/queueVersion及可信依据。PREPARED撤销资格并恢复原序号WAITING；可信结束置FINISHED，成功不足5秒拒绝提前释放，SEND_POSSIBLE未知拒绝。
- [ ] 成功审计与命令事务同时保存；失败/冲突独立事务审计，保留因果异常。客户端actionNo并发冲突先回滚再新事务查原结果。
- [ ] 用两线程验证旧版本释放不能清除新holder、重放命令不重做操作、拒绝审计不随回滚丢失；查询故障向客户端报错，不返回空集合。
- [ ] Failsafe运行契约/真实审计事务测试，提交：`提供内部运营直充限频监控与受控操作接口`。

## Task 7：监控前端与部署权限

**前端文件（相对前端仓库）：**
- 新建 `src/api/order/rechargeFrequency.ts`：精确接口/类型，金额按已有分单位，不新增Mock。
- 新建 `src/views/supplier/order/recharge-frequency/index.vue`。
- 新建 `src/views/supplier/order/recharge-frequency/components/FrequencyActionDialog.vue`。
- 复用 `src/views/supplier/order/platform/detail/index.vue` 与 `src/api/order/index.ts`，不扩大资产权限。

**后端部署文件：**修改 `script/deploy/aifuli-test/sql/system-supply-chain-admin-menus.sql`，菜单组件 `supplier/order/recharge-frequency/index`，组件名 `RechargeFrequencyMonitor`；三个权限独立，幂等插入。

- [ ] 先补后端HTTP契约测试覆盖前端消费字段/分页/操作返回，冻结TypeScript对应接口，不引入新的前端测试框架。
- [ ] 页面顶部四卡全局统计，三页签等待/占用/积压，表格显示maskedMobile、SPU、归属、单号、时长、状态。筛选结果不改变“全局统计”口径。
- [ ] 请求序号或AbortController阻止迟到响应覆盖当前页签/筛选；失败显示错误并标识旧数据过期，不能赋正常零值。
- [ ] reason必填，actionNo每次意图固定直至结果确定，等待项触发重试不承诺已发送；释放弹窗携带期望holder/generation/version，仅canRelease允许。未知状态提示查单核实，无force按钮。
- [ ] 权限指令分别控制query/retry/release，详情单独校验既有订单权限；手机号不写URL、localStorage或复制明文。
- [ ] 执行以下改动文件检查及构建，全仓类型既有错误与新文件错误分别记录：

```bash
pnpm exec eslint src/api/order/rechargeFrequency.ts src/views/supplier/order/recharge-frequency
pnpm exec prettier --check src/api/order/rechargeFrequency.ts src/views/supplier/order/recharge-frequency
pnpm ts:check
pnpm build:dev
```

- [ ] 页面验收：筛选分页、SPU区分、三个权限组合、拒绝释放、旧响应、CAS冲突刷新、调用失败提示、统计超过200单仍准确。前端提交 `新增内部运营直充限频监控页面`；后端提交 `部署直充限频菜单与分离操作权限`。

## Task 8：存量迁移、全节点门禁与联调收尾

**文件：**
- 新建 `border/fulfillment/application/RechargeFrequencyMigrationService.java`，只提供受控离线/部署任务入口，不开放公开API。
- 新建 `script/deploy/aifuli-test/sql/supply-chain-recharge-frequency-preflight.sql`，只读预检，用于列出非终态手机号直充候选和冲突。
- 新建测试 `border/fulfillment/RechargeFrequencyMigrationIntegrationTest.java`。
- 更新 `docs/集成测试.md` §7.74 和已确认设计的实现状态。

- [ ] 先写已有多单可能外呼的迁移必须阻塞、历史无法可靠解密不能从旧购买人手机号回填的失败测试。
- [ ] 暂停新受理和SEND后扫描存量，恢复可信MOBILE目标与SPU，按可信受理顺序+orderId稳定入队；已发送或未知置SEND_POSSIBLE，无法证明未发不写PREPARED。同组合多可能处理中订单置blocked_reason并保留每单事实。
- [ ] 迁移不新建资金预占、账本或供应商请求；幂等重跑不改已冻结SPU/序号。数据库无状态冲突且全节点版本已切换才恢复受理；密钥缺失、节点旧版本或未处理迁移冲突不得绕过队列。
- [ ] 执行供应链全部单测、契约/集成门禁与架构门禁，依照仓库AGENTS仅跳过供应链之外模块测试，禁止 `-DskipTests`。
- [ ] 联调覆盖全局跨项目/供应商同组合、不同SPU并发、5秒窗口、30分钟只告警、资金保留、PREPARED竞争、迟到回调、MANUAL核实、200单以上统计、权限/页面操作；将真实结果、环境和失败原因写入集成文档。
- [ ] 核对原标准HTTP设计的报价依赖仍未被误称完成；限频发布不等于标准下单已可对外启用。
- [ ] 后端提交 `完成直充限频存量迁移门禁与集成验收记录`，前后端工作区清理至仅本任务变更，汇报真实测试证据与尚未完成的环境验收。

## 计划自检与完成定义

- [ ] 设计§1所有已确认决策均对应Task 1/3/4/6；§3持久化对应Task 2；§4～6执行恢复对应Task 4/5；§7～9运营/前端/告警对应Task 5/6/7；§10迁移/门禁对应Task 8。
- [ ] 新接口、枚举、字段注释与DDL意义一致，操作日志/审计不输出手机号、密钥或目标。
- [ ] 每阶段均完成规格审查后再完成代码质量审查，无遗留阻断问题；代码/设计/联调文档同批对齐。
- [ ] 真实MySQL/Failsafe成功证据齐全，前端改动文件检查/构建成功，人工未执行的页面验收不得标为已通过。
