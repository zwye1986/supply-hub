# 分销商管理端账户余额编辑详细设计

## 1. 范围与状态

2026-10-23 用户确认：仅改供应链后端，沿用 `PUT /admin-api/supply-chain/buyers/{buyerCode}` 和 `openingBalanceCents` 字段，允许设置调整后的账户总余额，数据库必须记录实际变动。本设计已获实施授权；代码、自动化和部署状态分别以实际证据为准。

本文取代管理端 REST 设计 §6.2 对分销商“编辑余额拒绝”的限制，并为一期账户设计中“无日常调余额”增加本管理端修正入口。供应商账户、一次性开户和对账申请审批流程不在本次范围内。本入口是用户明确授权的直接管理端余额修正，不复用对账申请审批，也不新增充值单。

## 2. 接口语义与业务边界

- POST 创建：`openingBalanceCents` 仍为一次性期初，空视为零，调用既有 `BuyerAccountOpeningService`，追加 `DEPOSIT`。
- PUT 编辑：字段省略或为 null 时不修改账户；非负整数表示目标总余额，零表示归零。当前总余额允许因历史透支为负，调整目标仍不得为负。
- 目标等于锁后实际余额时不更新账户、版本或追加流水，但允许档案正常更新。
- 冻结金额始终不变，不改资金预留；沿用一期账户允许 `frozen > balance` 的不变量，人工归零或调减也不因冻结金额大于目标而拒绝。可用余额继续为 `balance - frozen`。
- 不自动开户；缺账户报既有 `BUYER_ACCOUNT_NOT_OPENED`。非正常资金账户不允许调整；本次复用 `BUYER_STATUS_CONFLICT`。接口沿用 `BUYER_UPDATE_EXPRESSION`，不新增前端参数、菜单权限或审批步骤。
- 详情继续返回只读 `accountBalanceCents`，返回结构及 PUT 的成功布尔值不变；`BUYER_OPENING_ALREADY_DONE` 错误码保留兼容，但本 PUT 不再抛该错误。

## 3. 事务、分层与并发

`BuyerAdminCommandService.update` 校验档案并锁定 Buyer 后完成档案及关联行更新，在返回前调用新增 `tradecore.account.application.BuyerAccountAdjustmentService`。资金服务锁定该 Buyer 的 CNY 账户，读取余额、冻结和版本，使用 `Math.subtractExact` 校验有符号差额不溢出，再设置目标余额。禁止把可能为负的原余额或差额装入只允许非负发生额的 `Money`。

资金服务使用 `supplyChainTransactionManager`，传播方式 MANDATORY，必须加入档案事务，禁止 REQUIRES_NEW。账户行锁持有至提交；使用 LambdaUpdateWrapper，按主键、正常账户状态及锁后版本做 CAS，显式更新 balance、version、updatedBy、updatedAt，不修改 frozen。CAS 非一行则抛异常，由外层整体回滚。

随后通过既有唯一追加入口 `AccountLedgerWriter.append` 写流水。档案、联系人等关联行、账户与流水同事务，任何更新/插入失败全部回滚。不同请求依赖数据库账户行锁串行，与既有冻结、扣款、退款同一锁域，不依赖进程内锁，也不读取或锁定资金预留。相同目标重复提交且期间无其他账户变化时自然无资金增量；本轮不承诺跨交易的请求重试幂等。

## 4. 流水与审计

复用 `sc_buyer_account`、`sc_account_ledger` 及现有 ADJUSTMENT 约束，不新增表或修改已发布变更集。金额变化非零时追加：

- entryType 为常量 `ADJUSTMENT`，referenceType 为常量 `ADMIN_BALANCE_ADJUSTMENT`。
- 服务端生成操作 UUID，作为 referenceNo 和 causationId；entryKey 为 `ADMIN_BALANCE_ADJUSTMENT:{操作UUID}`，不是按目标金额或 Buyer 固定去重。
- balanceBefore/After 保存锁后原余额、目标余额，balanceDelta 由唯一 Writer 计算；frozenBefore/After 保存同一冻结值，frozenDelta 为零。
- operatorId 来自真实登录用户，traceId 沿用 Controller 已有追踪读取方法，经内部 UpdateBuyerCommand 传给资金服务；缺操作人或链路编号拒绝实际资金修改。
- evidenceRef 引用上述服务端操作编号，auditNote 固定说明为“管理端分销商余额修正”，证据摘要对 Buyer、账户、操作编号、操作人、余额/冻结前后值及原因的确定性内容计算 SHA-256。该证据是本次系统操作记录，不冒充外部财务凭证或审批。
- 使用现有数据库业务时钟记录发生时间。流水为权威资金记录，不修改或删除历史期初/扣款流水。本次不新增业务成功日志，避免事务提交前误报成功；现有更新入口日志仅表达请求进入，按日志规范统一动作标签。

## 5. 已知缺陷与后续版本

`BUYER-BALANCE-STALE-001` 本次明确暂缓：前端每次档案保存均回传页面余额，缺修改意图和客户端账户版本。交易后旧页面保存可能把变化抵消，网络重试也可能再次设置旧目标；本轮账户行锁和服务端 CAS 不能解决该问题。不得把服务端操作 UUID 说成客户端稳定幂等键。

完整触发场景、后续前端修改意图/账户版本/稳定操作编号协议及关闭条件见[管理端 REST 设计 §6.2.1](./2026-09-02-supply-chain-admin-frontend-api-design.md#621-余额编辑方案的已知缺陷与后续迭代)和[项目待办列表](../../待办列表.md#后续版本必须解决分销商余额编辑的旧页面覆盖问题)。后续版本必须解决，不在本次验收中标记已修复。

## 6. 自动化与联调验收

- 纯逻辑单测：编辑路由到资金服务、空字段不动账；调增/调减/归零/同值、负目标、负原余额与溢出、缺账户/非正常账户/缺审计、CAS 失败和流水异常传播。
- 真实 MySQL + Liquibase：通过真实 BuyerAdminCommandService Spring 事务代理执行调增、调减、归零；验证差额、冻结不变、版本更新、操作人、证据、链路和唯一操作编号。
- 同值及 null：账户版本与账本条数不增加；不同目标的并发请求按账户锁串行，流水链连续。
- 故障注入：真实插入调账流水后抛异常，核对档案、关联联系人、账户余额/冻结/版本、流水条数全部恢复到操作前。
- HTTP 契约：PUT 沿用 openingBalanceCents，不新增前端必传字段；模型与接口说明反映创建/编辑的不同语义，保留非负校验、更新权限及返回结构。
- 运行供应链适用纯逻辑、契约和架构门禁，以及真实账户/Buyer 相关集成回归。仅跳过供应链以外模块测试。人工联调步骤与自动化命令、实际结果维护 `docs/集成测试.md`，不以本地通过替代环境发布和页面验收。

## 7. 本轮实现与验证状态

后端及测试已按本设计实现；定向单测 23 项、真实 MySQL/HTTP/权限及受影响回归 78 项通过。供应链扩展纯逻辑/架构 2544 项通过；额外 Liquibase 总量断言 2 项失败已在未修改的 f5111056 基线独立复现，不宣称扩展整条命令全绿。实际命令、失败范围、故障注入及人工步骤见[集成测试记录](../../集成测试.md#2026-10-23-分销商账户余额编辑仅后端)。未部署，旧页面覆盖缺陷仍待后续版本解决。
