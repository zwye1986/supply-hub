# 单步直充商户共享限额与持久等待设计

> 日期：2026-10-18；基线：`Dev-1031 / 8f795db7`。
> 状态：用户已选择方案A（MySQL统一配置/准入 + PersistentTimer持久等待）；用户已批准本文全部细化合同，按[实施计划](../plans/2026-10-18-single-step-merchant-quota-plan.md)推进本地TDD；进度与实测单独记录，不代表功能已通过。
> 上游：[三家详细设计§2.2、§4.4](2026-10-15-renyue-qnm-szcard-detailed-design.md)、[开发计划T03及§18](../plans/2026-10-15-renyue-qnm-szcard-development-plan.md)。
> 本文不授权生产限额、共享迁移、供应商调用、推送或部署；当前生产新路径继续关闭。

## 1. 目标与已确认不变量

覆盖C-04第20～22项：同一供应商平台同一真实商户，跨Connector、版本和实例共享CREATE/QUERY出站速率及并发；不同平台/商户隔离。配置使用当前统一值，不从原单/Connector版本冻结或叠加。

- 降额不取消已获发送许可的调用，不判业务失败、不动账；剩余在途数低于新上限才准入。
- 等待不生成供应商超时、NOT_FOUND或终态；不重下、不切供、不延长截止。
- 原单QUERY认领前等待不消耗1000次预算；已提交认领不退款预算。
- 可信成功保护、回调、纯本地结果补交不获取出站额度。
- 原单协议地址、商户、凭据、商品、目标、恢复节奏及截止仍使用原冻结材料。
- 不改华昇或当前旧QNM路径，不接运营通知，不将现有表单100次/秒、并发20当成生产许可。

## 2. 现有代码与本次切入点

以下J为`aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/`。

- `J/border/fulfillment/application/singlestep/SingleStepFulfillmentRunner.java`：事务外准备原Ticket，调用认领，执行HTTP，再保存结果。
- `SingleStepFulfillmentLifecycle.claim`：独立短事务，CREATE含手机号频控发送边界，随后写generation、lease、marker、QUERY预算和watchdog。
- `RechargeFrequencyLifecycle.claimSingleStepSend`：MANDATORY调用，必须与原单认领整体回滚。
- `DatabasePersistentTimer.scheduleOnce`：同taskType/idempotencyKey永久幂等；完成的同键任务不会重新调度，容量等待必须有独立递增序次。
- `SingleStepFulfillmentLifecycle.preparationFailed`：5分钟准备看门狗已豁免正常FIFO等待，但未识别容量等待，需要新增精确豁免。
- `SupplierConnectorVersionDO.rateLimit/concurrencyLimit`只是版本字段；不能承担当前统一商户配置职责。
- 实际前端`/Users/mac/codespace/supplychainhubweb`的ConnectorFormDialog按版本提交限额，不能直接作为新共享额度管理入口。

## 3. 分阶段范围

本次第一阶段交付后端共享配置应用服务、MySQL准入、单步Runner接线、持久等待及真实数据库测试。配置创建/调额由应用服务的显式命令驱动，隔离测试通过该入口配置，不直接写表模拟配置成功。

不在第一阶段增加管理端HTTP、菜单、页面或生产配置播种；这些作为后续受控管理切片，必须在真实供给发布前完成。服务没有自动读取ENV/Nacos或每个实例启动时覆盖数据库的初始化逻辑。现有Connector限额字段保持旧协议语义，新共享路径不回填/修改历史版本。

## 4. 商户身份与统一配置

### 4.1 额度域

以代码封闭供应商平台码`RENYUE / QNM / SZ_CARD`和冻结商户账号原值定位，不使用Connector ID、版本ID、凭据槽/集ID、供应商主键或baseUrl划分额度。

账号采用UTF-8原值精确匹配，不trim/casefold；数据库采用二进制比较，避免默认大小写不敏感排序规则误合并。Adapter类型到平台码由代码声明，不接受回调或HTTP自由传入。相同平台有多个账号别名指向同一实际商户时，必须先取得别名合同再扩展显式映射，不凭字符串猜测；此情形未获证据时保持发布阻断。

### 4.2 配置命令

由`tradecore.supply.application.MerchantQuotaService`拥有配置、准入和释放；border仅调用应用服务，不反向读取其内部租约表。

配置命令字段：平台码、非秘密商户账号、每秒成功准入上限、并发占用上限、expectedVersion、操作者、变更原因。两个上限均必填正整数，不允许null表示无限额，不写默认商户行。创建冲突和调额版本冲突明确拒绝，不静默覆盖。

配置行更新与准入锁同一额度行；调整不清空已存在的速率记录或占用，也不修改任何原单检查点。调额从命令提交后取得额度锁的新准入生效。

缺少配置属于不可执行配置，而不是临时额度耗尽：不得发送或生成默认额度，原单转明确材料/配置Case并保留预留；不通过无限容量等待掩盖未配置。

## 5. 数据结构与归属

拟追加当前068之后的独立Liquibase迁移；实施前再次检查实际末尾，不修改历史changeSet。所有表为`sc_*`，无物理外键，时间均DATETIME(3)、Asia/Shanghai，字段COMMENT与DTO一致。

1. `sc_supplier_merchant_quota`（tradecore.supply所有）：id、platform_code、merchant_account、rate_limit_per_second、concurrency_limit、version、created_by/at、updated_by/at、change_reason。平台+精确账号唯一，配置/准入的串行化根。
2. `sc_supplier_merchant_call_lease`（tradecore.supply所有）：id、quota_id、operation_no、execution_generation、admitted_at、lease_until、released_at。原操作+代次唯一；记录准入事实并承担并发占用，不存凭据、手机号或原正文。`quota_id/admitted_at`、`quota_id/lease_until`用于速率与占用查询。
3. `sc_single_step_capacity_wait`（border.fulfillment所有）：原operation_id唯一、action、expected_generation、wake_sequence、next_wake_at、active_flag、version、created_at/updated_at。一次原操作只有一行当前等待状态；独立序次避免复用已完成Timer键，不扩展加密检查点Schema。

配置变更审计沿应用命令记录操作者/原因/版本；第一阶段不宣称具备完整管理操作历史查询。若要求完整逐版本审计，须扩展此设计和配置管理切片再放行对应生产管理。

## 6. 准入算法与事务

### 6.1 速率和并发定义

采用数据库时间的滚动1秒成功准入窗口，不使用固定自然秒窗口，避免跨秒双倍突发；失败竞争、回滚或纯容量检查不计速率。

额度行`FOR UPDATE`后，在`(now-1秒, now]`内的已提交准入记录数小于当前rateLimit、且`released_at IS NULL AND lease_until > now`的占用数小于当前并发上限时才可插入新租约。

HTTP已获准但尚未物理发送也保守占用额度。准入记录不得因HTTP结束提前删除，否则会退还本秒速率；released_at只释放并发。超过速率窗口且已释放/过期的记录才可清理；清理在额度锁下按主键升序每次最多256条，不在请求中无界扫描全表；该值只是技术批量上限，不是生产业务额度。

数据库查询失败不退回本机计数器或无限额。时钟只用SupplyChainDatabaseClock，不由实例墙钟重建窗口。

### 6.2 与原单认领原子提交

原单认领事务沿现有频控聚合锁序，核原单到期/预算/安全资格后，再锁商户额度行并插租约；之后原单generation、lease、marker、QUERY预算及watchdog与该租约一起提交。

CREATE路径目前先调用claimSingleStepSend。若额度不足，必须抛出专用容量拒绝信号，使整个claim事务回滚；不能直接return null并提交SEND_POSSIBLE或手机号holder变更。事务外编排捕获此封闭容量信号后，另调用等待事务重新锁原单、核代次/终态/marker和等待动作，再可靠保存等待。只有等待事务成功后原触发任务才能正常完成；保存等待/Timer失败必须向原Timer或Outbox入口传播失败，让原可靠任务重投。两个事务间宕机不能假称已接管等待，沿原入口的失败/死信及准备watchdog留痕，不依赖内存重试。普通数据库异常不转换为容量等待。

QUERY成功认领后没有“先等额度再拿新租约”的第二次放行；额度不足在认领事务回滚，因此未新增QUERY预算。失败回滚不能残留租约、marker、watchdog或容量计数。

额度租约截止与该次冻结执行lease一致；不单独赋予更长HTTP时间。发送前继续核原单当前代和剩余截止，关闭HTTP重试/跳转。

### 6.3 锁序与释放

锁序为原频控聚合锁序 → 商户额度行 → 该额度的租约；配置命令只锁额度行，调用结束释放也只锁额度行/租约，不反向锁原单，避免额度锁与原单锁互等。配置/准入层不得回调border服务。

Runner在HTTP调用结束或发送跳过后的finally按原操作+代次幂等释放并发租约，且在结果事务之前执行；不将资金结果回滚视为HTTP仍在途。释放失败不能吞掉已拿到的可信结果：保留原结果推进/留证，资源回收失败交由独立安全诊断及租约过期兜底，不能因此重发CREATE。异常责任遵守全系统日志规范，实施日志前复读规范。

进程宕机无finally时租约到期回收本地额度；这不代表远端已停止执行。HTTP工厂30秒总预算/60秒执行lease仍是平台保护，不承诺进程暂停或远端异步业务物理并发绝对不超限。配置限制约束的是中台获准的同步调用占用，不是供应商内部异步充值任务数量。

### 6.4 释放故障注入与诊断收敛（2026-10-19补充）

本切片不改变§6.3的HTTP/资金隔离与租约兜底合同，不增加释放重试任务或重新发送资格。

- 用真实MySQL释放UPDATE执行后的故障注入确认独立释放事务回滚；已获得SUCCESS仍独立保护并一次COMMIT。叠加资金COMMIT失败时，成功保护必须保留，解除故障后由watchdog纯本地补交，CREATE/QUERY均不得再发。网络未知仍遵循原恢复合同，不因回收失败分类为本地执行失败。
- 租约未释放时继续保守占用；数据库时间到期后其它原单可准入。测试只在隔离库推进租约时间，不声称等待过真实60秒或远端物理在途已结束。
- Runner拥有独立的释放诊断器，每个实例仅一个固定故障桶（事件merchantQuotaRelease、目标supplyChainDataSource、错误码RELEASE_FAILED），不按订单/商户/异常文本建桶。单调时钟60秒窗口为本路径技术诊断周期，不是业务额度；首个故障ERROR附既有安全异常因果，每个后续窗口第一次故障WARN汇总，其余仅计数。无后台线程，汇总在下一次释放故障到来时输出，不承诺空闲期间定时输出。
- 窗口摘要携windowMs/totalCount/suppressedCount；恢复事件INFO携本轮累计故障/抑制数。首次详细异常只输出一次，持续窗口不重复堆栈；新一轮故障重新保留首次诊断。多线程并发更新同一有界状态，单个订单号仅作当次定位，不保存在桶键中。
- 只有已提交的真实租约释放成功才关闭故障周期；释放入口返回boolean说明是否确认已有租约释放，缺失/已清理租约的幂等空操作不构成恢复证据。恢复仅代表后续一次真实释放成功，不宣称旧失败租约已经补回收；旧租约仍靠自身到期兜底。此返回值不授权重发，也不参与资金判定。
- 保留当前日志兼容格式，不修改共享encoder、追踪、线程池或八进程配置。计数字段在message中是可读摘要，**不是ES顶层可聚合字段**，不得宣称已完成日志规范§4目标结构化契约；本切片验证安全渲染、级别、计数/窗口/恢复、并发有界和实际兼容JSON，实机ELK/八进程联调另记NOT_RUN。

## 7. 持久等待与唤醒

### 7.1 时间与幂等

速率不足的retryAt取最早窗口内准入时间+1秒；并发不足取最早有效租约截止；同时不足取二者较晚时点。短暂等待和数据库时间精度采用`max(retryAt, now+1毫秒)`，避免同毫秒忙循环。只使用已有记录推导，不另设未经确认的固定重试间隔。

释放并发额度不主动扫描唤醒全部订单；第一阶段保守等到已持久到期点，不承诺释放即刻唤醒或严格公平/FIFO。唤醒时重新检查当前统一额度和全部原单资格，不因曾等待而优先于新单或绕过截止。若需要及时抢占/严格公平，应另行细化商户队列协议。

### 7.2 等待记录与Timer

Runner及认领事务必须检查仍有效、同代同动作的容量等待；未到next_wake_at时所有触发都跳过，不允许采购方反复查询、旧Timer或另一实例绕过已持久等待发起新的额度竞争。等待到期只恢复竞争资格，不直接授予发送许可。

等待事务先重核原单action与expectedGeneration：已有新代、终态、成功保护或CREATE marker则不写过期等待；QUERY已到截止按原Case合同处理。

首次等待或一次实际到期后再次容量不足时递增wake_sequence，更新next_wake_at，并在同事务scheduleOnce新增`SINGLE_STEP_CAPACITY_WAKE`。幂等键包含operationNo、expectedGeneration、action、wakeSequence；payload只携非秘密定位和来源编号。尚未到期的重复请求不增序、不造新Timer。

QUERY的fireAt不得晚于原recoveryDeadlineAt；到截止仅转Case保留预留，不能因容量再顺延。CREATE尚未首次认领，没有创建日期/绝对截止，不伪造日历窗口。

Handler核持久等待行的active、sequence、action和generation后调用同一Runner；任务过时只跳过。等待结束置inactive，不能删除序次再从1开始。认领成功关闭对应等待，原单结果成功/人工后旧等待任务可跳过，不依赖取消成功。

准备watchdog对真实active容量等待返回CAPACITY_WAITING，不将正常容量排队误判为准备失败；坏材料/缺配置不享受该豁免。等待不清零NOT_FOUND、不改事实时间/恢复合同，不把capacity nextWakeAt写成供应商未知的新nextRecoverAt。

## 8. 测试与验收

- 纯逻辑：滚动窗口边界、并发/速率同时不足、retryAt推导、账号精确比较、非法配置及CAS冲突。
- 真实MySQL/Liquibase：三表约束、唯一键、当前读、调额与准入并发；两个独立Spring容器/连接池共享同一隔离库竞争额度，避免用同一JVM局部锁冒充跨实例。
- 公开受理/Runner：不同Connector/版本/供应商记录指向同平台同账号共享；不同商户/平台隔离；同单双工作者不重复发送。
- 原子故障：额度不足及认领写入失败整笔回滚，CREATE频控发送标志/marker不提交，QUERY预算不耗，租约/watchdog无孤立行。
- 宕机：认领后HTTP前关闭重开容器，marker仍不可再CREATE；额度过期后只能按原单QUERY合同恢复，原预留不动。
- 等待：反复唤醒不会复用已完成Timer键；同到期并发只推进一个序次；回滚不残留Timer；准备5分钟看门狗豁免；等待期间成功回调不被旧任务复活。
- 调额：并发5降2且4占用时无新准入，降到小于2才可进入；升额不重置窗口记录或原单预算/截止；配置缺失失败关闭。
- 资金：容量路径COMMIT/RELEASE均0；QUERY截止Case保留预留；可信成功仍一次COMMIT，迟到回调不受出站额度阻断。
- 回归：供应链全部单测/架构、原冻结/受理/恢复/回调/频控、仁跃/QNM/华昇现有HTTP合同；新增集成通过supply-chain-integration profile执行。迁移改变数量基线时只更新精确对应断言，不降低时间/无外键门禁。

实际命令、RED/GREEN、失败排障与报告写入`docs/集成测试.md`；2026-10-19本批后端机制与隔离用例已通过，第20～22项由NOT_RUN推进为PARTIAL，未覆盖的实际多实例物理HTTP/动态调额完整组合及容量证据不写PASS。本机通过不解除生产额度、供应商独立向量、回调重投或真实准入门禁。

## 10. 本批实现与验证状态（2026-10-19）

- 已实现069迁移：三表、71个changeSet、132张业务表、319列DATETIME(3)，精确账号采用utf8mb4_0900_bin，平台/操作码二进制比较，表级既有排序规则和无物理外键门禁保持。
- MerchantQuotaService拥有配置CAS/准入/释放，配置无默认值；纯策略覆盖retryAt，认领原子保存租约，普通HTTP/异常/发送跳过均在结果事务前释放。释放故障仅安全诊断并靠过期兜底，不借故障重发CREATE。
- 等待行和独立序次Timer同事务，CREATE拒绝整体回滚频控/marker；QUERY等待保留预算、原nextRecoverAt和截止。终态/人工关闭active等待，旧payload不复活。
- 两个独立Spring容器/连接池共享同MySQL竞争最多1份额度；跨Connector/版本及已退役原单共享同商户行，版本上9999不增加额度。此为数据库准入/Runner测试，不冒充两个真实进程的物理HTTP压测。
- 最终组合verify退出0，单测/架构2500项、16类集成/契约223项，失败/错误/跳过均0；日志`.amp/in/artifacts/merchant-quota-combined.log`，逐类XML及汇总`merchant-quota-reports/summary.json`。
- 未新增管理HTTP/前端/生产额度，未部署或操作共享库；三家生产新路径仍关闭。完整管理审计、供应商额度、物理HTTP容量/公平性及第20～22项所有组合未验收，后续独立推进。

## 9. 审阅要点

方案A及以下细化合同均已确认：

1. 第一阶段只做后端应用配置入口，管理HTTP/前端单独交付，真实发布前补齐。
2. 商户账号原值精确匹配，平台码不随Connector/供应商记录变化；别名不猜测。
3. 滚动1秒准入计数，无生产默认额度，缺配置转Case保留预留。
4. 额度不足使整次认领回滚，再独立保存有围栏等待；不提前消耗QUERY预算。
5. 并发等待保守等到租约截止，不承诺释放即醒、严格公平或供应商内部异步任务并发。

用户在完整细化稿展示后明确“同意”，以上细节均已批准用于本地实施；生产操作边界不变。

## 11. 释放故障续作验证状态（2026-10-19）

§6.4已完成本地实现与故障验证：释放UPDATE已执行后注入异常，事务回滚仍保留可信成功；叠加资金COMMIT故障后重开Spring容器/连接池，在原租约到期点由watchdog纯本地补交，不再CREATE/QUERY。网络未知保持预算/截止与原恢复合同，释放失败的占用按数据库租约过期兜底。

固定单桶诊断器已接Runner，首次安全因果ERROR、持续60秒窗口WARN汇总、后续真实释放提交后INFO恢复；空租约不假报恢复，旧失败租约不因恢复事件被视作已释放。5项纯测覆盖两万不同订单、8线程执行1000个竞争任务、窗口精确边界/跨窗口累计与兼容JSON。另4项真实MySQL故障用例纳入容量集成15项；临时恢复旧逐次ERROR行为取得1项有效RED（3条而非1条），随后恢复新实现通过。

最终verify于02:32退出0：供应链全单测/架构2505项、11类集成/契约213项，另根配置/TraceFilter30项均无失败/错误/跳过；命令、夹具排障和当次XML见docs/集成测试.md末尾及`.amp/in/artifacts/merchant-quota-release-reports/summary.json`。没有修改069迁移、共享encoder/MDC/根配置或生产开关；本批未提交、未推送、未部署、无共享迁移/真实供应商调用。C-04第20～22及24项仍PARTIAL，完整MySQL、多实例物理HTTP在途/动态调额/容量、管理端/完整配置历史、供应商许可及实机ELK仍未验收；两项到店测试继续延期。
