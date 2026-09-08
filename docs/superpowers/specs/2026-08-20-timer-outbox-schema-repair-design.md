# Timer / Outbox 持久化基础设施修复设计

## 1. 背景与问题证据

INSTORE 一次性入口凭证切片执行全量供应链 `IntegrationTest` 时，
`TimerOutboxInboxIntegrationTest` 在独占空 MySQL 数据库中稳定失败。失败链路与
INSTORE 业务表无关，根因分别位于 Timer 对象映射和 Outbox 表结构：

1. `TimerTaskDO` 只有 Lombok `@Builder` 生成的全参构造，没有无参构造。
   `TimerTaskMapper.selectClaimable` 使用 `SELECT *`，MyBatis 因此采用构造器映射；
   DDL 列顺序中 `version` 位于 `created_at/updated_at` 之前，而 Java 字段顺序把它放在最后，
   最终出现把 BIGINT `version` 转换为 `LocalDateTime` 的错误。
2. `OutboxEventDO` 和 `OutboxEventMapper` 已使用 `@Version` 与
   `WHERE id = ? AND version = ?` CAS，但已发布的 `008-timer-inbox-outbox.sql`
   没有创建 `sc_outbox_event.version`。读取时 Java 字段为空，claim 会在拆箱时失败；
   即使绕过拆箱，更新 SQL 也会引用不存在的列。
3. Timer 映射修正后，第一次任务能进入 Handler，但 `ClaimedTask.attemptNo` 仍取 claim 前的
   `attempt_count=0`。完成事务先把任务更新为 COMPLETED，再向 `sc_timer_execution` 插入
   `attempt_no=0`，违反 `attempt_no >= 1` CHECK，导致整个完成事务回滚，任务停在 EXECUTING。
4. 执行次数修正后，前两次失败能进入 RETRY 并追加审计，但第三次死信把
   `sc_timer_task.next_fire_at` 更新为 `NULL`，违反已发布 008 DDL 的非空约束；死信状态和第三条
   执行审计随事务一起回滚，任务再次停在 EXECUTING。原实现还在事务提交前打印“dead-lettered”，
   造成日志终态与数据库真相不一致。

这些问题会阻断 Timer 扫描、Outbox 投递以及依赖它们的支付/退款权威查询兜底，
必须在继续 SaaS 收银台接入前修复。

## 2. 方案比较与决策

### 2.1 采用方案：对象映射修正 + `015` 前向迁移

- 为 `TimerTaskDO` 增加 `@NoArgsConstructor/@AllArgsConstructor`，使 MyBatis 按属性名映射，
  不再依赖数据库列顺序。
- claim 成功后，`ClaimedTask.attemptNo` 固化为读取值加一，与同一 claim SQL 中的
  `attempt_count = attempt_count + 1` 保持一致；首次执行审计序号必须为 1。
- 失败可重试时更新 `next_fire_at`；进入死信终态时保留最后一次计划时间，禁止向非空列写入
  `NULL`。死信日志只在状态与执行审计事务成功提交后输出。
- 新增 `015-timer-outbox-schema-repair.sql`，为 `sc_outbox_event` 增加
  `version BIGINT NOT NULL DEFAULT 0 COMMENT '事件投递状态乐观锁版本号'`。
- 保留现有 Outbox 状态条件、claim 阶段的 version CAS，以及 claim 后的租约/fencing 保护；
  一次 claim、续租、成功与失败推进仍必须由合法持有者完成。
- 已发布的 `008–014` 文件只校验、不修改，避免 Liquibase checksum 漂移。

这是最小且与现有实现一致的修复：Timer 只调整对象构造方式，Outbox 只补齐 Java 模型已经依赖的列。

### 2.2 不采用：直接修改 `008`

修改 `008-timer-inbox-outbox.sql` 可以让新空库得到正确结构，但会改变已执行变更集 checksum，
导致已有环境启动失败，不符合“已发布迁移只追加”的发布原则。

### 2.3 不采用：删除 Outbox version CAS

只移除 `@Version` 和 Mapper 的 version 条件可以绕开缺列，但会削弱多节点投递的并发保护，
与一期详细设计中的短事务抢占、租约和 fencing 不变量冲突。

## 3. 结构与运行时语义

### 3.1 Timer 映射

`TimerTaskDO` 必须同时提供无参和全参构造。MyBatis 通过无参构造创建对象，再按
`task_no → taskNo` 等属性名映射；数据库列顺序不再影响 Java 类型。此修复不改变
`sc_timer_task` DDL、状态码、抢占顺序或执行语义。

`TimerClaimService.claimBatch` 在 claim SQL 成功后返回 `attemptNo = 原 attemptCount + 1`。
该值同时用于 Handler 上下文和 `sc_timer_execution.attempt_no`；它必须等于数据库原子递增后的
次数，满足 `(task_id, attempt_no)` 唯一键和 `attempt_no >= 1` CHECK。完成或失败事务中的执行审计
插入失败时继续整体回滚，禁止把审计降级为非原子旁路写入。

`sc_timer_task.next_fire_at` 是非空的调度与审计字段。失败可重试时将其更新为退避后的时间；
任务进入 `DEAD(60)` 后不再参与扫描，因此保留本次执行前的最后计划时间，不修改为 `NULL`。
死信状态、租约清理、错误码和第三条执行审计必须在同一本地事务提交；只有提交成功后才能打印
“dead-lettered”终态日志，禁止日志先于数据库真相。

### 3.2 Outbox 乐观锁

`sc_outbox_event.version` 初始值为 `0`。claim 使用 `id + expectedVersion + status` CAS；
claim 后的续租、开始投递、发布成功或失败推进继续使用 `fencingToken + leaseOwner + status`
保护当前持有者。上述每次状态更新都执行 `version = version + 1`，使下一轮扫描只能基于最新版本 claim；
旧 claim 或旧 fencing token 的更新影响行数为零，不得覆盖新持有者状态。
已有事件在迁移后统一获得初始版本 `0`，其业务键、状态、尝试次数和 fencing token 保持不变。

`015` 仅增加非空默认列，不回写事件业务数据，不修改唯一键、索引或历史事件载荷。

## 4. 数据库发布

Liquibase 顺序变为 `001 → … → 014 → 015`。生产环境继续使用部署前 Migration Job 和独立
DDL 身份；迁移时暂停 Timer/Outbox worker，迁移成功并发布包含映射修正的应用后再恢复 worker。
应用运行账号不获得 DDL 权限。

回退不删除 `version` 列。若应用发布需要回退，保留向后兼容的新增列并回退应用包；后续修复仍追加
新变更集。

## 5. 测试门禁

1. `SupplyChainLiquibaseIntegrationTest` 先增加
   `sc_outbox_event.version` 断言，并在没有 `015` 时红灯；实现后验证空库 001→015 和二次启动幂等。
2. `TimerOutboxInboxIntegrationTest` 作为真实行为红灯：独占空库下必须依次通过一次任务、重试死信、
   Outbox 投递和 Inbox 去重；首次执行审计 `attempt_no=1`，日志中不再出现构造转换、执行审计 CHECK
   或 version 空值错误。
   测试中的短扫描与退避参数必须以命令行属性启动 Spring；`SpringApplicationBuilder.properties`
   只是默认属性，会被模块 `application.yaml` 覆盖，不能用于缩短集成测试等待时间。
   Handler 调用计数发生在失败事务提交之前，第三次调用与数据库死信提交之间存在合法短暂窗口；
   测试必须等待 `status=60` 和 1/2/3 三条执行审计一起收敛，不能在调用计数到 3 后立即读取终态。
3. `013`、`014` 的 SHA-256 必须保持不变；INSTORE 三个 MySQL 门禁继续通过。
4. 无 Docker 时，全量 IntegrationTest 的每个测试类使用独占外部空库运行，避免固定测试业务编码
   在测试类之间互相污染；这只替代 Testcontainers 的数据库隔离，不改变测试断言。

## 6. 范围边界

本切片不改变 Timer/Outbox API、不新增消息中间件、不调整重试和退避策略，也不实现 SaaS 收银台、
支付回调或退款流程。它只恢复这些后续业务依赖的持久化执行基础设施，并清除全量集成门禁中的
确定性基础设施失败。
