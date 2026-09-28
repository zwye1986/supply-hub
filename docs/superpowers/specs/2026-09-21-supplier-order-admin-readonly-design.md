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
