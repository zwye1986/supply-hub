# 供应商订单只读工作台

本文落实 PRD「供应商订单」在本期的只读裁剪：运营查看中台向供应商推送的履约尝试，不改选供、不发货、不补发、不退款。

冲突时：路径、字段与安全边界以本文为准；平台总单列表/详情仍以 `docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md` 为准；路由记录以 `docs/superpowers/specs/2026-09-21-order-routing-records-admin-design.md` 为准。订单基数禁止拆单，见 ADR 0007 与 09-12 s15。金额与路径身份口径沿用 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` a2（CNY 分、业务 code）；前端关 mock 后对齐本文，后端不为 mock 再做适配层。

## 1. 范围

本期做：

- `GET /supply-chain/supplier-orders`
- `GET /supply-chain/supplier-orders/{bOrderNo}`
- 只读已有 `sc_b_order`，批量补平台总单快照与供应商编码/名称
- 权限复用 `supply-chain:order:query`（含 `supply-chain:admin` 超集）

本期不做：

- 一键补发、退款、发货、查单、重试、取消
- 卡密、CallLog、Adapter 原始报文、idempotencyKey、Offer/Connector/库存源内部主键
- 把 O2O/INSTORE 渠道单改写成 B 单；这两类不进本工作台
- 新 PRD 8.12 的并行供应商子单 1:N；串行 `attemptNo` 不是拆单
- 新 Liquibase；不把 `GET /supply-chain/orders?source=SUPPLIER` 当成聚合入口

## 2. 领域不变量

1. 一行响应 = 一次 B 端履约尝试（`sc_b_order`），不是平台总单。同一履约请求下 `attemptNo` 从 1 递增，同时最多一张 `active=true` 的活动尝试。
2. 查询不得推进状态、不得补写、不得调用供应商或渠道。
3. 尝试不存在 → `SUPPLY_CHAIN_B_ORDER_NOT_FOUND`。筛选条件没有命中 → HTTP 成功空页，不 404。
4. 尝试存在但总单或供应商档案缺失：总单缺失按数据损坏失败；供应商档案缺失时 `supplierCode`/`supplierName` 为空，不 404。
5. 非法 `fulfillment_status` / `settlement_status` 不得伪造枚举名，按数据损坏失败。
6. DTO `@Schema` 必须与下表 COMMENT 同一表述。
7. 对外状态枚举名对齐 `sc_b_order` CHECK 码，补齐现有 Java 枚举缺的 `0`（待执行 / 待报告）。

## 3. REST

网关前缀 `/admin-api`。路径遵守 09-02 a12：名词资源。身份用 `bOrderNo`，不用内部主键。

权限：`@PreAuthorize` 与平台订单相同，`ORDER_QUERY_EXPRESSION`。

### 3.1 分页列表

`GET /supply-chain/supplier-orders`

查询参数：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `bOrderNo` | String | B 端履约尝试稳定业务编号，精确匹配 |
| `orderNo` | String | 供应链总订单稳定业务编号，精确匹配 |
| `buyerCode` | String | 受理时采购商业务编码快照，精确匹配 |
| `supplierCode` | String | 尝试供应商业务编码，精确匹配 |
| `fulfillmentStatus` | 枚举名 | 见 §3.3 |
| `createdFrom` | LocalDateTime | 尝试创建时间下界，包含 |
| `createdTo` | LocalDateTime | 尝试创建时间上界，包含；早于 `createdFrom` 时拒绝 |
| `pageNo/pageSize` | Integer | 默认 1/20，最大 100 |

默认按 `created_at DESC, id DESC`。`orderNo` / `buyerCode` 先解析总表再 `IN order_id`；`supplierCode` 先解析 `sc_supplier` 再 `eq supplier_id`。解析不到对应档案时返回空页，不 404。

未知 `fulfillmentStatus` 在入口按 `ENUM_NOT_OPEN` 拒绝。时间逆序复用 `ORDER_QUERY_TIME_RANGE_INVALID`。

### 3.2 详情

`GET /supply-chain/supplier-orders/{bOrderNo}` 返回与列表同一映射函数生成的 `SupplierOrderView`，不另开 tagged union，不加载 Operation / CallLog。

### 3.3 响应字段

| 字段 | COMMENT |
| --- | --- |
| `bOrderNo` | B端履约尝试稳定业务编号 |
| `attemptNo` | 履约请求内供应商尝试序号，从1递增 |
| `active` | 活动尝试标记：true 非终态，false 终态 |
| `orderNo` | 供应链总订单稳定业务编号 |
| `orderType` | 订单类型：CARD/O2O/INSTORE；本工作台现网应为 CARD |
| `buyerCode` | 受理时采购商业务编码快照 |
| `buyerName` | 受理时采购商名称快照 |
| `buyerOrderNo` | 采购商侧C端订单号 |
| `supplierCode` | 实际尝试供应商业务编码 |
| `supplierName` | 实际尝试供应商名称 |
| `skuCode` | CARD场景平台SKU编码快照 |
| `skuName` | CARD场景平台SKU名称快照 |
| `billno` | 发给供应商的稳定业务单号 |
| `externalOrderNo` | 供应商返回的外部订单号 |
| `requestedQuantity` | 本次整单请求履约数量 |
| `fulfilledQuantity` | 本次已经成功交付数量 |
| `supplierSettlementUnitPrice` | 冻结的供应商履约结算单价，单位为分 |
| `expectedSupplierSettlementAmount` | 预计供应商结算金额，单位为分 |
| `currency` | 供应商结算币种，一期固定CNY |
| `fulfillmentStatus` | 对外枚举名，码见下表 |
| `settlementStatus` | 对外枚举名，码见下表 |
| `createdAt` | B端履约尝试创建时间 |
| `updatedAt` | B端履约尝试最后更新时间 |
| `startedAt` | 首次开始外部履约时间 |
| `completedAt` | 履约尝试进入终态时间 |

履约状态对齐 DDL CHECK（`0,10,20,40,50,90`）：

| 码 | 枚举名 | COMMENT |
| --- | --- | --- |
| 0 | `WAITING` | 待执行 |
| 10 | `PENDING` | 执行中 |
| 20 | `PROCESSING` | 查询中 |
| 40 | `SUCCEEDED` | 成功 |
| 50 | `FAILED` | 确定失败 |
| 90 | `MANUAL` | 人工处理 |

结算状态对齐 DDL CHECK（`0,10,20,90`）：

| 码 | 枚举名 | COMMENT |
| --- | --- | --- |
| 0 | `PENDING` | 待报告 |
| 10 | `MATCHED` | 已匹配 |
| 20 | `MISMATCH` | 有差异 |
| 90 | `MANUAL` | 人工处理 |

`PENDING`（履约码 10）保留现有 `BOrderFulfillmentStatus` 名称，避免改写履约生命周期；对外 OpenAPI 写明码值。受理落库的 `FulfillmentInitialStatuses.B_ORDER_PENDING=0` 映射为 `WAITING`。

不返回：内部主键、`idempotencyKey`、Offer/Connector/库存源 id、乐观锁 `version`、卡密、原始报文。

### 3.4 与前端页面的挂接

对照 `supplychainhubweb`（`src/views/supplier/order/supplier/index.vue`、`src/api/order/index.ts`）：

- 供应商订单页目前与平台页共用 `GET /supply-chain/orders?source=SUPPLIER`。`source` 只是 mock 过滤位，不是总表列。本切片不把 `source` 加进平台订单 GET，避免总单与 B 单混聚合。
- 前端关 mock 后改为 `GET /supply-chain/supplier-orders` 与 `GET /supply-chain/supplier-orders/{bOrderNo}`。路径身份是 `bOrderNo`，不是平台 `orderNo`。
- 金额单位为分；mock 的元、`VIRTUAL_CARD`、`WAIT_PAY`、`items[]` 由前端映射。
- 页面上的发货 / 一键补发 / 退款本期仍不提供后端命令。

## 4. 分层

`SupplierOrderAdminController` 只做查询参数、封闭枚举解析和权限。`border.order.application.SupplierOrderAdminQueryService` 在 `supplyChainTransactionManager` 只读事务中：

1. 校验时间区间。
2. 按精确条件组 `LambdaQueryWrapper` 分页读 `sc_b_order`。
3. 当页 `order_id` / `supplier_id` 批量换成总单快照与供应商编码名称。
4. 详情按 `bOrderNo` 读一行再走同一映射。

查询线程不访问账户/账本 DAL，不调用 `SupplierRoutingEngine` 或 Adapter。Mapper 只增 Lambda 查询，不新增注解 SQL，不新增变更集。

## 5. 日志

入口记录操作人、`bOrderNo` / 非敏感筛选、页码。禁止打印响应对象、`idempotencyKey`、卡密或原始报文。

## 6. 错误语义

| 场景 | 结果 |
| --- | --- |
| `fulfillmentStatus` 枚举未知 | `ENUM_NOT_OPEN` |
| `createdTo < createdFrom` | `ORDER_QUERY_TIME_RANGE_INVALID` |
| `bOrderNo` 不存在 | `SUPPLY_CHAIN_B_ORDER_NOT_FOUND` |
| 尝试存在、总单行缺失 | 数据损坏失败 |
| 权限不足 | 框架 `403` |

## 7. 测试门禁

- `*Test`：分页按创建时间倒序；`orderNo`/`supplierCode` 精确过滤；未知供应商编码空页；缺尝试 404；时间逆序拒绝；DTO 不含内部主键与幂等键；码 0 映射 `WAITING`/`PENDING`（结算）。
- `*ContractTest`：两个 HTTP 映射 `@PreAuthorize` 为 `order:query`；方法安全覆盖这两个 GET。
- `*IntegrationTest`：真实 MySQL 插入 CARD 总单 + B 单后，列表与详情读到同一 `bOrderNo`、`attemptNo`、关联平台 `orderNo`。
- `*ArchitectureTest`：管理端 `@PreAuthorize` 继续覆盖新 Controller；查询服务不得依赖账户/账本 DAL。

## 8. 订单展示修订（2026-10-15，已确认）

本节覆盖 §3.3、§3.4 中页面展示字段缺口，并补充失败关单设计 §8.1。用户确认供货模式按“当前合作商品配置”展示，不实现下单时快照。本轮不修改订单、资金、供应商调用与 Case 命令规则，不新增迁移。

### 8.1 当前合作商品供货模式与业务类型

- `/supplier-orders` 列表与详情统一追加可空 `supplyMode`：沿本次 `sc_b_order.offer_id` 读取 `sc_supplier_offer.offer_supply_mode`，语义为“该Offer供货模式（当前合作商品配置）”。不读当前供应商范围首项，不根据连接器或库存类型猜测，不使用固定 `API_DIRECT`。无 Offer 或模式为空时返回 NULL；Offer 修改后旧订单的展示随当前配置变化，不表述为历史事实。
- 页面列表供货模式列注明“当前合作商品配置”，详情供货模式值旁展示同一说明。仍以供应商尝试为列表粒度，不改为平台总单分页，也不切换到 `order-views?source=SUPPLIER`。
- 保留 `orderType=CARD/O2O/INSTORE` 的领域语义，追加可空 `bizType`，复用 `OrderViewMappings.bizType`，与平台订单一致区分卡密、直充、电影、点餐等。CARD 的 SKU 缺失时业务类型未知，不强制归为卡密；可读取 SKU 时继续遵守已有映射优先级。
- 前端顶层 `bizType` 与商品行 `orderType` 均使用响应 `bizType`，移除固定 `INSTORE` 及把原始 CARD 强制断言为前端业务类型的处理。旧后端缺少新字段时显示 `-`，不编造类型或模式。
- 分页对当页关联 SKU、SPU、Offer 分别批量读取，不新增逐行查询；不加载资产、原始报文或执行外呼。旧的 `order-views` 供应商兼容详情如返回供货模式，亦统一读取该尝试关联 Offer 当前值。

### 8.2 成功订单不展示失败处置

- 平台订单主状态成功时，所有嵌套尝试的 `failureClosure` 均为空；包括先失败、再换供应商成功的情况。保留尝试历史和编号，不删除 Case 或审计记录。
- 单笔供应商尝试成功时 `failureClosure` 为空，即使平台仍在等待资产持久化，也不能将成功供应商单展示为可失败处置。
- 前端独立过滤平台 `SUCCESS` 和供应商 `SUCCEEDED`（兼容 `FINISHED`）的处置摘要，避免后端先后发布差异导致成功面板误显。
- 本轮只排除成功；MANUAL、FAILED、CANCELLED 等非成功订单保持既有原因、金额及资金摘要行为，不以缺少 Case 为唯一隐藏条件。按钮权限和最终安全校验保持原实现。

### 8.3 回归与交付边界

先在真实 API 映射、查询服务及详情公开入口补回归并确认旧实现失败，再实现修复。覆盖：CARD 卡密/直充区分、Offer 选择不同于供应商范围首项、模式修改/空值、分页与详情一致、成功后不再返回处置摘要、先失败后成功、非成功保留摘要。真实 MySQL/Liquibase 验证关联查询和当前配置更新；契约与 ArchUnit 验证无新增权限或数据所有权越界；前端检查与实际 Element Plus 页面截图验证列表及成功/人工详情。

本地完成不代表测试环境上线；联调证据、未执行场景和发布后步骤写入 `docs/集成测试.md`。发布顺序为后端后前端；本轮不推送、部署或处置真实订单。
