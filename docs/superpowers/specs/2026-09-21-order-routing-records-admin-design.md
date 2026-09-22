# 平台订单路由记录只读查询

本文落实 PRD 8.11.6「订单路由记录」弹窗的后端契约：运营从平台订单上查看该单进入路由引擎后的冻结决策，不改选供算法，不写路由策略。

冲突时：路径、字段与安全边界以本文为准；总订单列表/类型化详情仍以 `docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md` 为准；引擎排序仍以 `docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md` s12 为准（`candidatePriority → supplierCode → offerId`，原因码 `SELECTED_BY_STABLE_PRIORITY`）。订单基数禁止拆单，见 ADR 0007。金额与路径身份口径沿用 `docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md` a2（CNY 分、业务 code）；前端关 mock 后对齐本文，后端不为 mock 再做适配层。

## 1. 范围

本期做：

- `GET /supply-chain/orders/{orderNo}/routing-records`
- 只读已有 `sc_supplier_routing_round` / `sc_supplier_routing_record`（及选中行上已关联的 `sc_b_order.b_order_no`）
- CARD 按 `order_id` 回放轮次与候选；O2O / INSTORE 不走标准路由，返回空 `rounds`
- 权限复用 `supply-chain:order:query`（含 `supply-chain:admin` 超集）

本期不做：

- 改 `SupplierRoutingEngine`、综合排序、四级配置组、履约分选供
- 供应商原始请求/响应报文、CallLog 全文、卡密、充值目标
- 一键补发、退款、售后；供应商订单工作台见 `docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md`；路由规则页 GET 见 `docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md`，保存见 `docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md`
- 新 Liquibase；不把 O2O/INSTORE 渠道绑定改写成路由轮次

## 2. 领域不变量

1. 路由记录是受理时追加的不可变审计，查询不得重算、不得补写、不得调用供应商或渠道。
2. 标准商品一期通常 `round_sequence=1`：失败降级消费同一轮下一条合格候选，不新开轮次。响应仍按 `round_sequence, id` 升序返回全部轮次，按 `candidate_sequence` 升序返回候选。
3. 总订单不存在 → `SUPPLY_CHAIN_ORDER_NOT_FOUND`。总订单存在但无轮次 → HTTP 业务成功、`rounds: []`，不 404。
4. O2O / INSTORE 即使库中偶然出现轮次也不在本接口解释；本切片对这两类直接返回空 `rounds`，避免把渠道固定商户误读成选供轨迹。
5. 非法 `decision_status` 不得伪造枚举名，按数据损坏失败。
6. DTO `@Schema` 必须与下表 COMMENT 同一表述。

## 3. REST

网关前缀 `/admin-api`。路径遵守 09-02 a12：名词资源。

`GET /supply-chain/orders/{orderNo}/routing-records`

权限：`@PreAuthorize` 与列表/详情相同，`ORDER_QUERY_EXPRESSION`。

响应：

| 字段 | 语义 |
| --- | --- |
| `orderNo` | 供应链总订单稳定业务编号 |
| `orderType` | `CARD/O2O/INSTORE` |
| `skuCode` | CARD 场景平台 SKU 编码快照；其它类型为空 |
| `skuName` | CARD 场景平台 SKU 名称快照；其它类型为空 |
| `buyerCode` | 受理时采购商业务编码快照 |
| `rounds[]` | 路由轮次；无记录时为空数组 |

轮次行对应 `sc_supplier_routing_round`：

| 字段 | COMMENT |
| --- | --- |
| `routingRoundNo` | 供应商路由轮次稳定业务编号 |
| `roundSequence` | 同一路由主体内的轮次序号，从1递增；场景路由固定为1 |
| `routingType` | 路由类型：STANDARD_PRODUCT标准商品供给路由，SCENARIO场景供应商路由 |
| `decisionStatus` | 对外返回枚举名：`SELECTED` / `NO_CANDIDATE` / `CONFLICT`（码 10/20/30） |
| `decisionReasonCode` | 本轮最终决策稳定原因码 |
| `decisionReasonMessage` | 本轮最终决策中文说明快照 |
| `candidateCount` | 本轮候选供应商数量 |
| `computedAt` | 纯路由计算完成时间 |
| `candidates[]` | 本轮候选 |

候选行对应 `sc_supplier_routing_record`：

| 字段 | COMMENT |
| --- | --- |
| `candidateSequence` | 本轮候选排序序号，从1递增 |
| `supplierCode` | 候选供应商编码快照 |
| `supplierName` | 候选供应商名称快照 |
| `configuredPriority` | 已发布规则中配置的候选优先级，数值越小越优先 |
| `availabilityClass` | 动态可用性分类：CONFIRMED已确认，UNKNOWN未知，UNAVAILABLE不可用 |
| `supplierSettlementUnitPrice` | 标准商品候选供应商履约结算单价快照，单位为分 |
| `eligible` | 候选资格：true符合，false被淘汰 |
| `selected` | 本轮选中标记：true选中，false未选中 |
| `decisionReasonCode` | 进入、淘汰或选择的稳定原因码 |
| `decisionReasonMessage` | 进入、淘汰或选择的中文说明快照 |
| `attemptBOrderNo` | 选中后创建的 B 端履约尝试稳定业务编号；未关联时为空 |
| `checkResult.inventory` | 库存检查结果快照 |
| `checkResult.balance` | 余额检查结果快照 |
| `checkResult.health` | 健康度检查结果快照 |
| `checkResult.reasons` | 脱敏淘汰原因列表 |

不返回：`routing_input_digest`、`fact_snapshot_json`、内部主键、Offer/Connector/库存源 id、`health_score`、供应商原始报文。PRD 8.11.6.4「查看请求/响应」本期用 `checkResult` 代替，禁止把 CallLog 或 Adapter 报文当成功响应。

`check_result_json` 无法解析时按数据损坏失败，不把原始 JSON 交给前端。

## 3.1 与前端页面的挂接

对照 `supplychainhubweb`（`src/api/order/index.ts`、`src/views/supplier/order`）：

- 平台订单列表 `source=PLATFORM`、供应商订单列表 `source=SUPPLIER` 已调用 `GET /supply-chain/orders`；详情抽屉调用 `GET /supply-chain/orders/{orderNo}`。路由记录弹窗尚未落地，本接口挂在同一订单资源下：`GET /supply-chain/orders/{orderNo}/routing-records`，前端可与 `getOrder` 并列增加 `getRoutingRecords(orderNo)`。
- 字段用 camelCase、状态用枚举名、金额单位为分，与已落地的订单只读 REST 及 09-02 a2 一致。前端 mock 里 `amount` 为元、`createTime` 为 `YYYY-MM-DD HH:mm:ss`、`bizType` 为 `VIRTUAL_CARD` 等展示码；关 mock 时由前端换算与映射，本接口不返回元、不发明 `VIRTUAL_CARD` / `WAIT_PAY`。
- 列表页的 `supplierOrderNo` 对应总表展示供应商侧单号；本弹窗的尝试单号是 B 端履约尝试 `attemptBOrderNo`，语义不同，不复用 `supplierOrderNo`。
- 列表/详情 DTO 不在本切片改造成 mock 的 `OrderVO`（含 `items[]`、售后、卡密、物流轨迹）。那会推翻 09-12 o2/o5，且 mock 仍开着 `USE_ORDER_MOCK`。

## 4. 分层

`OrderAdminController` 只做路径参数与权限。`OrderAdminQueryService.getRoutingRecords` 在 `supplyChainTransactionManager` 只读事务中：

1. 按 `orderNo` 读总表，不存在则 `SUPPLY_CHAIN_ORDER_NOT_FOUND`。
2. 非 CARD → 空 `rounds`，不查路由表。
3. CARD → `LambdaQueryWrapper` 按 `order_id` 查轮次，再按 `routing_round_id` 查候选；`attempt_b_order_id` 批量换成 `b_order_no`。
4. 查询线程不访问账户/账本 DAL，不调用 `SupplierRoutingEngine`。

Mapper 只增 Lambda 查询，不新增注解 SQL，不新增变更集。

## 5. 日志

入口记录操作人、`orderNo`、`orderType`、`roundCount`。禁止打印 `check_result_json` 全文、摘要二进制或响应对象。

## 6. 测试门禁

- `*Test`：CARD 按序号回放候选与选中标记；O2O/INSTORE 空 rounds；缺订单 404；缺轮次空数组；DTO 不含 digest/报文/内部主键；`attemptBOrderNo` 来自已关联 B 单。
- `*ContractTest`：新映射 `@PreAuthorize` 与 `order:query`；方法安全覆盖该 GET。
- `*IntegrationTest`：真实 MySQL 插入一轮两条候选，CARD 读到同一顺序；O2O 空 rounds。
- `*ArchitectureTest`：既有管理端 `@PreAuthorize` 与「订单查询不依赖账户/账本」继续覆盖。
