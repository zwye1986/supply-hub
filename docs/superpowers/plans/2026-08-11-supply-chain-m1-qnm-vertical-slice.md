# Supply Chain M1 QNM Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通奇那梦（QNM）直充与卡券履约端到端垂直切片，覆盖标准商品建单受理、出站端口契约、QNM 适配器调用、超时查单、卡密解析与契约测试闭环。

**Architecture:**
1. 领域出站端口隔离（Hexagonal Architecture）：履约领域定义不可变 `OperationTicket` 与标准事实 `OperationOutcome`，不暴露任何底层协议或数据库实现；
2. 适配器无状态与数据所有权（ArchUnit 强制）：`supplieradapter` 包仅消费 Ticket 并返回 Outcome，严禁依赖 `border..dal..` 或直接读写订单库；
3. 签名、安全与脱敏：外部通信使用 MD5 严格按字段字典序签名与验签；通信密钥来自 CredentialSlot；日志对账号脱敏、卡密脱敏、禁止打印密钥明文；
4. 恢复真相：网络超时保持 `RESULT_UNKNOWN`，通过 PersistentTimer 可靠唤醒并触发 `/GameOrder/Query` 恢复。

**Tech Stack:** Java 21, Spring Boot 3, MyBatis Plus, Jackson, OkHttp3, MockWebServer, JUnit 5, AssertJ, ArchUnit.

---

### Task 1: 基础设施与 OpenAPI 契约配置（已完成）
- [x] OpenAPI 分组与组合部署隔离配置 (`SupplyChainOpenApiConfiguration`)。详见 `#task-1`。

### Task 2: 数据库表与迁移脚本（已完成）
- [x] 核心总订单、履约请求、BOrder、Operation、Asset、CallLog、StatusMapping、ErrorCodeMap DDL（`001` - `006` SQL）。详见 `#task-2`。

### Task 3: 履约受理生命周期与预占（已完成）
- [x] `FulfillmentAcceptanceLifecycle`：不可取消受理回执、单单串行尝试、资金一次性冻结、首张 BOrder 与 Operation 创建。详见 `#task-3`。

### Task 9: 持久化定时器与 Outbox 调度接入（已完成）
- [x] `TimerEngineLifecycle`、`FulfillmentOperationDispatchHandler`：Outbox 事件投递与持久化定时任务调度。详见 `#task-9`。

---

### Task 10: 履约领域出站端口与奇那梦（QNM）适配器实现（当前任务）

**Files:**
- Modify: `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md`
- Modify: `docs/集成测试.md`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/OperationExecutorPort.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/OperationOutcomeClass.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/OperationTicket.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/OperationOutcome.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/OperationAssetFact.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/RawObservationFact.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/domain/CallDigestFact.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmEndpointConstants.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmSignUtils.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmMaskingUtils.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmHttpExecutor.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmProtocolParser.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmSupplierAdapter.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmCallbackParser.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/supplieradapter/CompositeOperationExecutor.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmSignUtilsTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmMaskingUtilsTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmProtocolParserTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmCallbackParserTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmSupplierAdapterTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/supplieradapter/qnm/QnmSupplierAdapterContractTest.java`

- [x] **Step 1: 详细设计先行**
  - 在 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.3` 详细固化 `OperationExecutorPort` 与入出参契约规范。
  - 在 `#10` 补充 QNM GameOrder V1 直充 `/GameOrder/Send`、卡密发券 `/GameOrder/SendKm`、查单 `/GameOrder/Query` 的协议字段与 MD5 验签、脱敏规则。
  - 在 `docs/集成测试.md` 追加 QNM 适配器测试场景与挡板契约。

- [x] **Step 2: 定义履约领域出站端口与不可变契约模型**
  - 在 `border.fulfillment.domain` 定义 `OperationExecutorPort` 接口。
  - 定义 `OperationOutcomeClass` 封闭结果枚举（`ACCEPTED`、`SUCCEEDED`、`PARTIALLY_SUCCEEDED`、`REJECTED`、`NOT_FOUND`、`RESULT_UNKNOWN`、`MANUAL_REQUIRED`）。
  - 定义不可变 `OperationTicket` 凭证与 `OperationOutcome` 结果。
  - 定义附属事实模型：`OperationAssetFact`、`RawObservationFact`、`CallDigestFact`。

- [x] **Step 3: 实现 QNM 签名工具与脱敏工具**
  - 实现 `QnmSignUtils`：参数字典排序、过滤 sign 与空值、追加 key 并进行 MD5 计算与校验。
  - 实现 `QnmMaskingUtils`：账号脱敏、卡密脱敏、防日志风暴。

- [x] **Step 4: 实现 QNM HTTP 传输执行器与适配器**
  - 实现 `QnmHttpExecutor`：基于 OkHttp3 发起 HTTP POST 请求，超时控制（连接 5s，读写 10s），记录耗时与 SHA-256 报文摘要。
  - 实现 `QnmSupplierAdapter`：实现 `OperationExecutorPort`，支持直充、卡密发券、主动查单；解析卡密数组与金额；网络异常转 `RESULT_UNKNOWN`。
  - 实现 `QnmCallbackParser`：回调验签与状态解析。
  - 实现 `CompositeOperationExecutor`：按 `adapterType` 路由至匹配的适配器。

- [x] **Step 5: 编写单元测试、模拟挡板与契约测试**
  - `QnmSignUtilsTest`：覆盖签名生成、验签通过与篡改拒绝、大小写容错。
  - `QnmMaskingUtilsTest`：覆盖账号、卡密、卡号脱敏与参数快照不可变保护。
  - `QnmCallbackParserTest`：覆盖异步回调验签、状态解析、卡密提取与脱敏 rawPayload。
  - `QnmSupplierAdapterTest`：基于 `MockWebServer` 模拟直充成功/失败、卡密发券（含多卡密、单串卡密解析及空卡密场景）、主动查单（终态成功/终态失败/处理中/查无此单）、浮点金额换算、网络超时与连接中断。
  - `QnmSupplierAdapterContractTest`：断言 Ticket/Outcome 契约不可变性、端口方法签名与异常隔离。
  - 运行 `SupplyChainModuleArchitectureTest` 验证 ArchUnit 规则通过（无跨层或非法 DAL 依赖）。

---

### Task 11: 任务执行器真实接入与状态机/资金/资产闭环（当前任务）

**Files:**
- Modify: `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md`
- Modify: `docs/集成测试.md`
- Modify: `docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/SupplyChainOrderEventDispatchHandler.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/BuyerEventRpcDispatchHandler.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/FulfillmentOperationExecuteHandler.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/SupplierOrderLifecycle.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/domain/SupplyChainOrderTypes.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/order/domain/CardOrderSummaryPolicy.java`
- Modify: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/tradecore/account/application/FundReservationService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/dal/dataobject/FulfillmentAssetDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/dal/mysql/FulfillmentAssetMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/dal/dataobject/SensitiveAccessAuditDO.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/dal/mysql/SensitiveAccessAuditMapper.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/border/fulfillment/application/BuyerFulfillmentQueryService.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/application/facade/BuyerFulfillmentQueryFacade.java`
- Create: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/interfaceapi/rpc/BuyerFulfillmentQueryApiImpl.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/fulfillment/application/SupplyChainOutboxDispatchHandlerTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/fulfillment/application/FulfillmentOperationExecuteHandlerTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/fulfillment/application/SupplierOrderLifecycleTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/fulfillment/application/BuyerFulfillmentQueryServiceTest.java`
- Test: `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/border/order/domain/CardOrderSummaryPolicyExtensionTest.java`

- [x] **Step 1: 详细设计与计划对齐**
  - 更新详细设计与集成测试文档，固化 Outbox 补全、执行器、生命周期 CAS、资金 commit/release 闭环、卡密信封加密与授权查询规范。
- [x] **Step 2: 补全 Outbox 派发处理器**
  - 实现 `SupplyChainOrderEventDispatchHandler`（target: `supply-chain-order-event`）与 `BuyerEventRpcDispatchHandler`（target: `buyer-event-rpc`）。
- [x] **Step 3: 实现敏感履约资产与审计持久化**
  - 落地 `FulfillmentAssetDO`、`FulfillmentAssetMapper`、`SensitiveAccessAuditDO`、`SensitiveAccessAuditMapper`。
- [x] **Step 4: 实现 `SupplierOrderLifecycle` 与资金闭环**
  - 短事务内通过 CAS 乐观锁推进 BOrder、FulfillmentOperation、FulfillmentRequest 与 SupplyChainOrder 状态。
  - 推进 `CardOrderSummaryPolicy` 业务阶段：`CARD_FULFILLED`（30 成功）、`CARD_FAILED`（40 失败）、`CARD_MANUAL`（90 人工）。
  - 记录 `OrderStatusLogDO` 状态审计。
  - 权威成功实扣资金 `fundReservationService.commit()`；全败释放资金 `fundReservationService.release()`。
  - 持久化交付资产并计算 HMAC 指纹与信封加密。
- [x] **Step 5: 改造 `FulfillmentOperationExecuteHandler`**
  - 解密目标账号，加载凭证与配置，组装 `OperationTicket`，调用 `OperationExecutorPort.execute(ticket)`。
  - 处理成功、失败与 `RESULT_UNKNOWN`。
- [x] **Step 6: 实现 `BuyerFulfillmentQueryApi.getFulfillmentAssets` 授权解密查询**
  - 落地 `BuyerFulfillmentQueryService`、`BuyerFulfillmentQueryFacade` 与 `BuyerFulfillmentQueryApiImpl`。
  - 校验采购商身份、用户归属、解密卡密并记录不可变审计。
- [x] **Step 7: 编写自动化测试并验证门禁**
  - 单元测试与切片测试覆盖各组件，验证 ArchUnit 24 项门禁全绿。

---

### Task 12: 回调入站与主动查询恢复闭环（后续切片）
- [ ] 暴露 QNM 回调 HTTP 端点，验签后驱动 `FulfillmentLifecycle` 状态推进。
- [ ] 针对 `RESULT_UNKNOWN` 由 PersistentTimer 触发 `/GameOrder/Query` 主动查单恢复。

### Task 13: 采购方事件通知与重试增强（后续切片）
- [ ] 触发 `FULFILLMENT_RESULT` 与 `ORDER_SUMMARY_CHANGED` 外部事件可靠投递。
