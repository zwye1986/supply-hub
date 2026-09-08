# Timer / Outbox 持久化基础设施修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以前向 `015` 迁移补齐 Outbox 乐观锁列，并修正 Timer 对象映射、执行序号与死信持久化，使 Timer、Outbox、Inbox 真实 MySQL 门禁恢复通过。

**Architecture:** 保留现有 Timer/Outbox 的短事务、version CAS、租约和 fencing 模型，不修改已发布的 `008–014`。先用 Liquibase 空库测试锁定缺列，再追加 `015`；随后以独占空库中的既有基础设施集成测试锁定 Timer 构造映射、执行序号和死信非空字段错误，以最小代码修复恢复原设计不变量。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Liquibase SQL、MySQL 8.4、JUnit 5、AssertJ、Testcontainers、Maven Failsafe。

---

## 范围与文件职责

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/015-timer-outbox-schema-repair.sql`：只增加 `sc_outbox_event.version`。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`：在 `014` 后注册 `015`。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/outbox/OutboxEventDO.java`：使 `version` 字段注释与 DDL COMMENT 一致。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/dal/dataobject/TimerTaskDO.java`：提供 MyBatis 属性映射所需的无参构造。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/dal/mysql/TimerTaskMapper.java`：死信时保留非空 `next_fire_at`。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/TimerClaimService.java`：claim 后使用递增后的执行次数，保证首次审计 `attempt_no=1`。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/TimerEngineLifecycle.java`：只在死信事务成功后记录终态日志。
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`：锁定空库 001→015 和 Outbox version 列。
- Modify: `docs/集成测试.md`：记录修复后的 Timer/Outbox 与全量供应链实际门禁结果。

### Task 1: 以前向 `015` 补齐 Outbox version

**Files:**

- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/015-timer-outbox-schema-repair.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/outbox/OutboxEventDO.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java`

- [x] **Step 1: 先增加缺列和变更集数量断言**

把 `columnNames` 断言扩为：

```java
assertThat(firstStartup.columnNames())
        .contains("sc_instore_channel_user_binding.source_system",
                "sc_instore_channel_user_binding.external_tenant_id",
                "sc_instore_channel_order.source_system",
                "sc_instore_channel_order.external_tenant_id",
                "sc_outbox_event.version");
assertThat(firstStartup.changeSetCount()).isEqualTo(15L);
```

- [x] **Step 2: 在独占空库运行测试并确认红灯**

先创建独占数据库 `supply_chain_codex_liquibase_015_red_20260820`，再运行：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SupplyChainLiquibaseIntegrationTest \
  -Dsupply-chain.test.jdbc-url='jdbc:mysql://127.0.0.1:3306/supply_chain_codex_liquibase_015_red_20260820?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false' \
  -Dsupply-chain.test.jdbc-username=root -Dsupply-chain.test.jdbc-password= \
  verify
```

Expected: FAIL；缺少 `sc_outbox_event.version`，且 changeSet 数量仍为 14。

- [x] **Step 3: 新增最小前向迁移**

`015-timer-outbox-schema-repair.sql` 内容固定为：

```sql
-- 表中文名：Outbox事件
-- 核心职责：补齐投递状态乐观锁，使数据库结构与 OutboxEventDO/Mapper 的 CAS 语义一致
ALTER TABLE sc_outbox_event
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0
        COMMENT '事件投递状态乐观锁版本号' AFTER published_at;
```

在 `db.changelog-1.0.0.yaml` 的 `014` 后增加：

```yaml
  - changeSet:
      id: 015-timer-outbox-schema-repair
      author: supply-chain
      changes:
        - sqlFile:
            path: 015-timer-outbox-schema-repair.sql
            relativeToChangelogFile: true
            encoding: UTF-8
            splitStatements: true
            stripComments: false
```

在 `OutboxEventDO.version` 上增加与 DDL 一致的字段注释：

```java
/** 事件投递状态乐观锁版本号。 */
@Version
private Long version;
```

- [x] **Step 4: 在新独占空库运行测试并确认绿灯**

使用新空库 `supply_chain_codex_liquibase_015_green_20260820` 重跑 Step 2 命令。

Expected: PASS；空库执行 15 个 changeSet，二次启动无新增变更，存在非空默认 `version` 列。

- [x] **Step 5: 校验旧迁移未变化并提交**

```bash
shasum -a 256 \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/008-timer-inbox-outbox.sql \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/013-instore-channel.sql \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/014-instore-production-invariants.sql
git diff --check
git add \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/015-timer-outbox-schema-repair.sql \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/outbox/OutboxEventDO.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/configuration/SupplyChainLiquibaseIntegrationTest.java
git commit -m "fix(supply-chain): add Outbox optimistic-lock column"
```

Expected: `008/013/014` 仅输出既有哈希，`git diff --check` 无输出，提交成功。

### Task 2: 修复 Timer `SELECT *` 构造映射

**Files:**

- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/dal/dataobject/TimerTaskDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/dal/mysql/TimerTaskMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/TimerClaimService.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/TimerEngineLifecycle.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/TimerOutboxInboxIntegrationTest.java`

- [x] **Step 1: 在含 `015` 的独占空库确认 Timer 红灯**

创建空库 `supply_chain_codex_timer_015_red_20260820`，运行：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=TimerOutboxInboxIntegrationTest \
  -Dsupply-chain.test.jdbc-url='jdbc:mysql://127.0.0.1:3306/supply_chain_codex_timer_015_red_20260820?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false' \
  -Dsupply-chain.test.jdbc-username=root -Dsupply-chain.test.jdbc-password= \
  verify
```

Expected: 第一阶段 FAIL，日志包含 `Unsupported conversion from LONG to java.time.LocalDateTime`，证明红灯精确指向 Timer 构造映射。增加无参构造后同一测试继续在任务完成断言红灯，数据库中任务停在 EXECUTING 且没有执行审计，证明 `attempt_no=0` 使完成事务回滚。

- [x] **Step 2: 增加无参属性映射构造**

在 `TimerTaskDO` 增加 Lombok import 和类注释：

```java
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 持久化定时任务数据对象。
 *
 * <p>必须保留无参构造，使 MyBatis 对 {@code SELECT *} 使用列名到属性名映射，
 * 禁止退化为依赖 DDL 列顺序的构造器映射。设计见
 * {@code docs/superpowers/specs/2026-08-20-timer-outbox-schema-repair-design.md#31-timer-映射}。</p>
 */
@TableName("sc_timer_task")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimerTaskDO {
```

字段、状态与 Mapper SQL 不作其它修改。

在 `TimerClaimService.claimBatch` 的 claim 成功分支使用递增后的执行次数：

```java
int claimedAttemptNo = task.getAttemptCount() + 1;
claimed.add(new ClaimedTask(task.getId(), task.getTaskNo(), task.getTaskType(),
        claimedAttemptNo, task.getFencingToken() + 1, task.getPayloadJson()));
```

该值必须与 `TimerTaskMapper.claim` 同一成功更新中的
`attempt_count = attempt_count + 1` 一致，不修改 DDL CHECK 或执行审计事务边界。

`TimerOutboxInboxIntegrationTest.applicationContext` 必须把测试参数作为命令行属性传入，
避免模块 `application.yaml` 覆盖测试的短扫描和退避设置：

```java
String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
return new SpringApplicationBuilder(TestConfiguration.class)
        .web(WebApplicationType.NONE).run(arguments);
```

在一次任务和失败任务断言中分别锁定审计序号：

```java
assertThat(jdbc.queryForObject(
        "SELECT attempt_no FROM sc_timer_execution WHERE task_id IN "
                + "(SELECT id FROM sc_timer_task WHERE task_type=? AND idempotency_key=?)",
        Integer.class, RecordingTimerHandler.TYPE, "T-1")).isEqualTo(1);
await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
    assertThat(flaky.count.get()).isEqualTo(3);
    assertThat(taskStatus(jdbc, FlakyTimerHandler.TYPE, "F-1")).isEqualTo((short) 60);
    assertThat(jdbc.queryForList(
            "SELECT attempt_no FROM sc_timer_execution WHERE task_id IN "
                    + "(SELECT id FROM sc_timer_task WHERE task_type=? AND idempotency_key=?) "
                    + "ORDER BY attempt_no",
            Integer.class, FlakyTimerHandler.TYPE, "F-1"))
            .containsExactly(1, 2, 3);
});
```

Handler 调用计数先于失败事务提交发生，因此第三次调用和数据库死信终态之间存在合法的短暂窗口；
失败任务的调用次数、终态和审计序号必须在同一个 Awaitility 断言中等待收敛。

独占空库红灯若显示第三次 Handler 已执行且日志称已死信，但数据库仍为 `EXECUTING(20)`、只存在
1/2 两条审计，检查 `next_fire_at` 的 DDL 非空约束。`finishAttempt` 只在重试时间非空时更新该列：

```java
.set(nextFireAt != null, TimerTaskDO::getNextFireAt, nextFireAt)
```

进入 `DEAD(60)` 时任务已被状态排除扫描，保留最后计划时间用于审计。`TimerEngineLifecycle` 必须先调用
`claimService.fail(...)` 提交死信状态与第三条执行审计，再打印 dead-lettered 日志。

- [x] **Step 3: 在新独占空库重跑并确认完整基础设施绿灯**

使用新空库 `supply_chain_codex_timer_015_green_20260820` 重跑 Step 1 命令。

Expected: PASS；一次任务以 `attempt_no=1` 完成、失败任务以 1/2/3 三次审计后死信、Outbox LOCAL 投递和 Inbox 去重全部通过；日志没有 Timer 类型转换、执行审计 CHECK 或 Outbox version 错误。

- [x] **Step 4: 提交 Timer 修复**

```bash
git diff --check
git add \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/dal/dataobject/TimerTaskDO.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/infrastructure/timer/TimerClaimService.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/infrastructure/TimerOutboxInboxIntegrationTest.java \
  docs/superpowers/specs/2026-08-20-timer-outbox-schema-repair-design.md \
  docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md \
  docs/superpowers/plans/2026-08-20-timer-outbox-schema-repair.md
git commit -m "fix(supply-chain): restore Timer task property mapping"
```

### Task 3: 重跑供应链门禁并更新联调记录

**Files:**

- Modify: `docs/集成测试.md`

- [x] **Step 1: 重跑 INSTORE 单元测试**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreChannelLifecycleTest,InstoreChannelUserBindingResolverTest,InstoreEntryCredentialServiceTest,InstoreOperationRecoveryHandlerTest,InstoreRefundSequencerTest,InstoreIdempotencyKeysTest,InstoreStatusEnumsTest,SupplyChainErrorMappingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 132 个测试通过，0 失败、0 错误、0 跳过。

- [x] **Step 2: 重跑 INSTORE 三个真实 MySQL 门禁**

显式创建三个独占空库：

```bash
mysql -uroot -e "
DROP DATABASE IF EXISTS supply_chain_codex_gate_liquibase_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_gate_instore_migration_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_gate_entry_credential_20260820;
CREATE DATABASE supply_chain_codex_gate_liquibase_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_gate_instore_migration_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_gate_entry_credential_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"
```

逐类运行：

```bash
for pair in \
  'SupplyChainLiquibaseIntegrationTest:supply_chain_codex_gate_liquibase_20260820' \
  'InstoreProductionInvariantsMigrationIntegrationTest:supply_chain_codex_gate_instore_migration_20260820' \
  'InstoreEntryCredentialIntegrationTest:supply_chain_codex_gate_entry_credential_20260820'
do
  test_class=${pair%%:*}
  test_database=${pair##*:}
  JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
    mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
    -Psupply-chain-integration \
    -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test="${test_class}" \
    -Dsupply-chain.test.jdbc-url="jdbc:mysql://127.0.0.1:3306/${test_database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false" \
    -Dsupply-chain.test.jdbc-username=root -Dsupply-chain.test.jdbc-password= \
    -Dsupply-chain.migration-test.jdbc-url="jdbc:mysql://127.0.0.1:3306/${test_database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false" \
    -Dsupply-chain.migration-test.jdbc-username=root -Dsupply-chain.migration-test.jdbc-password= \
    verify || exit 1
done
```

Expected: 三个测试全部通过。普通测试库和迁移测试库不共用。

- [x] **Step 3: 逐类使用独占空库运行全量 IntegrationTest**

显式创建九个独占空库：

```bash
mysql -uroot -e "
DROP DATABASE IF EXISTS supply_chain_codex_all_01_liquibase_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_02_instore_migration_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_03_entry_credential_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_04_timer_outbox_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_05_routing_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_06_fund_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_07_connector_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_08_master_20260820;
DROP DATABASE IF EXISTS supply_chain_codex_all_09_fulfillment_20260820;
CREATE DATABASE supply_chain_codex_all_01_liquibase_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_02_instore_migration_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_03_entry_credential_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_04_timer_outbox_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_05_routing_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_06_fund_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_07_connector_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_08_master_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE supply_chain_codex_all_09_fulfillment_20260820 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"
```

逐类运行全部九个 IntegrationTest：

```bash
for pair in \
  'SupplyChainLiquibaseIntegrationTest:supply_chain_codex_all_01_liquibase_20260820' \
  'InstoreProductionInvariantsMigrationIntegrationTest:supply_chain_codex_all_02_instore_migration_20260820' \
  'InstoreEntryCredentialIntegrationTest:supply_chain_codex_all_03_entry_credential_20260820' \
  'TimerOutboxInboxIntegrationTest:supply_chain_codex_all_04_timer_outbox_20260820' \
  'SupplierRoutingApplicationServiceIntegrationTest:supply_chain_codex_all_05_routing_20260820' \
  'FundReservationIntegrationTest:supply_chain_codex_all_06_fund_20260820' \
  'ConnectorPublicationIntegrationTest:supply_chain_codex_all_07_connector_20260820' \
  'MasterDataLifecycleIntegrationTest:supply_chain_codex_all_08_master_20260820' \
  'FulfillmentAcceptanceIntegrationTest:supply_chain_codex_all_09_fulfillment_20260820'
do
  test_class=${pair%%:*}
  test_database=${pair##*:}
  JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
    mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
    -Psupply-chain-integration \
    -Dtest=NoMatchingUnitTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dit.test="${test_class}" \
    -Dsupply-chain.test.jdbc-url="jdbc:mysql://127.0.0.1:3306/${test_database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false" \
    -Dsupply-chain.test.jdbc-username=root -Dsupply-chain.test.jdbc-password= \
    -Dsupply-chain.migration-test.jdbc-url="jdbc:mysql://127.0.0.1:3306/${test_database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false" \
    -Dsupply-chain.migration-test.jdbc-username=root -Dsupply-chain.migration-test.jdbc-password= \
    verify || exit 1
done
```

Expected: 仓库中全部供应链 IntegrationTest 逐类通过；不再出现固定业务编码冲突、Timer 类型转换或 Outbox version 错误。

- [x] **Step 4: 更新实际测试结果和排障结论**

把 `docs/集成测试.md` 中“全量门禁当前不得标记通过”的记录更新为实际修复结果：

- Liquibase 空库执行至 `015`；
- `TimerOutboxInboxIntegrationTest` 的四类基础设施行为通过；
- 写入实际 IntegrationTest 类数量、测试数量、失败/错误/跳过数量；
- 保留“外部 MySQL 每类独占空库”的执行要求。

- [x] **Step 5: 最终边界检查并提交**

```bash
git diff --check
shasum -a 256 \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/008-timer-inbox-outbox.sql \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/013-instore-channel.sql \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/014-instore-production-invariants.sql
git status --short
git add docs/集成测试.md
git commit -m "test(supply-chain): verify Timer Outbox infrastructure repair"
```

Expected: 只有联调文档和执行计划状态待提交，旧迁移哈希不变，提交后工作区干净。

## 完成标准

- `015` 是唯一数据库修复，`008–014` 不修改。
- `sc_outbox_event.version` 为 `BIGINT NOT NULL DEFAULT 0`，DTO/DDL 注释都表达“事件投递状态乐观锁版本号”。
- Timer 使用无参属性映射，不依赖 DDL 列顺序。
- `TimerOutboxInboxIntegrationTest` 在独占空 MySQL 中通过。
- 132 个 INSTORE/错误映射单元测试、3 个 INSTORE MySQL 门禁和全部供应链 IntegrationTest 通过。
- `docs/集成测试.md` 只记录实际执行结果，工作区最终干净。
