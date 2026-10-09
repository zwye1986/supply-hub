# 页面选品与订单受理目录衔接详细设计

> **2026-10-09 本期 B/C 替代设计（用户已确认方向，详细契约待书面复核）：** 首个业务交付合并 B/C，采用保存事务提交后单 LST 幂等刷新＋后台周期扫描自动恢复＋下单首冻前准备＋最终当前项目资格。规范性入口为 [真实选品可售与签名下单受理](2026-10-09-saleable-catalog-order-acceptance-design.md)。下方 §5～§7、§9 的通用执行器、账本 flush、ROOT/PAGE/LEAF、持久页轮次／心跳及 §10 的管理重算接口、§13 的对应机制门禁、§14 的旧 B/C 技术拆分退出本期，不执行、不阻断业务。前端只改保存／发布结果提示，不新增编码／状态列、重算操作或项目编码展示。A 已有成果及真实价格、MANUAL、暂停／退役、项目首选 UNKNOWN 拒绝、冻结回放语义保留。后台自动恢复已纳入，不能仅靠用户操作／下单补偿。当前仅文档修订，无新增实现／验收结论。后续页首说明与正文冲突时以本条及新设计为准。

> **最新业务优先修订（2026-10-09）：** 用户明确本阶段B0剩余全部为过度设计，不再作为B/C设计、编码或商业链路前置。§5～§7/§9依赖通用B0事务型执行器、账本flush、ROOT/PAGE/LEAF、页桶/心跳容量整体验收的方案暂停适用，后续实施必须先定案最小业务替代，不能原样调用尚未开放的BCS通道。推荐原选品/定价事务提交后由事务外业务编排做单LST幂等刷新，并与首冻前按需准备及最终当前资格核对组合；管理返回/补偿契约尚属候选，不能承诺全部后台投影实时收敛。已确认BPD身份、READY真实价格、MANUAL兼容、人工暂停/退役、当前项目首选资格、冻结回放等语义保持。最新 `feat/remove-druid-hikari / adac935` 已修复旧快照预算缺口，不新增加固待办；五阶段含B0首阶段的旧路线被业务优先路线替代。详细适用边界见 [合入与业务优先修订](2026-10-09-hikari-merge-business-first-design.md)，未合并、未实施候选。

> **2026-10-09 新项目事实与路线纠偏（优先于下方旧检查点）：** 用户已确认不存在旧 Timer 存量，R3-04 旧迁移及其专属启动全量核验/双向扫描/有界读取取消且不适用，不再阻断 B0/B1/C。取消迁移不取消新任务/事件的事务一致性、运行时 JSON 资源限制、租约恢复或新单当前准入。采用 Hikari 依赖路线，复用 Task A、R3-03、华昇 A1～A5 最新有限成果；正常启动仍装配的旧迁移入口需要独立清理，不重启旧复杂候选。五阶段顺序和通知/售后后置保持，前加基线与无效迁移退出检查点。路线候选见 `docs/superpowers/plans/2026-10-09-greenfield-business-closure-roadmap.md`；本次不放行生产实现、分支整合或启用。

> **2026-10-09 主链范围修订（用户明确确认）：** 本轮全流程贯通按“目录可靠性前置 → 自动商业发布与可用性 → 项目当前准入和冻结回放 → 华昇生产履约与资产交付 → 从零配置全链验收”推进。对买家/项目的主动通知、售后审核与退款/补发运营流程放到最后单独实施和验收，**不作为本轮主链完成条件**。项目主动查单/资产查询、资金预留/提交/释放、未知结果保留、幂等、跨项目隔离和异常恢复仍是主链必需项。内部目录刷新 Outbox/Inbox/Timer 不属于排除的主动通知；未实现传输不得伪标成功的基础设施安全约束也不因此删除。
>
> 该指示确定推进路径，不自动放宽既有 R3-04 暂停、保护文件、池参数、共享日志及生产启用边界。已验收的 Task A、R3-03 和华昇 A1～A5 通信组件复用，不重做、不扩大其完成声明。当前首个检查点仍是 R3-04 完整设计解阻；新增对象级池诊断来源绑定研究需另行明确范围。执行状态及门禁见 `docs/superpowers/plans/2026-10-07-business-closure-next-stage.md#7-五阶段主链推进与首个检查点2026-10-09`，本次仅修订范围，不表示编码或验收已完成。

日期：2026-10-04。状态：A在身份/双编码及既定事务、NULL CAS、RR和管理安全范围内已最终验收（39候选、2139项运行门禁全绿，独立实现规格与完整质量复审均通过）；根核最终审查集合与受测哈希，4项质量首审阻断闭合，无剩余阻断。B进入可靠消费前置技术规格修订，原预审仍未通过，须修订可靠消费、生产者及Reader契约再复审，C仍依赖B。自动发布和手工目录兼容范围已经用户确认，旧 RPC 的移除联动见 §12 待确认项。本文先于实现，A的冻结候选验收不代表已经提交、部署或全配置闭环。

关联设计：

- `2026-09-02-supply-chain-admin-frontend-api-design.md` §6.3、§7.2：分销和项目选品。
- `2026-09-15-generic-batch-pricing-design.md` §1、§5：通用定价身份、继承与可售状态。
- `2026-10-02-standard-acceptance-project-key-design.md` §2、§3：永久项目键、冻结身份与回放。
- `2026-10-03-standard-project-direct-order-price-freeze-design.md` §2、§3、§7：直接下单首冻、当前准入及已知回放缺口。
- `2026-08-07-supply-chain-phase1-detailed-design.md` §5.10、§5.11、Outbox 与 Inbox、持久化定时器。
- `2026-09-24-standard-order-routing-execution-design.md` §3、§4：真实路由和每日配额。
- `2026-10-02-huasheng-fulfillment-adapter-design.md` §12.6：正式资料及用户已确认的金额、确认、取券契约；目录桥接不改写这些履约约定。

## 1. 已确认范围与不变量

1. 新项目订单的 `buyerSkuCode` 使用页面分销商品稳定编号 `BPD_…`，不增加自定义外部编号字段。
2. 新页面选品建立的目录，在真实有效价格和真实供给检查通过后自动发布受理目录。选品成功仅表示关联事务提交；如果条件尚未满足，目录等待发布，不能提示已经可受理。
3. 已有手工 `BuyerListing` 保留原编号、商业版本和人工生命周期管理方式。不能因为关联了 BPD 就接管、改价、自动恢复暂停或重写历史订单。
4. 保留现有 RPC 外部编码及历史别名的解析能力。BPD 的选择不等于删除旧映射，也不等于把同号不同编码当作同参请求。
5. BPD、PLT、LST 是独立身份。报价的 `listing_id` 引用 `sc_project_listing.id`；履约请求的 `listing_id` 引用 `sc_buyer_listing.id`。严禁把两个主键比较为相同身份或替换写入旧列。
6. 订单金额只有通用项目定价和永久首次报价这条来源。受理目录商业价是有来源的分销价快照，不另设价格公式、不接受客户端报价、不填假价格。
7. 供给投影可滞后；新HTTP单最终必须读取新的真实供给快照，不能用一个滞后的 `AVAILABLE` 授权。HTTP复用MANUAL目录时保留其商业价/生命周期，以本次真实供给资格替代旧UNKNOWN/AVAILABLE投影前置门禁；旧RPC仍保留原投影行为。
8. LST 的 `RETIRED` 是终态；移除 BPD/PLT 是可复活的关联状态。二者不能互相代用。成功回放保证以通过现有买家/项目ACTIVE及身份认证前置门禁为边界，不改写停用主体的认证策略。
9. 下一阶段实际验收为 HUA_SHENG 固定价卡密一单；QNM 测试只能验证共用机制，不能替代该验收。不通过直接 SQL 补 LST、商业价、AVAILABLE 或目录关系绕过管理端链路。

## 2. 代码核对与最小方案

本文代码证据路径基准为 `aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/`；Java 包基准为 `java/com/aifuli/module/supplychain/`。行号为本文起草时的实现位置，后续以类/方法名定位。

- `tradecore/catalog/application/BuyerProductCommandService.java:78–96、201–247`：选品事务生成 BPD，复活沿用原身份，随后可以保存行内通用价格。
- `tradecore/catalog/application/ProjectListingCommandService.java:190–233`：PLT 引用 BPD，创建和复活走同一选品事务。
- `resources/db/changelog/releases/1.0.0/002-product-and-offer.sql:71–73`：LST 号、买家/SKU、买家/编码均唯一。不能为已有手工 LST 的同买家/SKU 再创建 BPD LST。
- `tradecore/catalog/application/ProductCatalogCommandService.java:275–301`：旧创建入口只建 DRAFT LST 和 UNKNOWN 供给投影。
- `border/fulfillment/application/FulfillmentAcceptanceLifecycle.java:149、187、522、541–553、723–733`：当前按旧编码直接查 LST；要求已发布商业版本和 AVAILABLE；首次 claim 无版本会抛系统异常。
- `tradecore/catalog/pricing/GenericBatchPricingService.java:348–406、438–535`：通用分销/项目版本同步初始化和重建，不创建 LST。
- `tradecore/catalog/dal/read/BuyerCatalogReadMapper.java:38`、`tradecore/catalog/application/BuyerCatalogQueryService.java:103`：旧目录 RPC 暴露 LST 商业价，不能以“订单不用该列”为理由填假值。

备选方案：

1. **唯一 LST 加双编码解析，推荐。** 复用两边的买家/SKU 唯一约束，新自动 LST 使用 BPD；已有手工 LST 保留旧编码，BPD 经分销商品身份解析到同一 LST。只增加自动管理及快照来源字段，不新增通用别名体系。
2. **明确关联表和通用编码别名表。** 可以支持多别名生命周期、变更审计，但需要额外 API 和数据治理；本期没有多个新外部编码的需求，不采用。
3. **收敛三个目录模型。** 涉及目录 RPC、订单历史引用和版本所有权迁移，不能作为 HUA_SHENG 一单前的最小切片，不采用。

## 3. 身份解析与同事务唯一创建

新增 catalog 应用服务 `BuyerAcceptanceCatalogResolver`，返回不可变 `CatalogIdentity`：买家 ID、原始请求编码、BPD ID/编码（存在时）、LST ID/号、平台 SKU ID、管理来源。不返回可供长期授权的状态缓存。

### 3.1 双查与碰撞

`resolve(buyerId, requestedCode)` 同时定位：

1. 按 BPD 编码查询 BuyerProduct，并核验 buyerId；不因 REMOVED 而丢失身份。
2. 按买家/请求编码查询精确旧 LST。
3. 对命中的 BPD，以买家/平台 SKU 定位唯一 LST。

两条路径都命中且指向同 LST/SKU时允许；指向不同 LST 或 SKU 时抛专用 `BUYER_CATALOG_CODE_CONFLICT`，不优先任选一条、不捕获数据库异常后降级。查到属于其他买家的 BPD 不能解释为当前买家商品。不存在关系返回明确 unavailable 身份结果，后续仍可保存真实拒绝回执。

`requestedCode` 原样进入回执及格式 2 指纹，不改成 BPD。历史外部编码与 BPD 即使命中同 SKU，同项目同订单号改编码仍是幂等冲突。

**A切片即保留BPD_命名空间，防跨表并发占用。** 不能仅在旧create中“先查当前BPD是否已存在”：当选品复用MANUAL LST(Y)且该行旧alias并非BPD时，没有跨表唯一键阻止另一create把同BPD编码绑定到SKU X。`ProductCatalogCommandService.createBuyerListing`的新兼容请求一律拒绝BPD_保留前缀，无论当前BPD是否已经存在。必须在服务入口执行，不能只放Controller或前端。

现有兼容DTO只@NotBlank/@Size (`M1MasterDataAdminController.java:280–286`)，create直接保存原字符串 (`ProductCatalogCommandService.java:284`)，旧表使用utf8mb4_0900_ai_ci (`002-product-and-offer.sql:77`)。A对**新增兼容编码**先使用明确Java trim去首尾空白，再校验/保存同一归一后的值；存量原码完全不改，不能新增ASCII-only外部编码限制。BPD命名空间判断必须按该真实DB排序规则处理大小写及等价字符，拒绝`BPD_`、`bpd_`等以及数据库将其前缀视为BPD_的等价形式。不能用仅Java startsWith或仅toUpperCase推定DB唯一语义。

最小实现可在XML Mapper用绑定参数标量比较；比较两侧必须先`CONVERT(#{normalizedCode} USING utf8mb4)`、`CONVERT(#{reservedPrefix} USING utf8mb4)`再应用`COLLATE utf8mb4_0900_ai_ci`，不能给连接字符集不明的绑定值直接COLLATE。候选前缀按字符长度截取，实际DB的等价前缀长度变化（例如组合字符）亦应被测试覆盖，不能只验证ASCII四字符后宣称覆盖所有等价形式；实现可逐字符前缀按同一DB排序规则比较，编码最大64字符使检查有界。前缀参数来自统一BPD常量，无SQL拼接。这是数据库排序规则专属技术判断，使用XML理由明确，不新增注解SQL。

存量含该前缀的旧编码不删除、不改写；Resolver继续双查并报告已有冲突。兼容创建的新规则不能追溯拒绝旧RPC请求。此方案不增加通用alias表或全buyer栅栏锁。

替换 Lifecycle 的 Schema 查询、claim 门禁定位、锁定 LST 三处直接 Mapper 查询；HTTP/RPC 都委派 Resolver。Facade 不接触 Mapper/DO。最终受理只按已定位 LST ID当前读加锁，并再次校验买家/SKU/自动关联；严禁锁前状态决定准入。

**最终受理不得因身份定位提前建立RR一致性视图。** 身份定位在最终accept事务之外完成，可信不可变`CatalogIdentity`经应用快照传入受理命令/协调器，不向Facade暴露Mapper或DO，也不采信客户端提供的LST ID。最终事务先按receipt→LST ID→SKU当前读锁定；LST锁后核验行内买家/SKU/管理来源等不可变字段，涉及BPD等普通查询的关联核验放到SKU锁取得之后。禁止在最终accept中先调用普通读`resolve()`再锁LST/SKU；否则等待SKU期间前序订单提交后，限购/每日配额仍可能读到旧视图。所有HTTP/RPC及限频协调器调用路径必须传播同一应用身份快照；不存在身份仍按真实稳定拒绝处理，不补造身份。

### 3.2 创建协议

在 `BuyerProductCommandService.addOrRevive` 的原供应链事务内、插入 BPD 后确保受理身份：

- 若买家/SKU没有 LST，生成新的 `LST_…` 业务号，buyerSkuCode=BPD，管理来源 AUTO_SELECTION，buyer_product_id=真实 BPD ID，DRAFT，无 current_version；创建 UNKNOWN/NOT_EVALUATED 投影。
- 若已有 LST，仅校验不可变买家/SKU和编码碰撞，复用其身份，不对已有 LST 加锁改价/改状态，也不把 MANUAL 改 AUTO_SELECTION。
- 新 LST 插入唯一冲突时抛专用目录身份竞争异常，完整退出当前批次worker事务，再按§3.4用独立短事务双唯一重探测并有界重试原整批。不能在原catch继续写库，也不能把任意DuplicateKeyException解释成成功。
- BPD 同买家/SKU唯一和 LST 同买家/SKU唯一均为最终竞争裁决；测试包含旧管理入口与页面选品并发。旧入口命中现有 AUTO_SELECTION LST 时返回稳定目录冲突，不重复创建。
- 原 BPD 复活沿用原 ID/编码及自动 LST；已有 RETIRED LST 不能自动恢复或另建第二条。关联事务应返回明确不可发布结果，若调用契约只有成功/异常则使用专用目录退役错误，不把它伪装成已可受理。
- BPD、定价身份、首次继承版本、行内定价和自动LST身份任一技术写入失败，整个批次全部回滚。A只持久DRAFT身份，不追加尚无Handler的刷新Outbox；B消费者与生产者同片开启后，刷新Outbox也加入同一批次原子回滚范围。

这个事务在A只确保身份，在B消费者存在后才包含可靠刷新请求，不同步锁已有LST发布商业价，避免BPD/定价→LST的反向锁。B启动全AUTO sweep必须覆盖A期间创建的DRAFT，不依赖A留下无消费者事件。

### 3.3 已有页面数据补偿

已创建 BPD 而没有 LST 的数据，通过管理应用服务 `ensureIdentityAndRequestRefresh(buyerProductCode, operatorId)` 补关联，遵守与新增相同碰撞/唯一约束；只能创建自动新身份，不接管手工行。新增管理端重算入口 §10 可调用该服务。从零测试及生产联调均走该接口/选品接口，禁止数据 SQL 种子。

A的补关联内部方法仅ensureIdentity，不命名/返回“已申请刷新”；B交付真实消费者后才启用ensureIdentityAndRequestRefresh及§10对外重算端点。已有BPD补关联也采用事务外编排→整体写worker协议，不以REQUIRES_NEW只提交身份而丢失其调用者原子性。

### 3.4 批次事务边界、真实调用者与竞争重试

当前`BuyerProductCommandService.addProducts`本身为批量@Transactional (`:78–96`)，整个循环和每行genericPricingService.saveBuyer加入同一供应链事务。生产Java调用者核对仅`BuyerAdminController.addProducts:273–279`，其类/方法无@Transactional；其HTTP请求天然可用无事务编排。其他直接调用位于供应链单测/集成测试（包括BuyerAdminIntegrationTest、ProjectAdminIntegrationTest、StandardProjectHttpClosureIntegrationTest），需保留函数入参/正常返回及整批语义，不能假定测试中的new对象已代表真实代理事务。

A将原公开`BuyerProductCommandService.addProducts`改为**无活动事务编排入口**：明确`Propagation.NEVER`或等价活动事务检测，发现任何当前活动事务立即失败，尚未生成编码/写任何BPD。不得使用NOT_SUPPORTED挂起外层后偷偷独立提交，不允许在rollbackOnly外层中继续重试。调用者若确实需要包含其他写入的更大事务，必须由其业务负责人另行设计整项事务外重试协议；本切片不改变为部分提交。现生产Controller没有此外层依赖，测试需增加活动TransactionTemplate调用的拒绝及无写入断言。

无事务入口调用独立Spring bean `BuyerProductSelectionBatchTransactionalWorker.executeBatch`，公开方法经真实代理、供应链事务管理器、REQUIRED/rollbackFor=Exception；入口NEVER保证每次worker都是一个新的完整事务。worker包含原整批buyer锁、全部SKU校验、BPD新建/复活、L2、通用继承/行内价格、LST身份及B期Outbox，不是“只把ensureIdentity放REQUIRES_NEW”。不得self-invocation，不能把循环拆成每行独立提交。

worker只捕获LST身份唯一冲突并包装明确`BuyerAcceptanceCatalogIdentityRaceException`以穿过事务代理；不能捕获后继续原事务。BPD已ACTIVE/同批重复行仍保留原BUYER_PRODUCT_DUPLICATE，不纳入目录竞争重试。JSON/SQL/任意键冲突照常传播；死锁若要重试也必须辨认受控竞争类型且重跑整批，不泛catch所有RuntimeException。

编排在收到该异常时已确认worker代理结束并回滚全部写入，随后调用另一个真实代理短事务probe（REQUIRES_NEW）：以异常中的原买家/SKU、尝试编码为定位信息，当前读核对`(buyer_id,platform_sku_id)`及`(buyer_id,buyer_sku_code)`，检查真实持久BPD归属及LST编码碰撞。探测不能返回旧事务的BPD id/code当成成功：那些编码可作为审计/定位，不能继续写外键或返回给调用者。

探测同SKU已有合法MANUAL/AUTO LST且编码无冲突时，只返回RETRY_WHOLE_BATCH；无获胜行说明冲突来源不明则保留原因失败；异SKU/编码/关联矛盾返回稳定冲突。原始参数整批最多重新执行2次（初次+最多2次重试，技术常量集中），每次重新定位/分配真实BPD，重新运行全部条件。返回codes只来自最后成功提交的worker结果；如果重试发现别的请求已把BPD置ACTIVE，仍按原重复业务错误结束，不把它当幂等选品成功。次数耗尽报稳定竞争失败并保留cause。

短probe只用定位当前值，不在缺失键上持间隙锁继续写入，不和下一worker共用事务；第二个worker仍以数据库唯一键裁决。真实测试需验证两行批次后半行LST冲突会回滚前半行，重试完整后仅最终返回身份存在且全部价格/关系提交；还需证明外层rollbackOnly调用未进入worker/probe、无活动事务入口和真实代理边界。

**A质量补正约束（不改变业务裁决与事务协议）：** BPD插入命中已识别的`uk_sc_buyer_product_sku`仍转换为原`BUYER_PRODUCT_DUPLICATE`，不进入目录身份probe或整批重试；转换后的业务异常必须保留实际`DuplicateKeyException`及其底层`SQLException`完整cause，不以新建无cause异常丢失诊断。只有真实捕获的技术异常转换需要cause，查询到已ACTIVE的普通业务拒绝不伪造SQL原因。probe作为独立事务编排边界，在任何数据库查询前用结构化debug记录`buyerId`、`platformSkuId`、`attemptedCode`，不在各层重复打印异常堆栈。Writer、probe、专用竞争异常及NULL版本守卫的重要中文注释须包含本文完整路径及真实章节标题/锚点；probe明确前置条件为worker真实代理已经完全回滚，且只探测、不继续写入。NULL版本和完整性守卫链接本文§10“DRAFT稳定响应与管理端可见结果”，禁止沿用不存在的“订单准入与回放”章节链接。

## 4. 数据库增量与来源所有权

当前 Liquibase 已到 059；实现时再次核对末尾，使用下一可用独立变更集，不改已交付 SQL 校验和。本节定义字段契约，不预先执行 DDL。所有表均在供应链库，逻辑引用，无物理外键。

### 4.1 sc_buyer_listing

- `management_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '受理目录管理来源：MANUAL手工管理，AUTO_SELECTION页面选品自动管理'`；CHECK 限定这两个枚举。
- `buyer_product_id BIGINT NULL COMMENT '自动受理目录关联的分销商品主键，逻辑引用sc_buyer_product.id；手工目录为空'`；UNIQUE，MySQL允许多行 NULL。
- CHECK：MANUAL 的 buyer_product_id 为空，AUTO_SELECTION 非空。已有行由默认值保持 MANUAL，不回填关联、不改 buyerSkuCode/状态/商业价。
- 自动行的 buyer/SKU/buyer_product_id/buyerSkuCode 创建后不可换绑；Resolver以买家/SKU唯一关系兼容手工行，不需要更新手工行建立关联。

### 4.2 sc_buyer_listing_version

- `source_buyer_pricing_version_id BIGINT NULL COMMENT '自动商业快照来源的分销通用定价版本主键，逻辑引用sc_buyer_sku_pricing_version.id；手工版本为空'`。
- `commercial_snapshot_digest BINARY(32) NULL COMMENT '自动商业快照来源及展示商业字段的SHA256摘要；手工版本为空'`。
- UNIQUE `(listing_id, source_buyer_pricing_version_id, commercial_snapshot_digest)`，避免同来源及同商业内容重复发布。

摘要包括来源 ID、真实分销价、币种、displayName、业务有效期及数量限制；不含重试时间、trace/operator或投递次数。当前版本已是相同摘要则不发版本、不产生新的UPSERT。若历史已有同摘要版本而当前版本不同，不重新插入；以源当前版本为准决定是否复用旧版本或发布合法的新商业内容，不能让迟到事件把current指针切回旧来源。

自动商业价等于来源 BuyerSkuPricingVersion.distributionPriceCents，来源 identity/version均READY、归属一致、金额非空非负且币种CNY。displayName来自真实SKU名称；数量沿用一期1。有效期从真实自动发布业务时刻开始，validTo为空；供给/价格依赖有效期由可用性投影和最终新快照检查控制，不能伪造来源版本有效期。摘要计算以已冻结的商业有效期为准，同来源重复刷新沿用当前已发布有效期。

### 4.3 sc_buyer_listing_availability

保留现有列并增加：

- `basis_digest BINARY(32) NULL COMMENT '供给评估来源身份版本及路由资格的SHA256摘要'`。
- `basis_json JSON NULL COMMENT '供给评估来源引用及资格摘要，不含凭据、履约目标或完整供应商报文'`。
- `valid_until DATETIME(3) NULL COMMENT '供给可用性投影下次必须重建的时间，Asia/Shanghai；历史未评估为空'`。

评估来源包括平台状态版本、BPD当前状态和定价版本、评估项目/规则版本、候选供应商/Offer/连接器版本/库存来源状态及事实时间引用。basis_version按成功评估递增；version为CAS并发版本。必须使用库时钟，不以Outbox创建时间当成当前评估时间。

索引支持管理来源/买家/SKU分页，以及投影valid_until到期扫描。DTO字段注释与上述COMMENT一致。源引用损坏为系统故障，不能降级为UNPRICED、UNKNOWN或重建假版本。

### 4.4 手工入口治理

**A切片必做旧发布入口MANUAL-only封闭。** `BuyerListingPublicationService.publish`锁LST后、插入任何版本或变更前校验managementMode=MANUAL，AUTO_SELECTION以稳定`BUYER_CATALOG_AUTO_MANAGED`拒绝外部价格命令，事务无商业版本/current/游标/Outbox残留。不能把这项留到B，否则A刚创建的自动DRAFT就能被旧 `/master-data/listings/publish` 任意外部价格发布。

MANUAL继续原商业价/生命周期契约。AUTO_SELECTION的内部`publishAutomaticSnapshot`仅B交付，参数为已核验来源快照，不接受外部价格命令。A的status入口可按原expectedVersion暂停/退役，但任何AUTO→PUBLISHED（包括DRAFT发布或SUSPENDED恢复）在B真实资格检查尚未交付时一律失败关闭，返回稳定未就绪错误；不得仅依据currentVersion非空恢复。B交付后人工恢复才允许委派真实价格/供给资格检查，未知不恢复。自动refresh始终不执行SUSPENDED→PUBLISHED、RETIRED→任何状态。

## 5. 可复用基础设施及新增接口

**历史架构（本期不执行）：** 以下基础设施前置和接口改由新设计 §3 替代；保留文字用于追溯，不要求整体 B0 放行。

**B0技术前置单列设计：** `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md`明确领取恢复/耗尽、转换资格、心跳、事务型Handler分派、源行提交栅栏、Inbox及稳定页/轮次。历史10/`deleg_08c77a41`批准与01/06原报告保留；既有切片1/2运行状态见集成文档，不在本轮重新验收。V4A切片3因真实空表三事务反例撤回“排他upsert创建者回滚后无死锁”保证，修订为仅纯本地完整worker首次加最多一次重跑（总≤2），精确本地账本锁标记+同一SQLException1213/40001+afterCompletion明确ROLLED_BACK+事务资源清理后才事务外重跑。技术领域fixture的run-04原XML12项全0不是生产代理+Druid或全部门禁通过；第二attempt再次真实死锁、提交不确定/rollback失败等门禁未证。本次按用户批准补正原预审deleg_bbb25a07的R01/R02，仅修订并送新独立预审，不批准实施或参数生效。共享nano起点15s不reset、C1000ms仅清理预留、统一R≥3500ms准入/退避前R-b≥3500及后重检；raw.getConnection(1500L)仅有限排队，借后重算E=min(commandDeadline-C,acquiredNano+6s)，事务/每Statement按floor及已有正限制收紧，不足1秒拒绝，MyBatis flush后与commit前guard、rollback不省略。唯一现有Druid池由原bean名稳定budget DataSource包装，manager/SqlSessionFactory/clock同wrapper key、非InfrastructureProxy/TransactionAware代理；非预算透明委派。唯一全局增量是独立int配置aifuli.supply-chain.datasource.connect-timeout-millis默认且仅1500，build后init前setConnectTimeout，用户接受HTTP/Liquibase/clock/六旧Handler新建连接及冷启动影响但它们不接retry。其它maxWait/maxActive/global socket不改、不另建池；真实Driver.connect/HostInfo优先级与冲突形式拒绝、Druid前提校验与恢复失败disable+discard、缓存/资源清理按B0§7.2.1冻结，不承诺TCP以外或15s硬结束。不可变账本/完整载荷/同事务源写绑定/提交guard/真实collation排序/旧Timer回填与维护窗口同版本切换保留，本轮仅本地文档及冻结输入、未实施重跑或自行批准。A选品/定价RPC、六旧Handler、引擎及实际B消费者均隔离，旧scheduleOnce参加更大业务事务只能向事务所有者传播异常、不得局部重跑，外呼Handler不包retry；新增业务语义不私设。B启用新生产者前必须先验收B0；源Outbox/Timer锁前缀仍按§4、追加/调度按§7.2/§7.2.1，不重复锁被消费的源行。

**V4A最新idle例外设计修订（用户批准仅设计修订/预审；未独立预审、未实施）：** R02-ALLOWLIST、R01-PREPARATION、R02-DISCARD-FAILURE的来源仅为父任务提供的完整三项摘要，不冒称归档审查原JSON。B0§7.2.1列可编码有效值集合：retryCount=0、createScheduler=null、空filter、borrow=false/idle=true/return=false及原验证设置、精确默认MySQL checker/validation组合、defaultAutoCommit=true/removeAbandoned=false、单host直接Connector9.7标准socket；配置不符在预算入口失败关闭、不改共享属性。用户批准保留共享testWhileIdle=true，精确默认MySqlValidConnectionChecker及usePingMethod=true/validationQuery=null/validationQueryTimeout=-1旧路径明确允许，不因idle验证存在而拒绝；其它允许集合不放宽，不关闭idle验证、不改checker/global validation。实际运行配置及传输范围仍待验，默认匹配不等于生产可启用。默认checker实际发`/* ping */ SELECT 1`，默认validation timeout=-1；原idle验证失败discard/continue重借是获准非硬截止例外，raw.getConnection(1500L)仅每轮正数队列等待，累计borrow无有限上界，可超过15s或不能及时返回；不重置nano15s。raw返回后第一步检查cancel/deadline并清理late连接，不得暴露manager/启动worker，禁止Future借线程。原pool内部discard无强关闭证明例外只限既有验证路径，不扩成新预算污染/恢复失败/late连接清理宽松；后者继续严格R02.6协议。raw返回后manager看见连接前保存并安装network/socket，覆盖事务prepare/autoCommit、server prepare返回前网络、metadata/session/setup及封闭非递归SESSION control，委派前后重检；Statement记忆最小正timeout、缓存归还恢复，MyBatis flush后及commit前双guard保持。提前保存holder/physical/实际socket→disable阻止recycle→直接捕获Socket.close异常→同步abortInternal→仅一次discard(savedHolder)，禁止用Druid计数/emptySignal/driver isClosed证明物理关闭；源码中discard与abort链都吞close异常，直接标准socket关闭只证明本地传输资源，不保证服务端立即断线。恢复失败即清理失败禁retry，随后丢弃成功也不解禁；Connector/J9.7默认sslMode=PREFERRED，标准socketFactory不等于非TLS；不关闭TLS、不改现非预算行为。未知socket/未审TLS资源覆盖无法证明时该预算实现不得启用；下一独立预审可按明确受控supported transport范围评价，运行未知与TLS支持仍待验，不假实现TLS支持、不声称生产可上线；需改池/驱动内部或扩大支持时另审另授权。三阻断必需负例按B0§9逐项执行，不替代成TODO、不冒称已验证。新冻结`retry-idle-exception-design-review-input.json`及`retry-idle-exception-parent-design-audit.json`在仓外task-b0-ledger-evidence；原retry-resource输入SHA256 `c6bf3832cb77265e71ebd32c6e97afdb8d5d501194bf9d8b034e04edc7cd15c5`及历史retry-budget输入SHA256 `28e380ba361e9afd948337f461c94d1119f5af9b8f91e9883ef06c45eb2607e2`逐字保留。本轮仅四文档patch与证据冻结，生产Java/XML/DDL/测试未改，未构建/测试/DB或网络，未批准实施。

实际已存在：

- `OutboxEventRepository.java:22–49`：调用方现有事务追加LOCAL事件，默认maxAttempts=20，不投递。
- `OutboxDeliveryEngine.java:123–164`：事务外投递注册Handler及正常异常退避已有实现，但Mapper目前只领取PENDING/RETRY，不恢复过期CLAIMED/DELIVERING；不能据现状承诺宕机抢占。死信路径写NULL与available_at NOT NULL冲突，B启用前须修复和真实MySQL验证。不是业务消费ExactlyOnce。
- `InboxService.java:32–67`：调用方事务与当前锁/version CAS可复用，但现有InboxAttempt既不返回version也未封闭重试状态；真实成功CAS还要求PROCESSING。B须明确摘要、状态和版本检查及合法重试迁移，人工处理状态不得自动重试，不能盲用0或将所有非成功状态视为可处理。
- `PersistentTimer.scheduleOnce(taskType,key,payload,fireAt)`；`DatabasePersistentTimer.java:35–63`和`PersistentTimerHandler.java:8–20`：键唯一和正常失败重试已有实现，但当前Mapper不恢复过期CLAIMED/EXECUTING，同键已有完成/死信记录也只返回旧handle；B须先完成恢复及新轮次身份契约。Handler不得直接写领域表，应调生命周期入口。
- `SupplierRoutingApplicationService.route(RoutingInput)`：无持久化路由副作用，返回真实不可变候选/决策快照。现有内部大量普通读，不可直接加入accept的已有RR快照作为“当前供给”。

新增应用接口：

1. `BuyerAcceptanceCatalogResolver.resolve/lockResolvedIdentity`：身份与碰撞。
2. `BuyerSelectionCatalogRefreshRequestService.request(scope, cause, sourceVersion, trace, operator)`：仅追加可靠事件，不锁LST，不写商业价/投影。
3. `BuyerSelectionCatalogLifecycle.refreshListing(listingId, trigger)`：独立短写事务，以LST起锁，发布/投影/CAS/后续Timer同事务提交。
4. `CurrentSupplyEligibilityReader.evaluate(context)`：独立新只读事务，返回新快照的资格、路由快照、basisDigest、nextBoundary，不写目录/报价/订单，不持有供给或定价行锁。
5. `BuyerSelectionCatalogRequestCoordinator.prepareForNewAttempt(identity)`：Facade调用的应用入口，必要时运行一次上述独立refresh；返回READY或DEFERRED及稳定原因。已接受回放不调用它。
6. `BuyerSelectionCatalogRefreshDispatchHandler`、供给变更转发Handler、TimerHandler：解析封闭载荷后委派应用生命周期。

业务状态、targetName、taskType、事件类型及原因码集中枚举/常量，不能散落字符串。重要方法用中文注释链接本文章节；日志记录BPD/LST/SKU/来源版本/事件ID/耗时/闭合结果，不记录凭据和完整目标。重试失败debug/错误码聚合，死信单次warn。

## 6. 全部生产者与可靠触发

**历史架构（本期不执行）：** 本期只接选品／定价事务外编排，其余变化由业务扫描读取当前事实，不接下方全生产者事件平台。

统一LOCAL target：`buyer-selection-catalog-refresh`；事件 `BUYER_SELECTION_CATALOG_REFRESH_REQUESTED`。载荷版本1，字段为scopeType、scopeIdentity、sourceType/sourceIdentity/sourceVersion、cause、operatorId、traceId和可选分页afterListingId。不把事件中旧价格或AVAILABLE当授权。**本节所有新增生产者只在B真实Handler/消费者与Timer一起交付时开启；A完全不发这些事件。** 不以默认开启而消费端未上线的feature flag造成可持续死信。

触发点全部在真实变更事务内追加Outbox；只追加，不同步调用publisher。事务回滚时请求一并回滚。

1. **BPD创建/复活/移除**：真实创建/复活链为BuyerProductCommandService.addProducts（NEVER事务外编排）→代理BuyerProductSelectionBatchTransactionalWorker.executeBatch→worker私有addOrRevive。每行最终行内定价saveBuyer之后在worker原事务登记真实刷新意图，整批领域写完成后按B0§7.2固定顺序同步flush账本及Outbox；flush后不再取新领域行锁，失败随整批回滚。不在NEVER编排层另开事务、裸afterCommit或无可靠提交的内存队列写请求，也不能只修改旧不可达私有addOrRevive。移除在BuyerProductCommandService.retire原事务收尾追加。中间初始化意图可以合并但不能漏最终版本，MANUAL关联不触发自动商业发布。
2. **BPD通用版本变化**：GenericBatchPricingService.publishBuyer之后在原事务登记BPD最终版本意图，由实际最外层供应链写事务收尾按B0§7.2同步flush；saveBuyer、initializeBuyerProduct、recalculatePlatformSku/rebuildBuyers可能嵌套于整批worker和同步初始化链，不能把“最外层Java定价方法”误作事务收尾，publishBuyer之后的项目重建仍在flush之前。任何READY/非READY切换都触发。不能在重建循环尚待拿下一BuyerPricing/ProjectPricing锁时先持多个新增账本锁，也不能只监听页面手工保存。整事务失败不提交任何意图/Outbox。
3. **PLT创建/复活/移除和项目状态**：ProjectListingCommandService.addProducts/retire、ProjectAdminCommandService的生效状态变化；刷新相关BPD以重新评估项目路由汇总。不发项目价格到买家级商业版本。
4. **平台商品和主数据**：ProductCatalogCommandService保存SKU/SPU、applySkuStatus/applySpuStatus；BrandAdminService、CategoryCommandService、ProductTypeAdminService的影响有效性/归属/展示变更。SKU事件精确按SKU；SPU/品牌/类目/产品类型按关联分页展开。BuyerAdminCommandService状态变化也按买家展开。
5. **Offer创建、发布、上下架/移除、商业字段更新**：SupplierOfferPublicationService.publish/listOnShelf；SupplierCooperativeProductService.updateCommercial/retire。前者已有target `supply-availability-recalculation`（:67、217–222），新增真实Handler转发到本target。换SKU编辑同时通知旧、新SKU，不能只通知修改后的SKU。
6. **Supplier状态、配送范围、每日配额、供货范围**：SupplierProfileCommandService.changeStatus/update、SupplierDailyQuotaAdminService.put、SupplierSupplyScopeService.replaceAll；按影响供应商Offer的SKU展开。
7. **Connector发布、退役、状态/能力及影响资格的版本变化**：ConnectorPublicationService.publish/retire已有target `supplier-config-cache-invalidation`（:37、111、157）。当前生产代码只有该target声明、没有Handler，也未核实有对应缓存失效消费者；B新增Handler的实际职责就是可靠转发供应商范围资格刷新，不能虚构已存在缓存失效实现或把刷新成功伪称外部通知成功。未发布草稿/凭据明文保存不触发可售，但实际生效能力/凭据可用状态变更需触发。
8. **InventorySource状态、远程库存/余额/健康事实新增或到期**：SupplierOfferPublicationService.activateInventorySource；后续实际事实写入服务必须在同事务追加精确SKU刷新。当前没有通用事实采集实现，不能在本切片假设已经有；REQUIRED无事实即不通过。
9. **已发布路由变化**：RoutingPolicyPublicationService.publish/publishSuccessorStandardProductRules，以及RoutingRuleAdminCommandService最终发布继承路径；已有target `supply-availability-recalculation`（RoutingPolicyPublicationService:285–289）以全AUTO范围分页转发。仅写草稿不触发有效路由变更。
10. **额度占用与释放**：CARD订单创建、权威失败/取消退出每日配额统计时，订单应用事务追加SKU范围刷新；每天库时钟自然日边界补偿。投影不能替代既有SKU锁及最终实际配额查询。
11. **LST人工暂停/恢复/退役**：在原状态事务提交的事件中标识管理操作；允许重建供给投影但不能再次调用自动恢复生命周期，禁止循环产生相同状态事件。

实现计划必须逐一核对本清单对应类的方法名及调用者，覆盖真实管理HTTP入口；若某个当前入口没有该行为，不新增虚构API，先列为“未来事实生产者接入约束”，由当前Timer和最终事实校验覆盖时间变化。

### 6.1 范围展开与至少一次消费

范围Handler每次按LST主键升序扫描最多100条（配置化技术批量），只展开AUTO_SELECTION。B0事务型执行器先锁/核源Outbox租约，再当前读/核验Inbox事件摘要；同一短事务追加每条listingId刷新事件、固定upperListingId的下一afterId续页事件，最后以真实Inbox版本CAS成功及源Outbox完成，并在提交前核租约。不能先标成功再调publisher；root/page/leaf身份及上界冻结按B0设计§7，不能只换eventId规避真实四列聚合唯一键。

单LSTHandler的真实代理事务包括源Outbox当前锁/有效租约→Inbox当前锁→Lifecycle刷新→Inbox成功→源完成与提交前守卫；publisher失败或源失格则整个事务回滚，仍合法的claim才可由引擎安排退避，否则等待回收。不要独立提交Inbox成功或源完成而业务还没写完。来源载荷仅唤醒“读当前”，迟到旧事件不能切回旧版本。相同事件并行交付由Inbox行锁序列化，不同事件由LST锁和商业摘要唯一键幂等。

在B注册`supply-availability-recalculation`和`supplier-config-cache-invalidation`时读取其真实业务载荷，按封闭事件类型可靠转发资格刷新；不声称调用了当前不存在的cache实现。未知类型/损坏JSON抛异常，不能日志后成功。旧target重复投递只产生幂等分页或最终同来源刷新。不得修改既有Outbox事件的target或投递状态来补历史事件。

### 6.2 商业变更游标与目录fanout

自动发布/投影改变仍同事务追加BuyerCatalogChange，旧目录RPC分页/增量查询立即可读取；不得仅打日志宣称已推送商城。生产核对`buyer-catalog-change-fanout`也只有publisher的target声明、没有Handler。A不新增商业发布或该target生产者，不为此扩大范围。

B复用发布代码若仍追加该target，则必须同时提供明确的本地消费Handler：真实payload为latestCursor、listingNo、listingVersion、availabilityVersion、changeType、occurredAt，不含changeId/buyerId/listingId。以latestCursor定位真实BuyerCatalogChange，再核验buyer/LST/version归属与载荷一致，并以Inbox成功记录“目录游标已可拉取”，不执行尚未接入的外部HTTP/消息推送。用户可观察结果是原RPC增量目录游标可读，不承诺外部平台已收到通知。主动通知/外部fanout为未来边界，其实现需另设实际订阅契约和独立投递结果；本地Handler成功不可冒充主动通知已完成。Handler不把该商业变更再转成自动刷新，避免发布→刷新→发布循环。

## 7. 自动发布、短事务和失败重试

**历史事务实现（本期已替代）：** 单目录事务、幂等及锁序以新设计 §3～§4 为准；以下涉及源锁、Inbox、续页与Timer的实现不执行。

`refreshListing` 事务顺序：

1. 以LST ID锁当前行；核验AUTO关联不可变身份。MANUAL返回SKIPPED_MANUAL，不写商业价/生命周期/**共享availability**，不因BPD移除把旧RPC共享投影改UNAVAILABLE。RETIRED返回TERMINAL，不发布；SUSPENDED只更新安全可用性及来源审计，不恢复。
2. 当前读锁BPD，再当前读锁其BuyerSkuPricing身份；核验版本真实归属及READY。不存在当前价格是DEFERRED，损坏引用是技术异常。
3. 获取独立新只读供给快照 §8；snapshot不读外层尚未提交的目录状态，把真实LST身份及候选发布状态作为可信输入。新DRAFT评估候选供给时不因为“尚未发布”自我否决，否则永远无法首次发布。
4. DRAFT只有BPD ACTIVE、价格READY、商品有效、至少一个实际资格CONFIRMED路线时，发布真实商业版本并DRAFT→PUBLISHED。新价格未配置/供给未知/无路由时保持DRAFT，不写currentVersion。
5. PUBLISHED且条件通过时，仅来源/商业内容变化才发布新版本；价格或供给暂不可用时保留历史商业current及PUBLISHED生命周期，投影置UNAVAILABLE/UNKNOWN，不把它改成人工SUSPENDED。
6. 以投影version CAS写评估、来源、valid_until。状态/原因/basis改变才追加AVAILABILITY_CHANGED及目录变更Outbox；重复评估可更新evaluatedAt/下次边界但不产生游标风暴。
7. 同事务调PersistentTimer.scheduleOnce，为nextBoundary安排一次重算。Outbox消费时业务写入、续页/Timer及Inbox成功一起提交；不得先独立提交publisher再标Inbox。事务外请求协调器可调用独立写事务，消费路径则必须加入Inbox所在写事务；新Reader仅为只读REQUIRES_NEW例外。异常不吞，Outbox重投。

系统发布审计沿用真实触发选品/变更operator，若由补偿产生且源版本publishedBy为空，使用明确配置的系统操作人ID；启动配置缺失应阻止自动发布并记录稳定配置错误，不填0、随机运营用户或冒充发起人。来源和触发人分别审计。

业务DEFERRED正常提交可用性及后续Timer，消费可成功；配置损坏/归属矛盾/SQL/JSON/死锁抛异常。Outbox默认20次失败后按现有引擎死信；Timer持续巡检提供再次评估机会，管理端可重算具体BPD而不修改死信原事件。技术失败绝不能把原AVAILABLE续期；超期投影读取返回UNKNOWN。日志限频，保留完整因果但不输出秘密。

### 7.1 固定锁序及禁止项

- 既有最终HTTP：receipt→LST→SKU→quote→PLT→BPD，保持原前缀；本切片最终供给读取不追加project/pricing/供给行锁。
- 单LST消费：B0基础设施源Outbox（或Timer）→Inbox（如适用）→LST→BPD→BuyerPricing→availability；不锁SKU/PLT/project，不等待带锁供给事务。领域写完成后按B0§7.2固定顺序追加不同子节点身份账本及源记录，随后只做已持锁的Inbox/源完成和提交守卫。消费者不访问自身源追加账本，重复生产者只核独立账本，不重复INSERT/FOR UPDATE旧源Outbox或Timer；仍以真实并发测试验证角色/多槽顺序和无间接锁环。
- 定价生产者原锁链不变，只append Outbox；严禁GenericBatchPricingService.publishBuyer/rebuildBuyers里调用publisher锁LST。savePlatform已有SKU→platformPricing→buyerPricing→projectPricing，与受理LST→SKU会互锁。
- BPD复活/移除不反锁LST；PLT变更不反锁BPD去更新整批LST；独立消费者处理这些投影。
- 同时操作多个LST的消费者拆成单LST事务，不把整个供应商/平台事件展开成一次长事务。
- 新LST在选品事务创建未提交行，已有LST只读核验。旧创建与新选品的唯一竞争必须整事务重探测，不能在持插入失败残留锁时继续其他领域写入。

## 8. 真实供给、项目汇总和新单当前校验

### 8.1 复用真实路由资格

`CurrentSupplyEligibilityReader.evaluate` 经Spring代理使用供应链事务管理器、REQUIRES_NEW、readOnly、REPEATABLE_READ，独立于已有accept/publisher事务建立新的数据库一致性视图。进入后第一次业务读取建立快照并取得库时钟businessTime；全部商品、供给、有效路由、配额读取使用这个新视图，调用真实 `SupplierRoutingApplicationService.route` 加入该只读事务。不能self-invocation，不调用外部SDK/RPC、不获取会等待外层锁的FOR UPDATE，不从accept旧快照复用任何可变供给状态。

其返回完整本次RoutingSnapshot及来源引用，调用方最终建单使用该快照，不再次在旧accept快照里route。读取查验：商品/SPU/品牌及买家真实有效；恰当且唯一的有效标准路由策略/规则；供应商ACTIVE；唯一有效Offer；OfferVersion有效期/数量/CNY；当前连接器及发布版本匹配；能力/执行配置满足实际履约；库存来源ACTIVE/供应商/SKU/REMOTE_ON_DEMAND归属正确；每日配额、地区和配置的库存/余额/健康事实按现有策略检查。

当前实现 `route` 内的缺配置/不匹配异常有多种IllegalStateException。新增封闭 `SupplyQualificationUnavailableException`区分可恢复业务缺条件，公开evaluate返回资格原因；缺少/损坏已指向版本、重复已发布策略、JSON等继续传播系统异常，不泛catch IllegalStateException当未知供给。

缺能力/已停用/没有有效Offer/配额耗尽为UNAVAILABLE；全部候选均因REQUIRED事实缺失、过期或UNKNOWN未通过时，Reader对该项目返回UNKNOWN并保留真实拒绝原因，不能因此自动发布或接受新单；已指向错误实体为引用损坏。现有FIXED及AUTO+ALL候选检查模式UNSUPPORTED (`SupplierRoutingApplicationService:278–296`) 表示该事实未被要求检查，不等于伪造库存充足。有效REMOTE_ON_DEMAND可无数量快照，符合主设计§5.10；此类结构资格可CONFIRMED。OPTIONAL缺事实产生UNKNOWN候选时，不能用于首次自动发布的“检查通过”。

**B实施前的真实内核审计与已确认资格映射（整体B规格仍待独立复审，不提前改实现）：**

- `SupplierRoutingApplicationService.resolveCandidate/evaluateEligibility/evaluateFact` 当前将OPTIONAL事实缺失、过期及UNKNOWN保留为eligible=true、availabilityClass=UNKNOWN；`SupplierRoutingEngine.decide`只按既有三策略及稳定同分规则排序，再选择首个eligible候选，不按CONFIRMED优先。已有`SupplierRoutingApplicationServiceIntegrationTest`源码也断言空事实时选中UNKNOWN；本次仅回读源码，未重新执行该测试。Reader不能把`decision.selected()`或`candidate.eligible()`转换成CONFIRMED证明。
- 当前REQUIRED缺失/过期/UNKNOWN会淘汰候选并保留`*_FACT_REQUIRED/STALE/UNKNOWN`真实原因；现有ERROR事实则产生`*_ERROR`并淘汰，OPTIONAL也不例外。与`docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#8-f-路由读取快照`关于未来库存ERROR映射UNKNOWN的契约必须按事实种类核对，不能在B里把所有库存/余额/健康ERROR统一改成OPTIONAL未知，或泛catch技术异常吞为无供给。
- **用户已确认保留现有排序：** 同项目按既有三策略选中的首个eligible候选为OPTIONAL UNKNOWN时，Reader返回该首选的UNKNOWN，本项目不自动发布并拒绝新单；即使同项目较低候选为CONFIRMED，也不跳过首选、不重跑CONFIRMED-only候选池、不新增可用性排序优先级。只有真实首选eligible且CONFIRMED才构成本项目的确认资格。§8.2聚合的是各项目真实首选结果，不能聚合候选池；其他项目存在真实CONFIRMED首选时，买家级投影仍可AVAILABLE，但未知项目的HTTP仍拒绝。
- **用户已确认REQUIRED全缺失的管理结果：** 全部候选因REQUIRED事实缺失、过期或UNKNOWN被淘汰，Reader将本项目资格及对应管理原因标为UNKNOWN，保留`*_FACT_REQUIRED/STALE/UNKNOWN`具体原因，事实补齐后重新评估。不改路由内核的eligible=false、availabilityClass及原淘汰原因，不把管理UNKNOWN反向提升为可下单。来源明确不可用仍按实际原因UNAVAILABLE，技术损坏仍抛系统异常；本确认不授权改变ERROR事实处理或旧RPC。
- 当前生产受理构造`RoutingInput`仍使用空事实映射；实际生产树仅有InventorySource身份/Mapper，未发现InventorySnapshot读取器或库存刷新实现。`RoutingInput.factsBySupplierCode`只是调用方注入契约，不是已落库采集事实。未来接入真实快照须遵守上述9月29日设计的InventorySource维度，同供应商不同SKU/来源不能互借库存；不能凭空构造AVAILABLE及新时间戳满足REQUIRED。本期FIXED/UNSUPPORTED的REMOTE_ON_DEMAND结构资格与未来REMOTE_OBSERVED采集交付分别验收，不扩写“已有远程实时库存”。

### 8.2 买家级投影与项目级资格

LST是买家/SKU级，无法存每个项目的独立授权。因此投影采用明确的汇总语义：

- 有当前AVAILABLE PLT的ACTIVE项目时，以真实项目上下文逐个评估；存在一个真实首选eligible且CONFIRMED的项目资格则买家投影可AVAILABLE，不取同项目较低候选替代首选。
- 尚无任何可用项目选品时，允许用projectId=null评估买家/平台默认规则；只有实际匹配的非项目专属规则首选eligible且CONFIRMED才可发布。不能假造项目ID或用其他买家的项目凑可用性。
- 任意项目可用只代表买家目录存在可履约路线，不代表全部项目可下单。最终HTTP始终按真实认证项目评估；项目专属暂停、无路由或独立条件失败仍拒绝。
- 无CONFIRMED而有UNKNOWN为UNKNOWN；全部已确定不可用为UNAVAILABLE；有效配置引用损坏报系统错误。

这是本设计的投影聚合选择，保持LST粒度；若产品需要在买家目录显示逐项目可用性，应另增项目读模型，本期不把PLT状态塞到LST生命周期。

### 8.3 最终准入与线性化边界

HTTP在claim之后、首冻之前对新尝试运行一次自动refresh协调器，尽可能完成此前积压；已接受同参回放完全跳过。独立refresh不嵌在accept持锁事务内。MANUAL复用返回`MANUAL_USE_CURRENT_QUALIFICATION`，不跑自动publisher、不把其UNKNOWN投影当未发布；只要真实手工商业版本PUBLISHED/有效期有效，后续首冻仍从当前PLT通用定价取得真实价格。

最终accept已取得receipt→LST→SKU→quote→PLT→BPD当前锁后，在真实资金/订单写入前调用上述新只读供给读取。检查自动目录真实商业来源存在、当前分销价格可形成READY（来源不是订单金额来源）、项目归属/当前选品有效，目标Schema仍满足服务端冻结绑定；实际项目RoutingSnapshot必须选中CONFIRMED有效候选。使用返回快照写路由/BOrder，不再次旧快照route。

**MANUAL/UNKNOWN闭环是B/C必须实现项。** 原`requireSaleableListing`的availability前置判断 (`Lifecycle:550–553`) 在HTTP专用accept模式下改为当前事实策略，不要求MANUAL或AUTO旧投影必须AVAILABLE；保留LST当前PUBLISHED/版本有效期/真实SKU/Schema检查。MANUAL商业版本不要求自动来源字段，也不改其价格；实际订单价格仍使用永久通用报价。最终CurrentSupplyEligibilityReader返回CONFIRMED才允许建单，UNKNOWN/UNAVAILABLE按真实原因拒绝。因此旧手工LST初始UNKNOWN也可在新HTTP中经真实有效供给完成受理，不需要SQL把共享投影翻AVAILABLE。

RPC的原`accept`模式仍保留旧AVAILABLE投影前置检查和原行为，不套用HTTP分支；Resolver兼容身份不等于扩RPC准入。AUTO刷新仅更新AUTO行的投影，MANUAL的新HTTP真实检查只返回本次资格，不回写共享manual投影。这同时避免借后台BPD_REMOVED修改manual availability暗中确定§12.1待确认的旧RPC语义。

最终事实校验不依赖“缓存未过TTL”：投影AVAILABLE但此前已提交Offer退役/供应商停用/连接器退役/有效期结束时，新快照必须拒绝；DRAFT经请求refresh仍未发布，或本次真实资格UNKNOWN时保存稳定可重试拒绝。单纯MANUAL旧投影UNKNOWN不构成拒绝理由。金额仍消费永久原报价，当前价格变动不重新定价旧ISSUED报价。

**供给准入线性化点为最终新只读供给视图建立时刻**：在此前已提交的供给停用必须看见；之后发生的供给变化影响下一新尝试，当前单保留该时刻的合法候选版本。该视图不承诺“供给停用事务等待订单提交”；远程实时履约仍可能失败并走既有权威失败/恢复流程。BPD/PLT移除则继续由原最终准入行锁持到订单提交，遵守10-03已有线性化契约。不要把两种边界混写。

新只读事务不锁外层quote/LST/BPD/pricing行，不会等待外层自己持有的锁。最终SKU锁已序列同SKU订单，因此新视图可看到前序已提交订单的真实配额占用。真实代理/MySQL测试必须证明不加入accept旧RR视图、不被外层未提交回执阻塞。

若未来要求供给停用必须等待订单提交，需先另行统一Offer/Supplier/Connector及定价回算全生产者锁序或引入经设计的供给发布栅栏，不能本切片临时给route加FOR UPDATE造成环锁。

## 9. 到期、巡检与可恢复补偿

**历史补偿实现（本期不执行）：** 下方持久Timer轮次与播种由新设计 §6 的有界周期扫描替代，不恢复页轮次／心跳容量工程。

`nextBoundary`取所有已引用的未来validFrom/validTo、事实validUntil、路由生效/失效时刻、次日配额边界、投影最大评估间隔的最小值。默认最大间隔30秒、单页100为可配置技术参数；无实际未来依赖也有巡检边界。valid_until用于读侧防陈旧，不是实际订单资格证明。

每次成功refresh同事务安排 `BUYER_SELECTION_CATALOG_REEVALUATE`，幂等键以listingId+basisDigest+已冻结boundary时间的版本化身份摘要生成，符合实际128长度；相同已完成task不能承担新的未来轮次。处理器调Lifecycle，返回UPDATED/DEFERRED/TERMINAL等封闭码；B0事务型Timer执行器使业务/续页/下一轮/任务完成及成功审计同提交，不在finally另行完成。抛异常按合法claim重试和死信，禁止在Handler直接改领域表。

另外实现 `BUYER_SELECTION_CATALOG_SWEEP` 全AUTO周期巡检，补偿遗漏事件、过期及Outbox死信：

- ApplicationReady经明确Bootstrapping应用服务，在单短事务幂等确保当前时间桶和下一合法未来桶；当前桶COMPLETED/DEAD/CANCELLED仍必须确保不同key的未来任务，不以旧handle当未来链成功。未来桶已存在时幂等复用合法非终态；未来桶也终态则按B0§8固定序有限向后探测/确保，超过lookahead上限稳定报阻塞，不重开旧key。并发实例及任一步失败全回滚按B0协议测试。
- 每轮扫描按LST升序有限页，持久payload保存轮次/afterId；短事务追加每LST刷新Outbox及下一页/下一轮Timer。追加全部成功后才返回任务完成，宕机由原Timer重试。
- 每轮即使无待评估行也安排下一轮；旧轮次重启时把下一轮推进到库时钟当前/下一桶，不无限补跑已错过时间桶。
- 每LSTTimer与全巡检都是提示，真正状态决定只在LST锁内；定时重复/乱序均不能回退来源或恢复人工暂停。
- 死信可通过管理重算恢复；服务重启时仍会重新播种巡检，不能依赖内存定时队列或只在前端刷新时触发。

旧RPC目录读侧对自动行过期/来源不匹配投影返回UNKNOWN，availableOnly过滤必须同时考虑valid_until和自动来源健康；不继续返回expired AVAILABLE。最终HTTP事实检查始终执行，不因投影刷新频繁而省略。

## 10. DRAFT稳定响应与管理端可见结果

本期准备、保存／发布响应与后台补偿以新设计 §5～§7 为准；原 A 的NULL版本／CAS事实保留。下方新增管理读列、`/recalculate`动作及可靠“重算已受理”响应不实施，不是验收前置。

052已允许受理回执listing_version_no为NULL (`052-standard-order-project-key.sql:39–40`)。将Lifecycle.resolveAttemptListingVersionNo返回Integer：没有真实当前发布版本返回NULL；非空currentVersion指向不存在/错误归属版本继续抛系统异常。不能补1。`FulfillmentAcceptanceReceiptMapper.reopenRetryable`的listingVersionNo参数也必须为Integer，CAS重开显式保留真实NULL，禁止自动拆箱或默认1；同号首次拒绝、正确CAS重开、再次拒绝及修复后续跑都遵守原版本/CAS契约，错误CAS不得重开。

**A/C交付边界：** A只交付NULL尝试版本和原最终受理门禁的稳定拒绝，不交付首冻前Coordinator或“DRAFT不产生报价”。A沿当前HTTP编排：claim后只要数量/target/Schema有效仍可能冻结真实PLT价格，随后因LST DRAFT由accept保存可重试拒绝，报价保持ISSUED且未消费。这是原两段事务的真实结果，可允许补发布后CAS续跑，不是虚构目录价格；A测试必须如实断言该报价可能存在，不能宣称无报价/首冻前目录资格已经交付。

以下首冻前协调和无新报价行为在C落地（依赖B真实refresh），不是A验收标准。首次请求正常claim并记录真实BPD/SKU/Schema及NULL版本，Coordinator在首冻前执行一次refresh：

- 已变READY：沿原首冻→最终受理流程。
- MANUAL已有真实PUBLISHED/有效商业版本但availability=UNKNOWN：沿首冻→HTTP真实资格校验，不执行旧投影前置拒绝，也不自动改其商业价/生命周期/共享投影。MANUAL实际未发布/暂停/退役仍拒绝。
- DRAFT因供给/目录尚未发布，或暂停/退役：不创建报价，调用现有拒绝生命周期保存REJECTED_RETRYABLE/LISTING_VERSION_EXPIRED，listingVersionId=NULL或真实当前版本。先依据协调器封闭原因区分缺价格，不能把下一项的缺价契约改成这一类。
- 已发布但真实供给未就绪：不创建新报价，保存现有INVENTORY_INSUFFICIENT或NO_ROUTABLE_SUPPLIER的真实可重试拒绝；字段与已落库结果一致。
- 真实价格暂缺（包括DRAFT/UNPRICED）：优先使用既有STANDARD_PRICING_QUOTE_MISSING稳定错误契约；若首冻未创建，按10-03保持PROCESSING，不伪称已保存拒绝。不要让DEFERRED空商业版本触发500。
- 修复后新号可重试；已保存REJECTED_RETRYABLE同号恢复继续要求原CAS，不由后台refresh自动重开订单回执。

不改HTTP四必填字段、签名原字节、数量一期1、永久项目键和费用契约。

新增管理读字段：canonicalBuyerSkuCode=BPD、acceptanceListingNo、managementMode、publicationState、availabilityStatus、catalogRefreshReason、sourceBuyerPricingVersionId、lastEvaluatedAt、nextEvaluateAt；均为服务端读字段。项目列表已有PLT编码继续显示，订单用BPD字段不得冒用PLT。

管理动作：`POST /supply-chain/buyers/{buyerCode}/products/{buyerProductCode}/acceptance-catalog/recalculate`，复用分销商品管理权限，方法级@PreAuthorize；校验买家归属，确保缺失身份后追加可靠重算，返回真实身份及当前状态/“重算已受理”。不接价格/availability参数，不直接保证已发布。正常页面选品无需额外点击。已有手工LST返回管理来源MANUAL并保留原管理方式。

该新HTTP动作和“重算已受理”响应仅B消费者真实存在时交付；A只提供内部ensure身份服务及原选品Controller真实创建，不新增/recalculate HTTP、请求协调器或虚假重算受理结果。

## 11. 暂停、退役、移除和复活

- 自动发布只允许DRAFT→PUBLISHED；已发布条件失效只调整安全投影，不自动改变人工生命周期。
- 人工SUSPENDED必须人工恢复，恢复前运行真实refresh核验价格/供给；未知不恢复。自动事件或时间到期不能复活人工暂停。
- RETIRED终态，关联/重算返回稳定TERMINAL；禁止重置为DRAFT/PUBLISHED，禁止违反买家/SKU唯一键另建新LST。若未来需要退役后重上架，先单独修改生命周期及唯一槽设计。
- BPD REMOVED：HTTP新尝试由原BPD当前锁检查拒绝；仅自动目录刷新把其投影UNAVAILABLE/BPD_REMOVED，保留LST/商业版本/编号与报价。既有MANUAL不自动改生命周期/商业价/共享投影，其旧RPC范围继续待确认。
- BPD复活：保持同ID/BPD/LST，通用定价按现有复活规则产生新来源版本；仅AUTO且未人工暂停/退役的目录在条件恢复时重建安全投影，原价永久报价不改。
- PLT REMOVED只影响该项目的HTTP准入，不能一概暂停买家LST让其他项目失效；汇总投影按§8.2重建。
- 目录移除/暂停、商品价格变动、供给失效以后，已成功同参回放在仍通过现有买家/项目ACTIVE和身份认证前置门禁时返回原单，不发布、首冻、路由或资金重复执行。买家/项目本身停用后的回放权限不在本切片改写，见§12.2。

## 12. 旧RPC待确认与回放冻结身份缺口

### 12.1 唯一待确认业务项

**待用户确认：移除BPD或项目选品，是否也禁止已有旧RPC编码的新下单尝试？** 用户已被根任务询问，尚未回答。本设计不默认“全部同步禁止”或“永久允许”。

当前10-03设计及实现保留旧RPC.accept行为；实现本切片身份解析兼容和自动新目录时，不先给手工旧RPC增加BPD/PLT准入，不因该待确认项阻塞自动新目录、可靠重算和HTTP当前事实机制。新增自动目录供给/生命周期门禁按已确认自动目录范围生效。

待答后若选择统一禁止，根任务需在本文更新RPC入口范围、final锁顺序及对应真实MySQL回归场景，再编排实现；若选择保持旧RPC独立，只记录差异并保持manual契约。不以保留旧别名推断移除后的授权。该项未确认前不能宣布HTTP/RPC停用语义已经全一致。

### 12.2 回放必须先取冻结身份

10-03设计§7/现文件末尾已记录：当前Facade先用当前SKU Schema构造指纹后claim；Schema/身份变化可令同一payload冲突。本文桥接不能靠“不改价就不改Schema”掩盖。

真实Facade前置顺序已经核对：HTTP `ProjectStandardOrderCommandFacade.java:94–96` 先`projectAccessResolver.requireActive`、`buyerResolver.requireActiveById`，随后resolveTargetSchema/prepare/claim；RPC `BuyerFulfillmentCommandFacade.java:83–95` 先basic request、BuyerResolver.resolve、projectAccessResolver.requireActiveByCode，随后Schema/prepare/claim。两入口都可能在买家/项目ACTIVE检查失败时先拒绝，不是回执读取优先于全部认证/主体状态。

全配置闭环验收的先决切片：**保留上述现有身份认证及ACTIVE前置门禁**，在取得真实buyer/project之后、当前Schema解析之前，新增border应用读服务按真实projectId+buyerOrderNo获取 `FrozenAcceptanceIdentity`。HTTP和RPC Facade在这个位置先读冻结身份。已存在且格式2、冻结SKU/Schema完整时，以回执冻结身份、冻结Schema规范化原请求目标，计算同一格式2指纹，再由原claim当前锁比较。原请求编码仍参与；不读当前BPD/LST映射重新改身份。已接受同参回放绕过当前**商品目录、Schema及供给**，不是绕过买家/项目认证；异参仍冲突。没有回执才走Resolver；查与claim之间并发首创由既有项目键竞争重探测裁决。

本文仅保障主体仍有调用权限时，商品目录/Schema/价格/供给变化不破坏既有成功回放。**买家或项目本身停用以后是否仍可回放，不在本切片决定或实现。** 若产品以后要求此行为，需另行确认认证/主体准入策略和安全范围，再设计认证后查询冻结主体的专用读路径；不能只替换Schema就宣称buyer/project停用也可回放。

格式0/缺冻结来源历史记录沿052既有治理规则处理，不从当前目录推断补齐；没有可验证历史绑定时报告稳定历史兼容错误/人工治理，不能“重新生成当前指纹”冒充可信旧身份。对PROCESSING/REJECTED_RETRYABLE续跑保持冻结不变量，再执行当前真实准入和原CAS。若本切片未实施上述前置修复，交付说明必须明确仅覆盖价格和状态回放，HUA_SHENG全配置验收门禁未通过，不能声称已覆盖Schema/身份变化回放。

## 13. 自动化测试与实际管理链路门禁

本期矩阵由新设计 §10替代本章的事件接线／Outbox/Inbox/Timer专项；原A回归及金额、幂等、隔离、真实资格、并发／事务门禁不豁免。华昇资产交付属后续，不混作本期受理完成条件。

仅跳过供应链以外模块单测。门禁按`docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁`执行；人工联调文档不能替代自动化。实现时同步维护`docs/集成测试.md`；本轮技术规格补正仅记审计与待实施矩阵，不冒称运行。

### 13.1 纯逻辑与契约

- Resolver：BPD/旧别名同身份、跨buyer、双路径不同SKU碰撞、REMOVED身份仍可定位、没有LST、退役、原编码不规范化；新增兼容create保留前缀在服务层拒绝，真实DB大小写/等价字符和不同连接字符集绑定转换一致，非ASCII合法外部码仍可创建、存量原码不改。
- 自动发布：真实来源READY、缺价、未知供给、来源引用损坏、摘要幂等、迟到旧来源读当前、display变更、暂停/退役不恢复、手工商业价未变；A旧publish服务经真实Controller/直接服务调用均拒AUTO外部价，AUTO人工恢复在B前失败关闭且无版本/指针/游标/Outbox副作用。
- 投影：有效固定价REMOTE_ON_DEMAND无数量快照可按真实UNSUPPORTED模式通过；REQUIRED缺失/过期、OPTIONAL未知、有效期、项目专属路由汇总、所有项目不可用、库存不足及额度恢复。
- 已确认资格映射：同项目首选UNKNOWN、较低候选CONFIRMED时不改选且不发布/接单；另一个项目真实首选CONFIRMED时买家投影可AVAILABLE但未知项目仍拒绝。全部候选因REQUIRED缺失/过期/UNKNOWN未通过时，管理结果UNKNOWN、保留逐候选实际原因且不更改内核淘汰结果；技术错误和明确缺货不借此变成可用。
- 生产者清单逐入口验证append发生于真实事务，rollback不留刷新事件；unknown事件/损坏JSON不成功。
- Inbox真实version重试、Outbox至少一次、Timer重复键和下一桶、死信/重启补偿；技术异常保留因果，无永久假AVAILABLE续期。
- HTTP/RPC请求字段、响应receipt、BPD编码文档契约；报价PLT ID与履约LST ID分别验证。DRAFT NULL版本真实拒绝与既有CAS相符，覆盖ReceiptMapper的NULL首次拒绝→正确CAS重开→再次稳定拒绝、修复后续跑及错误CAS不重开，不能仅验证首次claim。
- A质量回归：BPD同买家/SKU唯一键异常转换后保留原业务错误码及完整SQL cause，不调用目录probe、不重试；重要设计链接指向真实章节，独立probe在查询前记录三项定位参数且不增加逐层异常堆栈。真实MySQL两行批次负例须由数据库产生1062并核验`uk_sc_buyer_product_sku`、完整回滚及无孤儿残留，不仅使用mock worker证明cause和事务。
- 架构测试保留Facade无DAL、routing.domain纯域、catalog不写账户账本、管理映射@PreAuthorize。新增reader/publisher不得借桥接访问system/member表或隐式RPC扫描。

### 13.2 真实MySQL并发与事务

在`supply-chain-integration`真实MySQL/Liquibase/Mapper下，以确定性latch验证：

1. 同buyer/SKU页面并发选品，旧LST管理创建与页面并发；无双LST/双BPD，无跨SKU复用。两行批次在后行制造LST竞争：整个worker事务回滚、独立双唯一probe后重跑原整批，最终返回ID/编码均真实已提交；已ACTIVE普通重复仍原拒绝。以活动外层事务及rollbackOnly外层调用入口，必须在worker/probe开始前失败、无偷偷提交。补真实跨表负例：已有MANUAL LST(Y)被选品复用时，另create尝试以新BPD保留编码绑定SKU X，必须由保留命名空间拒绝，不出现竞态异SKU别名；大小写/trim变体同样验证。
   A另外以确定性latch验证身份快照在accept之外定位：后一请求等待SKU锁时前一请求提交占额，后一请求取得SKU锁后的普通统计必须看到前序订单并拒绝超额，覆盖BPD和旧编码两条路径；不得靠mock或仅检查@Transactional注解宣称RR时序已验证。
2. 目录关联/定价/Outbox任一失败整体回滚；消费者崩溃在商业版本插入、current CAS、投影、Inbox成功之前，均可恢复且不重复UPSERT。
3. v1/v2事件乱序和重复；已有v2 current时迟到v1不能降级；自动来源及MANUAL版本不可交叉接管。
   A期间只创建DRAFT且无新刷新target Outbox；B启用真实Handler后启动sweep能补齐这些DRAFT。以MANUAL PUBLISHED/UNKNOWN及真实HUA_SHENG供给进行新HTTP受理，必须通过新事实资格成功，manual价/状态/共享UNKNOWN不变；对同manual旧RPC回归原UNKNOWN拒绝行为，不由BPD移除后台改投影。
4. accept取得LST/SKU后，平台定价、Offer发布/退役、Supplier停用、Connector退役与publisher并发，不形成pricing→LST环，不在新只读事务等待外层锁。
5. 外层accept已建立旧RR视图，停用真实供给提交后再开始CurrentSupplyEligibilityReader；必须读取已提交停用并拒绝，无订单/资金/消费/Outbox残留。普通加入外层事务作为反例应被测试识别。
6. 最终新视图建立后并发供给停用，按§8.3合法冻结旧候选，不把测试写成“停用必须等待订单提交”；BPD/PLT移除仍按10-03验证停用先提交则拒绝，accept先持准入锁则移除等待提交。
7. 到期无人工操作：供给validTo、策略跨界、事实过期、自然日配额、Timer重启与Outbox死信；投影可过期UNKNOWN，AVAILABLE滞后时最终仍拒绝，供给恢复不自动恢复人工暂停。
8. 原永久报价重试保持原金额，新号读取新价；旧/new编码改参冲突、跨项目同号隔离；主体仍ACTIVE时已接受商品目录/供给停用后回放原单，无新报价/资金/履约调用。
9. 冻结Schema/身份变化回放前置测试；历史格式0不能从当前目录伪造冻结来源；买家/项目本身停用沿现有两个Facade前置策略拒绝，不把Schema修复测试误称为主体停用可回放。

### 13.3 从零管理Controller测试及HUA_SHENG一单

测试fixtures只启动基础设施及必要字典/权限，不直接insert目录/价格/AVAILABLE。使用真实Spring管理Controller和供应链应用服务创建：商品/SPU/SKU→默认真实价格→HUA_SHENG供应商/凭据集/连接器校验发布→固定价Offer上架→真实路由规则→分销商页面选品→项目选品→Outbox消费者/Timer→自动LST/商业来源/真实投影→项目认证HTTP下单→履约→查单/资产。

HUA_SHENG外部响应可在自动化里使用协议真实的隔离HTTP stub；真实环境验收另用受控真实HUA_SHENG配置和业务号。禁止替换为QNM、绕过HUA_SHENG凭据/能力校验，或通过SQL预置PUBLISHED BuyerListing作为成功条件。

验证管理员选品请求与前端当前真实payload一致；由 `BuyerAdminController`/`ProjectAdminController` 的实际products端点执行，不调用虚构Controller。确认BPD页面编码即订单buyerSkuCode，PLT仅用于项目选品管理，LST自动生成并有真实版本来源。自动发布未就绪时管理读字段/HTTP拒绝真实可见；补齐供给后自动恢复到可受理。

测试至少对金额、唯一订单/报价、回执状态、实际LST版本引用、项目定价引用、真实选中HUA_SHENG供应商、履约操作唯一、资产及资金不变量作数据库断言；不能仅断言Controller返回200。

## 14. 实施拆分与交付判定

**历史拆分：** 下方A成果保留；B/C已合并为新设计 §10首个业务交付，不按旧consumer/producer/Timer前置串联执行。

按以下依赖顺序编排后续实施，文档批准不等于已实现。每项是可单独审查和验证的切片，不合并为一次庞大提交：

1. DDL/枚举、Resolver、BPD保留命名空间、无事务批次编排/代理worker/竞争probe、同事务自动身份及DRAFT NULL版本；同时封闭旧publish为MANUAL-only、AUTO人工恢复B前失败关闭。A不追加任何新刷新target事件。
2. 自动快照publisher、真实来源/CAS、请求Outbox、Inbox事务消费者、既有target处理器、Timer及生产者触发清单。
3. 新只读供给资格Reader、真实项目最终校验与路由快照复用，自动目录过期读侧和管理可见字段/重算端点。
4. Schema/身份冻结回放前置切片与历史规则测试。
5. 从零Controller真实MySQL全链路，HUA_SHENG实际一单及集成测试文档。
6. 用户回答后单独落实旧RPC移除语义；之前不扩该范围，也不宣称已一致。

前三个最小切片的独立完成标准：

- **A：身份解析及安全封闭。** 交付自动管理字段/来源字段迁移、双编码Resolver、BPD保留命名空间、新兼容输入归一、NEVER事务外整批编排/独立代理worker/双唯一短probe和有界整批重试、同事务唯一DRAFT身份、内部补关联服务及NULL尝试版本（含ReceiptMapper的NULL CAS重开）。最终accept之外定位并传入可信应用身份快照，保持receipt→LST ID→SKU当前读前缀及SKU锁后建立普通读视图。同时旧publish必须MANUAL-only，AUTO外部价发布拒绝、人工恢复在B前失败关闭；A只写DRAFT，完全不发尚无Handler的新刷新Outbox，不交付/recalculate HTTP、假重算成功响应、首冻前Coordinator或DRAFT不产生报价行为。通过Resolver契约、实际旧publish/status Controller负例、原选品Controller真实创建、真实MySQL整批回滚/外层事务/跨表保留码并发、NULL CAS续跑及SKU锁等待后可见前序占额测试后可独立安全落地；DRAFT最终稳定拒绝可能保留真实未消费ISSUED报价，按§10如实验证。商业发布/无报价前置门禁闭环仍明确未完成。
- **B：自动商业镜像与真实可用性。** 依赖A，真实consumer/Handler、producer及Timer同片启用，启动sweep补A期间DRAFT；交付真实来源publisher、新只读资格Reader供投影使用、Outbox/Inbox、全部当前生产者触发、手工兼容及管理状态回显。MANUAL不被自动写价/恢复/改共享availability，不能因此在C永久UNKNOWN拒绝。通过乱序/宕机/CAS、真实供给失效和从零管理发布测试；页面选品可以在条件具备后自动发布，但未通过C之前不能宣称最终供给门禁完整。
- **C：当前供给准入与从零Controller集成。** 依赖B，交付HTTP最终新快照读取和路由快照复用、MANUAL PUBLISHED/UNKNOWN以真实资格替代旧投影前置门禁、DRAFT稳定拒绝及§12.2冻结身份回放前置修复；该修复保留两个Facade现有认证/buyer/project ACTIVE前置边界。通过RR旧快照反例、准入/停用并发、MANUAL UNKNOWN新HTTP/旧RPC差异回归、从零管理Controller→HUA_SHENG订单/资产/金额全链路。正式HUA_SHENG金额、确认及取券以其总设计§12.6为准。旧RPC移除待确认项不加入C的实现范围。

根任务可按A→B→C安排唯一实施者，每片先跑其必要门禁再进入下一片；A/B通过只宣告本片结果，不使用SQL补数据提前宣告C通过。

身份存在、商业发布、价格READY、供给投影及最终实际资格均有真实证据，才可称“页面选品→受理目录”已完成。手工目录未被接管、旧映射/旧单未被重写、投影滞后不会授权新单、已接受回放不重做副作用，都是交付必须满足的不变量。
