# 页面选品与受理目录桥接实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development。实施者每次一名，先规格审查再质量审查；只读分析可并行。用户明确在当前仓库dev分支开发，不新建工作树。

目标：页面BPD编号成为订单buyerSkuCode，真实管理选品建立唯一受理身份，条件满足时自动发布真实商业快照，最终按实际项目当前供给受理；之后用全新数据完成HUA_SHENG固定价卡密订单及资产、资金验收。不得用QNM或SQL补目录替代目标。

详细设计：`docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md`。用户已确认BPD编码、新选品自动发布、旧手工目录保留原商业管理；旧RPC移除联动仍待回答，该项单独隔离。设计须先修复独立审查问题，才开始对应实现。

## Task A：唯一受理身份与双编码解析

文件范围（实现时按实际调用链核对）：catalog应用层新增Resolver/身份确保服务与封闭管理来源，BuyerProductCommandService、ProductCatalogCommandService、BuyerListingPublicationService；BuyerProductMapper/BuyerListingMapper及相关DO；FulfillmentAcceptanceLifecycle三处身份读取和NULL尝试版本、FulfillmentAcceptanceReceiptMapper的NULL CAS重开；HTTP/RPC Facade和限频协调器仅传播事务外定位的可信应用身份快照；供应链错误码；下一独立Liquibase变更集和YAML；内部补关联契约及测试。Facade仍不接DAL，不改金额/资金/履约，不注册HUA_SHENG。

- [x] 设计审查问题先修订详细规格，明确本片MANUAL-only发布保护、AUTO人工恢复失败关闭、唯一冲突结束事务后重探测，以及刷新Outbox仅在具有真实消费者的后续片启用。增量补齐NULL CAS和accept事务外身份快照/RR时序，独立复审通过；只批准A规格，不代表实现门禁通过。
- [x] RED：真实双路径Resolver测试，新BPD/旧手工编码命中唯一LST，跨买家/不同SKU碰撞拒绝，不规范化原请求编码，REMOVED不删除身份；真实选品Controller创建DRAFT/UNKNOWN，无商业版本；兼容发布入口拒绝AUTO外部价格；DRAFT NULL尝试版本不伪造1、不抛缺版本500，非空损坏引用仍抛系统异常。根已回读01/03/04/05/07/08/09/10/13/14/24原日志并分类；02编译错误不计RED、09仅调用顺序单测不证明真实RR，四组新增保护直接GREEN不伪造RED，详见集成测试§7.85。
- [x] 按详细设计§3/§4落身份迁移、DO/DTO COMMENT、唯一创建与旧manual复用；不锁已有LST改状态，不把PLT主键写履约LST列。不改历史行编码、价格、状态或已成功回执。
- [x] Lifecycle的Schema、claim和最终锁定都委派应用Resolver；身份在最终accept事务之外定位并经可信应用快照传入，最终按LST ID当前读锁定并重验真实归属，保持receipt→LST→SKU前缀，涉及BPD的普通关联读取在SKU锁后执行。不新增BPD→已有LST反向锁。确定性MySQL测试验证后一请求等待SKU后可见前序已提交占额，覆盖BPD与旧编码。
- [x] NULL尝试版本贯穿ReceiptMapper：首次拒绝→正确CAS重开→再次稳定拒绝及修复后续跑，错误CAS不重开；不拆箱NULL、不补1。
- [x] 真实MySQL/Liquibase/Mapper/事务验证：新旧数据升级、CHECK和unique、页面与旧创建竞争、多个页面竞争、任一关联/定价写失败原事务回滚、唯一竞争仅专用异常及明确新事务重探测，不吞任意SQL异常。
- [x] 实现规格复审缺口1：MANUAL复用期间跨表BPD保留编码并发负例（大小写/trim/异SKU），以及不同连接字符集的真实Mapper比较。
- [x] 实现规格复审缺口2：真实Controller两行行内定价第二行失败，BPD/L2/继承及行内定价身份版本/LST/投影整批无残留；非身份1062及其它SQL故障不进probe或重试。
- [x] 实现规格复审缺口3：AUTO移除/复活身份不变、RETIRED复活/补关联失败且整批回滚、真实MANUAL PUBLISHED/SUSPENDED商业保护、真实旧status Controller的AUTO恢复负例及无副作用。
- [x] 实现规格复审缺口4：060重复BPD关联及商业来源摘要唯一键实际拒绝；HTTP修复后续跑中永久报价PLT listing_id与履约LST listing_id分别核验。
- [x] 供应链相关纯测试/HTTP契约、架构及全量Surefire，相关真实MySQL Failsafe通过；更新迁移基线计数应以实际模式为准，不机械加1。JDK21，供应链测试不跳过。39补正后XML实测：Surefire216类2082项、Failsafe9类57项，合计2139全绿；39候选与原仓/受测副本哈希一致。运行门禁、规格、质量与最终验收分别核验，不混为同一结论。
- [x] 质量首审补正1：BPD唯一键转换保留原BUYER_PRODUCT_DUPLICATE、实际DuplicateKeyException及SQL cause；35真实MySQL因果丢失RED、36同用例GREEN，38/39再次核验整批回滚且不probe/重试。
- [x] 质量首审补正2：Writer、probe、专用竞争异常的重要中文注释补本文档实际路径/章节；probe明确worker代理完全回滚后调用、只探测不继续写入。根逐一核对实际章节与补正增量，不改变事务及身份协议。
- [x] 质量首审补正3：Lifecycle两处NULL/完整性守卫链接指向实际§10“DRAFT稳定响应与管理端可见结果”，不指向不存在的章节。
- [x] 质量首审补正4：独立probe在查询前记录buyerId/platformSkuId/attemptedCode的结构化debug入口，不逐层打印堆栈或增加高频info日志。37日志断言RED、38/39 GREEN；该单测只证明诊断字段/顺序，不冒充事务或RR证据。
- [x] 质量补正后重验最新候选：定向RED/GREEN及完整门禁已经根核验（38定向58项、39完整2139项全绿，39候选哈希一致）；新的增量规格确认deleg_606a6273与另一独立质量复审deleg_31f45348均通过。旧33通过不冒充修复后生产快照已受测。P3不可达副本清理不纳入本轮，明确为非阻断后续建议。
- [x] 自审、独立规格、独立完整质量复审及根最终验收完成；docs/集成测试.md记录原始命令、计数、RED/失败与范围。根核审查路径集合、全部最终哈希、原XML与暂存/工作区白名单后关闭A。当前仅授权开发推进，不自动提交、推送或部署；提交须用户另行授权。

**当前证据状态（A已最终验收）：** 四组规格补测缺口及质量首审4项阻断已闭合。38定向58项、39完整2139项均全绿；39候选（17新增）的原仓/受测副本/40冻结审查输入哈希一致。独立实现规格增量确认`deleg_606a6273`与独立完整质量复审`deleg_31f45348`均通过；security_concerns及logic_errors为空，唯一P3不可达副本清理非阻断且不扩大本轮范围。根将运行、规格、质量与最终验收分别归档，终检审查集合、源码、完整原XML及精确Git状态后关闭A。原始各轮证据、实际RED及编译/fixture/同步/限额/元数据解析失败分类见`docs/集成测试.md` §7.85，不将局部GREEN或旧33快照冒充最终生产增量已受测。

**独立审查与根最终验收：** 规格增量确认重审7个增量文件，其余32个文件核字节后复用此前批准；完整质量复审另审全部39候选diff、17新增全文及直接调用链，不以规格或测试通过代替代码判断。根对两次送达结论的reviewed_files分别核唯一路径和冻结集合一致，核源字节、225原XML、固定HEAD及空暂存区；终检工作区仅含39候选及3份本任务文档。仓外证据`42-parent-spec-confirmation-receipt.json`和`43-parent-quality-review-receipt.json`明确为完成消息的根摘要归档，不冒充自动提取的完整原JSON；`44-parent-task-a-final-acceptance.json`及`45-parent-final-git-scope-verification.json`记录根验收与精确状态核验。代码仍未提交、推送或部署。

本片只能宣告身份衔接，不能宣告目录已发布/AVAILABLE或全配置已闭环。首冻前目录准备、当前供给和冻结Schema回放属于后续片；尚无这些行为时不得扩写验收结论。

## Task B：真实商业镜像与可用性

**当前推进范围：补核失败／切片3重开。** 父`slice3-reacceptance/parent-reacceptance-verdict.json`已将旧`07-slice3-final-acceptance.json`失效；规格、质量与完整运行门禁均failed，不得恢复下一B0片或B1。完整verify真实exit1，270 XML/2321执行/6failure/21error/0skip；API额外7 XML/45执行全0不能替代完整门禁，两类BeforeAll使25方法未执行。精确F01–F13映射、分片依赖/有限清单、真实TDD正反例、运行失败归因及A39保护边界见[切片3重开修复实施计划](2026-10-05-b0-slice3-reopened-repair.md)。本轮仅文档候选，父复核后逐片实施，不自行宣称修复／验收；下一唯一候选片R3-01只修比较与深不可变输入，真实映射仍另片必验。

**以下为历史设计阶段记录，不代表当前交付状态：** A原验收不改；切片1/2已有运行记录见§7.85，本次不重新验收。B0切片3V4A先修设计并冻结独立预审输入：真实无retry三事务反例否定排他upsert回滚后无死锁，历史01/06/10及批准原报告不覆盖、不删除。用户选择不协调表/哨兵、纯本地完整worker首次加最多一次重跑、维护窗口同版本切换仅本地验证；本次按用户批准补正原预审deleg_bbb25a07的R01/R02，仅修订并送新独立预审，不批准实施或参数生效。共享nano起点15s不reset、C1000ms仅清理预留、统一R≥3500ms准入/退避前R-b≥3500及后重检；raw.getConnection(1500L)仅有限排队，借后重算E=min(commandDeadline-C,acquiredNano+6s)，事务/每Statement按floor及已有正限制收紧，不足1秒拒绝，MyBatis flush后与commit前guard、rollback不省略。唯一现有Druid池由原bean名稳定budget DataSource包装，manager/SqlSessionFactory/clock同wrapper key、非InfrastructureProxy/TransactionAware代理；非预算透明委派。唯一全局增量是独立int配置aifuli.supply-chain.datasource.connect-timeout-millis默认且仅1500，build后init前setConnectTimeout，用户接受HTTP/Liquibase/clock/六旧Handler新建连接及冷启动影响但它们不接retry。其它maxWait/maxActive/global socket不改、不另建池；真实Driver.connect/HostInfo优先级与冲突形式拒绝、Druid前提校验与恢复失败disable+discard、缓存/资源清理按B0§7.2.1冻结，不承诺TCP以外或15s硬结束。本次不改生产Java/XML/DDL/测试、不运行测试、不自行批准实施。run-04隔离12项全0仅作为证据，真实代理/Druid、第二次死锁及提交不确定等全门禁尚未完成。A商业生产者/定价RPC、实际B消费者、六旧Handler及引擎隔离，新增业务语义列未决。B1提案及UNKNOWN已确认项/D隔离状态不变。

**V4A最新idle例外设计修订（用户批准仅设计修订/预审；未独立预审、未实施）：** R02-ALLOWLIST、R01-PREPARATION、R02-DISCARD-FAILURE的来源仅为父任务提供的完整三项摘要，不冒称归档审查原JSON。B0§7.2.1列可编码有效值集合：retryCount=0、createScheduler=null、空filter、borrow=false/idle=true/return=false及原验证设置、精确默认MySQL checker/validation组合、defaultAutoCommit=true/removeAbandoned=false、单host直接Connector9.7标准socket；配置不符在预算入口失败关闭、不改共享属性。用户批准保留共享testWhileIdle=true，精确默认MySqlValidConnectionChecker及usePingMethod=true/validationQuery=null/validationQueryTimeout=-1旧路径明确允许，不因idle验证存在而拒绝；其它允许集合不放宽，不关闭idle验证、不改checker/global validation。实际运行配置及传输范围仍待验，默认匹配不等于生产可启用。默认checker实际发`/* ping */ SELECT 1`，默认validation timeout=-1；原idle验证失败discard/continue重借是获准非硬截止例外，raw.getConnection(1500L)仅每轮正数队列等待，累计borrow无有限上界，可超过15s或不能及时返回；不重置nano15s。raw返回后第一步检查cancel/deadline并清理late连接，不得暴露manager/启动worker，禁止Future借线程。原pool内部discard无强关闭证明例外只限既有验证路径，不扩成新预算污染/恢复失败/late连接清理宽松；后者继续严格R02.6协议。raw返回后manager看见连接前保存并安装network/socket，覆盖事务prepare/autoCommit、server prepare返回前网络、metadata/session/setup及封闭非递归SESSION control，委派前后重检；Statement记忆最小正timeout、缓存归还恢复，MyBatis flush后及commit前双guard保持。提前保存holder/physical/实际socket→disable阻止recycle→直接捕获Socket.close异常→同步abortInternal→仅一次discard(savedHolder)，禁止用Druid计数/emptySignal/driver isClosed证明物理关闭；源码中discard与abort链都吞close异常，直接标准socket关闭只证明本地传输资源，不保证服务端立即断线。恢复失败即清理失败禁retry，随后丢弃成功也不解禁；Connector/J9.7默认sslMode=PREFERRED，标准socketFactory不等于非TLS；不关闭TLS、不改现非预算行为。未知socket/未审TLS资源覆盖无法证明时该预算实现不得启用；下一独立预审可按明确受控supported transport范围评价，运行未知与TLS支持仍待验，不假实现TLS支持、不声称生产可上线；需改池/驱动内部或扩大支持时另审另授权。三阻断必需负例按B0§9逐项执行，不替代成TODO、不冒称已验证。新冻结`retry-idle-exception-design-review-input.json`及`retry-idle-exception-parent-design-audit.json`在仓外task-b0-ledger-evidence；原retry-resource输入SHA256 `c6bf3832cb77265e71ebd32c6e97afdb8d5d501194bf9d8b034e04edc7cd15c5`及历史retry-budget输入SHA256 `28e380ba361e9afd948337f461c94d1119f5af9b8f91e9883ef06c45eb2607e2`逐字保留。本轮仅四文档patch与证据冻结，生产Java/XML/DDL/测试未改，未构建/测试/DB或网络，未批准实施。

### B0：可靠消费技术前置（先审后实施）

- [x] 切片3补核失败及旧07失效已记录；仅完成重开状态／计划同步，不表示实现、TDD或验收完成。
- [ ] 父复核[重开专用计划](2026-10-05-b0-slice3-reopened-repair.md)与A39/迁移授权；逐片唯一实施者，先R3-01有限比较片，不跨片泛化预算改造。
- [ ] 按专用计划闭合F01–F13及完整§7.2/§7.2.1/§9必需门禁，逐项归因完整门禁旧失败／fixture／新增回归；原无retry测试变更不计原RED→GREEN。
- [ ] 最新最终候选完整门禁、独立规格与另一质量审查及父验收全部通过后，才允许讨论下一B0片/B1；目前保持failed/reopened。

- [x] 实际Mapper/引擎/服务、最终008/009/015/022迁移核对，先落B0设计及本文源Outbox/Timer锁前缀；草案不是审查批准。
- [x] 独立实施前首审已返回不通过，先补B0-01/B0-02具体协议及非阻断明确化；本项只表示文档补正，不表示复审批准或运行GREEN。
- [x] 06修订完整复审已返回不通过，原两阻断闭合、1项B0-R01及5建议已完整归档；第二次文档补正排他SQL/首次绑定/Inbox同类路径及三事务门禁，本项不是复审批准或运行GREEN。
- [x] 第二次修订的10冻结输入已获独立完整实施前规格批准；父核原JSON/哈希/状态后归档11，仅技术可实施性通过，未验收B0。
- [ ] 垂直TDD修复领取/宕机回收/预算/死信、启动/续租/完成CAS与真实审计、独立心跳及token级清理；仅供应链，不改变外部履约契约。
- [ ] 首个实施切片：真实代理/Mapper/MySQL下过期10/20恢复与持久预算耗尽收敛，分别保留每条RED→GREEN、原XML与受测哈希；先不改启动/续租/完成、Inbox/账本、引擎线程池或真实目录业务。
- [ ] 真实代理事务型Outbox/Timer执行器、提交前租约栅栏、Inbox摘要/真实version及30→10/90保护，纯本地夹具证明全部效果同提交；兼容旧Handler明确至少一次限制。
- [ ] V4A切片3独立设计预审：撤回upsert回滚后无死锁保证，冻结四文档/当前账本源及迁移/XML/测试哈希、原实验manifest、精确Git状态；本轮只完成候选文档与冻结，不自行勾选批准。
- [ ] 预审通过后才实施纯本地基础完整worker与技术领域fixture：仅账本锁专用标记+完整cause/next链同一SQLException1213/40001、明确ROLLED_BACK且无活动资源，事务外重跑完整原worker最多一次；固定业务身份/每attempt新nonce、不增加持久attempt/token。旧scheduleOnce参与外层仅传播异常、外呼旧Handler不包retry；保持原无retry反例不变。
- [ ] 切片3新增真实门禁：三事务真实两等待者/失败领域源账本无残留后完整重跑、第二attempt再次真实死锁无第三次、非本地及1205/1062/3819不重跑；UNKNOWN/提交异常/rollback失败、活动/错manager/read-only/rollbackOnly/嵌套/thread清理；持旧源锁重复不等源、两CLIENT_FOUND_ROWS/旧RR、完整载荷/映射/DDL NULL负例、绑定0/第二槽/guard/真实collation多槽排序/新旧Timer回填。R01/R02按B0§7.2.1冻结，补生产R3500/floor999/1000/1999/借后超期/退避/清理、真实代理Druid同资源与饱和interrupt/Driver优先级及隔离TCP阶段/flush超期回滚、恢复失败discard/HTTP Liquibase clock六旧Handler冷启动兼容门禁，均待实施验证；仅connect1500全局增量，不另建池、不承诺硬截止。商业flush实际接线留后续，不扩大A测试。其余Inbox/页轮次/心跳/Druid引擎容量/六旧Handler回归仍是后续B0未完范围，不提前开真实目录生产者/消费者。
- [ ] 保存原命令/XML/最终候选哈希，独立实现规格、另一个独立质量审查、根最终验收B0；再推进B其它契约修订和真实目录消费者。

### B1及后续：真实目录消费者与商业资格

- [ ] B前置可靠性规格修订及独立复审：核对Outbox/Timer过期领取恢复与fencing/CAS、合法死信时间、真实聚合唯一键下request/page/leaf/round身份、Inbox重试状态和业务同事务完成；该预审当前不通过，不能沿用“已有可靠设施”的假设直接启用新生产者。
- [ ] B前置真实MySQL门禁：领取/执行后宕机、业务提交后完成标记前宕机、旧worker失格、次数耗尽死信、同事件异摘要、分页重放及续页/leaf原子提交；只修基础设施不代表目录消费者已交付。
- [x] 核对A交付后的BPD创建/复活真实生产者入口：刷新只能挂在代理worker.executeBatch每行最终saveBuyer之后的同事务位置，不能挂NEVER编排层或旧不可达私有副本。已先修详细设计§6的入口清单，尚未新增生产者或运行B测试。
- [x] 用户确认两项资格语义并先落主设计§8.1/§8.2：保留现有排序，不因首选UNKNOWN跳选后备CONFIRMED；跨项目聚合实际首选。REQUIRED事实全部缺失/过期/未知时管理显示UNKNOWN并保留原因，仍拒绝发布/新单，不改内核淘汰或旧RPC。已补§13.1验收项，尚未实施或验收。
- [ ] 补齐真实生产者清单、时间驱动原通用定价来源新鲜性、非秘密配置完整性检查、新RR第一实体读与项目汇总边界、系统操作人/引擎启用契约；按真实payload消费三个旧target。OPTIONAL UNKNOWN不得由实施者擅自跳过改选，投影聚合与实际选中含义须在规格中明确。
- [ ] 修订并审查MANUAL与AUTO职责：自动商业仅AUTO，MANUAL价格/状态不接管；新HTTP复用manual目录的可用性不能永远卡在没有更新器的UNKNOWN，也不能暗改旧RPC待确认停用语义。
- [ ] 实施真实READY分销价来源、摘要/版本幂等、publisher/CAS与新独立只读资格Reader；损坏引用系统失败，缺条件DEFERRED，不填假价格/AVAILABLE。
- [ ] 全部当前生产者同事务写可靠Outbox；注册封闭Handler、Inbox与目录写同事务、分页展开、乱序读当前、重试/死信/Timer补偿，不能裸afterCommit或先成功Inbox后写业务。
- [ ] 真实MySQL并发/宕机/迟到/重启/到期/手工暂停退役回归，当前管理HTTP与真实前端payload一致；新增管理读字段及重算入口须权限并按实际需要同步前端。
- [ ] 独立规格、质量审查与完整文档取证。本片不宣告最终供给门禁或HUA_SHENG一单已经完成。

## Task C：当前准入、冻结回放与从零链路

- [ ] 首冻前准备目录，DRAFT/缺价格的拒绝及CAS沿详细设计真实返回，不制造空版本/报价。
- [ ] 最终新只读真实项目供给快照在订单/资金写前建立并复用，AVAILABLE滞后不能授权；不为加强停用线性化临时反锁供给/定价。
- [ ] 先冻结已有回执SKU/Schema再准备指纹，保持格式2原编码不变量；已接受同参回放不依赖当前目录和Schema，不重路由/扣款，历史治理不伪造来源。
- [ ] 当前RR旧视图、真实停用并发、改价原报价、跨项目隔离及真实Controller从零配置测试。
- [ ] 依赖HUA_SHENG协议、资产、受控多步骤/恢复与可信回调各自已通过规格及质量审查后，执行HUA_SHENG真实协议HTTP挡板全链及资产/资金恰一次断言；挡板结果不算真实供应商验收。
- [ ] 核验真实环境条件和单次测试范围，使用全新管理配置完成实际HUA_SHENG订单/资产/资金核对，结果写docs/集成测试.md；未经执行仍标未验收。

## Task D：旧RPC移除联动（待用户回答）

- [ ] 用户选择后先修订详细设计§12，明确BPD/PLT移除与旧编码新尝试范围、锁序和兼容；保持已成功同参回放。
- [ ] 按确认范围实施并独立审查、真实MySQL验证；回答前不猜测该项，A/B/C的新页面链路继续独立推进。
