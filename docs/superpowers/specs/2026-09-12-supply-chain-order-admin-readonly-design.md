# 供应链平台订单管理端只读查询详细设计

## 1. 文档定位

本文落实[《供应链中台 PRD 一期范围对齐》](./2026-09-12-supply-chain-prd-scope-alignment-design.md#11-建议实施顺序)第 3 项，定义供应链运营后台的订单列表与详情 REST。只覆盖现有 `CARD`、`O2O`、`INSTORE` 三类订单，不新增订单命令、供应商订单工作台、售后命令、实物包裹或外部查单。

订单状态机、数据所有权和场景详情模型仍以[《供应链中台一期详细设计》](./2026-08-07-supply-chain-phase1-detailed-design.md#7-订单模型)为准；运营功能权限沿用[《供应链中台运营后台 RBAC 详细设计》](./2026-09-03-supply-chain-admin-rbac-design.md)。

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| o1 | 新增 `GET /supply-chain/orders` 与 `GET /supply-chain/orders/{orderNo}`；路由记录只读 GET 见 2026-09-21 专文。本切片没有 POST、PUT、PATCH、DELETE。 |
| o2 | 列表只读 `sc_supply_chain_order`，不得连接或逐行查询场景详情、供应商、采购商档案，也不得调用外部服务。 |
| o3 | 列表筛选为订单号、采购商订单号、采购商编码、展示供应商编码、SKU 编码、订单类型、公共主状态、售后状态、受理时间区间；默认按 `created_at DESC, id DESC`，默认每页 20 条。 |
| o4 | 详情先读总表摘要，再按 `detail_type` 只加载一个类型化分支。详情行不存在或类型未注册时，HTTP 业务响应仍成功，`detailAvailability=UNAVAILABLE`、`detailErrorCode=DETAIL_UNAVAILABLE`。总订单不存在才返回稳定业务错误。 |
| o5 | CARD 详情只返回履约请求编号、目标 Schema 编码/版本、请求/完成数量、履约状态与时间；禁止返回履约目标密文、摘要、密钥版本、卡密/券码和供应商原始报文。 |
| o6 | O2O 详情返回渠道交易、门店展示、收银台引用、支付/资金/渠道履约状态及累计退款金额；不返回渠道匿名用户标识和门店原始 JSON。 |
| o7 | INSTORE 详情返回渠道订单头和按 `flow_sequence` 排序的支付流水列表；不返回收银台 URL、会员编号、请求指纹和资金预留内部引用。当前生产 1:1 不变量不收窄数据库已有 1:N 读模型。 |
| o8 | 新权限 `supply-chain:order:query` 保护列表、详情与路由记录三个 GET；历史 `supply-chain:admin` 仍是超集。运营数据范围本期仍不做。 |
| o9 | 本切片不新增 Liquibase。查询使用 MyBatis-Plus Lambda Wrapper；列表命中已有订单号、采购商时间、供应商时间和状态时间索引，组合筛选是否加覆盖索引待真实慢查询证据。 |

## 3. REST 契约

### 3.1 分页列表

`GET /supply-chain/orders`

查询参数：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `orderNo` | String | 供应链中台订单号，精确匹配 |
| `buyerOrderNo` | String | 采购商订单号，精确匹配 |
| `buyerCode` | String | 受理时采购商编码快照，精确匹配 |
| `supplierCode` | String | 列表展示供应商编码快照，精确匹配 |
| `skuCode` | String | 标准 SKU 编码快照，精确匹配；O2O/INSTORE 通常为空 |
| `orderType` | `CARD/O2O/INSTORE` | 可选单值 |
| `orderStatus` | 公共主状态枚举 | 可选单值 |
| `aftersaleStatus` | 公共售后状态枚举 | 可选单值 |
| `createdFrom` | LocalDateTime | 受理时间下界，包含 |
| `createdTo` | LocalDateTime | 受理时间上界，包含；早于 `createdFrom` 时拒绝 |
| `pageNo/pageSize` | Integer | 默认 1/20，沿用框架分页上限 |

响应行全部来自 `sc_supply_chain_order`，字段为：`orderNo/orderType/buyerCode/buyerName/buyerOrderNo/supplierCode/supplierName/buyerSettlementAmount/salesUnitPriceCents/productSubtotalCents/serviceFeeCents/userPayableAmountCents/currency/skuCode/skuName/quantity/orderStatus/aftersaleStatus/businessStage/detailType/detailVersion/createdAt/updatedAt/completedAt`。标准商品展示冻结的服务费与用户实付；O2O/INSTORE 这些字段为空。状态对外返回枚举名，不返回数据库短整型码。

### 3.2 订单详情

`GET /supply-chain/orders/{orderNo}` 返回：

- `summary`：与列表同一映射函数生成的总表摘要。
- `detailAvailability/detailErrorCode`：场景详情可用性。
- `card/o2o/instore`：严格三选一；不可用时三者都为空。

CARD 履约状态枚举：`PENDING_ROUTING(0)`、`ROUTING(10)`、`FULFILLING(20)`、`SUCCEEDED(40)`、`FAILED(50)`、`MANUAL(90)`。

O2O 详情状态复用现有封闭枚举：`O2OPaymentStatus`、`O2OFundStatus`、`O2OChannelFulfillmentStatus`、`O2ODetailStatus`。INSTORE 复用 `InstoreOrderStatus`、`InstorePaymentFlowStatus`。数据库 CHECK 保证码值合法；发现非法码值时不伪造状态，按数据损坏异常失败。

## 4. 分层与数据访问

`OrderAdminController` 只做 HTTP 参数校验、封闭枚举解析和权限拦截。`border.order.application.OrderAdminQueryService` 负责编排总表与三类详情 Mapper，并集中完成 DTO 映射；查询不得推进状态或修复数据。

`SupplyChainOrderMapper` 继续承载总表 Lambda 查询。详情 Mapper 只增加必要的按 `order_id`、按渠道订单头查询方法，不新增注解 SQL。服务不依赖 `tradecore.account.dal` / `tradecore.ledger`，不越过供应链独立数据库边界。

所有查询运行在 `supplyChainTransactionManager` 的只读事务内，保证详情读取期间使用同一数据源；这不是跨行强一致快照承诺，响应中的 `detailVersion` 才是总表已投影版本。

## 5. 权限、日志与隐私

列表、详情与路由记录入口均使用 `@PreAuthorize("@ss.hasAnyPermissions('supply-chain:admin', 'supply-chain:order:query')")`。system 库部署 SQL增加只读「订单管理」页面菜单，不新增写按钮。

列表与详情入口记录操作人、非敏感筛选条件、页码或订单号。不得打印响应对象、`buyerUserId`、渠道匿名用户、履约目标、卡密、收银台 URL、门店原始 JSON或请求指纹。查询线程不调用供应商、渠道或 system/member 数据接口。

## 6. 错误语义

| 场景 | 结果 |
| --- | --- |
| 订单类型或状态枚举未知 | `ENUM_NOT_OPEN` |
| `createdTo < createdFrom` | `ORDER_QUERY_TIME_RANGE_INVALID` |
| 总订单不存在 | `SUPPLY_CHAIN_ORDER_NOT_FOUND` |
| 总表存在、详情行不存在/类型未注册 | 成功响应 + `DETAIL_UNAVAILABLE` |
| 权限不足 | 框架 `403` |

## 7. 测试与联调门禁

- `OrderAdminQueryServiceTest`：列表条件、稳定排序、状态映射；CARD/O2O/INSTORE 三分支；详情缺失降级；敏感字段不进入 DTO；路由记录 CARD 回放、非 CARD 空数组。
- `SupplyChainAdminPermissionContractTest` 与 `SupplyChainAdminMethodSecurityContractTest`：列表、详情、路由记录三个 HTTP 映射均有 `order:query` 与 `admin` 超集权限，并验证错误资源权限被拒绝；既有 `ClosedEnumsTest` 覆盖入口复用的未知枚举拒绝契约，`OrderAdminQueryServiceTest` 覆盖时间逆序拒绝。
- `SupplyChainModuleArchitectureTest`：管理端映射必须有 `@PreAuthorize` 的既有门禁继续覆盖新 Controller；订单查询服务不得依赖账户/账本 DAL。
- `OrderAdminIntegrationTest`：真实 MySQL/Liquibase 下分别构造三类总订单和详情，验证列表不加载详情、详情 tagged union、支付流水顺序与缺详情降级；CARD 路由记录按候选序号回放，O2O 空 rounds。供应商履约尝试只读断言见 `docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#7-测试门禁`。
- 联调步骤和实际测试结果同步写入 `docs/集成测试.md`。

平台订单「路由记录」弹窗的只读 GET 见 `docs/superpowers/specs/2026-09-21-order-routing-records-admin-design.md`，不再属于本文「明确不做」。

## 8. 明确不做

- 供应商原始观察、异常 Case 工作台。供应商订单只读工作台见 `docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md`。
- 主动查单、重试、取消、发货、退款、售后审核等命令。
- 卡密/券码/充值目标等敏感资产读取。
- 实物收货地址、包裹与物流字段。
- 导出、模糊搜索、跨库联表、运营数据范围。
