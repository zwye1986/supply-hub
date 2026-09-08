# Supply Chain Design Grill Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Grill 评审确认的 M1/M2 边界、幂等、资金、异常处置和 O2O 身份/唯一键规则同步到全部供应链设计文档。

**Architecture:** 以核心设计记录不可变业务原则，以一期详细设计描述聚合和流程，以 DDL 固化约束；调用时序和 SaaS 规范只表达各自跨系统责任。所有文档统一使用同一业务键、状态和风险编号，避免同一规则在不同层次出现冲突。

**Tech Stack:** Markdown、Mermaid、MySQL 8 DDL、Liquibase 设计、Spring Cloud RPC 契约。

---

### Task 1: 修订核心业务原则

**Files:**
- Modify: `docs/superpowers/specs/2026-08-04-supply-chain-core-design.md`

- [x] **Step 1: 固化 M1/M2 交付门禁**

写明 M1 仅要求吾盛到奇那梦闭环，标准商品 `quantity=1`，路由为确定性优先级；M2 以 SaaS 权威查支付/查退款为门禁。

- [x] **Step 2: 修正受理、取消和资金规则**

增加 `FulfillmentAcceptanceReceipt`，规定受理后不接受采购商主动取消；明确失败释放、未知保持冻结、奇那梦成功无资产仍扣款并进入 `MANUAL`。

- [x] **Step 3: 固化 O2O 身份与业务键**

增加渠道用户绑定，支付唯一键使用 `(channelCode, transactionId)`，退款唯一键使用 `(channelCode, channelRefundNo)`；切流同时维护 payment/refund owner。

### Task 2: 修订详细设计

**Files:**
- Modify: `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md`

- [x] **Step 1: 更新应用编排和幂等状态机**

描述受理回执四状态、可重试拒绝的受限新尝试、受理后的不可取消语义和一次性期初资金初始化。

- [x] **Step 2: 增加最小履约异常处置模型**

描述单人处置、证据审计、`CONFIRM_SUCCESS/CONFIRM_FAILURE/KEEP_PENDING/RECOVER_ASSET` 受限动作及卡密安全补录。

- [x] **Step 3: 记录奇那梦已知风险**

登记 `QNM-ASSET-RECOVERY-001`，明确查单成功无卡密时资金提交、订单人工态和补录后收敛规则。

- [x] **Step 4: 更新 O2O 用户映射、唯一键和切流**

补充 `O2OChannelUserBinding`、transaction/refund owner，删除以 Buyer 扩大渠道业务键命名空间的规则。

### Task 3: 更新一期数据库 DDL

**Files:**
- Modify: `docs/superpowers/specs/2026-08-07-supply-chain-phase1-database-ddl.md`

- [x] **Step 1: 新增标准履约控制表**

新增带中文表/字段说明的 `sc_fulfillment_acceptance_receipt` 和 `sc_fulfillment_exception_case`，包含唯一键、状态约束、请求摘要、CAS 版本和审计字段。

- [x] **Step 2: 新增 O2O 渠道用户绑定**

新增 `sc_o2o_channel_user_binding`，确保采购商用户映射和渠道用户标识分别唯一。

- [x] **Step 3: 修正 O2O 唯一约束**

将支付约束改为 `(channel_code, transaction_id)`；退款 Case 增加 `channel_code` 并改为 `(channel_code, channel_refund_no)`。

### Task 4: 同步跨系统契约

**Files:**
- Modify: `docs/superpowers/specs/2026-08-10-supply-chain-call-sequences.md`
- Modify: `docs/superpowers/specs/2026-08-11-saas-mall-supply-chain-integration-spec.md`
- Modify: `docs/供应链中台架构基本原则与服务调用规范/供应链中台架构基本原则与服务调用规范.md`

- [x] **Step 1: 修订标准履约时序**

在时序中加入受理回执、不可取消、奇那梦成功无资产的扣款/人工恢复分支。

- [x] **Step 2: 修订 O2O 时序与 SaaS 责任**

加入渠道用户绑定解析、全局渠道业务键、权威查单门禁和商城不得将中台受理状态视为可取消状态。

- [x] **Step 3: 同步架构原则**

只补充跨文档必须一致的不可变原则，不把详细表结构复制到原则文档。

### Task 5: 一致性验证

**Files:**
- Verify: all files modified above

- [x] **Step 1: 扫描已废弃规则**

Run: `rg -n "数量可以大于一|cancelFulfillment|buyer_id, channel_code, transaction_id|o2o_detail_id, channel_refund_no|不另建通用幂等结果表" docs/superpowers/specs docs/供应链中台架构基本原则与服务调用规范`

Expected: 仅保留用于解释已废弃方案的明确否定文本，不存在有效规则冲突。

- [x] **Step 2: 扫描新增模型覆盖**

Run: `rg -n "FulfillmentAcceptanceReceipt|FulfillmentExceptionCase|QNM-ASSET-RECOVERY-001|O2OChannelUserBinding|refund_owner" docs/superpowers/specs docs/供应链中台架构基本原则与服务调用规范`

Expected: 核心设计、详细设计、DDL 和相关跨系统文档均覆盖其职责范围内的新增规则。

- [x] **Step 3: 检查 DDL 和差异**

Run: `git diff --check && git diff --stat && git diff -- docs/superpowers/specs docs/供应链中台架构基本原则与服务调用规范`

Expected: 无空白错误；表名、字段名、状态和唯一键在各文档中一致。
