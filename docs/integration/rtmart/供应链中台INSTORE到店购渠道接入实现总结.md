# 供应链中台 INSTORE 到店购渠道接入实现总结

> 对应设计文档：`aifuli-mall-pro/docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md`
> 代码仓库：`/Users/mac/Documents/codeSpace/code_bak/aifuli-mall-pro`
> 分支：`dev-0812-sc`（截至 2026-08-21）

---

## 1. 背景与定位

大润发到店扫码购渠道由旅通（ltsw.cn）支付/POS 聚合中台作为渠道方，中台扮演"商户"提供会员/建单/查单/退款接口。本实现将该渠道作为供应链中台的**第三个 `orderType=INSTORE`** 接入，与既有 `CARD`（标准商品履约）、`O2O`（ele.me/盒马配送）平行，**不硬合**：独立详情模块、独立状态机、独立入站契约、独立渠道服务；共享 `BuyerOrder` 总表、`trade-core` 资金账本与 `Outbox`/`PersistentTimer` 基础设施。

核心差异（构成独立 `orderType` 的依据）：
- 履约形态：到店即完成，支付成功即 `SUCCEEDED`，无 225 推送与渠道通知收敛。
- 状态同步：**pull 模型**，旅通主动调查支付/退款状态，中台无 `CHANNEL_NOTIFY` Outbox。
- 身份绑定：每个采购商租户独立 `app_key`；稳定用户绑定与一次性入口凭证分离，旅通持 token 调中台换会员。
- 订单:支付身份 = **1:1**；`trade_id` 是到店购详情上的支付幂等键，过渡支付表只能保留一行卫星记录。

---

## 2. 已完成内容

### 2.1 设计文档

- `docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md`
  - 已明确 1:1 支付身份、`014` 前向迁移、租户独立 `app_key`、多枚并存的一次性凭证、完整请求摘要、真实账户返还与生产测试门禁。
- `docs/adr/0001`～`0005`：记录独立 INSTORE、单支付身份、`user_code`、租户作用域与凭证分表决策。
- `docs/architecture/供应链中台多场景概要设计.md`：补入独立 INSTORE 时序及退款资金闭环。

### 2.2 当前数据库 Schema（Liquibase 变更集 `013`～`018`）

> 注：设计文档原写 `012`，实际 `012-o2o-supplier-order.sql` 已存在，故落为 `013`。

- **6 张新表**：
  - `sc_instore_channel_user_binding` — 014 后新流程使用独立入口凭证表；绑定行上的匿名 ID/token 列只作历史兼容，017 将稳定业务键改为项目会员作用域。
  - `sc_instore_channel_order` — 渠道订单头，1:1 对应 `BuyerOrder`，承载旅通 `order_id`。
  - `sc_instore_payment_flow` — 014 已通过 `uk(instore_order_id)` 锁死 1:1；`trade_id` 仍是支付永久幂等键。
  - `sc_instore_refund_case` — 当前保留 `payment_flow_id` 兼容引用；领域语义和并发校验按 `instore_order_id` 整笔交易执行。
  - `sc_instore_operation` — 可恢复外部副作用，操作类型无 `CHANNEL_NOTIFY`（pull 模型）。
  - `sc_instore_supplier_order` — 固定商户供应商订单，对标 `sc_o2o_supplier_order`；到店即完成，`fulfillment_status` 创建即 30(SUCCEEDED)。
- **`sc_supply_chain_order` 总表前向扩展**（DROP + ADD CHECK，不改 O2O/CARD 已发布表行）：
  - `ck_sc_order_type`：`order_type IN ('CARD','O2O','INSTORE')`
  - `ck_sc_order_card_fields`：新增 INSTORE 分支（`buyer_order_no` 必填、`acceptance_receipt_id` 为空、不绑定 SKU、`buyer_settlement_amount>0`、`detail_type='INSTORE_CHANNEL'`、`display_supplier_id` 非空）
  - `order_type`/`detail_type` 列注释同步扩展。
- `014-instore-production-invariants.sql`：租户快照、独立入口凭证、支付 1:1 等生产约束。
- `015-timer-outbox-schema-repair.sql`：Timer/Outbox 基础设施修复。
- `016-project-integration-route.sql`：采购项目与版本化集成档案路由。
- `017-instore-project-cashier-route.sql`：INSTORE 绑定改为
  `(project_id,buyer_user_id,channel_code)`，订单头固化 `project_id/cashier_profile_version_id`；
  无法可靠推导项目的历史 INSTORE 行在永久 DDL 前失败关闭。
- `018-instore-member-entry-route.sql`：入口凭证项目化——`sc_instore_entry_credential` 增加
  `project_id/member_profile_version_id/company_code/revoked_at/revoke_reason`；无法回填项目或档案版本的
  历史在途凭证在永久 DDL 前失败关闭，可回填者由迁移按 `MIGRATION_UNFROZEN_ROUTE` 转 REVOKED；
  ACTIVE/PROCESSING 必须冻结档案版本与企业组快照，REVOKED 必须同时携带撤销时间与稳定撤销原因（CHECK 强制）。
- `source_system/external_tenant_id` 继续保留为 SaaS 路由快照，但不再参与会员业务唯一键。
- `db.changelog-1.0.0.yaml` 已按顺序注册至 `018`（空库共 18 个 changeSet）。

### 2.3 领域枚举与常量（`border.instore.domain`，11 个）

| 枚举/常量类 | 稳定值 |
|---|---|
| `InstoreChannelCode` | `RT_MART` |
| `InstoreOrderStatus` | CREATED=0/PROCESSING=10/COMPLETED=20/CANCELLED=30/FAILED=40/MANUAL=90 |
| `InstorePaymentFlowStatus` | PENDING=0/PROCESSING=10/PAID=20/FAILED=30/CANCELLED=40/MANUAL=90 |
| `InstoreRefundStatus` | PENDING=0/QUEUED=10/BUYER_REFUNDING=20/SUCCESS=30/FAILED=40/COMPLETED=60/MANUAL=90（无 50 通知渠道中） |
| `InstoreRefundType` | `CHANNEL_REFUND`/`CANCEL_REFUND` |
| `InstoreOperationType` | `PREORDER`/`PAYMENT_QUERY`/`CANCEL_PAY`/`BUYER_REFUND`/`REFUND_QUERY`（无 `CHANNEL_NOTIFY`） |
| `InstoreOperationStatus` | PENDING=0/RUNNING=10/UNKNOWN=20/SUCCESS=30/FAILED=40/MANUAL=90 |
| `InstoreIdempotencyKeys` | `preorder`/`paymentQuery`/`cancelPay`/`buyerRefund`/`refundQuery`/`cancelRefundChannelNo` |
| `InstoreOutboxCodes` | `AGGREGATE_INSTORE_ORDER`/`AGGREGATE_INSTORE_REFUND_CASE`/`EVENT_INSTORE_ORDER_SUMMARY_CHANGED`/`TARGET_BUYER_EVENT_RPC` |
| `InstoreTimerTaskTypes` | `INSTORE_OPERATION_RECOVER` |
| `InstoreRejectionCode` | `TOKEN_INVALID`/`CHANNEL_USER_BINDING_MISSING`/`IDENTITY_CONFLICT`/`SUPPLIER_CHANNEL_BINDING_NOT_FOUND`/`BUYER_ACCOUNT_NOT_OPENED`/`BUYER_BALANCE_INSUFFICIENT`/`REFUND_AMOUNT_EXCEEDED`/`REFUND_ALREADY_IN_PROGRESS`/`CANCEL_REFUND_FAILED` |

**扩展既有常量**：
- `SupplyChainOrderTypes`：+`ORDER_TYPE_INSTORE`、`DETAIL_TYPE_INSTORE_CHANNEL`、4 个 INSTORE 业务阶段常量。
- `BusinessNoPrefixes`：+`IUB_`/`ISC_`/`IPF_`/`IRC_`/`IOP_`/`ISO_`。
- `FundReferenceType`：+`INSTORE_ORDER`。
- `OrderStatusAuditCodes`：+5 个 INSTORE 源事件常量。
- `SupplyChainErrorCodeConstants`：+8 个 INSTORE 错误码（1_050_001_026~033）+ 重试指令映射。

### 2.4 核心实体 DO（`border.instore.dal.dataobject`，6 个）

`InstoreChannelUserBindingDO`、`InstoreChannelOrderDO`、`InstorePaymentFlowDO`、`InstoreRefundCaseDO`、`InstoreOperationDO`、`InstoreSupplierOrderDO`。

均镜像 O2O DO 模式：MyBatis-Plus `@TableName`/`@TableId(IdType.AUTO)`/`@Version`、Lombok `@Data`/`@Builder`、`Byte` 承载可空标记位、`byte[]` 承载指纹/摘要。

### 2.5 Repository 层 Mapper（`border.instore.dal.mysql`，6 个）

| Mapper | 关键 default 方法 |
|---|---|
| `InstoreChannelUserBindingMapper` | `selectByProjectUser`；旧 token/租户查询仅保留 deprecated 兼容入口 |
| `InstoreChannelOrderMapper` | `selectByChannelOrder`/`selectByOrderId`/`advanceStatus`/`accumulateRefundAmounts` |
| `InstorePaymentFlowMapper` | `selectByChannelTrade`/`selectByOutTradeNo`/`advanceToCashierCreating`/`recordCashierOrder`/`confirmPayment`/`cancelFlow`/`attachSupplierOrder` |
| `InstoreRefundCaseMapper` | `selectByChannelRefundNo`/`selectByRefundCaseNo`/`selectActiveByFlow`/`countByFlow`/`selectByFlowAndType`/`advanceStatus` |
| `InstoreOperationMapper` | `selectByIdempotencyKey`/`selectByOperationNo`/`claimPreorder`/`recordResult` |
| `InstoreSupplierOrderMapper` | `selectByOrderId`/`selectByPaymentFlowId` |

全部遵循 AGENTS.md §8：优先 `LambdaQuery/UpdateWrapper`，不新增注解 SQL。

### 2.5a 订单状态汇总策略（`border.order.domain`）

- `InstoreOrderSummaryPolicy implements OrderSummaryPolicy`：`orderType()` 返回 `INSTORE`，按 `InstoreOrderStatus` 映射公共主状态/业务阶段；与 `CardOrderSummaryPolicy`/`O2OOrderSummaryPolicy` 平行注册到 `OrderSummaryPolicyRegistry`，互不冲突（按 orderType 唯一注册的语义不变）。
- 到店即完成：`COMPLETED`→`SUCCEEDED(30)`；`CANCELLED`→`CANCELLED(50)`；`FAILED/MANUAL`→`FAILED/MANUAL`。

### 2.6 Service 与 Facade 层

**`InstoreChannelUserBindingResolver`** — 项目会员稳定绑定解析器（已完成项目化）：
- 唯一签发入口 `resolveOrCreateStableBinding(buyerId, projectId, buyerUserId, channelCode, memberRoute)`：
  按项目会员三元组读取或创建稳定绑定；`memberRoute` 必须为指向同一项目的 MEMBER_PROFILE 能力，
  SaaS 路由快照 `(sourceSystem,externalTenantId)` 变化时以版本 CAS 刷新，影响行数不是 1 返回
  `INSTORE_CONCURRENT_UPDATE`。
- 旧租户四元组签发重载已删除，`selectByTenantUser` 无任何生产调用；`ArchitectureTest`
  禁止应用层回调该废弃查询。短期 token 在独立 `sc_instore_entry_credential`，绑定行不写匿名 ID/token。

**`InstoreRefundSequencer`** — `nextSequence(paymentFlowId)`：按支付流水已有 Case 数 +1（vs O2O 按 `o2o_detail_id`）。

**`InstoreChannelLifecycle`** — 核心编排，7 个事务方法：

| 方法 | 语义 |
|---|---|
| `createPaymentOrder` | 按项目会员身份 `(projectId,buyerUserId)` 校验绑定，固化 `projectId/cashierProfileVersionId/sourceSystem/externalTenantId`；按渠道业务键幂等建单、冻结资金并创建 PREORDER 操作。跨项目重放失败关闭。 |
| `claimPaymentPreparation` | CAS 抢占 PREORDER（0→10），流水推进 `PROCESSING`；抢占失败只查单不调收银台。 |
| `recordCashierOrder` | 按 invocationId+expectedVersion 记录收银台结果；CREATED 通过流水 CAS 同时回写 `cashierUrl/cashierReference`，所有条件更新检查影响行数。 |
| `confirmBuyerPayment` | 已 PAID 幂等回放；终态流水/订单头抛 `ORDER_STATE_CONFLICT`；commit 资金 + 创建供应商订单（SUCCEEDED=30，到店即完成）+ 推进流水 PAID + 推进订单头 COMPLETED + 累计已支付金额 + 写 `EVENT_INSTORE_ORDER_SUMMARY_CHANGED` Outbox。 |
| `cancelPaymentFlow` | 未支付：已 CANCELLED 幂等回放，否则 release 资金 + 关单 + 订单头无已支付流水则 CANCELLED；已支付：只落 `CANCEL_REFUND` Case（稳定键 `cancelRefundChannelNo(tradeId)`），并发撞键回放，已有 CHANNEL_REFUND 则 `INSTORE_CANCEL_REFUND_FAILED`。 |
| `acceptRefund` | 按 `(channelCode,refund_bn)` 全局幂等；行锁渠道订单头计算可退 = 已付 − 已累计 − 在途（退款中+排队），超额抛 `INSTORE_REFUND_AMOUNT_EXCEEDED`；`uk_sc_instore_refund_active(payment_flow_id,buyer_refunding_flag)` 数据库层保证同一支付流水至多一个活动退款——首笔 CASE 抢占派发（BUYER_REFUNDING + active_refund_case_id 指针 + BUYER_REFUND 操作），并发第二笔 QUEUED 排队不外呼。 |
| `applyBuyerRefundResult` | 幂等收敛：实际退款金额缺失或 ≠ 申请金额时 Case 转 MANUAL（`result_reason_code=REFUND_AMOUNT_MISMATCH`）并抛 `INSTORE_REFUND_AMOUNT_MISMATCH`，不返还不累计；金额一致时经 `BuyerAccountReturnService` 幂等返还（`REFUND_RETURN:{refundCaseNo}` 账本唯一键）+ 三项累计（渠道发起/采购商实际/账户返还）+ 释放 active 指针 + 派发排队 Case + 售后投影；未知状态抛 `IllegalArgumentException`。 |
| `queryPaymentStatus` / `queryRefundStatus` | 只读投影（中台内部 `pay_status`/`status`），不上 HTTP，不映射旅通 0/1/2。 |

**`InstoreRefundProjection` + `InstoreOrderSummaryPolicy` 售后投影** — 聚合订单下未收敛（含 MANUAL）退款 Case
与累计退款金额：有未收敛 Case → `aftersale_status=10` 处理中；无未收敛时按累计/实付投影 20 部分退款 /
30 全额退款 / 0 无售后；BuyerOrder 主状态不受退款影响。

**`BuyerAccountReturnService`**（trade-core）— 已扣采购资金返还唯一入口：以 `REFUND_RETURN:{refundCaseNo}`
为永久幂等键原子返还账户并追加 `REFUND` 账本条目（balance_delta=+amount，frozen_delta=0）；同键不同金额返回
`INSTORE_ACCOUNT_RETURN_CONFLICT`；只拥有账户和账本，不依赖 INSTORE 模块（`ArchitectureTest` 强制）。

**`InstoreChannelCommandFacade`** — 解析冻结的 `projectId + cashierProfileVersionId + CASHIER` 路由；
建单在两段 Lifecycle 本地事务之间调用 `BuyerCashierPort.createCashierOrder`，使用原字符串
`buyerUserId/outTradeNo`，返回并落库 `cashierUrl/cashierOrderNo`；退款受理在受理事务与外呼之间编排——
抢占派发的 Case 以原 `refundCaseNo` + 原 invocationId（operationNo）调用 `BuyerCashierPort.refund`，
排队 Case 只落库不外呼。幂等回放、抢占失败和调用超时均不重建业务号。

**会员入口编排（项目路由切片）** — `InstoreBuyerEntryFacade`/`InstoreChannelMemberFacade` 及支撑服务：
- `InstoreEntryCredentialService`：HOME/DETAIL 入口凭证签发、claim 与按签发版本撤销；token 仅存 SHA-256
  摘要，状态机 ACTIVE(10)→PROCESSING(15)→USED(20)/EXPIRED(30)/REVOKED(40)；claim 以
  `(projectId,channelCode,token)` 定位，HOME 与 DETAIL 均为合法会员查询用途，凭证冻结
  `memberProfileVersionId/companyCode`。
- `BuyerProjectAccessResolver`/`InstoreEntryAccessService`：入口前置校验——项目归属当前采购商、
  渠道商户绑定启用、DETAIL 订单详情归属与状态合法（稳定错误码）。
- `InstoreBuyerEntryFacade` + `InstoreBuyerEntryApiImpl`：HOME 按项目路由签发凭证并外呼旅通入口 URL；
  HOME 的 `companyCode` 过渡期从配置 `aifuli.supply-chain.instore.home.company-code` 读取（默认 `L0000A`），
  请求体只接受经纬度；DETAIL 只接受 `orderNo`，从订单头固化快照取 `tradeId/companyCode`；
  URL 外呼失败按签发版本撤销凭证，调用方重试获得新 token。
- `InstoreChannelMemberFacade` + `InstoreChannelMemberApiImpl`：接口①按凭证冻结的项目与 MEMBER_PROFILE
  档案版本解析路由，外呼 `BuyerMemberProfilePort` 获取手机号/姓名；中台不落会员资料，
  项目/渠道不匹配在任何 CAS 前失败（`INSTORE_TENANT_SCOPE_CONFLICT`）。
- `SaaSMemberProfileAdapter`（`integration/saasmall`）：`BuyerMemberProfilePort` 的生产实现，经专用
  Feign `MemberProfileResolveApi` 调 member-server，三元身份全程 String 透传；完成日志只含租户摘要与
  状态，`CommonResult` 错误/RPC 异常映射 `UNAVAILABLE` 而非会员不存在。
- 商城 BFF（`aifuli-module-trade-server`）：App `POST /app-api/trade/instore/rtmart/home-entry`，
  登录用户 HMAC 调中台 HOME 入口；请求体只有经纬度，不持有旅通密钥与 `companyCode`。设计见
  `docs/superpowers/specs/2026-08-28-saas-mall-rtmart-home-entry-design.md`。

**`InstorePaymentConvergenceService` / `InstoreRefundConvergenceService` / `InstoreBuyerCashierCallbackFacade`** —
支付与退款共用同一收敛模式：SaaS 回调优先直接确认；渠道查询只在本地非终态时按订单固化版本和原业务号
（`outTradeNo`/`refundCaseNo`）权威查询，SUCCEEDED 结果与回调共用同一 Lifecycle 收敛方法
（`confirmBuyerPayment`/`applyBuyerRefundResult`），终态幂等跳过。两条外部入口都校验项目归属。

**`InstoreOperationRecoveryHandler`** — PREORDER/PAYMENT_QUERY 的 Timer 恢复按原 `outTradeNo` 调统一支付
收敛服务；BUYER_REFUND/REFUND_QUERY 按数据库固化的原 `refundCaseNo` 调统一退款收敛服务，Case 已终态
（含 MANUAL）时跳过外呼；取消恢复仍保留原 `tradeId` 决策，等待取消收银台切片接线。

**`BuyerCashierPort`** — 四个生产命令均强制携带 `BuyerIntegrationRoute`；`buyerUserId` 为 String；
退款立即结果和权威查询结果均可返回 `actualRefundAmountCny`。O2O 暂通过显式 deprecated legacy 入口联调，
INSTORE 禁止使用无路由方法。

### 2.7 自动化测试

| 测试类 | 覆盖点 |
|---|---|
| `InstoreStatusEnumsTest` | 4 个状态枚举 `fromCode` 往返；未知码拒绝；`InstoreRefundStatus` 无 `NOTIFYING_CHANNEL`、`InstoreOperationType` 无 `CHANNEL_NOTIFY`。 |
| `InstoreIdempotencyKeysTest` | 6 个幂等键构造稳定，不生成新业务号。 |
| `InstoreRefundSequencerTest` | 无 Case 返回 1；已有 N 个返回 N+1。 |
| `InstoreChannelUserBindingResolverTest` | 项目键读取/创建稳定绑定；路由快照变化以版本 CAS 刷新；停用绑定拒绝；跨项目/非 MEMBER_PROFILE 路由拒绝；并发首创建回读。 |
| `InstoreChannelLifecycleTest` | 建单幂等、项目绑定与金额冲突、冻结与 PREORDER、抢占/记录收银台结果、支付确认、未支付取消、已支付取消落 Case、退款受理（可退校验/串行抢占/排队）、退款收敛（金额校验/账户返还/累计/派发下一个/售后投影/幂等回放）与只读查询等。 |
| `InstoreRefundAcceptanceTest` | Facade 退款受理编排：抢占派发外呼原 refundCaseNo+invocationId、排队不外呼、在途超限拒绝、幂等回放、项目归属校验。 |
| `InstoreRefundConvergenceTest` | 统一退款收敛：回调优先、本地终态跳过外呼、权威查询 SUCCEEDED/PROCESSING/FAILED、金额不一致稳定错误码、Timer 恢复共用路径。 |
| `InstoreRefundSettlementTest` | 收敛落账：`REFUND_RETURN:{refundCaseNo}` 幂等返还、三项累计、活动指针释放与排队 Case 派发、售后投影 10/20/30/0。 |
| `InstoreOperationRecoveryHandlerTest` | 操作不存在拒绝；终态跳过；PREORDER/PAYMENT_QUERY 查原 outTradeNo；CANCEL_PAY 查原 tradeId；BUYER_REFUND/REFUND_QUERY 按原 refundCaseNo 收敛且 Case 终态跳过外呼；退款操作缺 refundCaseId 拒绝；流水缺失拒绝。 |
| `BuyerCashierPortContractTest` | 路由必填、字符串会员标识、原支付/退款业务号、退款实际金额与成功结果校验。 |
| `InstoreChannelCommandFacadeTest` | 路由解析、事务外建单、URL 返回、幂等回放、抢占失败与超时保留原号。 |
| `InstorePaymentConvergenceTest` | 回调优先、项目归属、权威查询成功/处理中、本地终态跳过外呼。 |
| `InstoreProjectCashierRouteMigrationIntegrationTest` | 真实 MySQL 验证 017 失败关闭、项目唯一键、共享 externalTenantId 隔离与二次启动幂等。 |
| `InstoreEntryCredentialServiceTest` | 凭证状态机：签发冻结项目/档案版本/企业组、并发唯一 claim、查询失败释放回 ACTIVE、按签发版本撤销与重放拒绝。 |
| `BuyerProjectAccessResolverTest` / `InstoreEntryAccessServiceTest` | 入口前置校验：项目归属、渠道商户绑定启用、DETAIL 订单详情归属与状态的稳定错误码。 |
| `InstoreBuyerEntryFacadeTest` / `InstoreChannelMemberFacadeTest` | HOME/DETAIL 编排与接口①会员解析：路由冻结、URL 外呼失败撤销、凭证用途/项目不匹配在 CAS 前失败、外呼身份三元组断言。 |
| `InstoreBuyerEntryApiImplTest` / `InstoreChannelMemberApiImplTest` | RPC 层入参校验与可信上下文（项目/渠道）透传。 |
| `SaaSMemberProfileAdapterTest` | 路由白名单与租户上下文恢复、三元 String 透传、`UNAVAILABLE/NOT_FOUND` 错误分类、完成日志租户摘要与敏感值零泄漏。 |
| `InstoreMemberEntryApiContractTest` / `BuyerMemberProfilePortContractTest` / `RtmartEntryUrlPortContractTest` | 会员入口 API 与会员/入口 URL 出站 Port 的契约一致性（字段类型、必填、稳定错误映射）。 |
| `InstoreEntryCredentialIntegrationTest` | 真实 MySQL：凭证签发冻结、并发唯一 claim、跨项目消费拒绝、URL 外呼失败撤销 REVOKED、摘要-only 持久化。 |
| `InstoreMemberEntryRouteMigrationIntegrationTest` | 真实 MySQL：018 凭证项目化回填失败关闭、历史在途转 REVOKED、快照/撤销事实 CHECK 拒绝、二次启动 0 changeSet。 |
| `InstoreMemberEntryFlowIntegrationTest` | 真实 MySQL 纵向：HOME/DETAIL 签发→旅通入口 URL 外呼→接口①会员解析端到端；凭证一次性消费进 USED、跨项目消费在 CAS 前失败且不触发会员外呼、共享租户稳定绑定不合并。 |
| `InstoreRefundFlowIntegrationTest` | 真实 MySQL 纵向：退款串行闭环——首笔受理抢占派发（原 refundCaseNo+invocationId 外呼）、第二笔排队不外呼、可退超限拒绝、回调收敛（账户返还/账本/三项累计/活动指针切换/排队 Case 自动派发/售后投影）、金额不一致转 MANUAL 不返还、回调丢失权威查询兜底收敛、终态回调幂等回放。 |

---

## 3. 待完成内容

### 3.1 仍待完成的生产核心不变量

- ~~`BuyerAccountReturnService` 幂等返还（`REFUND_RETURN:{refundCaseNo}` 账本键）~~ ✅ 已完成（退款串行切片）。
- ~~退款串行抢占/排队/防并发超退；金额不一致转人工不入账~~ ✅ 已完成（退款串行切片，`InstoreRefundFlowIntegrationTest` 门禁）。
- 支付确认链路中剩余的 SupplierOrder/订单头连续 CAS 仍需真实 MySQL 事务故障注入门禁覆盖。
- 退款回调与查询的**真实并发竞争**（多线程同时收敛同一 refundCaseNo）目前由数据库唯一键与 CAS 兜底，
  尚未补多线程集成门禁。

### 3.2 API 契约层（会员入口与支付/退款 Wire 已实现）

`aifuli-module-supply-chain-api/.../api/instore/`：
- **已实现** `InstoreBuyerEntryApi`（`getHomeEntry`/`getOrderDetailEntry`，旅通 `getRtPosUrl/home/detail`）、
  `InstoreChannelMemberApi.resolveMember`（旅通接口①）及 5 个 DTO，由
  `InstoreBuyerEntryApiImpl`/`InstoreChannelMemberApiImpl` 提供 RPC 实现；契约一致性由
  `InstoreMemberEntryApiContractTest` 门禁。
- **已实现** `InstoreChannelCommandApi` 五方法（`createPaymentOrder`/`queryPaymentStatus`/`cancelPaymentFlow`/
  `acceptRefund`/`queryRefundStatus`）、`InstoreBuyerCashierCallbackApi` 两方法（`confirmPayment`/
  `reportRefundResult`，SaaS 收银台回调）及 12 个 Wire DTO，由 `InstoreChannelCommandApiImpl`/
  `InstoreBuyerCashierCallbackApiImpl` 提供 RPC 实现；命令侧身份来自 `ChannelContext`、回调侧来自新增
  `ProjectContext`；Wire 建单经 `InstoreChannelCommandFacade.createPaymentOrder` 编排（稳定绑定 + 当前
  CASHIER 档案版本）；契约一致性由 `InstorePaymentCommandApiContractTest` 门禁。
- 已实现 `InstoreChannelCommandFacade`、`InstoreBuyerCashierCallbackFacade` 和统一支付收敛服务；
  Wire DTO/RPC 接口已暴露，仍缺真实 SaaS 收银台 Adapter。
- `SaaSMemberProfileAdapter` 已按冻结的 `AIFULI_MALL/RPC/SAAS_MEMBER_RPC/v1` 路由调用
  member-server 专用 `MemberProfileResolveApi`；`sourceSystem + externalTenantId + buyerUserId`
  全程以 String 传输，只有 member-server 内部兼容解释当前 `member_user.id`。
  错误分类：路由不支持或 RPC 异常映射 `UNAVAILABLE`，会员不存在或手机号为空映射 `NOT_FOUND`。
  日志只输出 project/profile、外部租户 SHA-256 前 12 位摘要、状态与耗时；ArchUnit 门禁禁止
  `integration.saasmall` 包调用 `Long.parseLong/valueOf`，`buyerUserId` 禁止直接转 `Long`。
- 中台先固化 `outTradeNo`，再由项目 CASHIER 档案解析
  `(sourceSystem,externalTenantId,buyerUserId)` 调 SaaS 收银台并取得 `cashierUrl/cashierOrderNo`；
  支付/退款回调优先，接口③/⑥和 Timer 按原业务号权威查询兜底，所有来源共用同一幂等收敛路径。

### 3.3 渠道服务（未开始）

`aifuli-channel-rtmart-server`（独立 spring-boot 子模块）：
- 旅通 HTTP 协议适配：`/rtmart/channel/{member,createOrder,queryPay,cancelPay,refund,queryRefund}` 端点。
- RSA 双向验签（商城私钥签响应、旅通公钥验请求）、`timestamp` 3 分钟过期、`nonce_str` 防重放。
- 每个采购商租户独立配置 `app_key`，映射为 `(sourceSystem, externalTenantId)`，禁止跨租户共用。
- hutool `MapKeySort` 签名串拼接（按 key 字典序 `key+value`，排除 `sign`，`Boolean→1/0`，`Map/List` 递归）。
- 错误码映射 `E0000/E4001/E4002/E4003/E4004/E5000` → 中台稳定错误码。
- 自有库：旅通交易注册表、退款记录缓存，不进中台 Liquibase。

### 3.4 集成测试状态

- 017/018 的真实 MySQL/Liquibase/唯一约束测试已落地（项目收银台路由与凭证项目化迁移）；会员入口纵向
  `InstoreMemberEntryFlowIntegrationTest` 覆盖 HOME/DETAIL 签发→入口 URL 外呼→接口①会员解析的端到端
  与跨项目隔离；退款串行纵向 `InstoreRefundFlowIntegrationTest`（2026-08-23 通过，3 个测试）覆盖
  受理抢占派发/排队/超限拒绝/回调收敛/金额不一致 MANUAL/权威查询兜底/幂等回放端到端；支付回调/查询
  并发仍是 Mock 单测，尚未补真实事务竞争测试。
- 重点：支付卫星 1:1 数据库约束、完整摘要幂等、支付合并 CAS、凭证并发单次消费、账户返还幂等、并发部分退款防超额。
- `*ContractTest`：旅通 `queryPay`/`queryRefund` 两个权威查询契约一致性（生产门禁）；会员入口
  API 与出站 Port 契约（`InstoreMemberEntryApiContractTest`/`BuyerMemberProfilePortContractTest`/
  `RtmartEntryUrlPortContractTest`）。
- `*ArchitectureTest`：ArchUnit 验证 `border.instore` 不访问其他场景 Mapper、Facade 不跨模块访问 Repository、
  `api.instore` 不依赖 integration/dal、DAL/Outbox/Timer 不引用 `InstoreMemberRespDTO`、应用层不回调
  废弃租户四元组查询；由 JUnit 5 测试方法显式执行每条规则，禁止出现 Surefire 发现类但执行数为 0 的假绿。

### 3.5 已确认且实现必须遵守的业务语义

1. 同一 `order_id` 只有一个 `trade_id`，不实现 1:N 支付。
2. 会员接口①由中台转发采购商；会员业务身份为 `(projectId,buyerUserId)`，外呼时由会员档案翻译为 SaaS 租户三元组，中台不落手机号姓名。
3. 已支付取消在从未存在退款协同时走全额动账退款；接口⑤允许部分退款。
4. 同一用户可同时持有多枚短期凭证，每枚只消费一次，默认十分钟过期。
5. 每个采购商租户独立 `app_key`；`company_code` 只绑定大润发 Supplier，`shop_code` 只作门店快照。
6. `getRtPosUrl/detail` 面向采购商用户，校验订单归属后新签绑定该订单的 `DETAIL` 凭证。
7. 供应链中台承担分销商角色；旅通、大润发和 POS 在核心领域统一视为 `RT_MART` 外部渠道，不分别建模。
8. 建单由中台先固化 `outTradeNo`，再调用对应 SaaS 收银台取得 `cashierUrl/cashierOrderNo`；支付和退款回调优先、原业务号权威查询兜底，退款成功返回实际退款金额。

### 3.6 当前代码已知差距

- `InstoreChannelLifecycle.acceptRefund`/`applyBuyerRefundResult`/`cancelPaymentFlow`：尚未实现已批准的真实账户返还与账本口径，目标见详细设计 §4.5。
- `InstoreChannelLifecycle.acceptRefund`：当前金额校验未锁定整笔交易，也未统计排队/处理中 Case，不能防并发超退。
- `InstoreOperationRecoveryHandler`：支付恢复已调用统一权威查询；Timer 任务注册/扫描到该 Handler 的生产接线仍待完成。
- `BuyerCashierStubAdapter` 只用于受控联调，真实 SaaS RPC/HTTP Adapter 与契约测试未完成，不满足生产上线门禁。

---

## 4. 文件清单

```
docs/superpowers/specs/
  2026-08-18-rtmart-instore-channel-design.md          [设计]

aifuli-module-supply-chain-api/.../error/
  SupplyChainErrorCodeConstants.java                    [扩展 +8 错误码]

aifuli-module-supply-chain-server/src/main/resources/db/changelog/releases/1.0.0/
  013-instore-channel.sql                               [新增 6 表 + 总表 CHECK 扩展]
  014-instore-production-invariants.sql                 [凭证/租户/1:1 生产约束]
  015-timer-outbox-schema-repair.sql                    [基础设施修复]
  016-project-integration-route.sql                    [项目集成档案]
  017-instore-project-cashier-route.sql                [INSTORE 项目与收银台版本]
  018-instore-member-entry-route.sql                   [入口凭证项目化与失败关闭迁移]
  db.changelog-1.0.0.yaml                               [注册至 018，空库共 18 个 changeSet]

aifuli-module-supply-chain-server/.../border/order/domain/
  SupplyChainOrderTypes.java                            [扩展 INSTORE 类型/阶段]
  OrderStatusAuditCodes.java                            [扩展 5 个 INSTORE 源事件]
  InstoreOrderSummaryPolicy.java                        [新增]

aifuli-module-supply-chain-server/.../border/instore/
  domain/   (11 个枚举/常量类)
  dal/dataobject/   (6 个 DO)
  dal/mysql/   (6 个 Mapper)
  application/   (Lifecycle/Resolver/Sequencer/RecoveryHandler/PaymentConvergence)

aifuli-module-supply-chain-server/.../application/facade/
  InstoreChannelCommandFacade.java
  InstoreBuyerCashierCallbackFacade.java

aifuli-module-supply-chain-server/.../infrastructure/identity/
  BusinessNoPrefixes.java                              [扩展 6 个前缀]
aifuli-module-supply-chain-server/.../tradecore/account/domain/
  FundReferenceType.java                               [扩展 INSTORE_ORDER]

aifuli-module-supply-chain-server/src/test/.../border/instore/
  domain/InstoreIdempotencyKeysTest.java
  domain/InstoreStatusEnumsTest.java
  application/InstoreChannelLifecycleTest.java
  application/InstoreChannelUserBindingResolverTest.java
  application/InstoreOperationRecoveryHandlerTest.java
  application/InstoreRefundSequencerTest.java

aifuli-module-supply-chain-server/src/test/.../application/facade/
  InstoreChannelCommandFacadeTest.java
  InstorePaymentConvergenceTest.java

aifuli-module-supply-chain-server/src/test/.../integration/buyer/
  BuyerCashierPortContractTest.java
```

---

## 5. 编译与测试验证

- 2026-08-21 使用 JDK 21 干净执行供应链全量单测：API 41 个、server 160 个，共 201 个测试通过，
  0 失败、0 错误、0 跳过。
- `SupplyChainModuleArchitectureTest` 由标准 JUnit 5 显式执行 7 条 ArchUnit 规则，7 个测试全部通过；
  已验证 INSTORE Facade 不直连项目集成 DAL，且任何 `buyerUserId` 字段均不是 `Long`。
- `BuyerCashierPortContractTest` 由 `supply-chain-integration` profile 的 Failsafe 执行，4 个契约测试通过。
- `InstoreProjectCashierRouteMigrationIntegrationTest` 与 `SupplyChainLiquibaseIntegrationTest` 使用两个独占空
  MySQL 8.4 数据库执行，2 个测试通过；空库执行 17 个 changeSet，第二次启动执行 0 个。
- 2026-08-23 会员入口纵向切片（Task 9）门禁：server 全量单测 253 个、API 模块 52 个全部通过；
  ArchitectureTest 扩至 10 条规则（新增会员入口三条：API 层不依赖 integration/dal、DAL 与基础设施
  不触碰 `InstoreMemberRespDTO`、应用层禁止调用 `selectByTenantUser`）；契约
  `InstoreMemberEntryApiContractTest`（6）/`BuyerMemberProfilePortContractTest`（13）/
  `RtmartEntryUrlPortContractTest`（12）通过；`InstoreMemberEntryFlowIntegrationTest`（2）、
  `InstoreEntryCredentialIntegrationTest`（1）、`InstoreMemberEntryRouteMigrationIntegrationTest`（1）、
  `SupplyChainLiquibaseIntegrationTest`（1）在四个独占空库逐一通过，空库均执行 18 个 changeSet。
- 2026-08-23 退款串行切片门禁：退款相关单测 71 个通过（`InstoreRefundAcceptanceTest`/
  `InstoreRefundConvergenceTest`/`InstoreRefundSettlementTest`/`InstoreRefundSequencerTest`/
  `InstoreOperationRecoveryHandlerTest`/`InstoreChannelLifecycleTest`）；server 全量单测 294 个、
  API 模块 52 个通过；`InstoreRefundFlowIntegrationTest`（3）在独占空库 `sc_it_t9_refund_flow`
  通过，空库执行 18 个 changeSet；仓库全部 14 个 `*IntegrationTest` 在 14 个独占空库逐一通过，
  并修复两个 017/018 落地后未重跑的存量迁移测试（014 单步应用与 changeSet 计数 18，
  详见 `docs/集成测试.md` 3.3 退款串行切片全量门禁）。Timer 恢复按原 `refundCaseNo` 收敛接线完成
  （commit `c1a4e92`/`3b9eb5f`）。
- 2026-08-23 支付/退款 Wire 契约切片门禁：`InstoreChannelCommandApi` 五方法与
  `InstoreBuyerCashierCallbackApi` 两方法 Wire 契约及 RPC 实现落地（命令侧身份来自 `ChannelContext`、
  回调侧来自新增 `ProjectContext`，建单经 `InstoreChannelCommandFacade.createPaymentOrder` 编排）；
  server 全量单测 308 个、API 模块 59 个全部通过；ArchitectureTest 扩至 11 条规则（新增：
  `interfaceapi.rpc` 下 `Instore*` RPC 实现不得直连 DAL/集成层/场景应用层/账户核心）；契约
  `InstorePaymentCommandApiContractTest`（7）与命令/回调 ApiImpl 单测 10 个（含日志脱敏断言）通过。
- 支付回调/权威查询的 Mock 收敛已验证；真实 MySQL 并发收敛、退款真实并发竞争和真实 SaaS Adapter
  仍未完成，不能据此判定完整生产资金路径已就绪。
- 注意：JDK 8 规则（用户规则）适用于 `cardShop-admin` 等其他项目，**本 `aifuli-mall-pro` 仓库按 root pom 要求 JDK 21**。
