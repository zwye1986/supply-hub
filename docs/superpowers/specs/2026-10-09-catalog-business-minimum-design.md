# 自动受理目录与项目当前准入最小业务设计

日期：2026-10-09。状态：用户已确认按本文实施；实施中，自动化、实际联调与部署结果分别记录，不代表已经完成或部署。

## 1. 目标与适用范围

复用当前 `0be390d` 的选品身份、通用定价、永久报价、真实路由与供应链事务能力，让真实页面选品/定价产生可下单目录，不通过SQL补LST、价格或AVAILABLE。

本设计替代 `docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md` §5～§7、§9中作为商业发布前置的B0消费、账本flush、ROOT/PAGE/LEAF、全量生产者和巡检方案。该文§1～§4身份/字段契约、§8项目资格与线性化、§10真实拒绝、§11生命周期、§12冻结回放的业务约束保留，以本文明确差异为准。

实际验收环境使用pro143已有供应链测试实例与原测试库；不新建远端MySQL/Redis/入口、不清库、不换数据源、不运行第二个共库后台实例。华昇履约接线是后续独立业务切片，不把本片目录通过描述为已交付卡密。

## 2. 实际基线与方案取舍

已核对真实实现与前端：

- `BuyerProductCommandService.addProducts`为事务外NEVER编排，真实代理worker负责整批选品、行内价格及AUTO DRAFT身份；只建立身份，未发布商业版本。
- `GenericBatchPricingService.saveBuyer`保留单事务整批价格写入及项目继承重建；不能在其持锁循环中调用LST publisher。
- `BuyerListingPublicationService`手工发布拒绝AUTO；AUTO恢复仍失败关闭。该保护不能临时删除换取首单。
- `ProjectStandardOrderCommandFacade`目前claim后直接首冻；`FulfillmentAcceptanceLifecycle`最终受理仍依赖旧AVAILABLE投影，并在原受理事务普通读路由。
- 实际前端位于 `/Users/mac/Documents/codeSpace/supplychainhubweb`，分销选品调用 `createBuyerProducts`，定价调用 `saveBuyerProductPricing`，其返回分别为 `string[]` 和 `boolean`；页面只有“添加成功/改价成功”，没有目录结果。
- 060已定义AUTO来源、商业摘要及投影有效期字段，无需为本片新增通用任务表；061身份账本实现保持原边界，不调用仍失败关闭的 `appendLocalIdempotent`。
- pro143只读数据库检查：63条已执行changeSet，末尾059；0条LST/履约回执/资产/Timer，6条历史总订单，12条status=0 Outbox，尚无timer_schedule_identity。后续发布必须核060/061正式增量；旧052已执行，不重复清理历史订单。

选择：

1. **采用保存后独立刷新＋请求前补准备＋最终当前资格读取。** 不增加事件平台；既有保存接口兼容，真实结果经读模型和显式重算可见。代价是未被触发的目录投影不承诺后台实时收敛。
2. 保存事务内同步发布不采用：既有SKU/定价→LST与受理LST→SKU可成环，也会把可售依赖故障变成选品保存回滚。
3. 完整可靠事件/巡检平台不采用：超出首单业务需要，重开已撤出的B0；不能为本片全局开启消费已有12条Outbox。

## 3. 入口与提交后的真实结果

新增catalog管理应用编排服务，入口NEVER，先调用现有真实代理完成领域保存，返回已提交的业务结果后逐个调用独立刷新服务。Controller仅委派，不自行控制事务或访问DAL。

本片自动触发仅覆盖真实分销选品与通用分销定价两个入口：

- `POST /supply-chain/buyers/{buyerCode}/products`：保留原 `CommonResult<List<String>>`，原整批选品/定价/身份仍全成功或全回滚，返回编码只来自最终成功worker。
- `PUT /supply-chain/buyers/{buyerCode}/products/pricing`：保留原 `CommonResult<Boolean>`，原定价整批事务不拆行提交。领域提交后按实际受影响BPD刷新。
- 不把该编排塞入可被嵌套调用的 `saveBuyer` 内部；既有初始化、回算等事务调用者保持原行为。本期其余供给/路由/项目/主数据写点不新增全范围刷新生产者，变化由请求准备和显式管理重算覆盖。

领域保存失败：沿既有错误传播，全部回滚，不启动刷新。领域保存成功而某条刷新技术失败：该条刷新事务全部回滚，其余条仍可继续；保存响应仍代表保存已提交，不能抛成“添加/改价失败”诱使客户端重发非幂等选品。事务外编排作为降级终结边界记录一次原因与BPD/LST定位，既有上抛日志不重复堆栈；不写假AVAILABLE或续期，不宣称刷新成功。

保存返回仅保证关联/定价提交，不是可下单证明。前端保存提示改为“商品已保存，请查看目录状态”；随后刷新列表显示独立目录状态。网络响应丢失仍是既有保存接口不确定性，不新增静默重发逻辑。

## 4. 单目录刷新与幂等发布

`BuyerSelectionCatalogLifecycle.refreshListing`经独立Spring bean代理开启供应链短事务；事务外编排不得self-invocation。一次只刷新一条LST，不跨多LST持锁。身份补关联继续复用现有ensureIdentity及其整批竞争协议，完成后再独立刷新。

固定顺序为LST当前锁→BPD当前锁→BuyerPricing当前锁→商业版本/投影/游标写入。不得取SKU/PLT/项目/Offer/供应商行锁；供给读取走§5独立只读事务，不能等待外层持有的写锁。

1. 校验AUTO不可变buyer/SKU/BPD绑定；MANUAL返回MANUAL且不写价格、生命周期或共享availability。
2. RETIRED返回终态；SUSPENDED返回人工暂停，不发布或恢复。人工AUTO恢复继续沿现有失败关闭保护，本片不引入未确认的新恢复接口；不把暂停/退役当缺配置可自动恢复。
3. 锁BPD和真实当前BuyerPricing：身份及来源版本均READY，归属一致，分销价非空非负、CNY；正常缺价返回DEFERRED_PRICE，损坏引用/金额矛盾是系统错误，不能降级为缺配置。
4. 在独立新视图评估真实供给，DRAFT不能因自身尚未发布而自我否决。保留买家级汇总：有ACTIVE项目AVAILABLE选品时，至少一个项目真实首选CONFIRMED才可发布；尚无可用项目选品时按projectId=null的实际默认路由评估。无CONFIRMED则UNKNOWN或UNAVAILABLE并保留原因，不另选同项目较低候选凑可用。
5. DRAFT条件通过才生成真实商业版本并转PUBLISHED；PUBLISHED条件失效保持生命周期与历史current，更新不安全投影。商业价仅等于READY分销价快照，订单金额仍是永久项目通用报价。
6. 摘要含真实来源ID、分销价、币种、SKU名称、数量1及商业有效期；相同当前来源/内容沿用已冻结validFrom，不因刷新时刻变化发新版本。实际新来源/内容才生成新版本。迟到调用总是读当前来源，不接受旧事件价格。版本指针/投影CAS、商业版本及目录游标同事务提交，重复评估无新UPSERT/游标风暴。
7. 投影basisJson只存来源及路由引用/原因，不含秘密、目标或完整报文。evaluatedAt来自库时钟；validUntil不晚于已知生效/失效边界、事实过期、下一自然日和30秒最大评估间隔的最早值。不能因本片不做Timer就写无限有效AVAILABLE。

发布审计使用真实管理操作人；HTTP补准备仅可用可信来源版本的publishedBy，来源缺失报明确发布审计配置错误，不填0或任意运营人，不让项目伪造管理操作人。

实施细化：同内容判断使用当前商业版本的真实字段及摘要，含其已冻结有效期；新来源或展示内容生成的新版本以本次库时钟作为validFrom，不沿用旧版本起点。PUBLISHED缺current或current字段/摘要损坏为系统错误。独立Reader返回已知下一评估边界：同策略已发布版本的未来起止、所有实际候选已发布Offer当前版本的未来起止均参与最小值（包括当前尚未生效的候选），不只读取首选的结束时间。当前Reader未接入远程事实采集，事实输入为空，不能声称已覆盖外部库存时效；以后传入事实时须同时纳入validUntil和maxAge边界。

自动路径同事务写真实 `BuyerCatalogChange` 可拉取游标，不追加尚无真实消费者的 `buyer-catalog-change-fanout` 或目录刷新Outbox，不把本地游标当外部通知成功。原MANUAL路径及历史Outbox不改、不删除、不改状态；本片不启用任何新Outbox/Timer消费者。

## 5. 当前供给Reader与错误分类

`CurrentSupplyEligibilityReader`经真实Spring代理使用供应链REQUIRES_NEW/readOnly/REPEATABLE_READ，建立独立当前一致性视图；调用既有真实 `SupplierRoutingApplicationService.route`加入该视图，不从外层accept RR快照读取供给。不外呼、不FOR UPDATE、不查询system/member表。

返回不可变资格、稳定原因、库时钟评估时刻及真实RoutingSnapshot。核商品有效、buyer/project归属与状态，版本/Offer/connector/inventory来源完整性和实际执行配置资格；不得把不存在的远程库存采集写成已实现能力。

执行资格复用PublishedConnectorConfigurationResolver、AdapterMetadataRegistry和封闭Schema校验，核首选Offer主能力与SKU、执行画像需要的查询/回调能力，并仅调用OperationExecutorPort.supports；不解析秘密、不execute。已发布配置非法仍上抛；有效配置但本进程无executor返回EXECUTOR_NOT_SUPPORTED/UNKNOWN，不改选后备。买家汇总先验证全部有效项目选品归属，再逐项目裁决，返回首个CONFIRMED并合并所有读到的未来时间边界，避免首个可用项目掩盖后续损坏引用。

- 真实首选eligible且availabilityClass=CONFIRMED才准入。
- 真实首选OPTIONAL UNKNOWN即项目UNKNOWN，不跳过它选择较低CONFIRMED候选。
- REQUIRED事实缺失/过期/UNKNOWN淘汰时，保持内核候选结果及原因，管理资格UNKNOWN；明确停用、缺货、超配额等为UNAVAILABLE。
- FIXED/UNSUPPORTED/REMOTE_ON_DEMAND结构资格可CONFIRMED，这是“无需该事实检查”，不代表实际远程库存充足。

对正常配置尚缺设置封闭业务缺条件异常/结果，例如无有效策略、无匹配规则、无有效供给。重复已发布策略、已指向版本缺失/错归属、非法JSON/枚举与SQL故障继续系统失败；不能泛catch IllegalStateException全部当NO_ROUTABLE_SUPPLIER。本片在实际路由读点精确分类，不通过错误消息文本判断。已有已发布Offer的坏引用不得由resolveOffer静默filter成“无供给”。

## 6. HTTP请求准备、最终准入与冻结回放

保留HTTP现有字段、签名原字节、quantity=1、项目永久键、既有CAS重开、账户及费用契约。RPC独立旧投影准入不扩大，旧RPC停用语义待确认项不阻断本片。

### 6.1 请求准备

认证及buyer/project ACTIVE前置不变。其后先通过border应用只读服务查项目永久键的冻结受理身份，再构造指纹；格式2且绑定完整的既有回执用冻结SKU/Schema，不能先解析当前Schema破坏同参回放。HTTP与RPC共用冻结身份读取，Facade不访问DAL。格式0/无可信冻结来源保持失败关闭，不借当前目录补历史身份。

claim不要求先刷新。已ACCEPTED同参回放直接返回原单，跳过目录发布、首冻、路由和资金；异参仍幂等冲突。PROCESSING或合法CAS续跑必须重新当前准入，不因有回执放行。

实施细化：冻结身份只读服务仅返回回执中的不可变SKU/Schema与回执状态，不读取现行目录。格式0或不完整冻结绑定用既有 `IDEMPOTENCY_CONFLICT` 稳定失败关闭，不补历史身份。已ACCEPTED分支只做冻结Schema目标规范化与指纹计算，不再执行Schema校验或加密；claim仍为最终状态/指纹裁决者。未成功回执续跑保留冻结Schema指纹，claim确认执行后才解析当前CatalogIdentity；该身份只能定位本次锁定目录，必须与冻结SKU一致，不能用现行Schema替换原不变量。并发首创期间读服务未看到回执时仍由原claim竞争协议裁决，不引入盲回放。

claim确认本次需受理且数量/目标合法后，首冻前执行一次目录准备：AUTO调用同一独立刷新，MANUAL保留商业版本并按当前供给核验，不更新其共享UNKNOWN投影。缺目录或暂停/退役保存稳定可重试目录拒绝；缺供给保存实际可重试原因。真实缺价优先沿 `STANDARD_PRICING_QUOTE_MISSING`，未首冻时保持PROCESSING，不伪造已存拒绝或假版本。未准备好不创建新报价；已ISSUED永久报价不删、不改价、不消费。

### 6.2 最终事务

保留receipt→LST→SKU→quote→PLT→BPD既有锁前缀及最终当前项目价格准入。HTTP不再以AUTO/MANUAL共享availability=AVAILABLE作为前置授权，但仍要求真实商业版本PUBLISHED/有效、SKU/Schema匹配、AUTO来源完整及当前READY分销价。

在订单、资金预留及报价消费前再调用§5Reader，用本次真实认证项目评估；CONFIRMED才写订单，RoutingSnapshot直接用于路由持久化/BOrder，不在accept旧视图再次route。旧RPC保留原AVAILABLE前置和行为。

供给准入线性化点为最终Reader新视图建立时刻：此前已提交停用必须拒绝；之后停用影响下一新尝试，本单使用该时刻合法候选版本，不承诺停用事务等待订单提交。BPD/PLT移除仍由原当前锁序保护持到受理提交。同SKU锁保证读到前序已提交配额占用。

已成功订单金额和履约保持冻结，不因当前改价/目录/Schema/路由变化重新首冻、重路由或扣款。buyer/project停用仍沿现有认证策略拒绝，不扩“停用主体仍可回放”。

## 7. 管理可见性与显式重算

分销商品列表追加只读字段：canonicalBuyerSkuCode=BPD、acceptanceListingNo、managementMode、publicationState、availabilityStatus、catalogRefreshReason、sourceBuyerPricingVersionId、lastEvaluatedAt、nextEvaluateAt。项目页面下单编码使用BPD，不冒用PLT。字段沿既有060 DDL语义，不允许客户端回写状态/价格。

GET只读，不发布。AUTO投影过期/当前定价来源不匹配时显示UNKNOWN/待重新评估，不显示当前可下单；MANUAL沿现有读契约。底层投影有新业务时间边界时读侧同时检查边界，本期不承诺主动供给变化后后台即时更新。管理展示是目录层投影，不是每个项目的实时授权。

新增 `POST /supply-chain/buyers/{buyerCode}/products/{buyerProductCode}/acceptance-catalog/recalculate`，方法级 `@PreAuthorize` 复用BUYER_UPDATE，校验归属，必要时原协议补身份，再同步刷新并返回该次真实结果。不是“排队已受理”，无秘密或价格/状态参数；技术失败返回错误且无假结果，原保存不回滚。其他买家不可重算，MANUAL/暂停/退役返回明确不自动管理结果。

实施细化：提交后刷新与显式重算均先普通读定位该 BPD 对应 buyer/SKU 的既有 LST，校验 AUTO 不可变绑定；仅无目录时调用原 ensureIdentity 代理。不让既有 RETIRED 再进入补身份协议，也不创建第二条目录。显式重算保留跨 buyer 拒绝；保存后该定位/补身份/刷新任一步失败仅终结该条降级并继续下一条。

前端只改真实distribution API/types、分销商品列表/选品提示以及必要项目下单编码展示，不改全局mock开关，不碰既存Login副本。显示“已保存”与“已发布/待价格/待供给/人工暂停/终态”区别，提供重算操作。原选品/定价返回类型不破坏，旧调用者仍可工作。

2026-10-12 列表展示细化：分销商品行的“重算”按钮及操作反馈统一改称“刷新状态”，仍调用原受理目录同步重算接口；不是仅重新 GET，条件满足时仍会发布或更新商业版本。SKU规格单元格分两行展示规格名称与 `platformSkuCode`，缺失值显示 `-`；适当增加规格列与操作列宽，保持其余字段与发布/供给状态判定不变。`UNKNOWN` 的“待重新评估”表示管理端尚不能确认当前供给状态，不等同于未发布或确定不可用；`SOURCE_CHANGED` 应表示真实定价来源变化，不能把投影依据递增计数当作定价版本ID。本次仅修改前端展示，不修改后端来源判定。

目录RPC AUTO可用性过滤与返回同时应用投影过期/来源失配规则，避免selectPage过滤与total不一致；普通GET不创建版本。MANUAL的原RPC可用性与商业字段不改。

## 8. 受影响文件与验证门禁

后端主要范围：catalog新管理编排/refresh/reader/读模型，BuyerAdminController两保存入口与重算端点，BuyerProductQueryService，BuyerCatalogQueryService及复杂分页XML；路由应用层精确缺条件/坏引用分类；border冻结身份读、请求准备与FulfillmentAcceptanceLifecycle最终HTTP分支；HTTP/RPC Facade及限频协调器保持可信身份传递。

不改共享Hikari预算、通用Timer/Outbox/Inbox/账本核心、池参数、RPC扫描、其它进程或通用通知平台。删除旧迁移启动入口仅作为另行明确小清理，不借本片改现有任务处理规则。

先红后绿验证：

- 纯测：真实READY来源、缺价、未知/明确无供给、首选UNKNOWN不改选、损坏引用不吞、摘要幂等、manual/人工暂停/退役；已冻结SKU/Schema回放与异参。
- 真实MySQL/Liquibase：原领域整批回滚；提交后刷新失败原保存保留、refresh无残留；并发/迟到刷新不倒退；真实代理新RR视图看到停用；锁序/同SKU配额与BPD/PLT停用并发；商业版本/投影/游标原子；MANUAL PUBLISHED/UNKNOWN仅HTTP凭当前CONFIRMED可受理、共享UNKNOWN/旧RPC不变。
- HTTP/RPC契约：保存string[]/boolean兼容、读字段/total与过滤、重算RBAC/跨buyer拒绝、DRAFT NULL版本/CAS、缺价PROCESSING、不可用不创建新报价；同号同参/异参/跨项目与配置变化冻结回放，无重复订单/报价消费/资金。
- 架构：Facade无DAL、routing.domain纯域、border无账户/账本DAL、管理映射均@PreAuthorize、供应链不访问外部模块表。
- 前端：实际组件对接、保存提示不冒称可下单、读状态与重算失败反馈、BPD/PLT分清；使用该仓实际脚本验证。

上述自动化只证明本片共用商业机制，不以QNM代表华昇目标。华昇metadata/executor、协议挡板闭环及已授权测试交易/资产/资金仍需后续切片独立验收。联调维护 `docs/集成测试.md` §8.13，当前不预填测试通过。

## 9. 发布与完成边界

### 9.1 2026-10-10 评审修复约束

本次仅修复评审六项缺口，不改变保存契约、永久报价金额或旧 RPC 准入，不新增消费者、部署或提交。

- AUTO 首冻前刷新仍使用买家级汇总发布，但刷新成功后必须再按本次可信 projectId 读取供给；其它项目的 CONFIRMED 不能代替本项目资格。MANUAL 同样在首冻前用数据库时间核验真实商业版本有效期。未就绪不进入 freezeForProject。
- 准备拒绝的 listingVersionId 只允许来自 sc_buyer_listing_version 的真实 current；DRAFT 无商业版本保持 NULL，绝不使用 sourceBuyerPricingVersionId。已指向不存在/错归属版本为系统错误。
- 最终 HTTP 在原 receipt→LST→SKU→quote→PLT→BPD 锁前缀之后，追加 BuyerPricing 当前锁；复用自动目录生命周期的商业摘要和来源校验。定价版本以当前锁定读取得，避免外层早期 RR 视图看不到随后提交的版本。来源及商业快照损坏继续系统失败；当前来源非 READY 或已变更则稳定可重试 LISTING_VERSION_EXPIRED，不重新冻结永久报价，锁持至受理提交。MANUAL 和旧 RPC 不调用此 AUTO 守卫。
  - 当前来源变化时仍须核验商业版冻结来源的真实存在、定价归属与金额一致，不能只靠摘要把悬空历史引用降为可重试缺价；来源版本为不可变记录，当前/冻结两条按当前读取得，不新增写行为。
- Reader 内 route 的 REQUIRED 代理对 `RoutingConfigurationMissingException` 不标记参与事务回滚：该异常是可由 Reader 转成 UNKNOWN 的正常缺配置事实；否则外层即使捕获仍在提交时抛 UnexpectedRollbackException。只豁免这一精确只读异常，损坏引用等系统异常继续回滚。
- 前端重算契约与 RefreshResult 字段完全一致；根据 publicationState、availabilityStatus、reason 展示成功或未就绪，不以缺失 rejectionReason 默认为成功。提供无网络的组件回归测试验证真实响应字段及失败反馈。
- 原有真实受理、HTTP 闭环和依赖这些配置的套件同步装配新服务。保留原业务断言，不用 mock 替代新 HTTP 供给/自动定价门禁；旧 RPC 专属 fixture 可只装配未调用的依赖，但不能作为 HTTP 验收证据。
- 原 Task 6 完成状态先撤回；本次以真实运行范围、红绿日志及全供应链纯测/契约、受影响 MySQL 用例重新登记证据，未执行范围不勾选通过。

测试环境只更新pro143现有供应链实例及必要实际前端，不顺带部署system/infra/member/gateway或生产。启动前对比正式Liquibase checksum及pending changeSet，确认仅预期060/061或后续获准增量；不得clearCheckSums、重复052、SQL清业务或修改旧changeSet。启动成功不等于交易通过。

本片不改变远端Outbox/Timer开启状态，12条历史Outbox不因目录发布被批量消费。华昇履约需要的worker、恢复/回调及配置启用在下一设计中依据实际协议处理，不能用本片停止事件平台建设推断无需订单后台执行。

完成判据：实际Controller/页面新选品＋定价，在真实供给具备时自动商业发布；未就绪有稳定原因；合法项目HTTP能进入真实订单/资金预留，停用供给的新单拒绝，成功同参按冻结回放。未完成华昇资产交付时不宣称全链通过。
