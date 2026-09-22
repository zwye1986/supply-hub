# 管理端路由规则可视化编辑 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐步执行。步骤使用 checkbox（`- [ ]`）跟踪。
>
> 设计权威：`docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md`。GET 投影不变：`docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md`。

**Goal:** 让 `/supply-chain/routing-rules` 的 POST/PUT/DELETE/`PUT /{ruleCode}/status` 通过「克隆当前生效版本 → 打补丁 → 关闭旧 `valid_to` → 立即发布」真正改标准商品路由候选顺序，且 v1 强制恰好一个 Buyer。

**Architecture:** `RoutingRuleAdminController` 只做 VO 与权限；`RoutingRuleAdminCommandService` 编排补丁与业务错误码；`RoutingPolicyPublicationService` 新增后继发布能力（关旧区间 + `createDraft` + `publish`），禁止 Controller 直接拼 Mapper。查询仍走 `RoutingRuleAdminQueryService`。不改 `SupplierRoutingEngine`、不加 Liquibase。

**Tech Stack:** Java 21、Spring MVC、`supplyChainTransactionManager`、MyBatis Plus Lambda、JUnit 5、AssertJ、Testcontainers MySQL 8.4（profile `supply-chain-integration`）。

---

## 文件结构

```
api:
  error/SupplyChainErrorCodeConstants.java          # 122–131 + 120 文案
  test/.../SupplyChainErrorMappingTest.java         # CsvSource 增量

server routing.application:
  RoutingRuleAdminCommandService.java               # 新建：补丁发布编排
  RoutingRuleSaveSupport.java                       # 新建（可选内联）：VO→RuleDraft、ruleCode 生成、范围拒绝
  RoutingPolicyPublicationService.java              # 扩展：后继发布、引导策略常量复用

server interfaceapi.admin:
  RoutingRuleAdminController.java                   # 写入口 + RoutingRuleSaveReqVO
  vo/RoutingRuleSaveReqVO.java                      # 新建（或 controller 内 static 类）

server test:
  routing/application/RoutingRuleAdminCommandServiceTest.java
  routing/application/RoutingPolicyPublicationSuccessorTest.java  # 或扩展现有 IntegrationTest
  interfaceapi/admin/RoutingRuleAdminControllerTest.java
  routing/application/RoutingRuleAdminWriteIntegrationTest.java   # 新建 MySQL IT

docs:
  集成测试.md                                       # 写路径自动化记录
```

路径前缀：

- API：`aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/`
- Server：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`
- Test：`.../src/test/java/com/aifuli/module/supplychain/`

---

### Task 1: 稳定错误码 122–131

**Files:**

- Modify: `error/SupplyChainErrorCodeConstants.java`
- Modify: `error/SupplyChainErrorMappingTest.java`

- [ ] **Step 1: 写失败测试**

在 `SupplyChainErrorMappingTest` 的 `@CsvSource` 中，在现有 `1050001120,DO_NOT_RETRY` 与 `1050001121,DO_NOT_RETRY` 之间插入 122–131 共 10 行，均为 `DO_NOT_RETRY`。并新增一个 `@Test` 断言 `ROUTING_RULE_WRITE_NOT_OPEN` 文案为「路由规则写入口已开放，本错误码不再使用」。

- [ ] **Step 2: 跑测确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/mac/.codex/worktrees/f887/supply-hub
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=com.aifuli.module.supplychain.error.SupplyChainErrorMappingTest
```

Expected: FAIL（缺常量或 Csv 不匹配）。

- [ ] **Step 3: 实现常量**

在 `SupplyChainErrorCodeConstants` 中：

- 将 `ROUTING_RULE_WRITE_NOT_OPEN` 文案改为设计 §7 规定句。
- 新增 `ROUTING_RULE_CODE_MISMATCH` … `ROUTING_RULE_FULFILLMENT_MISMATCH`（122–131），文案与设计 §7 表一致。
- `RETRY_DIRECTIVES` 为每个新码追加 `DO_NOT_RETRY`。

- [ ] **Step 4: 跑测通过** — 同上命令，Expected: Tests run ≥1, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/main/java/com/aifuli/module/supplychain/error/SupplyChainErrorCodeConstants.java \
  aifuli-module-supply-chain/aifuli-module-supply-chain-api/src/test/java/com/aifuli/module/supplychain/error/SupplyChainErrorMappingTest.java
git commit -m "$(cat <<'EOF'
feat(supply-chain): 增加路由规则页保存业务错误码

支撑补丁发布拒绝场景，并标记原只读拒绝码不再从写入口抛出。
EOF
)"
```

---

### Task 2: 请求体校验与 `RuleDraft` 转换（纯逻辑单测）

**Files:**

- Create: `routing/application/RoutingRuleSaveSupport.java`
- Create: `test/.../routing/application/RoutingRuleSaveSupportTest.java`

职责：`validateAndNormalize(RoutingRuleSaveReqVO, pathRuleCode, operation)` → 内部 `NormalizedRulePatch`（name、buyerCode、skuCode、fulfillmentType、routeMode、orderedSupplierCodes、ruleCode 可选）。拒绝逻辑：

- `distributorCodes` null/empty/.size()!=1 → `ROUTING_RULE_BUYER_REQUIRED`
- `projectCodes` 非空或 `projectMode==SPECIFIED`（product 同理）→ `ROUTING_RULE_SCOPE_NOT_OPEN`
- `supplierMode==ALL` → `ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN`
- `priorities`/`weights` 违反 §4.1 → `ROUTING_RULE_STRATEGY_NOT_OPEN`
- `status==DISABLED` on save body → 拒绝（可用 `ROUTING_RULE_STRATEGY_NOT_OPEN` 或专用 IllegalArgumentException 映射，优先业务码）
- `FIXED` 无 `primarySupplierCode` / `AUTO` 去重后 `<2` → `ROUTING_RULE_CANDIDATE_INVALID`
- PUT：`ruleCode` body 与 path 不一致 → `ROUTING_RULE_CODE_MISMATCH`

候选默认检查模式常量（新候选）：inventory/balance/health 均 `UNSUPPORTED`，maxAge 全 null。

- [ ] **Step 1: 写失败测试**（至少覆盖：单 buyer 通过、0 buyer、2 buyer、projectCodes 非空、ALL 供应商、AUTO 1 供应商、weights 非 40/30/30、path/body ruleCode 不一致）

- [ ] **Step 2: 跑测 FAIL**

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupportTest
```

- [ ] **Step 3: 实现 `RoutingRuleSaveSupport`**（仅抛 `ServiceException` + 设计错误码，不访问 DB）

- [ ] **Step 4: 跑测 PASS**

- [ ] **Step 5: Commit** — `feat(supply-chain): 路由规则保存请求校验与归一化`

---

### Task 3: `RoutingPolicyPublicationService` 后继发布

**Files:**

- Modify: `routing/application/RoutingPolicyPublicationService.java`
- Create: `test/.../routing/application/RoutingPolicyPublicationSuccessorTest.java`（MySQL IT，可复用 `SupplierRoutingApplicationServiceIntegrationTest` 的容器与 `@Import` 模式）

新增公开方法（命名可微调，语义固定）：

```java
public SuccessorPublishResult publishSuccessorStandardProductRules(SuccessorPublishCommand command)
```

`SuccessorPublishCommand` 字段建议：

- `String policyCode` / `String policyName`（引导首版时用 `STANDARD_PRODUCT_PRIMARY` / `标准商品供给路由`）
- `Long operatorId`、`String traceId`
- `List<RuleDraft> rules`（完整规则集，非增量）
- `Long closeVersionId`（nullable：无生效版本时不关；有关闭对象时必填）
- `long closeExpectedVersion`（乐观锁）
- `LocalDateTime effectiveAt`（= `databaseClock.now()`）

事务内顺序：

1. 若 `closeVersionId != null`：锁版本行，校验 `status==PUBLISHED` 且 `version==closeExpectedVersion`；若 `!effectiveAt.isAfter(version.getValidFrom())` → 抛 `IllegalStateException` 由命令层映射 `ROUTING_RULE_VERSION_CONFLICT`；设置 `validTo=effectiveAt` 并 `updateById`。
2. 锁策略（`selectByCodeForUpdate` 或按 id）；无策略则 insert INACTIVE（与现有 `createDraft` 一致）。
3. 调用现有 `createDraft(CreateDraftCommand(..., validFrom=effectiveAt, validTo=null, rules))`。
4. 调用现有 `publish(PublishCommand(draftVersionId, 0, "管理端路由规则页保存", ...))`。
5. 返回 `policyCode`、新 `versionId`、`versionNo`。

**不变量：** 不得修改已发布版本内 rule/candidate 行；只 UPDATE 旧版本 `valid_to`。`requireNoPublishedOverlap` 在旧版本已收 `valid_to=effectiveAt` 后须仍能通过（单测/IT 断言）。

- [ ] **Step 1: 写 IT** — 先 seed 一条已发布生效版本 + 1 规则；调用 `publishSuccessorStandardProductRules` 带 2 条规则；断言旧版本 `valid_to` 非空、新版本 `version_no` 递增、`selectEffective(now)` 仅 1 条且含 2 rules。

- [ ] **Step 2: 跑 IT FAIL**（`-Psupply-chain-integration` 或项目既有 IT 命令，加 `-Dsurefire.excludes=`）

- [ ] **Step 3: 实现后继发布**

- [ ] **Step 4: IT PASS**

- [ ] **Step 5: Commit** — `feat(supply-chain): 标准路由策略后继版本发布`

---

### Task 4: `RoutingRuleAdminCommandService`（补丁 + 引导）

**Files:**

- Create: `routing/application/RoutingRuleAdminCommandService.java`
- Create: `test/.../routing/application/RoutingRuleAdminCommandServiceTest.java`

依赖：`RoutingRuleSaveSupport`、`RoutingPolicyPublicationService`、`RoutingRuleAdminQueryService`、各 Mapper、`SupplyChainDatabaseClock`、`BuyerMapper`、`PlatformSkuMapper`、`SupplierMapper`。

公开方法：

- `RoutingRuleView create(NormalizedRulePatch patch, Long operatorId)`
- `RoutingRuleView update(String ruleCode, NormalizedRulePatch patch, Long operatorId)`
- `void delete(String ruleCode, Long operatorId)`
- `RoutingRuleView changeStatus(String ruleCode, RoutingRuleStatus status, Long operatorId)`

核心私有流程 `publishPatchedRules(List<MutableRuleState> nextRules, String focusRuleCode, Long operatorId)`：

1. `now = clock.now()`。
2. 解析启用策略：`selectActiveByType(STANDARD_PRODUCT)` — 0 条且为 create → 引导 policyCode；>1 条 → 数据损坏 `IllegalStateException`。
3. 加载生效版本：`selectEffective(policyId, now)` — 0 条且非 create → `ROUTING_RULE_NOT_FOUND`（update/delete/status）；0 条且 create → 空规则集 + `closeVersionId=null`。
4. 从 DB 加载当前规则+候选，转为 `MutableRuleState`（含 ruleCode、buyerId、skuId、fulfillmentType、rulePriority、candidates 含 check modes）。
5. 应用补丁（add/replace/remove）；生成新 `ruleCode`（`RR`+yyyyMMdd+序号，见设计 §4.2）。
6. 校验结果集非空（delete 最后一条 → `ROUTING_RULE_LAST_CANNOT_REMOVE`）；三元组唯一（→ `ROUTING_RULE_MATCH_OVERLAP`）。
7. 将每条转为 `RuleDraft`：解析 buyer/sku/supplier code；SKU 档案校验 fulfillment 一致（→ `ROUTING_RULE_FULFILLMENT_MISMATCH`）；Buyer/SKU/Supplier 状态（→ 既有码）；**合并检查模式**：对 replace 行，按 supplierCode 匹配旧候选则拷贝 check 字段，否则 UNSUPPORTED。
8. 调用 `publishSuccessorStandardProductRules`。
9. `queryService.get(focusRuleCode)` 返回。

`changeStatus`：`DISABLED` → 等同 delete；`ENABLED` → 仅 `get` 不发布。

单测使用 Mockito 固定 Mapper/clock/publication，覆盖：create 首条、create 第二条保留第一条、update 换候选顺序、delete 非最后、delete 最后拒绝、buyer 非 1 在 Support 层已测。

- [ ] **Step 1–4: RED-GREEN**（`RoutingRuleAdminCommandServiceTest`）

- [ ] **Step 5: Commit** — `feat(supply-chain): 路由规则页补丁发布命令服务`

---

### Task 5: Controller 接线

**Files:**

- Create: `interfaceapi/admin/vo/RoutingRuleSaveReqVO.java`（字段对齐设计 §4.1，`@Schema` 中文与 DDL 语义一致）
- Modify: `interfaceapi/admin/RoutingRuleAdminController.java`
- Modify: `test/.../interfaceapi/admin/RoutingRuleAdminControllerTest.java`

Controller 变更：

- 构造注入 `RoutingRuleAdminCommandService` + `queryService`。
- `POST`/`PUT`：`@Valid @RequestBody RoutingRuleSaveReqVO` → `saveSupport.validateAndNormalize` → command → `success(view)`。
- `DELETE`：command.delete → `success(null)`。
- `PUT /status`：`ChangeStatusReqVO { status }` → command.changeStatus。
- 删除 `rejectWrite`；OpenAPI description 指向写设计。
- 入口日志：userId、ruleCode、routeMode、buyerCode、skuCode、supplierCodes（不含 weights 当生效配置）。

`RoutingRuleAdminControllerTest`：

- 改为 mock `commandService` + `queryService`。
- 断言 POST 调用 create；不再期望 `WRITE_NOT_OPEN`。
- 保留一个用例：mock create 抛 `ROUTING_RULE_BUYER_REQUIRED` 时 Controller 透传。

- [ ] **RED-GREEN + Commit** — `feat(supply-chain): 路由规则写 REST 接入补丁发布`

---

### Task 6: MySQL 集成测试（写路径 + 路由选中）

**Files:**

- Create: `test/.../routing/application/RoutingRuleAdminWriteIntegrationTest.java`

场景（设计 §10）：

1. Seed：Buyer、PlatformSku（ACTIVE，fulfillment 固定）、2× STANDARD_SUPPLIER、Offer 可选（若 `SupplierRoutingApplicationService.route` 需要 published offer — 复用 `SupplierRoutingApplicationServiceIntegrationTest` 的 offer 构造）。
2. Seed：启用策略 + 生效版本 + **两条**精确规则（不同 sku 或不同 buyer/sku/fulfillment）。
3. `commandService.update(ruleCode2, …)` 交换候选顺序。
4. 断言：`versionMapper.selectEffective` 仅新版本；旧 `valid_to` 已设；GET 顺序变化。
5. `SupplierRoutingApplicationService.route(RoutingInput(...))` 对 rule2 对应输入，断言选中 supplier 随 priority 变化，decision 原因码仍 `SELECTED_BY_STABLE_PRIORITY`。
6. `delete` 一条后仍剩一条；再 `delete` 最后一条 → `ROUTING_RULE_LAST_CANNOT_REMOVE`。

`@Import` 至少：`SupplyChainDataSourceConfiguration`、`RoutingRuleAdminCommandService`、`RoutingRuleAdminQueryService`、`RoutingPolicyPublicationService`、`SupplierRoutingApplicationService` 及其依赖 Bean（参考现有 IT 最小图）。

- [ ] **实现 IT 并跑通**（profile `supply-chain-integration`）

- [ ] **Commit** — `test(supply-chain): 路由规则页补丁发布集成测试`

---

### Task 7: 契约、架构、文档

**Files:**

- Modify: `test/.../interfaceapi/admin/SupplyChainAdminMethodSecurityContractTest.java`（Controller 构造增加 command mock；写入口仍 admin）
- Modify: `test/.../architecture/SupplyChainModuleArchitectureTest.java`（若新增规则：command 不得依赖 account/ledger dal — 与 query 服务对称）
- Modify: `docs/集成测试.md`「供应链路由规则可视化编辑」节：填自动化验证记录

- [ ] **跑定向测试套件**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
cd /Users/mac/.codex/worktrees/f887/supply-hub
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=RoutingRuleAdminCommandServiceTest,RoutingRuleSaveSupportTest,RoutingRuleAdminControllerTest,RoutingRuleAdminQueryServiceTest,SupplyChainAdminPermissionContractTest,SupplyChainAdminMethodSecurityContractTest,SupplyChainErrorMappingTest,SupplyChainModuleArchitectureTest
```

集成（可选本任务或 Task 6 已跑）：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am verify \
  -Psupply-chain-integration -Dsurefire.excludes= -Dtest=RoutingRuleAdminWriteIntegrationTest
```

- [ ] **Commit** — `docs(supply-chain): 补全路由规则写路径联调与门禁记录`

---

## Spec 覆盖自检

| 设计章节 | 任务 |
| --- | --- |
| w1–w6 决策 | Task 4–5（单 buyer、引导 policy、status 语义） |
| §3 REST | Task 5 |
| §4 请求体 | Task 2、5 |
| §5 事务 | Task 3、4 |
| §7 错误码 | Task 1、2、4 |
| §8 日志 | Task 5 |
| §10 测试 | Task 2、4、6、7 |
| 不改引擎/无 Liquibase | 全计划 |
| 前端改形 §9 | 不实施（文档已写） |

## 执行前提醒

- 所有写事务使用 `supplyChainTransactionManager`。
- 禁止在 Mapper 上新增 `@Select` 注解 SQL；版本 `valid_to` 更新用 `LambdaUpdateWrapper` 或 `updateById`。
- 重要类注释链到 `docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#章节`。
- Git 提交说明用中文。

---

**Plan complete and saved to `docs/superpowers/plans/2026-09-22-routing-rule-admin-write.md`.**

两种执行方式：

1. **Subagent-Driven（推荐）** — 每个 Task 派生子 agent，任务间人工/主 agent 复核。
2. **Inline Execution** — 本会话按 Task 1→7 连续 RED-GREEN，Checkpoint 在 Task 3、6 后各跑一次集成。

你选哪一种？若直接说「开始实现」，默认 **Inline Execution** 从 Task 1 起。
