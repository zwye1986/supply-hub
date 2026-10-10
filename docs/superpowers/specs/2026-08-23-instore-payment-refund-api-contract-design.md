# INSTORE 支付/退款 API 契约层设计

- 日期：2026-08-23
- 状态：已确认（方案 A：薄 ApiImpl + Facade Wire 编排）
- 上游设计：
  - `docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#5-入站契约中台-api`
  - `docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#54-projectcallcontext`
  - `docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层`

## 1. 背景与目标

支付串行、退款串行两个纵向切片已完成，`InstoreChannelCommandFacade`（建单/查支付/取消/退款受理/查退款）与
`InstoreBuyerCashierCallbackFacade`（支付确认/退款回调）均已实现并通过 MySQL 集成门禁，但两者均无对外
Wire 契约。本切片补齐 API 层，使渠道服务（未来的 `aifuli-channel-rtmart-server`）与 SaaS 收银台适配器
可通过稳定 RPC 契约接入，不再依赖进程内调用。

本切片只做契约暴露与身份解析编排，不新增业务语义：所有幂等、串行、收敛、金额校验行为保持既有实现不变。

## 2. API 层

`aifuli-module-supply-chain-api/.../api/instore/` 新增两个接口，模式复用会员入口切片已验证样板：
`@FeignClient(name = ApiConstants.NAME, primary = false)` + `CommonResult<T>` + `@Valid @RequestBody`。

```text
InstoreChannelCommandApi        ← 旅通渠道服务调用，PREFIX = /instore/channel-command
├── createPaymentOrder     旅通接口② 建单
├── queryPaymentStatus     旅通接口③（本地非终态触发权威查询收敛）
├── cancelPaymentFlow      旅通接口④
├── acceptRefund           旅通接口⑤
└── queryRefundStatus      旅通接口⑥

InstoreBuyerCashierCallbackApi   ← SaaS 收银台调用，PREFIX = /buyer/instore/payment
├── confirmPayment         支付成功回调（按 outTradeNo 加载固化归属）
└── reportRefundResult     退款结果回调（按 refundCaseNo 加载固化归属）
```

### 2.1 命令侧 DTO 字段边界

信任域为渠道 Principal。`channelCode/projectId` 来自 `ChannelContext`，`buyerId/cashierProfileVersionId/
sourceSystem/externalTenantId` 由 Facade 编排解析，**一律禁止出现在 Wire DTO**。

| DTO | 字段（* 必填） | 与 DDL/Command 对齐 |
|---|---|---|
| `CreatePaymentOrderReqDTO` | `channelOrderId*` `tradeId*` `userCode*` `payAmount*`(分) `orderType*`(1线下扫码购/2动态码) `totalAmount*`(分) `companyCode*` `externalMerchantId*` `shopCode` `shopName` `subject` `traceId` | `sc_instore_channel_order/channel_order_id`「旅通订单号order_id，与channel_code构成永久幂等键」等 013 注释逐字段一致 |
| `QueryPaymentStatusReqDTO` | `tradeId*` `traceId` | `sc_instore_payment_flow/trade_id`「旅通支付流水号trade_id，与channel_code构成支付永久幂等键」 |
| `CancelPaymentFlowReqDTO` | `tradeId*` `traceId` | 同上 |
| `AcceptRefundReqDTO` | `channelRefundNo*` `tradeId*` `refundType*` `channelRefundAmount*`(分) `traceId` | 与 `AcceptRefundCommand` 一致；`(channelCode,channelRefundNo)` 全局幂等 |
| `QueryRefundStatusReqDTO` | `channelRefundNo*` `traceId` | 与 `QueryRefundCommand` 一致 |

`traceId` 可选，ApiImpl 缺省生成 UUID，保证收敛/操作日志必有链路标识。

### 2.2 命令侧响应 DTO

字段与 Facade 返回 record 一一对应，`short` 状态统一映射为 `Integer`（Wire 层不暴露数据库短整型）：

- `PaymentTicketRespDTO`：`orderNo` `outTradeNo` `cashierUrl`（处理中可空，调用方必须按原 outTradeNo 查询）`payStatus`
- `PaymentStatusRespDTO`：`paymentFlowNo` `outTradeNo` `tradeId` `payStatus` `payAmount` `buyerPaidAmount` `cashierReference` `externalTransactionId`
- `CancelPaymentFlowRespDTO`：`outTradeNo` `refundCaseNo` `convertedToRefund` `claimedForDispatch`
- `RefundAcceptanceRespDTO`：`refundCaseNo` `refundStatus`(10排队/20退款中) `claimedForDispatch`
- `RefundStatusRespDTO`：`refundCaseNo` `channelRefundNo` `refundType` `status` `channelRefundAmount` `buyerActualRefundAmount`

### 2.3 回调侧 DTO 字段边界

信任域为 SaaS 收银台 Principal，`projectId` 来自 `ProjectContext`（见 §4）。字段与现有 Facade Command
严格一一对应，**不新增 Facade 不支持的语义**（如回调携带 FAILED 状态——FAILED 目前仅由权威查询路径产生，
避免半成品字段）：

- `ConfirmPaymentReqDTO`：`outTradeNo*` `actualAmountCny*`(分) `cashierOrderNo` `cashierTransactionNo` `traceId`
- `ReportRefundResultReqDTO`：`refundCaseNo*` `actualAmountCny*`(分) `cashierRefundReference` `traceId`
- 两者均返回 `CommonResult<Void>`。

## 3. Interface Adapter 层

`interfaceapi/rpc` 新增 `InstoreChannelCommandApiImpl`、`InstoreBuyerCashierCallbackApiImpl`，
`@RestController @Primary @Validated`，只做三件事：取可信上下文、入口/出口日志、DTO↔Command 映射后
委派 Facade。异常由全局异常处理转 `CommonResult`，本层不捕获。

日志规范：入口/出口记录业务键（单号、金额、状态、traceId），不打印 `userCode` 全值与 `cashierUrl`
（沿用会员入口脱敏惯例，见开发规范 §6）。

## 4. 可信上下文扩展：ProjectContext

`ProjectCallContextAccessor` 新增：

```java
ProjectContext requireProjectContext();

record ProjectContext(Long projectId) { /* 紧凑构造器校验非空 */ }
```

失败关闭默认配置（`ProjectCallContextConfiguration`）同步拒绝：未注入上下文抛 `BUYER_AUTH_INVALID`，
不从 DTO、请求头或回调参数猜测身份。依据 `2026-08-21-supply-chain-buyer-project-design.md#54-projectcallcontext`：
回调按 `outTradeNo/refundCaseNo` 加载订单固化的归属后核验已认证 SaaS 租户主体，回调参数不改变归属。

`BuyerContext`/`ChannelContext` 语义不变。

## 5. Facade Wire 编排（方案 A）

`InstoreChannelCommandFacade` 新增 Wire 层建单方法 `createPaymentOrder(WireCreatePaymentCommand)`。
`WireCreatePaymentCommand` 携带 `projectId + channelCode`（均来自 ApiImpl 的可信 `ChannelContext`）
以及 `CreatePaymentOrderReqDTO` 全部字段：

1. `InstoreChannelUserBindingResolver` 新增只读方法 `requireActiveBinding(projectId, userCode, channelCode)`
   ——绑定缺失或停用抛 `INSTORE_CHANNEL_USER_BINDING_MISSING`，返回 `BindingSnapshot` 获得 `buyerId`；
2. `BuyerIntegrationRouteResolver.resolveCurrent(projectId, CASHIER)` 解析当前已发布档案版本，
   获得 `cashierProfileVersionId`；
3. 组装既有 `PreparePaymentCommand`（`buyerUserId = userCode`，与 Lifecycle 既有归属二次校验一致）
   委托既有 `createPayment`，幂等/抢占/外呼/收敛行为完全复用。

其余 4 个命令 + 2 个回调：ApiImpl 直接做 DTO→Command 映射，Facade 零改动。

### 5.1 备选方案记录

- **方案 B（独立应用服务做 Wire→Command 转换）**：层次更纯，但与会员入口切片模式分裂，否决。
- **方案 C（ApiImpl 直接解析绑定和路由）**：ApiImpl 访问 Mapper/Resolver，破坏既有薄委托架构约束，否决。

## 6. 错误处理

无新增错误码。复用既有 21 个 INSTORE 稳定错误码及 `RETRY_DIRECTIVES` 处置指令
（`INSTORE_IDEMPOTENCY_CONFLICT`/`INSTORE_REFUND_AMOUNT_EXCEEDED`/`INSTORE_REFUND_AMOUNT_MISMATCH`/
`INSTORE_TENANT_SCOPE_CONFLICT`/`INSTORE_CHANNEL_USER_BINDING_MISSING`/`INSTORE_CONCURRENT_UPDATE` 等），
渠道服务负责映射为旅通协议响应。

旅通入站适配器（中台直接承载接口②～⑥）查询支付成功 `data` 的 `transaction_id` 对齐 Word：**可为空，支付成功时必传**。待支付/取消输出空字符串；`pay_status=1` 时必须回收银台 `externalTransactionId`，缺失失败关闭为 `E5000`。详见 `docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出`。

接口⑤申请退款、接口⑥查询退款成功 `data` 必须始终输出 `out_trade_no`/`out_refund_no`/`refund_status`/`refund_fee`/`refund_time`/`refuse_reason`。`refuse_reason` 没有时填空字符串；`refund_time` 为 Integer Unix 秒，无完成时间则取接口调用当前时间。详见 `docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#82-申请退款--查询退款成功-data-字段始终输出`。

## 7. 测试门禁

- **`InstorePaymentCommandApiContractTest`**（API 模块，反射 + Bean Validation，模式同
  `InstoreMemberEntryApiContractTest`）：
  - 全部 12 个 DTO（命令侧 5 请求 + 5 响应、回调侧 2 请求）字段类型与必填约束；
  - **禁止出现** `projectId/buyerId/sourceSystem/externalTenantId/cashierProfileVersionId` 字段；
  - 状态字段为 `Integer`；金额字段为 `Long`。
- **两个 ApiImpl 单测**（server，Mock accessor + facade，模式同 `InstoreBuyerEntryApiImplTest`）：
  - 上下文是唯一身份来源（未注入上下文抛 `BUYER_AUTH_INVALID`）；
  - DTO→Command 映射逐字段断言（含 traceId 缺省生成）；
  - 入口/出口日志不泄漏 `userCode` 全值、`cashierUrl`。
- **`InstoreChannelCommandFacadeTest` 扩展**：Wire 建单编排（绑定缺失拒绝、路由解析、buyerUserId=userCode）。
- **ArchitectureTest 新增 1 条规则**：Instore 支付/回调两个 ApiImpl 不得注入 DAL 或 Resolver
  （仅 `ProjectCallContextAccessor` + Facade）。
- 既有全量单测回归 + `SupplyChainModuleArchitectureTest`。
- 文档：`docs/integration/rtmart/供应链中台INSTORE到店购渠道接入实现总结.md` §3.2 更新为已实现；
  `docs/集成测试.md` 补本切片门禁记录。

## 8. 完成标准

- 两个 RPC 契约和本地 ApiImpl 可通过测试上下文运行；
- 契约测试、ApiImpl 单测、架构测试、全量单测回归全部通过；
- 文档与实现同一变更内对齐；工作区无裸 TODO/TBD。

## 9. 明确不做（后续切片）

- 真实 SaaS 收银台 RPC/HTTP Adapter（替换 `BuyerCashierStubAdapter`）；
- 项目 appId/appKey 统一认证与可信上下文真实注入；
- `aifuli-channel-rtmart-server` 渠道服务及旅通协议六接口；
- 回调携带 FAILED 状态的 Wire 字段（待权威查询路径语义扩展时一并设计）。
