# 标准项目直接下单与首次价格冻结

日期：2026-10-03。业务选择：用户已同意项目直接下单，中台按配置价格冻结；本设计细化该已确认选择。工作区沿用当前 dev 分支。

> **2026-10-12 SKU契约候选，待评审：** [平台SKU下单与历史兼容详细设计](2026-10-12-platform-sku-order-contract-design.md) 拟新增v2商品定位字段；本文首冻／消费、永久项目报价键、金额不变量及最终项目选品当前准入全部复用，v1／内部RPC不改。SKU字段正确不构成项目授权，跨版本创建不能绕过永久键。候选未实现；本文当前请求字段仍描述v1，不将文档改动视为报价／订单迁移完成。

## 1. 范围与兼容

项目仍提交 buyerOrderNo、buyerSkuCode、quantity=1、target 和可选 previousReceiptVersion。身份仅来自项目应用认证上下文，不增加价格、用户、支付事实、报价 token、来源系统或租户字段。三个 HTTP 路径、HMAC 原字节签名、返回 DTO、nonce、限频及资金口径不变。

HTTP 首次有效受理尝试自动生成或复用项目订单永久报价，调用方不再需要先调用 StandardPricingQuoteApi。已有内部 RPC 报价入口保留，已有 ISSUED/CONSUMED 报价及历史价格不回写；纯消费入口 requireForProject 不自动计算。项目、订单号仍为永久报价键，现有数据库比较规则不变；不同 SKU/数量不得覆盖首次报价。

本切片不实施 HUA_SHENG 供应商外呼、回调、资产扩展、步骤恢复或真实部署，不修改其它场景订单。卡券协议证据核验可并行进行。

## 2. HTTP 编排与两段事务

沿用 ProjectStandardOrderCommandFacade 的可信身份校验、目标规范化/加密和 claim。claim 不执行新尝试时直接返回原回执，不触发报价或重算。仅 executeAcceptance=true、quantity=1、targetValid 且服务端 Schema 可解析时，调用定价服务的 freezeForProject(ProjectQuoteKeyContext)，输入为服务端 projectId/buyerId/platformSkuId 与项目订单号/数量。

首冻经生产Spring代理以REQUIRES_NEW开启独立供应链短事务，在受理最终事务之前完成；禁止self-invocation，真实代理测试验证它不加入调用者事务；禁止将它放入 receipt→BuyerListing→SKU 已持锁的 accept 事务，或在 Facade 中直接访问 DAL。先按项目主键当前读加锁并核验 ACTIVE/分销商归属，再按永久键当前读报价。新旧 RPC 签发路径均用 selectByProjectOrderForUpdate，避免 RPC 的 BuyerResolver 普通读建立 RR 旧快照后漏看并发报价。项目行锁串行同项目首冻，唯一索引兜底。

报价事务提交后，现有 acceptanceCoordinator 在事务外开启最终 accept：报价消费、订单、路由/B单/操作、频控、资金预占、Outbox 与 ACCEPTED 回执仍同事务提交。接受失败时这些业务写入全部回滚，已成功签发的 ISSUED 报价保留；同号后续尝试必须复用它。进程在首冻后中断时，PROCESSING 回执同参续跑可复用报价。拒绝回执普通重放不再尝试，条件重试仍需原 CAS 规则。新 buyerOrderNo 才可取得新价格。

无效 target、数量或 Schema 不触发首冻，继续由现有受理顺序产生拒绝回执。首冻事务自身失败时，不留下半条报价，不创建订单、消费或资金事实；系统异常原样传播，保留因果，PROCESSING 回执可按现有规则同参续跑。

## 3. 定价身份与当前准入

新增可信入口不调用 BuyerResolver.resolve(sourceSystem, tenant)，不从 appId/projectId 伪造外部租户。项目当前编码和 SKU 编码由供应链自有主键记录解析，首次不存在时调用原 GenericBatchPricingService.ensureCurrentProjectVersion，按现有规则冻结分销单价、用户售价、小计、服务费、应付额、税务和文案。

冻结配置必须 READY，返回的 project listing/SKU 身份必须与可信输入一致。已有报价只读取原金额/版本/token，不调用 ensureCurrentProjectVersion。首冻阶段核验报价所指 sc_project_listing 属于当前项目且 AVAILABLE、其 sc_buyer_product 属于当前分销商/平台 SKU 且 ACTIVE；不把该 ProjectListing 主键和 accept 使用的 sc_buyer_listing 主键比较。相关可变准入记录使用当前读；引用缺失或归属矛盾不得降级或任取其它项目选品。

已成功受理的完全同参重放优先返回原订单，不因后来停用或改价生成新报价。首冻阶段已有报价但当前项目选品停用，用现有 SKU_NOT_SALEABLE 稳定 CommonResult 错误拒绝，报价保持原样，回执保持PROCESSING；项目准入/身份/配置的首冻错误不伪造已保存的拒绝回执。永久报价商品/数量冲突映射 IDEMPOTENCY_CONFLICT。项目不存在、停用或归属不符映射 BUYER_AUTH_INVALID。

当前配置不能形成 READY 报价时使用现有 STANDARD_PRICING_QUOTE_MISSING；此错误仍由 CommonResult 返回，未建单，回执保持 PROCESSING，不伪称已经保存 REJECTED_RETRYABLE。没有冻结报价时，配置补齐后的同号同参续跑才可首冻。GenericBatchPricingService 已知的 SKU/SPU、分销商品或项目选品不可用条件使用专用 IllegalStateException 子类辨识并转换为 SKU_NOT_SALEABLE，保留原因为因果；不得捕获任意 IllegalStateException、JSON、数据库或运行时错误后伪装业务拒绝。旧 RPC 调用仍能按原异常基类处理。

定价记录不存在或尚无 currentVersionId 可走原有继承初始化；若已指向 currentVersionId 但版本缺失，或版本 pricingId 不属于该身份，则为数据完整性系统异常，不得按“未配置”重建、覆盖项目覆盖规则或签发报价。该校验放在共同 ensureCurrentProjectVersion 的引用解析处，也保护原 RPC 首签；分销定价引用遵循同样区分。通过针对悬空和错归属版本的测试验证异常传播及无发布副作用，不扩展为其它配置修复。

最终accept事务也须检查当前准入，不能仅依赖首冻事务的检查。HTTP Facade改调 acceptanceCoordinator.acceptDirectProjectOrder，由该协调入口经真实代理调用 Lifecycle.acceptDirectProjectOrder；原accept入口保留RPC行为，两者共用私有受理实现。HTTP专用路径在 requireForProject 锁定报价后调用catalog服务 requireCurrentProjectAdmission(ProjectQuoteKeyContext, FrozenQuote)，该服务为MANDATORY：按 quote→ProjectListing→BuyerProduct 当前读加锁并核验冻结关联/AVAILABLE/ACTIVE，持锁到订单事务提交。首冻采用相同相对准入锁序；首次不存在报价时也须先按可信project/SKU解析并锁ProjectListing→BuyerProduct准入身份，再调用ensureCurrentProjectVersion取得project pricing锁，避免与既有BuyerProduct→project pricing发布路径形成反向锁序；最终受理不得新增project/pricing锁，原receipt→BuyerListing→SKU前缀保持。已知停用抛专用准入异常，由Lifecycle转换为原 SKU_NOT_SALEABLE/REJECTED_RETRYABLE BusinessRejection，accept回滚后独立reject保存回执，后续需原CAS；冻结引用缺失、归属矛盾与其它系统异常保留因果传播，不泛捕获。这封闭首冻提交与最终受理之间的停用窗口。

首次无报价时，现有Mapper按buyerId/SKU及projectId/buyerProductId普通读只定位不可变身份主键，再按ProjectListing→BuyerProduct加锁重新核验；不以普通读的状态或价格决定准入。此定位发生于REQUIRES_NEW项目/报价当前锁之后，项目行锁与现有ProjectListing创建/调整命令同序；当前管理服务不改已有商品身份的buyerId/SKU或listing的projectId/buyerProductId。已有报价直接按冻结listingId定位，不做普通身份回退。无需新增子查询XML或更换事务隔离级别；若身份关联矛盾则失败关闭。

金额继续为 CNY 分、精确乘加，不让调用方覆盖分销金额；库存、供应商路由、真实商品可售、目标 Schema、限购/频控及账户仍由原 accept 校验，不把存在报价当作全部准入通过。

## 4. 增量数据契约

新增059迁移，只修改 sc_standard_pricing_quote 的来源两列为可空，保留类型、历史值和索引。source_system COMMENT 为“内部RPC调用来源系统编码；项目直接下单首次冻结为空”；external_tenant_id COMMENT 为“内部RPC来源系统外部租户标识；项目直接下单首次冻结为空”。DO 注释同步。可信 HTTP 首签两列均为 NULL，RPC 仍保存真实来源；两列从来不参与项目永久键或报价消费身份。

不修改035/051/054–058，不补造历史来源、不改已发布摘要或订单价格，不新增物理外键。迁移后基线为61变更集/123业务表/295 DATETIME列。Liquibase 实测升级、旧数据保留与重放；其它模块库不访问。

### 4.1 059固定升级与真正重放边界（2026-10-06，测试限定授权）

用户明确批准仅修复既有 `StandardProjectDirectOrderQuoteSourceMigrationIntegrationTest`，固定059边界；不修改生产、历史SQL或全局changelog。测试通过实际Liquibase `getDatabaseChangeLog()` 解析原登记，在测试实例内找到唯一 `059-standard-project-direct-order-quote-source`，确认它位于第61个变更集，再从该实例的可变 `getChangeSets()` 列表移除其后项；保留前61个原ChangeSet对象、原文件路径与真实SQL/checksum，不改变其上下文、标签或执行规则。Liquibase 4.31.1 的update/updateCount向实际命令传递该DatabaseChangeLog，不能用对全局登记的第二次update(1)冒充059重放。

同一固定边界先真实执行前60个，再应用059；之后重新解析相同固定边界执行无数量限制update，必须无新增变更集。断言061/060从未执行、总数仍61，059的MD5SUM与EXECTYPE在重放前后相同；原RPC来源与价格、HTTP双NULL来源、价格快照、永久键索引及两列中文注释全部保留并复核。此测试只证明059升级/幂等；060/061全量迁移另由独立完整profile及全量迁移门禁覆盖，不能机械改为63并宣称重放已证。执行结果尚待真实隔离MySQL验证；不是生产行为RED或验收。

## 5. 验证与影响

TDD 验证可信首签、原报价复用、不调用重定价、项目/SKU/数量冲突、缺选品或停用、READY 与费用快照、异常不吞、普通回放不首冻、无效目标不首冻。真实 MySQL 验证 HTTP 不预签报价仍下单/履约/查单/资产、同参并发唯一报价/订单/资金、旧 RR 快照等待项目锁后复用已提交报价、RPC 与 HTTP 首签竞争、跨项目同号隔离、失败后 ISSUED 保留且无消费/资金残留、改价后的 CAS 原价重试、新号新价、当前项目选品停用拒绝。确定性latch测试在首冻提交/accept前真实retire项目选品与分销商品，恢复HTTP后不得建单/消费/预占/Outbox；另在accept取得准入锁后启动真实retire，必须等待订单提交，证明线性化。

现有 HTTP 契约仍拒绝价格/token/身份覆盖，保持真实生产 Controller、HMAC、Mapper、事务、供应商 HTTP 挡板及执行器，不用测试 Controller 或直接改订单状态制造成功。受影响 API Contract、供应链全量 Test/ArchitectureTest 与相关 Failsafe 均执行，计数按实际执行记录 `docs/集成测试.md`。

变更涉及独立供应链进程的 Facade/定价服务/Mapper 与数据库，不改启动、安全过滤器、Feign 注册、事务管理器、缓存或 Gateway。管理端配置页面无需新增字段；核验实际 supplychainhubweb 项目定价和订单请求实现，不用模拟接口替代后端验证。真实部署和供应商验收不由自动化替代。

## 6. 已有受理身份偏差的后续门禁

只读审计发现，当前HTTP Facade每次先从现SKU取得Schema构造指纹，再claim；既有主设计要求先用已冻结身份准备重放。当前Schema或分销商品→平台SKU映射变更后，同一外部payload可能发生指纹冲突。该既有问题不通过本次价格首冻重构顺带修复；本切片只验证商品价格/项目选品状态变动后的回放。全配置闭环Task5验收前须单独落实既有 `2026-10-02-standard-acceptance-project-key-design.md#2-首次身份与重放优先` 并补真实Schema/身份变化测试，不将本切片通过宣称为所有配置变动重放已覆盖。
