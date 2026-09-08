# 商城收银台对接 API（供应链中台 ↔ SaaS 商城）

> 面向商城侧开发。描述供应链中台与 SaaS 商城在「到店购（旅通）」链路中的接口契约：商城需提供的 API、商城需回调中台的 API、H5 收银台页契约、鉴权与状态同步约定。

## 1. 背景与协作关系

旅通 POS 调中台创建订单，中台生成收银台地址（cashier_url）返回旅通；旅通拉起商城 H5 收银台，用户在商城完成支付；商城回调中台确认支付，中台收敛并推进订单。中台与商城各自维护独立订单，以 outTradeNo 为唯一关联键。

整体链路：旅通建单 → 中台签令牌并给收银台地址 → 旅通拉起商城收银台 → 用户支付 → 商城回调中台 → 旅通查单确认已支付。

**关联键**：outTradeNo（中台支付流水号）= cashier_url 的 orderNo 参数 = 商城支付单号。1:1 桥接两套订单，回调与查单都靠它定位。

## 2. 接口总览

| # | 接口 | 方向 | 提供方 | 用途 |
|---|---|---|---|---|
| A1 | 查询支付状态 | 中台 → 商城 | 商城提供 | 中台本地非终态时按 outTradeNo 权威查商城 |
| A2 | 发起退款 | 中台 → 商城 | 商城提供 | 中台受理旅通退款后外呼商城退款 |
| A3 | 查询退款状态 | 中台 → 商城 | 商城提供 | 中台本地非终态时按 refundCaseNo 权威查商城 |
| B1 | 支付成功回调 | 商城 → 中台 | 中台提供 | 商城支付成功后通知中台收敛 |
| B2 | 退款结果回调 | 商城 → 中台 | 中台提供 | 商城退款成功后通知中台收敛 |
| C  | H5 收银台页 | 旅通/用户 → 商城 | 商城提供 | 承载支付页，读 cashier_url 参数建支付单 |

金额单位约定：中台 ↔ 商城所有 API 金额字段单位均为「分」（long，CNY）；cashier_url 的 orderPrice 为「元」（中台已由分换算）。

---

## 3. 商城需提供的 API（中台 → 商城）

中台通过 BuyerCashierPort 外呼商城。商城需实现以下 3 个 HTTP 接口。路径由商城定义后告知中台适配器配置；请求/响应字段须严格对齐下表。

### A1. 查询支付状态

- 用途：中台查单（旅通接口③）本地非终态、或 Timer 兜底恢复时，按 outTradeNo 权威查询商城支付结果。
- 方法/路径：POST {商城定义}，建议 /rpc-api/mall/instore/payment/query
- 幂等/重试：可退避重试；中台不生成新号，始终用原 outTradeNo 查询。

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| outTradeNo | string | 是 | 中台支付流水号（= 商城支付单号），≤128 |

响应体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| status | enum | 是 | NOT_FOUND / CREATING / PENDING / SUCCEEDED / FAILED / CLOSED / UNKNOWN |
| confirmedAmountCny | long | SUCCEEDED 必填 | 实付金额（分），SUCCEEDED 时必须 >0 |
| cashierOrderNo | string | SUCCEEDED 必填 | 商城收银台支付单号 |
| cashierTransactionNo | string | SUCCEEDED 必填 | 商城收银台交易号 |
| errorCode | string | 否 | 失败/未知时错误码摘要 |
| observedAt | string(date-time) | 是 | 商城观测时间（UTC） |

状态约束：
- SUCCEEDED：confirmedAmountCny > 0 且 cashierOrderNo、cashierTransactionNo 非空。
- UNKNOWN：商城自身状态不确定时返回，中台保留原号不收敛，等待回调或下次查询。
- NOT_FOUND：商城无此单，中台据此判定异常。

### A2. 发起退款

- 用途：中台受理旅通退款（接口⑤）或取消已支付单（接口④转退款）后，抢占成功的 Case 外呼商城退款。
- 方法/路径：POST {商城定义}，建议 /rpc-api/mall/instore/refund
- 幂等/重试：稳定键 invocationId + refundCaseNo，禁止自动重退；UNKNOWN 时中台按原 refundCaseNo 查询（A3），不重退。

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| invocationId | string | 是 | 中台操作号（幂等键），≤128 |
| refundCaseNo | string | 是 | 中台退款 Case 号，≤32 |
| outTradeNo | string | 是 | 原支付流水号（= 商城支付单号），≤128 |
| amountCny | long | 是 | 退款金额（分），必须 >0 |

响应体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| status | enum | 是 | PROCESSING / SUCCEEDED / REJECTED / UNKNOWN |
| actualRefundAmountCny | long | SUCCEEDED 必填 | 实际退款金额（分），SUCCEEDED 时必须 >0 |
| cashierRefundReference | string | 否 | 商城退款单引用 |
| errorCode | string | 否 | 失败/拒绝/未知时错误码摘要 |
| observedAt | string(date-time) | 是 | 商城观测时间（UTC） |

状态约束：SUCCEEDED 时 actualRefundAmountCny > 0。UNKNOWN 由中台走 A3 兜底。

### A3. 查询退款状态

- 用途：中台查退款（旅通接口⑥）本地非终态、或退款外呼 UNKNOWN 兜底时，按 refundCaseNo 权威查询。
- 方法/路径：POST {商城定义}，建议 /rpc-api/mall/instore/refund/query
- 幂等/重试：可退避重试；始终用原 refundCaseNo。

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| refundCaseNo | string | 是 | 中台退款 Case 号，≤32 |

响应体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| status | enum | 是 | NOT_FOUND / PROCESSING / SUCCEEDED / FAILED / UNKNOWN |
| actualRefundAmountCny | long | SUCCEEDED 必填 | 实际退款金额（分），SUCCEEDED 时必须 >0 |
| cashierRefundReference | string | 否 | 商城退款单引用 |
| errorCode | string | 否 | 失败/未知时错误码摘要 |
| observedAt | string(date-time) | 是 | 商城观测时间（UTC） |

---

## 4. 商城需回调中台的 API（商城 → 中台）

中台已提供以下 2 个 RPC 接口。商城支付/退款成功后必须调用，由中台收敛订单状态。鉴权采用「项目应用统一认证」（见第 6 节）。

### B1. 支付成功回调

- 方法/路径：POST /rpc-api/supply-chain/buyer/instore/payment/confirmPayment
- operationCode：INSTORE_CONFIRM_PAYMENT_CALLBACK（契约版本 1.0.0）
- 幂等：中台按 outTradeNo CAS 幂等，重复回调安全。

请求体（ConfirmPaymentReqDTO）：

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| outTradeNo | string | 是 | 非空，≤128 | 中台支付流水号（= 商城支付单号） |
| actualAmountCny | long | 是 | 非空，>0 | 实付金额（分），须等于中台流水应付金额 |
| cashierOrderNo | string | 否 | ≤128 | 商城收银台支付单号 |
| cashierTransactionNo | string | 否 | ≤128 | 商城收银台交易号 |
| traceId | string | 否 | ≤128 | 链路追踪标识，缺省服务端生成 |

说明：actualAmountCny 必须与中台建单时的应付金额一致，否则中台抛身份冲突不收敛。回调成功后中台建供应商订单、推进支付流水为 PAID、订单头为 COMPLETED。

### B2. 退款结果回调

- 方法/路径：POST /rpc-api/supply-chain/buyer/instore/payment/reportRefundResult
- operationCode：INSTORE_REPORT_REFUND_RESULT_CALLBACK（契约版本 1.0.0）
- 幂等：中台按 refundCaseNo CAS 幂等。

请求体（ReportRefundResultReqDTO）：

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| refundCaseNo | string | 是 | 非空，≤32 | 中台退款 Case 号 |
| actualAmountCny | long | 是 | 非空，>0 | 实际退款金额（分） |
| cashierRefundReference | string | 否 | ≤128 | 商城退款单引用 |
| traceId | string | 否 | ≤128 | 链路追踪标识，缺省服务端生成 |

说明：实际退款金额缺失或与申请金额不一致时，中台转人工且不返还采购商账户。回调成功后中台推进退款 Case 为终态。

---

## 5. H5 收银台页契约（商城提供）

中台生成的 cashier_url 形如：

```
{h5BaseUrl}{h5Path}?orderNo={outTradeNo}&orderPrice={元}&token={accessToken}&source=rtmart
```

参数说明：

| 参数 | 说明 |
|---|---|
| orderNo | 中台支付流水号 outTradeNo，商城用作支付单号 |
| orderPrice | 订单金额，单位「元」，字符串两位小数 |
| token | 会员 SSO 访问令牌（中台已向 member-server 签发） |
| source | 固定 rtmart，标识到店购来源 |

商城侧职责：

1. 用 token 完成会员登录（SSO）。
2. 用 orderNo（= outTradeNo）创建或复用商城支付单，支付单号即用 outTradeNo，确保与中台 1:1。
3. 引导用户完成支付；支付成功将商城支付单置为 PAID。
4. 支付成功后调用 B1（confirmPayment）通知中台收敛。
5. 若发起退款并成功，调用 B2（reportRefundResult）通知中台。

> 商城支付单号必须等于 outTradeNo，否则中台回调/查单无法定位流水。

---

## 6. 鉴权约定

### 6.1 商城回调中台（B1/B2）：项目应用统一认证

商城调用 B1/B2 须携带以下 10 个 HTTP 头，由中台验签后解析可信 projectId：

| 头 | 说明 |
|---|---|
| appId | 中台分配给商城收银台项目的应用 ID |
| keyVersion | 密钥版本（int） |
| contractVersion | 契约版本，当前 1.0.0 |
| operationCode | INSTORE_CONFIRM_PAYMENT_CALLBACK 或 INSTORE_REPORT_REFUND_RESULT_CALLBACK |
| timestamp | 毫秒时间戳 |
| nonce | 随机串（建议 16 字节 Base64URL） |
| requestSha256 | 请求 body 原始字节的 SHA-256 小写十六进制摘要 |
| signature | 签名值（见下） |
| buyerUserId | 回调类操作固定为空字符串 |
| channelCode | 回调类操作固定为空字符串 |

签名算法：

- 签名串（9 字段，换行符 \n 分隔，顺序固定）：
  `appId \n keyVersion \n contractVersion \n operationCode \n timestamp \n nonce \n requestSha256 \n buyerUserId \n channelCode`
  回调类操作 buyerUserId、channelCode 取空字符串。
- 签名：以 appKey 为密钥，对签名串做 HMAC-SHA256，结果 Base64URL 编码（无填充）。
- requestSha256：对请求 body 原始字节做 SHA-256，输出小写十六进制；空 body 取空字节串摘要。

约束：各字段值不得含换行符；appKey 明文仅存于调用方内存，禁止落日志/缓存/序列化。中台侧按 (HTTP 方法, 路径) 精确匹配受保护操作，未登记路径一律拒绝。

### 6.2 中台外呼商城（A1/A2/A3）：由商城定义

中台外呼商城的鉴权方式由商城决定（如同一套项目应用认证、或商城自有签名/Token）。商城确定后提供：鉴权头名、签名算法、密钥发放方式，中台在 BuyerCashierRpcAdapter 配置对接。

---

## 7. 状态同步与幂等约定

- 关联键：outTradeNo 贯穿 cashier_url、商城支付单、回调与查单，1:1 桥接两套订单。
- 回调优先 + 拉查兜底：商城支付/退款成功应立即回调中台（B1/B2，实时）；中台查单时若本地非终态，按 outTradeNo/refundCaseNo 权威查商城（A1/A3，兜底防回调丢失）。两条路径最终汇入中台同一收敛入口，由状态机 + CAS 保证并发幂等，重复回调/查单不会重复收敛。
- 金额校验：回调 actualAmountCny 必须等于中台流水应付/退款申请金额，否则中台不收敛（支付抛身份冲突，退款转人工）。
- 不重创不重退：中台建单/退款稳定键为 invocationId + outTradeNo/refundCaseNo，外呼 UNKNOWN 时按原号查询，禁止自动重创收银台订单或重复退款。

---

## 8. 联调注意事项

1. 金额单位：中台 ↔ 商城 API 一律「分」；cashier_url 的 orderPrice 是「元」。切勿混用。
2. 商城支付单号必须 = outTradeNo，否则中台无法收敛。
3. 商城需向中台索取：appId、appKey、keyVersion（用于 B1/B2 签名），以及中台外呼商城的 base URL 与鉴权要求。
4. 中台需向商城索取：A1/A2/A3 的最终路径、鉴权方式、密钥。
5. 回调与查单均需幂等：商城侧重复收到中台查单请求应返回当前最新状态，不重复发起支付/退款。
6. 当前中台侧 A1/A2/A3 外呼尚未接入真实商城（实现为 stub），联调前需中台适配器与商城对接完成。
7. 详细设计见 docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md 与 docs/superpowers/specs/2026-08-23-project-app-auth-design.md。


