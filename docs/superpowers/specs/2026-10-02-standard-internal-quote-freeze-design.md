# 标准商品内部首次报价永久冻结适配

日期：2026-10-02。状态：承接用户已确认方案的实现细化，尚未实施。

依据`2026-09-30-project-signed-standard-order-http-design.md` §4、§7、§8：继续使用现有`StandardPricingQuoteApi`内部入口；永久键为项目＋项目订单号，首次成功报价冻结价格，本期无报价有效期/paidAt校验，下单HTTP不提交token。本文取代旧`2026-09-15-generic-batch-pricing-design.md` §7中标准商品用户手机号绑定及15分钟重签语义，不改变其通用定价计算、费用/税务快照和资金职责。

## 1. 内部契约与归属

内部报价请求保留sourceSystem、externalTenantId、projectCode、platformSkuCode、buyerOrderNo、quantity；删除buyerUserId，不增加同义用户/手机号字段。quantity本期为1。该入口仍是已有内部RPC信任入口，不新增对外报价HTTP、不向项目签名请求开放sourceSystem/tenant身份覆盖。

内部入口先用真实BuyerResolver解析来源分销商，再按projectCode锁供应链自有项目并验证属于该分销商且ACTIVE。SOURCE/TENANT只记录调用来源，报价永久键不包含它们；不能用同一来源下另一个项目的同号报价，也不能因内部映射后续变化重新签发同项目报价。

报价响应保留既有价格、税务、币种、项目SKU版本、随机quoteToken等字段。token仅为内部追踪标识，不作为HTTP请求字段。expiresAt保留为可空兼容字段，新报价返回null，历史值不参与下单或重签判断；契约注释明确“历史报价失效时间，永久冻结报价为空且不作为本流程下单条件”。不返回用户身份。

## 2. 首次签发与重放

在供应链事务中，项目行锁串行首次签发，按`project_id,buyer_order_no`读取唯一报价；DB唯一约束提供最后一道保护。匹配判断核对buyerId/projectId、quantity和冻结的platformSkuCode。不同商品或数量明确幂等冲突，不能覆盖旧快照。

已有ISSUED或CONSUMED报价且不变量匹配时直接返回原报价的全部冻结价格/版本/文案；不调用ensureCurrentProjectVersion，不比较当前时间与旧expiresAt，不换token。不把“已消费后查询首次报价”误判为再次签发，但不允许再次消费给另一订单。

首次不存在时沿现有GenericBatchPricingService形成READY项目商品价格，校验项目Listing和平台SKU归属。freeze完整listingId/platformSkuId/Code/projectPricingVersionId、分销单价、销售单价、小计、服务费、应付额、税务JSON和文案；状态ISSUED，expiresAt=null。金额仍以CNY分、Math精确计算；不向外部请求获取用户实收金额。

以后价格或Listing版本改变不影响原报价重放；新订单号才可获得新价格。HTTP实际首次受理另校验商品仍可售和Schema兼容，不能因为报价存在而放宽商品准入。

## 3. 受理内查取与唯一消费

增加可信项目消费入口，消费上下文只含buyerId/projectId/platformSkuId/buyerOrderNo/quantity。通过项目永久键`SELECT ... FOR UPDATE`取得已冻结报价；缺报价使用稳定业务错误STANDARD_PRICING_QUOTE_MISSING，重试指令RETRY_SAME_KEY_AFTER_CONDITION_CHANGE；共享受理将其保存为可条件重试的拒绝回执，内部首次报价形成后由调用方携带回执版本CAS重试。不计算新价格、不任取最新历史报价。验证分销商/项目/SKU/数量与报价一致，并返回不可变FrozenQuote及listingId等必要商品身份快照供共享受理校验。

共用受理最终统一使用这一入口。过渡阶段旧内部受理的token入口可适配到同一个项目键并额外核对token指向该唯一报价；该过渡不保留user/paidAt作为报价绑定或有效期条件，完整标准DTO/PreparedAcceptance身份清理由下一阶段完成。过渡入口不是新增外部协议。

require与markConsumed必须处于供应链业务事务，生产代理强制MANDATORY或等价显式事务断言；行锁保护状态。markConsumed重新锁定报价并校验ISSUED/CONSUMED与consumedOrderId完整性：

- ISSUED且consumedOrderId为空：写CONSUMED、真实中台orderId/消费时间。
- CONSUMED且同orderId：幂等返回，不改首次时间。
- 其他orderId或状态/引用矛盾：拒绝，不能转移消费。

消费与总订单、资金预占、路由/Outbox/频率队列在共享受理同一事务中；任何失败回滚仍为未消费。不能单独提交消费来提前占用报价。资金使用FrozenQuote金额快照，重复下单/条件重试不重新计算。

## 4. 增量DDL与历史阻断检查

在资产审计050之后新增`051-standard-quote-permanent-key`变更集，不修改035及其原COMMENT。真实部署仍属于低优先级待办；本节代码和自动化迁移验证当前实施。

DDL前Liquibase HALT预条件检查：

1. 项目永久键不存在两条以上报价（按真实DB排序规则分组）。旧15分钟重签数据可能多条，不能默认取最新/删除旧报价/迁移为另一订单号。
2. 报价projectId/buyerId/listingId/platformSkuId与实际项目商品关系可确认；消费状态与consumedOrderId一致。
3. 已消费报价对应CARD订单的pricingQuoteId、项目/分销商/订单号/商品/数量与资金价格快照一致；不假定未建单ISSUED已经消费。

任何冲突HALT，输出安全计数/内部资源编号供离线核实，保留原数据；不自动修复金额或历史归属，不先删列后才报告冲突。历史多报价须依据实际订单、账本和业务事实单独制定迁移方案再执行，当前不伪造统一历史价格。空库及合法单报价历史可直接升级。

SQL增量：

```sql
ALTER TABLE sc_standard_pricing_quote
  DROP COLUMN buyer_user_id,
  MODIFY COLUMN expires_at DATETIME(3) NULL COMMENT '历史报价失效时间，永久冻结报价为空且不作为本流程下单条件',
  ADD UNIQUE KEY uk_sc_standard_quote_project_order (project_id, buyer_order_no),
  COMMENT = '标准商品项目订单首次永久冻结报价';
```

可删除已无用途的到期索引，但不得误改其他业务索引；源系统订单索引不作为永久读取入口。新DO/DTO注释与DDL业务表述一致。历史expiresAt保留仅为历史时间，不把它转成新有效期。标准专用用户列删除且DO/报价代码/响应不含该身份；全流程残余身份的迁移与指纹/资产清理由标准受理身份移除设计完成，不宣称只改报价即已彻底清除。

## 5. 自动化与发布协调

RED/GREEN覆盖：首次报价无用户、超过旧15分钟/已消费仍返回同token/同价格、当前价格变化不调用重签、跨项目同号独立、同键商品/数量冲突、缺报价不重新定价、无paidAt校验、消费回滚/同order幂等/不同order并发拒绝、无事务调用拒绝。

真实MySQL/Liquibase覆盖：项目唯一键并发签发只一条、SQL等价大小写同号不重复、同buyer不同project同号可各签发、消费锁并发，035合法历史迁移后用户列不存在、旧过期报价可用、非法多报价/错消费引用预条件HALT且旧数据/列未被删除、全量主changelog二次幂等。

独立API Contract验证删除用户请求字段与expiresAt可空语义、供应链架构门禁及现有定价单测回归。测试使用真实Mapper/事务验证，不把mock调用次数当数据库并发证据。执行结果维护`docs/集成测试.md`。

内部调用方需同步删除buyerUserId并接受永久冻结、expiresAt=null和同号报价复用；仓库内实际调用点全部调整，外部调用方发布协调记录到待办。标准商品写入升级窗口须停旧节点，不能旧代码向已删除列继续写入；不修改INSTORE/O2O用户身份。实际环境发布协调和迁移没有完成前不宣称上线。
