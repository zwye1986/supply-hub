# INSTORE 退款串行与账户返还 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 INSTORE 退款资金闭环——串行受理与排队、事务提交后按原 `refundCaseNo` 外呼收银台退款、回调优先收敛、权威查询与 Timer 恢复兜底、实际退款金额校验、`BuyerAccountReturnService` 幂等账户返还、订单累计与售后投影。

**Architecture:** 镜像支付切片的分层——`InstoreChannelLifecycle` 每个方法独立本地事务；`InstoreChannelCommandFacade` 与 `InstoreRefundConvergenceService` 在事务外编排收银台网络调用；回调、接口⑥查询与 Timer 恢复共用同一条收敛路径。串行锁由 `uk_sc_instore_refund_active (payment_flow_id, buyer_refunding_flag)`（NULL 可重复语义）在数据库层兜底。`BuyerAccountReturnService` 是已扣采购资金返还唯一入口，只拥有账户和账本，不依赖 INSTORE。

**Tech Stack:** Java 17、Spring Boot、MyBatis Plus（LambdaWrapper）、Liquibase（本切片**无 DDL 变更**，全部表字段已就绪）、JUnit 5、Mockito、AssertJ、ArchUnit、外部 MySQL 8.4 集成测试

**Design baseline:** `docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md` 第 4.4/4.5/4.6/5.1 节（退款不变量 4/5/6/7/8、稳定错误语义、收银台编排第 4 条）

---

## 现状与差距（调查结论）

已具备：`acceptRefund` 受理幂等 + 订单级可退校验 + `QUEUED` Case；`createCancelRefundCase` 取消已支付转 `CANCEL_REFUND`（互斥校验已有）；`applyBuyerRefundResult` 骨架（状态推进 + 累计，无资金动作）；`BuyerCashierPort.refund/queryRefund` 契约与 Stub；`InstoreRefundSequencer` 排队号；错误码 `INSTORE_REFUND_AMOUNT_EXCEEDED/MISMATCH`、`INSTORE_ACCOUNT_RETURN_CONFLICT`、`INSTORE_REFUND_CASE_NOT_FOUND` 已注册。

缺失（本切片补齐）：
1. 在途金额校验（当前只减已成功累计，并发排队可超退）；受理未锁定订单头。
2. 串行锁未执行（`buyerRefundingFlag` 恒 NULL，`selectActiveByFlow` 无人调用）。
3. 受理后不外呼收银台；无回调入口；`queryRefund` 权威查询无人调用；Timer 恢复只输出字符串。
4. 退款成功无金额校验、无账户返还、无账本；`accountReturnAmount` 恒 NULL。
5. 售后投影恒 `NONE`；`activeRefundCaseId` 无人维护；终态后不派发下一个排队 Case。

## 文件结构与职责

```text
aifuli-module-supply-chain-server
├── tradecore/account
│   ├── application/BuyerAccountReturnService.java   # 新建：已扣资金返还唯一入口（REFUND_RETURN 幂等键）
│   └── dal/mysql/BuyerAccountMapper.java            # 修改：新增 atomicReturn
├── border/instore
│   ├── application
│   │   ├── InstoreChannelLifecycle.java             # 修改：受理串行化、结果收敛强化、派发下一个
│   │   ├── InstoreRefundConvergenceService.java     # 新建：退款统一收敛（回调/查询/Timer/外呼派发）
│   │   └── InstoreOperationRecoveryHandler.java     # 修改：BUYER_REFUND/REFUND_QUERY 接收敛
│   └── dal/mysql
│       ├── InstoreRefundCaseMapper.java             # 修改：在途查询/下一排队/抢占/完成
│       ├── InstoreChannelOrderMapper.java           # 修改：行锁/累计扩展/活动退款指针
│       └── InstoreOperationMapper.java              # 修改：通用抢占（PENDING→RUNNING）
├── border/order
│   ├── domain/InstoreRefundProjection.java          # 新建：售后投影封闭事实
│   ├── domain/InstoreOrderSummaryPolicy.java        # 修改：按退款投影售后状态
│   └── dal/mysql/SupplyChainOrderMapper.java        # 修改：售后状态 CAS 投影
└── application/facade
    ├── InstoreChannelCommandFacade.java             # 修改：acceptRefund/cancelPaymentFlow/queryAndConvergeRefund
    └── InstoreBuyerCashierCallbackFacade.java       # 修改：confirmRefund 退款回调
```

所有新重要类、公共方法和状态转换必须写中文职责/不变量注释，并链接
`docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md` 的对应章节锚点（`#45-生产事务与幂等不变量`、`#51-saas-商城收银台编排`）。禁止新增 Feign/HTTP 自调用；`InstoreChannelCommandApi` Wire 契约与 `aifuli-channel-rtmart-server` 不得夹带进本切片（与支付切片同口径，Facade 即联调 seam）。

---

### Task 1: BuyerAccountReturnService —— 已扣资金返还唯一入口

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/account/dal/mysql/BuyerAccountMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/account/application/BuyerAccountReturnService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore/account/application/BuyerAccountReturnServiceTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ACCOUNT_RETURN_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BuyerAccountReturnService 单测：永久幂等键、余额返还与 REFUND 账本语义。 */
@ExtendWith(MockitoExtension.class)
class BuyerAccountReturnServiceTest {

    @Mock private BuyerAccountMapper accountMapper;
    @Mock private AccountLedgerMapper ledgerMapper;
    @Mock private AccountLedgerWriter ledgerWriter;
    @InjectMocks private BuyerAccountReturnService service;

    private static BuyerAccountDO account(long balance) {
        return BuyerAccountDO.builder().id(9L).buyerId(5L).currency("CNY")
                .balance(balance).frozen(0L).status((short) 1).version(3L).build();
    }

    private BuyerAccountReturnService.ReturnCommand command(long amount) {
        return new BuyerAccountReturnService.ReturnCommand(5L, "IRC_123", amount,
                "SC_100", "INSTORE_ORDER", "ISC_200", "trace-1", "causation-1");
    }

    @Test
    void returnsFundsAndAppendsRefundLedger() {
        BuyerAccountDO account = account(1000L);
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account);
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(null);
        when(accountMapper.atomicReturn(eq(9L), eq(3L), eq(300L), any())).thenReturn(1);

        service.returnFunds(command(300L));

        ArgumentCaptor<AccountLedgerWriter.LedgerEntry> captor =
                ArgumentCaptor.forClass(AccountLedgerWriter.LedgerEntry.class);
        verify(ledgerWriter).append(captor.capture());
        AccountLedgerWriter.LedgerEntry entry = captor.getValue();
        assertThat(entry.entryKey()).isEqualTo("REFUND_RETURN:IRC_123");
        assertThat(entry.entryType()).isEqualTo("REFUND");
        assertThat(entry.balanceBefore()).isEqualTo(1000L);
        assertThat(entry.balanceAfter()).isEqualTo(1300L);
        assertThat(entry.frozenBefore()).isZero();
        assertThat(entry.frozenAfter()).isZero();
        assertThat(entry.orderNo()).isEqualTo("SC_100");
        assertThat(entry.referenceType()).isEqualTo("INSTORE_ORDER");
        assertThat(entry.referenceNo()).isEqualTo("ISC_200");
    }

    @Test
    void sameKeySameAmountReplayIsIdempotentNoop() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account(1300L));
        AccountLedgerDO existing = AccountLedgerDO.builder()
                .accountId(9L).entryKey("REFUND_RETURN:IRC_123").entryType("REFUND")
                .balanceDelta(300L).build();
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(existing);

        service.returnFunds(command(300L));

        verify(accountMapper, never()).atomicReturn(anyLong(), anyLong(), anyLong(), any());
        verify(ledgerWriter, never()).append(any());
    }

    @Test
    void sameKeyDifferentAmountThrowsAccountReturnConflict() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account(1300L));
        AccountLedgerDO existing = AccountLedgerDO.builder()
                .accountId(9L).entryKey("REFUND_RETURN:IRC_123").entryType("REFUND")
                .balanceDelta(200L).build();
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(existing);

        assertThatThrownBy(() -> service.returnFunds(command(300L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_ACCOUNT_RETURN_CONFLICT.getMsg());
        verify(ledgerWriter, never()).append(any());
    }

    @Test
    void missingAccountFails() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.returnFunds(command(300L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Buyer CNY account does not exist");
    }

    @Test
    void concurrentAccountUpdateFails() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account(1000L));
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(null);
        when(accountMapper.atomicReturn(eq(9L), eq(3L), eq(300L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.returnFunds(command(300L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent account update");
    }
}
```

- [x] **Step 2: 运行确认编译失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=BuyerAccountReturnServiceTest test
```

Expected: 编译错误 `BuyerAccountReturnService` / `atomicReturn` 不存在。

- [x] **Step 3: 实现 atomicReturn 与 BuyerAccountReturnService**

`BuyerAccountMapper` 追加（与 `atomicFreeze` 同构，`@Update` 属于规范第 8 条既有例外——原子资金更新必须单语句防并发）：

```java
/**
 * 原子返还已扣资金：balance 增加 amount，frozen 不变。REFUND 账本语义要求
 * balance_delta > 0 且 frozen_delta = 0（sc_account_ledger CHECK 约束）。
 */
@Update("UPDATE sc_buyer_account SET balance = balance + #{amount}, version = version + 1, "
        + "updated_at = #{now} WHERE id = #{accountId} AND version = #{expectedVersion}")
int atomicReturn(@Param("accountId") Long accountId, @Param("expectedVersion") long expectedVersion,
                 @Param("amount") long amount, @Param("now") LocalDateTime now);
```

`BuyerAccountReturnService`：

```java
package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ACCOUNT_RETURN_CONFLICT;

/**
 * 已扣采购资金返还的唯一入口：只拥有采购商账户与账本，不依赖 INSTORE 场景模块。
 *
 * <p>以 {@code REFUND_RETURN:{refundCaseNo}} 为账户范围内永久幂等键：锁定账户后先查账本既有流水，
 * 同键同金额幂等返回；同键不同金额抛 {@code INSTORE_ACCOUNT_RETURN_CONFLICT}（数据异常，整体回滚）。
 * 追加 REFUND 账本（balance_delta &gt; 0、frozen_delta = 0）。调用方（INSTORE Lifecycle）必须在外层
 * 本地事务中调用本服务，任一步失败整笔回滚。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 6 条。</p>
 */
@Service
@RequiredArgsConstructor
public class BuyerAccountReturnService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String ENTRY_TYPE_REFUND = "REFUND";
    private static final String ENTRY_KEY_PREFIX = "REFUND_RETURN:";

    private final BuyerAccountMapper accountMapper;
    private final AccountLedgerMapper ledgerMapper;
    private final AccountLedgerWriter ledgerWriter;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void returnFunds(ReturnCommand command) {
        validate(command);
        BuyerAccountDO account = accountMapper.selectByBuyerIdForUpdate(command.buyerId());
        if (account == null) {
            throw new IllegalStateException("Buyer CNY account does not exist");
        }
        String entryKey = ENTRY_KEY_PREFIX + command.refundCaseNo();
        AccountLedgerDO existing = ledgerMapper.selectByEntryKey(account.getId(), entryKey);
        if (existing != null) {
            if (existing.getBalanceDelta() == command.amountCny()) {
                return;
            }
            throw exception(INSTORE_ACCOUNT_RETURN_CONFLICT);
        }
        LocalDateTime now = now();
        if (accountMapper.atomicReturn(account.getId(), account.getVersion(),
                command.amountCny(), now) != 1) {
            throw new IllegalStateException("Concurrent account update");
        }
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(), entryKey,
                ENTRY_TYPE_REFUND, account.getBalance(), account.getBalance() + command.amountCny(),
                account.getFrozen(), account.getFrozen(), null, command.orderNo(),
                command.referenceType(), command.referenceNo(), null, null, null, null,
                command.traceId(), command.causationId(), now));
    }

    private static void validate(ReturnCommand command) {
        if (command.buyerId() == null || command.refundCaseNo() == null
                || command.refundCaseNo().isBlank() || command.amountCny() <= 0
                || command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Buyer account return command is invalid");
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 返还命令：refundCaseNo 构成永久幂等键；referenceType/referenceNo 由调用方按资金原始引用传入。 */
    public record ReturnCommand(Long buyerId, String refundCaseNo, long amountCny,
                                String orderNo, String referenceType, String referenceNo,
                                String traceId, String causationId) {
    }
}
```

- [x] **Step 4: 运行测试通过并提交**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=BuyerAccountReturnServiceTest test
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/tradecore
git commit -m "feat(supply-chain): add buyer account return service"
```

---

### Task 2: Mapper 扩展 —— 串行抢占、在途统计、累计与投影

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreRefundCaseMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreChannelOrderMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/dal/mysql/InstoreOperationMapper.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/dal/mysql/SupplyChainOrderMapper.java`

（Mapper 方法为声明式 LambdaWrapper，行为由 Task 4/5/9 的应用层单测与纵向集成测试覆盖；本任务以编译 + 既有测试回归验证。）

- [x] **Step 1: InstoreRefundCaseMapper 追加四个方法**

```java
/** 排队中(10)与退款中(20)的 Case；受理时按其总额做在途防超退校验。 */
default List<InstoreRefundCaseDO> selectInflightByFlow(Long paymentFlowId) {
    return selectList(new LambdaQueryWrapper<InstoreRefundCaseDO>()
            .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
            .in(InstoreRefundCaseDO::getStatus, List.of(InstoreRefundStatus.QUEUED.getCode(),
                    InstoreRefundStatus.BUYER_REFUNDING.getCode())));
}

/** 未收敛的 Case：排队中、退款中或人工；用于售后 PROCESSING 投影。 */
default List<InstoreRefundCaseDO> selectOpenByFlow(Long paymentFlowId) {
    return selectList(new LambdaQueryWrapper<InstoreRefundCaseDO>()
            .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
            .in(InstoreRefundCaseDO::getStatus, List.of(InstoreRefundStatus.QUEUED.getCode(),
                    InstoreRefundStatus.BUYER_REFUNDING.getCode(),
                    InstoreRefundStatus.MANUAL.getCode())));
}

/** 同一流水下一个排队中的 Case（按 refund_sequence 升序）；终态派发用。 */
default InstoreRefundCaseDO selectNextQueuedByFlow(Long paymentFlowId) {
    List<InstoreRefundCaseDO> queued = selectList(new LambdaQueryWrapper<InstoreRefundCaseDO>()
            .eq(InstoreRefundCaseDO::getPaymentFlowId, paymentFlowId)
            .eq(InstoreRefundCaseDO::getStatus, InstoreRefundStatus.QUEUED.getCode())
            .orderByAsc(InstoreRefundCaseDO::getRefundSequence));
    return queued.isEmpty() ? null : queued.getFirst();
}

/**
 * CAS 抢占派发：QUEUED(10) → BUYER_REFUNDING(20) 并置 buyer_refunding_flag=1。
 * uk_sc_instore_refund_active (payment_flow_id, buyer_refunding_flag) 在数据库层
 * 保证同一流水最多一个活动退款（NULL 在唯一键中互不冲突）。
 */
default int claimDispatch(Long id, long expectedVersion, LocalDateTime updatedAt) {
    return update(null, new LambdaUpdateWrapper<InstoreRefundCaseDO>()
            .set(InstoreRefundCaseDO::getStatus, InstoreRefundStatus.BUYER_REFUNDING.getCode())
            .set(InstoreRefundCaseDO::getBuyerRefundingFlag, (byte) 1)
            .set(InstoreRefundCaseDO::getUpdatedAt, updatedAt)
            .setSql("version = version + 1")
            .eq(InstoreRefundCaseDO::getId, id)
            .eq(InstoreRefundCaseDO::getVersion, expectedVersion)
            .eq(InstoreRefundCaseDO::getStatus, InstoreRefundStatus.QUEUED.getCode()));
}

/**
 * CAS 完成退款 Case：回写实际退款金额、账户返还金额、收银台参考号与原因码，
 * 并清空 buyer_refunding_flag 释放串行锁（唯一键中 NULL 可重复）。
 */
default int completeRefund(Long id, long expectedVersion, short status,
                           Long buyerActualRefundAmount, Long accountReturnAmount,
                           String cashierRefundReference, String resultReasonCode,
                           LocalDateTime updatedAt) {
    return update(null, new LambdaUpdateWrapper<InstoreRefundCaseDO>()
            .set(InstoreRefundCaseDO::getStatus, status)
            .set(buyerActualRefundAmount != null, InstoreRefundCaseDO::getBuyerActualRefundAmount,
                    buyerActualRefundAmount)
            .set(accountReturnAmount != null, InstoreRefundCaseDO::getAccountReturnAmount,
                    accountReturnAmount)
            .set(cashierRefundReference != null, InstoreRefundCaseDO::getCashierRefundReference,
                    cashierRefundReference)
            .set(resultReasonCode != null, InstoreRefundCaseDO::getResultReasonCode, resultReasonCode)
            .set(InstoreRefundCaseDO::getBuyerRefundingFlag, null)
            .set(InstoreRefundCaseDO::getUpdatedAt, updatedAt)
            .setSql("version = version + 1")
            .eq(InstoreRefundCaseDO::getId, id)
            .eq(InstoreRefundCaseDO::getVersion, expectedVersion));
}
```

`advanceStatus` 保留（`InstoreRefundStatus` 投影路径仍用）。

- [x] **Step 2: InstoreChannelOrderMapper 追加/修改**

```java
/** 行锁加载订单头：退款受理与收敛必须先锁详情，串行化在途金额校验（设计 4.5 第 4 条）。 */
default InstoreChannelOrderDO selectByIdForUpdate(Long id) {
    return selectOne(new LambdaQueryWrapper<InstoreChannelOrderDO>()
            .eq(InstoreChannelOrderDO::getId, id)
            .last("FOR UPDATE"));
}
```

`accumulateRefundAmounts` 签名扩展第三个 delta（唯一调用点是 `applyBuyerRefundResult`，随 Task 5 同步修改）：

```java
default int accumulateRefundAmounts(Long id, long expectedVersion,
                                    long channelRefundDelta, long buyerRefundDelta,
                                    long accountReturnDelta, LocalDateTime updatedAt) {
    return update(null, new LambdaUpdateWrapper<InstoreChannelOrderDO>()
            .setSql("total_channel_refund_amount = total_channel_refund_amount + " + channelRefundDelta)
            .setSql("total_buyer_refund_amount = total_buyer_refund_amount + " + buyerRefundDelta)
            .setSql("total_account_return_amount = total_account_return_amount + " + accountReturnDelta)
            .set(InstoreChannelOrderDO::getUpdatedAt, updatedAt)
            .setSql("version = version + 1")
            .eq(InstoreChannelOrderDO::getId, id)
            .eq(InstoreChannelOrderDO::getVersion, expectedVersion));
}

/** CAS 维护订单头活动退款指针；caseId 为 null 表示清空（当前 Case 已终态）。 */
default int setActiveRefundCase(Long id, long expectedVersion, Long refundCaseId,
                                LocalDateTime updatedAt) {
    return update(null, new LambdaUpdateWrapper<InstoreChannelOrderDO>()
            .set(InstoreChannelOrderDO::getActiveRefundCaseId, refundCaseId)
            .set(InstoreChannelOrderDO::getUpdatedAt, updatedAt)
            .setSql("version = version + 1")
            .eq(InstoreChannelOrderDO::getId, id)
            .eq(InstoreChannelOrderDO::getVersion, expectedVersion));
}
```

- [x] **Step 3: InstoreOperationMapper 通用抢占 + SupplyChainOrderMapper 售后投影**

`InstoreOperationMapper.claimPreorder` 重命名为通用方法（`claimPreorder` 保留为委托以不动支付路径）：

```java
/** CAS 抢占操作：待执行(0) → 执行中(10)；PREORDER 与 BUYER_REFUND 共用。 */
default int claimPendingExecution(Long id, long expectedVersion, LocalDateTime updatedAt) {
    return update(null, new LambdaUpdateWrapper<InstoreOperationDO>()
            .set(InstoreOperationDO::getStatus, (short) 10)
            .set(InstoreOperationDO::getUpdatedAt, updatedAt)
            .setSql("version = version + 1")
            .eq(InstoreOperationDO::getId, id)
            .eq(InstoreOperationDO::getVersion, expectedVersion)
            .eq(InstoreOperationDO::getStatus, (short) 0));
}

/** CAS 抢占 PREORDER：待执行(0) → 执行中(10)。 */
default int claimPreorder(Long id, long expectedVersion, LocalDateTime updatedAt) {
    return claimPendingExecution(id, expectedVersion, updatedAt);
}
```

`SupplyChainOrderMapper` 追加：

```java
/** CAS 投影售后状态并推进 detailVersion；退款 Case 每次状态变更后由 INSTORE Lifecycle 调用。 */
default int advanceAftersaleStatus(Long id, long expectedVersion, long nextVersion,
                                   short aftersaleStatus, LocalDateTime updatedAt) {
    return update(null, new LambdaUpdateWrapper<SupplyChainOrderDO>()
            .set(SupplyChainOrderDO::getAftersaleStatus, aftersaleStatus)
            .set(SupplyChainOrderDO::getDetailVersion, nextVersion)
            .set(SupplyChainOrderDO::getUpdatedAt, updatedAt)
            .eq(SupplyChainOrderDO::getId, id)
            .eq(SupplyChainOrderDO::getDetailVersion, expectedVersion));
}
```

- [x] **Step 4: 编译 + 既有测试回归 + 提交**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreChannelLifecycleTest,InstoreRefundSequencerTest,InstoreOperationRecoveryHandlerTest' test
```

Expected: 全部通过（`accumulateRefundAmounts` 旧签名调用点在 `applyBuyerRefundResult` 内——若本步编译报错，把该调用临时改为新签名传 `0L` 第三参，Task 5 再补全语义）。

```bash
git add -A aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border
git commit -m "feat(supply-chain): extend instore refund mappers for serialization"
```

---

### Task 3: InstoreOrderSummaryPolicy 售后投影

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/domain/InstoreRefundProjection.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/domain/InstoreOrderSummaryPolicy.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/order/domain/InstoreOrderSummaryPolicyTest.java`

售后状态码（`sc_supply_chain_order.aftersale_status` DDL CHECK）：0 无售后、10 处理中、20 部分退款、30 全部退款、90 人工处理。

- [x] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.order.domain.InstoreOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.InstoreRefundProjection;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryFact;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** INSTORE 售后投影单测：按在途 Case 与累计退款投影总表售后状态。 */
class InstoreOrderSummaryPolicyTest {

    private final InstoreOrderSummaryPolicy policy = new InstoreOrderSummaryPolicy();

    private OrderSummaryFact fact() {
        return new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_INSTORE,
                SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL,
                InstoreOrderStatus.COMPLETED.getCode(), 1L, 1L, null);
    }

    @Test
    void noRefundKeepsNone() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(false, 0L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
    }

    @Test
    void openRefundCaseProjectsProcessing() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(true, 400L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 10);
    }

    @Test
    void partialRefundProjectsPartiallyRefunded() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(false, 400L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 20);
        assertThat(decision.orderStatus()).isEqualTo((short) 30);
    }

    @Test
    void fullyRefundedProjectsFullyRefunded() {
        OrderSummaryDecision decision = policy.decide(fact(),
                new InstoreRefundProjection(false, 1000L, 1000L));
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 30);
    }

    @Test
    void nullProjectionKeepsLegacyNone() {
        OrderSummaryDecision decision = policy.decide(fact(), null);
        assertThat(decision.aftersaleStatus()).isEqualTo((short) 0);
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreOrderSummaryPolicyTest test
```

Expected: 编译错误 `InstoreRefundProjection` 不存在。

- [x] **Step 3: 实现投影**

`InstoreRefundProjection.java`：

```java
package com.aifuli.module.supplychain.border.order.domain;

/**
 * INSTORE 售后投影封闭事实：由 Lifecycle 从退款 Case 与订单头累计值组装后提交给
 * {@link InstoreOrderSummaryPolicy}。hasOpenRefund 覆盖排队中、退款中与人工 Case
 * （人工属于"退款协同进入人工处理"，投影为处理中）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#44-一期联调切片约束}（售后目标态）。
 */
public record InstoreRefundProjection(boolean hasOpenRefund,
                                      long totalChannelRefundAmount,
                                      long totalPaidAmount) {
}
```

`InstoreOrderSummaryPolicy` 追加重载（原 `decide(fact)` 不动，建单初始投影继续走它）：

```java
private static final short AFTERSALE_STATUS_PROCESSING = 10;
private static final short AFTERSALE_STATUS_PARTIALLY_REFUNDED = 20;
private static final short AFTERSALE_STATUS_FULLY_REFUNDED = 30;

/**
 * 带退款投影的裁决：主状态与业务阶段沿用 {@link #decide(OrderSummaryFact)}，
 * 售后按"在途优先，其次按累计/实付"投影。无在途且累计为 0 → NONE；
 * 有在途或人工 Case → PROCESSING；累计达实付 → FULLY_REFUNDED；其余正累计 → PARTIALLY_REFUNDED。
 */
public OrderSummaryDecision decide(OrderSummaryFact fact, InstoreRefundProjection refundProjection) {
    OrderSummaryDecision base = decide(fact);
    if (refundProjection == null) {
        return base;
    }
    short aftersale;
    if (refundProjection.hasOpenRefund()) {
        aftersale = AFTERSALE_STATUS_PROCESSING;
    } else if (refundProjection.totalChannelRefundAmount() > 0
            && refundProjection.totalChannelRefundAmount() >= refundProjection.totalPaidAmount()) {
        aftersale = AFTERSALE_STATUS_FULLY_REFUNDED;
    } else if (refundProjection.totalChannelRefundAmount() > 0) {
        aftersale = AFTERSALE_STATUS_PARTIALLY_REFUNDED;
    } else {
        aftersale = AFTERSALE_STATUS_NONE;
    }
    return new OrderSummaryDecision(base.orderStatus(), aftersale, base.businessStage());
}
```

- [x] **Step 4: 运行测试通过并提交**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreOrderSummaryPolicyTest test
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/domain aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/order/domain
git commit -m "feat(supply-chain): project instore aftersale from refund cases"
```

---

### Task 4: Lifecycle 受理强化 —— 在途校验、串行抢占与排队

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`（`acceptRefund`、`createCancelRefundCase`、`cancelPaymentFlow`）
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundAcceptanceTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_EXCEEDED;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.BUYER_REFUNDING;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款受理串行化单测：在途防超退、活动退款互斥、抢占派发与取消转退款。 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundAcceptanceTest {

    @Mock private InstoreRefundCaseMapper refundCaseMapper;
    @Mock private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock private InstoreChannelOrderMapper instoreOrderMapper;
    @Mock private InstoreOperationMapper operationMapper;
    @Mock private BusinessNoGenerator businessNoGenerator;
    @Mock private InstoreRefundSequencer refundSequencer;
    @InjectMocks private InstoreChannelLifecycle lifecycle;

    private static InstorePaymentFlowDO flow(long id) {
        return InstorePaymentFlowDO.builder().id(id).instoreOrderId(7L).orderId(11L)
                .channelCode("RT_MART").tradeId("T1").outTradeNo("OT1").payAmount(1000L)
                .payStatus(InstorePaymentFlowStatus.PAID.getCode()).version(0L).build();
    }

    private static InstoreChannelOrderDO order(long paid, long refunded) {
        return InstoreChannelOrderDO.builder().id(7L).orderId(11L).buyerId(5L)
                .instoreOrderNo("ISC_1").projectId(1L).cashierProfileVersionId(2L)
                .totalPaidAmount(paid).totalChannelRefundAmount(refunded)
                .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(0L).build();
    }

    private static InstoreRefundCaseDO caseDO(long id, short status, Long flag, long amount) {
        return InstoreRefundCaseDO.builder().id(id).refundCaseNo("IRC_" + id)
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R" + id).refundSequence(1)
                .refundType("CHANNEL_REFUND").channelRefundAmount(amount)
                .status(status).buyerRefundingFlag(flag).version(0L).build();
    }

    private void stubCommon() {
        lenient().when(paymentFlowMapper.selectByChannelTrade("RT_MART", "T1")).thenReturn(flow(3L));
        lenient().when(instoreOrderMapper.selectByIdForUpdate(7L)).thenReturn(order(1000L, 0L));
        lenient().when(refundSequencer.nextSequence(3L)).thenReturn(1);
        lenient().when(businessNoGenerator.next(any(String.class))).thenReturn("IRC_NEW");
    }

    private InstoreChannelLifecycle.AcceptRefundCommand acceptCommand(long amount) {
        return new InstoreChannelLifecycle.AcceptRefundCommand(
                "RT_MART", "R_NEW", "T1", "CHANNEL_REFUND", amount, "trace-1");
    }

    @Test
    void rejectsRefundExceedingPaidMinusInflightAmounts() {
        stubCommon();
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(null);
        // 已有排队中 300 + 已成功累计 200，可退 = 1000 - 200 - 300 = 500
        lenient().when(instoreOrderMapper.selectByIdForUpdate(7L)).thenReturn(order(1000L, 200L));
        when(refundCaseMapper.selectInflightByFlow(3L))
                .thenReturn(List.of(caseDO(1L, QUEUED.getCode(), null, 300L)));

        assertThatThrownBy(() -> lifecycle.acceptRefund(acceptCommand(501L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_REFUND_AMOUNT_EXCEEDED.getMsg());
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void queuesBehindActiveRefundWithoutDispatch() {
        stubCommon();
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(null);
        when(refundCaseMapper.selectInflightByFlow(3L)).thenReturn(List.of());
        when(refundCaseMapper.selectActiveByFlow(3L))
                .thenReturn(caseDO(1L, BUYER_REFUNDING.getCode(), (byte) 1, 300L));
        when(refundCaseMapper.insert(any(InstoreRefundCaseDO.class))).thenReturn(1);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(acceptCommand(300L));

        assertThat(outcome.claimedForDispatch()).isFalse();
        assertThat(outcome.refundStatus()).isEqualTo(QUEUED.getCode());
        verify(refundCaseMapper, never()).claimDispatch(anyLong(), anyLong(), any());
        verify(operationMapper, never()).insert(any(InstoreOperationDO.class));
    }

    @Test
    void claimsDispatchWhenNoActiveRefund() {
        stubCommon();
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(null);
        when(refundCaseMapper.selectInflightByFlow(3L)).thenReturn(List.of());
        when(refundCaseMapper.selectActiveByFlow(3L)).thenReturn(null);
        when(refundCaseMapper.insert(any(InstoreRefundCaseDO.class))).thenReturn(1);
        when(refundCaseMapper.claimDispatch(anyLong(), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(acceptCommand(300L));

        assertThat(outcome.claimedForDispatch()).isTrue();
        assertThat(outcome.refundStatus()).isEqualTo(BUYER_REFUNDING.getCode());
        assertThat(outcome.operationNo()).isNotBlank();
        verify(instoreOrderMapper).setActiveRefundCase(eq(7L), anyLong(), anyLong(), any());
        ArgumentCaptor<InstoreOperationDO> opCaptor = ArgumentCaptor.forClass(InstoreOperationDO.class);
        verify(operationMapper).insert(opCaptor.capture());
        assertThat(opCaptor.getValue().getOperationType()).isEqualTo("BUYER_REFUND");
        assertThat(opCaptor.getValue().getRefundCaseId()).isNotNull();
    }

    @Test
    void idempotentReplayOfQueuedCaseClaimsDispatch() {
        stubCommon();
        InstoreRefundCaseDO existing = caseDO(9L, QUEUED.getCode(), null, 300L);
        when(refundCaseMapper.selectByChannelRefundNo("RT_MART", "R_NEW")).thenReturn(existing);
        when(refundCaseMapper.selectActiveByFlow(3L)).thenReturn(null);
        when(refundCaseMapper.claimDispatch(eq(9L), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(acceptCommand(300L));

        assertThat(outcome.claimedForDispatch()).isTrue();
        assertThat(outcome.refundCaseNo()).isEqualTo("IRC_9");
        verify(refundCaseMapper, never()).insert(any(InstoreRefundCaseDO.class));
    }

    @Test
    void cancelPaidFlowClaimsCancelRefundDispatch() {
        when(paymentFlowMapper.selectByChannelTrade("RT_MART", "T1")).thenReturn(flow(3L));
        when(instoreOrderMapper.selectById(7L)).thenReturn(order(1000L, 0L));
        when(refundCaseMapper.selectByChannelRefundNo(eq("RT_MART"), any(String.class))).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, "CANCEL_REFUND")).thenReturn(null);
        when(refundCaseMapper.selectByFlowAndType(3L, "CHANNEL_REFUND")).thenReturn(null);
        when(refundSequencer.nextSequence(3L)).thenReturn(1);
        when(businessNoGenerator.next(any(String.class))).thenReturn("IRC_CANCEL");
        when(refundCaseMapper.insert(any(InstoreRefundCaseDO.class))).thenReturn(1);
        when(refundCaseMapper.selectActiveByFlow(3L)).thenReturn(null);
        when(refundCaseMapper.selectInflightByFlow(3L)).thenReturn(List.of());
        when(refundCaseMapper.claimDispatch(anyLong(), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
                new InstoreChannelLifecycle.CancelPaymentFlowCommand("RT_MART", "T1", "trace-1"));

        assertThat(result.convertedToRefund()).isTrue();
        assertThat(result.claimedForDispatch()).isTrue();
        assertThat(result.refundCaseNo()).isEqualTo("IRC_CANCEL");
    }
}
```

注意：`InstoreChannelLifecycle` 为 `@RequiredArgsConstructor`，`@InjectMocks` 按构造器注入所需 Mock；上方仅列出退款路径用到的依赖，其余依赖（`orderMapper`、`fundReservationService`、`outboxRepository`、`supplierOrderMapper`、`orderSummaryPolicyRegistry` 等）同样以 `@Mock` 声明（`lenient()` 避免无关用例触发 UnnecessaryStubbing）。

- [x] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreRefundAcceptanceTest test
```

Expected: 编译错误——`AcceptRefundOutcome`、`claimDispatch`、`selectInflightByFlow`、`CancelPaymentFlowResult.claimedForDispatch` 不存在。

- [x] **Step 3: 实现 Lifecycle 受理强化**

`InstoreChannelLifecycle` 修改点（保持方法级 `@Transactional`，类内已有依赖注入不变）：

1. 新增依赖字段：无（`refundCaseMapper`/`operationMapper`/`instoreOrderMapper`/`businessNoGenerator`/`refundSequencer` 均已注入）。

2. `acceptRefund` 返回类型 `String` → `AcceptRefundOutcome`，核心改为：

```java
/**
 * 退款受理（接口⑤联调切片）：锁定订单头后按"已成功累计 + 在途（排队中/退款中）"校验可退金额，
 * 落 QUEUED Case；同一支付流水无活动退款时抢占派发（QUEUED→BUYER_REFUNDING + 置活动标记 +
 * 订单头 activeRefundCaseId + BUYER_REFUND Operation），有活动退款时排队等待。
 * 幂等：同 (channelCode, channelRefundNo) 回放已有 Case；回放的 QUEUED Case 仍可被抢占派发。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 4 条。
 */
@Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
public AcceptRefundOutcome acceptRefund(AcceptRefundCommand command) {
    log.info("[INSTORE][lifecycle] acceptRefund 入口 channelCode={}, channelRefundNo={}, tradeId={}, refundType={}, refundAmount={}, traceId={}",
            command.channelCode(), command.channelRefundNo(), command.tradeId(),
            command.refundType(), command.channelRefundAmount(), command.traceId());
    InstoreRefundCaseDO existingCase = refundCaseMapper.selectByChannelRefundNo(
            command.channelCode(), command.channelRefundNo());
    if (existingCase != null) {
        log.info("[INSTORE][lifecycle] 退款幂等回放 channelRefundNo={}, refundCaseNo={}, status={}",
                command.channelRefundNo(), existingCase.getRefundCaseNo(), existingCase.getStatus());
        return claimOrQueue(existingCase, command.traceId());
    }
    InstorePaymentFlowDO flow = paymentFlowMapper.selectByChannelTrade(command.channelCode(), command.tradeId());
    if (flow == null) {
        throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
    }
    // 锁定订单头，串行化在途金额校验（并发受理第二个事务在前者提交后才能读到其 QUEUED Case）
    InstoreChannelOrderDO lockedOrder = instoreOrderMapper.selectByIdForUpdate(flow.getInstoreOrderId());
    if (lockedOrder == null) {
        throw exception(INSTORE_ORDER_NOT_FOUND);
    }
    long inflight = refundCaseMapper.selectInflightByFlow(flow.getId()).stream()
            .mapToLong(InstoreRefundCaseDO::getChannelRefundAmount).sum();
    long refundable = lockedOrder.getTotalPaidAmount()
            - lockedOrder.getTotalChannelRefundAmount() - inflight;
    if (command.channelRefundAmount() > refundable) {
        throw exception(INSTORE_REFUND_AMOUNT_EXCEEDED);
    }
    InstoreRefundType refundType;
    try {
        refundType = InstoreRefundType.valueOf(command.refundType());
    } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("Invalid instore refund type: " + command.refundType());
    }
    SupplyChainOrderDO order = orderMapper.selectById(flow.getOrderId());
    LocalDateTime now = now();
    int sequence = refundSequencer.nextSequence(flow.getId());
    String refundCaseNo = businessNoGenerator.next(BusinessNoPrefixes.IRC);
    InstoreRefundCaseDO refundCase = InstoreRefundCaseDO.builder()
            /* 原有 builder 内容不变，status=QUEUED，buyerRefundingFlag=null */
            .build();
    refundCaseMapper.insert(refundCase);
    log.info("[INSTORE][lifecycle] acceptRefund 完成 refundCaseNo={}, sequence={}", refundCaseNo, sequence);
    return claimOrQueue(refundCase, command.traceId());
}

/**
 * 无活动退款则抢占派发（CAS + 唯一键兜底），否则保持排队。
 * 抢占成功同时创建 RUNNING 状态的 BUYER_REFUND Operation（受理事务内即派发外呼，无需二次抢占）。
 */
private AcceptRefundOutcome claimOrQueue(InstoreRefundCaseDO refundCase, String traceId) {
    InstoreRefundCaseDO active = refundCaseMapper.selectActiveByFlow(refundCase.getPaymentFlowId());
    if (active != null && !active.getId().equals(refundCase.getId())) {
        return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), false, null, 0L,
                refundCase.getStatus());
    }
    if (refundCase.getStatus() != InstoreRefundStatus.QUEUED.getCode()) {
        // 已在退款中或已终态：回放当前状态，不重复抢占
        return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), false, null, 0L,
                refundCase.getStatus());
    }
    LocalDateTime now = now();
    if (refundCaseMapper.claimDispatch(refundCase.getId(), refundCase.getVersion(), now) != 1) {
        return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), false, null, 0L,
                refundCase.getStatus());
    }
    InstorePaymentFlowDO flow = paymentFlowMapper.selectById(refundCase.getPaymentFlowId());
    String operationNo = businessNoGenerator.next(BusinessNoPrefixes.IOP);
    InstoreOperationDO operation = InstoreOperationDO.builder()
            .operationNo(operationNo).instoreOrderId(refundCase.getInstoreOrderId())
            .paymentFlowId(refundCase.getPaymentFlowId()).refundCaseId(refundCase.getId())
            .supplierOrderId(null).operationType(InstoreOperationType.BUYER_REFUND.name())
            .idempotencyKey(InstoreIdempotencyKeys.buyerRefund(refundCase.getRefundCaseNo()))
            .requestFingerprint(refundCase.getRequestFingerprint()).externalOperationNo(null)
            .status(InstoreOperationStatus.RUNNING.getCode()).resultClass(null).attemptCount(0)
            .nextRecoverAt(null).responseDigest(null).version(0L)
            .createdAt(now).updatedAt(now).completedAt(null).build();
    operationMapper.insert(operation);
    InstoreChannelOrderDO order = instoreOrderMapper.selectById(refundCase.getInstoreOrderId());
    instoreOrderMapper.setActiveRefundCase(order.getId(), order.getVersion(), refundCase.getId(), now);
    log.info("[INSTORE][lifecycle] 退款抢占派发 refundCaseNo={}, operationNo={}",
            refundCase.getRefundCaseNo(), operationNo);
    return new AcceptRefundOutcome(refundCase.getRefundCaseNo(), true, operationNo, 1L,
            InstoreRefundStatus.BUYER_REFUNDING.getCode());
}
```

3. `createCancelRefundCase` 返回 `AcceptRefundOutcome`（替换原 `String`），落 Case 后同样调 `claimOrQueue`；`cancelPaymentFlow` 已支付分支改为：

```java
// 已支付：落 CANCEL_REFUND Case 并与接口⑤共用同一串行派发闭环
AcceptRefundOutcome outcome = createCancelRefundCase(flow, instoreOrder, order, now);
return new CancelPaymentFlowResult(flow.getOutTradeNo(), outcome.refundCaseNo(),
        true, outcome.claimedForDispatch(), outcome.operationNo());
```

4. record 定义更新：

```java
/** 退款受理结果：claimedForDispatch=true 时调用方必须在事务提交后按原 refundCaseNo 外呼收银台。 */
public record AcceptRefundOutcome(String refundCaseNo, boolean claimedForDispatch,
                                  String operationNo, long operationVersion, short refundStatus) {
}

/** 取消支付流水结果：未支付仅返回 outTradeNo；已支付返回退款 Case 与派发信息。 */
public record CancelPaymentFlowResult(String outTradeNo, String refundCaseNo,
                                       boolean convertedToRefund, boolean claimedForDispatch,
                                       String operationNo) {
}
```

5. 原有 `acceptRefund` 的调用方（如有测试内直接调用返回 `String` 的用例）同步改为断言 `AcceptRefundOutcome`。

- [x] **Step 4: 运行测试（新测试 + Lifecycle 全量回归）**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreRefundAcceptanceTest,InstoreChannelLifecycleTest' test
```

Expected: 全部通过；`InstoreChannelLifecycleTest` 中原 `acceptRefund`/`cancelPaymentFlow` 用例按新返回类型修正断言。

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application
git commit -m "feat(supply-chain): serialize instore refund acceptance"
```

---

### Task 5: Lifecycle 收敛强化 —— 金额校验、账户返还、累计、售后与派发下一个

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreChannelLifecycle.java`（`applyBuyerRefundResult`、新增 `projectRefundAftersale`/`claimNextQueuedRefund` 私有方法；新增依赖 `BuyerAccountReturnService`、`InstoreOrderSummaryPolicy`）
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundSettlementTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.InstoreOrderSummaryPolicy;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountReturnService;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.BUYER_REFUNDING;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.MANUAL;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.QUEUED;
import static com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款结果收敛单测：金额校验、账户返还、累计、售后投影与下一个排队派发。 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundSettlementTest {

    @Mock private InstoreRefundCaseMapper refundCaseMapper;
    @Mock private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock private InstoreChannelOrderMapper instoreOrderMapper;
    @Mock private SupplyChainOrderMapper orderMapper;
    @Mock private InstoreOperationMapper operationMapper;
    @Mock private BuyerAccountReturnService accountReturnService;
    @Mock private InstoreOrderSummaryPolicy summaryPolicy;
    @InjectMocks private InstoreChannelLifecycle lifecycle;

    private static InstoreRefundCaseDO caseDO(short status, Long flag) {
        return InstoreRefundCaseDO.builder().id(9L).refundCaseNo("IRC_9")
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R9").refundSequence(1)
                .refundType("CHANNEL_REFUND").channelRefundAmount(300L)
                .status(status).buyerRefundingFlag(flag).version(4L).build();
    }

    private void stubScope(InstoreRefundCaseDO refundCase, InstoreChannelOrderDO head,
                           SupplyChainOrderDO order) {
        lenient().when(refundCaseMapper.selectByRefundCaseNo("IRC_9")).thenReturn(refundCase);
        lenient().when(paymentFlowMapper.selectById(3L)).thenReturn(
                InstorePaymentFlowDO.builder().id(3L).instoreOrderId(7L).orderId(11L)
                        .channelCode("RT_MART").tradeId("T1").outTradeNo("OT1")
                        .payAmount(1000L).payStatus(InstorePaymentFlowStatus.PAID.getCode())
                        .version(0L).build());
        lenient().when(instoreOrderMapper.selectById(7L)).thenReturn(head);
        lenient().when(orderMapper.selectById(11L)).thenReturn(order);
    }

    private InstoreChannelOrderDO head(long paid, long refunded) {
        return InstoreChannelOrderDO.builder().id(7L).orderId(11L).buyerId(5L)
                .instoreOrderNo("ISC_1").projectId(1L).cashierProfileVersionId(2L)
                .totalPaidAmount(paid).totalChannelRefundAmount(refunded)
                .totalBuyerRefundAmount(0L).totalAccountReturnAmount(0L)
                .activeRefundCaseId(9L)
                .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(6L).build();
    }

    private SupplyChainOrderDO order() {
        return SupplyChainOrderDO.builder().id(11L).orderNo("SC_1").orderType("INSTORE")
                .orderStatus((short) 30).aftersaleStatus((short) 0).detailVersion(2L).build();
    }

    @Test
    void successReturnsFundsAccumulatesAndProjectsAftersale() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(SUCCESS.getCode()),
                eq(300L), eq(300L), eq("CRF_1"), eq((String) null), any())).thenReturn(1);
        when(instoreOrderMapper.accumulateRefundAmounts(eq(7L), eq(6L), eq(300L), eq(300L),
                eq(300L), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of());
        when(summaryPolicy.decide(any(), any())).thenReturn(
                new com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision(
                        (short) 30, (short) 20, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 20), any()))
                .thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1");

        assertThat(outcome.amountMismatch()).isFalse();
        assertThat(outcome.finalStatus()).isEqualTo(SUCCESS.getCode());
        ArgumentCaptor<BuyerAccountReturnService.ReturnCommand> returnCaptor =
                ArgumentCaptor.forClass(BuyerAccountReturnService.ReturnCommand.class);
        verify(accountReturnService).returnFunds(returnCaptor.capture());
        assertThat(returnCaptor.getValue().refundCaseNo()).isEqualTo("IRC_9");
        assertThat(returnCaptor.getValue().amountCny()).isEqualTo(300L);
        assertThat(returnCaptor.getValue().orderNo()).isEqualTo("SC_1");
        assertThat(returnCaptor.getValue().referenceNo()).isEqualTo("ISC_1");
        verify(instoreOrderMapper).setActiveRefundCase(eq(7L), eq(7L), eq(null), any());
        verify(instoreOrderMapper).accumulateRefundAmounts(eq(7L), eq(6L), eq(300L), eq(300L), eq(300L), any());
    }

    @Test
    void amountMismatchGoesManualWithoutReturnOrAccumulation() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(MANUAL.getCode()),
                eq(null), eq(null), eq("CRF_2"), eq("REFUND_AMOUNT_MISMATCH"), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of(refundCase));
        when(summaryPolicy.decide(any(), any())).thenReturn(
                new com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision(
                        (short) 30, (short) 10, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 10), any()))
                .thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 200L, "CRF_2", null, "trace-1");

        assertThat(outcome.amountMismatch()).isTrue();
        assertThat(outcome.finalStatus()).isEqualTo(MANUAL.getCode());
        verify(accountReturnService, never()).returnFunds(any());
        verify(instoreOrderMapper, never()).accumulateRefundAmounts(anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void nullActualAmountGoesManual() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L), eq(MANUAL.getCode()),
                eq(null), eq(null), eq(null), eq("REFUND_AMOUNT_MISMATCH"), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of(refundCase));
        when(summaryPolicy.decide(any(), any())).thenReturn(
                new com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision(
                        (short) 30, (short) 10, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 10), any()))
                .thenReturn(1);
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(null);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", null, null, null, "trace-1");

        assertThat(outcome.amountMismatch()).isTrue();
        verify(accountReturnService, never()).returnFunds(any());
    }

    @Test
    void failedReleasesLockAndDispatchesNextQueuedCase() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());
        when(refundCaseMapper.completeRefund(eq(9L), eq(4L),
                eq(com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.FAILED.getCode()),
                eq(null), eq(null), eq(null), eq("REJECTED"), any())).thenReturn(1);
        when(refundCaseMapper.selectOpenByFlow(3L)).thenReturn(List.of());
        when(summaryPolicy.decide(any(), any())).thenReturn(
                new com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision(
                        (short) 30, (short) 0, "STAGE"));
        when(orderMapper.advanceAftersaleStatus(eq(11L), eq(2L), eq(3L), eq((short) 0), any()))
                .thenReturn(1);
        InstoreRefundCaseDO next = InstoreRefundCaseDO.builder().id(10L).refundCaseNo("IRC_10")
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R10").refundSequence(2)
                .refundType("CHANNEL_REFUND").channelRefundAmount(200L)
                .status(QUEUED.getCode()).buyerRefundingFlag(null).version(0L).build();
        when(refundCaseMapper.selectNextQueuedByFlow(3L)).thenReturn(next);
        when(refundCaseMapper.claimDispatch(eq(10L), eq(0L), any())).thenReturn(1);

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "FAILED", null, null, "REJECTED", "trace-1");

        assertThat(outcome.finalStatus())
                .isEqualTo(com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus.FAILED.getCode());
        assertThat(outcome.nextClaimedRefundCaseNo()).isEqualTo("IRC_10");
        verify(instoreOrderMapper).setActiveRefundCase(eq(7L), eq(7L), eq(10L), any());
    }

    @Test
    void terminalCaseReplayIsIdempotentNoop() {
        InstoreRefundCaseDO refundCase = caseDO(SUCCESS.getCode(), null);
        stubScope(refundCase, head(1000L, 300L), order());

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1");

        assertThat(outcome.finalStatus()).isEqualTo(SUCCESS.getCode());
        verify(accountReturnService, never()).returnFunds(any());
        verify(refundCaseMapper, never()).completeRefund(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyShort(), any(), any(), any(), any(), any());
    }

    @Test
    void processingKeepsCaseInFlightWithoutSideEffects() {
        InstoreRefundCaseDO refundCase = caseDO(BUYER_REFUNDING.getCode(), (byte) 1);
        stubScope(refundCase, head(1000L, 0L), order());

        InstoreChannelLifecycle.RefundSettlementOutcome outcome =
                lifecycle.applyBuyerRefundResult("IRC_9", "PROCESSING", null, null, null, "trace-1");

        assertThat(outcome.finalStatus()).isEqualTo(BUYER_REFUNDING.getCode());
        verify(accountReturnService, never()).returnFunds(any());
        verify(refundCaseMapper, never()).completeRefund(anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.anyShort(), any(), any(), any(), any(), any());
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreRefundSettlementTest test
```

Expected: 编译错误——`RefundSettlementOutcome`、`applyBuyerRefundResult` 新签名不存在。

- [x] **Step 3: 实现收敛强化**

`applyBuyerRefundResult` 重写（签名加 `resultReasonCode`、返回 `RefundSettlementOutcome`）：

```java
/**
 * 退款结果收敛（回调与权威查询共用）：终态幂等回放；SUCCESS 时实际金额必须等于申请金额，
 * 缺失或不一致转 MANUAL 且不返还、不累计；金额一致时在外层事务内调用
 * {@link BuyerAccountReturnService} 返还采购商账户，随后推进 Case 终态、订单累计、
 * 清活动指针、投影售后状态并抢占下一个排队 Case（串行锁语义：终态即释放，队列继续）。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 6/7 条。
 */
@Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
public RefundSettlementOutcome applyBuyerRefundResult(String refundCaseNo, String refundStatus,
                                                       Long buyerActualRefundAmount,
                                                       String cashierRefundReference,
                                                       String resultReasonCode, String traceId) {
    log.info("[INSTORE][lifecycle] applyBuyerRefundResult 入口 refundCaseNo={}, refundStatus={}, actualAmount={}, cashierRefundReference={}, traceId={}",
            refundCaseNo, refundStatus, buyerActualRefundAmount, cashierRefundReference, traceId);
    InstoreRefundCaseDO refundCase = refundCaseMapper.selectByRefundCaseNo(refundCaseNo);
    if (refundCase == null) {
        log.warn("[INSTORE][lifecycle] 退款结果找不到 Case refundCaseNo={}", refundCaseNo);
        throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
    }
    if (isTerminalRefundStatus(refundCase.getStatus())) {
        log.info("[INSTORE][lifecycle] 退款终态幂等回放 refundCaseNo={}, status={}",
                refundCaseNo, refundCase.getStatus());
        return new RefundSettlementOutcome(refundCase.getStatus(), false, false, null, null, 0L);
    }
    InstorePaymentFlowDO flow = paymentFlowMapper.selectById(refundCase.getPaymentFlowId());
    if (flow == null) {
        throw exception(INSTORE_PAYMENT_FLOW_NOT_FOUND);
    }
    InstoreChannelOrderDO instoreOrder = instoreOrderMapper.selectById(refundCase.getInstoreOrderId());
    if (instoreOrder == null) {
        throw exception(INSTORE_ORDER_NOT_FOUND);
    }
    SupplyChainOrderDO order = orderMapper.selectById(refundCase.getOrderId());
    InstoreRefundStatus next = mapRefundStatus(refundStatus);
    LocalDateTime now = now();
    switch (next) {
        case SUCCESS -> {
            boolean mismatch = buyerActualRefundAmount == null
                    || buyerActualRefundAmount != refundCase.getChannelRefundAmount();
            if (mismatch) {
                return settleManual(refundCase, flow, instoreOrder, order,
                        cashierRefundReference, traceId, now);
            }
            accountReturnService.returnFunds(new BuyerAccountReturnService.ReturnCommand(
                    instoreOrder.getBuyerId(), refundCase.getRefundCaseNo(), buyerActualRefundAmount,
                    order.getOrderNo(), FundReferenceType.INSTORE_ORDER.code(),
                    instoreOrder.getInstoreOrderNo(), traceId, refundCase.getRefundCaseNo()));
            if (refundCaseMapper.completeRefund(refundCase.getId(), refundCase.getVersion(),
                    SUCCESS.getCode(), buyerActualRefundAmount, buyerActualRefundAmount,
                    cashierRefundReference, null, now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            if (instoreOrderMapper.accumulateRefundAmounts(instoreOrder.getId(), instoreOrder.getVersion(),
                    refundCase.getChannelRefundAmount(), buyerActualRefundAmount,
                    buyerActualRefundAmount, now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            return settleTerminal(refundCase, flow, instoreOrder, order, SUCCESS.getCode(),
                    false, cashierRefundReference, traceId, now);
        }
        case FAILED -> {
            if (refundCaseMapper.completeRefund(refundCase.getId(), refundCase.getVersion(),
                    InstoreRefundStatus.FAILED.getCode(), null, null,
                    cashierRefundReference, resultReasonCode, now) != 1) {
                throw exception(ORDER_STATE_CONFLICT);
            }
            return settleTerminal(refundCase, flow, instoreOrder, order,
                    InstoreRefundStatus.FAILED.getCode(), false,
                    cashierRefundReference, traceId, now);
        }
        default -> {
            // PROCESSING：仍在收银台处理，保持 BUYER_REFUNDING，等待回调或 Timer 权威查询
            return new RefundSettlementOutcome(refundCase.getStatus(), false, false, null, null, 0L);
        }
    }
}

/** 金额缺失/不一致：转人工，不返还不累计；售后投影保持处理中（人工属于未收敛协同）。 */
private RefundSettlementOutcome settleManual(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                             InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                             String cashierRefundReference, String traceId,
                                             LocalDateTime now) {
    log.warn("[INSTORE][lifecycle] 退款实际金额与申请不一致转人工 refundCaseNo={}, applied={}, actual={}",
            refundCase.getRefundCaseNo(), refundCase.getChannelRefundAmount(),
            refundCase.getBuyerActualRefundAmount());
    if (refundCaseMapper.completeRefund(refundCase.getId(), refundCase.getVersion(),
            MANUAL.getCode(), null, null, cashierRefundReference,
            InstoreRejectionCode.REFUND_AMOUNT_MISMATCH, now) != 1) {
        throw exception(ORDER_STATE_CONFLICT);
    }
    return settleTerminal(refundCase, flow, instoreOrder, order, MANUAL.getCode(), true,
            cashierRefundReference, traceId, now);
}

/** 终态收尾：清活动指针、累计已由 SUCCESS 分支完成、投影售后、派发下一个排队 Case。 */
private RefundSettlementOutcome settleTerminal(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                               InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                               short finalStatus, boolean amountMismatch,
                                               String cashierRefundReference, String traceId,
                                               LocalDateTime now) {
    long orderVersionAfterAccumulate = instoreOrder.getVersion() + 1;
    instoreOrderMapper.setActiveRefundCase(instoreOrder.getId(), orderVersionAfterAccumulate,
            null, now);
    projectRefundAftersale(refundCase, flow, instoreOrder, order, traceId, now);
    appendRefundCaseEvent(refundCase, flow, order, finalStatus, traceId, now);
    ClaimedNext next = claimNextQueuedRefund(flow, instoreOrder, order, orderVersionAfterAccumulate, traceId, now);
    log.info("[INSTORE][lifecycle] applyBuyerRefundResult 完成 refundCaseNo={}, finalStatus={}, nextClaimed={}",
            refundCase.getRefundCaseNo(), finalStatus, next != null ? next.refundCaseNo() : null);
    return new RefundSettlementOutcome(finalStatus, amountMismatch, next != null,
            next != null ? next.refundCaseNo() : null,
            next != null ? next.operationNo() : null,
            next != null ? next.operationVersion() : 0L);
}

/** 按未收敛 Case 与累计退款投影总表售后状态（detailVersion 单调递增）。 */
private void projectRefundAftersale(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                                    InstoreChannelOrderDO instoreOrder, SupplyChainOrderDO order,
                                    String traceId, LocalDateTime now) {
    boolean hasOpen = !refundCaseMapper.selectOpenByFlow(flow.getId()).isEmpty();
    long channelDelta = refundCase.getStatus() == InstoreRefundStatus.BUYER_REFUNDING.getCode()
            && refundCase.getChannelRefundAmount() != null ? refundCase.getChannelRefundAmount() : 0L;
    InstoreRefundProjection projection = new InstoreRefundProjection(hasOpen,
            instoreOrder.getTotalChannelRefundAmount() + channelDelta,
            instoreOrder.getTotalPaidAmount());
    OrderSummaryDecision decision = instoreOrderSummaryPolicy.decide(
            buildSummaryFact(instoreOrder), projection);
    if (orderMapper.advanceAftersaleStatus(order.getId(), order.getDetailVersion(),
            order.getDetailVersion() + 1, decision.aftersaleStatus(), now) != 1) {
        throw exception(ORDER_STATE_CONFLICT);
    }
    appendStatusLog(order, order.getDetailVersion() + 1, decision,
            OrderStatusAuditCodes.SOURCE_INSTORE_REFUND_RESULT,
            instoreOrder.getInstoreOrderNo(), OrderStatusAuditCodes.REASON_REFUND, traceId, now);
}

/** 终态即释放串行锁：抢占下一个排队 Case（按 refund_sequence），创建 BUYER_REFUND Operation 并回指活动指针。 */
private ClaimedNext claimNextQueuedRefund(InstorePaymentFlowDO flow, InstoreChannelOrderDO instoreOrder,
                                          SupplyChainOrderDO order, long orderVersion, String traceId,
                                          LocalDateTime now) {
    InstoreRefundCaseDO next = refundCaseMapper.selectNextQueuedByFlow(flow.getId());
    if (next == null) {
        return null;
    }
    if (refundCaseMapper.claimDispatch(next.getId(), next.getVersion(), now) != 1) {
        return null;
    }
    String operationNo = businessNoGenerator.next(BusinessNoPrefixes.IOP);
    InstoreOperationDO operation = InstoreOperationDO.builder()
            .operationNo(operationNo).instoreOrderId(next.getInstoreOrderId())
            .paymentFlowId(flow.getId()).refundCaseId(next.getId()).supplierOrderId(null)
            .operationType(InstoreOperationType.BUYER_REFUND.name())
            .idempotencyKey(InstoreIdempotencyKeys.buyerRefund(next.getRefundCaseNo()))
            .requestFingerprint(next.getRequestFingerprint()).externalOperationNo(null)
            .status(InstoreOperationStatus.RUNNING.getCode()).resultClass(null).attemptCount(0)
            .nextRecoverAt(null).responseDigest(null).version(0L)
            .createdAt(now).updatedAt(now).completedAt(null).build();
    operationMapper.insert(operation);
    instoreOrderMapper.setActiveRefundCase(instoreOrder.getId(), orderVersion, next.getId(), now);
    return new ClaimedNext(next.getRefundCaseNo(), operationNo, 1L);
}
```

配套修改：
1. 类新增 `private final BuyerAccountReturnService accountReturnService;` 与 `private final InstoreOrderSummaryPolicy instoreOrderSummaryPolicy;`（构造器由 Lombok 生成，测试 `@InjectMocks` 同步）。
2. `InstoreRejectionCode` 追加常量 `REFUND_AMOUNT_MISMATCH("退款实际金额与申请金额不一致")`。
3. `buildSummaryFact(instoreOrder)`：从现有 `decideOrderSummary` 中抽出 Fact 组装（`new OrderSummaryFact(ORDER_TYPE_INSTORE, DETAIL_TYPE_INSTORE_CHANNEL, instoreOrder.getOrderStatus(), 1L, 已完成?1:0, null)`）。
4. `appendRefundCaseEvent`：沿用现有 Outbox 写法（`AGGREGATE_INSTORE_REFUND_CASE`），payload 增加 `finalStatus`、`actualAmount`、`accountReturnAmount` 字段。
5. `isTerminalRefundStatus(short)`：SUCCESS/FAILED/COMPLETED/MANUAL。
6. 新 record：

```java
/** 退款收敛结果：amountMismatch=true 表示已转人工未返还；nextClaimed=true 时调用方须外呼下一个 Case。 */
public record RefundSettlementOutcome(short finalStatus, boolean amountMismatch,
                                      boolean nextClaimed, String nextClaimedRefundCaseNo,
                                      String nextClaimedOperationNo, long nextClaimedOperationVersion) {
}

/** 内部：抢占到的下一个排队退款 Case。 */
private record ClaimedNext(String refundCaseNo, String operationNo, long operationVersion) {
}
```

- [x] **Step 4: 运行测试**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreRefundSettlementTest,InstoreRefundAcceptanceTest,InstoreChannelLifecycleTest' test
```

Expected: 全部通过。

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore
git commit -m "feat(supply-chain): settle instore refund with account return"
```

---

### Task 6: InstoreRefundConvergenceService —— 统一收敛

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundConvergenceService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundConvergenceTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款统一收敛单测：回调优先、项目归属校验、权威查询兜底与派发外呼。 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundConvergenceTest {

    @Mock private InstoreRefundCaseMapper refundCaseMapper;
    @Mock private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock private InstoreChannelOrderMapper orderMapper;
    @Mock private BuyerIntegrationRouteResolver routeResolver;
    @Mock private BuyerCashierPort cashierPort;
    @Mock private InstoreChannelLifecycle lifecycle;
    @InjectMocks private InstoreRefundConvergenceService service;

    private static InstoreRefundCaseDO refundCase(short status) {
        return InstoreRefundCaseDO.builder().id(9L).refundCaseNo("IRC_9")
                .instoreOrderId(7L).paymentFlowId(3L).orderId(11L).buyerId(5L)
                .channelCode("RT_MART").channelRefundNo("R9").refundSequence(1)
                .refundType("CHANNEL_REFUND").channelRefundAmount(300L)
                .status(status).buyerRefundingFlag((byte) 1).version(4L).build();
    }

    private static InstoreChannelOrderDO order() {
        return InstoreChannelOrderDO.builder().id(7L).orderId(11L).buyerId(5L)
                .instoreOrderNo("ISC_1").projectId(1L).cashierProfileVersionId(2L)
                .totalPaidAmount(1000L).totalChannelRefundAmount(0L)
                .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(0L).build();
    }

    private void stubScope(InstoreRefundCaseDO refundCase) {
        lenient().when(refundCaseMapper.selectByRefundCaseNo("IRC_9")).thenReturn(refundCase);
        lenient().when(paymentFlowMapper.selectById(3L)).thenReturn(
                InstorePaymentFlowDO.builder().id(3L).instoreOrderId(7L).orderId(11L)
                        .channelCode("RT_MART").tradeId("T1").outTradeNo("OT1")
                        .payAmount(1000L).version(0L).build());
        lenient().when(orderMapper.selectById(7L)).thenReturn(order());
    }

    @Test
    void callbackConvergesDirectlyWithoutAuthoritativeQuery() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.SUCCESS.getCode(), false, false, null, null, 0L));

        service.confirmRefund(1L, "IRC_9", 300L, "CRF_1", "trace-1");

        verify(cashierPort, never()).queryRefund(any());
    }

    @Test
    void callbackRejectsDifferentProjectOwnership() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));

        assertThatThrownBy(() -> service.confirmRefund(99L, "IRC_9", 300L, "CRF_1", "trace-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_TENANT_SCOPE_CONFLICT.getMsg());
        verify(lifecycle, never()).applyBuyerRefundResult(any(), any(), any(), any(), any(), any());
    }

    @Test
    void callbackAmountMismatchThrowsStableErrorAfterCaseSettledManual() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 200L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.MANUAL.getCode(), true, false, null, null, 0L));

        assertThatThrownBy(() -> service.confirmRefund(1L, "IRC_9", 200L, "CRF_1", "trace-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_REFUND_AMOUNT_MISMATCH.getMsg());
    }

    @Test
    void terminalCaseSkipsAuthoritativeQuery() {
        stubScope(refundCase(InstoreRefundStatus.SUCCESS.getCode()));
        when(lifecycle.queryRefundStatus("RT_MART", "R9")).thenReturn(
                new InstoreChannelLifecycle.RefundStatusView("IRC_9", "R9", "CHANNEL_REFUND",
                        InstoreRefundStatus.SUCCESS.getCode(), 300L, 300L));

        service.queryAndConvergeRefundByRefundCaseNo("IRC_9", "trace-1");

        verify(cashierPort, never()).queryRefund(any());
    }

    @Test
    void authoritativeQuerySuccessConvergesWithOriginalRefundCaseNo() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(
                new com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute(
                        1L, 2L, "SAAS", "TENANT_A"));
        when(cashierPort.queryRefund(any())).thenReturn(new BuyerCashierPort.QueryRefundResult(
                BuyerCashierPort.RefundQueryStatus.SUCCEEDED, 300L, "CRF_1", null,
                OffsetDateTime.now(ZoneOffset.UTC)));
        when(lifecycle.applyBuyerRefundResult("IRC_9", "SUCCESS", 300L, "CRF_1", null, "trace-1"))
                .thenReturn(new InstoreChannelLifecycle.RefundSettlementOutcome(
                        InstoreRefundStatus.SUCCESS.getCode(), false, false, null, null, 0L));
        when(lifecycle.queryRefundStatus("RT_MART", "R9")).thenReturn(
                new InstoreChannelLifecycle.RefundStatusView("IRC_9", "R9", "CHANNEL_REFUND",
                        InstoreRefundStatus.SUCCESS.getCode(), 300L, 300L));

        service.queryAndConvergeRefundByRefundCaseNo("IRC_9", "trace-1");

        ArgumentCaptor<BuyerCashierPort.QueryRefundCommand> captor =
                ArgumentCaptor.forClass(BuyerCashierPort.QueryRefundCommand.class);
        verify(cashierPort).queryRefund(captor.capture());
        assertThat(captor.getValue().refundCaseNo()).isEqualTo("IRC_9");
    }

    @Test
    void dispatchClaimedRefundCallsCashierWithFrozenRouteAndOriginalNumbers() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(
                new com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute(
                        1L, 2L, "SAAS", "TENANT_A"));
        when(cashierPort.refund(any())).thenReturn(new BuyerCashierPort.RefundResult(
                BuyerCashierPort.RefundStatus.PROCESSING, null, null, null,
                OffsetDateTime.now(ZoneOffset.UTC)));

        service.dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");

        ArgumentCaptor<BuyerCashierPort.RefundCommand> captor =
                ArgumentCaptor.forClass(BuyerCashierPort.RefundCommand.class);
        verify(cashierPort).refund(captor.capture());
        assertThat(captor.getValue().refundCaseNo()).isEqualTo("IRC_9");
        assertThat(captor.getValue().outTradeNo()).isEqualTo("OT1");
        assertThat(captor.getValue().amountCny()).isEqualTo(300L);
        assertThat(captor.getValue().invocationId()).isEqualTo("IOP_1");
    }

    @Test
    void dispatchCashierFailureKeepsOriginalRefundCaseNoForRecovery() {
        stubScope(refundCase(InstoreRefundStatus.BUYER_REFUNDING.getCode()));
        when(routeResolver.resolveFrozen(eq(1L), eq(2L), any())).thenReturn(
                new com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute(
                        1L, 2L, "SAAS", "TENANT_A"));
        when(cashierPort.refund(any())).thenThrow(new RuntimeException("cashier timeout"));

        service.dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");

        verify(lifecycle, never()).applyBuyerRefundResult(any(), any(), any(), any(), any(), any());
    }
}
```

- [x] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreRefundConvergenceTest test
```

Expected: 编译错误 `InstoreRefundConvergenceService` 不存在。

- [x] **Step 3: 实现收敛服务**

```java
package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_AMOUNT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_REFUND_CASE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_TENANT_SCOPE_CONFLICT;

/**
 * 到店购退款统一收敛服务：SaaS 回调优先，本地非终态时按原 refundCaseNo 权威查询兜底。
 *
 * <p>渠道查询（接口⑥）、PersistentTimer 恢复与派发外呼后的响应处理必须复用本服务；
 * 回调、查询与 Timer 并发时依靠 Lifecycle 的 Case 状态 CAS 幂等收敛。
 * 金额不一致时 Case 已在 Lifecycle 事务内转 MANUAL，本服务在事务提交后抛
 * {@code INSTORE_REFUND_AMOUNT_MISMATCH}（RetryDirective.MANUAL，禁止调用方自动重试）。
 * 网络调用不在本地事务中执行。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 8 条
 * 与 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#51-saas-商城收银台编排} 第 4 条。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstoreRefundConvergenceService {

    private final InstoreRefundCaseMapper refundCaseMapper;
    private final InstorePaymentFlowMapper paymentFlowMapper;
    private final InstoreChannelOrderMapper orderMapper;
    private final BuyerIntegrationRouteResolver routeResolver;
    private final BuyerCashierPort cashierPort;
    private final InstoreChannelLifecycle lifecycle;

    /** 回调优先入口：校验已认证项目拥有该 refundCaseNo 后直接进入 Lifecycle 收敛，不先查询。 */
    public void confirmRefund(Long expectedProjectId, String refundCaseNo, Long actualRefundAmountCny,
                              String cashierRefundReference, String traceId) {
        log.info("[INSTORE][refund-convergence] callback 入口 projectId={}, refundCaseNo={}, actualAmount={}, traceId={}",
                expectedProjectId, refundCaseNo, actualRefundAmountCny, traceId);
        RefundScope scope = loadScope(refundCaseMapper.selectByRefundCaseNo(refundCaseNo));
        verifyProject(expectedProjectId, scope.order());
        InstoreChannelLifecycle.RefundSettlementOutcome outcome = lifecycle.applyBuyerRefundResult(
                refundCaseNo, "SUCCESS", actualRefundAmountCny, cashierRefundReference, null, traceId);
        throwIfMismatch(outcome);
        dispatchNextClaimed(scope, outcome, traceId);
    }

    /** 采购项目侧查询（接口⑥）：先校验项目归属，再在本地非终态时按原 refundCaseNo 权威查 SaaS 收银台。 */
    public InstoreChannelLifecycle.RefundStatusView queryAndConvergeRefund(Long expectedProjectId,
                                                                           String channelCode,
                                                                           String channelRefundNo,
                                                                           String traceId) {
        log.info("[INSTORE][refund-convergence] query 入口 projectId={}, channelCode={}, channelRefundNo={}, traceId={}",
                expectedProjectId, channelCode, channelRefundNo, traceId);
        RefundScope scope = loadScope(refundCaseMapper.selectByChannelRefundNo(channelCode, channelRefundNo));
        verifyProject(expectedProjectId, scope.order());
        return queryIfNecessary(scope, traceId);
    }

    /** Timer 恢复入口：只接受数据库已固化的原 refundCaseNo，不接受临时路由字段。 */
    public InstoreChannelLifecycle.RefundStatusView queryAndConvergeRefundByRefundCaseNo(
            String refundCaseNo, String traceId) {
        log.info("[INSTORE][refund-convergence] timer query 入口 refundCaseNo={}, traceId={}",
                refundCaseNo, traceId);
        return queryIfNecessary(loadScope(refundCaseMapper.selectByRefundCaseNo(refundCaseNo)), traceId);
    }

    /**
     * 派发外呼（受理/取消抢占后与终态派发下一个共用）：使用订单固化的同一项目和收银台档案版本，
     * 按原 refundCaseNo 调 refund；外呼异常保留原号，交由回调或 Timer 权威查询收敛。
     */
    public void dispatchClaimedRefundCase(String refundCaseNo, String invocationId, String traceId) {
        RefundScope scope = loadScope(refundCaseMapper.selectByRefundCaseNo(refundCaseNo));
        InstoreRefundCaseDO refundCase = scope.refundCase();
        if (refundCase.getStatus() != InstoreRefundStatus.BUYER_REFUNDING.getCode()) {
            log.info("[INSTORE][refund-convergence] Case 非退款中，跳过外呼 refundCaseNo={}, status={}",
                    refundCaseNo, refundCase.getStatus());
            return;
        }
        BuyerIntegrationRoute route = resolveRoute(scope.order());
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][cashier] refund 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantId={}, invocationId={}, refundCaseNo={}, outTradeNo={}, amount={}",
                route.projectId(), route.profileVersionId(), route.sourceSystem(),
                route.externalTenantId(), invocationId, refundCaseNo,
                scope.flow().getOutTradeNo(), refundCase.getChannelRefundAmount());
        BuyerCashierPort.RefundResult result;
        try {
            result = cashierPort.refund(new BuyerCashierPort.RefundCommand(route, invocationId,
                    refundCaseNo, scope.flow().getOutTradeNo(), refundCase.getChannelRefundAmount()));
            log.info("[INSTORE][cashier] refund 响应 projectId={}, refundCaseNo={}, status={}, actualAmount={}, "
                            + "cashierRefundReference={}, cost={}ms",
                    route.projectId(), refundCaseNo, result.status(), result.actualRefundAmountCny(),
                    result.cashierRefundReference(), System.currentTimeMillis() - startMs);
        } catch (RuntimeException ex) {
            log.warn("[INSTORE][cashier] refund 异常，保留原号待权威查询 refundCaseNo={}, invocationId={}, cost={}ms",
                    refundCaseNo, invocationId, System.currentTimeMillis() - startMs, ex);
            return;
        }
        handleRefundResponse(scope, result.status().name(), result.actualRefundAmountCny(),
                result.cashierRefundReference(), result.errorCode(), traceId);
    }

    private InstoreChannelLifecycle.RefundStatusView queryIfNecessary(RefundScope scope, String traceId) {
        InstoreRefundCaseDO refundCase = scope.refundCase();
        if (isTerminal(refundCase.getStatus())) {
            log.info("[INSTORE][refund-convergence] 本地已终态，跳过外呼 refundCaseNo={}, status={}",
                    refundCase.getRefundCaseNo(), refundCase.getStatus());
            return lifecycle.queryRefundStatus(refundCase.getChannelCode(), refundCase.getChannelRefundNo());
        }
        BuyerIntegrationRoute route = resolveRoute(scope.order());
        long startMs = System.currentTimeMillis();
        log.info("[INSTORE][cashier] queryRefund 请求 projectId={}, profileVersionId={}, sourceSystem={}, "
                        + "externalTenantId={}, refundCaseNo={}",
                route.projectId(), route.profileVersionId(), route.sourceSystem(),
                route.externalTenantId(), refundCase.getRefundCaseNo());
        BuyerCashierPort.QueryRefundResult result = cashierPort.queryRefund(
                new BuyerCashierPort.QueryRefundCommand(route, refundCase.getRefundCaseNo()));
        log.info("[INSTORE][cashier] queryRefund 响应 projectId={}, refundCaseNo={}, status={}, actualAmount={}, "
                        + "cost={}ms",
                route.projectId(), refundCase.getRefundCaseNo(), result.status(),
                result.actualRefundAmountCny(), System.currentTimeMillis() - startMs);
        if (result.status() == BuyerCashierPort.RefundQueryStatus.SUCCEEDED) {
            handleRefundResponse(scope, "SUCCESS", result.actualRefundAmountCny(),
                    result.cashierRefundReference(), null, traceId);
        } else if (result.status() == BuyerCashierPort.RefundQueryStatus.FAILED) {
            handleRefundResponse(scope, "FAILED", null, result.cashierRefundReference(),
                    result.errorCode(), traceId);
        }
        return lifecycle.queryRefundStatus(refundCase.getChannelCode(), refundCase.getChannelRefundNo());
    }

    /** 回调/查询/派发共用的结果收敛：SUCCEEDED 与 FAILED 之外（处理中/未知）不推进本地状态。 */
    private void handleRefundResponse(RefundScope scope, String refundStatus, Long actualAmount,
                                      String cashierRefundReference, String errorCode, String traceId) {
        if (!"SUCCESS".equals(refundStatus) && !"FAILED".equals(refundStatus)) {
            return;
        }
        InstoreChannelLifecycle.RefundSettlementOutcome outcome = lifecycle.applyBuyerRefundResult(
                scope.refundCase().getRefundCaseNo(), refundStatus, actualAmount,
                cashierRefundReference, errorCode, traceId);
        throwIfMismatch(outcome);
        dispatchNextClaimed(scope, outcome, traceId);
    }

    private void dispatchNextClaimed(RefundScope scope,
                                     InstoreChannelLifecycle.RefundSettlementOutcome outcome,
                                     String traceId) {
        if (!outcome.nextClaimed()) {
            return;
        }
        log.info("[INSTORE][refund-convergence] 派发下一个排队 Case nextRefundCaseNo={}",
                outcome.nextClaimedRefundCaseNo());
        dispatchClaimedRefundCase(outcome.nextClaimedRefundCaseNo(),
                outcome.nextClaimedOperationNo(), traceId);
    }

    private void throwIfMismatch(InstoreChannelLifecycle.RefundSettlementOutcome outcome) {
        if (outcome.amountMismatch()) {
            throw exception(INSTORE_REFUND_AMOUNT_MISMATCH);
        }
    }

    private BuyerIntegrationRoute resolveRoute(InstoreChannelOrderDO order) {
        return routeResolver.resolveFrozen(order.getProjectId(), order.getCashierProfileVersionId(),
                BuyerIntegrationCapabilityCode.CASHIER);
    }

    private RefundScope loadScope(InstoreRefundCaseDO refundCase) {
        if (refundCase == null) {
            throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
        }
        InstorePaymentFlowDO flow = paymentFlowMapper.selectById(refundCase.getPaymentFlowId());
        if (flow == null) {
            throw exception(INSTORE_REFUND_CASE_NOT_FOUND);
        }
        InstoreChannelOrderDO order = orderMapper.selectById(refundCase.getInstoreOrderId());
        if (order == null) {
            throw exception(INSTORE_ORDER_NOT_FOUND);
        }
        return new RefundScope(refundCase, flow, order);
    }

    private static void verifyProject(Long expectedProjectId, InstoreChannelOrderDO order) {
        if (expectedProjectId == null || !expectedProjectId.equals(order.getProjectId())) {
            throw exception(INSTORE_TENANT_SCOPE_CONFLICT);
        }
    }

    private static boolean isTerminal(short status) {
        return status == InstoreRefundStatus.SUCCESS.getCode()
                || status == InstoreRefundStatus.FAILED.getCode()
                || status == InstoreRefundStatus.COMPLETED.getCode()
                || status == InstoreRefundStatus.MANUAL.getCode();
    }

    private record RefundScope(InstoreRefundCaseDO refundCase, InstorePaymentFlowDO flow,
                               InstoreChannelOrderDO order) {
    }
}
```

- [x] **Step 4: 运行测试通过并提交**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=InstoreRefundConvergenceTest test
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundConvergenceService.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundConvergenceTest.java
git commit -m "feat(supply-chain): add instore refund convergence service"
```

---

### Task 7: Facade 编排与退款回调入口

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerCashierCallbackFacade.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreChannelCommandFacadeTest.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade/InstoreBuyerCashierCallbackFacadeTest.java`（如支付回调已有测试类则并入）

- [x] **Step 1: 写失败测试（Facade 新方法）**

`InstoreChannelCommandFacadeTest` 追加：

```java
@Test
void acceptRefundDispatchesClaimedCaseThroughConvergenceService() {
    InstoreChannelLifecycle.AcceptRefundOutcome outcome = new InstoreChannelLifecycle.AcceptRefundOutcome(
            "IRC_9", true, "IOP_1", 1L, InstoreRefundStatus.BUYER_REFUNDING.getCode());
    when(lifecycle.acceptRefund(any())).thenReturn(outcome);

    InstoreChannelCommandFacade.RefundAcceptance acceptance = facade.acceptRefund(
            new InstoreChannelCommandFacade.AcceptRefundCommand(
                    "RT_MART", "R9", "T1", "CHANNEL_REFUND", 300L, "trace-1"));

    assertThat(acceptance.refundCaseNo()).isEqualTo("IRC_9");
    assertThat(acceptance.refundStatus()).isEqualTo(InstoreRefundStatus.BUYER_REFUNDING.getCode());
    verify(refundConvergenceService).dispatchClaimedRefundCase("IRC_9", "IOP_1", "trace-1");
}

@Test
void acceptRefundQueuedBehindActiveCaseSkipsExternalCall() {
    when(lifecycle.acceptRefund(any())).thenReturn(new InstoreChannelLifecycle.AcceptRefundOutcome(
            "IRC_10", false, null, 0L, InstoreRefundStatus.QUEUED.getCode()));

    InstoreChannelCommandFacade.RefundAcceptance acceptance = facade.acceptRefund(
            new InstoreChannelCommandFacade.AcceptRefundCommand(
                    "RT_MART", "R10", "T1", "CHANNEL_REFUND", 200L, "trace-1"));

    assertThat(acceptance.claimedForDispatch()).isFalse();
    verify(refundConvergenceService, never()).dispatchClaimedRefundCase(any(), any(), any());
}

@Test
void cancelPaidFlowDispatchesClaimedCancelRefund() {
    when(lifecycle.cancelPaymentFlow(any())).thenReturn(new InstoreChannelLifecycle.CancelPaymentFlowResult(
            "OT1", "IRC_C", true, true, "IOP_C"));

    InstoreChannelCommandFacade.CancelPaymentTicket ticket = facade.cancelPaymentFlow(
            new InstoreChannelCommandFacade.CancelPaymentCommand("RT_MART", "T1", "trace-1"));

    assertThat(ticket.convertedToRefund()).isTrue();
    verify(refundConvergenceService).dispatchClaimedRefundCase("IRC_C", "IOP_C", "trace-1");
}

@Test
void queryAndConvergeRefundDelegatesToConvergenceService() {
    when(refundConvergenceService.queryAndConvergeRefund(1L, "RT_MART", "R9", "trace-1"))
            .thenReturn(new InstoreChannelLifecycle.RefundStatusView(
                    "IRC_9", "R9", "CHANNEL_REFUND",
                    InstoreRefundStatus.BUYER_REFUNDING.getCode(), 300L, null));

    InstoreChannelLifecycle.RefundStatusView view = facade.queryAndConvergeRefund(
            new InstoreChannelCommandFacade.QueryRefundCommand(1L, "RT_MART", "R9", "trace-1"));

    assertThat(view.refundCaseNo()).isEqualTo("IRC_9");
}
```

`InstoreBuyerCashierCallbackFacadeTest`（新测试类，Mockito 验证转发与入口日志）：

```java
@ExtendWith(MockitoExtension.class)
class InstoreBuyerCashierCallbackFacadeTest {

    @Mock private InstoreRefundConvergenceService refundConvergenceService;
    @InjectMocks private InstoreBuyerCashierCallbackFacade facade;

    @Test
    void confirmRefundDelegatesWithTrustedProjectScope() {
        facade.confirmRefund(new InstoreBuyerCashierCallbackFacade.RefundCallbackCommand(
                1L, "IRC_9", 300L, "CRF_1", "trace-1"));

        verify(refundConvergenceService).confirmRefund(1L, "IRC_9", 300L, "CRF_1", "trace-1");
    }
}
```

（若该测试类已存在支付回调用例，退款用例并入同文件。）

- [x] **Step 2: 运行确认失败**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreChannelCommandFacadeTest,InstoreBuyerCashierCallbackFacadeTest' test
```

- [x] **Step 3: 实现 Facade 扩展**

`InstoreChannelCommandFacade` 追加依赖 `private final InstoreRefundConvergenceService refundConvergenceService;` 与三个方法：

```java
/**
 * 退款受理（接口⑤联调切片）：受理事务提交后，抢占成功的 Case 按原 refundCaseNo 外呼收银台；
 * 排队中的 Case 不外呼，等待当前活动退款终态后由收敛服务派发。
 */
public RefundAcceptance acceptRefund(AcceptRefundCommand command) {
    Objects.requireNonNull(command, "command");
    log.info("[INSTORE][facade] acceptRefund 入口 channelCode={}, channelRefundNo={}, tradeId={}, "
                    + "refundType={}, refundAmount={}, traceId={}",
            command.channelCode(), command.channelRefundNo(), command.tradeId(),
            command.refundType(), command.channelRefundAmount(), command.traceId());
    InstoreChannelLifecycle.AcceptRefundOutcome outcome = lifecycle.acceptRefund(
            new InstoreChannelLifecycle.AcceptRefundCommand(command.channelCode(),
                    command.channelRefundNo(), command.tradeId(), command.refundType(),
                    command.channelRefundAmount(), command.traceId()));
    if (outcome.claimedForDispatch()) {
        refundConvergenceService.dispatchClaimedRefundCase(outcome.refundCaseNo(),
                outcome.operationNo(), command.traceId());
    }
    return new RefundAcceptance(outcome.refundCaseNo(), outcome.refundStatus(),
            outcome.claimedForDispatch());
}

/** 取消支付流水（接口④）：未支付走取消释放；已支付转全额 CANCEL_REFUND 并共用串行派发闭环。 */
public CancelPaymentTicket cancelPaymentFlow(CancelPaymentCommand command) {
    Objects.requireNonNull(command, "command");
    log.info("[INSTORE][facade] cancelPaymentFlow 入口 channelCode={}, tradeId={}, traceId={}",
            command.channelCode(), command.tradeId(), command.traceId());
    InstoreChannelLifecycle.CancelPaymentFlowResult result = lifecycle.cancelPaymentFlow(
            new InstoreChannelLifecycle.CancelPaymentFlowCommand(
                    command.channelCode(), command.tradeId(), command.traceId()));
    if (result.claimedForDispatch()) {
        refundConvergenceService.dispatchClaimedRefundCase(result.refundCaseNo(),
                result.operationNo(), command.traceId());
    }
    return new CancelPaymentTicket(result.outTradeNo(), result.refundCaseNo(),
            result.convertedToRefund(), result.claimedForDispatch());
}

/** 渠道退款查询（接口⑥）：委托统一收敛服务，本地非终态时按原 refundCaseNo 权威查 SaaS 收银台。 */
public InstoreChannelLifecycle.RefundStatusView queryAndConvergeRefund(QueryRefundCommand command) {
    Objects.requireNonNull(command, "command");
    return refundConvergenceService.queryAndConvergeRefund(command.projectId(),
            command.channelCode(), command.channelRefundNo(), command.traceId());
}
```

新增内部 record（`projectId` 来自认证上下文，业务 Wire DTO 禁止声明）：

```java
/** 退款受理内部命令。 */
public record AcceptRefundCommand(String channelCode, String channelRefundNo, String tradeId,
                                  String refundType, long channelRefundAmount, String traceId) {
}

/** 退款受理封闭结果：refundStatus 为 10 排队中 / 20 退款中。 */
public record RefundAcceptance(String refundCaseNo, short refundStatus, boolean claimedForDispatch) {
}

/** 取消支付流水内部命令。 */
public record CancelPaymentCommand(String channelCode, String tradeId, String traceId) {
}

/** 取消封闭结果：未支付仅 outTradeNo；已支付转退款时携带 refundCaseNo。 */
public record CancelPaymentTicket(String outTradeNo, String refundCaseNo,
                                  boolean convertedToRefund, boolean claimedForDispatch) {
}

/** 项目侧退款查询内部命令。 */
public record QueryRefundCommand(Long projectId, String channelCode, String channelRefundNo,
                                 String traceId) {
}
```

`InstoreBuyerCashierCallbackFacade` 追加依赖 `private final InstoreRefundConvergenceService refundConvergenceService;` 与退款回调入口：

```java
/**
 * 退款成功回调：Adapter 完成鉴权后提供可信 projectId；按原 refundCaseNo 校验归属后直接收敛，
 * 不先发起权威查询；金额不一致由收敛服务抛稳定错误码（Case 已在事务内转 MANUAL）。
 */
public void confirmRefund(RefundCallbackCommand command) {
    Objects.requireNonNull(command, "command");
    log.info("[INSTORE][callback] confirmRefund 入口 projectId={}, refundCaseNo={}, actualAmount={}, "
                    + "cashierRefundReference={}, traceId={}",
            command.projectId(), command.refundCaseNo(), command.actualAmountCny(),
            command.cashierRefundReference(), command.traceId());
    refundConvergenceService.confirmRefund(command.projectId(), command.refundCaseNo(),
            command.actualAmountCny(), command.cashierRefundReference(), command.traceId());
}

/** Adapter 内部命令；projectId 来自可信回调认证上下文，不属于回调 Wire DTO。 */
public record RefundCallbackCommand(Long projectId, String refundCaseNo, long actualAmountCny,
                                    String cashierRefundReference, String traceId) {
}
```

- [x] **Step 4: 运行测试通过**

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  mvn -o -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest='InstoreChannelCommandFacadeTest,InstoreBuyerCashierCallbackFacadeTest,InstoreRefundConvergenceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- [x] **Step 5: 提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/application/facade
git commit -m "feat(supply-chain): orchestrate instore refund facade and callback"
```

---

### Task 8: Timer 恢复接线 —— BUYER_REFUND/REFUND_QUERY 按 refundCaseNo 收敛

**Files:**
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreOperationRecoveryHandler.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreOperationRecoveryHandlerTest.java`

- [x] **Step 1: 写失败测试**

`InstoreOperationRecoveryHandlerTest` 追加（`InstoreOperationRecoveryHandler` 构造器新增 `refundCaseMapper`、`refundConvergenceService` 两个依赖，用 `@Mock`）：

```java
@Test
void buyerRefundRecoveryQueriesRefundByOriginalRefundCaseNo() {
    // operation: type=BUYER_REFUND, RUNNING, refundCaseId=9
    // refundCaseMapper.selectById(9L) → Case(refundCaseNo=IRC_9, status=BUYER_REFUNDING)
    // refundConvergenceService.queryAndConvergeRefundByRefundCaseNo(...) 返回视图
    RecoveryDecision decision = handler.recover("IOP_R1");
    assertThat(decision.action()).isEqualTo("QUERY_REFUND:IRC_9");
    verify(refundConvergenceService).queryAndConvergeRefundByRefundCaseNo(
            eq("IRC_9"), eq("TIMER_RECOVERY:IOP_R1"));
}

@Test
void buyerRefundRecoverySkipsWhenCaseAlreadyTerminal() {
    // Case status=SUCCESS → 不外呼，决策 ALREADY_CONVERGED
    verify(refundConvergenceService, never()).queryAndConvergeRefundByRefundCaseNo(any(), any());
}
```

- [x] **Step 2: 实现恢复接线**

`recover` 的 `case BUYER_REFUND, REFUND_QUERY` 分支改为：

```java
case BUYER_REFUND, REFUND_QUERY -> {
    if (operation.getRefundCaseId() == null) {
        throw new IllegalStateException("Refund operation missing refundCaseId: " + operationNo);
    }
    InstoreRefundCaseDO refundCase = refundCaseMapper.selectById(operation.getRefundCaseId());
    if (refundCase == null) {
        throw new IllegalStateException("Refund case disappeared during recovery: " + operationNo);
    }
    if (isTerminalRefundStatus(refundCase.getStatus())) {
        yield "ALREADY_CONVERGED:" + refundCase.getRefundCaseNo();
    }
    refundConvergenceService.queryAndConvergeRefundByRefundCaseNo(
            refundCase.getRefundCaseNo(), "TIMER_RECOVERY:" + operationNo);
    yield "QUERY_REFUND:" + refundCase.getRefundCaseNo();
}
```

终态判断复用 Lifecycle 同一口径（SUCCESS/FAILED/COMPLETED/MANUAL）。

- [x] **Step 3: 运行测试并提交**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/instore/application/InstoreOperationRecoveryHandler.java aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreOperationRecoveryHandlerTest.java
git commit -m "feat(supply-chain): converge instore refund recovery by refund case"
```

---

### Task 9: 退款串行纵向集成测试（真实 MySQL）

**Files:**
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundFlowIntegrationTest.java`

模式沿用 `InstoreMemberEntryFlowIntegrationTest`：Testcontainers MySQL 8.4 + Liquibase 真实建表，
导入真实 Mapper/Lifecycle/Service/Facade，仅以 `RecordingCashierPort` 替换 `BuyerCashierPort`
（记录外呼命令、返回可配置结果）。夹具：buyer/project/profile/version/supplier_binding/order/
instore_order/payment_flow（PAID, payAmount=100000）。

- [x] **场景 1 串行受理与排队**：`acceptRefund` 30000 → 抢占派发（Case=BUYER_REFUNDING，
  active_refund_case_id 指向）；再受理 20000 → QUEUED 排队不外呼；
  第三笔 50001 → `INSTORE_REFUND_AMOUNT_EXCEEDED`（100000-0-50000 在途）。
- [x] **场景 2 成功收敛闭环**：回调 `confirmRefund(projectId, IRC_1, 30000, CRF_1)` →
  Case=SUCCESS；`sc_buyer_account` 余额 +30000、`account_ledger` 新增 REFUND 条目
  （entry_key=REFUND_RETURN:IRC_1, balance_delta=+30000）；订单头累计三值（channel/buyer/account）
  各 +30000；`aftersale_status` 投影为 20 部分退款；排队 Case 自动抢占派发并外呼（RecordingPort 记录
  第二笔 refundCaseNo=IRC_2 原号调用）。
- [x] **场景 3 金额不一致转人工**：`confirmRefund(projectId, IRC_x, 29999, ...)` →
  异常 `INSTORE_REFUND_AMOUNT_MISMATCH`；Case=MANUAL；账户余额不变；售后投影保持 10 处理中。
- [x] **场景 4 权威查询兜底**：回调丢失时 `queryAndConvergeRefund(projectId, channelCode,
  channelRefundNo)` → RecordingPort 返回 SUCCEEDED → Case 收敛 SUCCESS（同场景 2 断言）。
- [x] **场景 5 幂等回放**：对已 SUCCESS 的 Case 重复回调 → 无二次返还（ledger 只有一条
  REFUND_RETURN 条目）、无状态冲突异常。
- [x] **运行**（`supply-chain-integration` profile 或外部 JDBC）后提交：

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/instore/application/InstoreRefundFlowIntegrationTest.java
git commit -m "test(supply-chain): verify instore refund serialization end to end"
```

---

### Task 10: 文档与全量门禁

- [x] 更新 `docs/集成测试.md`：INSTORE 退款串行联调步骤、前置夹具、验证点与排障（含金额不一致 MANUAL 路径、Timer 恢复路径）。
- [x] 更新 `docs/integration/rtmart/供应链中台INSTORE到店购渠道接入实现总结.md`：退款串行切片完成记录（受理排队、收敛、账户返还、售后投影、Timer 恢复）。
- [x] 全量门禁：供应链模块全部单测 + `*IntegrationTest` + `*ContractTest` + `*ArchitectureTest`
  （注意清理 `target/` 下 macOS 同步冲突文件，见项目记忆）。
- [x] 提交文档与门禁记录。

---

## 验收清单

- 串行不变量：同一 payment_flow 至多一个活动退款（uk_sc_instore_refund_active + CAS 抢占）。
- 防超退：可退 = 已付 − 已累计 − 在途（排队+退款中）。
- 金额一致才返还与累计；不一致转 MANUAL 且抛 `INSTORE_REFUND_AMOUNT_MISMATCH`（MANUAL 重试指令）。
- 账户返还幂等：`REFUND_RETURN:{refundCaseNo}` 账本唯一键。
- 回调优先、权威查询兜底、Timer 恢复三者共用收敛路径且幂等。
- 售后投影：无未收敛 Case 时按累计/实付投影 0/20/30；有未收敛（含 MANUAL）→ 10。
