# 管理端路由规则可视化编辑（补丁发布）

本文落实方案 A：前端「路由规则」页（`supplychainhubweb` `src/views/supplier/routing/rule`、`src/api/routing`）在关掉 mock 后，用已写路径真正改**当前生效**的标准商品规则与候选顺序。保存不是原地 UPDATE 已发布行，而是克隆生效版本、打一条补丁、关闭旧生效区间并立即发布新版本。

冲突时：写契约、请求体校验、发布事务以本文为准。GET 字段映射仍以 `docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md` 为准（本文只改写入口与「可写字段」约束）。引擎排序仍以 `docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md` s12 为准（`candidatePriority → supplierCode → offerId`，原因码 `SELECTED_BY_STABLE_PRIORITY`）。草稿整包替换仍可走 `POST /supply-chain/routing-policies/drafts` 与 `POST /supply-chain/routing-policies/versions/{versionId}/publish`，规则页不调这两条。金额与路径身份沿用 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` a2。本仓不改前端工程（a13）；关 mock 前前端必须按第 9 节改表单，否则会打到本文的业务拒绝。

已确认（方案 A）：

| 编号 | 决策 |
| --- | --- |
| w1 | 同一套 `/supply-chain/routing-rules` URL。GET 保持投影；POST/PUT/DELETE/`PUT /{ruleCode}/status` 改为真写。 |
| w2 | 一行规则 = 精确三元组 `buyer + platformSku + fulfillmentType` + 有序候选。不物化四级配置组，不落权重，不改引擎。 |
| w3 | 适用分销商 **必须恰好一个** `buyerCode`。不允许空（不做全局回退）、不允许多选展开成 N 条。 |
| w4 | 停用没有独立存储。`DISABLED` 与删除相同：下一版本拿掉该 `ruleCode`。列表只展示生效规则，故全是 `ENABLED`。 |
| w5 | `PUT /{ruleCode}/status` 仅对齐前端已写动词路径（a12 例外，不扩散到其它资源）。 |
| w6 | 无启用策略时，首次 POST 引导创建 `policyCode=STANDARD_PRODUCT_PRIMARY` 的标准策略并发布第一版。已有启用中的 `STANDARD_PRODUCT` 策略则补丁打在该策略上，不新建第二份启用策略。 |

## 1. 范围

本期做：

- 将 `RoutingRuleAdminController` 的四个写入口改为调用命令服务
- 在 `supplyChainTransactionManager` 中：克隆当前生效已发布版本的全部规则 → 应用新增/替换/移除 → 关闭旧版本 `valid_to` → 创建草稿 → 立即发布
- 请求体校验：SKU、履约类型、单分销商、有序候选；拒绝项目/商品类型/全部供应商/可写综合权重
- 检查模式不进表单：新候选默认三检查均为 `UNSUPPORTED`；从上一版本拷来的同一供应商保留原检查模式
- 权限仍只认 `supply-chain:admin`（不新增 `routing:update`，不改菜单 SQL）
- 无新 Liquibase（只改已发布版本的 `valid_to` 与插入新版本行）

本期不做：

- 综合排序、权重落库、`PRICE_ASC`、引擎读 Offer 履约分、topN、人工处理队列
- 给规则表加项目 / 商品类型 / 品类列；`buyer_id` 空的全局兜底匹配
- `supplierMode=ALL`（运行时扫描全部合作 Offer）
- 写入 `sc_scenario_routing_*`；点餐 / 电影 / 京东 / 大润发 / 淘宝闪购专属规则
- 系统预置「全局默认规则」、`protectedRule=true`
- 改历史订单的路由 round/record
- 编辑未发布草稿、清理历史草稿、暴露检查模式或数量区间
- 修改 `supplychainhubweb`

## 2. 领域不变量

1. 已发布版本及其规则/候选行仍不可变。页面「保存成功」= 新版本已发布且成为 `now` 下唯一生效版本。
2. 查询线程仍不得调用 `SupplierRoutingEngine` 或 Adapter；写线程也不得调用引擎或 Adapter，只改配置并走既有发布校验。
3. 运行时匹配保持精确：`selectExactMatches(versionId, buyerId, skuId, fulfillmentType)`。没有分销商+品类回退，没有「全部分销商」规则。
4. 同一新版本内 `(buyerId, platformSkuId, fulfillmentType)` 唯一；`ruleCode` 在版本内唯一，且跨版本对同一逻辑规则保持稳定（编辑/删除按 `ruleCode` 寻址）。
5. 发布后的版本至少一条规则、每条至少 1 个候选。删掉最后一条 → 业务拒绝，不发布空版本。
6. 候选供应商必须是启用中的 `STANDARD_SUPPLIER`；Buyer、SKU 必须为启用/上架中，且请求的 `fulfillmentType` 与 SKU 档案一致。
7. `FIXED` 必须恰好 1 个候选；`AUTO` 必须 ≥2 个互异有序候选。顺序即 `candidate_priority`（0 起，与列表下标一致）。
8. 写请求里出现项目范围、商品类型范围、「全部供应商」、多个分销商、或把 PRICE/COMPOSITE/权重当成可配排序 → 整单拒绝，禁止部分落库。
9. 无启用策略或无生效版本时：GET 列表仍空页；GET 详情 / PUT / DELETE / 启停 → `ROUTING_RULE_NOT_FOUND`；仅 POST 允许作为第一规则引导发布。
10. DTO `@Schema` 必须与本文及只读文同一表述。

## 3. REST

网关前缀 `/admin-api`。身份用 `ruleCode`。类级 `@PreAuthorize(ADMIN_EXPRESSION)`。

### 3.1 读

`GET /supply-chain/routing-rules`、`GET /supply-chain/routing-rules/{ruleCode}` 不变，见只读文 §3。

本页写出的规则在投影上固定：`distributorMode=SPECIFIED` 且 `distributorCodes` 长度为 1；`supplierMode=SPECIFIED`；`projectMode`/`productTypeMode` 仍为 `ALL`；`priorities[0]=STABLE`；`weights` 仍为占位 `{40,30,30}`；`protectedRule=false`；`status=ENABLED`。`skuCode` / `fulfillmentType` 对页面写出的规则必有值。

### 3.2 新增

`POST /supply-chain/routing-rules`

Body：`RoutingRuleSaveReqVO`（§4）。忽略 body 内 `ruleCode`（若传了也不得采用），由服务端生成。

成功：HTTP 业务成功，返回与 GET 详情同一 `RoutingRuleView`（含新 `ruleCode`）。立即成为当前生效版本中的一行。

### 3.3 整资源替换

`PUT /supply-chain/routing-rules/{ruleCode}`

当前生效版本必须已有该 `ruleCode`，否则 `ROUTING_RULE_NOT_FOUND`。Body 的 `ruleCode` 若出现且与路径不一致 → `ROUTING_RULE_CODE_MISMATCH`。路径编码写入新版本，不换码。

成功返回更新后的 `RoutingRuleView`。

### 3.4 删除

`DELETE /supply-chain/routing-rules/{ruleCode}`

从下一版本移除该规则。若这是生效版本里的最后一条 → `ROUTING_RULE_LAST_CANNOT_REMOVE`。成功返回空 data。

### 3.5 启停（前端兼容）

`PUT /supply-chain/routing-rules/{ruleCode}/status`

Body：`{ "status": "ENABLED" | "DISABLED" }`。未知枚举 → `ENUM_NOT_OPEN`。

| 当前 | 目标 | 行为 |
| --- | --- | --- |
| 生效版本有该规则 | `DISABLED` | 与 DELETE 相同（移除并发布） |
| 生效版本有该规则 | `ENABLED` | **空操作**：不发新版本，返回当前 `RoutingRuleView` |
| 生效版本无该规则 | 任一 | `ROUTING_RULE_NOT_FOUND`（停用记录不落库，无法从本接口恢复） |

恢复被停用的规则：用 POST 再配一条（新 `ruleCode`）。

## 4. 请求体与页面字段

### 4.1 `RoutingRuleSaveReqVO`（POST/PUT）

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `name` | 是 | 标准商品路由规则中文名称；trim 后 1–128 字 |
| `distributorCodes` | 是 | 恰好 **一个** 分销商业务编码 |
| `skuCode` | 是 | 平台 SKU 业务编码 |
| `fulfillmentType` | 是 | 与 SKU 档案 `fulfillment_type` 完全一致；封闭枚举与 DDL CHECK 相同：`DIRECT_RECHARGE` / `VOUCHER_CODE` / `CARD_CODE_SECRET` / `VOUCHER_URL` / `ACCOUNT_BOUND_BENEFIT` |
| `routeMode` | 是 | `FIXED` / `AUTO` |
| `primarySupplierCode` | `FIXED` 时是 | 唯一候选 |
| `supplierCodes` | `AUTO` 时是 | **有序**、去重后长度 ≥2；顺序即降级顺序 |
| `supplierMode` | 否 | 缺省或 `SPECIFIED` 才接受；`ALL` 拒绝 |
| `distributorMode` | 否 | 缺省按 `distributorCodes` 推导；若传必须为 `SPECIFIED` |
| `status` | 否 | 仅允许 `ENABLED` 或省略；`DISABLED` 请走删除/启停，不在保存体里停用 |
| `ruleCode` | PUT 可选 | 见 §3.3 |
| `projectCodes` / `projectMode` | 否 | 非空或 `SPECIFIED` → 拒绝 |
| `productTypeCodes` / `productTypeMode` | 否 | 同上 |
| `priorities` | 否 | 省略则忽略；若出现则 `priorities[0].strategy` 必须为 `STABLE`，三条须为 STABLE/PRICE/COMPOSITE 各一 |
| `weights` | 否 | 省略则忽略；若出现必须为 `{stability:40, price:30, timeliness:30}` |
| `protectedRule` / 名称数组 / `updater` / `updateTime` | 否 | 忽略，不作为写入条件 |

`FIXED` 时忽略 `supplierCodes`，只采用 `primarySupplierCode` 生成长度为 1 的候选。`AUTO` 时忽略 `primarySupplierCode`。

前端当前 `RouteRuleVO` 没有 `skuCode`（GET 已有）。**关 mock 前必须把 SKU、履约类型和有序候选补进请求体**，见第 9 节。未改形的 mock 体（项目/类型/无 SKU）会失败关闭，不会假装保存成功。

### 4.2 页面 → 内核

| 页面意图 | 落库 |
| --- | --- |
| 规则名称 | `sc_standard_product_routing_rule.rule_name` |
| 一个分销商 | `buyer_id`（解析 `sc_buyer.buyer_code`） |
| 平台 SKU | `platform_sku_id` |
| 履约类型 | `fulfillment_type` |
| 候选顺序 | `sc_standard_product_routing_candidate.candidate_priority` 升序 |
| 新候选检查模式 | 库存/余额/健康均为 `UNSUPPORTED`，maxAge 空 |
| 从上一版本拷贝且供应商仍在名单中的候选 | 保留原检查模式与 maxAge |
| `rule_priority` | 未改动的拷贝行保留原值；新增或整行替换为 0 |
| 项目 / 商品类型 / 权重 / PRICE 第一 | 不落库，请求拒绝 |

`ruleCode` 生成：`RR` + 数据库时钟日期 `yyyyMMdd` + 序号。序号取本补丁结果集中已占用的同日前缀最大值 + 1，不足 3 位左补 0，超过 999 增宽不回绕。编辑不换码。

## 5. 保存事务

`RoutingRuleAdminCommandService` 在 `supplyChainTransactionManager` 中编排；发布细节复用并扩展 `RoutingPolicyPublicationService`（新增「关闭旧区间 + 用完整规则集发后继版本」，禁止在 Controller 里拼草稿）。

时钟一律 `SupplyChainDatabaseClock.now()`，记为 `now`。

```text
锁启用中 STANDARD_PRODUCT 策略（无则走引导，见下）
读 now 下唯一生效已发布版本全部规则+候选
  无生效版本且不是 POST 第一规则 → NOT_FOUND
若 now 不晚于该版本 valid_from → ROUTING_RULE_VERSION_CONFLICT（禁止把 valid_to 收到等于 valid_from）
在内存中打补丁，得到新规则集
  空集 → LAST_CANNOT_REMOVE
  精确三元组重复 → MATCH_OVERLAP
校验 Buyer/SKU/Supplier/履约类型（失败映射 §7，不把 IllegalArgumentException 漏成 500）
旧版本 valid_to = now（仍保持 PUBLISHED，不改历史 digest）
createDraft：policyCode/Name 沿用当前策略；versionName = 「规则页保存 」+ now；validFrom=now；validTo 空；rules=补丁结果
publish：原因「管理端路由规则页保存」；仍写既有 Outbox ROUTING_POLICY_PUBLISHED
返回目标 ruleCode 的投影（同事务可读新版本：validFrom<=now 且 validTo 空；旧版本 validTo>now 不成立）
```

引导（无启用策略）：

- `policyCode` 固定 `STANDARD_PRODUCT_PRIMARY`，`policyName` 固定 `标准商品供给路由`
- 若该 code 已存在但未启用（例如曾用 `/routing-policies` 建过草稿），沿用其名称校验规则（名称不得通过版本草稿改掉）；没有则插入
- 不得在已有**另一份**启用中的 `STANDARD_PRODUCT` 策略时再启用第二份（沿用发布门禁）

未改动的规则必须原样进入新版本（含候选顺序与检查模式），禁止「只插一条、丢掉同版本其它规则」。

并发：策略行 / 旧版本行乐观锁失败，或 `now` 落在旧 `valid_from` 上 → `ROUTING_RULE_VERSION_CONFLICT`。不静默重试覆盖。

历史草稿（`status=0`）不清理、不编辑。页面每次保存都新建版本号。

既有 `POST /routing-policies/drafts` 行为不变：调用方仍须自己保证与已发布区间不重叠。规则页后继发布是唯一自动收口 `valid_to` 的路径。

## 6. 分层

- `RoutingRuleAdminController`：参数、封闭枚举、权限；POST/PUT 绑定 `RoutingRuleSaveReqVO` 并做 §4 的范围拒绝；不直接碰 Mapper。
- `routing.application.RoutingRuleAdminCommandService`：补丁、引导策略、冲突码。
- `RoutingPolicyPublicationService`：摘要、引用校验、插入规则/候选、发布、Outbox；新增后继发布方法，供命令服务调用。
- GET 仍只走 `RoutingRuleAdminQueryService`。

禁止：命令服务访问账户/账本 DAL；`routing.domain` 增加分数字段；新注解 SQL；为四级/权重加列。

## 7. 错误语义

全部 `DO_NOT_RETRY`。`ROUTING_RULE_WRITE_NOT_OPEN`（`1_050_001_120`）**不再从写入口抛出**，常量保留以免码段空洞，文案改为「路由规则写入口已开放，本错误码不再使用」。

| 场景 | 错误码 | 文案 |
| --- | --- | --- |
| 当前生效版本无此 `ruleCode`（含无生效版本时的 GET 详情/PUT/DELETE/启停） | `ROUTING_RULE_NOT_FOUND` `1_050_001_121` | 路由规则不存在 |
| 路径与 body `ruleCode` 不一致 | `ROUTING_RULE_CODE_MISMATCH` `1_050_001_122` | 规则编码与路径不一致 |
| 项目/商品类型非空或 mode=SPECIFIED | `ROUTING_RULE_SCOPE_NOT_OPEN` `1_050_001_123` | 路由规则不支持按项目或商品类型配置 |
| `supplierMode=ALL` | `ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN` `1_050_001_124` | 路由规则必须指定候选供应商，不支持全部供应商 |
| 分销商不是恰好一个 | `ROUTING_RULE_BUYER_REQUIRED` `1_050_001_125` | 适用分销商必须指定恰好一个 |
| priorities/weights 违反 §4.1 | `ROUTING_RULE_STRATEGY_NOT_OPEN` `1_050_001_126` | 本期仅支持按候选顺序的稳定优先 |
| FIXED 无主供应商 / AUTO 不足 2 个或有重复 | `ROUTING_RULE_CANDIDATE_INVALID` `1_050_001_127` | 固定模式必须一个主供应商，自动模式必须两个及以上互异有序候选 |
| 同版本精确三元组重复 | `ROUTING_RULE_MATCH_OVERLAP` `1_050_001_128` | 已存在相同分销商、平台SKU与履约类型的路由规则 |
| 发布结果将变成 0 条规则 | `ROUTING_RULE_LAST_CANNOT_REMOVE` `1_050_001_129` | 当前生效版本至少保留一条路由规则 |
| 乐观锁或生效时刻冲突 | `ROUTING_RULE_VERSION_CONFLICT` `1_050_001_130` | 路由规则版本已变更，请刷新后重试 |
| 履约类型与 SKU 档案不一致或非封闭枚举 | `ROUTING_RULE_FULFILLMENT_MISMATCH` `1_050_001_131` | 履约类型必须与平台SKU档案一致 |
| `status` 等封闭枚举未知 | `ENUM_NOT_OPEN` | 既有 |
| 分销商编码不存在 | `BUYER_NOT_FOUND` | 既有 |
| 分销商未启用 | `BUYER_DISABLED` | 既有 |
| SKU 不存在 | `PLATFORM_SKU_NOT_FOUND` | 既有 |
| SKU 非上架/启用 | `SKU_NOT_SALEABLE` | 既有 |
| 供应商不存在或非启用中标准供应商 | `SUPPLIER_NOT_FOUND` / `ROUTING_RULE_CANDIDATE_INVALID` | 不存在用前者；类型/停用归入候选非法 |
| 权限不足 | 框架 `403` | |

保存中途失败必须回滚：旧 `valid_to` 不得单独提交。

## 8. 日志

写入口记录操作人、`ruleCode`、`routeMode`、buyerCode、skuCode、候选编码列表、是否引导首版。禁止打印完整规则体、检查模式细节当审计替代、或把 `weights` 打成已生效配置。冲突与拒绝打 info，防风暴按 `userId+路径+错误码` 聚合。第三方调用：本路径无 Adapter。

## 9. 与前端页面的挂接

对照 `src/api/routing/index.ts`（目前 `USE_ROUTING_MOCK=true`）：

路径可保留。关 mock 并要保存成功，`supplychainhubweb` 必须另开任务改形（本仓不改）：

1. `RouteRuleVO` / 表单增加必填 `skuCode`、`fulfillmentType`；列表增加 SKU、履约类型列。
2. 适用分销商改为 **单选**，选项 `GET /supply-chain/buyers`，删除 `MOCK_DISTRIBUTORS`。
3. 删除适用项目、适用商品类型、优先级拖拽、综合权重；`priorities`/`weights` 可不传。
4. `AUTO` 候选改为 **可排序** 列表（不少于 2）；选项接真实供应商，删除 `MOCK_SUPPLIERS`。禁止「全部供应商」。
5. 启停/删除确认文案：将发布新版本，立即对后续下单生效，不改历史路由记录。停用 = 从生效集移除，列表不再出现该行。
6. 用后端 `msg` 展示拒绝（缺 SKU、范围未开放、最后一条等）。
7. 更新人/更新时间以 GET 为准，删除 `MOCK_UPDATERS`。

稳定性/时效分仍在合作商品页维护；本页 STABLE 只表示候选名单顺序。在引擎消费履约分之前，不得把「稳定性评分越高越优先」写成已生效行为。

## 10. 测试门禁

- `*Test`：无策略时 POST 首条成功且 GET 能读到；第二 POST 保留第一条；PUT 改候选顺序后 GET 顺序变化且旧 `ruleCode` 不变；DELETE 最后一条拒绝；DELETE 非最后一条后 GET 404；`DISABLED` 同删除；`ENABLED` 空操作不新增版本号；带 `projectCodes` / `supplierMode=ALL` / 两个 buyer / 无 sku / AUTO 一个供应商 / weights 非 40/30/30 均拒绝且无新版本；精确三元组冲突拒绝。
- `*ContractTest`：六个 HTTP 映射仍为 `admin`；方法安全覆盖 GET 与至少一个写入口成功路径。
- `*IntegrationTest`：真实 MySQL 先插入启用策略+生效版本+两条规则，页面 PUT 改其中一条候选顺序后：新版本 `valid_from=now`、旧版本 `valid_to` 已关闭、GET 读到新顺序、`selectEffective(now)` 只有新版本、历史 `version_no` 行仍在；再用 `SupplierRoutingApplicationService.route` 断言选中顺序随 `candidate_priority` 变，原因码仍为 `SELECTED_BY_STABLE_PRIORITY`。无新增 Liquibase。
- `*ArchitectureTest`：命令服务不得依赖账户/账本 DAL；`routing.domain` 仍无分数字段；写入口有 `@PreAuthorize`。

既有 `RoutingPolicyPublicationService` 集成测试（草稿区间重叠拒绝）保持：直接打 `/routing-policies` 仍不自动收口，与规则页后继发布分开断言。
