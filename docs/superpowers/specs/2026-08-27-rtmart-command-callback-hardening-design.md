# 旅通命令回调协议加固设计

- 日期：2026-08-27
- 状态：已实现（错误码枚举统一、退款金额不变量与实际金额响应、nonce 防重放加固；接口③ `transaction_id` 按 Word「可为空，支付成功时必传」；动态码传递按第 3 节延期）
- 适用范围：供应链中台内旅通接口②～⑥、INSTORE 支付/退款核心、pro143 部署脚本
- 上游设计：
  - `docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md`
  - `docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md`

## 1. 目标

修复旅通命令回调第一轮实现中剩余的协议与领域边界问题：当前建单只接受线下扫码购类型；退款金额正数约束必须由领域层兜底；退款查询成功态必须返回实际退款金额；签名、nonce 和错误码必须稳定且失败关闭；文档、测试及部署脚本与实现保持一致。

本次不改变已经确认的核心流程：供应链中台代表分销商，按可信 `projectId` 解析 SaaS 商城会员和收银台路由；支付以回调优先、权威查询兜底；退款使用原 `refundCaseNo` 发起和查询。

## 2. 旅通响应码枚举

新增 `RtmartResponseCode` 枚举，集中定义旅通已经确认的六个响应码，Controller、Response 和异常映射禁止继续使用错误码字符串字面量：

| 枚举项 | 协议码 | 使用场景 |
|---|---|---|
| `SUCCESS` | `E0000` | 请求成功 |
| `INVALID_ARGUMENT` | `E4001` | 公共/业务参数缺失或非法、nonce 重放、可由调用方修正的业务拒绝 |
| `REQUEST_EXPIRED` | `E4002` | timestamp 超出三分钟窗口 |
| `INVALID_APP_KEY` | `E4003` | app_key 不匹配 |
| `INVALID_SIGNATURE` | `E4004` | sign 缺失、为空或验签失败 |
| `SYSTEM_ERROR` | `E5000` | Redis/收银台/内部配置等服务异常及未知异常 |

`RtmartResponse.success/fail` 改为接收枚举。响应消息允许按具体上下文覆盖，但响应码只能来自枚举。内部 `ServiceException` 映射使用 `SupplyChainErrorCodeConstants` 常量比较：明确的参数、幂等、数据不存在和金额拒绝映射为 `INVALID_ARGUMENT`；基础设施、配置和未知异常映射为 `SYSTEM_ERROR`。不再向旅通返回未经确认的 `E4005～E4008、E5001`。

接口①会员回调与接口②～⑥共用同一枚举，消除签名错误码分裂。

## 3. 动态码传递（本轮延期）

当前接入范围确认 `order_type` 固定为 `1`，本轮不实现、不传播 `dynamic_code`，也不扩展
`BuyerCashierPort`。Controller 只接受类型 1，所有其他类型（包括类型 2）一律在调用 Facade 前以
`INVALID_ARGUMENT/E4001` 拒绝；类型 1 请求即使携带 `dynamic_code` 也不向下游传递该字段。

后续如果旅通正式启用 `order_type=2`，须重新确认动态码的长度、有效期和 SaaS 收银台契约，再单独完成
Controller → Facade → Lifecycle 幂等摘要 → `BuyerCashierPort` 的敏感字段传递设计与实现；禁止在本轮凭猜测
预留生产端口字段。

## 4. 退款金额不变量与响应语义

正数退款金额是领域不变量：

- HTTP/RPC Bean Validation 保留，提供快速参数反馈；
- `InstoreChannelLifecycle.acceptRefund` 在查询幂等 Case 前拒绝 `refundAmount <= 0`，保证首次受理与回放入口遵守同一领域不变量；
- `InstoreChannelLifecycle.requireRefundable` 在正数约束成立后计算剩余可退金额；
- 命中已有 `(channelCode, channelRefundNo)` 时，必须比较当前请求与 Case 持久化的 `requestFingerprint`；`tradeId`、`refundType` 或金额任一变化均使用 `INSTORE_IDENTITY_CONFLICT` 拒绝，不得回放或抢占原 Case；
- 内部错误目录新增 `INSTORE_REFUND_AMOUNT_INVALID（1_050_001_047）`，处置指令为 `DO_NOT_RETRY`，对旅通映射为 `INVALID_ARGUMENT/E4001`；超过可退金额仍使用 `INSTORE_REFUND_AMOUNT_EXCEEDED`；
- 零值、负值和超过可退金额分别由单元测试覆盖，真实 MySQL 集成测试至少覆盖零值不落 Case。

退款查询 `refund_fee` 的返回规则固定如下：

- 成功态：返回 `buyerActualRefundAmount`；该值缺失属于内部状态不一致，返回 `E5000`，不得退回申请金额伪装成功；
- 处理中：尚无实际金额，返回申请金额 `channelRefundAmount`；
- 失败态：返回 `null`，并返回 `refuse_reason`。

## 5. 签名与 nonce

协议校验顺序固定为：适配器开关 → sign 是否存在 → 其余公共字段 → app_key → timestamp → 验签 → nonce 原子占用。

- sign 缺失和验签失败统一返回 `INVALID_SIGNATURE/E4004`；
- nonce 使用 Redis `SET NX EX`，只有明确返回 `Boolean.TRUE` 才允许继续；
- Redis 返回 `null` 或抛出异常时失败关闭为 `SYSTEM_ERROR/E5000`，保持旅通响应信封；
- nonce 重放映射为 `INVALID_ARGUMENT/E4001`；
- 日志仅记录 app_key 脱敏摘要和 nonce 哈希前缀，不记录完整 nonce、sign、dynamic_code 或 user_code。

装配边界补充：`RtmartCommandCallbackController` 在入站开关关闭时仍保留路由，以便按上述校验顺序返回
`SYSTEM_ERROR/E5000`，不得通过条件装配把响应改成 HTTP 404。关闭态不依赖 Redis，因此完整应用测试、离线工具等
显式关闭 Redis 自动配置的上下文仍须能够启动；入站开关启用时，`StringRedisTemplate` 是 nonce 防重放的强依赖，
若 Bean 缺失必须在 `@PostConstruct` 阶段 fail-fast，禁止启动一个无法防重放的旅通入口。

## 6. 部署脚本

传入 commit 时，`deploy-pro143.sh` 使用 `mktemp -d` 创建临时目录，并通过 `git worktree add --detach` 构建指定提交；`trap` 负责移除 worktree 和临时镜像包。脚本不得执行 `git checkout` 修改调用者当前分支。

未传 commit 时仍允许从当前工作区构建，并明确提示是否包含未提交内容。部署构建继续使用 `-DskipTests`，但脚本输出必须说明它不替代供应链发版测试门禁；自动化测试由提交前门禁执行。

## 7. 测试策略

严格使用失败测试驱动实现：

1. `RtmartCommandCallbackControllerTest`
   - 入站开关关闭且上下文无 `StringRedisTemplate` 时仍可构造，并返回 `E5000`；
   - 入站开关启用但无 `StringRedisTemplate` 时初始化 fail-fast；
   - 类型 1 正常进入 Facade，类型 2 即使携带动态码也返回 `E4001` 且不进入 Facade；
   - sign 缺失返回 `E4004`；
   - nonce 返回 `null`、Redis 异常返回 `E5000`，重放返回 `E4001`；
   - 退款成功返回实际金额，处理中返回申请金额，失败金额为 JSON null 但键保留；接口⑤⑥ 成功 `data` 始终含 `refund_time`（Integer 秒）与 `refuse_reason`（无则空字符串）；
   - 接口③待支付/取消 `transaction_id` 为空字符串；`pay_fee` 始终为 Integer 支付金额（分）；`pay_time` 未支付/取消填 `0`；支付成功缺收银台流水号返回 `E5000`；
   - 所有响应码来自 `RtmartResponseCode`。
2. `InstoreChannelCommandFacadeTest`
   - 类型 1 建单链路保持现有收银台端口契约；
   - 日志和持久化对象不包含动态码。
3. `InstoreChannelLifecycleTest` 与 `InstoreRefundFlowIntegrationTest`
   - 零值、负值退款被领域层拒绝且不落退款 Case；
   - 相同退款单号的同参数请求允许幂等回放，变更支付流水、退款类型或金额时拒绝且不得派发原 Case。
4. 回归门禁
   - 旅通定向测试；
   - 供应链模块完整单元测试；
   - `supply-chain-integration` 下真实 MySQL/Liquibase 集成测试；
   - `git diff --check`。

## 8. 文档同步

同一实现变更内更新：

- `docs/integration/rtmart/instore-channel-command-api.html`：移除“暂不返回”，统一三分钟窗口及 `E4004` 签名语义，只列六个确认响应码；接口③ `transaction_id` 与 Word 一致；接口⑤⑥ 成功 `data` 全字段输出；
- `docs/integration/rtmart/大润发到店购-旅通调用商户接口说明.md`：接口③ 成功 `data` 全字段输出；`transaction_id` 可为空、支付成功必传；接口⑤⑥ `refund_time`/`refuse_reason` 键始终输出；
- `docs/集成测试.md`：更新接口①～⑥错误码、固定类型 1、nonce 故障、退款幂等冲突和退款实际金额验证步骤；补接口③ 待支付/成功字段核对；补接口⑤申请退款成功 `data` 含 `refund_time` 与空 `refuse_reason`；
- `docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md`：补充供应链中台直接承载旅通入站适配器的当前决策，并同步新增响应字段语义。

### 8.1 查询支付成功 data 字段始终输出

旅通 Word 对接口③ `transaction_id` 的原文是：**支付流水号（可为空，支付成功时必传）**。POS 按字段名解析，成功 `data` 必须始终输出文档列出的全部键，禁止因 null 省略。

| 字段 | 待支付 / 取消（`pay_status` 0 或 2） | 支付成功（`pay_status=1`） |
|---|---|---|
| `out_trade_no` | 中台支付单号，必填 | 同左 |
| `pay_status` | `0` 或 `2` | `1` |
| `pay_fee` | **必填** Integer，单位分。取中台流水 `pay_amount`（建单金额），未支付也要填，不得 `null` 或省略 | 同左；已支付时与买家实付相等 |
| `pay_time` | **必填** Integer，Unix 秒。未支付/取消填 `0`，不得 `null` 或省略 | 实际支付时间 Unix 秒 |
| `transaction_id` | 空字符串 `""`（可为空；字符串不用 JSON null，避免部分 POS 按 string 取值） | **必传非空**。取值仅收银台 `externalTransactionId`，不得用旅通 `trade_id` 或 `out_trade_no` 冒充 |

`pay_status=1` 时若 `externalTransactionId` 缺失或空白，属于内部状态不一致：返回 `SYSTEM_ERROR/E5000`，不得假装支付成功。实现落在 `RtmartCommandCallbackController#queryPayment` 与 `RtmartQueryPaymentData`（`@JsonInclude(ALWAYS)`）。

### 8.2 申请退款 / 查询退款成功 data 字段始终输出

旅通 Word 对接口⑤申请退款成功 `data` 的要求是：**字段必须齐全**；`refuse_reason` 没有时填空字符串；`refund_time` 类型为 Integer、单位秒，不传则取接口调用当前时间。POS 按字段名解析，禁止因 null 省略键。接口⑥查询退款与接口⑤共用同一组 `data` 键。

接口⑤受理成功 `data`（实现：`RtmartAcceptRefundData` + `RtmartCommandCallbackController#acceptRefund`，`@JsonInclude(ALWAYS)`）：

| 字段 | 类型 | 规则 |
|---|---|---|
| `out_trade_no` | string | 中台支付单号 |
| `out_refund_no` | string | 中台 `refundCaseNo` |
| `refund_status` | Integer | 内部退款状态映射为旅通 `0` 待退款、`1` 退款成功、`2` 退款失败。首次受理为排队中/退款中，返回 `0` |
| `refund_fee` | long | 单位分。取本次请求 `refund_fee`（受理成功表示收下 Case，不等于资金已退回） |
| `refund_time` | Integer | Unix 秒。受理时尚无完成时间，填接口调用当前时间，不得 `null` 或省略 |
| `refuse_reason` | string | 无拒绝原因时填空字符串 `""`，不得 JSON `null` 或省略 |

接口⑥查询退款成功 `data`（实现：`RtmartQueryRefundData`，`@JsonInclude(ALWAYS)`）：

| 字段 | 规则 |
|---|---|
| `out_trade_no` / `out_refund_no` / `refund_status` | 与接口⑤相同键；`refund_status` 仍按内部状态映射 |
| `refund_fee` | 仍按第 4 节：成功/完成返回买家实退金额；处理中返回申请金额；失败/人工处理值为 JSON `null`，**键必须保留** |
| `refund_time` | Integer Unix 秒。Case 有完成时间则用完成时间；否则取接口调用当前时间。不得 `null` 或省略 |
| `refuse_reason` | 失败原因；没有时填空字符串 `""` |

## 9. 完成标准

- 代码中不再散落旅通错误码字符串和内部错误码整数；
- 当前固定 `order_type=1` 的链路不传播或记录 `dynamic_code`；
- 所有入口均无法创建非正数退款；
- 退款成功响应使用实际退款金额；
- 签名和 nonce 的异常路径始终返回稳定旅通响应；
- 关闭旅通入站且排除 Redis 自动配置的完整应用能够启动，启用旅通入站但缺少 Redis Bean 时启动失败；
- 指定 commit 部署不改变当前 Git 分支；
- 详细设计、接口文档、集成测试文档和自动化测试全部通过并对齐。
