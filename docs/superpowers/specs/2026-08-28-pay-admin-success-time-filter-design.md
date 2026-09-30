# 支付后台成功时间筛选详细设计

## 1. 目标与范围

管理后台支付订单分页接口 `GET /pay/order/page` 增加支付成功时间范围筛选，管理后台退款订单分页接口
`GET /pay/refund/page` 增加退款成功时间范围筛选。本次只扩展分页查询，不改变详情、导出、支付或退款状态流转。

## 2. 接口契约

两个接口都新增可选查询参数 `successTime`，使用 Spring MVC 数组参数传递起止时间：

- 参数类型：`LocalDateTime[]`；
- 时间格式：`yyyy-MM-dd HH:mm:ss`；
- 第一个元素为起始时间，第二个元素为结束时间；
- 起止边界均包含在查询范围内；
- 不传或传空值时不追加成功时间条件，保持原查询结果兼容。

字段在两个接口中采用相同技术名称，是因为 `pay_order.success_time` 与 `pay_refund.success_time` 及其数据对象均使用
`successTime`；OpenAPI 描述分别展示为“订单支付成功时间”和“退款成功时间”，避免混淆业务语义。

## 3. 查询设计

`PayOrderMapper.selectPage` 使用 `LambdaQueryWrapperX.betweenIfPresent` 将 `successTime` 绑定到
`PayOrderDO::getSuccessTime`；`PayRefundMapper.selectPage` 以相同方式绑定到 `PayRefundDO::getSuccessTime`。
新条件与应用、渠道、业务单号、状态、创建时间等原有条件按 AND 组合，分页排序仍按主键倒序。

## 4. 验证要求

- 支付分页：命中时间范围内的支付成功订单，排除成功时间在范围外的订单；
- 退款分页：命中时间范围内的退款成功订单，排除退款成功时间在范围外的订单；
- 同时传入 `createTime` 与 `successTime` 时，两项必须同时满足；
- 不传 `successTime` 时，接口行为与变更前一致。

人工联调步骤见 `docs/集成测试.md#7-支付后台分页成功时间筛选联调`。
