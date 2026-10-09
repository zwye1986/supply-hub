# 首个业务交付：真实选品可售与签名下单受理

日期：2026-10-09。设计方向及后台扫描方案已获用户确认；用户本次明确要求按本文拆 TDD 实施计划并开始推进代码。执行入口为 `docs/superpowers/plans/2026-10-09-saleable-catalog-order-acceptance.md`，首批从真实首选资格纯映射开始。**整链尚未验收、尚未部署；逐片实现及测试状态以计划和 `docs/集成测试.md` 的实际记录为准。**

## 1. 适用范围与替代关系

交付真实链：管理选品／定价 → 自动商业目录 → 当前项目资格 → 签名下单 → 路由及资金预留。后台在条件满足后自动恢复可售，不能依赖用户再次保存或发起订单。

本文替代 `docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md` 中 B/C 的实施架构，尤其原 §5～§7、§9、§10 的可靠重算入口、§13 中事件／Timer门禁及 §14 的 B→C 技术拆分。原 A 的身份、事务、命名空间、NULL CAS及安全封闭成果保留；原 §8 的真实供给及项目首选语义、§11 的生命周期、§12 的冻结回放与 RPC 边界继续适用，冲突以本文为准。

不依赖或新增：通用事务型执行器、商业刷新 Outbox/Inbox、账本 flush、ROOT/PAGE/LEAF、持久页轮次、租约心跳容量工程、全部十一类生产者接线。已有订单 Outbox、Timer、账户／账本和 Hikari 保护不删除、不回退；不打开仍失败关闭的 `appendLocalIdempotent` 商业通道。

前端只改选品／改价后的结果提示及必需 API 类型。**不新增下单编码列、目录状态列、重算行操作、项目 BPD/PLT 展示、菜单、任务中心或轮询。** 不交付专用管理重算 HTTP 接口。华昇 type201 生产执行、资产交付、通知及售后不属于本切片完成声明。

## 2. 已核对基线与复用能力

核对代码基线：`0be390d`，Hikari 合并提交 `4ee45e9` 已在当前历史中；不再把“等待合入 Hikari”列为本片前置。

后端 Java 路径基准：`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`。

- `tradecore/catalog/application/BuyerProductCommandService.addProducts` 为 NEVER 编排，调用独立代理 `BuyerProductSelectionBatchTransactionalWorker.executeBatch`；选品原整批事务及身份竞争重试复用。
- `tradecore/catalog/pricing/GenericBatchPricingService.saveBuyer/saveProject/savePlatform` 为 REQUIRED，可嵌入原事务；不得直接在这些方法中刷新 LST。
- `tradecore/catalog/application/BuyerListingPublicationService` 已封闭手工发布 MANUAL-only，AUTO 恢复目前失败关闭。其旧手工发布会追加 fanout；不能直接拿该公开入口实现 AUTO 发布。
- Liquibase `060-buyer-acceptance-catalog.sql` 已包含管理来源、自动价格来源／摘要及投影 `valid_until`；当前末尾还有 `061-append-schedule-identity-ledger.sql`。不修改已交付变更集校验和，不重复建列。
- `application/facade/ProjectStandardOrderCommandFacade` 目前在 claim 前解析当前 Schema，claim 后直接首冻，最终 Lifecycle 内原视图 route；这三处是本次真实缺口。
- 管理前端位于 `/Users/mac/Documents/codeSpace/supplychainhubweb`。实际选品 `createBuyerProducts` 返回 `string[]`，分销定价 `saveBuyerProductPricing` 返回 `boolean`；保存提示没有目录结果。

## 3. 应用边界与事务协议

新增应用服务分工如下，均局限供应链进程／供应链库：

1. **单目录刷新服务** `BuyerSelectionCatalogRefreshService`：接受可信 listingId、触发种类和操作上下文；事务外门面调用独立代理 worker，短写事务刷新一个 LST，返回不可变真实结果。门面禁止活动事务，不用 self-invocation 或 NOT_SUPPORTED 偷偷挂起业务事务。
2. **当前资格 Reader** `CurrentSupplyEligibilityReader`：独立代理 REQUIRES_NEW、readOnly、REPEATABLE_READ；使用库时钟和真实路由，返回本次项目资格、完整 RoutingSnapshot、原因、来源摘要及下一事实边界；不外呼、不写领域、不加供给行锁。
3. **管理保存编排** `BuyerSelectionCatalogAdminCoordinator`：无活动事务入口调用原选品／定价服务，代理确认提交后定位受影响目录并逐条刷新；返回保存及目录两个维度。嵌套初始化只完成原领域事务，不增加 LST 锁。
4. **后台扫描服务及生命周期** `BuyerSelectionCatalogSweepService` / `BuyerSelectionCatalogSweepLifecycle`：只负责有界读取目录 ID和调用同一刷新服务，不负责领域判定，不使用持久任务确认协议。
5. **下单准备服务** `BuyerSelectionCatalogRequestCoordinator`：只为需要执行的新尝试补刷新／检查首冻前条件；已受理回放不调用。
6. **冻结回执读服务** `FrozenAcceptanceIdentityReader`：按可信 projectId+buyerOrderNo 读取历史绑定快照；Facade 不访问 Mapper/DO。

单目录写锁顺序为 LST → BPD → BuyerPricing → availability。当前价格身份使用锁定当前读，版本引用核对真实归属。刷新不锁 SKU、PLT、project 或供给行；Reader 不读取外层未提交写入作为授权，不等待外层自身持锁行。禁止定价／选品持锁期间反向调用刷新。

最终受理保留 receipt → LST → SKU → quote → PLT → BPD 的当前读锁序；Reader 在建单／资金前独立建立新供给视图，不在原受理快照再次 route。普通身份定位在最终受理事务外完成，不能提前建立 RR 旧视图影响配额统计。

## 4. 自动商业刷新与生命周期

### 4.1 真实来源与幂等

AUTO 商业价仅来自本次锁定的 READY `BuyerSkuPricingVersion.distributionPriceCents`，归属一致、非空非负、币种 CNY。订单金额仍来自项目永久首次报价，不从目录商业价反推。

READY存量标记本身不证明其价格依赖仍有效；须核真实来源／依赖有效边界。需要重算时先结束本次目录事务，由事务外编排委派已有定价重算并确认提交，再重新刷新；不得持LST锁去调用会锁SKU／项目的定价重建。不创建新价格公式。当前不能形成真实有效价格则DEFERRED，不发布过期价；后台下一次遍历继续覆盖。首冻沿既有价格来源按需准备，不能以旧目录商业快照替代当前定价依赖核验。

商业摘要沿用原设计 §4.2：来源 ID、实际价格、币种、真实名称、有效期与数量限制；不含刷新时间、traceId 或操作人。同来源与内容重复刷新不创建版本／游标。同来源有效期沿用已经冻结的业务有效期，不能每次 now 重建摘要。旧触发必须读取当前来源，不能把 current 指针切回旧来源。

版本、current CAS、availability 及本地 `BuyerCatalogChange` 同一单目录事务提交；任一步失败全回滚。AUTO 新路径只记录可拉取目录游标，不新增 `buyer-catalog-change-fanout` 事件，不伪称外部已通知；旧 MANUAL 发布／事件行为保持。没有变化不重复追加目录游标。

### 4.2 状态决定

- MANUAL：SKIPPED_MANUAL，不写价格、生命周期或共享 availability。
- RETIRED：TERMINAL，永不复活、不另建 LST 占用唯一槽。
- SUSPENDED：可重评估安全投影，但不发布新商业版本、不恢复生命周期；返回 PAUSED。
- DRAFT：BPD ACTIVE、价格 READY、商品有效且买家聚合存在真实 CONFIRMED 首选资格时发布；缺条件保持 DRAFT及真实等待原因。
- PUBLISHED：条件满足且来源／内容改变才更新商业版本；条件失效保留商业历史及生命周期，投影 UNAVAILABLE/UNKNOWN；条件恢复自动重评估，不需要人工保存。
- BPD REMOVED：AUTO 投影不可用，身份／历史不删；复活沿用原身份。PLT REMOVED只影响对应项目，不能停掉其他项目。

人工恢复 AUTO 仍走现有带 expectedVersion 的状态入口，必须明确人工命令，在同一 LST 当前锁内检查 READY 价格及真实资格后才恢复；不能“先读通过，再无检查改状态”。自动扫描／下单准备不能传人工恢复模式。RETIRED 不接受恢复。

缺价格／供给是 DEFERRED；损坏引用／归属矛盾／SQL／序列化故障为技术失败，不能泛 catch 为 UNKNOWN。管理触发发布使用真实管理操作人；扫描／下单触发无管理操作人时使用显式配置的系统操作人 ID，来源版本发布人独立保留。未配置为明确自动发布配置错误，不填0、随机用户或冒充原请求操作人；不访问 system 库查询用户。

## 5. 管理保存成功与刷新失败契约

### 5.1 HTTP 返回

实际选品／定价接口保留 URL、请求字段和权限；响应 `CommonResult.data` 从原数组／布尔改为结构化保存结果，前后端同次发布。直接领域服务原签名保留，Controller 委派事务外编排。

受影响路径为分销 `POST /supply-chain/buyers/{buyerCode}/products`、`PUT /supply-chain/buyers/{buyerCode}/products/pricing` 及项目 `POST /supply-chain/projects/{projectCode}/products`、`PUT /supply-chain/projects/{projectCode}/products/pricing`。旧 L2 PATCH／其它历史批量入口不改返回类型，不借本片替换其业务语义；其变更由扫描覆盖。

响应包含：

- `saveState=COMMITTED`：仅原保存代理确认提交后产生。
- `savedCodes`：真实已提交操作身份，分销操作 BPD，项目操作 PLT；不混用编号。
- `catalogResults`：逐 BPD/LST 返回 `refreshOutcome`、真实 `publicationState`、`availabilityStatus`、`reasonCode`、`evaluatedAt`、`automaticRecoveryEnabled`。
- `refreshOutcome` 封闭为 UPDATED、UNCHANGED、DEFERRED、FAILED、SKIPPED_MANUAL、PAUSED、TERMINAL；publicationState 为真实生命周期，不用执行码代替。

技术失败 reasonCode 使用稳定目录刷新错误码，诊断关联用平台 traceId；不返回 SQL、堆栈或供应商秘密。失败事务的状态／时间不能放入响应：只可附失败后确实读到的已提交历史快照，读取也失败则字段为空，绝不补默认 PUBLISHED/AVAILABLE。刷新提交结果不确定也标 FAILED／结果未确认，后续幂等重评估。

保存校验失败／回滚按原错误契约返回且不刷新；保存提交结果不确定不生成 COMMITTED成功响应。领域整批仍原子提交；提交后目录独立刷新，部分失败不能重跑保存 worker。目录定位失败也返回已保存加真实刷新失败，不把已提交选品包装成整体失败。

### 5.2 真实管理调用范围

- 分销选品（含行内定价）：原 batch worker提交后刷新最终已提交 BPD目录。
- 分销通用定价：`BuyerAdminController` 实际 PUT pricing调用原服务确认提交后刷新选中 BPD。
- 项目选品／定价：原项目事务提交后刷新选中 PLT对应 BPD；项目价不写买家级商业价。
- 平台默认定价／其它主数据／供应商／路由变更不新增全范围生产者或更改其响应，由后台扫描覆盖当前事实，既有原事务不增加 LST锁。此范围不承诺保存返回前所有关联目录已经刷新。
- 选品／定价真实Controller新增编排不改变原服务内部调用者整批／级联原子性。

### 5.3 前端唯一改动

实际修改分销／项目选品及定价的 API 类型与调用处，按结果提示保存完成、已发布、待就绪、刷新失败、MANUAL／暂停／退役等摘要；批量包含异常项身份及原因，可用已有消息／结果对话框，不加列表列。

- 已保存、已发布且本次评估通过：提示目录已发布，不说全部项目可下单。
- 已保存、待就绪：真实原因＋条件满足后后台自动发布。
- 已保存、刷新失败且扫描启用：后台将自动重试，当前可售未确认；扫描关闭时不得承诺自动重试。
- MANUAL／暂停／退役：明确状态，不说自动恢复可售。
- 保存确认提交后关闭表单并刷新原列表，目录失败不引导重复添加／保存。
- 网络超时／响应缺失：结果未确认，提示查询现有列表，不判断回滚、不自动重发非幂等选品。

不新增专用管理重算按钮／接口或项目实时资格看板。仅靠现有列表不保证可查询到目录状态；网络未确认时查询列表只能确认相应选品／价格事实。

## 6. 后台自动恢复：有界周期扫描

### 6.1 唤醒与覆盖

供应链业务专用生命周期启动单线程 fixed-delay 调度器，启动后执行扫描，前一批结束后等待默认30秒；每批最多100条，可配置且校验正数。配置／执行器只作用供应链，不修改共享调度器、nonce清理、Timer/Outbox线程池或八进程装配。

范围为全部 AUTO_SELECTION且非 RETIRED行，含 DRAFT、SUSPENDED、PUBLISHED、UNKNOWN、DEFERRED和上次技术失败行；不只扫描 FAILED队列或投影到期行。已发布行也复核，覆盖价格变化、供给失效／恢复和项目变化；暂停行只评估不恢复。

普通键集查询按主键升序只读取有限 ID，非 FOR UPDATE；不持事务跨整批执行。查询失败本批不推进位置，下一次重试；没有候选不结束调度。

### 6.2 公平推进与重启

实例只保留内存扫描位置及本次遍历主键上界：从最小 ID起，遍历开始读取当前最大符合范围 ID；后续逐批 `cursor < id <= upperId`。每条已尝试即推进位置，即使刷新失败；遍历到上界／空页就清空位置，下次从最小 ID读取新上界。

这是普通有界查询，**不建数据库页记录、持久轮次、ROOT/PAGE/LEAF、子事件、claim、租约或心跳**。内存上界只防持续新增高ID使既有低ID失败项饿死。新增行下一次遍历覆盖；重启丢失位置只造成重复，不丢业务事实。

每条调同一独立刷新代理，失败回滚本条并继续其余条；下次遍历覆盖失败项。退出／中断不伪造成功，不无限吞中断；停止前不启动新条目，当前事务依既有 Spring／JDBC退出协议结束，不承诺硬截止。

### 6.3 多实例与恢复承诺

各实例独立扫描，同一 LST由当前行锁及来源摘要唯一约束序列化，重复不创建版本／游标；不新增选主。源变动未登记刷新事件、保存后退出、扫描中退出及多实例重复均靠再扫数据库事实补偿。

自动恢复前提为至少一个扫描实例持续运行、扫描启用、数据库／真实配置可用且未人工暂停／退役；不承诺30秒全量恢复。目录规模／单条耗时决定遍历时间，持续损坏条目不阻断其余条；本片不另建持久重试／告警平台。

### 6.4 投影陈旧安全

成功评估 `valid_until` 为未来真实依赖边界与最大评估有效期的最小值，默认最大有效期30秒。扫描一遍超过该间隔，读侧返回过期 UNKNOWN，不续期假 AVAILABLE。

摘要不包含evaluatedAt、每次重新计算的30秒截止时间或累计basisVersion，避免无变化重评估产生游标风暴；事实本身的validFrom/validTo/validUntil仍为来源。成功无变化可更新时间及截止，但不生成新商业版本／目录变更。技术失败不更新evaluatedAt／valid_until，不给旧AVAILABLE延期。

目录 RPC读项及 availableOnly筛选遵守同一库时钟／AUTO过期规则；当前价格来源不匹配不返回旧 AVAILABLE。MANUAL读侧与旧RPC准入行为保持。HTTP最终资格检查永不因投影 AVAILABLE而省略。

## 7. 当前项目资格与签名下单

### 7.1 资格语义不变

Reader复用既有三策略排序及真实首选，不重排 CONFIRMED优先：首选 OPTIONAL UNKNOWN拒绝本项目，即使低候选 CONFIRMED也不替换。REQUIRED事实缺失／过期／UNKNOWN全淘汰时管理资格 UNKNOWN，保留原淘汰原因，不提升 eligible。不改 ERROR事实分类。固定价 REMOTE_ON_DEMAND在真实 UNSUPPORTED检查模式下可按结构资格 CONFIRMED，不伪造库存事实。

买家级目录聚合每个 ACTIVE／有效 PLT项目的真实首选；任一项目 CONFIRMED可 AVAILABLE，但最终只认证项目本次 CONFIRMED授权。无可用项目时按真实 projectId=null默认规则评估，不假造项目身份。

#### Task 2 实施接口与异常边界

- `CurrentSupplyEligibilityReader.readProject(RoutingInput)` 是独立 Spring 代理入口；调用者传入的 businessTime 不用于当前授权，Reader 首先普通读取买家实体建立新的 RR 视图，然后一次读取 `SupplyChainDatabaseClock`，以该库时钟重建路由输入。facts／配送地区保留原输入，不外呼补事实。返回不可变 `ProjectEligibility`，包含 projectId、资格、完整 RoutingSnapshot（未形成路由时为空）、原因、evaluatedAt、来源摘要及 nextFactBoundary。
- `readBuyer(Long buyerProductId)` 在同一个独立新 RR 内读取 BPD／买家／SKU和 AVAILABLE PLT，核对 PLT 当前版本引用及项目归属，只聚合 ACTIVE 项目；项目停用或 PLT REMOVED 是正常不可用。AVAILABLE PLT 的空／损坏版本引用、缺失项目或跨买家归属为技术失败。没有有效项目时仅评估 projectId=null 默认规则。聚合优先 CONFIRMED，其次 UNKNOWN，否则 UNAVAILABLE；保留全部项目结果，不能作为单项目授权凭据。Mapper 实际路径为 `tradecore/buyer/dal/mysql/ProjectListingMapper.java`。
- 来源摘要保留策略／版本／规则及原有完整候选快照；`RoutingSnapshot.nextFactBoundary` 收集未来的已发布策略切换／结束、当前 Offer 的开始／结束、参与检查事实的 validUntil 与 observedAt+maxAgeSeconds，以及存在配额的下一自然日边界。跨项目取最早未来边界，不加入每次滚动的投影 TTL，后续 Task 4 再加最大评估有效期。
- 未形成 RoutingSnapshot 的可恢复结果也保留已读取的未来策略边界：封闭缺条件异常携带可空 nextFactBoundary，Reader 原样带回；计划版本尚未生效或动态候选池为空时，不因提前返回丢掉已知恢复／失效时刻。
- 可恢复的缺条件仅由封闭 `SupplyQualificationUnavailableException.Reason` 表达：无活动策略、无生效版本、无已发布规则、无匹配启用规则、无动态候选，以及主体不活动／商品不匹配。Reader 只捕获这个异常并返回 UNAVAILABLE；它不是 UNKNOWN，也不捕获任意 IllegalStateException。已发布 FIXED／SPECIFIED 规则的空候选或缺供应商是损坏配置，而 AUTO+ALL 无 Offer 是正常缺供给。
- 路由只读 REQUIRED 参与 Reader 事务时，`noRollbackFor` 仅豁免 `SupplyQualificationUnavailableException`，避免已捕获的正常缺条件把聚合事务标为 rollback-only；所有技术异常维持原回滚规则。真实代理测试必须覆盖同一聚合中一个项目无匹配而另一个项目 CONFIRMED 的正常返回，不调整共享事务管理器。
- 已存在指针必须核对实体存在及归属：Offer→版本、版本→连接器版本→连接器、版本→库存来源、SKU→SPU、项目→买家；重复活动策略／重复生效版本、非法检查模式／范围、非对象或无法解析的连接器／履约 JSON 一律技术失败。连接器／库存／供应商停用、Offer过期／数量不匹配维持正常结构拒绝。不记录配置原文，不改发布事务／供应商外呼／旧RPC准入。本片只影响供应链进程路由读取与新增 Reader，不调整共享事务管理器、缓存、RPC 或八进程装配。

### 7.2 下单顺序

1. 验签及现有 buyer/project ACTIVE前置门禁保持。
2. 当前 Schema／目录解析前，按 projectId+buyerOrderNo读冻结回执身份；有可信格式2绑定用其 SKU／Schema规范化目标和计算指纹，无回执才读当前目录。
3. 原 claim当前锁比较指纹／版本；已受理同参直接回放，异参拒绝。并发首创按既有项目键竞争重探测。
4. 仅需执行的有效新尝试在首冻前准备：AUTO刷新；MANUAL检查真实商业发布／有效性，不改共享投影。准备检查实际项目资格，不拿买家聚合授权项目。
5. READY才沿原永久项目键首冻；目录未发布／暂停／退役或已明确供给不可用不创建新报价，保存真实可重试拒绝。缺价沿原 `STANDARD_PRICING_QUOTE_MISSING`及PROCESSING契约，不伪造拒绝落库；技术失败不伪装业务拒绝。
6. 最终当前锁核对 BPD/PLT、报价及Schema绑定；建单／报价消费／资金前新 Reader复核真实项目资格，仅 CONFIRMED通过，使用其 RoutingSnapshot持久化路由/BOrder，不再次原RR视图 route。
7. 原建单、报价消费、履约初始记录、路由、资金预留及 ACCEPTED回执保持同事务；任一失败不能留下部分副作用。

供给准入线性化点为最终新只读视图建立时刻；此前已提交停用必须看见，之后停用不强求等待订单提交。BPD/PLT当前锁持到订单提交。首冻前检查不替代最终检查，永久旧报价不重新定价。

## 8. 冻结事实回放与兼容边界

主体仍ACTIVE及认证有效时，已受理同参用冻结 SKU／Schema／请求编码计算格式2指纹，不因当前商品删除、Schema／价格变化、目录暂停或供给变化变成冲突。不重新解析当前 LST映射，不首冻、不刷新、不路由、不重复预留。

HTTP与RPC两门面在当前Schema之前取冻结身份；原请求编码参与比较，别名与BPD指向同商品但改编码仍异参。格式0／缺可信历史绑定沿原历史兼容错误，不从当前目录补造。

已ACCEPTED比较不重新要求当前Schema注册／目标校验通过，不依赖当前平台SKU或LST仍存在；使用冻结Schema code/version及原目标规范化规则算指纹后让claim当前锁裁决。不把“读取冻结Schema编号”误实现为再次校验当前Schema可用。非ACCEPTED续跑仍按冻结身份定位当前目录并检查绑定，不能为回放绕过新尝试准入。

PROCESSING／REJECTED_RETRYABLE续跑保持冻结不变量及原CAS，再执行当前准入。后台恢复不重开任何回执。主体停用后回放权限不改。

旧 RPC保留 AVAILABLE投影前置，不套用HTTP新事实分支；BPD/PLT移除是否统一限制MANUAL旧RPC仍是原独立待确认项，不纳入本期，不宣布停用语义统一。

## 9. 日志与配置约束

遵守 `docs/superpowers/specs/2026-10-04-logging-standardization-spec.md`。每次扫描新建 traceId／executionId，保存／恢复完整原MDC，不伪造来源；原生调度入口显式上下文包装，不假设Spring装饰器接管。不改变共享encoder或八进程协议。

提交确认后才记发布成功；worker继续上抛不打印堆栈。保存后降级终结在管理编排记录一次系统因果；后台入口可恢复异常按低基数错误类别有界限频，首次保留因果、持续计数汇总、恢复记录。ServiceException单行WARN无堆栈，无变化逐项DEBUG。耗时数值elapsedMs，不记凭据、目标或完整供应商报文。

不声称全系统目标结构化字段已落地；message摘要与顶层JSON分开验收。后台扫描默认启用，显式关闭时响应 automaticRecoveryEnabled=false。系统操作人无合法配置时真实报未就绪，不冒称能自动发布。

## 10. 测试、实施顺序与交付声明

按一期设计 §17运行供应链 Test、IntegrationTest、ContractTest、ArchitectureTest。集成门禁必须真实MySQL/Liquibase/Spring事务代理，不用mock锁序证明事务。

顺序：资格Reader → 单LST刷新／陈旧读侧 → 管理提交后编排 → 后台扫描 → 冻结身份／HTTP准备与最终准入 → 前端提示及真实主链联调。单片不宣称整链完成。

必验场景：

1. 原保存回滚不刷新；保存提交刷新失败保留保存事实；部分刷新失败不重跑保存；刷新提交不确定后重试不重复发布。
2. 同来源重复／并发不新增版本／游标；来源变化读当前；MANUAL不写、暂停不恢复、退役不复活，缺系统操作人不假发布。
3. 无用户操作补齐价格／Offer／路由后后台发布；供给失效／恢复自动更新；不恢复人工暂停。
4. 超过100条、首条持续失败、持续新增高ID、多实例、保存后未刷即退出、扫描中退出、重启、空页后新增：不永久遗漏／不重复商业副作用。
5. AUTO投影过期／来源不匹配RPC UNKNOWN及availableOnly排除；HTTP不拿旧AVAILABLE授权。
6. 当前项目首选UNKNOWN、低候选CONFIRMED不改选；其他项目通过不授权本项目；REQUIRED全缺失真实UNKNOWN拒绝。
7. 外层旧RR视图后供给停用提交，最终Reader仍见停用；最终视图后停用遵守线性化；SKU锁等待后见前序配额。
8. MANUAL PUBLISHED/UNKNOWN新HTTP凭真实CONFIRMED受理，旧RPC原拒绝；手工价／状态／投影不变。
9. 已受理同参在SKU/Schema/目录/价格/供给变化后回放；异参、项目隔离；永久报价／订单／路由／预留唯一；拒绝无新订单／消费／预留。
10. 真实管理Controller选品／定价→自动目录→签名HTTP→真实路由和预留金额，不SQL补价或AVAILABLE；只验收到受理，不冒称华昇卡密已交付。
11. 前端已发布、待就绪、部分失败、MANUAL／暂停／退役、扫描关闭及网络未确认；不重复保存、不新增被排除的列／按钮。
12. 后台trace不复用、MDC恢复、异常单边界、防风暴及适用日志真实配置验证。

联调／实际命令与结果维护 `docs/集成测试.md`，自动化、配置审阅及真实页面／供应商验证分开。当前新增行为全部 NOT_RUN；方向批准不等于完成、生产启用或真实供应商调用授权。
