# 项目标准订单查询与资产交付实施设计

日期：2026-10-02。状态：承接已确认契约的实现细化，尚未实施/验收。

上位设计：`2026-09-30-project-signed-standard-order-http-design.md` §2、§5、§7、§9。本文不改变报价、下单幂等或限频规则，也不增加用户身份字段。实际环境迁移、菜单部署、真实页面和供应商联调按`docs/待办列表.md`暂缓；真实MySQL自动化门禁继续执行。

## 1. 分层与可信输入

外部HTTP Controller在后续接入阶段获取`requireStandardProductContext(expectedOperationCode)`，将可信项目快照和经过严格解析的`buyerOrderNo`交给`ProjectStandardOrderQueryFacade`。Facade位于`application.facade`，仅校验并委派，不依赖Mapper/DO。业务服务位于`border.fulfillment.application`，沿供应链独立数据源读取sc_*数据。

新查询流程不调用原`BuyerFulfillmentQueryService`的租户/用户/scope入口，不拼装旧RPC DTO，不通过`BuyerResolver`覆盖认证得到的项目/分销商。认证上下文没有用户身份；数据库旧字段暂存期间也不读取它作为授权条件。旧内部RPC在后续标准身份移除阶段统一调整，INSTORE/O2O不受影响。

对外响应DTO为封闭字段对象；响应只包含上位设计§5登记的字段。资产DTO及包含资产的响应禁止由自动生成的toString输出明文，日志只记录项目、订单业务号、结果类别和资产数量。项目摘要与资产查询分别使用对应操作，不允许互借上下文。

## 2. 一致快照与归属校验

使用独立Spring代理的查询快照服务，`supplyChainTransactionManager`、只读、`REPEATABLE_READ`；多表读取在同一事务中完成，禁止在Facade自行开启第二个无关联读视图。查询不锁住履约推进，不同步查供应商，不安排Timer或重试。

入口按`project_id=认证项目`和数据库等价比较的`buyer_order_no`查询回执及CARD总订单，另外核对其`buyer_id=认证分销商`。不按buyer+业务号兜底，不回退到NULL项目，不将INSTORE/O2O订单作为标准订单。零匹配统一`SUPPLY_CHAIN_ORDER_NOT_FOUND`；匹配多条或本项目数据持久化引用冲突属于内部一致性故障。跨项目数据只产生与不存在相同的外部错误。

校验已建单引用链：

1. 回执项目/分销商/订单号与可信身份一致；回执结果orderId、orderNo、fulfillmentRequestNo与实际总订单/请求一致。
2. 总订单为CARD，projectId/buyerId/buyerOrderNo一致，acceptanceReceiptId指回该回执，detailType是既有标准履约详情类型，detailId指向本订单履约请求。
3. 请求orderId/buyerId及requestNo与上述引用一致，platformSkuId与总订单非空商品身份相同，requestedQuantity与总单/回执quantity一致，冻结目标Schema/targetDigest与回执一致。缺失请求、单边引用、非法状态、无法识别的已建单冻结目标Schema均失败关闭，不能拼接另一项目订单或另一商品快照。
4. 未建单PROCESSING或已拒绝回执的三个结果引用必须为空；ACCEPTED回执缺订单不是等待状态。孤立总订单或孤立引用不合成虚构回执，返回一致性故障，待修复数据后开放查询。

数量和状态读取真实投影：有请求时使用requestedQuantity/fulfilledQuantity；尚未建单使用回执quantity、deliveredQuantity=0。未建单摘要不依赖尚未解析的目标Schema，PROCESSING/拒绝回执仍正常返回，assetAvailable=false；此时资产接口必须另行确认类别，未知类别按§4失败关闭。未建单orderNo/orderStatus/fulfillmentStatus/completedAt为null；requestId始终为实际receiptNo。updatedAt使用同一读视图的实际最后更新时间。时间通过`SupplyChainDatabaseClock`转换为带偏移ISO8601，不把数据库上海时间当UTC。

## 3. 摘要资产标志

商品交付类别取订单履约请求冻结的targetSchemaCode；未建单只取回执已解析的Schema，不重新读取可变商品配置。

只有`VOUCHER_DELIVERY_TARGET`属于本期可查询卡券。`DIRECT_RECHARGE_TARGET`及账户绑定权益不产生本接口卡券交付，assetAvailable始终false；不能依据fulfilledQuantity判断。

卡券assetAvailable通过本地资产元数据判断，不解密。资产必须属于该请求和分销商，其BOrder属于同一请求/总订单，supplierId一致，产生资产的operation属于该BOrder。允许生命周期仅AVAILABLE/DELIVERED，排除VOIDED/UNBOUND/EXPIRED/QUARANTINED及未知值；有有效期时必须已生效且未到期。非交付生命周期资产不解密；合法隔离资产不因存在而泄漏内容。元数据引用矛盾返回一致性故障。

## 4. 资产结果与封闭载荷

资产接口只接受buyerOrderNo，不接受用户、scope或fulfillmentRequestNo。先做§2的完整引用校验，再按冻结Schema判断是否支持查询，最后判断回执/订单状态；所有路径均产生应用级查询审计。

- 明确为直充/非卡券：`ASSET_QUERY_UNSUPPORTED_PRODUCT_TYPE`，文案“此类商品不能查询资产，请通过查单接口获取订单信息”。
- 未建单且回执REJECTED_RETRYABLE/REJECTED_FINAL：订单未受理业务错误；不返回等待提示。
- 无法确认未建单回执商品类别：一致性故障，不假定为卡券。
- 总订单FAILED/CANCELLED：明确终态业务错误，提示通过查单查看结果；禁止先解密残留资产。
- 有可交付资产：成功返回全部授权资产，hint=null。
- 无资产且MANUAL：成功空数组，hint“订单正在人工处理中，请通过查单接口查看进度”。
- 未建单PROCESSING或未完成卡券：成功空数组，hint“请等待，稍后重新获卡”。处理中订单可使用“订单延迟，请等待重试”，不增加未确认的延迟时长阈值。
- 卡券SUCCEEDED却无应交付资产：一致性故障，不能用成功空数组掩盖。

仅对交付资产执行EnvelopeCrypto解密，保持既有AAD `bOrderId|sequenceNo|assetType`和密钥版本。解密载荷必须为JSON对象，字段类型为字符串，不能以JsonNode.asText把对象或数组静默转为卡券值。资产类型封闭为VOUCHER_CODE、CARD_CODE_SECRET、VOUCHER_URL，载荷中assetType若存在必须与持久化类型一致。未知类型/畸形JSON/缺必要字段均失败关闭。

现有生产端`SupplierOrderLifecycle.persistAssets`对券码/链接也使用cardCode保存`OperationAssetFact.cardNo`，读取时兼容这一既有密文格式：CARD_CODE_SECRET映射cardCode/cardSecret，至少有一个非空交付值；VOUCHER_CODE映射非空voucherCode（旧cardCode作为载荷兼容）；VOUCHER_URL映射非空voucherUrl（旧cardCode兼容）。同一载荷同时给出不同值的两个同义字段时拒绝，不能任取。对外只输出与类型匹配的字段，不输出供应商外部资产标识、密文、内部id、原始JSON或其他字段。响应字段名称与现有资产契约保持一致。

解密字节在finally清零；错误保留原始cause用于因果追踪，但不得日志输出原始载荷/异常message。任何一项解密/解析失败，本次不返回部分明文列表。资产查询为只读，不因“读到资产”修改生命周期、订单或资金。

## 5. 审计事务与数据库增量

新增Liquibase `050-project-standard-asset-audit.sql`，追加至主变更集，不修改已执行010。扩展`sc_sensitive_access_audit`：

```sql
ALTER TABLE sc_sensitive_access_audit
  ADD COLUMN project_id BIGINT NULL COMMENT '相关采购项目内部主键，无项目上下文时为空',
  ADD COLUMN order_no VARCHAR(32) NULL COMMENT '相关中台订单稳定业务编号，尚未建单时为空',
  ADD COLUMN app_key_version INT NULL COMMENT '项目应用认证凭证版本，非项目应用访问时为空',
  ADD KEY idx_sc_sensitive_access_project_time (project_id, accessed_at);
```

主体验证CHECK增加`PROJECT_APPLICATION`，principal_type COMMENT同步增加“PROJECT_APPLICATION项目应用”。scope_code COMMENT调整为“授权访问所使用的权限Scope或操作编码”，保留原Scope记录；标准接口记录真实`STANDARD_ORDER_ASSET_QUERY`操作。所有新增/变更DO注释逐字与DDL业务语义一致；无物理外键。旧审计行和既有BUYER_SERVICE/ADMIN_USER/SYSTEM_WORKER调用保留，不伪造补值。

项目审计principalId使用认证appId，projectId/buyerId/appKeyVersion来自可信快照；buyerUserDigest为NULL，不用空字符串HMAC占位。记录真实orderNo；未建单为空。accessType使用ASSET_QUERY查询事件、ASSET_DECRYPT实际解密事件；resourceType使用FULFILLMENT_REQUEST/ASSET的准确类型。查不到或归属未确认时resourceId=0，不能记录另一个项目内部资源id。traceId为服务端生成的稳定业务追踪编号，不复制任意请求头或超长buyerOrderNo。

封闭审计原因枚举覆盖允许、等待、人工等待、类型不支持、未受理、终态拒绝、不存在、引用不一致、解密失败、载荷失败。SUCCESS=10、REJECTED=20、FAILED=30使用现有枚举。一次查询至少写一条ASSET_QUERY；每次实际解密再写对应资产事件，非交付资产不产生虚假解密事件。

外层编排收集查询结果/错误和审计事件，独立审计服务通过Spring代理`REQUIRES_NEW`追加整批记录，随后才返回明文或重抛业务异常。失败解密仍记录失败，之前已解密的资产如实记录，不将整个查询标为成功。调用方事务随后回滚也不能撤销审计。audit失败时不返回明文，抛稳定安全异常并保留audit原因；原业务失败同时存在时保留为suppressed，不丢因果关系。数据库完全不可用时无法承诺写下“审计失败”的记录，必须失败关闭，禁止假造已持久化审计。

HMAC未认证请求由现有认证链处理，不构造虚假应用主体的资产审计。no-store及全链日志保护在后续HTTP接入阶段落实，未完成这些保护前本阶段服务不能作为外部资产接口开放。

## 6. 自动化门禁与完成边界

先用可编译行为断言证明旧查询无法按项目隔离、正确交付待处理空数组及独立拒绝审计，再实现新服务。纯逻辑测试覆盖所有状态/交付类型/未知状态、资产字段闭包、过期与隔离不解密、关联矛盾、DTO安全toString、各种失败无部分明文。

真实MySQL/Liquibase/Mapper/事务测试必须覆盖：

- 同分销商不同项目同号隔离，NULL projectId不回退，buyer错配/跨请求/跨订单/跨供应商/operation引用失败。
- RR读视图在并发履约提交前后不会把旧回执和新详情拼接；查询不占用供应商执行锁、不触资金/Timer。
- 成功资产、待交付、MANUAL、拒绝、解密和JSON失败均有持久化审计；REQUIRES_NEW在外层回滚后仍存在；audit insert故障没有明文返回。
- 全量主变更集空库启动和二次启动，旧审计不丢失，新列/COMMENT/CHECK/索引符合契约；旧010及此前SQL不变。
- 独立执行API响应ContractTest及`SupplyChainModuleArchitectureTest`，不得以Mock Mapper代替事务/归属门禁。

阶段顺序说明：Task2时旧buyer+buyerOrderNo唯一键仍在，同一buyer两项目同时保存同号正向实体在052之后验证。本阶段真实验证本项目已有同号数据对同buyer另一项目统一不存在，以及不同buyer不同project各有同号不会串；不在测试中删旧唯一键。052完成后追加同buyer两项目各有同号的正向查询/下单事务门禁，才能宣称这一完整场景通过。

实施/验证记录写入`docs/集成测试.md`。本阶段完成仅代表查单/资产应用核心和审计DDL已实现，不宣称三个HTTP接口或真实供应商联调通过。后续标准身份移除迁移会删除标准专用user列，查询代码从开始即不依赖它。
