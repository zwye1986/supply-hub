# 管理端路由规则只读投影

本文落实前端「路由规则」页（`supplychainhubweb` `src/views/supplier/routing/rule`）在本期的只读对齐：运营查看**当前正在生效**的标准商品路由规则与候选顺序，不改选供算法，不物化 PRD 四级配置组，不落综合权重。

冲突时：路径、字段与写拒绝以本文为准；引擎排序仍以 `docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md` s12 为准（`candidatePriority → supplierCode → offerId`，原因码 `SELECTED_BY_STABLE_PRIORITY`）；草稿创建与不可变发布仍走既有 `POST /supply-chain/routing-policies/drafts` 与 `POST /supply-chain/routing-policies/versions/{versionId}/publish`。金额与路径身份口径沿用 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` a2。前端关 mock 后对齐本文，后端不为 mock 的元、点餐/电影类型或全局权重再做适配层。

## 1. 范围

本期做：

- `GET /supply-chain/routing-rules`
- `GET /supply-chain/routing-rules/{ruleCode}`
- 只读当前 **启用中** 的 `STANDARD_PRODUCT` 策略、在数据库时钟 `now` 下 **已发布且生效** 的版本中的 `sc_standard_product_routing_rule` + 候选
- 为对齐前端已写路径，登记 `POST /`、`PUT /{ruleCode}`、`DELETE /{ruleCode}`、`PUT /{ruleCode}/status`，一律业务拒绝
- 权限沿用既有路由管理：仅 `supply-chain:admin`（不新增 `routing:query`，不改菜单 SQL）

本期不做：

- 综合排序、权重落库、`PRICE_ASC`、引擎读履约分
- 给规则表加项目 / 商品类型 / 品类列模拟四级配置组
- 写入 `sc_scenario_routing_*`；点餐 / 电影 / 京东 / 大润发 / 淘宝闪购不出现在本列表
- 把 `AUTO` +「全部供应商」理解成运行时扫描全部合作 Offer
- 系统预置「全局默认规则」实体
- 新 Liquibase；不废弃既有 `/routing-policies` 草稿发布

## 2. 领域不变量

1. 一行响应 = 当前生效版本里的一条标准商品规则，不是场景规则，不是草稿。
2. 查询不得推进状态、不得补写、不得调用 `SupplierRoutingEngine` 或 Adapter。
3. 无启用策略或无生效版本 → HTTP 成功空页，不 404。规则编码在当前生效版本中不存在 → `ROUTING_RULE_NOT_FOUND`。
4. `buyer_id` / `platform_sku_id` / `supplier_id` 档案缺失：分销商、SKU、供应商编码名称为空，不 404。空候选按数据损坏失败（与发布门禁「禁止空候选」一致）。
5. `projectMode` / `productTypeMode` 固定 `ALL`；`priorities` 固定稳定第一，价格/综合仅占位；`weights` 仅展示占位，不落库、引擎不读。
6. 写入口无论入参是否像页面 VO，一律 `ROUTING_RULE_WRITE_NOT_OPEN`，禁止部分落库。
7. DTO `@Schema` 必须与下表表述一致。

## 3. REST

网关前缀 `/admin-api`。身份用 `ruleCode`（当前生效版本内唯一），不用内部主键。

权限：每个 HTTP 映射 `@PreAuthorize` 为 `ADMIN_EXPRESSION`。

### 3.1 分页列表

`GET /supply-chain/routing-rules`

查询参数：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `name` | String | 规则中文名称，trim 后包含匹配；空则不过滤 |
| `distributorCodes` | String[] | 分销商业务编码；`ALL` 规则始终命中，指定规则与集合求交 |
| `projectCodes` | String[] | 接受但不参与过滤（本期无项目维度） |
| `productTypeCodes` | String[] | 接受但不参与过滤（本期无商品类型维度） |
| `supplierCodes` | String[] | 候选供应商业务编码求交；解析不到任何编码时，仅当规则本身也无候选名称时才可能命中，否则空页 |
| `status` | 枚举名 | `ENABLED` / `DISABLED`；当前生效规则恒为 `ENABLED`，`DISABLED` 得空页 |
| `updaters` | String[] | 与 `updated_by` 的十进制字符串精确匹配；本仓不查 system 用户名 |
| `updateFrom` / `updateTo` | LocalDateTime | 规则 `updated_at` 闭区间；逆序拒绝 |
| `pageNo` / `pageSize` | Integer | 默认 1/20，最大 100 |

未知 `status` → `ENUM_NOT_OPEN`。时间逆序复用 `ORDER_QUERY_TIME_RANGE_INVALID`。

默认按规则 `updated_at DESC, id DESC`。先定位启用策略与生效版本，再读该版本全部规则后内存过滤分页（一期一策略版本内规则量为运营配置，禁止列表 N+1 补档案）。

### 3.2 详情

`GET /supply-chain/routing-rules/{ruleCode}` 返回与列表同一映射。只在当前生效版本内查找。

### 3.3 写入口（拒绝）

| 方法 | 路径 | 结果 |
| --- | --- | --- |
| POST | `/supply-chain/routing-rules` | `ROUTING_RULE_WRITE_NOT_OPEN` |
| PUT | `/supply-chain/routing-rules/{ruleCode}` | 同上 |
| DELETE | `/supply-chain/routing-rules/{ruleCode}` | 同上 |
| PUT | `/supply-chain/routing-rules/{ruleCode}/status` | 同上 |

`PUT .../status` 仅为对齐前端已写动词路径，**不是**真启停，也不把 `/status` 扩散到其它资源。请求体不校验、不落库。

### 3.4 响应字段

| 字段 | 语义 |
| --- | --- |
| `ruleCode` | 策略版本内稳定规则编码 |
| `name` | 标准商品路由规则中文名称 |
| `protectedRule` | 恒为 `false`；本期无系统预置不可删规则 |
| `distributorMode` | `buyer_id` 空=`ALL`，否则 `SPECIFIED` |
| `distributorCodes` / `distributorNames` | 指定时一条分销商编码/名称；`ALL` 时为空数组 |
| `projectMode` | 恒 `ALL` |
| `projectCodes` / `projectNames` | 恒空数组 |
| `productTypeMode` | 恒 `ALL` |
| `productTypeCodes` / `productTypeNames` | 恒空数组 |
| `routeMode` | 候选数=1 为 `FIXED`，≥2 为 `AUTO` |
| `supplierMode` | 恒 `SPECIFIED`（引擎只认规则内候选，没有「全部供应商」池） |
| `supplierCodes` / `supplierNames` | 按 `candidate_priority, supplier_id` 升序 |
| `primarySupplierCode` / `primarySupplierName` | 仅 `FIXED` 时等于那一个候选；`AUTO` 为空 |
| `skuCode` / `skuName` | 规则绑定的平台 SKU；未限定或档案缺失为空 |
| `fulfillmentType` | 履约类型条件；未限定为空 |
| `priorities` | 固定三条：`STABLE`、`PRICE`、`COMPOSITE`。仅 `STABLE` 与引擎一致；后两项占位让页面三列有序号 |
| `weights` | 固定 `{stability:40, price:30, timeliness:30}` 展示占位，与页面 mock 默认相同，**不落库** |
| `status` | 当前生效规则为 `ENABLED` |
| `updater` | `updated_by` 的十进制字符串，无则空 |
| `updateTime` | 规则最后修改时间 |

不返回：内部主键、策略/版本 id、`config_digest`、检查模式、数量区间以外的草稿字段、卡密、原始报文。

## 4. 分层

`RoutingRuleAdminController` 只做参数、封闭枚举、权限与写拒绝。`routing.application.RoutingRuleAdminQueryService` 在 `supplyChainTransactionManager` 只读事务中：

1. 用 `SupplyChainDatabaseClock.now()` 取启用中的 `STANDARD_PRODUCT` 策略（0 条空页，多于 1 条按数据损坏失败）。
2. `selectEffective(policyId, now)` 取当前生效已发布版本（0 条空页，多于 1 条按数据损坏失败）。
3. 读该版本规则与候选，批量补分销商 / SKU / 供应商编码名称。
4. 过滤、排序、分页；详情按 `ruleCode` 命中一行。

查询线程不访问账户/账本 DAL，不调用引擎。Mapper 只增 Lambda 查询，不新增注解 SQL，不新增变更集。

## 5. 日志

入口记录操作人、`ruleCode` / 非敏感筛选、页码。禁止打印完整规则体、摘要或权重被当成已生效配置。写拒绝打 info（含操作人与方法），防风暴按 userId+路径聚合。

## 6. 错误语义

| 场景 | 结果 |
| --- | --- |
| `status` 枚举未知 | `ENUM_NOT_OPEN` |
| `updateTo < updateFrom` | `ORDER_QUERY_TIME_RANGE_INVALID` |
| 当前生效版本无此 `ruleCode` | `ROUTING_RULE_NOT_FOUND` |
| POST/PUT/DELETE/启停 | `ROUTING_RULE_WRITE_NOT_OPEN` |
| 规则存在但候选为空 | 数据损坏失败 |
| 权限不足 | 框架 `403` |

## 7. 与前端页面的挂接

对照 `src/api/routing/index.ts`（`USE_ROUTING_MOCK=true`）与 `src/views/supplier/routing/rule`：

- 关 mock 后列表/详情打本资源。页面仍展示项目、商品类型、价格/综合列：后端固定「全部」与占位优先级，避免假装已按 mock 规则选供。
- 新增 / 编辑 / 删除 / 启停会收到 `ROUTING_RULE_WRITE_NOT_OPEN`。页面按钮可留，不在本仓改前端。
- 表单要求的 SKU、履约类型、检查模式不在 mock VO 里；真要可写必须另开设计，不能从本 VO 推断。
- 真要改候选顺序：继续用既有 `/routing-policies` 发不可变版本，不在本页假装 CRUD。

## 8. 测试门禁

- `*Test`：生效版本两条规则按更新时间倒序；1 候选=`FIXED`、2 候选=`AUTO`；名称包含匹配；无启用策略空页；缺规则 404；时间逆序拒绝；写入口拒绝；DTO 不含内部主键；`priorities[0]=STABLE`。
- `*ContractTest`：六个 HTTP 映射 `@PreAuthorize` 均为 `admin`；方法安全覆盖 GET 与一个写入口。
- `*IntegrationTest`：真实 MySQL 插入启用策略、已发布生效版本、一条双候选规则后，列表与详情读到同一 `ruleCode` 与供应商顺序。
- `*ArchitectureTest`：管理端 `@PreAuthorize` 覆盖新 Controller；查询服务不得依赖账户/账本 DAL；`routing.domain` 仍不出现分数字段。
