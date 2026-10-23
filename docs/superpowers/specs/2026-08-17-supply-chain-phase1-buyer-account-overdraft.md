# 一期弱化采购商账户余额门槛

> 采购商项目化后，共享/项目专属账户与项目账户指派以[《供应链中台采购商主体与采购项目详细设计》](./2026-08-21-supply-chain-buyer-project-design.md#8-账户指派与资金边界)为准；本文“允许透支、缺账户拒绝、唯一账本”的资金不变量继续有效。

> 2026-10-23 管理端余额修正例外：分销商编辑允许设置非负目标总余额并同事务追加 ADJUSTMENT，冻结不变；本入口不走对账申请审批，也不改变对账流程。本文“无日常调余额”的范围排除此入口。详细规则及已知缺陷见[分销商账户余额编辑详细设计](./2026-10-23-buyer-admin-balance-adjustment-design.md)。

## 1. 文档定位

本文是对[《供应链中台一期详细设计》](./2026-08-07-supply-chain-phase1-detailed-design.md)第 6 章账户不变量的一期修订，不是另起一套资金模型。冻结、扣减、释放和账本仍由 `trade-core` 唯一写入；一期只取消「可用余额不足则拒单」这条硬门槛，允许账户透支，以便在入金业务未建设时先打通卡券受理与 O2O 预下单。

二期恢复可用余额门槛时，必须重新收紧 SQL 条件、表 `CHECK` 和测试，不得靠配置开关分叉两套语义。

相关基线：

- 详细设计第 6、3.5、3.6、8.6、9、16、17 章
- [《供应链中台一期数据库 DDL》](./2026-08-07-supply-chain-phase1-database-ddl.md) `sc_buyer_account`
- [《供应链中台一期核心设计》](./2026-08-04-supply-chain-core-design.md)「唯一账本与资源预占」

## 2. 决策

| 项 | 一期行为 |
| --- | --- |
| FundReservation | 仍 `freeze / commit / release`，仍写 `sc_fund_reservation` |
| 账本 | 仍追加 `FREEZE / COMMIT / RELEASE / DEPOSIT`，`balance_before/after` 允许为负 |
| 缺账户 | 卡券受理、O2O 预下单继续拒绝 |
| 可用余额不足 | 不拒绝；按应付金额增加 `frozen`，允许 `available < 0` |
| 确认扣减 | 允许 `balance` 变为负数 |
| 自动开户 | 不做 |
| 入金/充值 | 仍不做；期初开户仍是唯一入金入口，**允许期初 0** |
| 配置开关 | 不加 `enforceAvailableBalance` |

发生额（Listing 采购价、O2O `payAmount`、冻结金额）仍用 `Money`，禁止负数。账户汇总 `balance`、`frozen`、`available` 使用 `long`；`available = balance - frozen` 允许为负，不得包进 `Money`。

## 3. 账户不变量

保留：

- 下单前必须已有 `BuyerAccount`（`status=10`）
- `frozen >= 0`
- `FundReservation.totalAmount = committed + released + remaining`
- 同 `(buyerId, referenceType, referenceNo)` 冻结幂等；金额不同则冲突
- `commit` 与 `release` 对同一预留互斥，只能一个进入终态

放松（相对原 `balance >= frozen >= 0`）：

- 不再要求 `balance >= 0`
- 不再要求 `balance >= frozen`
- `atomicFreeze` 不再要求 `balance - frozen >= amount`
- `atomicCommit` 不再要求 `balance >= amount`，仍要求 `frozen >= amount`

冻结 CAS 失败只表示账户非正常、版本冲突或并发丢失，**不得**再解释为「余额不足」。

对账调账（`ADJUSTMENT`）仍按详细设计第 13 章：补扣受当时可用余额约束，一期没有自动调账。本次修订不改变对账模块。

## 4. 调用方

### 4.1 卡券受理

`FulfillmentAcceptanceLifecycle` 在校验 Listing/路由成功后仍调用 `FundReservationService.freeze`。

- 账户不存在：拒绝，原因码 `BUYER_ACCOUNT_NOT_OPENED`
- 可用余额不足：继续建单并写下透支预留
- `BUYER_BALANCE_INSUFFICIENT` 一期不受理路径抛出

缺账户与余额不足不得再共用同一拒绝码。

### 4.2 O2O 预下单

`O2OChannelLifecycle.createPaymentPreparation` 仍按 `payAmount` 冻结。

- 账户不存在：在创建收银台前拒绝，错误码 `BUYER_ACCOUNT_NOT_OPENED`
- 可用余额不足：不拒绝，继续 CAS 抢占 PREORDER 并调收银台

支付确认仍 `commit`，关闭/明确未支付仍 `release`。本次不修复既有 O2O `commit` 查找键、固定商户 SupplierOrder、退款串行等其它偏离。

### 4.3 期初开户

`BuyerAccountOpeningService.open` 允许 `initialBalance = 0`；负期初仍禁止（`Money` 已禁止负数发生额）。开户仍只成功一次，仍写 `entry_key=OPENING_BALANCE:{buyerId}:CNY` 的 `DEPOSIT` 流水（金额可为 0）。

## 5. 错误码

在 `SupplyChainErrorCodeConstants` 新增：

| 码 | 值 | 文案 | retryDirective |
| --- | --- | --- | --- |
| `BUYER_ACCOUNT_NOT_OPENED` | `1_050_001_025` | 采购商资金账户未开户 | `RETRY_SAME_KEY_AFTER_CONDITION_CHANGE` |

`BUYER_BALANCE_INSUFFICIENT`（`1_050_001_011`）保留在目录中，供二期恢复余额门槛时使用；一期受理、预下单和错误映射测试不得再把它当作当前可抛出的业务拒绝。

卡券 `AcceptanceRejectionCode`、O2O `O2ORejectionCode` 同步增加 `BUYER_ACCOUNT_NOT_OPENED`，删除或停止使用这两处对余额不足的映射。

## 6. DDL

已发布的 `003-account-and-ledger.sql` 不改原文。追加叶子变更集 `011-buyer-account-overdraft.sql`：

```sql
ALTER TABLE sc_buyer_account
    DROP CHECK ck_sc_buyer_account_amount;

ALTER TABLE sc_buyer_account
    ADD CONSTRAINT ck_sc_buyer_account_amount CHECK (frozen >= 0);

ALTER TABLE sc_buyer_account
    MODIFY COLUMN balance BIGINT NOT NULL DEFAULT 0
        COMMENT '账户总余额，单位为分；一期允许为负（透支扣减后）';

ALTER TABLE sc_buyer_account
    MODIFY COLUMN frozen BIGINT NOT NULL DEFAULT 0
        COMMENT '账户已冻结金额，单位为分；一期允许大于 balance';

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_balance;

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_frozen;

ALTER TABLE sc_account_ledger
    DROP CHECK ck_sc_account_ledger_entry_semantics;

ALTER TABLE sc_account_ledger
    ADD CONSTRAINT ck_sc_account_ledger_frozen CHECK (frozen_before >= 0 AND frozen_after >= 0);

ALTER TABLE sc_account_ledger
    ADD CONSTRAINT ck_sc_account_ledger_entry_semantics CHECK (
        (entry_type = 'DEPOSIT' AND balance_delta >= 0 AND frozen_delta = 0)
        OR (entry_type = 'FREEZE' AND balance_delta = 0 AND frozen_delta > 0)
        OR (entry_type = 'COMMIT' AND balance_delta < 0 AND frozen_delta = balance_delta)
        OR (entry_type = 'RELEASE' AND balance_delta = 0 AND frozen_delta < 0)
        OR (entry_type = 'REFUND' AND balance_delta > 0 AND frozen_delta = 0)
        OR (entry_type = 'ADJUSTMENT' AND balance_delta <> 0 AND frozen_delta = 0)
    );
```

账户汇总只保证 `frozen >= 0`。账本不再要求余额非负、也不再要求 `balance >= frozen`；`DEPOSIT` 允许 `balance_delta = 0`（零期初）。`atomicFreeze` / `atomicCommit` 的注解 SQL 与上表约束一致：冻结只校验 `status=10` 与版本；扣减只校验 `frozen >= amount` 与版本。

## 7. 测试

| 测试 | 一期期望 |
| --- | --- |
| `FundReservationIntegrationTest` 并发 20 笔冻结（期初 1100、每笔 100） | 全部成功；`frozen > balance`；`available` 为负 |
| 同 reference 重放 | 不重复冻结、不重复流水 |
| `commit` 与 `release` 并发 | 仍只能一个成功 |
| 0 余额冻结 | 成功；`frozen = amount`，`available = -amount` |
| 透支后 `commit` | `balance` 为负，`frozen` 回落 |
| 缺账户冻结 | 仍失败 |
| 期初开户 0 | 成功；负期初仍拒绝 |
| `FulfillmentAcceptanceIntegrationTest` 原 `C-BALANCE` | 改为受理成功并写预留，不再 `BUYER_BALANCE_INSUFFICIENT` |
| 卡券未开户 | 拒绝 `BUYER_ACCOUNT_NOT_OPENED` |
| `MoneyTest` | 发生额仍禁止负数 |
| Liquibase 空库 + `010→011` | 透支冻结不再撞 `CHECK` |

供应商路由原因码 `BALANCE_INSUFFICIENT` 表示**供应商账号余额**资格，与本次采购商账户弱化无关，测试与映射保持原样。

## 8. 明确不做

- 不为余额门槛加配置开关或自动开户
- 不建设入金单、充值 API、日常调余额
- 不顺手修复 O2O `commit` 用 `channelDetailNo` 当 `reservationNo`、失败建单语义、退款「先退用户再回补」、支付确认误标终态
- 不修改对账调账「禁止用 ADJUSTMENT 把账户再调成更负」的后续口径

## 9. 二期收回

恢复可用余额硬门槛时至少同时做：

1. `atomicFreeze` 加回 `balance - frozen >= amount`
2. `atomicCommit` 是否要求 `balance >= amount` 按当时是否允许历史透支账户扣减单独评审
3. `CHECK` 是否恢复 `balance >= 0 AND balance >= frozen` 按存量透支账户是否先补齐再收紧
4. 受理/预下单重新抛出 `BUYER_BALANCE_INSUFFICIENT`
5. 并发测试改回「不能超额冻结」
