# 自动目录可靠消费前置技术设计（B0）

> **最新授权补充（2026-10-08）：** 用户明确允许恢复 R3-04 只读分析与设计修订，状态为 `READ_ONLY_DESIGN_REOPENED_IMPLEMENTATION_PAUSED`。编码、测试（含运行微probe）、共享配置修改、部署及真实数据库操作仍暂停；不豁免原池异步诊断风险，不改变其它池/非R3调用的日志，不自动扩大原DataSource factory或20路径候选范围。最新直接源码核对及剩余B03阻断见 [R3-04只读解阻分析](2026-10-08-r3-04-readonly-deblocking-analysis.md) §1～§4；目前无已证完整方案、无本轮独立预审或运行结果。下方暂停与旧A/B条目保留为历史，不再要求重复选择已拒绝的取舍；R3-03最新有限验收复用，R3-05+、B0整体及商业启用继续未放行。

> **当前有效状态补充（2026-10-07）：** R3-03 二次重开修正已完成独立规格、质量及父验收，记录见 `docs/superpowers/plans/2026-10-06-r3-03-reopened-repair.md` §7 与 `docs/集成测试.md` §7.85 的 2026-10-07 记录。下方原重开页首及历史记录不代表 R3-03 仍待修。整体 B0 切片3仍未验收；R3-04 保持 `PAUSED_BY_USER_BOUNDARY_UNRESOLVED`，R3-05+ 与商业启用不放行。用户本轮「按建议继续推进」不解除该明确暂停，不改变本文任何冻结强制契约。当前依赖与可独立华昇通信候选见 `docs/superpowers/plans/2026-10-07-business-closure-next-stage.md`；不是新预算片、迁移或商业通道授权。

日期：2026-10-05。当前交付状态：**B0切片3补核失败／重开（REOPENED_ACCEPTANCE_NOT_VALID）**，不得恢复下一片或启用B1；旧07切片3最终验收已由父补核裁定失效。本次仅同步状态与编制精确修复计划，未修复运行代码、未运行测试、未重新验收。以下原阶段说明保留为历史：V4A切片3重跑设计预审候选，未自行批准、未实施当时重跑修订、未通过切片3全部门禁。历史10冻结输入及`deleg_08c77a41`批准、01/06不通过记录与原报告保留；原先“创建者回滚后等待者无死锁”保证已被真实反例否定，不能沿用历史批准放行该条。既有切片1/2运行记录见§7.85，不在本次重新验收。用户已选择不新增协调表/哨兵、纯本地完整事务首次加最多一次重跑、维护窗口同版本切换且仅本地验证；R01/R02按本次批准的统一3500ms剩余预算准入、单池稳定包装及全局独立connect1500配置冻结于§7.2.1，尚待新独立预审；用户接受全供应链新建连接/冷启动影响，不是生产生效或端到端硬耗时承诺。run-04隔离实验经独立核原XML为12项全0，不能冒称生产代理+Druid全绿。本次仅四文档修订及仓外冻结预审输入，不启用A商业生产者、实际B消费者、自动商业或华昇。R3-02O-S rev1候选已交独立预审（`deleg_ffc1b748`）并返回passed=false，阻断B1/B2；用户裁定D1–D4（advisor粗过滤＋同事务参数化SELECT、LedgerJsonEncoding转public、删除Outbox reserve/bind死代码、两测试反射桥重写），本文已按裁定修订为rev2并冻结再次预审输入，仍未经passed、未实施代码或测试。rev2候选再交独立预审`deleg_045e4f3c`，返回passed=false，阻断BLK-1~4；用户已逐项裁定（BLK-1零DB放行收窄为可证明安全集合并补组合附标/预组合/零权重候选边界、BLK-2父确认两重复文件备份移出、BLK-3 LedgerJsonEncoding调用者表述修订、BLK-4 D4范围枚举改test02–test08或全部Outbox段，及四项非阻断修正），本文已按裁定修订为rev3并冻结独立预审输入，仍未经passed、未实施代码或测试。

关联：

- 主设计：`docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md` §5、§6.1、§7.1、§9、§13。
- 分片计划：`docs/superpowers/plans/2026-10-04-buyer-catalog-bridge.md` Task B。
- 基础设施既有修复：`docs/superpowers/specs/2026-08-20-timer-outbox-schema-repair-design.md`。
- 联调及实际证据：`docs/集成测试.md` §7.85；本文测试矩阵不是实测记录。

## 补核重开状态与必要纠偏（2026-10-05）

### R3-03／R3-04 二次补核重开（2026-10-06）

用户批准按补核建议继续修正。本轮起点为 `cd552ba68feef43a5da7545710870a7819d0d477`，该提交由用户明确授权产生，保留不改写。当前授权是设计、实现与隔离本地验证，不包含新的暂存、提交、推送、部署或真实业务库操作。

原 `parent-r3-03-final-acceptance.json` 与 `parent-r3-04-final-acceptance.json` 保留为历史报告，**完整协议验收结论失效（REOPENED_ACCEPTANCE_NOT_VALID）**。第48／54轮完整profile与第49／55轮API契约的既有通过结果仍是原受测快照的运行证据，不能证明未覆盖条款，也不能沿用为修正后候选的门禁。

- **R3-03 未闭合项**：Java大小写比较器不能替代数据库Collation；原始字符串Map不执行Collation等价身份合并；最外层事务归属、嵌套提前flush、最终元数据、flush后领域锁与RESERVED守卫须逐项补核，不以7项测试总绿推断完成。
- **R3-04 未闭合项**：回填硬编码载荷版本1；核验漏版本／比较协议、孤儿BOUND及身份逐字一致性；`SmartLifecycle.start()`早于`ApplicationRunner.run()`，Runner的`@Order`不能建立引擎启用屏障。原源字段快照不含计划、租约和审计时间，不能称全字段证明；合法MANUAL(90)状态亦须覆盖。
- **推进顺序**：先冻结R3-04完整修正条款及实际启动调用链，独立设计预审后逐项RED→GREEN；再修R3-03。两项重新取得独立规格、质量及父验收前，暂停R3-05及依赖它们的后续切片。Task A与切片1／2不在本次重开范围。
- **保护边界**：A39冻结文件与历史迁移保持不变；引擎启动屏障涉及进程整体影响，须先核保护清单与最小改动范围。未确认旧writer／引擎全部停止及在途排尽时，不宣称维护切换可启用；本轮不执行部署。

本记录仅纠正状态和开启修正，不表示新测试、修复或再次验收已完成。

父裁定及完整证据位于`/Users/mac/.hermes/cache/scratch/task-b0-ledger-evidence/slice3-reacceptance/parent-reacceptance-verdict.json`与`handoff.json`。独占完整verify的Maven/runner均exit1：270原XML、2321执行、6failure、21error、0skip；额外7类API契约45执行全0不能抵销完整门禁失败。4039源／受测文件无漂移；两类BeforeAll阻断25个源方法。规格与质量亦failed，旧07关闭依据失效，A及切片1/2不在本次重新验收。

F01–F13精确修复片、完整条款索引、真实TDD正反例、运行失败归因队列及A39待确认边界见[切片3重开修复计划](../plans/2026-10-05-b0-slice3-reopened-repair.md)。父先复核文档与授权边界，再仅派一个有限垂直实施片；不能一次泛化委派全部预算协议。此记录不改变下文获审§7.2/§7.2.1/§9协议：真实源INSERT与绑定1行、完整BCS/LEGACY比较、真实最外层集合/collation排序/flush、旧Timer直接BOUND回填和维护切换、allowlist/TLS失败关闭、setup/SESSION/缓存、flush后与commit guard、公开API及严格清理、真实REQUIRED代理和完整重跑故障门禁均必需，不降成建议或TODO。

旧无retry反例的原测试字节已被改成断言真实deadlock的GREEN，历史“原RED未改转GREEN”不成立。原测试SHA、原diff、日志/XML及当前反例分开保留，新完整恢复测试另立证据；不能覆盖历史或以第二次成功宣称锁原语无死锁。本轮只写文档和仓外交接，未运行测试、未改运行字节、未批准部署或商业接线。

R3-02O-S预审回执（2026-10-05）：rev1候选交独立预审`deleg_ffc1b748`，结果passed=false，阻断B1/B2。用户随即裁定D1–D4并授权仅按裁定修订设计：D1旧appendLocal隔离改为Java粗过滤快路径＋候选时调用方同事务连接参数化只读SELECT（utf8mb4_0900_ai_ci collation等价、零锁副作用），机制固定为程序化Advisor（spring-aop核心类NameMatchMethodPointcut指向bean outboxEventRepository＋方法appendLocal，不加aspectjweaver/@AspectJ/新依赖），须在main实际装配并以AopUtils.isAopProxy断言，对已核14处appendLocal调用点/7个bean逐点证明该SELECT不成为事务第一条一致性读，写入设计；独立连接方案废弃，容量§5不受影响。D2 LedgerJsonEncoding改public（保留final、private构造不可实例化、算法与预算常量不变；写明非A39、仅暴露静态工具语义、ArchUnit不增加新限制，底座仍嵌套在OutboxEventRepository）。D3删除DefaultAppendScheduleIdentityLedgerService的Outbox reserve/bind死代码（Timer方法不动），ArchUnit白名单明确OutboxAppendIdentityMapper写方法仅允许底座（及测试）访问，其它生产类禁依赖；OutboxAppendIdentityDO等按需调整权限。D4两测试（AppendScheduleIdentityLedgerIntegrationTest的Outbox段、LedgerComparisonEntryContractTest的Outbox断言）按同模块privateLookupIn反射桥驱动main底座重写，原字节+SHA归档后重写；Timer段保持公开API不动。预审R1–R9及O-S01~O-S11矩阵条款全部保留不得降低，O-S08补充生产装配断言、advisor SELECT零锁副作用/语句计数、快照时序负例三项；具体参数化SQL与粗过滤边界冻结于§7.2.1。B完整BCS schema未冻不造接口字段，B入口继续失败关闭；不新增生产LEGACY入口、不改旧appendLocal行为语义。修订后rev2候选仍为`DESIGN_SEPARATION_PENDING_INDEPENDENT_REVIEW`，交父再次独立预审；不自行通过预审、不实施代码或测试。rev2预审回执（2026-10-05）：rev2候选交独立预审`deleg_045e4f3c`，结果passed=false，阻断BLK-1~4。用户已逐项裁定并授权仅按裁定修订设计：**BLK-1**批准零DB放行收窄为可证明安全集合（null、码点<31、含ASCII非[A-Za-z_]码点），其余一律参数化候选SELECT；O-S08b补充U+0301/U+034F等组合附标插入、é→E预组合替换、零权重候选边界，真实MySQL验证后逐项固定。**BLK-2**父已确认两重复文件（`infrastructure/ledger/LedgerJsonEncoding 2.java`、`catalog-reliable-consumption-prerequisite-design 2.md`）授权备份移出并逐文件核验SHA（0c1ac825…、3accfbc5…，见`r3-02o/blk2-parent-confirmation.json`），正式两文件未改动。**BLK-3**批准将『底座仍是其唯一生产调用者』改为『除账本服务Timer路径既有调用者外，底座是唯一新增跨包生产调用者；ArchUnit不为LedgerJsonEncoding新增限制』。**BLK-4**批准D4范围枚举改为test02–test08（或全部Outbox段），设计/计划/集成记录三处同步。非阻断同步修正：冻结表#6首调用方法名改createPaymentOrder、#9/#12字面首读表述精度、O-S08b的data_locks断言改probe前后增量对比、设计补充main显式注册auto-proxy creator（DefaultAdvisorAutoProxyCreator或BeanNameAutoProxyCreator，均spring-aop核心类无新依赖）的装配措辞。修订后rev3候选仍为`DESIGN_SEPARATION_PENDING_INDEPENDENT_REVIEW`，交父再次独立预审；不自行通过预审、不实施代码或测试。

### 2026-10-06 P0 依赖校准与 R3-03 独立隔离重开（设计先行）

- **原冻结强制契约（R3-03 规范性条款，不因补核放宽，仍以原章节原文为准）**：§7.2(5)（真实写点记录事务内刷新意图、整批/级联结束后只 flush 一次、中间意图合并保留最终真实版本、flush 后不再取得新的领域行锁、嵌套方法不提前 flush、最后一行/最终版本不漏、flush 后新增领域锁或意图使整事务失败关闭）；§7.2.1(6)（Outbox 账本在前 Timer 在后、表内按业务 unique 列顺序比较完整键、数值按数值、字符串按源列实际 `utf8mb4_0900_ai_ci` 等效排序/等价关系、收集器在取得首个账本锁前完成身份集合/等价合并/异载荷检查及排序、排序键可由无实体表参数化 SQL 按同 collation 确定、不提前建立领域 RR 视图、锁后发现新意图整事务失败、自增空洞正常）；§7.2.2 全部协议意图（最外层归属、单次 flush、双向 beforeCommit 守卫、afterCompletion 清空）——实现算法由本次修正文本替代，不散落历史矛盾；§7.2.1(1–5) 排他建槽/nonce/RESERVED→BOUND/CAS1（经已闭环 R3-02O/02T 底座复用）；§9「追加/调度唯一键」「间接锁环」两行中 R3-03 部分（多槽排序/合并、第二槽及后续槽/源/绑定失败整批回滚、RESERVED/flush 提交守卫、不能仅靠 mock 锁序或死锁重试）；F10。
- **后加候选/依赖（已闭环，复用不重验）**：R3-01 `LedgerJsonEncoding`（canonicalBytes/legacyMatches）；R3-02O 私有底座 `TrustedOutboxAppendCore`＋O-S07 祖先 localRollbackOnly 观测 manager（`SupplyChainTransactionObservationManager`）；R3-02T `DatabasePersistentTimer.scheduleOnce` 账本路径；D1 advisor 旧 appendLocal 隔离。R01/R02 预算与 V4A 重跑为 §7.2.1 已冻结但实施未开始的规范：R3-03 不接入预算包装、不动 coordinator/`BudgetContext`；V4A 完整事务重跑属 R3-08。
- **部署前置**：R3-03 无新增 DDL/迁移/部署前置；不需要 R3-04（旧 Timer 回填/启动切换）先行——新键直接写账本，跨事务重复走既有 BOUND 重复路径；不要求商业生产者/消费者接线（§9 明文：技术领域 fixture 证明最外层完整 worker 的同构收集/flush 边界，真实商业 saveBuyer/初始化/项目级联留后续 B 接线验收）。
- **隔离验收声明**：R3-03 可独立隔离验收（运行依赖全部为已闭环项＋061 既有两账本表＋`SystemIntegrityException`）；R3-04 保持 `PAUSED_BY_USER_BOUNDARY_UNRESOLVED`，不因 R3-03 进展解除暂停；R3-05/06/07/08 未开始、商业启用不放行。§7.2 撤回的单槽 1213 反例保留；一次 A/Z 并发成功只证排序；完整事务重跑属后 08，不擅改 R3-03 为所有死锁绝无。
- **本轮产物与边界**：仓内仅三份既有文档 patch＋新计划 `docs/superpowers/plans/2026-10-06-r3-03-reopened-repair.md`；仓外证据根 `task-b0-ledger-evidence/slice3-reacceptance/r3-03-repair-20261006/`（design-handoff.json / clause-matrix.json / report.md，含 HEAD、git 状态、输入/源哈希、完整 SQL/接口算法/真实调用链/白名单/每条 RED 负例）。状态 `DESIGN_PENDING_INDEPENDENT_PRE_REVIEW`：未实施、未运行测试、未验收；完成即交回，不自主派审查。

## 1. 目标、依据与范围

B0先提供可用于B本地目录消费的事务栅栏，修复Outbox/Timer宕机恢复、失格执行者与Inbox状态协议。只改善成功标记的CAS不足以阻止旧worker提交本地业务。

依据为原仓`dev / de471b0af16ad3469e9b56acd8ebc7d27b42a69f`的实际引擎、Mapper、服务、Handler和迁移。只读草案`deleg_65d7c4c6`已结束；父从完成记录及被截断的可见条款恢复技术线索，再独立回读源码落本文，不声称自动提取了完整子报告，更不把草案当作批准。

### 1.1 当前已核事实

1. 现有LOCAL接口为`OutboxDispatchHandler.dispatch(OutboxEventDO)`，注册器按targetName分派；没有已核实的LocalOutboxHandler类型。
2. 现有Timer接口为`PersistentTimerHandler.handle(TimerTaskContext, JsonNode)`；TimerTaskContext仅有taskNo、taskId、attemptNo、fencingToken、workerId，不能假设已有version、payloadVersion或maxAttempts。
3. Outbox和Timer扫描/claim均只接受PENDING/RETRY，不能恢复过期CLAIMED/DELIVERING或EXECUTING。启动、续租、完成、失败目前未一致检查更新行数与租约有效期。
4. TimerClaimService.complete/fail目前在任务更新0行时仍可能追加成功/失败审计。Outbox死信把available_at设NULL，与最终DDL不符；TimerMapper已保留非空next_fire_at，不能误报其也存在同一NULL错误。
5. 心跳当前共用执行工作池；该池实际为ScheduledThreadPoolExecutor，不是注释承诺的有界业务队列。清理主要按任务ID，会使旧token误清理新token续租。
6. Inbox重复事件目前未比较摘要，非SUCCESS均认为可处理；InboxAttempt不返回真实version，RETRY未先迁回PROCESSING，MANUAL未保护。
7. scheduleOnce复用同type/key旧handle，未核对载荷；重复键后普通读可能受已有RR视图影响。完成或死信旧key不是新的执行轮次。

### 1.2 最终结构，不修改历史迁移

供应链库真相源为sc_*表；无跨库、物理外键或新增RPC依赖。

- `008-timer-inbox-outbox.sql`定义状态、预算与唯一键。
- `009-buyer-integration.sql`使Outbox target_type允许LOCAL/RPC/HTTP，并增加采购商目标配置列；引擎支持情况不能由DDL支持推断。
- `015-timer-outbox-schema-repair.sql`已经增加Outbox.version，不得再次新增该列。
- `022-database-local-time.sql`使时间COMMENT为Asia/Shanghai；available_at及next_fire_at仍NOT NULL。
- Outbox唯一键为`(aggregate_type, aggregate_id, aggregate_version, event_type)`，不含target、payload或eventId；aggregate_id上限64。
- Timer唯一键为`(task_type, idempotency_key)`，task_type上限64、key上限128；执行审计唯一键为`(task_id, attempt_no)`。
- Inbox唯一键为`(consumer_name, event_id)`，摘要BINARY(32)，version参与状态CAS。

租约/预算复用已有列；为§7.2/§8确定消环，拟新增两张独立不可变身份账本表。账本只记录追加/调度身份，不是账户或资金账本，不归资金DAL；消费者不能读取其自身源身份账本。需新索引时先由真实MySQL查询计划证实，表和索引均追加下一可用独立迁移；不修改008/009/015/022或A已受测060的校验和。

## 2. 状态、库时钟与失格定义

Outbox：0 PENDING、10 CLAIMED、20 DELIVERING、30 RETRY、40 PUBLISHED、50 DEAD、90 MANUAL。

Timer：0 PENDING、10 CLAIMED、20 EXECUTING、30 RETRY、40 COMPLETED、50 CANCELLED、60 DEAD、90 MANUAL。

Inbox：10 PROCESSING、20 SUCCESS、30 RETRY、90 MANUAL。

实现用集中枚举/常量，不把上述状态散落为业务字面量。所有租约判断、调度及回收均使用SupplyChainDatabaseClock的库本地时间；相等视为已过期，即有效条件为lease_until > now、回收条件为lease_until <= now。系统单调时钟仅用于耗时，不决定业务状态。

失格包括：owner/token不匹配、租约为空或已过期、状态不再是本次合法前态、任务已取消/终结。失格不是本次业务失败，不得由旧worker改新owner的状态、覆盖错误或追加成功审计；保留事件/任务等待当前owner或扫描器处理。

## 3. 领取、恢复及全转换CAS

### 3.1 扫描和预算

短事务按既有优先级/时间/id顺序使用FOR UPDATE SKIP LOCKED读取有限批次，候选条件分组明确：

- 普通：PENDING/RETRY，计划时间<=now，lease为空或已过期。
- 恢复：CLAIMED/DELIVERING或EXECUTING，lease非空且<=now；不受旧计划时间再次推迟。
- 终态、MANUAL和CANCELLED不进入自动扫描。活动状态却lease为空属于协议损坏，稳定记录/报错，不能无限占扫描批次或凭空取得合法token。

锁住当前行后重新取库时钟并核验版本、前态、到期条件及预算。attempt_count<max_attempts才可领取；成功CAS同时递增attempt_count、fencing_token、version，写新owner/lease并清旧错误。既有语义为领取即耗一次预算，包括领取后尚未进入Handler就宕机；本片不改成完成后才计数。

attempt_count已经等于max_attempts的到期候选直接转DEAD，不再递增次数、不得制造第max+1次尝试或违反CHECK。清owner/lease并使旧token失格，递增version，写稳定预算耗尽/租约过期原因；保留非空计划时间。过期扫描是其恢复路径，即使worker从未调用fail也能收敛。

### 3.2 转换表

| 动作 | 必须核验 | 结果及0行处理 |
| --- | --- | --- |
| 普通/过期领取 | 当前version、合法前态、到期条件、预算 | 1行才返回claim；竞态0行跳过，不返回虚构claim |
| 启动Handler | CLAIMED、owner、token、未过期租约 | 迁DELIVERING/EXECUTING；0行终止，不进入Handler |
| 续租 | 活动态、owner、token、lease仍有效 | 不得在到期后、尚未接管时复活旧租约；返回明确结果 |
| 成功 | 实际执行态、owner、token、lease有效、当前version | 同事务清租约、写成功；0行不成功且不写成功审计 |
| 可重试失败 | 活动态、owner、token、lease有效、预算未耗尽、当前version | RETRY、非空下一时间、清租约；0行只报告失格 |
| 次数耗尽/死信 | 合法活动态或锁定的到期耗尽行、当前version | 终态、清租约、稳定错误；Outbox保留非空available_at |
| 取消 | 保持现有PENDING/RETRY且无有效租约、当前version | 不扩大为任意取消正在执行任务，不在B0改变业务取消语义 |

不能把领取时的version永久作为后续expectedVersion：心跳也会递增version。各转换在自己的短事务内锁/读取当前version，再结合不可变owner/token及状态/时间CAS；事务型执行器则持有该行并跟踪本事务内更新后的version。

Outbox.fail不再给available_at赋NULL；DEAD通过状态排除扫描，时间保留最后合法值用于审计。Timer继续沿用已存在的保留next_fire_at规则。

### 3.3 Timer审计与失败处理

任务完成/失败转换与相应execution追加在同一事务。只在转换1行时写审计，不根据异常名称或日志意图伪称转换成功。execution的task/attempt/token/worker取真实claim，成功result与失败error摘要分别写正确字段；异常保留因果，脱敏，不将失败摘要填入result_digest。

固定回收规则：扫描器只把耗尽任务转DEAD及写稳定原因，不新增或覆盖execution。领取后尚未启动就宕机没有可信startedAt，不能用领取/回收/续租时刻填充NOT NULL的started_at。真实Handler调用开始时间由本次执行上下文保存，只有合法complete/fail的1行转换才同事务追加其真实execution；中途宕机没有结果审计的事实保留在任务预算/原因中，不伪造结果或另开审计世代。

Handler准备失败另有封闭分支：registry.require、载荷解码或启动转换尚未成功时，执行上下文没有Handler startedAt。准备异常须先穿出执行事务代理、确认回滚，再在短失败事务核真实owner/token、当前version、活动前态及租约，按持久化max_attempts安排RETRY或DEAD，转换0行只报告失格；不写execution，不再次调用缺失Handler的maxAttempts。启动0行不进入Handler也不以旧worker安排失败。只有在合法启动之后、实际调用handle之前紧邻取得库时钟，才设置本次Handler开始时间；调用异常随后合法fail才有真实失败execution。不得用准备阶段时刻或catch中的now补started_at。

普通业务异常使本地业务事务回滚，再由仍持合法租约的短失败事务写RETRY/DEAD；租约已失格时不写该失败，等待回收。失败转换本身SQL异常不得吞掉或仅日志后宣告死信。终态warn必须在数据库终态提交之后。

## 4. 本地事务栅栏与外部边界

### 4.1 拟新增类型及明确分派

以下均为拟新增，不冒充已有API：

- OutboxDeliveryContext：event内部ID、eventId、owner、fencingToken、attemptNo；只能由已核claim生成，不信任载荷中的token。
- TransactionalOutboxDispatchHandler：现有OutboxDispatchHandler的事务型扩展，新增带上述context的分派入口；无上下文的旧入口必须失败关闭，禁止兼容fallback绕过栅栏。
- TransactionalPersistentTimerHandler：现有PersistentTimerHandler的事务型扩展/标记，显式要求其全部业务写加入执行器事务。
- LOCAL事务执行器与租约守卫：infrastructure只负责源行、状态及回调协议，经接口调用Handler，不反向依赖目录DAL或资金服务。

保持已有接口和注册器；注册器识别明确模式，B新目录/转发/巡检Handler必须是事务型。重复target/taskType注册、未知模式或带事务型标记却走旧入口，启动/执行失败关闭，不默默当普通Handler。

### 4.2 新B LOCAL Outbox提交单元

真实供应链写事务的固定顺序：

1. 当前锁源Outbox行，核context与DELIVERING、owner/token及有效租约；确认数据库载荷/身份与claim一致。
2. 当前锁Inbox，核摘要和状态，取得真实version；SUCCESS重复跳过业务但仍以本次合法源claim完成交付。
3. 调用单LST生命周期，或只展开范围页，不同时锁整页LST。
4. 商业版本、投影、目录游标、子Outbox、下一页/后续Timer写入同事务。
5. Inbox SUCCESS CAS；源Outbox PUBLISHED CAS；全部转换必须1行。
6. 在事务提交前同步守卫再次取库时钟核验此次有效租约与上下文；失格抛异常使全部回滚。

源Outbox锁保持到事务结束；锁期间新的领取者SKIP LOCKED不能接管，外部心跳可能暂时无法更新该行。执行器可在入口合法时延长一次租约，但不能在已过期后续租；业务应是有限短事务，超出租约必须提交前失败回滚，而不是因为“仍持锁”永久合法。成功转换清租约前保存本事务的有效lease_until用于最终时间校验，不能因其已被自身清空误判；不得将提交前校验放在afterCommit。

严格SQL同事务收敛避免新的B消费者出现“商业已提交，Inbox/Outbox未成功”的双提交窗口。若在最终提交前过期，已执行SQL也必须回滚；没有任何领域写可在REQUIRES_NEW里逃逸。CurrentSupplyEligibilityReader的独立只读事务为主设计规定的唯一相关例外，不写这些效果。

### 4.3 新B Timer提交单元

当前锁源Timer→核EXECUTING/owner/token/有效租约→调用应用服务→子Outbox/续页/下一轮Timer→源Timer COMPLETED及真实execution成功审计→提交前时间守卫，一次提交。引擎不得在finally再独立complete一次。

Timer Handler不直接访问目录DAL；其委派的写生命周期加入执行器事务。涉及Inbox的Timer路径继续按源Timer→Inbox→LST顺序；Outbox路径按源Outbox→Inbox→LST→BPD→BuyerPricing→availability。保留A的receipt→LST→SKU前缀；不锁供给/SKU/项目，不临时给route加FOR UPDATE。

### 4.4 现有Handler的兼容承诺

当前已发现的Outbox Handler：BuyerEventRpcDispatchHandler、SupplyChainOrderEventDispatchHandler、FulfillmentOperationDispatchHandler、TaobaoFlashChannelNotifyDispatchHandler；Timer Handler：FulfillmentOperationExecuteHandler、RechargeFrequencyWakeHandler。O2O/Instore恢复应用服务不据名称自动算作已注册Timer Handler。

本片固定六个旧Handler均保留兼容非持源锁模式，不在B0顺带转换旧业务提交边界：

| 现有Handler | 实际路径 | B0模式与不得扩写的结论 |
| --- | --- | --- |
| BuyerEventRpcDispatchHandler | 解析JSON并记录采购商事件日志 | 兼容；名称不是已发送RPC/通知证明 |
| SupplyChainOrderEventDispatchHandler | 解析JSON并记录订单事件日志 | 兼容；不宣称已执行业务通知 |
| FulfillmentOperationDispatchHandler | 按operationNo调用scheduleOnce | 兼容；加强调度幂等不等于与旧源完成同提交 |
| TaobaoFlashChannelNotifyDispatchHandler | 调外部渠道端口的支付/退款通知 | 兼容；原外部端口至少一次，不持源锁跨外呼 |
| FulfillmentOperationExecuteHandler | 独立领域认领/状态转换及OperationExecutorPort.execute | 兼容；保留原领域令牌、未知结果及恢复边界 |
| RechargeFrequencyWakeHandler | prepareWake及query，含独立领域事务和外部查询 | 兼容；保留原queue/wakeGeneration幂等及预算 |

不得将含SDK/RPC/HTTP的Handler整段塞进持源行锁的长事务。旧Handler只增强基础设施启动/续租/完成资格；业务已提交、源完成前宕机仍可能重投，保持原业务幂等和领域令牌，不据源CAS宣称远程恰一次。将来如转换FulfillmentOperationDispatchHandler，须另审operationNo兼容与源/调度同提交，本片不做。B新Handler则一律显式事务型，不能借旧兼容模式绕过§4.2/§4.3。

当前Outbox引擎有HTTP“未实现”日志后publish分支，必须失败关闭：未真正执行投递不得PUBLISHED。B0不顺带实现HTTP/RPC传输、不注册新外部依赖；对未支持target_type返回稳定未实现错误并按合法预算处理。已有通过LOCAL Handler委派的RPC/HTTP路径保持原协议，不把DDL支持RPC等同于引擎已支持直接RPC分支。

## 5. 心跳、容量与进程生命周期

业务池改为明确容量的工作队列，扫描只领取可承接的有限数量。心跳使用独立调度资源，不与可能等待SDK/数据库的业务工作池共用；不能用未界定的ScheduledThreadPoolExecutor队列声称背压完成。

- 续租注册键为`(sourceKind, id, fencingToken)`，清理只删除本次token对应实例，旧finally不能移除新token续租。
- 心跳短事务可用SKIP LOCKED检查源行，封闭返回RENEWED、SKIPPED_BUSY、LEASE_LOST、TERMINAL；持事务型源锁时的忙不是续租成功，也不能据忙直接修改业务状态。源事务的最终同步守卫决定能否提交。
- 失格/终态取消本次续租，不再无界重试。续租SQL失败按错误码限频保留诊断；不能成功续期也不能伪称已失格，最终当前读负责裁决。
- 配置启动校验：正扫描周期/租约/续租周期，renewInterval<leaseDuration；正worker数、队列和batch容量，合法退避上下限。预算来自已持久化max_attempts，不在重启后改用Handler新声明扩充旧任务。
- stop先停止扫描，限定时间关闭业务和心跳资源，保留未完成数据库租约供下次恢复；不批量将未提交任务伪改成功/失败，不碰他人进程。

连接与等待边界也必须显式：本进程实际供应链池是DruidDataSource，不假设Hikari或另一个事务管理器。实施时配置并验证有限的连接获取maxWait、语句/事务超时、InnoDB锁等待及shutdown等待；租约有限而连接等待不能无限。提交前守卫或rollbackOnly异常必须穿出真实执行器事务代理，确认业务已回滚后才允许短失败事务。

真实绑定位置固定为`infrastructure/configuration/SupplyChainDataSourceProperties`与`SupplyChainDataSourceConfiguration.supplyChainDataSource`：前者目前只是DataSourceProperties空子类，必须显式声明并校验供应链Druid容量/等待属性，后者在实际构造的DruidDataSource上应用maxActive/maxWait；不能仅往YAML新增字段或假设initializeDataSourceBuilder自动绑定它们。容量守卫读取实际池有效值，验证有限等待确实生效；会话/语句/事务超时同时保持原UTC+8初始化及独立事务管理器。默认值与默认引擎总并发须相容，HTTP、Liquibase、嵌套Reader/Timer观测及旧Handler夹具均提供相容配置。只检查配置对象不等于实际池已受限。

容量校验采用保守上界：P为供应链maxActive，W为两个引擎最大并发业务连接总数，R为嵌套新只读Reader/Timer观测的最大额外连接，S为扫描并发，H为独立心跳并发，Q为前台预留；要求正值且P至少为W+R+S+H+Q，领取由实际worker/queue余量背压。心跳线程独立不等于连接硬保留，启动及运行指标须分别报告线程/连接余量；本片验证饱和自身业务池不会占尽心跳所需连接，不宣称未受限的全部前台负载下永不饿死。连接获取超时属于技术失败，不伪称续租成功或立即失格，最终租约守卫/回收决定。若此容量条件无法满足，拒绝启动引擎，不靠增大租约掩盖连接死等。数据源等待配置影响整个独立SupplyChainServer，须包含HTTP事务、只读嵌套与旧Handler兼容回归，不能只测新目录夹具。

上述改变影响独立SupplyChainServer进程的全部已有Timer/Outbox业务，必须做兼容回归和启停测试；不修改其它独立进程的启动/事务配置。

## 6. Inbox状态协议

事务型消费中的tryStart必须加入调用方写事务；不能独立提交开始或成功。返回不可变attempt：inboxId、实际version、封闭结果PROCESS/ALREADY_SUCCEEDED/MANUAL_BLOCKED，不以裸boolean涵盖全部状态。

1. 唯一身份为consumerName+eventId。新规范见§6.1，校验受支持类型/载荷版本后才允许SUCCESS去重；不把trace/投递尝试/租约作为业务摘要。旧byte[]入口仍对原始字节SHA-256，不用新规范重算旧consumerName下的记录。
2. 首次建立PROCESSING/version0。Inbox同样禁止普通INSERT冲突后FOR UPDATE的共享锁升级路径：按§7.2.1排他建槽原语执行单行、无内容变更的`INSERT ... ON DUPLICATE KEY UPDATE id=id`，省略内部自增主键，唯一业务键仅`uk_sc_inbox_consumer_event`；成功语句之后当前锁读真实行并比较摘要/状态/version。重复分支不得改变原摘要、状态、version或时间，不以影响行数判断首次。Inbox不需要账本RESERVED映射，PROCESSING/SUCCESS/RETRY/MANUAL仍按本文裁决；其他SQL错误原样抛出并保留cause，失败穿代理整事务回滚，不在原事务局部重试。旧byte[]摘要命名空间不变，补同键三事务及旧入口兼容回归。
3. 摘要不一致为系统完整性错误，包括已有SUCCESS；不得借成功记录吞掉同ID异载荷。
4. SUCCESS同摘要跳过业务。PROCESSING同摘要在取得当前行锁后可恢复本地处理；新事务型协议不存在独立持久化“正在处理”而业务部分提交，历史未完成也需可重放的业务幂等。
5. RETRY同摘要先以当前version执行30→10并递增version，再返回新的真实version；MANUAL不得自动重试，返回阻塞并使来源不能伪成功。
6. SUCCESS/失败CAS必须要求真实当前version与PROCESSING，0行使本地单元回滚。不得传硬编码0作为重试版本。
7. 异常使业务和本次Inbox变更一起回滚，源Outbox/Timer在事务外用仍合法的claim安排退避；不在已失败的业务事务里独立提交Inbox SUCCESS。

同一事件并发靠Inbox当前行锁与源栅栏收敛，不同事件仍靠LST锁/来源商业摘要唯一键幂等；Inbox不是所有领域幂等的替代品。

### 6.1 冻结载荷编码与旧摘要兼容

基础编码同时强制执行§7.2「Q-R3-01-01：获批 JSON 保守资源准入」的每文档政策；该资源准入不改变下面精确字节语义与LEGACY比较投影。输入／解析／构树／数字展开／总输出在分配前有界，超限为保留不泄payload预算原因cause的SystemIntegrityException；不是不匹配，也不得以相同文本绕过。具体BCS业务Schema仍未冻结、入口失败关闭。

新B载荷比较使用显式协议名`BCS_JSON_V1`和payloadVersion：UTF-8、无BOM/空白分隔；JSON对象字段按Unicode码点升序，数组保持顺序，字符串值原样保存，不trim、大小写或Unicode归一，拒绝未配对代理项。字符串只转义引号、反斜线及U+0000～U+001F（控制字符统一小写四位\\u编码），其它Unicode直接UTF-8。先按各封闭Schema校验字段/类型，拒绝未知字段、重复JSON键、非有限数及不支持版本；整数身份字段不接受字符串或小数。数值用精确十进制去尾零后普通形式（不含指数、正号、多余小数零；零统一0），禁止浮点二进制舍入；null和缺失不是同一个值，固定Schema必须显式约定可选字段。时间统一Asia/Shanghai，文本固定`uuuu-MM-dd'T'HH:mm:ss.SSS`且无时区后缀；先严格校验再编码，不补精度猜时间。边界/桶及occurredAt取已冻结源值，不把重复调用的now加入摘要。

已声明的traceId、投递attempt/token仅为信封元数据，不进入规范化业务投影；operatorId/cause/真实源身份等业务审计字段保留，不能泛删名字含trace/token的未知字段。载荷规范化字节整体作SHA-256，BINARY(32)存完整摘要；身份元组使用相同基础编码。对象字段顺序差异一致，数组顺序、字段类型、null/缺失、Unicode不同值及业务时间差异按契约区分。

现有Inbox BINARY(32)无算法版本列。新事务型B消费者使用带协议版本的新consumerName（128字符内），旧consumer继续原始byte[]协议，不改其已SUCCESS摘要。不向新命名空间自动复制旧SUCCESS掩盖处理；未知版本/事件类型即使已有SUCCESS仍失败关闭。身份账本另保存规范协议与完整版本化业务payload；可在账本读取后按固定规范比较，无需迁移时猜测旧摘要。旧Timer载荷采用`LEGACY_JSON_V1`：沿相同基础JSON编码保留全部原字段，不投影/剔除trace，也不按B封闭Schema拒绝旧字段；保持JSON字符串/布尔/数值/null类型与数组顺序，数值按精确十进制值比较，旧时间字符串不格式化。不得把新Schema的必填字段或B元数据剔除规则施加到旧任务。

## 7. 稳定事件身份和轮次：B消费者启用前契约

本节是B1目录消费者使用B0能力的前置契约，B0只交付通用追加/调度保护和测试夹具，不提前新增目录生产者或空Handler。

### 7.1 独立消费流身份

新刷新事件使用独立聚合命名空间，不占SUPPLIER_OFFER/BUYER_LISTING等原商业聚合版本槽。建议聚合类型集中常量BUYER_SELECTION_CATALOG_REFRESH；event_type/targetName沿主设计封闭声明。aggregate_version使用协议版本1，不假称它是源商业版本，真实源版本在载荷及身份元组中。

规范化身份采用固定顺序的版本化JSON数组编码，不用含分隔符的不透明编码拼接，SHA256完整64位小写hex作为aggregate_id；不截断digest。技术Timer key采用`BCS:v1:`加完整digest，长度71，符合128上限。实现须有真实编码/长度/碰撞输入与Unicode/字段顺序测试，不能只验证示例。

| 节点 | 身份元组必须包含 |
| --- | --- |
| 根刷新 | 协议版本、固定消费流、ROOT、scopeType/scopeIdentity、触发来源类型/身份/真实版本、triggerSlot或已持久化commandIdentity |
| 范围页 | 协议版本、消费流、PAGE、rootIdentity、afterListingId、upperListingId |
| 单LST叶子 | 协议版本、消费流、LEAF、rootIdentity、listingId |
| 巡检根/续页 | 协议版本、消费流、SWEEP、库时钟轮次bucket、页边界 |
| 精确边界重算 | 协议版本、消费流、REEVALUATE、listingId、basisDigest、已冻结boundary时间 |
| 旧target转发 | 协议版本、消费流、LEGACY、原eventId及封闭转发意图 |

triggerSlot区分同源版本中不同已定义意图，不用当前时间、trace、attempt/token作为重试身份。管理HTTP重算没有客户端commandKey时，在真实受理事务分配并持久化新commandIdentity；重复HTTP调用是不同已受理命令，不宣传HTTP命令恰一次。相同持久命令/源事件的恢复使用旧身份，不每次重新生成。

### 7.2 独立不可变身份账本：确定消除重复追加等待源行

本版选择独立账本，而不是继续对活跃源Outbox重复INSERT或FOR UPDATE：拟新增`sc_outbox_append_identity`、`sc_timer_schedule_identity`，位于本模块基础设施DAL。两表分别使用与源表**同列类型/长度/排序规则**的四列聚合键、两列任务键唯一约束，保存不可变eventId或taskId/taskNo、target（Outbox）、payloadVersion、比较协议、完整版本化业务payload和createdAt。Timer不复制活动fencingToken到不可变账本。无物理外键，账本不保存status/lease/version等可变源状态，不因源终态删除或重开槽。

Outbox的eventId和Timer的taskNo在§7.2.1确认首次保留者后分配；Timer自增taskId只能在真实task插入后绑定。两账本允许**仅首次未提交事务内**的RESERVED→BOUND一次绑定（源映射暂空），完成前不能正常返回handle或提交；追加服务检查真实源插入及绑定均1行，异常穿代理回滚，提交前守卫拒绝残留RESERVED。已BOUND行的映射/载荷不可改；其他事务只能在首次提交后读到BOUND。迁移直接按既有任务建BOUND，不能用占位taskId或另行提交RESERVED规避依赖。

Outbox新增appendLocalIdempotent只服务显式保留的B消费流聚合命名空间；旧appendLocal保持原严格插入/异常语义，禁止用它写B保留槽。事件ID在首次槽中分配一次；追加协议全部加入调用方原供应链事务：

1. 不先用普通RR读判断“不存在”。两账本按§7.2.1冻结的单行排他建槽SQL，再在同一有效事务内按完整业务键当前锁读；不是普通INSERT失败后捕获1062再升级锁，不能跨已标rollbackOnly的代理继续。
2. 获得首次槽的事务持账本锁，插入新的源事件，并在同事务固化映射；首次槽和源事件任何一步失败全部回滚。中间未完成槽永不单独提交。源事件尚未提交，扫描器不能消费它。
3. 重复槽只比较账本targetType/name、payloadVersion、协议和业务payload，相同返回账本eventId，**不再次INSERT、读取或锁定对应源Outbox**；异载荷/target为完整性错误。原源四列unique保留为第二道保护，源eventId或聚合unique异常不能吞作成功，说明保留命名空间/账本映射被破坏。
4. 源消费者的领取、续租、业务执行、完成、失败、回收均不读取/锁定其自身追加账本。它可在业务结束阶段追加不同的子节点账本，子节点须属于§7.1无环的ROOT→PAGE/LEAF层级，不允许以自身或祖先源槽当新子事件。生产者只能创建ROOT；PAGE/LEAF槽由对应已持久源身份生成，禁止无关生产者占用消费者子槽。
5. 新B生产者在真实写点记录事务内刷新意图，所有相关领域写结束后再在同事务末尾同步flush；不以afterCommit、独立事务或裸内存队列交付。收集器归属**实际最外层供应链写事务**而不是最外层Java定价方法：整批worker逐行saveBuyer、初始化事件同步写链、publishBuyer之后的项目重建都纳入同一事务，整批/级联结束后只flush一次；中间意图按契约合并但保留最终真实版本。flush后不再取得新的领域行锁。多个Outbox/Timer账本槽按§7.2.1固定sourceKind及源索引的完整身份顺序追加，同事务重复槽先合并；消费者也先完成领域写，再按确定顺序追加子节点，随后只有已持锁的Inbox/源完成和提交守卫。测试须证明嵌套方法没有提前flush、最后一行/最终版本不漏，flush后新增领域锁或意图使整事务失败关闭。

消环依据不是测试代替设计：重复生产者即使持BPD/BuyerPricing，也仅等待不可变账本；执行中的源消费者不持/访问自己的账本，因此去掉“领域→旧源Outbox→LST→领域”这条回边。**撤回首次创建者回滚后等待者无死锁/逐一成功的保证**：空表/末尾键且无哨兵的MySQL8.4.11、RR、autoinc=2真实三事务证明，无内容更新排他upsert仍可出现业务unique上的X锁与X insert intention死锁（1213/40001）。原无retry反例日志/XML及断言保留，不改成GREEN；不增加协调表、哨兵或跨事务预建槽绕过。首次事务的新源尚不可消费；多槽排序/所有权与重复不等待源仍是必要协议，不能以retry代替锁序。新增§7.2.1的受限完整事务重跑只覆盖纯本地技术入口，不承诺第二次必成功或全仓无死锁。真实MySQL仍须验证已有槽、真实两等待者、旧RR、异载荷、第二槽整批回滚及持源锁重复不等待源；不符合边界的调用者不得接入。

### 7.2.1 冻结排他建槽SQL与首次所有权

此节是B0-R01的必要技术补正，不是已实现Mapper或运行证明。

1. 两账本DDL只设内部自增`id`主键及§7.2的完整业务唯一键；eventId/taskId/taskNo/reservation_nonce不另建可竞争unique，不添加UPDATE触发器或自动更新时间副作用。不允许调用方提供账本id。源Outbox/Timer原业务号与聚合/task键unique保持不变，严格INSERT的任何约束异常仍失败并穿代理回滚，不吞作幂等成功。
2. 单槽竞争固定为单行参数绑定的`INSERT ... ON DUPLICATE KEY UPDATE id=id`；INSERT列含完整业务键、技术绑定态RESERVED、当次`reservation_nonce BINARY(16)`、比较协议/完整业务payload/target及createdAt，源映射暂NULL，**省略id**。重复分支仅令既有内部id等于自身，不引用候选id或候选VALUES，不更新绑定态、nonce、载荷、映射或时间；不得改用INSERT IGNORE、REPLACE、普通INSERT/catch后FOR UPDATE、SELECT缺行后INSERT或多行upsert。此MySQL锁语义无法由Lambda表达，使用参数化XML Mapper，不新增注解SQL。
3. 语句成功后立即按完整业务唯一键`SELECT ... FOR UPDATE`读取真实账本；它是当前读，不能用RR普通查询、缓存、generated keys、LAST_INSERT_ID或upsert affectedRows辨认首次。不存在行、未知绑定态、错误归属或字段损坏均为完整性故障，整事务回滚。SQL语句错误不在该方法捕获继续或局部重试。
4. 仅当前读为RESERVED且nonce等于本次不可变保留标识时，调用者有权执行首次源INSERT；nonce在每次首次建槽尝试前产生，原事务内不变，不作为业务摘要或执行资格，不输出其原值。RESERVED但nonce不属本次是协议损坏；不能接管/补填。当前读为BOUND一律走重复比较/返回原映射，不因nonce或影响行数恰好相等认作首次。同事务意图已合并，追加服务完成绑定前不允许重入返回未完成映射。
5. 首次源INSERT返回真实eventId或taskId/taskNo后，按账本id+本次nonce+RESERVED作一次绑定CAS，必须1行，迁BOUND并固定完整真实映射；0行抛系统异常。该实际字段改变的绑定CAS与严格源INSERT的行数要求保留。CHECK须区分RESERVED映射全空和BOUND映射完整；真实事务提交前拒绝本次任何残留RESERVED。BOUND之后不再更新，迁移直接插入完整BOUND并设置不可变迁移保留标识，不复用首次运行占位。
6. 多槽排序固定Outbox追加账本在前、Timer调度账本在后，各表按其业务unique列顺序比较完整键，数值列按数值、字符串按源列实际`utf8mb4_0900_ai_ci`等效排序/等价关系；不能用Java默认locale或把外部原始值大小写/Unicode归一化代替。收集器在取得首个账本锁前完成身份集合、等价合并/异载荷检查及排序，排序键可由无实体表的参数化SQL按同collation确定，不提前建立领域RR视图。锁后发现新意图不能边发现边追加，必须整事务失败；自增空洞正常，不以连续id或其增量判定所有权。
7. 官方依据：[MySQL 8.0 §17.7.3](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)明确普通重复INSERT取得共享锁，首次插入者回滚后两个等待者可共享锁升级死锁；ON DUPLICATE KEY UPDATE重复分支取得排他锁（重复unique为排他next-key）。[§15.2.7.2](https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html)明确无变化upsert affectedRows受CLIENT_FOUND_ROWS影响，且多unique会选择不同匹配行；因此本节限制竞争索引、无内容变更和真实当前行所有权。不以“用了upsert”推断其它跨槽、源unique或全仓事务已无死锁。

#### V4A：纯本地完整worker事务重跑（独立预审候选，未接生产）

本次范围严格为纯本地账本基础完整worker服务与隔离技术领域fixture：固定可信业务身份/key及完整原输入，每次重新执行技术领域前置写、完整意图收集/排序/flush、账本建槽、真实源INSERT及映射绑定，返回值仅来自最终确认提交。不能只重跑upsert、源插入或绑定；每attempt新nonce、同attempt内不变。源持久attempt_count/fencing_token不因技术重跑增加，不能把技术attempt混为领取/业务预算。本次不接A选品/定价/RPC商业写链、不注册实际B消费者、不改引擎和六旧Handler；新业务语义列未决，不私设。

- 无活动外层的事务外编排器首次调用加最多一次重跑，总worker尝试≤2；真实代理worker显式使用`supplyChainTransactionManager`、REQUIRED写事务，不用REQUIRES_NEW逃逸外层。每次事务结束后重建事务及收集上下文，清理ThreadLocal/同步/连接资源。错manager、活动外层、read-only、rollbackOnly、嵌套重入或资源污染入口失败关闭。旧scheduleOnce加入更大业务事务时只把异常传播给事务所有者，绝不在局部追加服务重跑；含外呼的旧Handler不得整段包retry。
- **唯一允许条件是全部同时成立**：由本地账本锁阶段（建槽SQL/紧邻当前锁读，不含领域、源、绑定或提交）封闭产生专用技术标记；保留完整cause，遍历Throwable cause和SQLException nextException完整链、避免链环，命中同一个SQLException的`vendorCode == 1213 AND SQLState == 40001`；失败attempt的afterCompletion明确为ROLLED_BACK；代理已退出且实际事务/同步不活动、供应链DataSource及相关事务资源已释放；仍有下一尝试准入预算且未取消。不得按异常消息、仅state40001、Spring Transient/锁异常大类或任意1213重跑。
- 非本地阶段、1205、1062、3819及其它错误、提交异常、rollback失败、不确认回滚、UNKNOWN或COMMITTED、取消/网络/超时均直接传播并保留cause，不试图重新提交或补偿未知结果。第二attempt任何失败直接传播，尤其再次真实1213不得第三次。异常传播不允许掩盖原SQL与清理失败。
#### R01：共享剩余预算与失败关闭（本次冻结规范，待实施验证）

1. `t0=System.nanoTime()`在首次无事务编排开始前取一次，`commandDeadline=t0+15s`；首次及第二次共用，不reset。所有比较使用单调纳秒，`R=commandDeadline-now`。清理预留`C=1000ms`只是不再准入业务的余量，不是清理硬截止；最小可执行SQL预算1000ms，借连接cap1500ms。每attempt（含首次）统一保守准入`R >= 3500ms`，低于此值直接拒绝，不采用先前调查的2001ms动态阈值；取消或中断拒绝并保持中断状态。
2. 第一次确认回滚与全部资源清理后才可选10–100ms事务外退避`b`；退避前必须`R-b >= 3500ms`，退避后重新取nano检查`R >= 3500ms`与取消/中断。清理耗时、退避及两次借连接均计入原15s，不重置、不持连接休眠。
3. 预算路径通过同一现有raw池调用`DruidDataSource.getConnection(1500L)`，正数参数仅限制每轮队列等待而非整个调用硬截止；保留原idle验证/discard/重借循环这一非硬截止例外，累计borrow无有限上界，可超过15s或不能及时返回。raw返回后的第一步先检查cancel/中断及commandDeadline/业务余量；late连接必须清理，不得向manager暴露或启动worker；清理遵守R02.6的新预算严格隔离协议，不借用原pool内部discard例外放宽。返回后重新预算；若`floor((R-C)/1000ms)<1`，释放所借资源并拒绝（释放失败也必须记录并否决重跑）。成功取得连接时固定`E=min(commandDeadline-C, acquiredNano+6s)`；借后事务timeout为`min(6, floor((E-now)/1s))`，不足1秒不启动事务，禁止timeout=0。
4. 每一个实际Statement `execute*`/`executeBatch`之前重新检查取消及预算，`q=floor(min(5s, E-now, commandDeadline-C-now)/1s)`，并与已有正queryTimeout取较小值；`q<1`拒绝执行。必须在Spring/MyBatis设置后、实际执行前最后收紧，不能被Spring剩余TTL向上取整、后续setter或timeout=0覆盖；覆盖普通、prepared/callable及批执行、flush和缓存复用。InnoDB SESSION lock wait取`min(2,q)`秒且不得0，使用同一连接，不放宽已有更短正限制。
5. 连接局部socket/network限制取`min(7000ms, floor(min(E-now,commandDeadline-C-now)/1ms), 已有正限制)`，小于1ms拒绝。**raw借用返回后、向manager暴露前**先检查cancel/deadline并清理late连接（不得暴露manager/启动worker），通过后才记录acquiredNano/E，保存物理连接、原network/socket值并安装首个局部限制；此时尚未完成事务prepare，失败走受控清理，不返回裸连接。Spring6.2.18 `DataSourceTransactionManager.doBegin:284–306`的prepareConnectionForTransaction、get/setAutoCommit及prepareTransactionalConnection早于determineTimeout，故只覆写determineTimeout不能约束启动网络。预算Connection拦截全部可能网络的prepareStatement/prepareCall（含所有重载）、createStatement、metadata对象及其可能查询的方法、get/setAutoCommit、get/setReadOnly、get/setTransactionIsolation、catalog/schema/session/clientInfo、isValid等setup入口：委派前检查取消及E/业务余量、收紧socket，返回后重检；未识别/无法覆盖入口失败关闭，unwrap禁止成为业务旁路。Connector9.7 `ServerPreparedStatement:115–130`构造内serverPrepare在prepareStatement返回前就可发包，不能等execute拦截器才安装限制。网络结果返回后超期即抛预算失败，禁止继续使用返回对象。
   SESSION原`innodb_lock_wait_timeout`读取及SET使用同物理连接的**封闭非递归control通道**：仅固定参数化读取/正整数SET，非任意SQL、非新借连接；每条control语句前后同样预算检查/收紧socket、设置floor正query限制，不触发“为control再SET”的递归。读取原值成功前不得修改SESSION；局部目标取原正值与min(2,q)较小，记录每次实际变更。正常清理恢复原SESSION/连接限制；清理不因业务期限耗尽而跳过，也不借新连接作恢复。Connection启动/setup与SESSION读取失败、半设置、返回超期均不得开始或继续worker；回滚后恢复失败按R02.6隔离并禁止retry。 **cleanup模式与业务setup明确分离：** manager开始rollback/cleanup即进入独占cleanup模式，rollback与恢复autoCommit/isolation等不再被业务E守卫拒绝，仍沿同一连接保留最后有效正socket限制执行并收集异常；先rollback、Statement/SESSION及manager连接状态恢复，最后才恢复原socket/network并准许正常回池。失败转R02.6，不把超时吞掉后回池；cleanup不发新业务SQL、不产生retry标记、不享有硬截止保证。
   每个实际Statement以物理Statement身份保存初始queryTimeout，跨Spring/MyBatis后续setQueryTimeout调用记忆**观察到的最小正限制**（0仅表示调用意图，不能清掉已有正限制）；最后execute前取该记忆、floor预算q与当前正值的最小值。Druid/server prepared缓存复用不重置该最小值，在本次借用/缓存归还前恢复初始状态；无法可靠恢复/淘汰具体缓存entry即隔离整个物理连接，不以逻辑Statement.close冒称淘汰。不把同一Statement最小限制泄露给下一请求；全部恢复必须在Spring资源清理返回编排器前完成。
6. 最外层MyBatis flush之后的beforeCommit及实际commit前再次检查`E`、命令业务余量、取消、完整flush/RESERVED/真实映射guard，不新增检查SQL，不在flush后取新领域锁；超期全回滚。rollback必须执行，不因deadline/C已耗尽而省略。网络commit/rollback、恢复/丢弃与清理没有硬截止，不承诺15s硬结束。只有afterCompletion明确ROLLED_BACK、代理退出、事务/同步/DataSource/SqlSession等全部绑定资源释放且恢复/清理成功，才讨论第二attempt；UNKNOWN、commit失败、rollback/恢复/丢弃失败一律拒绝retry，保留原cause并附清理失败，不让finally覆盖原异常。

#### R02：唯一Druid池的稳定预算包装与connect1500（本次冻结规范，待实施验证）

> **2026-10-09 状态更新**：底层连接池已裁定由 Druid 迁移至 HikariCP，R02 的预算契约不变量
> （3500ms 准入、q 守卫、SESSION 隔离、双模式、恢复失败禁重跑）在 Hikari 链路上按等价语义重新落地；
> 基础设施淘汰协议由 Druid discard 改为 Hikari evictConnection + 驱动 abort。
> 详见 `docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md`。本节原文保留为冻结历史。

1. 目标真实依赖路径为Druid1.2.28、Spring6.2.18、MyBatis-Spring3.0.5、生产Connector/J9.7.0；run-04的DriverManagerDataSource/Connector9.1.0不是此路径证明。保留原`supplyChainDataSource` bean名，暴露一个稳定budget路由DataSource实例；raw仅为现有唯一Druid池，不新增池。`supplyChainTransactionManager`、生产SqlSessionFactory/MyBatis、DataSourceUtils与clock全部注入并绑定同一wrapper resource key，不得分别绕过raw。wrapper不能实现会被Spring剥离的InfrastructureProxy，也不是TransactionAwareDataSourceProxy；显式unwrap仅供受控诊断/丢弃，不成为借连接旁路。无预算上下文时透明委派现有池，非预算事务保持原语义。
2. 无事务编排设置可清理的预算上下文，wrapper仅在预算借用路径调用raw.getConnection(1500L)；raw返回后先检查cancel/deadline，late连接清理且不得暴露manager/启动worker，通过后才包实际连接/Statement。禁止Future或额外借连接线程伪造硬截止。预算专用JdbcTransactionManager在借连接后以R01重新收紧determineTimeout/ConnectionHolder TTL（不能沿用借前6s或Spring ceil）；普通路径不附预算。代理worker用该manager的REQUIRED，MyBatis会话/clock读共享其连接；ThreadLocal在每个退出路径恢复/清空，不影响非预算HTTP/Liquibase/六旧Handler、不把它们接retry。
3. **预算借用允许配置集合（每次预算入口核有效值，不写共享池属性）：** raw已init；`notFullTimeoutRetryCount == 0`；`createScheduler == null`；实际proxyFilters为空且无动态filter注入；`testOnBorrow == false`、`testWhileIdle == true`、`testOnReturn == false`；`defaultAutoCommit == true`、`removeAbandoned == false`；checker只能是**精确**`MySqlValidConnectionChecker`（不接受子类/替换），保留`usePingMethod == true`、`validationQuery == null`、`validationQueryTimeout == -1`默认验证组合。**true及该checker/validation旧路径明确允许，不再因idle验证存在而拒绝预算入口**；不关闭idle验证、不替换checker、不修改global validation。保留既有UTC+8 connectionInitSqls唯一初始化SQL；不新加初始化语句/外部钩子。物理驱动为单host直接Connector9.7 ConnectionImpl、默认StandardSocketFactory，不接受多host代理/自定义socketFactory/propertiesTransform；其它形式仅预算接入失败关闭，不反射修改池/驱动、不改变非预算现有行为。maxWait/maxActive/global socket等原共享值不变，connect仅R02.4获准的1500。入口无法锁定配置生命周期或发现运行期变更同样拒绝，不用校验后的共享setter竞争规避；实际运行有效配置及supported transport仍待验，默认匹配不等于生产可启用。
   **用户批准的原idle验证循环非硬截止例外：** Druid1.2.28 `DruidAbstractDataSource:74–115`默认testOnBorrow=false、testWhileIdle=true、validationQueryTimeout=-1；现有SupplyChainDataSourceConfiguration仅build并设置UTC+8 init SQL，本轮保留共享testWhileIdle=true及原验证设置。`MySqlValidConnectionChecker:38–83`的usePingMethod=true意味着强制`/* ping */ SELECT 1`，不是直接调用驱动ping；`ValidConnectionCheckerAdapter:40–74`仅正timeout才setQueryTimeout，默认-1未设正限制。`DruidDataSource.getConnectionDirect:1366–1453`验证失败discard后continue并重新进入getConnectionInternal(1500)，不是共用1500剩余值；即使retryCount=0也不能阻止验证失败循环，closed连接分支也会continue。**raw.getConnection(1500L)仅每轮正数队列等待，累计borrow无有限上界，可超过15s或不能及时返回**；wrapper不能拦截raw内部验证，也不把init/内部锁/physical create说成硬截止。命令起点仍共用nano15s；raw返回后先检查cancel/deadline并清理late连接，不得向manager暴露或启动worker。禁止Future借线程；不通过关闭TLS/idle验证或改checker/global validation补绿。
   **例外边界与真实适用前提：** 原pool内部验证失败discard的吞close异常/无强关闭证明只承认为既有验证路径例外；它不保证物理强关闭、不构成预算新污染清理成功，也不得扩为恢复失败/late连接/新预算隔离的宽松标准。新预算路径必须保持R01启动及SESSION/Statement/flush/commit guard与R02.6捕获holder/physical/socket、可观测直接关闭、独占disable/discard协议；清理失败仍禁retry。Connector/J9.7默认sslMode=PREFERRED，默认StandardSocketFactory不等于非TLS；不禁TLS、不改现非预算行为。尚未审定TLS捕获/关闭覆盖时，该预算实现不得启用；下一独立设计预审可按明确受控supported transport范围评价，未知运行配置与TLS支持仍待验，不假实现TLS支持、不声称生产可上线。需要扩大传输支持或修改池/驱动内部必须另审另授权；此次仅批准设计修订/预审，不批准实施。
4. 唯一获准的全局池增量为独立`aifuli.supply-chain.datasource.connect-timeout-millis`，类型int、默认1500，本次只允许1500，显式其它值（含0/负数）拒绝启动。DataSourceProperties子类必须实际声明/绑定/校验；raw build完成后、任何init/Liquibase/borrow前显式`setConnectTimeout(1500)`，保持已有连接时区配置。不改全局maxWait/maxActive/socket等，不新池、不部署。用户明确接受全部供应链HTTP、Liquibase、clock、六旧Handler新建连接及冷启动影响；这只是参数影响授权，绝不是这些入口加入retry。
5. 实施门禁必须沿Druid createPhysicalConnection→实际Driver.connect properties→Connector/J ConnectionUrl/HostInfo→socket连接确认非敏感connectTimeout有效值1500及优先级，不能只看getConnectTimeout/YAML。普通单host URL属性可被Druid传入props覆盖，但host-specific属性、propertiesTransform/custom socket、多host及其它未审形式可能改变HostInfo优先级；检测冲突/未审形式即拒绝启动，不输出URL、用户名、密码或原配置载荷，仅白名单非敏感字段。1500只约束单地址TCP连接尝试，DNS、多地址、TLS、认证、初始化查询及commit/rollback均无此硬截止；必须分别取证，不伪称整次新建连接≤1500ms。
6. **恢复失败隔离/物理关闭可观测协议：** 任一网络/Statement/SESSION恢复失败即标记cleanupFailed，**即使随后成功关闭也绝不retry**；保留原业务/SQL cause，附加原恢复异常及每项关闭/池清理异常。借用最初保存`pooled.getConnectionHolder()`、`holder.getConnection()`的直接ConnectionImpl及`physical.getSession().getProtocol().getSocketConnection().getMysqlSocket()`的实际Socket引用和身份，不能等disable后再取holder。只使用上述公开API，不访问Druid protected holder.socket、不反射、不改驱动/池内部。缺失/非直接物理类型、无法保存实际socket即预算接入失败关闭，不冒称可证明丢弃。
   cleanup所有者在事务rollback/资源清理阶段独占执行，禁止并发借用/重复回收。顺序固定：先保存引用及异常；`pooled.disable(error)`阻止普通close/recycle并清Statement缓存（其`DruidPooledConnection:219–228`会清holder，故保存必须提前）；对**捕获的实际标准Socket**直接调用`Socket.close()`并捕获真实IOException/Throwable，不经JdbcUtils，记录直接关闭调用正常完成、捕获socket身份一致及本地关闭状态作为辅助证据；然后同步`physical.abortInternal()`释放驱动资源，最后仅一次`raw.discardConnection(savedHolder)`处理Druid账户/holder。每步异常不终止后续隔离尝试，不使用已清holder的pooled.getConnection()，不走deprecated discardConnection(Connection)重复扣active，不再正常recycle。若缓存清理/disable抛出且无法证明pooled已隔离，禁止正常return/recycle，报告不可确认资源隔离并终止retry；不得擅改池内部来补绿。
   **不能作为关闭成功证据：** `DruidDataSource:1502–1540`的holder discard先JdbcUtils.close(connection/socket)吞异常，再改active/discard计数，boolean只是emptySignal；`ConnectionImpl.abortInternal:593–595`→`NativeSession.forceClose:191–215`→`NetworkResources.forceClose:43–90`同样吞关闭异常；`AbstractSocketConnection.forceClose:103–109`还清socket引用。因此单独abort/close正常返回、连接isClosed、holder.discard、discardCount或emptySignal都不足以证明物理关闭。可观测证据是**吞异常层外、提前捕获的实际Socket直接close调用及异常结果**，仅证明本地传输资源关闭、不承诺服务器立即观察断线或网络硬截止；Socket.isClosed仅辅助，不能单独通过。未知/custom或未审TLS封装无法保证捕获对象覆盖全部传输资源则该预算实现不得启用，不影响原非预算行为、不关闭TLS；Connector/J默认PREFERRED不证明非TLS，下一独立预审仅按受控supported transport范围评价，TLS支持仍待验，不宣称支持或生产可上线；本候选可编码的直接标准socket路径仍须独立预审与故障门禁。若物理close/abort/池清理任何异常或效果不确认，归为清理失败禁retry，不把abort后flag或discard计数用作补偿成功。普通pooled close绝不当物理丢弃，下一借用无污染/holder不回池只是必要交叉证据。

隔离证据根`/Users/mac/.hermes/cache/scratch/task-b0-ledger-retry-spike`：run-04 Maven/runner退出均0、原XML12项/0failure/0error/0skipped；两账本先以data_lock_waits证明两个真实等待者，创建者回滚后各有1213/40001、明确回滚/资源释放/失败领域与源账本无残留后完整重跑成功；真实1205/40001、源1062/23000、绑定3819/HY000均不重跑。500ms deadline加真实阻塞约1062ms才全回滚，直接否定硬中断解释。此为独立核验既有隔离运行证据，未重跑门禁；第二attempt再次deadlock、UNKNOWN/提交异常/rollback失败、真实代理+Druid、多槽/collation/旧RR/旧Timer回填等仍未全证，§9新增门禁保持未完成。原run-01 fixture失败及原无retry反例不改写。

迁移与切换：先追加表及对既有Timer的身份映射回填，复制原taskId/taskNo、payloadVersion及完整JSON，按`LEGACY_JSON_V1`比较，不改变源任务状态/次数/计划时间或物理外键。新B Outbox命名空间当前无既有行；发现有源却无账本时失败关闭，不能在持领域锁的重复路径临时锁源补账本。切换要求全部供应链实例先停止旧调度/追加写入与引擎并完成在途事务，再做维护窗口迁移/一致性核验、部署同一新版本后开引擎；不支持旧新writer混跑，不擅自执行本次部署。启动核验映射完整及协议一致，损坏失败关闭。账本保留规则与对应源/幂等承诺同寿命，本片不新增清理器。

#### Q-R3-01-01：获批 JSON 保守资源准入（R3-01 质量阻断修复）

用户已明确批准本政策并授权仅本地实施／验证；先修订本文§6.1／§7.2与R3-01计划，再改Java。每一文档独立检查，首次输入、重复输入和当前BOUND旧载荷均适用，`LEGACY_JSON_V1`协议名和精确比较语义不变；不增加事务累计／并发预算，不读取生产尺寸。

| 资源 | 包含边界的上限（允许≤） |
| --- | --- |
| 原输入严格UTF-8字节 | 1048576 |
| 未闭合对象／数组容器深度 | 64 |
| 全部JSON token（字段名、容器起止、标量） | 65536 |
| 单数字原词法字符（符号、小数点、指数符号和数字均计） | 1000 |
| 单数字规范普通十进制ASCII字节 | 4096 |
| 完整规范UTF-8输出（标点、字段名、转义、值均计） | 4194304 |

解析前无编码副本扫描UTF-16：验证代理项，以long精确累计UTF-8字节，超限即拒绝。独立JsonFactory安装Jackson2.21.2 StreamReadConstraints（depth/token/number等）；String的maxDocumentLength不能替代UTF-8预算。额外逐token验证原数字词法长度（Jackson内建数长不一定包括所有词法字符），不使用共享Mapper，不改变其配置；构树禁用STRIP_TRAILING_BIGDECIMAL_ZEROES，避免未经资源准入的scale规范化。极端scale／指数在受词法限制的精确BigDecimal中表示，禁止构树时展开。

完整树先计量再分配任何普通十进制或总输出：节点同政策复核深度、token、Unicode及数字；统计含标点／控制字符六字节转义／码点UTF-8的总长度。非零数字先取受词法界限制的unscaled十进制位串算尾零，不先stripTrailingZeros；有效precision p、scale s、符号长度n使用long：s≤0为n+p−s，0<s<p为n+p+1，s≥p为n+s+2；零恒1。拒绝任何数字>4096，再累计总输出≤4194304；long运算避免INT_MIN取负／abs或去尾零scale下溢。获准的小范围数字才生成普通十进制；不得舍入、截断、指数输出或catch OOME兜底。编码时以同一规则二次有界校验，树计量未完成不得预分配完整输出。

资源拒绝必须为SystemIntegrityException并保留稳定、不含payload的预算原因cause；不是普通载荷不匹配，旧BOUND超限不得返回existing。首次超限在两个reserve的DAL零交互前拒绝；旧载荷只能在当前读后验证，但须在比较／返回existing前拒绝（不因target/version不同而短路预算核验）。旧LEGACY／旧Timer维护预检遇超限必须阻断切换、报告人工处置，不重写历史JSON、不绕过政策；预检和迁移实施仍属R3-04，本片不新增迁移。

TDD以可安全运行的数字4097字节等及各资源边界fixture证明旧实现RED，不在旧实现运行巨大展开；1e1000000000等只在preallocation guard生效后安全验证，记录时间和入口DAL交互而非制造OOME。最终纯／契约完整非Integration回归绑定JDK21、实际Maven/runner退出、原XML逐方法及before/after源manifest；完成后冻结交父独立双审，不自行验收。R3-02实际源映射、BCS具体封闭Schema、完整切片3仍open。

#### R3-02O实施前语义核对：阻断交回，未编码（2026-10-05）

本轮用户已授权唯一实施者仅推进R3-02O；R3-01父有限回执只接受LEGACY精确比较／nonce与Q-R3-01-01资源政策，不接受完整BCS、F11或切片3。原仓HEAD复核仍为`de471b0af16ad3469e9b56acd8ebc7d27b42a69f`，暂存区为空。未跟踪重复测试已按用户授权备份移出；备份路径与核验见集成记录。

实施前读取本文完整§7.2／§7.2.1／§9及真实Repository、ledger服务与Mapper后，确认存在**需先确认的接线语义**：本文规定`appendLocalIdempotent`仅服务B保留消费流，§6.1同时规定新B使用`BCS_JSON_V1`且具体Schema尚未冻结；当前真实reserve入口只接受`LEGACY_JSON_V1`。通用技术fixture可以使用已有合法LEGACY JSON，但这不自动授权它通过“仅B保留槽”的新Repository入口，亦不能把LEGACY标成BCS。现有源码没有`appendLocalIdempotent`定义或调用者；`BUYER_SELECTION_CATALOG_REFRESH`在§7.1仍是建议聚合名，不能由实施者自行冻结新技术命名空间或扩大新入口服务范围。

**交父需确认的最小设计决定：** 是否批准把真实源追加／可信绑定底座与仅B公开入口分离，并明确技术fixture可进入的非业务LEGACY命名空间及调用边界；或保持新入口仅B并先冻结具体BCS Schema。确认前不新增旁路、测试专用生产开关、虚构BCS投影或商业payload。首次绑定需核验真实源的完整身份、target、payloadVersion和完整JSON，不能仅凭任意eventId完成CAS；重复路径仍禁止反读源，可信切换核验不能由首次绑定单测替代。上述义务保留，没有因本次阻断而降级。

本轮只保存源码／规范核对证据与限定状态，未改运行字节、未建验证副本、未运行MySQL或TDD，不把API缺失／编译失败冒称行为RED。R3-02O为`BLOCKED_DESIGN_CONFIRMATION`，交父双审／确认，不自行验收、不进入Timer／排序／预算／迁移片。

#### R3-02O-S：用户批准分离后的独立预审候选（仅设计，尚未passed；rev3按BLK-1~4裁定修订）

**授权与状态：** 用户已明确选择“分离，先修订设计并独立预审，再实施”。rev1候选已交独立预审`deleg_ffc1b748`并返回passed=false，阻断B1/B2；rev2按D1–D4修订后再交独立预审`deleg_045e4f3c`，仍passed=false，阻断BLK-1~4；用户随后逐项裁定并授权仅按裁定修订设计，本节即rev3候选。这不构成预审passed、代码／测试实施或有限验收。当前R3-02O状态为`DESIGN_SEPARATION_PENDING_INDEPENDENT_REVIEW`；历史`BLOCKED_DESIGN_CONFIRMATION`及原handoff保留。rev3按裁定修订：①BLK-1零DB放行收窄为可证明安全集合（null、码点<31、含ASCII非[A-Za-z_]码点），其余一律参数化候选SELECT，O-S08b补充组合附标插入（U+0301/U+034F等）、é→E预组合替换与零权重候选边界，真实MySQL验证后逐项固定（见冻结小节）；②BLK-2父已确认两重复文件备份移出（见`r3-02o/blk2-parent-confirmation.json`）；③BLK-3 LedgerJsonEncoding调用者表述改为『除账本服务Timer路径既有调用者外，底座是唯一新增跨包生产调用者』，ArchUnit不为其新增限制；④BLK-4 D4测试范围枚举改为test02–test08（或全部Outbox段）。rev2变更摘要保留为历史：①旧appendLocal隔离机制改为“Java粗过滤快路径＋候选时调用方同事务连接参数化只读SELECT”，固定为程序化Advisor（见下文冻结小节），取代原“候选独立Repository Spring advisor”的宽泛表述；②LedgerJsonEncoding转public（D2）；③公开Outbox reserve/bind由“失败关闭签名”改为直接删除死代码（D3）；④两测试Outbox段反射桥重写、原字节归档（D4）。授权边界不变：只把内部可信源追加底座与仅B业务入口分开，并允许仅测试装配的LEGACY技术fixture证明同一生产算法；不是新增生产LEGACY幂等API、冻结商业Schema或接入商业生产者。R1–R9与O-S01~O-S11矩阵条款全部保留不得降低，O-S08按D1补充三项矩阵；完整BCS schema未冻结不造接口字段，B入口继续失败关闭；不新增生产LEGACY入口、不改旧appendLocal方法体/签名/严格INSERT与异常语义。§6.1编码、§7.2重复不触源、§7.2.1所有权和事务义务、§9故障门禁不放宽。

**2026-10-06 接续实施限定记录（非验收）：** rev3已由父`rev3-preapproval-receipt.json`确认预审通过并授权本片实施；前实施中断，当前仅接续R3-02O，不恢复后片。真实MySQL独占副本复现旧计数expected1/actual2；脱敏语句历史证明仅1条保留名probe，另1条为Connector/J 9.7.0在源INSERT前的`SELECT @@SESSION.transaction_read_only`。O-S08b改用独立观察连接按受测THREAD_ID/事件窗口精确计量，唯一驱动SELECT白名单按上述完整SQL digest匹配，其它SELECT仍拒绝；双probe与无关SELECT负控制不降低零probe/恰1probe/源INSERT0或1。补充O-S08a同名非目标bean反例已真实失败，按本节既定单bean机制选`BeanNameAutoProxyCreator`显式限定`outboxEventRepository`及命名Advisor，并限定pointcut目标类型；不得把同名方法当唯一bean边界。新增ArchUnit负控制已证明原裸new规则只匹配无参构造，改为所有构造签名；Mapper边界从整包白名单收紧为Repository固定装配与私有底座，三写方法仅底座可调用。以上为获审边界落实及夹具纠偏，不变更业务协议、旧appendLocal或Timer；完整O-S01~O-S11/R1–R9与最终门禁仍待核，不声称双审或有限验收。

**2026-10-06 接续冻结状态（覆盖上文历史待预审状态，不关闭本片）：** 最新定向原XML为6类39方法，API契约原XML为45方法，两轮Maven／runner均0、failure/error/skip均0、测试前后源码manifest一致；完整profile仍在独占copy-12运行，未取得退出码，不计通过。copy05/07的error发生于上下文已启动后非目标bean被旧生产Advisor误拦截，copy06为BeforeAll阶段`@Import`注册全限定bean名与固定短bean名单不符的夹具装配失败，均保留原XML，启动失败不计行为RED；修正组件扫描夹具及明确行为断言后copy09/10同测试字节取得1failure/0error→0failure/0error。保护核验发现前实施候选除D2类public外还将`legacyMatches`／`canonicalBytes`改public，与本轮接续“仅public类修饰”字面限制存在需父裁定的差异；算法／六常量／私有构造未改，接续者未编辑该文件，不自行扩大或回退。条款矩阵明确保留真实REQUIRED代理、逐属性映射故障、完整资源清理、两foundRows交叉门禁及隔离全量核验等缺证；即使全部profile绿也不关闭O-S01~O-S11／R1–R9。证据根`r3-02o/implementation-resume2`，详见`clause-matrix.json`、`d2-visibility-blocker.json`与`handoff.json`；当前为部分实施冻结／待裁定，不自验收，不恢复后片。

**2026-10-06 O-S01／O-S07代理检查点冻结（部分实施，非验收）：** D2三public授权已同步，编码器其它字节未变。本轮新增仅src/test的`TrustedOutboxProxyTransactionIntegrationTest`，真实生产配置／REQUIRED代理／Repository实际私有底座／固定`R3_02O_TECH_FIXTURE`／领域InnoDB fixture同事务，源INSERT1／源当前读1／CAS1及同连接、提交返回eventId已取证。当前代理localRollbackOnly、活动其它manager借用既有供应链资源、未验证markBound分别同测试字节行为RED→最小生产修复GREEN；Outbox Guard迁至实际同步集合与holder归属，beforeCommit残留／未验证标记拒绝、afterCompletion清空。挂起事务提交阶段曾误拿恢复后的外层代理状态判归属，修设计后以同测试字节RED→GREEN改为提交Guard只核实际资源。真实源1644/45000与绑定3819/HY000穿代理、ROLLED_BACK、资源清空及独立连接领域／源／槽零孤儿；无事务／只读／holder回滚／缺同步／重入／REQUIRED嵌套／同线程复用为回归覆盖。

最终有限门禁9类67执行、failure/error/skip均0，Maven／runner均0，4056文件前后manifest一致，原XML逐方法核验无问题；生产jar不含测试桥／fixture常量。它不是完整profile或O-S07闭合：进一步仅scratch诊断真实反例证明，**祖先代理先设localRollbackOnly、holder仍false，再经另一REQUIRED代理调用底座时，内层currentTransactionStatus不可见祖先local标记，当前实现未在DAL前拒绝**。`ancestor-local-diagnostic-red`原XML11方法／1failure／0error，明确失败`ancestorLocalRollbackOnlyMustBeRejectedAfterJoiningAnotherRequiredProxy: Expecting code to raise a throwable`，Maven／runner均1；诊断只在新独占archive替换该测试，运行字节与原仓差异有单独manifest／overlay绑定，不污染最终候选、不降断言、不改成成功测试。该O-S07剩余根因需要先细化获审的祖先状态／实际事务所有权方案，未擅改manager、预算或新增AOP接线／反射入口；本检查点交父保留阻断，不自称O-S07、R3-02O或切片3完成。

证据根`/Users/mac/.hermes/cache/scratch/task-b0-ledger-evidence/slice3-reacceptance/r3-02o/implementation-proxy-checkpoint/`。父copy-12完整profile已结束Maven／runner1，277 XML／2370执行／5failure／21error／0skip、字节一致，作为最终验收阻断保留；父后续9测试／fixture限定修复授权排在本检查点之后，本轮未动9路径、A39、Timer、预算、历史迁移或061，不重复完整profile、不触碰copy-12资源。其它O-S03/04剩余/05/06/08/11及R条款缺证继续留完整矩阵，等待顺序实施、独立双审及父最终核验。

**实际API缺口：** 当前`OutboxEventRepository.appendLocal`生成EVT_、构造DO并直接`mapper.insert`，没有幂等入口。`AppendScheduleIdentityLedgerService`公开Outbox reservation record／reserve／bind，record可公开构造；`DefaultAppendScheduleIdentityLedgerService.bindOutboxEvent`仅调用`bindEventCas`并markBound，不核真实源。因此不能把公开nonce／slotId／eventId组合当可信能力，也不能只新增注释或ArchUnit规则便称已封闭。当前Mapper继承BaseMapperX，DO可变，SQL CAS只要求id、nonce、RESERVED；这些DAL只在基础设施内部使用，不能直接暴露给业务或HTTP/RPC。

**2026-10-06 D2可见性范围明确授权（覆盖接续委派的过窄措辞）：** 用户已明确选择“授权两静态方法 public，补齐设计说明后继续本片”。`LedgerJsonEncoding`的可见性增量限定为`public final class`及`public static legacyMatches`、`public static canonicalBytes`三处；两方法供获审的跨包私有底座复用既有静态工具，不新增生产LEGACY业务入口、可注入策略或反射旁路。与R3-01预算冻结基线逐字节比较，除这三个`public`修饰符外，算法方法体、六个预算常量、private构造及其它字节必须保持不变；新增其它public成员不在授权内。原“仅public类修饰”是父委派中的过窄表述，不再用其阻断已批准的跨包复用。上文D2待裁定记录作为历史保留，本次仅消除可见性授权歧义；完整O-S01～O-S11／R1～R9缺证、完整profile结果及独立双审／父验收仍未关闭，不放行后片。

**候选可见性与装配（拟新增／调整，不冒称已有）：**

1. 唯一业务入口仍为`OutboxEventRepository.appendLocalIdempotent`；对调用者只返回eventId，不暴露槽id、nonce、源DO、绑定结果或可执行callback。内部完整算法放在Repository的`private static final`嵌套底座（候选名`TrustedOutboxAppendCore`）；底座构造器、追加方法、深不可变已验证命令及映射证明类型均private，不注册独立Spring bean，不设public工厂／SPI／可替换策略。Repository生产构造路径只使用固定B入口校验／codec与实际Mapper、BusinessNoGenerator；不把命名空间、codec、允许LEGACY、trusted flag、mapping verifier或source writer作为外部可注入策略。底座在`src/main`真实执行账本upsert／当前读、源INSERT、源核验、CAS与事务guard；不是只放在`src/test`的另一套算法。底座复用已验证`LedgerJsonEncoding`（D2将其转public供跨包嵌套底座调用；保留final与private构造不可实例化，算法与六个预算常量不变，仅暴露静态工具语义；该类不在A39保护清单内，ArchUnit不为其新增限制；除账本服务Timer路径既有调用者外，底座是唯一新增跨包生产调用者）。
2. 公开Outbox reserve/bind是生产零调用者死代码，按D3**直接删除**而非保留失败关闭签名：`AppendScheduleIdentityLedgerService`接口及`DefaultAppendScheduleIdentityLedgerService`中的`OutboxReservationResult` record、`reserveOutboxSlot`、`bindOutboxEvent`全部移除；`TimerReservationResult`/`reserveTimerSlot`/`bindTimerTask`、nonce生成、guardReservation/markBound及STATUS常量**原样不动**（Timer方法体、签名、语义零改动）。保留失败关闭签名本身仍是第二公开写入口面，与本裁定不符。Outbox算法只存在于上述私有底座，复用已验证LedgerJsonEncoding与现有账本XML原语，不同时保留第二套可成功的公开绑定路径。删除影响既有测试：`AppendScheduleIdentityLedgerIntegrationTest`的Outbox段（test02–test08（或全部Outbox段）中reserveOutboxSlot/bindOutboxEvent用例及Outbox账本行断言）与`LedgerComparisonEntryContractTest`的Outbox断言按D4以同模块`MethodHandles.privateLookupIn`反射桥驱动同一main底座重写（原字节+SHA归档于证据目录后重写，Timer段公开API不动、逐字节保持）；`LedgerReservationImmutabilityTest`的`outboxNonceCopiesInputAndEveryOutput`因API主体删除而随D3移除（其Timer测试不动，原字节同样归档）。若必须修改A39保护路径或Timer共享路径才能落实，立即停报最小diff。
3. 基础设施DAL仍是公开MyBatis类型，Java不是针对已获JVM反射／任意代码执行权限的安全沙箱；保证范围是普通业务、Facade、HTTP/RPC调用者通过受支持API不能提供任意映射或获得底座。新增独立ArchitectureTest（不改受保护既有架构测试）强制：`OutboxAppendIdentityMapper`写方法（`insertSlotOnDuplicateKeyUpdate`/`selectForUpdate`/`bindEventCas`）仅允许嵌套底座及`src/test`访问，其它生产类禁止依赖该Mapper（D3删除后`DefaultAppendScheduleIdentityLedgerService`已不再依赖它，违反即构建失败）；`TimerScheduleIdentityMapper`写方法仅Timer服务路径（及测试）允许；`OutboxAppendIdentityDO`等数据对象仍由MyBatis公共反射映射，但其生产构造与写字段入口仅底座持有。扫描实际生产字节码调用点及bean装配，发现第三个生产写入口即blocked；不能用架构规则替代private的语言边界。
4. LEGACY fixture只在`src/test`的显式测试配置装配：以测试专用反射／`MethodHandles.privateLookupIn`访问上述私有底座和私有已验证命令构造边界，先调用实际生产LEGACY资源／精确编码，再驱动同一底座。测试访问桥不进入main资源、运行配置、Spring自动扫描或打包jar；生产代码禁止反射开启该入口。允许的仅测试聚合名固定为`R3_02O_TECH_FIXTURE`，合法长度由源DDL门禁验证，只有测试代码持有此常量；不得通过YAML、profile、环境变量、启动参数或生产Bean替换开放该名字。fixture可以提供技术身份／JSON但不得提供eventId／slotId／nonce／绑定证明；真实源身份由底座生成。测试桥只能访问边界，不实现upsert／INSERT／bind算法；故障注入在隔离SQL参数／真实Mapper边界进行，不替换生产事务或源读回。反射只解释测试如何进入不可公开的生产算法，不声称抵御具有反射权限的恶意JVM代码。

**B入口保留槽／名称／codec gating：** 本候选冻结唯一B聚合保留名`BUYER_SELECTION_CATALOG_REFRESH`（由§7.1“建议名”提升为本候选明确名称，仍须独立预审）；aggregate_id为§7.1完整64位小写hex，aggregate_version=1；target_type必须LOCAL，主设计§6已声明event_type=`BUYER_SELECTION_CATALOG_REFRESH_REQUESTED`、targetName=`buyer-selection-catalog-refresh`及payloadVersion=1，此对应项保留，不推测新枚举；§7.1的子节点身份分类不自动新增可公开event-target映射。主设计已有字段草案不是完整闭合Schema注册，仍按下一段拒绝。聚合／event／target的非空、字段长度和源collation等价保留槽识别必须在DAL前完成；数据库认为等价的大小写／重音变体不能绕过保留槽。不直接以Java equals忽略源排序规则等价性；若不能在不改旧方法的条件下实现隔离门禁，则停报。

旧`appendLocal`方法体／签名／严格INSERT与异常语义不改。新入口前置隔离门禁按D1固定为程序化Advisor（见下一小节冻结文本）：仅匹配bean `outboxEventRepository`的`appendLocal`方法，在源Mapper前按真实源collation识别B保留聚合并拒绝；非B调用完全透明。同bean自调用／裸new可绕过Spring advisor，故生产不得自调用该方法、不得裸new Repository，独立架构及真实Spring代理门禁必须证明实际调用者全经advisor；旧方法的源unique仍是第二道约束，不能把它当隔离门禁。若独立预审认为代理边界不能满足用户“旧入口隔离且不改旧appendLocal”的要求，停止，不以注释承诺或悄改方法体补齐。

##### 旧appendLocal B保留槽隔离门禁（D1冻结：粗过滤＋同事务参数化SELECT）

1. **机制固定为程序化Advisor，main实际装配并显式注册auto-proxy creator。** 仅使用spring-aop既有核心类：`org.springframework.aop.support.NameMatchMethodPointcut`（mappedName=`appendLocal`）＋方法拦截Advice构成Advisor，目标bean名`outboxEventRepository`；不引入aspectjweaver、不使用@AspectJ注解、不新增Maven依赖。Advisor与auto-proxy creator在`src/main`实际装配：main配置显式注册`DefaultAdvisorAutoProxyCreator`（或`BeanNameAutoProxyCreator`）并把上述Advisor纳入其代理范围——两者均为spring-aop核心类，无新依赖；不靠运行开关/YAML/profile，不依赖组件扫描默认代理机制；生产装配断言`AopUtils.isAopProxy(outboxEventRepository)==true`，装配缺失或目标非代理即启动失败。已核生产14处调用点全部为其它bean对注入`outboxRepository`的跨bean调用（见第4点表），无自调用、无裸new；新增ArchitectureTest强制生产不得在`OutboxEventRepository`内自调用appendLocal、不得`new OutboxEventRepository`，违反即构建失败。
2. **粗过滤快路径（冻结边界，纯Java，零DB，仅可证明安全集合——BLK-1收窄）。** 方法进入时对aggregateType检查，以下任一成立即“可证明不可能与B保留名collation等价”，直接放行原方法（零DB、零额外语句）：(a) `aggregateType == null`（null永不等价于任何非空保留名）；(b) 码点计数<31（B保留名`BUYER_SELECTION_CATALOG_REFRESH`恰31个非忽略码点，每码点均有非零主权重；码点更少的字符串权重序列必更短，不可能等价。注意仅放行“<31”：≥31一律不零DB放行，因为零权重可忽略码点（如U+034F组合字素连接符）插入可令码点数≥31而collation权重不变）；(c) 存在任一ASCII码点且不属于[A-Za-z_]（每个ASCII非字母非下划线码点都有固定非零主权重，且不等于任何字母/下划线的主权重，故含此类码点的字符串权重序列必与保留名不同。非ASCII的非字母码点——含零权重组合附标——不在此零DB放行集合内）。快路径只做可靠否定、绝不做肯定判断；未命中快路径的调用**一律进入参数化候选SELECT**（第3点），不允许以Java equals/大小写归一化代替数据库等价判断，也不得扩大本零DB放行集合。
3. **候选路径（冻结参数化SQL，调用方同事务连接，零锁副作用）。** 快路径未排除的调用（码点≥31、无ASCII非[A-Za-z_]码点——含大小写变体、非ASCII字母（如é→E预组合替换）、组合附标插入（U+0301/U+034F等）及零权重变体）执行以下参数化只读SELECT——冻结SQL文本：

```sql
SELECT CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci
     = CAST('BUYER_SELECTION_CATALOG_REFRESH' AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci
     AS reserved_match
```

   首个占位符绑定aggregateType（参数化绑定，禁止拼接外部输入；保留名以字符串常量嵌入，SQL文本本身冻结不变）。返回1 → 按B保留槽拒绝（SystemIntegrityException，源Mapper零交互、零INSERT）；返回0 → 完全透明放行原appendLocal。该SQL为**无表表达式SELECT**：不访问任何InnoDB表，因此不取得任何行/间隙锁（零锁副作用）、不建立RR读视图（见第4点）；比较collation显式`utf8mb4_0900_ai_ci`，与源列`sc_outbox_event.aggregate_type`实际排序规则一致，等价判断与源列语义同构（大小写/重音变体按数据库真实权重裁决）。执行经由调用方事务绑定的**同一连接**（`DataSourceUtils.getConnection(supplyChainDataSource)`；已核14处调用点全部在`@Transactional`内，该连接即事务连接，不新借池连接、不切换事务管理器、不影响§5容量R预算）。**预算上下文透明声明：** 该SELECT不进入R01/R02预算资格判定，不在其前后安装/恢复任何连接级限制，不修改BudgetContext，不产生新借连接；若调用方连接已处于预算包装下，该语句与事务内其它语句同受既有连接限制，语义等价于原appendLocal的INSERT前检查。若调用方无绑定事务连接（已核生产不存在此情形），候选检查经`DataSourceUtils.getConnection`取得连接执行同一无表SELECT后按规则释放——事务外无表SELECT同样无快照/锁副作用，仍保持对非B调用的完全透明，不fail-closed拒绝非B调用。
4. **快照时序证明（两重，写入O-S08b/O-S08c取证）。** 第一重结构性：无表表达式SELECT不读InnoDB数据，MySQL不会为其建立事务读视图（读视图只在首次读取InnoDB表的一致性读时建立），因此该SELECT无论位于事务何处都不可能成为“第一条普通一致性读”，也不改变后续任何读的可见性；事务审查§3关于“快照在锁前过早建立”的风险不适用于它。第二重逐点：即便退化为会建读视图的表读，14处调用点全部位于其事务已有更早一致性读之后（下表），故在已核生产调用链上该SELECT也永不成为第一条一致性读。两重证明使旧appendLocal的RR时序语义完全不变。表中行号为当前工作区源码行号，实施前以冻结源码重新核对：

| # | bean／调用点 | 外围@Transactional方法 | 该事务更早的一致性读（首读，多数为FOR UPDATE当前读） | 结论 |
| --- | --- | --- | --- | --- |
| 1 | O2OChannelLifecycle:321 | confirmBuyerPayment | detailMapper.selectByOutTradeNo(outTradeNo) | 读视图已建立，probe非首读 |
| 2 | O2OChannelLifecycle:409 | applyBuyerRefundResult | refundCaseMapper.selectByRefundCaseNo(middleRefundNo) | 同上 |
| 3 | O2OChannelLifecycle:453（appendOrderSummaryEvent，调用于createPaymentPreparation:202） | createPaymentPreparation | bindingMapper.selectActiveByChannelUser | 同上 |
| 4 | FulfillmentAcceptanceLifecycle:686/691/697（appendAcceptanceEvents，调用于acceptPrepared:466；经accept/acceptDirectProjectOrder代理） | accept／acceptDirectProjectOrder | receiptMapper.selectByIdForUpdate(receiptId) | FOR UPDATE当前读，读视图已建立 |
| 5 | InstoreChannelLifecycle:992（appendRefundCaseEvent，调用于applyBuyerRefundResult:926） | applyBuyerRefundResult | refundCaseMapper.selectByRefundCaseNo(refundCaseNo) | 读视图已建立 |
| 6 | InstoreChannelLifecycle:1090（appendOrderSummaryEvent，调用于createPaymentOrder:251与confirmBuyerPayment:483） | createPaymentOrder／confirmBuyerPayment | paymentFlowMapper.selectByChannelTrade（136行）／selectByOutTradeNo（431行） | 读视图已建立 |
| 7 | BuyerListingPublicationService:108 | publish | listingMapper.selectByListingNoForUpdate（requireListingForUpdate） | FOR UPDATE当前读 |
| 8 | BuyerListingPublicationService:156 | changeStatus | listingMapper.selectByListingNoForUpdate（requireListingForUpdate） | FOR UPDATE当前读 |
| 9 | ConnectorPublicationService:111 | publish | versionMapper.selectById(command.connectorVersionId())（56行，普通一致性读，先于各FOR UPDATE） | 读视图已建立，probe非首读 |
| 10 | ConnectorPublicationService:157 | retire | connectorMapper.selectByNoForUpdate | FOR UPDATE当前读 |
| 11 | SupplierOfferPublicationService:217 | publish | offerMapper.selectByNoForUpdate | FOR UPDATE当前读 |
| 12 | RoutingPolicyPublicationService:285 | publish | versionMapper.selectByIdForUpdate(command.policyVersionId())（250行） | FOR UPDATE当前读 |

   合计14个appendLocal调用点/7个bean（同方法多处调用合并为一行；#4合并3处、#6合并2处）。新生产调用点须遵守同一规则：其事务内须先有更早一致性读，或由无表SELECT结构性保证；新增ArchitectureTest断言生产不裸new/不自调用，防止绕过advisor。

新B仅接受`BCS_JSON_V1`且payloadVersion、具体闭合Schema、完整身份字段、event／target映射、投影元字段全部已冻结注册才可构造私有已验证命令。当前这些业务Schema尚未冻结，因此整个新业务入口在账本／源DAL前稳定失败关闭，连同已有BOUND也不能绕过；不暂用LEGACY、不声明完整BCS支持、不虚构payload。基础规范的时间／版本编码等已有规则不重新标未决。底座对已验证命令执行共同追加机制，不负责替未知业务猜Schema；将来启用BCS需要另行Schema预审及接线授权。测试LEGACY证明底座，不证明B正向业务已交付。

##### 2026-10-06 O-S01／O-S07有界代理检查点实现细化（非验收）

本检查点不改变业务协议，只补齐既定事务资源与可信guard义务。测试新建仅src/test的Spring REQUIRED调用方，显式`supplyChainTransactionManager`，通过同模块privateLookupIn读取Repository实际持有的私有底座，不另造算法／main入口。技术聚合固定`R3_02O_TECH_FIXTURE`；真实隔离InnoDB领域fixture先写，实际槽／源／CAS同事务。MyBatis Executor拦截器记录真实执行后的结果及绑定ConnectionHolder连接身份；验证upsert、账本当前读、源INSERT1、源当前读1、CAS1和领域连接相同，独立连接提交读回eventId与代理返回一致。生产B继续DAL前拒绝。

资源守卫同时检查活动事务、同步、read-only、供应链ConnectionHolder及其rollbackOnly。处于真实Spring事务代理时，还核`TransactionAspectSupport.currentTransactionStatus()`的local/global rollbackOnly与实际Jdbc事务对象的ConnectionHolder身份；只检查holder会漏掉外层代理本地rollbackOnly。无代理的既有TransactionTemplate回归仍用真实holder检查，不新开任何事务；其它活动manager虽有供应链同步资源也不得冒充该写事务所有者。无同步立即拒绝，不悄悄跳过guard注册。

Outbox的pending状态从裸ThreadLocal表迁为活动TransactionSynchronization中的私有单事务Guard：绑定实际供应链ConnectionHolder，key与保留状态只存在于本次同步集合；未完成重入拒绝，正常REQUIRED嵌套复用该Guard。只有实际源核验与CAS1完成后，内部私有BindingProof才能将槽标记为可信BOUND；未验证markBound立即拒绝且保留未完成状态，beforeCommit拒绝任何残留RESERVED／缺证明。提交前核同一holder归属（此时Spring可能已恢复外层TransactionAspectSupport状态，因此beforeCommit只核本Guard持有的实际资源／同步／写属性与holder回滚态，不拿已退出的内层代理状态冒充资源归属），afterCompletion清空Guard内容；已结束／挂起事务的Guard不得进入下一次事务。同线程新事务能重新首写；测试可用挂起的新事务作资源归属负控制，但生产底座不创建REQUIRES_NEW／retry。

故障测试用真实MySQL绑定CHECK／源SQL失败穿出代理，观察afterCompletion=ROLLED_BACK，代理退出后无活动事务／同步／任何绑定资源，独立连接领域／源／槽零孤儿；提交前残留Guard与未验证markBound同样全回滚。已有无事务／错manager／只读／holder回滚门禁立即GREEN按回归记录，localRollbackOnly和未验证标记／事务上下文污染缺陷分别纵向同测试字节RED→最小生产修复GREEN。不实施集合flush、预算、重跑/coordinator、Timer与其它O-S条款。

##### 2026-10-06 O-S07 祖先 localRollbackOnly 观测机制与 manager 装饰器设计先行（用户已批准方案 A）

1. **根因与问题陈述（实证）**：
   在 Spring 6.2.18 / JdbcTransactionManager 中，当外层事务代理或程序化调用者将其 `TransactionStatus` 设置为 `setRollbackOnly()` 时，仅其自身的局部标记变更为 `localRollbackOnly=true`；若尚未触发该状态的 commit/rollback，底层 JDBC `ConnectionHolder` 的 `isRollbackOnly()` 仍保持 false。当控制流进入另一个内层 `PROPAGATION_REQUIRED` 代理时，Spring 会新建一个 `DefaultTransactionStatus`（`newTransaction=false`，`localRollbackOnly=false`）。此时私有底座即便读取 `TransactionAspectSupport.currentTransactionStatus()` 与 `ConnectionHolder.isRollbackOnly()`，也无法获知尚在途的外层祖先 localRollbackOnly 状态，导致调用放行至 DAL 操作之后才在最外层提交时回滚。这破坏了“同一实际事务的任何在途祖先处于回滚标记时，必须在账本/源 DAL 前拒绝且零 SQL 副作用”的契约。

2. **方案 A 核心架构：透明 PlatformTransactionManager 状态观测装饰器 + 资源级在途 status 登记簿**：
   - **Bean 暴露与委托关系**：
     保留现有 `supplyChainTransactionManager` Bean 名称与 `@Qualifier`，将其类型定义调整为实现 `PlatformTransactionManager`（以及委托实际的 `ResourceTransactionManager`），内部持有唯一的实际 `JdbcTransactionManager` 委托。不注册第二个独立的事务管理器 Bean，避免组件扫描或上下文自动装配误用。
   - **原始状态捕获与同步 Scope 绑定**：
     在每次公开 `PlatformTransactionManager.getTransaction(definition)` 返回时，获取返回的原始 `TransactionStatus`（保持 Spring 核心的 `DefaultTransactionStatus` 类型与对象身份，绝不包装 status，无私有字段反射）；
     当该 status 具有活动事务且关联到 `supplyChainDataSource` 对应的 `ConnectionHolder` 时，检查当前线程在 `TransactionSynchronizationManager` 中是否已注册属于该装饰器与该 holder 的专属 `Scope`（实现 `TransactionSynchronization`）；若无则立即注册；
     将捕获的原始 status 引用登记至该 `Scope` 的在途集合（`IdentityHashMap`）及句柄映射中。
   - **私有底座前置守卫（DAL 前强制拒绝）**：
     私有底座（`TrustedOutboxAppendCore`）在执行任何账本/源数据库交互（包括无表参数化 SELECT、账本 upsert、当前读）之前，从活动同步集合中获取当前 `Scope`，核验当前连接持有的所有未完成（`!status.isCompleted()`）status 引用；
     若发现其中任一 status 满足 `status.isRollbackOnly() == true`（无论是外层祖先 local、当前 local 还是全局 global），底座立即抛出 `SystemIntegrityException` 阻断执行，确保账本与源 Mapper 零交互、零额外 SQL；
     同时底座断言当前写事务必须显式归属于受观测的 `supplyChainTransactionManager`，若处于未观测事务边界或未知 manager，一律 fail-closed 抛出 `SystemIntegrityException`。
   - **精确生命周期释放与挂起隔离**：
     在装饰器的 `commit(status)` 与 `rollback(status)` 的 finally 块中，精确从映射及 Scope 中注销该 status 句柄；
     在 `TransactionSynchronization.afterCompletion(int)` 回调中，标记 Scope 为 completed 并清空所有引用；
     对于 `PROPAGATION_NESTED`：保存点局部回滚仅回滚 savepoint，其嵌套 status 释放后不将外层 status 提升为全局回滚，同线程后续新事务可安全复用；
     对于 `PROPAGATION_REQUIRES_NEW` 与 `NOT_SUPPORTED`：Spring 在挂起外层事务时会自动解绑外层同步与 holder，内层新事务绑定新 holder 与新 Scope，被挂起外层的 local 回滚标记绝不污染内层新实际事务；内层完成后 Spring 恢复外层同步与 holder，装饰器继续无缝恢复对外层状态的守卫；
   - **预算与连接透明性**：
     本机制纯粹基于 Spring 标准同步生命周期与内存状态登记，不建立任何新数据库连接，不访问外部表，不修改 `BudgetContext`，不改变既有 15s/3500ms 预算与连接超时策略。
   - **非授权范围明确禁止**：
     严禁使用 Spring 私有栈反射，严禁自定义包装 `TransactionStatus`，严禁在底座内部新开独立事务或引入局部 retry。

3. **TDD 实施与实测证据（同测试字节 RED→GREEN）**：
   - **RED 阶段（第 16 轮）**：在 `TrustedOutboxProxyTransactionIntegrationTest` 中加入反例测试 `ancestorLocalRollbackOnlyMustBeRejectedAfterJoiningAnotherRequiredProxy`，生产未实现时真实复现：11 测试中 1 failure / 0 error，`AssertionError: Expecting code to raise a throwable`，Maven/runner 退出码 1；确证内层当前 status 无法感知祖先 local 标记导致调用穿透至 DAL 前未被阻断。
   - **GREEN 阶段（第 17 轮）**：编写最小生产代码（`SupplyChainTransactionObservationManager` 装饰器注册、Scope 在途登记与底座 DAL 前 `checkObservationScope` 守卫），同测试字节重跑：11 方法全部通过（0 failure / 0 error / 0 skipped，Maven/runner 退出码 0）。
   - **全边界覆盖（第 18 轮）**：追加测试 12（程序化 TransactionTemplate 祖先回滚 DAL 前阻断）、测试 13（REQUIRES_NEW 挂起外层回滚隔离与恢复）、测试 14（PROPAGATION_NESTED 保存点局部回滚不提升全局且线程复用），14 方法全部通过（0 failure / 0 error / 0 skipped，Maven/runner 退出码 0）。
   - **定向回归**：架构测试 7 用例全绿、隔离 Advisor 9 用例全绿、全仓纯单测及 API 契约全绿通过。

**真实首写与可信BOUND：** 底座检查实际供应链manager资源绑定、活动写事务、同步已启用且非read-only／rollbackOnly；无事务、错manager、重入未完成槽一律失败，加入调用方REQUIRED而不另开事务或retry。解析／预算检查先于DAL。单槽upsert不靠行数，当前读核全键、态与nonce；首次才分配eventId和新源DO，源严格INSERT必须1行。使用同一事务连接按刚插入的源内部id当前读真实源（不是相信可变DO／generated key就是完整证明），核源eventId、四列完整聚合身份、LOCAL／targetName、payloadVersion、完整LEGACY JSON精确比较或已冻结BCS投影、初始PENDING／attempt0／token0、计划及创建值、合法非空字段；逐字段不匹配、缺行、错误归属均失败。codec协议存在账本、不伪称源有该列。源id必须由本次真实INSERT返回，不能由调用者选择。

核验通过才在底座内部生成私有绑定证明，绑定CAS参数只取本事务持有的真实槽／nonce／源eventId，CAS必须1行；随后注册的提交guard只允许本事务已验证并完成绑定的槽，拒绝残留RESERVED或未经验证markBound。guard关联实际事务资源而非裸ThreadLocal布尔成功；完成／异常退出清理，不跨线程复用。首写领域fixture、槽、源、CAS一次提交，任何环节或提交前失败全回滚，返回值由调用方真实代理提交后才交付。单槽本片不自行实现集合排序／商业flush／预算／coordinator。

BOUND重复只当前锁读账本，核完整业务键、已BOUND映射非空合法、target／payloadVersion／协议／旧与新JSON资源政策及精确内容，返回原eventId；源查询／源INSERT／源锁均为0，不为“可信”新增源反读。可信前提是封闭首次绑定及维护切换全量源／账本核验；受SQL管理员直接破坏的非空错误映射不可能在“不读源”的重复路径实时识别，本片不得声称可以。切换校验须在无writer／引擎的受控阶段全面验证源存在／归属／target／版本／载荷及无漏账本，不自动修复，映射损坏拒绝启用；实际迁移／启动切换仍R3-04未实施，本片只保存隔离校验用例要求，不能以首次测试替代全量门禁。

**独立预审／未来测试矩阵（全部NOT_RUN）：**

| 编号 | 正例／反例 | 必须取证，不能以mock或编译失败代替 |
| --- | --- | --- |
| O-S01 | 测试LEGACY正常首写；生产B Schema缺失／LEGACY／未知version拒绝 | 同一main私有底座，真实REQUIRED代理、正确manager／同连接；源INSERT1、源当前读1、CAS1、持久eventId；B拒绝账本与源零交互 |
| O-S02 | 普通调用者伪造record／nonce／slot／eventId；公开reserve/bind旧路径 | 私有构造／方法不可经普通Java调用，生产无技术bean／配置／jar测试桥；公开Outbox旧路径按D3已删除（编译期不可达，无失败关闭签名残留），Timer公开路径保留且仍受架构白名单；负编译只证明可见性、不当行为RED |
| O-S03 | 缺源、错id／eventId、四列分别错、targetType／name分别错、version／完整payload分别错 | 隔离真实SQL故障逐项制造及真实源读回；每项都不能CAS成功／返回；独立连接领域／账本／源无孤儿 |
| O-S04 | INSERT行数非1、源聚合unique／eventId unique／其它SQL、绑定0／CHECK错误、RESERVED提交、guard缺同步 | 保留真实SQL cause，失败穿代理、afterCompletion回滚；不吞1062、不局部retry；领域／源／账本全回滚 |
| O-S05 | 同payload重复、持旧源行锁重复、旧RR视图、CLIENT_FOUND_ROWS两设置 | 稳定原eventId、BOUND每个字段不变；实际SQL拦截计数源SELECT／INSERT／锁全部0、锁等待证据不能靠sleep；upsert行数不判所有权 |
| O-S06 | 异target／version／protocol／完整payload；未知态／映射NULL／非法eventId；坏JSON／超预算旧BOUND | 完整性失败及cause，旧载荷资源检查不因其它差异短路；合法比较差异／资源拒绝分开，失败不改槽，不触源 |
| O-S07 | 无事务／错manager／read-only／rollbackOnly／未完成重入／线程复用 | 正确资源与同步边界；全部拒绝且无独立提交、无状态污染；代理返回与持久提交一致 |
| O-S08 | 旧appendLocal合法非B；B保留名与源collation等价变体、非法名称／长度 | 真实旧调用链／程序化advisor透明回归；粗过滤快路径零DB；保留槽候选零源INSERT拒绝；无生产self-call／裸new旁路，旧方法字节保持不变；无法封闭则blocked |
| O-S08a | main装配断言（D1） | 生产配置实际装配程序化Advisor并显式注册auto-proxy creator（DefaultAdvisorAutoProxyCreator或BeanNameAutoProxyCreator）且`AopUtils.isAopProxy(outboxEventRepository)==true`；pointcut仅匹配appendLocal单方法单bean；无aspectjweaver/@AspectJ/新依赖；装配缺失或目标非代理即启动失败；生产不裸new Repository、不自调用appendLocal（ArchitectureTest） |
| O-S08b | advisor SELECT零锁副作用与语句计数（D1，BLK-1收窄后） | 14处非B调用点快路径零额外SQL语句（实际拦截计数0）；候选变体恰好1条只读无表SELECT（拦截计数1）；锁副作用以probe执行前后performance_schema.data_locks按该事务连接做**增量对比**断言——probe本身新增锁必须为0，该事务既有锁不计入增量；粗过滤边界逐项（null、<31码点、含ASCII非[A-Za-z_]码点→零DB放行；≥31码点大小写变体、é→E预组合替换、U+0301/U+034F等组合附标插入、零权重候选边界→一律候选SELECT且由DB collation裁决）证明拒绝/放行与冻结边界一致；B保留名及collation等价变体在源Mapper交互前拒绝、源零INSERT、错误类型为SystemIntegrityException；以上等价变体边界以真实MySQL验证后逐项固定 |
| O-S08c | 快照时序负例（D1） | 新RR事务先执行probe SELECT，另一连接提交新行，本事务随后普通SELECT必须可见该新行（读视图不锚定于probe）；对照：probe换成真实表普通SELECT后同一编排不可见（读视图被锚定）——两例同跑证明无表SELECT结构性无快照副作用；14处调用点逐点更早读证据按设计冻结表核验 |
| O-S09 | B身份hex／版本／event-target错配；未知Schema、元字段、整数身份／时间／null规则 | DAL前gating，未冻结项只拒绝不自定；将来BCS正例另片，不以LEGACY成功替代 |
| O-S10 | 首次创建者提交／回滚，两真实等待者；第二个源／绑定失败 | 单槽真实锁竞争及整事务回滚，不承诺无死锁；第二槽／完整集合及恢复扩展仍R3-03／08，不在本片实施retry |
| O-S11 | 切换校验合法完整BOUND；缺源／错归属／异target-version-payload／漏账本 | 隔离全量核验与首次绑定证据分开；无重复路径补修／反读源；实际维护迁移与启动接线仍未验 |

预审须以完整§6.1／§7.2／§7.2.1／§9和真实源码而非本表摘要裁决，尤其审：零DB放行集合收窄（null、<31码点、含ASCII非[A-Za-z_]码点）的可证明安全性及“其余一律候选SELECT”的闭合性（含组合附标插入、预组合替换、零权重候选边界，真实MySQL验证后逐项固定）、公开Outbox API**删除**（D3）对既有三个测试的变化（两个按D4反射桥重写、范围test02–test08或全部Outbox段、`LedgerReservationImmutabilityTest`Outbox段随删、原字节+SHA归档）、LedgerJsonEncoding转public（D2，除账本服务Timer路径既有调用者外底座是唯一新增跨包生产调用者，ArchUnit不为其新增限制）、BLK-2父已确认两重复文件备份移出（`r3-02o/blk2-parent-confirmation.json`）、private测试桥与生产装配（含main显式注册auto-proxy creator）、旧appendLocal不改方法体的程序化advisor隔离及14处调用点快照时序冻结表、O-S08a/b/c三项新取证能否成立。**技术边界无法实际保证即停报；所有方案未独立passed，未运行TDD，R3-02O／F11／切片3仍未关闭，B1/B2仍被阻断。**

#### 2026-10-06 九测试／夹具限定修复设计（用户授权，未验收）

接续HTTP429中断后，唯一原仓写者按 `full-profile-failure-diagnosis/user-test-only-repair-authorization.json` 精确九既有测试路径执行，不新增仓内资源。此授权只校正过时测试与隔离启动，不改生产Java、Mapper、DDL/YAML、A39或O-S07祖先localRollbackOnly；祖先阻断与完整条款矩阵继续open，旧07验收不恢复。原5failure/21error及BeforeAll未执行方法证据保留，不把测试修订说成同字节生产RED→GREEN。

按七根因逐组纵向修改并定向实测，再绑定最新候选跑完整供应链门禁：①供给及两到店测试仅将全量changeSet数量60/51/51校正为冻结登记63，全部约束／项目隔离／失败关闭断言不变；②Quote固定059实际重放边界见直接下单价格设计§4.1，不执行060/061；③LocalTime相对021表增量21→23（061两表），全量63变更集／125表／300 DATETIME(3)时区注释列，060增加1列、061增加4列，UTC→Asia/Shanghai只加八小时及重启不偏移、022不可破坏SQL断言不变；④查询支持夹具遵循052：receipt与标准asset不再插入已删除buyer_user_id，CARD订单身份明确NULL，18个查询／审计业务断言不变；⑤路由quota CARD夹具身份NULL，quota与稳定优先级断言不变；⑥Auth仅在本类显式加入test-only配置与32字节防御复制身份provider，不读取环境密钥、不改生产fail-closed，完整HTTP认证与nonce门禁原样；⑦Movie仅在本类启动参数关闭bootstrap/Nacos config/discovery/import-check，不导入真实配置，不外呼供应商。

每轮使用固定HEAD archive加精确status增量（含新增／删除），独占scratch无旧target；拒读dataless。离线Maven实际JDK21、-am、supply-chain-integration；只跳其它模块测试，API契约实际绑定独立核。归档实际命令、Maven／runner退出、原XML逐类逐方法和源前后hash，BeforeAll空方法不计全类执行。最终门禁未结束不计绿，隐藏失败越九代码路径需生产修改则停止，不扩授权。证据根 `r3-02o/test-fixture-repair`；本节为代码前设计冻结，运行／独立双审／父验收均未完成。

**本轮定向实测检查点：** 七组按顺序fresh archive验证共65执行（各轮汇总，非同轮完整profile），failure/error/skip全0、Maven／runner均0、每轮源码前后hash一致。供给BeforeAll恢复后7个原方法实测，Auth BeforeAll恢复后32个参数化实际执行均实测；查询／资产两类19执行中原18个夹具错误已消除。Quote固定059的无数量限制重放、059 checksum/EXECTYPE不变、060/061执行0均通过。03轮JVM全局Nacos禁用掩盖了Movie原import-check错误，不能用它作该类修复依据；09轮移除全局覆写后真实1error，10轮仅本类隔离参数后通过。所有原失败XML与测试原字节保留，不称生产RED→GREEN。下一步新候选完整profile/API门禁，尚未通过或验收。

**最终候选接续状态：** 复核真实DDL确认300列增量为060的`valid_until`1列＋061两账本4列，不是061独自5列；先修本节设计归因，再仅勘误LocalTime一行注释，断言／可执行token不变，13轮fresh MySQL重验2执行全0。11轮完整profile使用勘误前源码，已定位本轮Maven／fork线程并仅向自有Maven发送SIGTERM以避免与新完整门禁并发；实际Maven／runner143，231 XML／2182局部执行全0、源码前后一致，但明确是受控中断，不是完整门禁通过或行为RED。旧现场与终止操作证据保留。14轮将以最新字节重新运行未限定it.test完整profile；在取得最终真实退出和原XML前保持待验证，API12轮7类45执行全0仅在API字节完全相同的范围复用。不自验收、不关闭祖先阻断。

### 7.2.2 完整技术 worker 意图收集、数据库 Collation 确定性排序与单次 Flush 协议（R3-03）

**2026-10-06 修正替代声明（覆盖旧算法文本，本小节为 R3-03 唯一规范性来源）：** 旧版§7.2.2（2026-10-05，`deleg_23a851ff` 时代实现）已被父补核裁定不满足冻结契约，明确废止以下旧算法：Java `String.CASE_INSENSITIVE_ORDER.thenComparing(compareTo)` 不是数据库 Collation；`aggregateType+"|"+aggregateId+...` 字符串拼接键存在复合键分隔碰撞；同键合并只比 payload 不比 target/version；Outbox 意图经旧 `appendLocal` 严格 INSERT，绕过账本底座，无 RESERVED 守卫；收集器不核事务归属/read-only/rollbackOnly/外层 scope；守卫同步覆写 `TransactionSynchronization.flush()`，状态机可被 Spring `triggerFlush` 回调重入；flush 后无领域写守卫。本节为修正设计候选，状态 `DESIGN_PENDING_INDEPENDENT_PRE_REVIEW`：未实施、未运行测试、未验收。依赖 R3-01／R3-02O／R3-02T／O-S07（均已闭环）复用其冻结产物，不改动其内容。完整条款矩阵与 RED 负例见 `docs/superpowers/plans/2026-10-06-r3-03-reopened-repair.md` 及仓外 `r3-03-repair-20261006/clause-matrix.json`。

**2026-10-06 rev2 修订（首轮独立预审回执 failed 后按裁定修订，阶段仍 `DESIGN_PENDING_INDEPENDENT_PRE_REVIEW`）：** 独立预审裁决 35 passed／11 failed／0 unknown，三组阻断，本小节已按预审最小修订方向与用户裁定完成 rev2（不重做已成立部分）：**BLK-1** 第 4 点两条冻结 SQL 由「GROUP BY 返回分组代表键」改为「逐键返回行＋`DENSE_RANK() OVER (ORDER BY 已带 collation 派生列)` 组号＋原始精确列回传定位」，并同步第 3 点 Java 处理语义（按组号分组、组内合并/冲突、按组号顺序逐槽执行）；**BLK-2（用户裁定 B）** 第 6 点删除生产 `appendLocalTechnical` 通道：生产 collector 对 Outbox 槽执行保持失败关闭（零 DAL 前 `SystemIntegrityException`、不静默跳过），协议由仅 src/test 同模块反射桥驱动真实 main 底座证明，商业接线时再裁决生产执行通道（R3-02O-S 冻结边界文本不改）；**BLK-3** 第 7 点冻结锁定 SELECT 词法规则、既有 7 个 Mapper XML 语句分类表与假阳/假阴负控制；**N1** 第 5 点 triggerFlush 事实表述按本地 spring-tx-6.2.18 源码勘误（原「仅 JtaTransactionObject.flush() 调用」为旧版描述），并随修订固化 N2–N8。未实施、未运行测试、未验收。

1. **职责与生命周期归属（收集窗口与最外层 owner 能力）**：
   - 意图收集器 `LocalLedgerIntentCollector` 严格归属于**实际最外层供应链写事务**：`TransactionContext` 经 `TransactionSynchronizationManager.registerSynchronization` 注册为 `TransactionSynchronization`，绑定本次 `ConnectionHolder`，`afterCompletion` 清空；不裸 ThreadLocal、不跨线程复用。
   - `recordOutboxIntent`／`recordTimerIntent`／`flush` 每次进入必须通过**全部**资源守卫，任一不满足即 `SystemIntegrityException` 且零 SQL 副作用：`isActualTransactionActive`、`isSynchronizationActive`、非 `isCurrentTransactionReadOnly`、`getResource(supplyChainDataSource)` 为已同步 `ConnectionHolder`、holder 非 rollbackOnly、当前代理 status 非 rollbackOnly（`TransactionAspectSupport.currentTransactionStatus`，`NoTransactionException` 仅代表无代理的 TransactionTemplate 路径，仍须前面资源项齐全）、活动同步集合存在本 holder 的未 completed `SupplyChainTransactionObservationManager.Scope`、Scope 在途全部 status 非 rollbackOnly。错 manager（另开事务无 Scope）、只读、rollbackOnly、外层 scope 不明（手动 bindResource 无 Scope）、未知 manager 一律拒绝。REQUIRED 嵌套代理 join 同一 holder／Scope 复用同一 TransactionContext，不得另建上下文。
   - 在领域写全部完成前，意图仅在事务本地内存中缓冲，**禁止提前建槽、禁止对账本表加锁**。

2. **身份记录与深不可变（复合键分隔碰撞、mutable payload 防污染）**：
   - 键为不可变 record 元组 `OutboxKey(aggregateType, aggregateId, aggregateVersion, eventType)`／`TimerKey(taskType, idempotencyKey)`，内存映射以 record 为键，**禁止任何分隔符拼接编码**。旧 `"|"` 拼接键的复合键分隔碰撞（如 `("A|B","C",1,"E")` vs `("A","B|C",1,"E")`）必须作为 RED 负例被新实现消除。键 record 采用**显式 getter 命名**（`aggregateType()` 等，不依赖 MyBatis Reflector 字段回退——3.5.19 `GetFieldInvoker` 字段回退已核可读，仍以显式 getter 为准），XML `#{k.*}` 绑定契约测试固化（N2）。
   - record 时立即序列化 payload 为 JSON 字符串并执行 `LedgerJsonEncoding.canonicalBytes`（预算＋语法＋规范化），缓冲不可变 String 与 canonical byte[]；调用方事后修改原 JsonNode／Object 不影响已缓冲意图。`fireAt` 为不可变 Instant。每个意图记录时递增单调 `recordSeq`（记录时点顺序），作为 DB 等价组合并时「最后记录元数据」的判定依据（第 3 点）。
   - `OutboxIntent` 显式携带 `targetType`（仅 LOCAL）、`targetName`、`payloadVersion`（≥1）、`comparisonProtocol`（仅 `LEGACY_JSON_V1`）；`TimerIntent` 携带 `payloadVersion`（仅 1，与 `scheduleOnce` 冻结一致）、`comparisonProtocol`（仅 `LEGACY_JSON_V1`）。记录时校验。

3. **等价合并与冲突拒绝（数据库 Collation 分组＋最终元数据）**：
   - 权威等价分组**不由 Java 决定**：flush 冻结阶段执行参数化无实体表 SQL（第 4 点），数据库按 `utf8mb4_0900_ai_ci` 对字符串列排序并计算等价组号 `grp`（`DENSE_RANK`，废弃 GROUP BY——GROUP BY 每等价组只返回一行代表键，无法映射组成员），数值列按数值。Java 按 SQL 返回行序处理：每行以原始精确列 `k*_orig` 定位内存 record（内存 map 以原始元组为键、字节相等快路径已预合并相同精确元组，每行恰定位唯一 record），`grp` 相同即同一 DB 等价组。同一等价组内：`targetType`／`targetName`／`payloadVersion`／`comparisonProtocol` 与 canonical payload 全等 → 合并为单槽，**保留最后记录意图的元数据**（`recordSeq` 最大者；Outbox：targetName／traceId／occurredAt；Timer：fireAt）；任一不同 → `SystemIntegrityException`，且发生在**首个账本锁之前**，该组零账本/源 DAL 交互。
   - 字节相等快速路径仅作内存 map 预合并，不作为 DB 等价判断依据。大小写／重音／组合字符等价、复合键分隔碰撞均以真实 MySQL 验证后逐项固定为反例。
   - 组号 `grp` 仅作等价分组依据（DENSE_RANK 保证同组行相邻）；槽执行顺序以返回行序（数据库 Collation 序）为准，Outbox 全部组先于 Timer 全部组（第 6 点）。

4. **冻结参数化无实体表排序/分组 SQL（每 flush 每类恰好 1 条，首个账本锁之前执行；rev2 逐键返回行＋DENSE_RANK 组号＋原始精确列定位）**：

   不使用 GROUP BY（GROUP BY 每等价组仅返回一行代表键，Java 无法重演 collation 等价、无法把 DB 等价组成员映射回内存 record）。改为**逐键返回一行**：外层投影 collation 派生列（数据库排序/等价的唯一权威）＋原始精确列 `k*_orig`（仅回传定位，字符串不 CAST 归一，数值 CAST AS SIGNED 无损）＋`DENSE_RANK() OVER (ORDER BY 已带 collation 的派生列) AS grp` 等价组号。每 flush 每类（Outbox／Timer）恰好执行 1 条；N=0 不执行；参数化绑定（MyBatis foreach，禁止拼接外部输入）；无实体表派生表 → 零 InnoDB 访问。

   ```sql
   -- Outbox 意图（N≥1 时恰好执行 1 条；N=0 不执行）
   SELECT CAST(t.k1 AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS aggregate_type,
          CAST(t.k2 AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS aggregate_id,
          CAST(t.k3 AS SIGNED)                                AS aggregate_version,
          CAST(t.k4 AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS event_type,
          t.k1_orig                                           AS aggregate_type_orig,
          t.k2_orig                                           AS aggregate_id_orig,
          t.k3_orig                                           AS aggregate_version_orig,
          t.k4_orig                                           AS event_type_orig,
          DENSE_RANK() OVER (ORDER BY t.k1, t.k2, t.k3, t.k4) AS grp
   FROM (
     <foreach collection="keys" item="k" separator=" UNION ALL ">
     SELECT CAST(#{k.aggregateType}    AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS k1,
            CAST(#{k.aggregateId}      AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS k2,
            CAST(#{k.aggregateVersion} AS SIGNED)                                AS k3,
            CAST(#{k.eventType}        AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS k4,
            #{k.aggregateType}    AS k1_orig,
            #{k.aggregateId}      AS k2_orig,
            CAST(#{k.aggregateVersion} AS SIGNED) AS k3_orig,
            #{k.eventType}        AS k4_orig
     </foreach>
   ) t
   ORDER BY t.k1, t.k2, t.k3, t.k4
   ```

   ```sql
   -- Timer 意图（N≥1 时恰好执行 1 条；N=0 不执行）
   SELECT CAST(t.k1 AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS task_type,
          CAST(t.k2 AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS idempotency_key,
          t.k1_orig                                     AS task_type_orig,
          t.k2_orig                                     AS idempotency_key_orig,
          DENSE_RANK() OVER (ORDER BY t.k1, t.k2)       AS grp
   FROM (
     <foreach collection="keys" item="k" separator=" UNION ALL ">
     SELECT CAST(#{k.taskType}      AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS k1,
            CAST(#{k.idempotencyKey} AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci AS k2,
            #{k.taskType}      AS k1_orig,
            #{k.idempotencyKey} AS k2_orig
     </foreach>
   ) t
   ORDER BY t.k1, t.k2
   ```

   窗口 `ORDER BY t.k1…` 与最外层 `ORDER BY t.k1…` 均引用派生表**已带 `COLLATE utf8mb4_0900_ai_ci`** 的列（派生列 collation 已生效，窗口内不重复写 COLLATE）；`t.k3` 为 `SIGNED` 数值，数值列按数值排序；`k*_orig` 为参数精确回传（字符串列不做 CAST 归一——原值字节不变，仅回传定位用；数值列 `CAST AS SIGNED` 对 long 十进制无损，定位列即精确值），Java 侧与提交时的原始参数逐字/逐值比对定位内存 record（Outbox `aggregate_version_orig` 按 long 解析比对）。

   两条 SQL 均为**无实体表派生表表达式**：参数化绑定（MyBatis foreach，禁止拼接外部输入），不访问任何 InnoDB 表 → 不取得任何行/间隙锁（`performance_schema.data_locks` 前后增量 0）、不建立 RR 读视图（O-S08c 式结构证明＋负例），执行经调用方事务连接的 `supplyChainSqlSessionFactory` 同连接，不新借池连接、不切换 manager、不进入 R01/R02 预算资格判定。分组（grp）与排序同一条语句由数据库 Collation 完成；执行时点固定在**首个账本锁之前**，锁后发现新意图必须整事务失败，不得边发现边追加。Connector/J 源 INSERT 前的 `SELECT @@SESSION.transaction_read_only` 按 R3-02O 冻结白名单以完整 SQL digest 匹配，其它 SELECT 拒绝。UNION ALL 分支数 N 为单事务意图数，技术 fixture 规模下无 max_allowed_packet/解析器风险（N 不设设计上限，超限即驱动/协议报错阻断 flush，属失败关闭；N6）。

   处理语义（Java，冻结）：
   - 按 SQL 返回行序逐行处理；每行以 `k*_orig` 精确定位唯一内存 record（原始元组在内存 map 中唯一，字节相等快路径已预合并相同精确元组）。
   - 相同 `grp` 的相邻行构成一个 DB 等价组（DENSE_RANK 保证同组相邻）；组内执行第 3 点全等/冲突判定与合并（保留 `recordSeq` 最大者的元数据）。
   - 槽执行按组号升序（即返回行序）、组内合并后每等价组恰执行 1 槽；Outbox 全部组执行先于 Timer 全部组（生产 Outbox 执行入口失败关闭，见第 6 点；测试路径经反射桥驱动真实底座证明协议）。
   - 任一组冲突 → `SystemIntegrityException` 整体失败，发生在首个账本锁之前；该 flush 零账本/源 DAL 交互。
   - 语句计数义务：Timer 生产路径每 flush 恰 1 条 Timer SQL（N≥1）；Outbox SQL 由测试夹具路径经生产 `CollationOrderingMapper` 驱动，每夹具 flush 等价步骤恰 1 条（N≥1）；生产 collector 对含 Outbox 意图的 flush 在分组 SQL 前失败关闭（第 6 点），故生产路径 Outbox SQL 执行数为 0（N5 同范围声明）。

5. **单次 Flush 语义、Spring 回调防偷触发与提交前守卫**：
   - 事实核验（N1 勘误，已读本地 spring-tx-6.2.18-sources.jar）：`AbstractPlatformTransactionManager.prepareForCommit` 为空实现；`TransactionSynchronizationUtils.triggerFlush()` 为 public 静态方法，在 spring-tx 6.2.18 内**无任何框架内调用者**（原设计「仅 `JtaTransactionObject.flush()` 调用」为旧版描述，6.2.18 已无该调用点）；MyBatis-Spring 3.0.5 不调用 triggerFlush。当前 JdbcTransactionManager 路径不被偷触发（结论方向不变且更保守），但 triggerFlush 为 public 静态方法，未来框架版本／外部代码仍可调用，守卫同步覆写 `flush()` 会使状态机可被重入。
   - 冻结：事务守卫 `TransactionSynchronization` **不得覆写 `flush()`**（保留接口默认 no-op）；显式 flush 仅经 `LocalLedgerIntentCollector.flush()` 公共入口进入状态机。结构 RED 负例：对旧实现（覆写 flush 的同步）在显式 flush 后调用 `TransactionSynchronizationUtils.triggerFlush()` → `SystemIntegrityException`（证明旧实现可被偷触发）；新实现同一编排 → 无副作用（GREEN）。新增 ArchitectureTest：生产 `infrastructure.ledger`／`infrastructure.outbox` 的 `TransactionSynchronization` 实现不得覆写 `flush()`，含违规 fixture 负控制。
   - 状态机 `COLLECTING → FLUSHING → FLUSHED`；同一事务只能显式 flush 一次，二次显式 flush → `SystemIntegrityException`。
   - 嵌套提前 flush 拒绝：Spring 无法从方法层区分嵌套调用，编码契约＝**恰好一次 flush＋flush 后写守卫**（第 7 点）：嵌套方法提前 flush 后，外层任何后续领域写／行锁触发失败关闭，整事务失败，不存在“部分提交后继续写”的成功路径。
   - `flush()` 执行：冻结收集器，按第 4 点分组排序结果依次执行各槽。**生产路径**：含 Outbox 意图 → 第 6 点失败关闭（零 DAL）；仅 Timer 意图 → Timer 按组序经 `scheduleOnce`（R3-02T 已闭环路径，排他建槽／真实源插入／CAS 绑定）执行。**测试路径**：Outbox 槽经反射桥驱动真实底座执行（第 6 点）。任何槽的建槽、插入或绑定失败，异常原样上抛并**保留完整 cause 链**（不 catch-swallow、不局部重试；包裹时以原异常为 cause），整事务回滚，无孤儿槽、无孤儿源行。
   - `beforeCommit` 双向守卫（N7 分层表述）：存在非空意图且未 FLUSHED → 拒；FLUSHED 之后任何新意图追加 → 拒（record API 守卫）；FLUSHED 之后新领域写锁由第 7 点拦截器在**执行前**抛异常承担——beforeCommit 本身只核意图状态，双层机制合计满足「flush 后新增领域锁或意图使整事务失败关闭」契约。残留 RESERVED／未验证绑定由底座与 Timer 服务既有守卫拒绝。`afterCompletion` 彻底清空收集器状态（不重置回可复用 COLLECTING），防止线程池复用上下文泄露；afterCompletion 返回后活动同步集合不再含本 TransactionContext（Spring 自动解注册），第 7 点拦截器「存在 FLUSHED 上下文」判定在同线程后续事务自然为假（N3）。

6. **槽执行协议（Outbox 生产失败关闭＋测试反射桥证明、Timer 经 scheduleOnce、RESERVED 守卫）**：
   - Outbox（**用户裁定 B，rev2 冻结；不新增生产 `appendLocalTechnical` 通道，`OutboxEventRepository.java` 零修改**）：
     - 生产失败关闭：生产 `LocalLedgerIntentCollector.flush()` 冻结意图集合含 Outbox 意图 → 立即抛 `SystemIntegrityException`（**零 DAL**：发生在任何分组 SQL、任何账本锁、任何源 DAL 之前；**不静默跳过**——不得跳过 Outbox 仅执行 Timer 并提交），整事务失败关闭。当前生产无 Outbox 意图产生者（collector 无生产调用者），此为防御性契约。
     - 协议证明（测试路径）：新增仅 src/test 的同模块 `privateLookupIn` 反射桥 `TrustedOutboxTechnicalAppendBridge`（参照既有 `TrustedOutboxAppendCoreBridge` 模式；JDK21 同 unnamed module，无需 add-opens），构造 `VerifiedAppendCommand`（targetType=LOCAL、comparisonProtocol=LEGACY_JSON_V1、correlationId/causationId=null）并调用**同一**真实 main 底座 `TrustedOutboxAppendCore.append`——底座排他建槽／当前锁读／nonce／首次源 INSERT1／源当前读核验／CAS1／RESERVED 提交守卫原样复用，**算法不复制**。测试夹具以生产 `CollationOrderingMapper`（第 4 点冻结 SQL）取得 DB 等价分组与组序，按组序经桥逐槽驱动底座，证明等价合并／异载荷冲突／组序执行／第二槽整批回滚协议；分组编排仅测试代码，所用分组 SQL 与槽执行底座均为真实 main 产物，不做第二套 test-only 算法（无 Java collation 归一、无底座复制）。
     - 未来待裁决（仅记录，本次不实施）：**商业接线时再裁决生产执行通道**（生产 collector 接入 Outbox 执行的技术通道形态，届时独立预审并说明与 R3-02O-S 冻结边界文本「不新增生产 LEGACY 入口」的张力消解）。本次**不改** R3-02O-S 冻结边界文本（§7.2.1）。
     - 不变项：`OutboxAppendIdentityMapper` 三写方法仍仅底座；B 入口 `appendLocalIdempotent` 继续 DAL 前失败关闭；旧 `appendLocal` 方法体字节不变、D1 advisor 不变。
   - Timer：经 `DatabasePersistentTimer.scheduleOnce`（R3-02T 已闭环）执行；payloadVersion=1／LEGACY_JSON_V1 与意图一致；重复键、异载荷、终态不重开等冻结语义由该路径承担。
   - 执行顺序：Outbox 全部组先于 Timer 全部组（表内按组号/行序＝数据库 Collation 序；Outbox 执行仅在测试桥路径）。

7. **flush 后写守卫（真实 DML／行锁，不能只 record API；锁定 SELECT 词法规则 rev2 冻结）**：
   - 生产 MyBatis Interceptor（候选名 `LedgerPostFlushWriteGuardInterceptor`）仅装配于 `supplyChainSqlSessionFactory`（main 实际装配，不改其它模块／其它 SqlSessionFactory；`SupplyChainDataSourceConfiguration` 非 A39）：当活动同步集合存在状态 FLUSHED 的 TransactionContext 且其 holder 匹配当前事务连接时，`Executor.update`（INSERT/UPDATE/DELETE）及**锁定 SELECT**（按下列冻结词法规则判别）执行前抛 `SystemIntegrityException`。FLUSHING 窗口内（flush 自身写入）不拦截；普通无锁 SELECT 不拦截（负控制证明不过度拦截）；无活动收集上下文的事务完全透明；同线程下一事务不拦截（无 ThreadLocal 泄漏）。`TransactionContext` 以同包 package-private 暴露状态与 holder 供拦截器核验（`LocalLedgerIntentCollector.java` 属白名单）。
   - **锁定 SELECT 词法规则（2026-10-06 rev2 冻结，大小写不敏感；识别在语句级别，去除注释与字面量后词法检测）**：
     1. **判别对象**：拦截器在 `Executor.query` 处取得 `BoundSql.getSql()`（动态标签已展开的语句文本，`#{}` 已替换为 `?` 占位），对整条语句做**语句级**扫描——子查询／CTE／括号嵌套深度不影响判定，任一顶层（NORMAL 状态）位置命中即判锁定（fail-closed 方向）；同一 MappedStatement 的展开文本确定，判别结果可按语句签名缓存。
     2. **词法剥离（单遍状态机，固定顺序）**：单引号、双引号与反引号三种字面量/引用标识符（单引号内两个连续单引号或反斜杠转义不结束、双引号内两个连续双引号或反斜杠转义不结束、反引号内两个连续反引号不结束）；`-- ` 至行尾（MySQL 要求 `--` 后随空白或行尾）与 `#` 至行尾两种行注释；`/* … */` 块注释（MySQL 不嵌套）。剥离后仅在顶层（NORMAL 状态）做模式匹配。
     3. **匹配模式**（NORMAL 状态、任意空白记为 `[ \t\r\n]+`）：`FOR UPDATE`、`FOR SHARE`、`LOCK IN SHARE MODE`；其后可选 `OF 列列表` 及可选 `NOWAIT`／`SKIP LOCKED` 后缀——后缀与 `OF` 变体**不改变锁定判定**（一并判锁定）。`FOR`／`LOCK` 为 MySQL 保留字，不能作裸标识符，字面量/注释/反引号标识符内的文本已在剥离阶段排除，故无需额外边界回看。
     4. **假阳负控制（必须判非锁定、放行）**：字符串字面量内 `'FOR UPDATE'`／`"FOR UPDATE"`；行注释内 `-- FOR UPDATE`／`# FOR UPDATE`；块注释内 `/* FOR UPDATE */`；反引号标识符 `` `for update` ``；整条语句无锁定子句的普通 SELECT（含子查询无锁、外层无锁）；列名/别名仅含 `update`（如 `SELECT u.update_time FROM t u`）。
     5. **假阴负控制（必须判锁定、拒）**：大小写变体（`for update`／`For Update`／`FOR UPDATE`）；多空白/换行变体（`FOR \n UPDATE`、`LOCK  IN   SHARE  MODE`）；`FOR UPDATE SKIP LOCKED`；`FOR UPDATE OF c1, c2`；`FOR UPDATE NOWAIT`；`FOR SHARE`／`FOR SHARE OF c1`；`LOCK IN SHARE MODE`；`ORDER BY … LIMIT 1 FOR UPDATE`（锁定子句前有 ORDER BY/LIMIT）；子查询在前、外层 `FOR UPDATE`（`… WHERE id IN (SELECT …) ORDER BY id FOR UPDATE`）。
     6. **既有 7 个 Mapper XML 全量语句分类表（rev2 按 HEAD 实核；实施前逐条重核语句文本，漂移即停报）**：
        | XML | 语句 | 判定 |
        | --- | --- | --- |
        | fulfillment/RechargeFrequencyEntryMapper.xml | selectByIdForUpdate | 锁定 |
        | fulfillment/RechargeFrequencyEntryMapper.xml | selectFirstWaitingForUpdate（ORDER BY … LIMIT 1 FOR UPDATE） | 锁定 |
        | fulfillment/RechargeFrequencyExecutionMapper.xml | lockRequest／lockOrder | 锁定 |
        | fulfillment/RechargeFrequencyExecutionMapper.xml | lockBOrders（ORDER BY id FOR UPDATE） | 锁定 |
        | fulfillment/RechargeFrequencyExecutionMapper.xml | lockOperations（IN 子查询＋外层 ORDER BY id FOR UPDATE） | 锁定 |
        | fulfillment/RechargeFrequencyQueueMapper.xml | selectByIdForUpdate | 锁定 |
        | infrastructure/OutboxAppendIdentityMapper.xml | insertSlotOnDuplicateKeyUpdate | 写语句（Executor.update 拦截，非锁定判别） |
        | infrastructure/OutboxAppendIdentityMapper.xml | selectForUpdate | 锁定 |
        | infrastructure/OutboxAppendIdentityMapper.xml | bindEventCas | 写语句 |
        | infrastructure/OutboxEventMapper.xml | selectDeliverable（LIMIT … FOR UPDATE SKIP LOCKED） | 锁定 |
        | infrastructure/OutboxEventMapper.xml | selectBrokenActiveLeaseIds | 非锁定（放行） |
        | infrastructure/TimerScheduleIdentityMapper.xml | insertSlotOnDuplicateKeyUpdate | 写语句 |
        | infrastructure/TimerScheduleIdentityMapper.xml | selectForUpdate | 锁定 |
        | infrastructure/TimerScheduleIdentityMapper.xml | bindTaskCas | 写语句 |
        | infrastructure/TimerTaskMapper.xml | selectClaimable（LIMIT … FOR UPDATE SKIP LOCKED） | 锁定 |
        | infrastructure/TimerTaskMapper.xml | selectBrokenActiveLeaseIds | 非锁定（放行） |
        **与既有含 FOR UPDATE 的 XML 面兼容**：无收集上下文事务中上述锁定/写语句完全透明（守卫仅 FLUSHED 上下文生效，其余事务行为零改变）；FLUSHED 上下文中上述锁定 SELECT 与写语句被拒（契约要求：flush 后不得再取领域行锁）。
     7. **装配与链序（N4）**：`SupplyChainDataSourceConfiguration.supplyChainSqlSessionFactory` 将 `LedgerPostFlushWriteGuardInterceptor` 与既有 `MybatisPlusInterceptor`（PaginationInnerInterceptor／OptimisticLockerInnerInterceptor）一同加入 `factory.setPlugins` 链（MyBatis 按注册顺序包装）；守卫在 `invocation.proceed()` 前完成上下文与词法判别并抛出，分页/乐观锁插件不改写锁定子句文本；负控制固定：互换链序，锁定语句仍拒、非锁定语句仍放行。
     8. **范围声明（N5）**：写守卫范围是 MyBatis 层（`supplyChainSqlSessionFactory` 全部 Mapper）；生产直连 JDBC 领域写面（`BusinessCodeLegacyBackfill` 的 JdbcTemplate、预算 wrapper、clock）当前为零且均无收集上下文（上下文门控透明），该事实实施时重核并写入测试断言；生产路径 Outbox 分组 SQL 执行数为 0 属同一范围声明（第 4 点）。
     9. **FLUSHING 窗口不变量（N8）**：FLUSHING 放行仅覆盖 flush 路径固定代码（分组 SQL＋`scheduleOnce` 写入＋测试路径底座写入，无领域写）；`flush()` 实现内注释冻结「不得在 FLUSHING 窗口引入任何领域写」。
   - `recordOutboxIntent`／`recordTimerIntent` 在 FLUSHED 后调用 → 拒（record API 守卫保留）。
   - 目标：flush 后任何新领域写／行锁／意图 → 整事务失败关闭。

8. **死锁声明边界**：确定性分组排序仅消除“两事务对同一多槽集合以相反顺序加锁”的锁序环风险，**不宣称无死锁**；§7.2 撤回的单槽 1213 反例保留不改 GREEN；一次 A/Z 并发成功只是排序证据；完整事务重跑（V4A／coordinator）属 R3-08，本片不动 coordinator／BudgetContext／retry。

9. **最小运行字节白名单（拟名，实施前核对既有类；rev2 按用户裁定 B 调整）**：
   - 修改生产：`infrastructure/ledger/LocalLedgerIntentCollector.java`、`infrastructure/configuration/SupplyChainDataSourceConfiguration.java`（supplyChainSqlSessionFactory 注册拦截器；该文件不在 A39）。**`infrastructure/outbox/OutboxEventRepository.java` 零修改**（rev2：不新增生产通道，底座／`VerifiedAppendCommand`／`BindingProof` 全部字节不变）。
   - 新增生产：`infrastructure/ledger/dal/mysql/CollationOrderingMapper.java`＋`src/main/resources/mapper/infrastructure/CollationOrderingMapper.xml`（第 4 点两条无实体表参数化 SQL）、`infrastructure/ledger/LedgerPostFlushWriteGuardInterceptor.java`。
   - 测试：重写/扩展 `LocalLedgerIntentFlushIntegrationTest`（Outbox 段改为生产失败关闭断言，Timer 段保留；原字节归档）；新增 `LocalLedgerIntentCollationOrderingIntegrationTest`（Outbox 排序/合并/组序经生产 `CollationOrderingMapper`＋反射桥驱动真实底座）、`LocalLedgerIntentPostFlushGuardIntegrationTest`（锁定 SELECT 词法规则正反例＋假阳/假阴负控制）、`architecture/LocalLedgerIntentCollectorArchitectureTest`（禁止生产新增 Outbox 执行通道的负控制——除既有 `OutboxEventRepository` 自身外任何生产类不得依赖 `TrustedOutboxAppendCore`；mapper 写方法仍仅底座；同步不得覆写 flush()；`CollationOrderingMapper` 仅 collector 及测试可调用）、仅 src/test 反射桥 `infrastructure/ledger/TrustedOutboxTechnicalAppendBridge.java`。
   - 零修改：A39 全 39 路径、历史 changelog（061 不追加）、旧 `appendLocal` 方法体、**`OutboxEventRepository.java` 全文件**、`LocalLedgerTransactionalWorkerCoordinator`／`LocalLedgerDeadlockDetector`／`LocalLedgerDeadlockException`／BudgetContext／预算包装、R3-04 全部、O-S01~O-S11／R1–R9 已闭环产物、既有 `OutboxAppendIsolationArchitectureTest`（新规则进新文件）。若实施需超出白名单，停报最小 diff。

### 7.3 页上界与原子续页

根消费在首次成功的短事务固定扫描upperListingId；第一页叶子、携带同一上界的后续页、Inbox及根完成一起提交。根此前全回滚时可在下一次重新取上界，因为没有已提交子页；根已成功重复时先走Inbox重复分支，不重新生成上界。之后全部页只沿持久上界，以LST主键严格递增、有限batch扫描AUTO行。

每页原子追加本页叶子及下一页再完成来源；空末页也必须完成，不无限生成相同页。新上界外的LST由其真实生产者和下一巡检轮覆盖。旧页、乱序页和重复叶子只唤醒读当前，不传播旧价格/AVAILABLE为授权。

## 8. scheduleOnce、时间轮次及重启

现有scheduleOnce保持“同type/key同逻辑任务，任何已有终态不自动重开”。B需要新轮次必须提供新key；不能重置旧COMPLETED/DEAD/CANCELLED的次数或身份。

scheduleOnce采用§7.2独立Timer账本协议：同type/key首次账本与新任务映射同事务，重复只当前读账本并核payloadVersion/比较协议/完整业务payload，不重复INSERT或FOR UPDATE对应源Timer。旧key经维护窗口回填后可直接重复；缺映射不能在持领域锁时锁源修复。next_fire_at是可退避变化的列，不与第二次fireAt简单比较；B的原始boundary/bucket放在稳定业务载荷与key中，旧立即执行重放仍保留第一次任务。其它SQL、task_no唯一冲突或损坏JSON保留cause并整事务回滚。

实际TimerHandle含taskNo/taskId/fencingToken。taskNo/id由账本返回；fencingToken只是观测，不是执行授权，不从不可变账本伪造“当前token”。首次及同事务刚创建的任务可返回真实0；重复既有已提交任务若需观测token，使用独立新只读事务的非锁MVCC查询，不能FOR UPDATE等待源，也不能用旧RR看不到行就新建任务。该观测只读不逃逸写效果，额外连接计入§5的R；发现实际源被删除报完整性故障。所有启动/续租/完成仍只信任真实claim上下文，不使用调度handle取得资格。B新批量账本flush遵守固定多槽顺序及不再取领域锁的约束。

### 8.1 真实 Timer 调度与账本绑定实现（R3-02T）

1. **入口与契约转换**：
   - `DatabasePersistentTimer.scheduleOnce(taskType, idempotencyKey, payload, fireAt)` 改造为使用 `AppendScheduleIdentityLedgerService` 独立不可变身份账本（`sc_timer_schedule_identity`）管理排他建槽与唯一映射。
   - 入口必须在有效写事务（`supplyChainTransactionManager`，`REQUIRED`）内执行；若无活动事务，由账本守卫直接阻断并抛出 `SystemIntegrityException`。
   - 载荷处理：将 `payload` 序列化为规范 JSON，调用 `ledgerService.reserveTimerSlot(taskType, idempotencyKey, 1, "LEGACY_JSON_V1", payloadJson, now)` 进行排他建槽与载荷守卫检查。

2. **首次调度路径（firstReserved == true）**：
   - 账本返回首次保留凭证（`slotId`, `nonce`）；
   - 生成全新唯一业务单号 `taskNo = businessNoGenerator.next("TMR_")`；
   - 构造 `TimerTaskDO`（`status=0, attemptCount=0, fencingToken=0L, version=0L` 等初始字段），严格执行 `timerTaskMapper.insert(task)` 插入 1 行真实源任务；
   - 紧随源插入后，立即调用 `ledgerService.bindTimerTask(slotId, nonce, task.getId(), task.getTaskNo(), now)` 完成不可变账本绑定；
   - 返回 `TimerHandle(task.getTaskNo(), task.getId(), 0L)`，同事务新任务真实 token 确定为 0L；
   - 故障语义：若源插入、绑定 CAS 或后续操作发生任何 SQL/业务异常（如 task_no 唯一键冲突、CHECK 违约等），异常完整上抛并保留 cause，整事务回滚（源任务行与账本槽一同回滚，杜绝孤儿槽与孤儿任务）。

3. **重复调度路径（firstReserved == false）**：
   - 账本返回已存在任务的绑定信息（`existingTaskId`, `existingTaskNo`）；
   - 重复调度路径对 `sc_timer_task` **零 SELECT / 零 INSERT / 零行锁**，直接返回 `TimerHandle(result.existingTaskNo(), result.existingTaskId(), 0L)`；
   - 彻底消除在持有外层业务/领域锁时等待 `sc_timer_task` 行锁造成的间接死锁环；
   - 载荷冲突防范：若重复请求携带不同 payload、不同 payloadVersion 或损坏的 JSON，账本排他建槽检查阶段直接抛出 `SystemIntegrityException`，源表零交互。

4. **终态与取消语义**：
   - 现有 `cancel(taskNo, reason)` 方法逻辑保持不变，仅操作源表 `sc_timer_task` 的取消状态；
   - 终态旧任务（COMPLETED/DEAD/CANCELLED）的 idempotencyKey 不自动重开；重复调度仍返回历史已绑定任务 handle，不重置 attempt 或覆盖状态。

### 8.2 强制旧 Timer 迁移与启动切换检查实现（R3-04）

1. **架构定位与应用级服务**：
   - 实现为应用级迁移与核验服务 `TimerLegacyMigrationService`；现有 `TimerLegacyMigrationRunner` 的 `ApplicationRunner`／最高 `@Order` 仅决定Runner之间次序，不能作为引擎或对外写入口启动前置保证。二次修正须先沿生产生命周期与写入口冻结真实屏障方案并独立预审；核验前任何扫描／业务写入均不得放行，失败不得启用。维护回填与只读启动核验须明确区分，不能在未确认旧writer静止时自动写账本。
   - 零修改 Liquibase changelog 历史及当前文件（保持 63 个变更集、125 张表、300 个 datetime(3)），受保护的 A39 基线文件（`SupplyChainLiquibaseIntegrationTest` 和 `BuyerAcceptanceCatalogMigrationIntegrationTest`）逐字节不变。

2. **存量数据回填与不可变绑定协议**：
   - 严格在最外层写事务中分批扫描 `sc_timer_task` 中尚未绑定账本的历史记录；
   - 字段映射：复制原源任务字段 `taskId = id`, `taskNo = task_no`, `taskType = task_type`, `idempotencyKey = idempotency_key`；
   - 载荷协议：复制源任务真实 `payload_version`（必须为合法正版本，不硬编码为1），旧任务比较协议为 `LEGACY_JSON_V1`。已有BOUND槽不得覆写；启动核验须验证其版本与源一致，并按已冻结协议注册范围验证比较协议，不把未知协议当LEGACY放行；新B协议支持范围必须沿现有设计及实际入口确认，不擅自定义新Schema。
   - 资源预算与规范化比对：严格复用 R3-01 冻结的 `LedgerJsonEncoding.canonicalBytes(payloadJson)` 进行完整资源预算校验（UTF-8 ≤ 1MB，嵌套深度 ≤ 64，token 总数 ≤ 65536，数值词法 ≤ 1000，展开 ≤ 4096，规范输出 ≤ 4MB，精确十进制）；
   - 超限与损坏阻断：若存量数据存在损坏 JSON、语法错误或超出 R3-01 资源预算，直接抛出 `SystemIntegrityException` 阻断启动并记录详细原因，绝不静默改写历史 JSON，绝不绕过安全政策；
   - 不可变迁移标识：生成基于固定域前缀及源 `taskId` 的 16 字节不可变 `reservation_nonce`；
   - 直接 BOUND 写入：直接向 `sc_timer_schedule_identity` 插入 `binding_status = 20`（`STATUS_BOUND`），设置 `task_id = id`, `task_no = task_no`, `bound_at = now`，不提交中间态占位 `RESERVED`；
   - 源字段保护不变量：回填全过程对 `sc_timer_task` 源表的任何字段（`status`, `attempt_count`, `fencing_token`, `next_fire_at`, `payload` 等）严格保持零修改、逐字/逐值不变；
   - 覆盖多状态任务：以既有DDL／状态常量为准，覆盖 `PENDING(0)`、`CLAIMED(10)`、`EXECUTING(20)`、`RETRY(30)`、`COMPLETED(40)`、`CANCELLED(50)`、`DEAD(60)`、`MANUAL(90)`；重复执行不改已BOUND记录，但不得跳过其完整性及预算核验。原文将50／60反置及遗漏90在本轮勘误。

3. **启动切换前置一致性核验守卫（Switch Guard）**：
   - 在回填完成后，立即执行全量源表与账本的双向映射校验：
     (1) 账本完整性：源表 `sc_timer_task` 中的每一行，必须在 `sc_timer_schedule_identity` 存在对应且已处于 `BOUND` 状态的账本行；若源表有行而账本缺失，抛出 `SystemIntegrityException` 阻断切换并拒绝启用；
     (2) 映射一致性：账本行记录的 `(task_type, idempotency_key, task_no, task_id)` 必须与源表严格一致，身份文本逐字比较，不能以数据库大小写／重音不敏感比较替代。反向检查每个BOUND槽必须有正确源行；孤儿BOUND、错归属及残留RESERVED均拒绝；核验载荷版本与比较协议，不仅比较JSON。
     (3) 载荷一致性：账本 `payload_json` 必须通过 `LedgerJsonEncoding.canonicalBytes` 校验且与源表等价；
     (4) 故障模式：任何映射破坏、源行丢失、载荷损坏均 fail-closed 阻断应用启动，严禁在运行时持锁路径尝试修补。

### 8.2.1 R3-04 二次修正设计候选：早期屏障与维护隔离（待独立预审）

**独立预审回执：failed／passed=false（deleg_d2321fcb）。** 父核报告JSON为34条、19 passed／5 failed／10 unknown，均NOT_RUN，输入HEAD及主设计／计划哈希匹配。现候选不得实施；研究报告与预审原文保留在仓外 `reopen-r3-03-r3-04-20261006/r3-04-pre-review/`。

- PR04-B01：普通Import原DataSource配置仍执行全模块MapperScan与factory／BusinessCode装配；“精确Import”不能选择Bean方法。维护DS／manager精确复用、属性绑定与关闭所有权须定型，不能另造池或复制生产参数。
- PR04-B02：SmartInitializingSingleton装配断言晚于补偿initMethod；数据核验成功后若ledger缺失，补偿仍可能先扫描或走fallback。必需依赖／graph检查须在factory／补偿可创建前失败，实例核验阶段必须具体证明无循环，不能只改最终启动结果。
- PR04-B03：仅禁止服务raw log不足；Spring refresh与Boot失败报告／CLI未捕获异常可能输出完整cause链。安全呈现必须覆盖实际框架日志及stderr，同时保存原始因果；不能删除cause、静默关闭全进程日志或擅改共享日志配置。具体边界与新增路径须先冻结，不将无凭据的合成测试标记当真实秘密。
- PR04-B04：逐字段blank／正值／长度／nullable映射和SQL三值逻辑、有界页限、严格游标推进、显式UTF-8与JSON在JDBC物化之前的限额策略尚未具体闭合。LIMIT行数不证明单行JSON分配有界；不得改六预算或trim改写身份。

下一候选只解决以上四组技术阻断；新增维护装配专项矩阵不得漏项，unknown未闭合不得passed。实际维护控制者与集群静止证据交接继续待决，与上述技术failed分开。当前仍无新生产改动／新运行门禁，R3-03／R3-05依赖继续暂停。

**状态：DESIGN_CANDIDATE_NOT_APPROVED，不是实施／运行／验收。** 只读研究报告和34条完整矩阵位于仓外证据根 `reopen-r3-03-r3-04-20261006/r3-04-design-research/` 的 `report.md`／`clause-matrix.json`；研究输入HEAD为 `cd552ba68feef43a5da7545710870a7819d0d477`。下述方案只冻结为预审候选，不将研究者结论当passed。

1. **三个启动窗口统一封闭**：Timer／Outbox均为SmartLifecycle；直充补偿 `RechargeFrequencyRecoveryConfiguration` 的 `initMethod="start"` 更早在单例初始化时开线程。不得只改Runner、phase或readiness。候选通过main显式导入 `IdentityLedgerStartupConfiguration`，静态BeanFactoryPostProcessor保留原dependsOn并为唯一 `supplyChainSqlSessionFactory` 加入早期 `identityLedgerStartupBarrier` 依赖；屏障为非lazy InitializingBean且依赖 `supplyChainLiquibase`，只依赖DataSource／事务manager／无Mapper的核验服务，禁止依赖Mapper／registry／clock造成环。须核所有初始化JDBC写者和真实WebServer启动顺序，不能由依赖声明推断全部写面已封闭；独立预审若发现旁路或依赖环应failed。
2. **正常启动只读，成功后开放**：屏障状态NEW→VERIFYING→VERIFIED或FAILED；无public markReady、可选provider或配置旁路。一个本供应链无外层只读RR事务内有限keyset页核验，成功提交后才VERIFIED；失败保cause中止refresh。双引擎mandatory依赖并在running CAS／建线程之前assertVerified，手工start也不得绕过。正常Runner不自动回填；生产装配须保证ledger service存在，不能因狭窄A39 fixture兼容fallback而允许生产回退旧writer。装配检查不能在屏障阶段提前取Mapper导致循环。真实Spring＋隔离MySQL＋本机loopback证明核验阻塞期间双引擎、补偿、HTTP/RPC认证nonce及业务写入均为0，失败不开放且无残留线程；依赖/guard删除结构负控制必须能失败。
3. **维护独立且不假证明集群静止**：回填置于显式装配的窄非HTTP维护命令，不组件扫描、不启用Feign／供应商、双引擎／补偿，正常启动绝不自动修映射。可信运维控制者须先完成全部旧writer／引擎／补偿／入站停止、在途排尽、冻结库与版本、维护独占、同制品切换交接；本地boolean／maintenance配置／直充业务gate不能证明这些事实。控制者身份与证据交接格式仍待用户决定；本轮仅隔离本地模拟，不执行真实维护或部署。候选实现不得因运维未定而造可伪造permit或宣称已具备生产切换资格。
4. **分页与原子性**：维护拒绝活动外层事务、同步及污染资源，自己拥有供应链最外层写事务。一个事务内按源id keyset分页，严格INSERT影响行数1，直接BOUND、固定域源id确定性nonce、复制真实正payloadVersion／完整JSON；不改源、不覆盖现有槽、不提交RESERVED，不局部retry或REQUIRES_NEW。末页SQL／预算失败整批回滚，afterCompletion和独立连接取证。不得擅改为按页commit；长维护事务的容量／锁占用如实报告。开始校验允许待回填缺槽但禁止已存在损坏槽，结束必须完整校验；无待回填也不得跳过已有槽核验。
5. **双向全量核验**：Timer正向源→账本与反向账本→真实taskId均有限分页；ci定位不代替UTF-8字节身份匹配；覆盖孤儿BOUND、RESERVED、错归属、真实版本与注册协议、完整LEGACY载荷及双方六预算，元数据错误不短路预算。Outbox正向仅对冻结B保留聚合及数据库Collation等价变体要求完整账本；旧非B源不强加回填。反向检查所有已有Outbox账本的源、四键、eventId、target、版本、协议和完整载荷，不漏孤儿／RESERVED；BCS尚未注册Schema必须失败关闭，不新增商业字段或LEGACY生产B入口。核验快照只保证本次内部一致，不证明其它实例版本或静止。
6. **完整验证与安全**：源全字段快照以metadata列集合核对，包含计划、租约、错误及创建更新时间；覆盖8状态和合法FIXED_DELAY。六预算原始非法文本纯边界测试与真实JSON列可存超限反例分别取证。诊断只记录阶段、数值id、字段类和安全原因，不打印payload／nonce／任意cause文本；异常内部cause完整保留。所有34条矩阵逐项安排独立反例，编译／fixture／同步失败不算行为RED；修正后新的完整profile、API、独立规格／质量和父验收不可省略。

**候选运行字节白名单（20路径，待预审／范围确认，不是立即全部编辑）：** 路径均相对server模块；现有 `src/main/java/com/aifuli/module/supplychain/` 下的 `SupplyChainServerApplication.java`、`infrastructure/timer/TimerLegacyMigrationService.java`、`TimerLegacyMigrationRunner.java`、`TimerEngineLifecycle.java`、`infrastructure/outbox/OutboxDeliveryEngine.java`；拟新增同timer包 `IdentityLedgerStartupConfiguration.java`、`IdentityLedgerStartupBarrier.java`、`TimerLegacyMaintenanceApplication.java`。现有测试 `src/test/java/com/aifuli/module/supplychain/infrastructure/timer/TimerLegacyMigrationIntegrationTest.java`、`infrastructure/TimerOutboxInboxIntegrationTest.java`（仅必要装配，不改原行为断言）；拟新增同timer测试包 `IdentityLedgerProductionStartupIntegrationTest.java`、`TimerLegacyMigrationAtomicityIntegrationTest.java`、`TimerLegacyMigrationSourceProtectionIntegrationTest.java`、`TimerLegacyMigrationProtocolIntegrationTest.java`、`TimerLegacyMigrationBudgetTest.java`、`IdentityLedgerSwitchIntegrityIntegrationTest.java`、`IdentityLedgerMigrationUpgradeIntegrationTest.java`、`IdentityLedgerMaintenanceContractTest.java`、`TimerLegacyMigrationLoggingTest.java`；拟新增 `src/test/java/com/aifuli/module/supplychain/architecture/IdentityLedgerStartupArchitectureTest.java`。A39／060／061／changelog、编码器、池预算、原DataSource配置方法体、业务补偿类／商业写点均零修改；若实际需要超出则停报最小diff，不扩授权。

该候选独立预审必须读完整§7.2／§7.2.1／§8／§9、计划F02与34条矩阵，重点尝试推翻初始化依赖屏障及维护隔离可行性；架构选择和真实维护证据交接未批准前不得启用。

### 8.2.2 R3-04 rev2修订检查点（设计研究，未再次预审／未实施）

两路只读报告保留在 `reopen-r3-03-r3-04-20261006/rev2-research/`：`assembly-report.md`／`assembly.json` 与 `safety-read-report.md`／`safety-read.json`。以下取代§8.2.1被否定的模糊装配／读取措辞，仅为新候选，**整体仍DESIGN_CANDIDATE_NOT_APPROVED**。B03尚有实际Druid后台建连日志分支未闭合，不进入生产编码，不发起“全候选通过”的预审。

1. **B01精确维护装配**：无parent的GenericApplicationContext，不注册ConfigurationClassPostProcessor或annotation config processors，不解析原类@Configuration／MapperScan。用RootBeanDefinition将原DataSourceConfiguration当普通factory对象，仅选原 `supplyChainDataSource` 与 `supplyChainTransactionManager` 方法，explicit factoryBeanName／factoryMethodName／RuntimeBeanReference／targetType，允许框架公开装配API调用异包包私有方法，不应用层反射私有。原属性类由Binder在beforeInitialization绑定原前缀，保留Aware→绑定→InitializingBean顺序及1500校验，不加载正常app／远端配置，不复制参数。维护允许定义仅原factory对象、原properties、唯一wrapper DS、唯一观测manager及无Mapper JDBC内核；不含factory／mapper／clock／业务号／registry／引擎／补偿／HTTP／Feign／normal guard。默认既有061维护不注册Liquibase，缺schema失败；获单独授权才可选原Liquibase方法运行既有DDL，不把其技术写纳入回填事务。context没有自动关闭BudgetRoutingDataSource能力，CLI owner在DS初始化前经公开getRawDataSource捕获原raw，事务退出→context关闭→原Druid close一次，异常不吞；禁止失败时getBean再造池，不承诺公开pool.close等于物理强关闭。
2. **B02三层顺序**：A为普通static BFPP，在所有Mapper注册结束后、任何业务singleton创建前只作元数据检查：唯一支持的ledger定义、唯一ledger mapper、原factory／DS／manager资源图、全部Mapper明确factory引用；缺失／重复／错类型／不透明未审工厂／别名偷换／未知初始化写图即失败，禁止eager getBean猜类型。B为无Mapper只读数据屏障，factory.dependsOn B，Liquibase在先，RR最外层成功提交才DATA_VERIFIED。C为实际装配准入：B→factory→唯一ledger Mapper→实际ledger实例→C；C核公开mapper/factory/manager的同wrapper身份及完整实例后才ADMITTED。唯一ledger Mapper不依赖C，所有其它Mapper及补偿init／Timer writer／双引擎／业务号Runner定义显式依赖C并保留原边，防止环。B及内核不得依赖Mapper／registry／C。支持图按当前明确constructor／property refs冻结，未知BPP／supplier／动态替换不能假称通用DFS已证明。双引擎start在enabled／CAS／建线程之前强制assertAdmitted，normal Runner只断言不回填。SmartInitializingSingleton不能承担任何首写门禁。真实30秒补偿窗口／actual ledger失败／移除依赖边／真实Servlet仍NOT_RUN。
3. **B04四遍读取**：Timer源与账本各一遍，保留B Outbox源与全部Outbox账本各一遍。内部ID页固定128且上限128，不含JSON；首游标NULL而非0以捕获非法非正id，后续严格递增真实id不加1、不OFFSET。每id单对LEFT JOIN固定LIMIT 2，2行即错误；源／账本两侧分别投影 `OCTET_LENGTH(CAST(payload_json AS CHAR CHARACTER SET utf8mb4))` 与 `CASE WHEN 同表达式 <= 1048576 THEN CONVERT(同表达式 USING binary) ELSE NULL END`，不额外投影raw JSON。同SELECT同RR快照避免测长后无界重读。超限在客户端payload accessor／解码之前拒绝；显式binary UTF-8严解，长度等reported octets才调用原canonicalBytes；元数据差异不短路双方预算。默认驱动可能buffer两行，有限pair结果不是服务器串化／整堆硬上界；每row释放decoded／canonical，不持全页JSON。详细固定SQL及参数见safety-read报告§4；实施前将精确SQL纳入受审设计/代码快照，不自行增列或改变投影。
4. **字段与NULL准入**：joined id的NULL是缺行，不COALESCE／primitive读取成0；getObject包装类型保留NULL，若primitive必须紧邻wasNull。内部id／BOUND taskId正Long，payloadVersion正Int，两边一致；aggregateVersion非负。身份字段非NULL非blank且码点长度不超过DDL（Timer type64/key128/no32，Outbox聚合type64/id64/event128/eventId32，targetType32/name128，protocol32），不trim／Unicode归一／强加新业务号前缀。targetType准确LOCAL/HTTP；协议按实际注册范围，B的LEGACY／未注册BCS拒。nonce非NULL16字节，不新加全零禁止／全局唯一；BOUND boundAt与createdAt非NULL，不加未经冻结的时间大小关系。可选租约／错误字段NULL保持，合法JSON值null与SQL NULL区分；所有20个源列／八状态／合法FIXED_DELAY保留。合法性不能仅依赖两边相等或WHERE !=三值过滤，双方预算先于身份／版本／协议比较。首错异常不伪称之后未执行侧已完成预算核验。
5. **B03尚未闭合的安全呈现候选**：顶层toString／printStackTrace不阻止Logback ThrowableProxy展开cause；候选R3专属安全叶标记、refresh前安装限定TurboFilter、优先Boot ExceptionReporter及明确CLI非零终结，原cause内部保留。不能只FailureAnalyzer、只删服务log.error或全局关日志。该路线需新增 `src/main/resources/META-INF/spring.factories`，超原20路径且尚未授权／未实施；原共享日志配置不改。同步作用域不能覆盖Druid后台创建连接在调用方异常前的URL／cause日志，不能假设池已热，也不能静默把此分支排除出完整启动故障门禁。须先查清实际backend／最早安装／异步归属与最小安全边界；可行性或必要范围未定即保持blocked。

**有限实证：** 父重新核probe源码／依赖哈希并离线执行javac及java均0（2026-10-06T19:27:34.563944+08:00）；仅证公共API微型样例的selected package-private factory、无配置解析、Binder时序及非eager类型发现，DB／network calls均0。不是原DS／Druid／MyBatis／双屏障／补偿／HTTP实测，更不作为任何实施GREEN。报告源码原字节不覆盖；本轮A39、历史DDL及生产运行字节不改，真实维护控制者及静止交接仍待决。

### 8.2.3 B03异步日志范围决策（历史暂停；2026-10-08仅恢复只读设计）

**当前覆盖说明：** 本节原暂停及A/B选择是历史裁决，完整约束继续保留；新的只读授权不批准其中任何风险取舍或运行改动。直接核对Druid/Boot/Logback源码后的剩余问题与对象级来源绑定研究方向见 `2026-10-08-r3-04-readonly-deblocking-analysis.md` §3～§4，新增范围仍须另获确认，不将研究候选当作完整解阻。

**用户裁定：保持全部现约束，不接受A／B取舍；R3-04暂停实施。** 不豁免原池异步连接诊断风险，不授权影响其它池／非R3同调用点的class-logger呈现改变；当前无满足完整约束的已证方案，保持 `PAUSED_BY_USER_BOUNDARY_UNRESOLVED`。不继续后台研究、试改、实施或门禁，除非用户另行指示。R3-03仍重开待修，依赖后片不放行；本次暂停不是验收通过。

用户单独允许精确新增server资源 `src/main/resources/META-INF/spring.factories`，**仅注册R3专属Boot启动异常报告器**，不改共享日志XML、池参数或其它模块。此为路径范围授权，不代表B03技术阻断已解决或恢复实施；当前不创建该资源，未来恢复时仍须独立设计预审。

**B03异步日志边界待裁决：** 仓外 `rev2-research/b03-druid-boundary-report.md`／JSON查明Druid1.2.28建连错误采用类级private static logger，事件不含池对象／R3归属，未发现能完整区分原供应链池与本R3调用的公开API。仅同步作用域不能覆盖后台创建／重连；Liquibase可早于R3核验触发建连，不能假定热池。离线probe的特定classpath选择SLF4JImpl→Logback，缺provider／显式JUL负控制选JUL；这不是生产有效backend或真实连接故障验证。保持B03 blocked，不扩大现授权。

> **2026-10-09 状态更新**：因底层连接池由 Druid 迁移至 HikariCP（见
> `docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md`），Druid 1.2.28 类级私有静态
> Logger 的硬阻断已随组件移除而消除；B03 状态调整为 `PENDING_VERIFICATION`，待按该设计 §6.5
> 在 Hikari 环境下完成实机验证后再申请正式解阻。本节原文保留为冻结历史。

待用户选择A或B：A明确将原池自主init／create／reconnect及Liquibase连接诊断列为独立安全风险，R3安全呈现承诺限定同步核验／维护数据、语句／事务与框架／CLI，风险仍记录且不代表部署批准；B若要求该连接日志也安全，需明确授权指定Druid类logger调用点的诊断呈现变化，承认影响其它池及非R3同调用点，不称池级隔离、不能全logger静默或自动扩至Abstract/JdbcUtils。严格同时要求仅本池／仅R3／其它池原日志完全不变时，目前无已证可实施路线。两项均须修订设计并独立预审，不能把选项当已实现安全机制；候选Boot reporter的 `src/main/resources/META-INF/spring.factories` 新资源亦需精确范围确认。

### 8.3 巡检ONCE任务串联（既有协议，不属本轮新增实施）

- ApplicationReady的明确应用入口在一个短供应链事务内取库时钟，幂等确保**当前bucket和下一合法未来bucket**，两份任务的身份/载荷都固定；不能只返回当前handle就宣告已播种未来链。多实例同轮由Timer账本唯一键收敛，两次调度任一步SQL失败全回滚。
- 当前桶返回旧COMPLETED/DEAD/CANCELLED时不改旧行，但仍确保不同key的未来桶。未来桶已存在且为合法非终态则不重建；若已是终态，不把该handle视为未来执行已建立，按固定桶序向后有限探测/确保不同未来key，达到配置的正lookahead上限则稳定报播种阻塞，不能无限循环或重开旧槽。只读状态检查不锁源、不在持领域锁路径做播种；B0隔离夹具验证当前DEAD且无后继、未来已存在、终态未来/上限和并发实例。
- 启动播种先有限非锁探测当前/未来候选，确定完整身份集合，再按§7.2.1统一排序追加并同事务完成，不能先持当前桶账本锁再逐桶发现更早排序槽。未来可接受状态明确为PENDING/RETRY，或观测时带合法未过期lease的CLAIMED/EXECUTING；MANUAL、终态及活动态缺合法lease不算已建自动未来链。状态判断只代表此次观测时点，不保证以后不被合法并发取消；异常状态失败关闭或按既定有限后继探测处理，不复活旧行，也不扩写为永久执行保证。
- 单轮按固定上界分页，原子生成叶子、续页或下一轮；即使空轮也生成下一轮。
- 迟到旧轮次按库时钟跳至当前/下一合法bucket，不无限补跑缺失历史轮；新轮身份不同于旧任务。
- REEVALUATE同listing/basis/boundary重复返回旧任务；下一次评估生成新boundary/key，不借相同已完成key伪称已安排未来。
- B0不宣称交付FIXED_DELAY重复调度器。只修基础设施也不意味着目录sweep已存在；B1才注册真实启动/巡检应用服务。

## 9. B0实施切片与接受门禁

先独立预审本文，通过后在原仓按垂直RED→GREEN实施；不得新worktree。V4A仅准备切片3纯本地账本基础完整worker与隔离技术领域fixture，重跑协议尚未获独立预审或实施批准；不接A商业生产者/定价RPC、实际消费者、旧Handler或引擎，不以历史批准覆盖修订。用户接受维护窗口同版本切换但本次仅本地验证，不迁移部署。以下新增门禁是待实施验收要求，不是本轮运行结果：

- 原无retry三事务反例原日志/XML/测试字节必须保留；新完整worker测试在空表/末尾键、无哨兵下用真实data_lock_waits确认两个等待者，分别释放创建者COMMIT/ROLLBACK。失败领域/源/账本无残留、afterCompletion ROLLED_BACK且无活动事务/同步/连接资源后，事务外重跑完整原worker，固定身份/新nonce、真实源与映射、最终提交返回、attempt≤2及不增加持久attempt/token逐项取证。
- 第二attempt再次真实deadlock必须造出真实1213/40001并保留原SQL因果/锁证据，证明绝无第三次；仅合成异常或代码分支检查不算该门禁。完整cause/next链、非本地阶段1213、1205/40001、1062、3819拒重跑；UNKNOWN、提交异常、rollback失败/不确认回滚、COMMITTED拒重跑须单独故障取证，不拿socket探针替代提交不确定状态。
- 真实代理边界/正确manager与活动外层、错manager、read-only、rollbackOnly、嵌套重入/线程复用清理；退避处事务外且资源已释放。R01/R02技术协议以§7.2.1为规范，不再留作剩余预算/池路径TODO；仅全局connect1500影响获准，不改其它共享池属性、不另建池；真实运行验证仍未完成。
- R01新增生产待实现门禁：首次/第二次R=3500ms边界及上下界；999/1000/1999ms的floor拒绝/1秒边界；真实借后超期、退避前R-b与退避后重检、取消/interrupt保持、清理耗时计入不reset；实际Statement执行前Spring/MyBatis不能ceil或0覆盖、SESSION锁/socket保存恢复、prepared缓存无污染；真实超期flush后beforeCommit/commit guard全回滚而rollback不被预算阻止。
- R02新增生产待实现门禁：真实Spring代理+Druid唯一池、manager/SqlSessionFactory/clock同wrapper key同连接及非预算透明委派；实际池饱和有限排队、interrupt与连接恢复，受审前提不符拒绝且共享属性不改；build/init顺序与int默认/仅1500配置校验；实际Driver.connect/HostInfo属性优先级和冲突拒绝；后续只在隔离本地环境分离单地址TCP与DNS/多地址/TLS/认证/初始化阶段取证，网络实验本轮未跑、仍待验。真实恢复失败须按R02.6提前捕获holder/physical/socket、直接Socket.close可观测异常与独占disable/discard，不以计数/flag证明关闭，清理失败否决retry；UNKNOWN/commit/rollback失败、第二attempt真实死锁仍须独立故障门禁。HTTP、Liquibase、clock、六旧Handler与冷启动全局connect1500兼容回归，不把它们接retry、不改原无retry RED。
- **最新三阻断必需负例（未运行、不得以TODO代替协议）：** R02-ALLOWLIST以获准testWhileIdle=true及默认checker/validation旧路径为允许正例，再逐项置换retryCount/createScheduler/filter/testOnBorrow/testWhileIdle/testOnReturn/checker/validation/defaultAutoCommit/removeAbandoned/驱动socket形式至允许集合外，预算入口在raw借用前拒绝、原共享属性字节/有效值不变、非预算仍可透明使用；默认testWhileIdle=true不得仅因验证循环被拒绝；验证失败discard→continue重新等待是获准非硬截止例外，累计borrow无有限上界，可超过15s或不能及时返回；raw返回后的cancel/deadline首检及late连接严格清理须证明不暴露manager/不启动worker，禁止Future借线程。原pool内部discard无强关闭证明例外不扩成新预算污染清理宽松。R01-PREPARATION在raw返回后首个get/setAutoCommit、隔离级别/metadata及serverPrepare返回前分别阻塞/超期，证明先安装socket、所有委派前后guard、prepare结果不泄漏；SESSION读取/SET半失败及control递归负例必须失败关闭、原值恢复或隔离且不retry；Statement后续ceil/0/正更大setter、批flush/缓存复用均不能放宽最小正限制，缓存归还恢复失败隔离。R02-DISCARD-FAILURE分别令实际Socket.close抛IOException、abort吞内部close失败、isClosed已true但socket未闭、discard boolean/计数正常而实际close失败、disable清cache抛出/holder已清、重复cleanup/recycle，必须保留原cause及清理异常、不retry、不重复扣active、不将flag/count当关闭成功；标准socket直接close正常返回须同时记录捕获身份和本地传输关闭证据，未知/未审TLS封装或不可确认隔离时预算实现不得启用，非预算行为不变、不禁TLS；默认PREFERRED及受控supported transport适用前提须分别核验，TLS覆盖未证不得冒称支持。所有故障门禁以后只在获准隔离环境运行，本轮无测试。
- 保持持旧源锁重复不等待源、CLIENT_FOUND_ROWS两设置、旧RR、真实collation多槽排序/合并、映射/完整载荷/DDL显式NULL负例、绑定0行、第二槽失败整批回滚、RESERVED/flush提交guard与新旧Timer维护回填/同版本切换门禁。不为本片扩大A商业测试或修改历史DDL；技术夹具不证明真实生产者已接线。原嵌套saveBuyer/商业级联要求留作后续接线验收，本次仅用技术领域fixture证明最外层完整worker的同构收集/flush边界。

纯通用夹具先证明协议，不注册华昇、不发送真实外部请求。下列测试类名为拟新增/扩展类别，落地前查现有同类测试，不冒称已有类。

| 切片 | RED行为与GREEN必须证明 |
| --- | --- |
| 领取恢复 | 实际Mapper/MySQL下领取后宕机、执行后宕机、两节点竞争；到期10/20被接管，新token/attempt递增，未到期不能抢 |
| 预算/死信 | 最后一次领取后宕机无fail仍转DEAD；attempt不越界；Outbox非空available_at，Timer非空next_fire_at；终态不再扫 |
| 启动/续租 | 过期但未接管、已接管、owner错误、token错误的启动0行不调Handler，续租不能复活 |
| 转换/审计 | 失格complete/fail不改新owner、不写SUCCESS execution；合法状态及审计一同提交，SQL失败一同回滚；registry/JSON/启动准备失败按持久预算RETRY/DEAD但无虚假Handler execution，不重新调用缺失Handler |
| Outbox本地栅栏 | 真实代理事务/latch：失格旧worker无法写入；事务中租约过期在提交前全回滚；合法短事务业务/Inbox/子事件/Timer/源完成一起提交 |
| Timer本地栅栏 | Handler结束前、完成CAS前、execution写前故障均全回滚；重投不重复领域效果、不再finally双complete |
| Inbox | 同ID同摘要并发/成功重放；同ID异摘要失败；30→10真实version；90不重试；业务异常不留SUCCESS/孤儿；排他建槽首次提交/回滚加两个等待者、旧byte[]入口兼容，重复upsert不改原字段 |
| 追加/调度唯一键 | 两身份账本与源四列/两列真实unique；确定性三事务latch/真实锁等待证据：首次创建者分别提交/回滚、两个同槽等待者；已有槽并发/旧RR，同槽返回原身份，异target/payload冲突；CLIENT_FOUND_ROWS两种设置下都不靠upsert行数辨认首次，BOUND无任何字段变化；不吞源unique/其它SQL错误，rollbackOnly失败关闭；维护回填/新旧writer切换/映射损坏负例 |
| 页/轮次 | 同根页重放、固定上界、空页、下一页/叶子失败、新bucket/new boundary；当前DEAD且无后继→同桶重启原子确保未来不同key、未来已存在/终态/有限上限、并发启动；不重开旧key |
| 间接锁环 | 持源Outbox或Timer并等待领域锁时，重复生产者仅核独立账本而不等待源；排他首次竞争/正确槽所有权/实际索引多槽顺序，后续槽/源插入/绑定失败全回滚且无RESERVED残留；嵌套saveBuyer/初始化/项目级联只在真实最外层事务末尾flush、最终版本不漏、flush后不取新领域锁；不能仅靠mock锁序或死锁重试 |
| 线程池/心跳 | 真实Druid容量/有限等待绑定生效、默认及HTTP/Liquibase/旧Handler夹具相容；饱和自身业务池不耗尽心跳连接；嵌套Reader/Timer观测容量、有限连接/事务/锁/停机等待；旧token清理不删新token，忙/失败/失格区分、启停恢复 |
| 兼容/失败关闭 | 原四Outbox/两Timer Handler路径与预算回归；未实现HTTP不publish；模式注册失败关闭，不触发真实供应商 |

纯Test覆盖状态机、确定性身份/摘要、容量/模式与结果映射；IntegrationTest通过真实MySQL、Liquibase、Mapper、代理事务、RR和latch证明上述原子性；ContractTest覆盖现有Handler/上下文兼容与封闭payload；ArchitectureTest保留Facade无DAL、routing纯域、管理权限及数据库所有权。

测试与门禁按关联桥接主设计§13及`docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁`分类。完整供应链纯测/契约/架构之外，真实MySQL回归至少包括TimerOutboxInboxIntegrationTest、RechargeFrequencyTimerIntegrationTest、RechargeFrequencyExecutionIntegrationTest、所有新增B0 IntegrationTest以及受数据源等待/Handler兼容影响的现有HTTP/受理与Liquibase路径；不照搬A九类作为B0完整范围。直接调Handler的旧测试不能代替新引擎/代理/续租/启停测试。每轮保存实际命令、原XML及新增文件在内的受测哈希；编译/fixture/同步失败不称行为RED。B0实现规格通过后另做独立质量审查，父最终核范围才验收。本文矩阵不是运行证据。

## 10. B0之后仍待修订/实施的B范围

用户已经确认主设计§8.1/§8.2：保留实际首选UNKNOWN、不跳后备；REQUIRED全缺失管理UNKNOWN但不放行。这两项不再作为业务待答，不改变ERROR处理及旧RPC。

仍须后续B规格修订：全部生产者实际挂点和旧target真实payload、通用定价跨时间边界后的来源新鲜性、系统发布操作人及引擎启用条件、非秘密执行配置/能力完整性、新RR第一实体读取与项目枚举/汇总、自动publisher/商业来源/投影、管理HTTP/前端回显。时间补偿不能在持LST锁时反向重算定价链。

B0不启用空目录Handler/生产者，不实现首冻前Coordinator、HTTP最终资格/冻结回放、华昇执行器/可信回调/资产或真实一单。旧RPC移除联动D保持待用户独立选择，MANUAL商业价/生命周期/共享投影不借技术修复被接管。
