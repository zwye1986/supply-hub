# INSTORE 项目化收银台支付闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让到店购订单按已认证采购项目固化收银台档案版本，事务外调用对应 SaaS 商城收银台，并让支付回调、主动查询和 Timer 使用原 `outTradeNo` 进入同一幂等收敛路径。

**Architecture:** `projectId` 是业务归属，`sourceSystem/externalTenantId` 只由订单固化的 `cashierProfileVersionId` 经 `BuyerIntegrationRouteResolver` 解析。INSTORE Lifecycle 只负责本地事务和 CAS，`InstoreChannelCommandFacade` 负责事务外收银台调用；现有 O2O 保留显式 legacy 适配入口，不允许 INSTORE 走无路由调用。

**Tech Stack:** Java 17、Spring Boot、MyBatis Plus、Liquibase、JUnit 5、Mockito、AssertJ、Testcontainers MySQL 8.4

---

### Task 1: 固化 INSTORE 项目与收银台档案版本

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/017-instore-project-cashier-route.sql`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/db.changelog-1.0.0.yaml`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelUserBindingDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/dataobject/InstoreChannelOrderDO.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelUserBindingMapper.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProjectCashierRouteMigrationIntegrationTest.java`

- [x] **Step 1: 写失败的真实 MySQL 迁移测试**

测试从空库执行 001→017，断言 `sc_instore_channel_user_binding.project_id`、`sc_instore_channel_order.project_id`、`cashier_profile_version_id` 均为非空列，并验证同一项目同一用户不能重复绑定、两个项目可以复用相同 `externalTenantId + buyerUserId`。

- [x] **Step 2: 运行迁移测试确认 RED**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=InstoreProjectCashierRouteMigrationIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: FAIL，原因是 017 和目标列尚不存在。

- [x] **Step 3: 增加 017 前向迁移与映射**

迁移先失败关闭已有无法确定项目归属的 INSTORE 行，再执行：

```sql
ALTER TABLE sc_instore_channel_user_binding
    ADD COLUMN project_id BIGINT NOT NULL COMMENT '采购项目内部主键，业务身份作用域' AFTER buyer_id,
    DROP INDEX uk_sc_instore_user_binding_tenant,
    ADD UNIQUE KEY uk_sc_instore_user_binding_project
        (project_id, buyer_user_id, channel_code);

ALTER TABLE sc_instore_channel_order
    ADD COLUMN project_id BIGINT NOT NULL COMMENT '受理时固化的采购项目内部主键' AFTER buyer_id,
    ADD COLUMN cashier_profile_version_id BIGINT NOT NULL
        COMMENT '受理时固化的收银台集成档案版本主键' AFTER project_id;
```

DO 与 Mapper 同步增加 `projectId/cashierProfileVersionId`，生产查询改为 `(projectId,buyerUserId,channelCode)`；`sourceSystem/externalTenantId` 继续作为不可变路由快照保留。

- [x] **Step 4: 运行迁移测试确认 GREEN**

Expected: 017 执行一次、第二次启动不重复执行，唯一约束行为符合断言。

- [x] **Step 5: 提交迁移切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/db/changelog \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/InstoreProjectCashierRouteMigrationIntegrationTest.java
git commit -m "feat(supply-chain): persist instore project cashier route"
```

### Task 2: 项目化 BuyerCashierPort 合同

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerCashierPort.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerCashierStubAdapter.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/O2OChannelCommandFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/o2o/application/O2OOperationRecoveryHandler.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerCashierPortContractTest.java`

- [x] **Step 1: 写失败的端口契约测试**

测试构造 CASHIER `BuyerIntegrationRoute`，断言：

```java
var command = new BuyerCashierPort.CreateCashierOrderCommand(
        route, "IOP_1", "IPF_1", "opaque-user", 1000L,
        "到店购", expiresAt);
assertThat(command.buyerUserId()).isInstanceOf(String.class);
assertThat(command.route()).isSameAs(route);
```

同时断言 `QueryPaymentCommand` 使用原 `outTradeNo`，`RefundCommand/QueryRefundCommand` 使用原 `refundCaseNo`，退款成功结果必须可携带 `actualRefundAmountCny`。

- [x] **Step 2: 运行测试确认 RED**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false -Dtest=BuyerCashierPortContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL，原因是现有端口仍使用 `Long buyerUserId` 且查询命令没有路由。

- [x] **Step 3: 实现路由必填的四命令合同**

目标方法：

```java
CreateCashierOrderResult createCashierOrder(CreateCashierOrderCommand command);
QueryPaymentResult queryPayment(QueryPaymentCommand command);
RefundResult refund(RefundCommand command);
QueryRefundResult queryRefund(QueryRefundCommand command);
```

四个命令均持有 `BuyerIntegrationRoute route`；创建命令的 `buyerUserId` 为 `String`；退款立即结果和查询结果都增加 `Long actualRefundAmountCny`。构造器校验 capability 必须为 `CASHIER`，业务号和用户标识不得为空。O2O 通过命名清晰的 deprecated legacy 方法继续使用 stub，避免本切片伪造 projectId 或 externalTenantId。

- [x] **Step 4: 运行端口合同测试与 O2O 回归测试确认 GREEN**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=BuyerCashierPortContractTest,O2OOperationRecoveryHandlerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 5: 提交端口切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/O2OChannelCommandFacade.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/o2o/application/O2OOperationRecoveryHandler.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/integration/buyer/BuyerCashierPortContractTest.java
git commit -m "refactor(supply-chain): route buyer cashier calls by project"
```

### Task 3: INSTORE 建单事务固化路由并返回收银台 URL

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstorePaymentFlowMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycleTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java`

- [x] **Step 1: 写 Lifecycle 路由快照与 CAS 失败测试**

`CreatePaymentOrderCommand` 改为接收 `projectId/cashierProfileVersionId` 和由 Resolver 得到的 SaaS 路由快照；测试断言订单头持久化这些值。另写测试要求 `claimPaymentPreparation` 成功后返回 `expectedVersion + 1`，且支付流水 CAS 更新影响行数不是 1 时抛 `ORDER_STATE_CONFLICT`。

- [x] **Step 2: 运行 Lifecycle 测试确认 RED**

Expected: FAIL，原因是快照字段和正确 CAS 版本尚未实现。

- [x] **Step 3: 实现本地事务与收银台结果持久化**

`recordCashierOrder` 在 CREATED 时同时写入 `cashier_url/cashier_reference`，Mapper 提供：

```java
int recordCashierOrder(Long id, long expectedVersion, short payStatus,
                       String cashierUrl, String cashierReference,
                       LocalDateTime updatedAt);
```

所有条件更新检查影响行数；`PreparedPayment` 增加 `projectId/cashierProfileVersionId/buyerUserId/subject/cashierUrl`，保证事务提交后的 Facade 不重新读取调用方身份。

- [x] **Step 4: 写并验证 Facade RED**

测试顺序：Lifecycle 建单 → Resolver 按固化 `projectId + profileVersionId + CASHIER` 解析 → Lifecycle 抢占 → 事务外调用 `BuyerCashierPort` → Lifecycle 记录结果。断言端口收到原始字符串 `buyerUserId`、原 `outTradeNo` 和解析出的 `externalTenantId`；幂等回放和抢占失败不重复外呼。

- [x] **Step 5: 实现 Facade 并确认 GREEN**

Facade 返回封闭结果：

```java
public record PaymentTicket(String orderNo, String outTradeNo,
                            String cashierUrl, short payStatus) {}
```

收银台抛异常时保留原业务号并返回本地处理中投影，不生成新号；日志记录路由摘要、业务号、状态和耗时，不打印凭证或会员敏感资料。

- [x] **Step 6: 提交建单闭环**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java
git commit -m "feat(supply-chain): create routed instore cashier orders"
```

### Task 4: 支付回调、渠道查询和 Timer 共用权威收敛路径

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerCashierCallbackFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreOperationRecoveryHandler.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstorePaymentConvergenceTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreOperationRecoveryHandlerTest.java`

- [x] **Step 1: 写失败的统一收敛测试**

覆盖：回调成功直接按 `outTradeNo` 收敛；本地非终态查询按订单固化版本解析路由并调用 `queryPayment`；SUCCEEDED 查询结果调用与回调相同的 `confirmBuyerPayment`；PENDING/UNKNOWN 不改终态；Timer 也把原 `outTradeNo` 交给同一查询服务。

- [x] **Step 2: 运行测试确认 RED**

Expected: FAIL，原因是现有 RecoveryHandler 只返回字符串动作且没有调用采购商收银台。

- [x] **Step 3: 实现统一查询与收敛**

新增内部方法：

```java
PaymentStatusView queryAndConvergePayment(String channelCode, String tradeId, String traceId);
void confirmPayment(String outTradeNo, long actualAmountCny,
                    String cashierOrderNo, String cashierTransactionNo,
                    String traceId);
```

两条入口最终都调用 `InstoreChannelLifecycle.confirmBuyerPayment`。查询只使用 `BuyerCashierPort.QueryPaymentCommand(route, outTradeNo)`，禁止创建新 `outTradeNo`；并发调用由既有支付状态和 CAS 幂等保护。

- [x] **Step 4: 运行收敛与 Lifecycle 回归测试确认 GREEN**

Run:

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false \
  -Dtest=InstorePaymentConvergenceTest,InstoreOperationRecoveryHandlerTest,InstoreChannelLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 5: 提交支付收敛切片**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application
git commit -m "feat(supply-chain): converge instore payment results"
```

### Task 5: 文档、架构门禁与完整验证

**Files:**
- Modify: `docs/integration/rtmart/供应链中台INSTORE到店购渠道接入实现总结.md`
- Modify: `docs/集成测试.md`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java`

- [x] **Step 1: 更新文档与 ArchitectureTest**

记录 017、项目化收银台合同、建单/查询/回调/Timer 的统一链路和仍未实现的退款账户返还；架构测试禁止 INSTORE Facade 直接访问集成档案 Mapper，并禁止把 `buyerUserId` 声明为 `Long`。

- [x] **Step 2: 运行供应链单测门禁**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 所有供应链 `*Test` 通过。

- [x] **Step 3: 运行真实 MySQL 门禁**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dit.test=InstoreProjectCashierRouteMigrationIntegrationTest,SupplyChainLiquibaseIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Expected: 两个测试使用独占空库执行 17 个 changeSet，第二次启动执行 0 个。

- [x] **Step 4: 检查差异和裸占位符**

```bash
git diff --check
rg -n "T[B]D|T[O]DO" \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/Instore* \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/integration/buyer/BuyerCashierPort.java
```

Expected: `git diff --check` 无输出；本切片新增代码无裸未完成标记。

- [x] **Step 5: 提交门禁记录**

```bash
git add docs/integration/rtmart/供应链中台INSTORE到店购渠道接入实现总结.md docs/集成测试.md \
  aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/architecture/SupplyChainModuleArchitectureTest.java
git commit -m "test(supply-chain): verify instore cashier payment gate"
```

## 后续切片入口

本计划完成后，下一计划实现 `BuyerMemberProfilePort` 与 INSTORE 首页/详情凭证转发，再实现退款串行、实际退款金额校验和 `BuyerAccountReturnService`；最后开发 `aifuli-channel-rtmart-server` 六个旅通协议端点。采购项目统一 appId/appKey 认证属于独立安全切片，业务 DTO 不临时增加可伪造的 `projectId` 字段。
