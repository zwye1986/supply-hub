# B0切片3重开：精确修复实施计划

日期：2026-10-05。状态：**补核失败／REOPENED_ACCEPTANCE_NOT_VALID；本文件是待父复核的实施分片候选，不是实施完成或最终验收。**

原计划：[页面选品与受理目录桥接实施计划，Task B/B0](2026-10-04-buyer-catalog-bridge.md)。规范：[B0设计](../specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md)完整§6.1、§7.2、§7.2.1（V4A/R01/R02）、§8、§9；本计划仅拆执行顺序，**不删减、放宽或重新批准获审协议**。原设计中的“预审候选／待实施”措辞保留为历史设计阶段记录，当前交付状态以重开记录为准。

## 1. 证据、根因和关闭约束

证据根：`/Users/mac/.hermes/cache/scratch/task-b0-ledger-evidence/slice3-reacceptance/`。必须读取`handoff.json`、`findings.json`、`spec-review.md`、`full-design-clause-matrix.json`、`runtime_failed_classes`对应原XML及`parent-reacceptance-verdict.json`；历史输入和失败日志/XML不覆盖。

- 父裁定已使旧`07-slice3-final-acceptance.json`关闭结论失效。规格、质量、运行门禁均failed，不能恢复下一B0片或B1。
- 独占完整profile真实Maven/runner均exit1：270 XML、2321执行、6 failure、21 error、0 skipped；额外API契约Maven/runner均exit0：7 XML、45执行全0。277 XML/2366是执行数，不是全部方法覆盖；两类BeforeAll失败使25个源方法未执行。4039源／受测路径补核前后无漂移，补核未写原仓。
- 实际`DefaultAppendScheduleIdentityLedgerService`仅reserve/bind；生产源码检索没有真实调用者接此服务，`executeWorker`同样没有生产调用者。`OutboxEventRepository.appendLocal`是严格源INSERT；`DatabasePersistentTimer.scheduleOnce`仍普通读→源INSERT→catch重复读。建槽DAL已存在不代表完整源追加／调度协议交付。
- 六旧Handler中的`FulfillmentOperationDispatchHandler`、`RechargeFrequencyWakeScheduler`实际调用scheduleOnce；旧Outbox调用来自受理、O2O/Instore、商业发布等服务。修通用基础API并回归这些调用者不等于给这些业务加完整事务retry，不能修改A商业挂点。
- 历史审查只用缩略输入，漏完整§9、SystemIntegrityException及多个必需负例；“建议回填”、反射和吞清理异常的宽容与获审协议冲突。不能以历史passed豁免。
- 原无retry测试从`failures.isEmpty()`变成恰有一个真实deadlock；原测试SHA `afab971d6fa19b9612d3f8bb23009dcf8f10b17ec55ef1ccccb095cf3604e580`与当前不同。当前反例可保留，但GREEN只能说明反例被接受，**不能声称原无死锁RED→GREEN且测试未改**。原字节、原diff、日志/XML留在历史证据，新恢复门禁另立测试和哈希。

## 2. 安全与授权边界（每片先冻结再实施）

本轮只写三份既有文档与本专用计划；**未运行测试，未改Java/XML/SQL/YAML等运行字节**。父复核文档后逐片派唯一实施者；每次委派只含一片明确白名单及验收清单，不把本文件所有预算协议交一个250轮泛化任务。完成或遇阻断即交回，不跨片顺手修。

后续实施仍限定原仓`dev`，不新worktree，不commit/push/deploy/stash/restore；不读秘密、不访问真实业务库／供应商，只在仓外独占无旧target副本做隔离本地验证。读前stat；dataless立即停止，扫描跳过所有target。受测清单必须含新增文件、原命令、Maven与runner真实退出码、逐类逐方法原XML和哈希。

A39按`resume-a39-audit.json`及获批冻结输入核定：

1. `SupplyChainLiquibaseIntegrationTest`与`BuyerAcceptanceCatalogMigrationIntegrationTest`仅既有明确授权的迁移总数／校验和断言适配（当前历史适配63变更集、125表、300 datetime(3)，不能机械当未来固定值）。不删除行为断言、不换失败语义。新的迁移若需要新数值，先把实际模式与精确数字交父确认，不由计划猜数。
2. changelog登记文件已有例外仅末尾追加061，060及之前前缀字节固定，060 SQL完整固定；两测试例外不是改登记文件任意位置的授权。F02追加新的独立回填迁移若要继续追加登记，**需父确认新的尾追加路径／changeSet和针对性计数适配授权**，不改历史061/060或已执行校验和。
3. 其余受保护源码、Mapper、测试、测试支持及断言逐字节固定。若需要碰`SupplyChainErrorCodeConstants`、`FulfillmentAcceptanceLifecycle`、A商业writer/定价服务、`DeterministicSqlInterceptor`、`ProductCenterTestConfiguration`等保护路径，停报具体路径／原因／最小diff，请确认，不以“纯夹具”扩大授权。使用新隔离fixture，不改原A fixture。
4. 现有B0切片1/2保持，不把本次失效扩大成A未验收；但TimerOutboxInbox生命周期失败必须独立诊断，不能因属旧类就自动豁免。预算包装与manager配置可在本片非A39基础设施白名单修复；共享maxWait/maxActive/socket、validation/checker、TLS不改，不新增池，唯一既有全局增量connect1500不扩。
5. 不接A选品／定价RPC商业生产者，不注册实际B消费者，不把六旧Handler或引擎包retry，不实现B商业决策、HTTP/RPC传输或部署。§7.2最外层技术fixture必须闭环；真实商业saveBuyer/初始化/项目级联接线门禁保留给后续B，不能冒称已验证。

待确认清单：新增迁移登记例外及数字适配；超出两测试的受保护路径修改；未审TLS／custom传输支持扩展或驱动／池内部更改；任何新增商业Schema／业务决策。未答前对应分片blocked，不自行降级成建议或临时关TLS。

## 3. F01–F13逐组追踪

位置以补核`findings.json`原路径／原行号为证据锚点；实施后另记新行号。

| 缺口 | 修复片／依赖 | 完整必需条款（引用原规范，不以本列代替原文） | TDD正例／反例与验收类型 |
| --- | --- | --- | --- |
| F01 真实源／绑定缺失 | R3-02O、R3-02T；依赖R3-01 | §7.2(1–5)、§7.2.1(1–5)、§8 handle；§9追加唯一键／间接锁环 | 真实严格源INSERT1+CAS1返回持久身份；源unique/其它SQL、CAS0、伪造映射、RESERVED提交失败全回滚；Integration+Contract+Architecture |
| F02 强制旧Timer回填缺失 | R3-04；依赖R3-02T，登记授权先决 | §7.2迁移切换、§7.2.1(5)、§8旧key；§9维护回填／切换／映射损坏 | 多状态旧任务直接完整BOUND、新旧升级／重放；缺映射/损坏JSON/混writer阻断，原源字段不变；Liquibase/MySQL Integration |
| F03 allowlist／HostInfo／TLS缺失 | R3-05B；依赖R3-05A | R02(1–5)及idle例外，§9 R02-ALLOWLIST | 默认idle=true及精确checker允许，逐项置换拒绝且共享属性不变；实际Driver/HostInfo1500与冲突拒绝、未知/TLS fail-closed不改非预算；Test+Integration+兼容Contract |
| F04 setup／SESSION／network缺失 | R3-06A；依赖R3-05A/B | R01(4–5)全部setup/control／原更短限制，R02(6)，§9 R01-PREPARATION | manager前安装限制、setup/serverPrepare前后guard；读取/安装/SET半失败、返回超期、Callable/metadata/unwrap绕过失败关闭；Test+真实代理Druid/MySQL Integration |
| F05 Statement缓存污染 | R3-06B；依赖R3-06A | R01(4–5)物理身份、初始值/最小正记忆/恢复，§9缓存负例 | Spring ceil/0/更大setter后execute仍收紧，普通/prepared/callable/batch恢复；缓存恢复失败隔离且下一借用无污染；Test+真实Druid缓存Integration |
| F06 TTL／flush／commit／cleanup模式 | R3-07；依赖R3-03、R3-06B | R01(3、6)、R02(1–2)、§9 floor/flush后双guard | 借后E及holder TTL floor，flush后超期回滚、commit前拒绝；cleanup不受业务过期阻止，非预算原语义；真实代理/生产MyBatis Integration+Architecture |
| F07 cleanup吞异常／重复回收 | R3-05A并在R3-06/07复验 | R01(5–6)、R02(6)、§9 R02-DISCARD-FAILURE | 保存引用→disable→实际Socket.close→abortInternal→仅一次discard，原cause+suppressed、失败禁retry；close/disable/abort/discard/重复cleanup故障；Test+真实Druid Integration |
| F08 反射／late discard宽松 | R3-05A；R3-05B验接入 | R02(3、6)、R01(3、5)，§9 late/传输/cleanup | 公开API捕获实际holder/ConnectionImpl/socket、late首检不暴露manager；缺类型/socket/异常失败关闭，无反射吞异常；Test+Integration+Architecture |
| F09 任意callback非REQUIRED代理 | R3-08A/B；依赖R3-02O/T、03、04、07 | V4A全部、R01(1–3、6)、R02(1–2)、§9真实代理／错误阶段及不确定提交 | 真代理完整worker确认回滚后≤2，固定深不可变命令/新nonce；错manager/活动/readonly/rollbackOnly/嵌套/残留、非本地1213、UNKNOWN/COMMITTED/提交/回滚故障不retry，中断保原SQL；Integration+Test+Architecture |
| F10 无完整集合排序／末尾flush | R3-03；依赖R3-02O/T | §7.2(5)、§7.2.1(6)、§9多槽／间接锁环 | 最外层fixture最终版本/最后一行、真实collation等价合并与Outbox→Timer排序，只flush一次；第二槽失败、锁后新意图/flush后领域锁全回滚；MySQL Integration+Test |
| F11 BCS／LEGACY比较不完整、nonce可变 | R3-01（比较/nonce）与R3-02O/T（真实映射） | §6.1完整编码、§7.2不可变内容、§7.2.1(3–5)、§9载荷／映射／NULL | BCS完整规范与LEGACY精确十进制、原字段/类型保持；相同坏文本/重复键/代理项/版本/target/类型/null缺失拒绝、nonce外部改不污染；Test+Contract+MySQL Integration |
| F12 假late覆盖及各预算负例缺失 | R3-05A/B、06A/B、07、08 | §9全部R01/R02，不能仅setter/Bean同一性 | 借前合法≥3500后真实借后迟到、pool饱和/interrupt、首个setup阻塞、实际close错误/HostInfo/cold-start；对应原XML证据，不以借前拒绝冒充late；Integration |
| F13 原RED改写与第二次真死锁缺证 | R3-00归档、R3-08B恢复门禁 | §7.2反例撤回、V4A、§9第一／第二attempt | 原无retry日志/XML/字节独立保留；COMMIT/ROLLBACK两等待者及第二attempt真实1213无第三次，新恢复测试与历史反例分开；证据审计+MySQL Integration |

所有行均受§2同一A39保护约束；表中引用源类不是本轮代码写授权。

## 4. 分片顺序、有限交付与停止点

每片按**一条真实RED→最小生产GREEN→相邻负例→回归→冻结→独立规格／另一质量审查→父片验收**交回；不横向先写所有测试再集中改代码。已实现且补测直接GREEN如实记“回归覆盖”，不伪造RED。编译、JDK、fixture、同步超时不算行为RED。新测试／方案尚无类者下文仅拟定，不冒称存在。

### R3-00：状态／历史证据纠偏（本次仅计划层）

本片已完成内容是补核失败状态、历史证据索引与本计划。保留历史输入、原锁原语测试版本及原XML，失效旧07。当前测试的恰有死锁断言不还原、不改写；后续用独立命名区分“原无retry反例”与“完整worker恢复成功”。本轮未实现、未跑TDD。

### R3-01：下一次唯一实施片——纯比较与深不可变输入

**Q-R3-01-01重开修复授权：** 用户批准§6.1／§7.2保守每文档资源政策：原UTF8 1048576、容器64、全部token65536、数字原词法1000、单数规范ASCII4096、完整规范UTF8 4194304，均≤。先落设计再Java；仅LedgerJsonEncoding、必要预算原因／常量、R3-01纯／入口契约测试、BOUND旧载荷预算核验的最小入口增量与集成记录。独立factory约束／解析前精确UTF8扫描／构树禁提前去零／完整树分配前计量／编码二次守卫同政策；不catch OOME、不变精确十进制或协议名。旧LEGACY／Timer超限预检阻断切换并报告人工处置，不改写／绕过；R3-04实施仍open。每限额边界与安全超限fixture先RED，巨大指数仅保护生效后运行；两个reserve零DAL及旧BOUND超限cause必须取证。新证据独占`slice3-reacceptance/r3-01-budget/`，保留旧七候选和历史XML；最终JDK21完整非Integration纯／架构／契约回归、原XML逐方法及before/after manifest绑定最终候选。交父独立双审，不自行验收、不跨R3-02，不关闭BCS具体Schema或整体F11／切片3。

**有限清单：仅F11的编码比较／nonce部分；不写源、不动数据源、不接retry。**白名单限定`infrastructure/ledger`内比较实现、reservation值对象及新增纯测试/契约测试；先查可复用编码，不改共享ObjectMapper全局配置，不碰A39。

逐个垂直闭环：

1. LEGACY精确十进制值：1/1.0/1e0相同；大数不经double、数组顺序/字符串/布尔/null类型不同；保留旧全部字段与旧时间字符串，禁止按B规则剔除trace。
2. 相同非法JSON也失败，不走原文本相等shortcut；重复键和未配对代理项保留cause并失败关闭。
3. BCS对象Unicode码点顺序、固定UTF-8转义与精确十进制；数组/Unicode值不归一，null≠缺失。
4. 封闭Schema/已支持payloadVersion验证先于比较，未知字段/重复键/非有限数/身份小数或字符串拒绝；时间严格Asia/Shanghai毫秒格式；信封剔除仅声明元字段，不剔operator/cause/真实源身份。仅沿已有获审Schema实现；若B具体业务Schema未冻结，停报该范围，不自定必填字段或把LEGACY当BCS。
5. reservation nonce输入及访问输出防御复制、原attempt内稳定、不进摘要、不回显；固定命令内部集合／payload深不可变。

限定完成判据：上面五组正反例及既有比较回归通过，生产比较入口确实使用该实现，无共享配置漂移；Test/Contract可证明编码但不证明真实源归属。**F11仍部分未闭**直到R3-02的BOUND映射门禁。父只派这一片，遇Schema未决或保护文件需求即停，不向下一片扩写。

### R3-02O：真实Outbox追加闭环

依赖R3-01。范围`OutboxEventRepository`、账本服务/Mapper、必要新通用追加API与新本地fixture；不改任何商业调用者。

**本次实施前状态：`BLOCKED_DESIGN_CONFIRMATION`，未写运行代码／未跑TDD。** 已读取完整原规范与实际API：§7.2的新Repository入口仅限B保留槽，§6.1的新B协议为BCS，而当前reserve只支持LEGACY且具体BCS Schema未冻结。技术fixture的LEGACY输入授权不自动改变仅B入口的协议／命名空间；未自行选定技术保留名或添加旁路。父需先确认“分离通用可信源追加底座并明确非业务LEGACY fixture命名空间／边界”或“仅B入口待BCS Schema冻结”。证据与候选文档哈希在`slice3-reacceptance/r3-02o/handoff.json`；本次仅同步三份文档的有限状态，真实源写入／可信绑定／重复不触源及全部MySQL门禁仍未交付。R3-01父回执只有限接受比较／nonce／JSON资源政策，不代表F11或切片3通过。

真实代理REQUIRED调用方→保留B命名空间校验→排他槽／当前读→确认首次后分配eventId→严格源INSERT1→绑定CAS1→原事务提交返回。旧appendLocal严格语义保留，B保留槽拒绝旧入口。重复只核账本target/version/协议/完整payload返回原eventId，不SELECT/INSERT/锁源；BOUND完整归属由首次绑定/切换全量校验及可信映射守卫证明，不能为重复检查重新锁源。

**最新授权／状态（rev3，覆盖当前选择待答，不覆盖历史证据）：** 用户已批准“分离，先修订设计并独立预审，再实施”；rev1候选交独立预审`deleg_ffc1b748`后passed=false，阻断B1/B2；rev2按D1–D4修订后交独立预审`deleg_045e4f3c`，仍passed=false，阻断BLK-1~4。用户已逐项裁定：**BLK-1**批准零DB放行收窄为可证明安全集合（null、码点<31、含ASCII非[A-Za-z_]码点），其余一律参数化候选SELECT；O-S08b补充U+0301/U+034F等组合附标插入、é→E预组合替换、零权重候选边界，真实MySQL验证后逐项固定。**BLK-2**父已确认两重复文件（`LedgerJsonEncoding 2.java`、`catalog-reliable-consumption-prerequisite-design 2.md`）授权备份移出并核验SHA（0c1ac825…、3accfbc5…，见`r3-02o/blk2-parent-confirmation.json`），正式两文件未改动。**BLK-3**批准将『底座仍是其唯一生产调用者』改为『除账本服务Timer路径既有调用者外，底座是唯一新增跨包生产调用者；ArchUnit不为LedgerJsonEncoding新增限制』。**BLK-4**批准D4范围枚举改为test02–test08（或全部Outbox段），设计/计划/集成记录三处同步。非阻断同步修正：冻结表#6首调用方法名改createPaymentOrder、#9/#12字面首读表述精度、O-S08b的data_locks断言改probe前后增量对比、设计补充main显式注册auto-proxy creator（DefaultAdvisorAutoProxyCreator或BeanNameAutoProxyCreator，均spring-aop核心类无新依赖）。原D1–D4裁定保留：**D1**旧appendLocal隔离改为Java粗过滤快路径＋候选时调用方同事务连接参数化只读SELECT（utf8mb4_0900_ai_ci等价、零锁副作用），机制固定为程序化Advisor（spring-aop核心NameMatchMethodPointcut→bean outboxEventRepository+appendLocal，不加aspectjweaver/@AspectJ/新依赖），须main实际装配+AopUtils.isAopProxy断言，14处调用点/7 bean逐点证明该SELECT不成为事务第一条一致性读并写入设计；独立连接方案废弃，§5容量不受影响，预算上下文透明声明。**D2** LedgerJsonEncoding改public（保留final/私有构造/算法与预算常量，非A39，仅静态工具语义，ArchUnit不新增限制，底座仍嵌套OutboxEventRepository）。**D3**删除DefaultAppendScheduleIdentityLedgerService的Outbox reserve/bind死代码（Timer不动），ArchUnit白名单OutboxAppendIdentityMapper写方法仅底座及测试，其它生产禁依赖，DO按需调整权限。**D4**两测试（AppendScheduleIdentityLedgerIntegrationTest的Outbox段——范围test02–test08或全部Outbox段、LedgerComparisonEntryContractTest的Outbox断言）按同模块privateLookupIn反射桥驱动main底座重写，原字节+SHA归档，Timer段公开API不动。B完整BCS schema未冻不造接口字段，B入口继续失败关闭；不新增生产LEGACY入口、不改旧appendLocal行为语义。当前仍为`DESIGN_SEPARATION_PENDING_INDEPENDENT_REVIEW`（rev3），交父再次独立预审；不编码／不跑TDD，不关闭R3-02O／F11／切片3。rev3证据根`slice3-reacceptance/r3-02o/design-separation-rev3-handoff.json`；rev2证据根`design-separation-rev2-handoff.json`保留为历史；具体候选以B0 §7.2.1「R3-02O-S」（rev3）为准，完整读取§6.1／§7.2／§7.2.1／§9，不以本计划摘要替代。

新授权差异：main中的私有嵌套可信追加底座与Repository仅B公开入口分离，测试反射桥只在src/test进入同一main算法，以`R3_02O_TECH_FIXTURE`及LEGACY技术JSON验证，不新增生产技术命名空间或运行配置旁路。底座不注册可注入bean／public工厂，不收外部eventId／nonce／slotId／绑定证明／任意source callback；源身份内部生成、同连接真实INSERT后当前读核全属性才CAS1。已有公开Outbox reserve／bind为生产零调用者死代码，按D3**直接删除**（`OutboxReservationResult`/`reserveOutboxSlot`/`bindOutboxEvent`从接口与实现移除，Timer签名与实现不改，保留失败关闭签名不符合裁定）。既有测试影响：`AppendScheduleIdentityLedgerIntegrationTest`Outbox段（范围test02–test08或全部Outbox段）与`LedgerComparisonEntryContractTest`Outbox断言按D4反射桥重写、原字节+SHA归档；`LedgerReservationImmutabilityTest`的Outbox段随API删除移除（Timer段不动、同样归档）；不静默降低断言。此API边界变化须由独立预审明确裁决，保护路径需要另授权即停。ArchUnit新增白名单：`OutboxAppendIdentityMapper`写方法仅嵌套底座及测试访问，其它生产禁依赖；`OutboxAppendIdentityDO`按需调整权限。LedgerJsonEncoding按D2转public（算法/常量/私有构造不变；除账本服务Timer路径既有调用者外底座是唯一新增跨包生产调用者，ArchUnit不为其新增限制）。原阻断已闭合：main未跟踪重复文件`LedgerJsonEncoding 2.java`与docs重复文件`catalog-reliable-consumption-prerequisite-design 2.md`已由父授权备份移出并逐文件核验SHA（0c1ac825…、3accfbc5…，见`r3-02o/blk2-parent-confirmation.json`），正式两文件未改动。

B保留聚合名候选固定`BUYER_SELECTION_CATALOG_REFRESH`，主设计已定event=`BUYER_SELECTION_CATALOG_REFRESH_REQUESTED`、target=`buyer-selection-catalog-refresh`、payloadVersion=1不再泛称未定；完整闭合Schema／投影注册仍缺，B入口DAL前拒绝，不用LEGACY冒BCS。旧appendLocal方法体／签名不改；隔离门禁按D1冻结为程序化Advisor（NameMatchMethodPointcut→outboxEventRepository.appendLocal，main实际装配并显式注册auto-proxy creator（DefaultAdvisorAutoProxyCreator或BeanNameAutoProxyCreator，均spring-aop核心类无新依赖），AopUtils.isAopProxy断言）＋Java粗过滤快路径（null/码点数<31/含ASCII非[A-Za-z_]码点→零DB放行，其余一律候选SELECT）＋候选参数化无表SELECT（冻结SQL见B0 §7.2.1，utf8mb4_0900_ai_ci显式collation、调用方同事务连接、零锁、不建读视图，锁断言按probe前后增量对比）；普通非B严格INSERT透明，B保留名及collation等价变体（含大小写、组合附标插入、é→E预组合替换、零权重变体，真实MySQL验证后逐项固定）源零INSERT拒绝。14处调用点/7 bean已逐点核其事务更早一致性读（冻结表见设计，rev3已修正#6首调用方法名createPaymentOrder及#9/#12字面首读表述），probe不成为第一条一致性读。必须审真实生产调用者无self-call／裸new及advisor装配，否则停报无法保证旧入口隔离，不靠注释承诺。

预审输入覆盖O-S01–O-S11全部正反例矩阵（rev2在O-S08下新增O-S08a生产装配断言、O-S08b advisor SELECT零锁副作用与语句计数、O-S08c快照时序负例三项，R1–R9及原矩阵条款不减）：真实首写INSERT1／读回核验／CAS1、伪造／缺源／逐属性不符、源unique／SQL／CAS0／RESERVED提交全回滚、同载荷／旧RR／CLIENT_FOUND_ROWS两设置／持源锁重复源SQL0、异载荷／旧BOUND预算／损坏映射、事务资源／read-only／rollbackOnly／重入清理、生产装配／private可见性／测试桥不打包、保留槽名称与collation／旧入口隔离（advisor装配＋快路径零DB＋候选1条无表SELECT零锁，锁断言按probe前后增量对比）、B schema gating、单槽等待者与首次提交／回滚、切换全量映射核验。切换／维护实际实施仍R3-04，集合排序／flush仍R3-03，预算／retry／coordinator仍原片；本片不得横跨。独立审通过后父再指派唯一实施者，测试要证明同一生产底座而非test-only复制算法。技术边界无法用Java可见性与实际装配保证时保持blocked并交父，不开始替代实现。

RED先测真实源缺失／伪造映射；再源聚合unique、eventId unique／其它SQL、绑定0、保留未绑定提交、未知态／NULL坏映射、rollbackOnly。每个失败独立连接读回领域/源/账本全无残留。真实持源锁重复不等待源、旧RR、CLIENT_FOUND_ROWS两设置、BOUND所有不可变字段不变，保留第二道源unique，不吞1062。

**2026-10-06 R3-02O接续限定进度（最新记录）：** rev3父预审回执已批准仅本片实施；最后SELECT失败已实证为同线程1条probe＋Connector/J源INSERT前`transaction_read_only`检查，修为独立观察连接事件窗口计量并保留双probe／无关SELECT负控制。单beanAdvisor和ArchUnit全签名裸构造／精确Mapper三方法／自调用门禁已补；同名非目标bean以修正fixture明确断言完成同测试字节RED→GREEN，copy06启动错误不当RED。最终定向6类39方法及API45方法原XML全0，Maven／runner均0；完整profile仍运行，不计通过。保护文件39路径均与接续输入相同，旧appendLocal及Timer五方法逐字不变。前实施D2两个静态方法public超出本轮“仅类public”字面边界，已冻结待父裁定；完整条款覆盖仍缺真实代理／完整故障资源／切换隔离核验等证据，见`implementation-resume2/clause-matrix.json`。本片未完成、未双审、F11／切片3仍open；不推进R3-02T或后片。

**2026-10-06 D2授权同步／下一有界检查点（覆盖历史待裁定，不抹旧证据）：** 用户明确授权`LedgerJsonEncoding`类及`legacyMatches`／`canonicalBytes`两静态方法三处public；算法、六常量、private构造及其它字节不变，无其它新增public成员，无生产LEGACY入口。以父`implementation-resume2/parent-d2-authorized-next-checkpoint.json`（SHA256 `148d502c4ba00706784db376f642caf82dd7b61939555087f2e87df4f425dee0`）为冻结输入，本次只补O-S01真实REQUIRED代理首写与O-S07事务资源／guard／清理边界及关联O-S04故障门禁。D2不再待答；R3-02O及切片3继续重开。设计落实细节先写B0「R3-02O-S」检查点小节，再测试行为RED→最小生产修复GREEN；已存在保护立即GREEN如实记回归。父接管copy-12完整profile，实施者不得触碰或重复完整profile；仅独占fresh archive有限门禁，证据`r3-02o/implementation-proxy-checkpoint`。不改Timer、预算、retry/coordinator、A39或061，不跨其它O-S缺口，不自验收。

**2026-10-06 O-S01／O-S07代理检查点冻结（部分实施，非验收）：** D2三public授权已同步，编码器其它字节未变。本轮新增仅src/test的`TrustedOutboxProxyTransactionIntegrationTest`，真实生产配置／REQUIRED代理／Repository实际私有底座／固定`R3_02O_TECH_FIXTURE`／领域InnoDB fixture同事务，源INSERT1／源当前读1／CAS1及同连接、提交返回eventId已取证。当前代理localRollbackOnly、活动其它manager借用既有供应链资源、未验证markBound分别同测试字节行为RED→最小生产修复GREEN；Outbox Guard迁至实际同步集合与holder归属，beforeCommit残留／未验证标记拒绝、afterCompletion清空。挂起事务提交阶段曾误拿恢复后的外层代理状态判归属，修设计后以同测试字节RED→GREEN改为提交Guard只核实际资源。真实源1644/45000与绑定3819/HY000穿代理、ROLLED_BACK、资源清空及独立连接领域／源／槽零孤儿；无事务／只读／holder回滚／缺同步／重入／REQUIRED嵌套／同线程复用为回归覆盖。

最终有限门禁9类67执行、failure/error/skip均0，Maven／runner均0，4056文件前后manifest一致，原XML逐方法核验无问题；生产jar不含测试桥／fixture常量。它不是完整profile或O-S07闭合：进一步仅scratch诊断真实反例证明，**祖先代理先设localRollbackOnly、holder仍false，再经另一REQUIRED代理调用底座时，内层currentTransactionStatus不可见祖先local标记，当前实现未在DAL前拒绝**。`ancestor-local-diagnostic-red`原XML11方法／1failure／0error，明确失败`ancestorLocalRollbackOnlyMustBeRejectedAfterJoiningAnotherRequiredProxy: Expecting code to raise a throwable`，Maven／runner均1；诊断只在新独占archive替换该测试，运行字节与原仓差异有单独manifest／overlay绑定，不污染最终候选、不降断言、不改成成功测试。该O-S07剩余根因需要先细化获审的祖先状态／实际事务所有权方案，未擅改manager、预算或新增AOP接线／反射入口；本检查点交父保留阻断，不自称O-S07、R3-02O或切片3完成。

证据根`/Users/mac/.hermes/cache/scratch/task-b0-ledger-evidence/slice3-reacceptance/r3-02o/implementation-proxy-checkpoint/`。父copy-12完整profile已结束Maven／runner1，277 XML／2370执行／5failure／21error／0skip、字节一致，作为最终验收阻断保留；父后续9测试／fixture限定修复授权排在本检查点之后，本轮未动9路径、A39、Timer、预算、历史迁移或061，不重复完整profile、不触碰copy-12资源。其它O-S03/04剩余/05/06/08/11及R条款缺证继续留完整矩阵，等待顺序实施、独立双审及父最终核验。

### 九测试／夹具限定修复（2026-10-06，顺序接续，未验收）

- [x] 设计先行冻结七组修订；仅授权九既有测试路径及对应文档，不改生产／DDL／A39，不处理祖先rollbackOnly。
- [x] 供给与两到店全量63计数、Quote固定059真正重放、LocalTime63/125/300与时间不偏移，逐组定向真实MySQL。
- [x] 052查询夹具三处身份契约、quota CARD NULL、Auth test-only身份provider、Movie禁远程配置，逐组定向实测；全部原业务断言保留。
- [x] 新鲜最终候选完整profile与API契约门禁；第15轮完整门禁（run 15）退出码0/0，311 XML，2676用例全部通过（0 failure / 0 error / 0 skipped），源码前后哈希一致（a1ba74b3be4bd83b6c757200107428b28b980c6faff05aa94d5270f2869fd670）；API契约门禁7类45执行全0；前次26个失败执行全部归零。
- [x] 有界handoff交父并通过完整门禁审计（parent-run15-full-profile-audit.json）；九测试/夹具修复闭环。完整矩阵与O-S07祖先阻断推进方案A实施，不提前关闭切片3或推进后片。

具体方案以B0设计「九测试／夹具限定修复设计」及Quote设计§4.1为准；旧失败是测试数量／夹具阻断，不伪造生产RED。LocalTime注释归因勘误为060增加1列＋061增加4列，13轮重验2执行全0；11轮（SIGTERM 143）及14轮（Socket阻塞SIGTERM 143）受控中断现场与证据均保留。第15轮独占完整profile全部通过（311 XML/2676用例全0，退出码0）。证据在`r3-02o/test-fixture-repair`。

### O-S07 祖先 localRollbackOnly 方案 A 实施准备（用户已批准方案 A，设计预审 passed，待 TDD 实施）

- [x] 祖先 localRollbackOnly 只读研究完成，实证 Spring 6.2.18 无枚举祖先 status 的公开 API；方案 A 在 scratch 隔离探针 8 方法全绿。
- [x] 用户明确裁定批准方案 A（原 `supplyChainTransactionManager` 透明状态观测装饰器 + 资源级在途 status 登记簿）。
- [x] 主设计 `2026-10-05-catalog-reliable-consumption-prerequisite-design.md` 补齐方案 A 详细设计（Bean 装配、原始 status 引用捕获、同步 Scope 绑定、底座 DAL 前阻断、NESTED/REQUIRES_NEW 隔离与多 manager 失败关闭）。
- [x] 方案 A 独立设计预审通过（`deleg_02f8ba6a`，passed，证据 `option-a-preapproval/pre-review-report.json`）。
- [x] 由唯一原仓写者按严格 TDD 推进同测试字节 RED→GREEN 最小实现（16轮RED：11方法1failure0error确证反例；17轮GREEN：最小生产实现后11方法全0；18轮追加程序化、挂起及NESTED隔离覆盖后14方法全0；架构7用例、隔离9用例、单元/契约全0通过）。
- [x] 运行新鲜最终候选完整profile与API契约门禁；第30轮全门禁（311 XML / 2680 用例全0，退出码0）与第31轮API契约（7类45用例全0，退出码0），同源字节一致。
- [x] 独立实现规格审查（`deleg_8261edc1` passed）与独立质量/安全审查（`deleg_6eb1542c` passed）；父最终验收完成（`parent-r3-02o-final-acceptance.json`）。R3-02O 闭环。

### R3-02T：真实Timer调度／handle闭环

- [x] 设计先行：主设计文档新增 §8.1 真实 Timer 调度与账本绑定实现（R3-02T）。
- [x] 方案设计独立预审通过（`deleg_a2ecd1f8` passed）。
- [x] 编写真实 MySQL TDD 测试（`DatabasePersistentTimerIntegrationTest`）：
  - 首次成功调度：账本槽 + 源任务同事务持久化，返回真实 (taskNo, taskId, 0L)；
  - 重复调度：返回已有已绑定任务 handle，断言源表零 SELECT / 零 INSERT / 零锁；
  - 载荷协议与内容冲突：异 payload / 异 payloadVersion 抛出 SystemIntegrityException 且源表零写入；
  - 故障注入：task_no 唯一键冲突或绑定失败整事务回滚，无孤儿槽与无孤儿任务；
  - 终态旧任务重复不重开。
- [x] 生产代码最小实现：`DatabasePersistentTimer.scheduleOnce` 接入 `AppendScheduleIdentityLedgerService`，移除局部重试。
- [x] 运行定向门禁与全量门禁：第42轮全门禁312 XML / 2685用例全0，第43轮API契约7类45用例全0；独立规格审查（`deleg_5947cb4f` passed）与质量安全审查（`deleg_f2dd4a31` passed）；父验收完成（`parent-r3-02t-final-acceptance.json`）。R3-02T 闭环。

依赖R3-01，复用02O证明过的共用协议但单独验Timer。`DatabasePersistentTimer.scheduleOnce`转账本路径，严格源INSERT1后取真实taskId/taskNo并绑定；同事务新源handle返回真实0。重复不锁源，仅如§8确需token观测时独立新只读非锁MVCC事务，计额外连接，源被删报完整性错误；旧RR看不见不能新建。fireAt不当重复payload，不重开终态，不改cancel语义。外层业务事务异常只传播、不局部retry。

真实MySQL正反例同02O，另验task_no冲突、源token变化、终态旧key不重开、异payload/协议拒绝、观测不等源锁；旧Handler只兼容回归不改重试。完成后仍因旧任务未回填而禁止混writer启用。

### R3-03：完整技术worker意图收集／collation／flush

- [x] 设计先行：主设计文档新增 §7.2.2 完整技术 worker 意图收集、数据库 Collation 确定性排序与单次 Flush 协议（R3-03）。
- [x] 方案设计独立预审通过（`deleg_23a851ff` passed）。
- [x] 编写真实 MySQL TDD 测试（`LocalLedgerIntentFlushIntegrationTest`）：
  - 嵌套/多行领域写后单次 flush，所有 Outbox 与 Timer 槽同事务持久化；
  - 同键等价载荷合并且保留最终版本元数据；
  - 同键异载荷在 flush 阶段抛出 SystemIntegrityException 并整事务回滚；
  - 多槽按确定性 Collation 顺序加锁，并发反序事务不发生 1213 死锁；
  - 第二槽或后续槽/绑定失败整事务回滚，无任何孤儿槽或孤儿任务；
  - flush 后再次记录意图或获取新领域写锁被 beforeCommit 守卫严格阻断（追加用例 7 验证通过）。
- [x] 生产代码实现：`LocalLedgerIntentCollector`，实现内存缓冲、确定性Collation排序、等价合并、状态机与提交前守卫。
- [x] 运行定向门禁与全量门禁：第48轮全门禁313 XML / 2692用例全0，第49轮API契约7类45用例全0；独立规格审查（`deleg_dc653862` passed）与质量安全审查（`deleg_8dec5398` passed）；父验收完成（`parent-r3-03-final-acceptance.json`）。R3-03 闭环。

依赖02O/T。新增事务归属收集器和纯本地技术域fixture；不接真实saveBuyer。实际最外层供应链事务记录意图，嵌套/多行/级联全部领域写完，首个账本锁前冻结完整集合，等价合并且异载荷失败；参数化无实体表SQL按源unique实际collation和数值确定排序（Outbox先Timer），不得Java归一外部编码、提前建立领域RR视图。只flush一次，随后不得新领域锁／新意图。

RED→GREEN分别验嵌套提前flush、最后行最终版本、大小写／组合字符同collation等价合并与异载荷拒绝、反序并发排序、第二槽/源/绑定失败整批回滚、flush后新增锁/意图拒绝及RESERVED guard。fixture真实SQL记录/锁证据，mock顺序不足。真实商业链门禁明确留后续，不减原获审要求。

### R3-04：强制旧Timer迁移／启动切换检查

- [x] 设计先行：主设计文档新增 §8.2 强制旧 Timer 迁移与启动切换检查实现（R3-04）。
- [x] 方案设计独立预审通过（`deleg_90cc8c9d` passed）。
- [x] 编写真实 MySQL TDD 测试（`TimerLegacyMigrationIntegrationTest`）：
  - 存量多状态旧任务（PENDING/CLAIMED/EXECUTING/RETRY/COMPLETED/DEAD/CANCELLED）直接回填 BOUND 槽，源行全部字段逐字逐值不变；
  - 幂等重放：多次执行回填不重复插槽，映射一致性保持；
  - JSON 资源预算与损坏阻断：损坏 JSON 或超出 R3-01 资源预算抛出 SystemIntegrityException 阻断启动；
  - 启动切换守卫：检测源表有行但账本无对应 BOUND 槽时 fail-closed 阻断启动；
  - 映射破坏或载荷篡改检测：账本信息与源行不一致时阻断启动。
- [x] 生产代码实现：`TimerLegacyMigrationService` 与 `TimerLegacyMigrationRunner`。
- [x] 运行定向门禁与全量门禁：第54轮全门禁314 XML / 2698用例全0，第55轮API契约7类45用例全0；独立规格审查（`deleg_d5858df6` passed）与质量安全审查（`deleg_ab9fe198` passed）；父验收完成（`parent-r3-04-final-acceptance.json`）。R3-04 闭环。

依赖02T；用户裁定采用应用级回填与启动核验方案，零修改 Liquibase changelog 与 A39 迁移测试断言。直接以旧真实taskId/taskNo、payloadVersion、完整JSON及LEGACY建BOUND、不可变迁移标识，不提交占位RESERVED。**回填是必需，不是建议。**

在隔离MySQL分别新库重放、已有061库升级、含PENDING/RETRY/活动/全部终态旧任务、重放与损坏JSON/映射/协议负例；读回原status/attempt/token/plan/所有源字段逐字／逐值不变。启动全量校验映射完整协议一致，保留B命名空间源有行无账本即拒绝；停全部旧writer与引擎、排尽在途、维护迁移核验、同版本后开引擎的门禁仅本地模拟，不部署。混writer无法可靠确认则不宣称可启用，不在重复持锁路径补映射。

### R3-05A：公开API捕获／一次性严格隔离（预算片先做安全底座）

不启用新预算路径，先限定`MySqlSocketExtractor`、预算wrapper清理支持与新故障fixture。保存原holder、直接ConnectionImpl、实际标准Socket身份，只用公开API不反射；缺失类型/socket失败关闭。cleanup独占一次：保存引用→disable阻止recycle/清缓存→对提前捕获实际Socket直接close捕获异常→同步abortInternal→一次discard(savedHolder)。各步失败继续尝试余下步骤，保原业务cause及全部suppressed，cleanupFailed永久否决本命令retry；不正常pooled.close回池、不重复扣active。

正例记录直接close调用/身份/本地传输关闭及下次借用无污染；反例逐个实际close IOException、isClosed=true但socket未闭、abort吞内部错误、discard正常计数而close失败、disable清cache失败/holder清除、重复cleanup/recycle。不能用flag/count/abort返回当物理关闭证据，隔离不确认即blocked。

### R3-05B：allowlist／传输失败关闭／实际connect1500

依赖05A。范围routing/配置属性与新本地Druid/Connector fixture。每个预算入口raw借前检查R02完整允许集合与配置生命周期，不通过shared setter修配置；默认idle=true及精确默认checker旧验证组合必须允许。逐项置换retryCount、scheduler、filters/动态注入、borrow/idle/return、checker子类／替换、usePing/validation、autoCommit/removeAbandoned、initSQL、单host直接驱动／socketFactory/propertiesTransform等，预算拒绝而非预算透明。

证明唯一wrapper resource key和唯一池、build后init/任何borrow/Liquibase前setConnect1500，int默认／仅1500及非法值启动拒绝；沿实际Druid Driver.connect properties→Connector ConnectionUrl/HostInfo→socket的非敏感有效timeout取证并拒绝host-specific冲突/未审形式，不打印URL/身份配置。只证明单地址TCP参数，DNS/多地址/TLS/认证/初始化分别记录边界。

实际标准socket受控范围通过；默认PREFERRED可能TLS，SSLSocket/未知未审封装预算失败关闭，非预算现有TLS不变，不将sslMode改DISABLED。真实池饱和排队/interrupt、raw返回后第一步检查cancel/deadline、借前≥3500但借后真实迟到→严格清理且manager/worker未见连接；原验证discard/continue累计borrow无有限上界是明确例外，不用Future线程伪硬截止。

### R3-06A：Connection所有网络入口／SESSION control

依赖05A/B。raw返回并首检后定acquiredNano/E、保存原network/socket并在manager见连接前安装局部限制，失败不能WARN继续或用0代原未知值。每可能发网setup全部重载/metadata方法/prepareCall/createStatement等前检＋动态收紧＋后检；未知入口fail-closed，unwrap/getConnection等不得返回可绕过业务guard的裸对象。原正更短限制不放宽。

封闭同物理连接非递归SESSION控制：固定参数读原值成功前不改；各控制语句前后guard/socket/floor正query，目标原正值与min(2,q)最小，后续q更短继续收紧；失败半设置保cause并恢复或严格隔离，不能继续worker。真实首个get/setAutoCommit、isolation、metadata与serverPrepare返回前阻塞/超期，证明先安装限制/返回结果不泄漏；逐重载单测之外必须真实驱动/MyBatis门禁。

### R3-06B：物理Statement最小正限制／缓存归还

依赖06A。普通/prepared/callable、全部execute*/batch/largeBatch与MyBatis flush路径覆盖。按物理Statement保存初始queryTimeout，观察到的最小正限制不被Spring ceil、0或更大setter放宽；execute前floor q与当前／记忆值取min，不足1秒拒绝；SESSION随q收紧。缓存close/归还前恢复初始状态，失败无法可靠淘汰entry则隔离物理连接，隔离成功也禁retry。真实Druid/server-prepared缓存下一请求无污染；不把逻辑close当真实淘汰。

### R3-07：预算manager／flush后guard／cleanup模式

依赖03、06B。同wrapper预算专用JdbcTransactionManager借后重新以E设置determineTimeout和ConnectionHolder TTL，不沿用借前6秒或ceil；非预算透明。R3500上下界、999/1000/1999 floor、socket毫秒不足1、借后超期均独立闭环。

最外层生产MyBatis flush之后beforeCommit再查E/命令业务余量/cancel/完整集合已flush/RESERVED及真实映射；实际commit前最后同样检查，不新增SQL/领域锁。真实flush后超期写入全部回滚。rollback/恢复manager autoCommit/isolation等切独占cleanup模式，业务deadline不能阻止；仍同连接最后正socket、先rollback/Statement/SESSION/manager状态恢复，最后原socket/network再正常回池；任一恢复失败转05A严格隔离、不retry。超期不跳rollback，不承诺commit/rollback/cleanup硬截止。

### R3-08A：真实REQUIRED完整worker及重跑资格

依赖02O/T、03、04、07；不再任意TransactionTemplate callback冒称生产代理。固定纯本地worker契约／深不可变命令，正确manager REQUIRED；无事务编排器仅该worker，入口检查actual/sync及DataSource、SqlSession等全资源、错manager、readonly、rollbackOnly、嵌套、旧BudgetContext污染，ThreadLocal每出口恢复/清空。

失败必须穿代理，afterCompletion明确ROLLED_BACK且所有绑定资源释放、cleanup成功，专用标记只在建槽/紧邻当前读产生，同一SQLException1213 AND40001且完整cause/next链防环，才讨论第二attempt。t0一次15s、C1000、每次R≥3500；10–100ms退避事务/连接外，前R-b≥3500与后重检、cancel/interrupt保持并保原SQL cause，清理/退避/借连接都不reset。固定业务身份，新attempt新nonce/收集器/DO/返回值，最终已确认提交才返回，不增加持久attempt/token。

先真实单attempt成功与入口拒绝；再真实1205/40001、源1062、绑定3819、领域/源/绑定等非本地阶段1213不retry，读回回滚及执行计数；cause链纯Test只作补充，不能代实际SQL。

### R3-08B：完整worker并发恢复／耗尽／不确定提交

依赖08A。新独立恢复类，不改变历史无retry反例。Outbox与Timer在空表/末尾键、无哨兵，两真实等待者由data_lock_waits证明，创建者分别COMMIT/ROLLBACK；失败attempt读回领域/源/账本无残留、明确回滚与全资源释放后整个代理worker第二attempt成功，固定输入及最终返回真实源映射。

另立确定性第二attempt再次真实1213/40001门禁，保存真实锁/cause、不第三次、失败完整无残留；不能合成异常代替。UNKNOWN、COMMITTED、commit异常、rollback失败/不确认回滚各独立故障取证，不以socket探针泛代事务结果；每种拒retry并保原因及清理错误。最后做ThreadLocal线程复用、取消退避、总预算耗尽；若无法可靠造某故障，标blocked缺证，不能当passed。

### R3-09：全门禁归因／独立双审／父最终验收

依赖全部前片（含迁移授权）与§5运行失败闭合。真实完整profile不限定it.test；供应链纯测/架构、server profile全部Integration/Contract、API额外7类契约分别跑，范围随新增类以源码清单核执行集合；不复用旧XML、不预定未来计数。完整命令见§6。

分开汇报运行门禁、规格、质量、父最终验收；独立规格按完整规范逐条及所有新增源码/调用者审，不用13条摘要替代65行矩阵；质量另一个上下文审全部候选／资源清理／cause／权限边界。父核HEAD/暂存区/精确工作区、所有候选与受测哈希、原XML逐方法及未执行方法、A39例外与全部§9必需条款才可恢复推进。任何片GREEN不能关闭整个切片3。

## 5. 补核运行失败的单独修复／归因队列

这里是读回原失败XML的**症状分类及待验证归因**，不是已经证明全部基线或fixture。后续在精确固定HEAD＋A已验收增量与当前候选的独占副本做对照，保持同测试字节、同依赖/环境；只跑本地fixture。逐项标baseline / fixture / new-regression或未归因，保存原XML，不用将测试改绿倒推基线。

| 原失败类／数量 | 原XML直接证据 | 处理队列与范围门禁 |
| --- | --- | --- |
| ProjectStandardAssetAuditIntegrationTest：11 error；ProjectStandardOrderQueryIntegrationTest：7 error | Unknown column `buyer_user_id` in field list | SQL/模式fixture不一致候选；追各自建表与真实Mapper/迁移及原基线，不先改生产查询/Schema；保护路径需求另确认 |
| InstoreMemberEntryRouteMigrationIntegrationTest、InstoreProjectCashierRouteMigrationIntegrationTest各1 failure | 51→实际63变更集断言 | 迁移计数fixture适配候选；必须核新增changeSet与行为断言不变，此两类不在A两测试例外内，需明确授权后才动 |
| SupplyChainDatabaseLocalTimeMigrationIntegrationTest：1 failure | 123→实际125表断言 | 新两账本造成数量增量候选；真实不移两次时间行为仍需保留，不能只改数字；该类修改范围需父确认 |
| StandardProjectDirectOrderQuoteSourceMigrationIntegrationTest：1 failure | 61→实际62断言 | 先核其选定changelog和为何非63，禁止机械全改63；独立fixture范围授权 |
| SupplierConfigurationSchemaIntegrationTest：BeforeAll 1 failure | 60→实际63变更集，7方法未执行 | 迁移fixture候选；保留真实约束门禁、同方法补执行，不能把占位XML当覆盖 |
| ProjectAppAuthFlowIntegrationTest：BeforeAll 1 error | 测试上下文直充限频身份配置缺失，18方法未执行 | fixture配置候选；仅合成本地测试值，不读真实秘密、不放宽生产密钥校验；需授权该fixture增量 |
| MovieSupplierConfigIntegrationTest：1 error | No spring.config.import set | fixture/profile候选；隔离测试禁远程配置，禁止以连真实Nacos/外部服务补绿；先授权测试上下文变更 |
| SupplierRoutingApplicationServiceIntegrationTest：1 error | 3819／`ck_sc_order_card_user_identity` | fixture身份缺失或真实新增回归均可能；追真实插入与CHECK/基线，保持约束，不去CHECK、不补假业务数据冒充生产 |
| TimerOutboxInboxIntegrationTest：1 failure | 期望PUBLISHED40，实际DELIVERING20 | 生命周期／同步fixture／新增回归未定；先追线程时序、publish前态/version/token及实际SQL，不放宽失格守卫、不靠延长sleep或排除旧类 |

所有失败闭合必须逐类逐方法真实执行（两BeforeAll的25方法另追踪），现有纯测1842全0/API45全0不抵消这些失败。若修复要求扩大切片1/2或业务语义，列单独设计／授权阻断；不偷塞预算修复片。运行归因队列可只读并行，但写入唯一实施者且先明确白名单。

## 6. 最终门禁与证据格式（未来执行，当前未运行）

在独占当前候选副本，Maven实际JDK21并先核mvn -version；不执行原仓target：

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration verify \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-api -am test \
  '-Dtest=com.aifuli.module.supplychain.**.*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

定向片门禁不能称完整门禁；按插件/类/逐方法多重集合解析原XML，与源码预期执行集合一致，BeforeAll占位另记。原无retry与新恢复分开，RED/GREEN受测测试SHA相同或明确说明增补/修正fixture后重新取真正RED；真实MySQL、纯Test、Contract、Architecture、兼容范围分别报告。提交不确定状态／传输／关闭故障必须各自证据，不能偷换成合成异常已全证。

## 7. 完整条款覆盖索引（65行原矩阵，不漏跨切片规范）

以下原行号锚定补核冻结B0文档／`full-design-clause-matrix.json`；当前文档加状态记录后行号会移，按原章节/原条款核，不拿行数代协议。

- §7.2原210/212/214/216/217/218：R3-01、02O/T；219源不接自身账本/无环所有权：02O/T、03的通用限制，实际ROOT/PAGE/LEAF消费者后续B接线验收；220：03及后续真实商业接线；222无retry反例撤回及恢复边界：00、08B。
- §7.2.1原226/228/229/230/231/232：01、02O/T、04；233多槽：03；234官方锁语义限制：02/08B，不宣传无死锁。
- V4A原238/240/241/242：08A/B完整worker、资源、资格与错误阶段；不是只验注解。
- R01原245/246/247：05B、07、08A/B；248：06A/B；249/250：05A、06A、07；251：06B；252：03、07、08A/B。
- R02原256/257：05B、07、08A；258/259/260：05A/B（含原idle非硬截止例外和TLS失败关闭）；261/262：05B；263/264/265：05A、06、07；269维护迁移切换：04必需。
- §9原297授权/规范前提：每片；299：00、08B；300：08A/B；301：05B、07、08A；302：05B、06、07、08；303/304：05A/B、06A/B、07、08全部，不以TODO取代；305：02O/T、03、04、08B。
- §9原307通用fixture不外呼：每片；309/310表头不算实现；311/312领取回收预算切片1历史保持；313/314转换审计切片2历史保持且§5回归失败不得豁免；315/316新事务型Outbox/Timer源栅栏、317 Inbox、319页轮次、321线程池心跳／容量、322兼容/HTTP失败关闭是后续B0仍必需范围，当前未交付，不删规范、不注册空Handler；318追加唯一键：02、04、08；320间接锁环：02、03、08，真实商业最外层接线仍后续；324测试类别：各片；326完整范围、原证据、独立双审与父验收：09。

§8 Timer独立token观测及旧key/轮次约束与§6.1编码全契约虽不全在65行矩阵，也分别绑定02T/04与01。§5总体引擎容量是后续B0，不能据此擅改共享池补本片预算绿。当前状态始终failed/reopened，以上索引未勾完成。
