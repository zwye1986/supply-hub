# 直充手机号与 SPU 限频及运营监控设计

日期：2026-09-30。状态：用户已确认全文，实施中。纯领域规则、持久化模型及受理原子入队已完成，执行接入与监控尚未完成。

关联：[项目签名标准商品 HTTP 设计 §10](2026-09-30-project-signed-standard-order-http-design.md#10-本期新增直充手机号与-spu-限频及管理端监控)、`docs/集成测试.md#774-项目签名标准商品-http-闭环设计待实现2026-09-30`。

## 1. 目标与已确认决策

将旧 cardshop 的直充限频与监控能力纳入供应链中台，接入既有受理、履约、查询、回调与资金流程。旧系统仅作行为参考，不作为接入对象。

- 限频身份为规范化实际充值手机号+平台 SPU，全中台共享；同组合跨分销商、项目、供应商以及该 SPU 下不同 SKU 串行。不同手机号或不同 SPU 可并行。
- 仅 MOBILE 直充适用；非手机号直充与卡券不进入队列。不增加下单参数，不恢复 buyerUserId 或增加 limitMobile。
- MySQL 持久化队列，现有 PersistentTimer 唤醒；同队列按入队序号 FIFO，自动恢复与人工重试不插队。
- 受理时预占资金，等待期间保留；唤醒、重试、重放和重路由不重复预占/扣款。
- 成功最短占用 5 秒，从取得占用起算；确认成功时已满 5 秒即可放行，否则只等剩余时间。
- 未知结果继续占用。超过 30 分钟只告警，不自动放行；任务租约不是外部处理结果。
- 人工释放仅允许可信已结束或确认未发送且无执行中外呼的占用，不提供未知结果强制释放。
- 监控仅供内部运营使用，按 query/retry/release 独立授权；不开放分销商门户和项目 API。

本文的队列限频与项目+SKU+周期手机号限购独立，不能共用统计维度或将排队解释成限购拒单。

## 2. 现有实现与接入边界

已核对 `FulfillmentAcceptanceLifecycle`、`FulfillmentOperationExecuteHandler`、`SupplierOrderLifecycle`、`FulfillmentOperationDO`、`DatabasePersistentTimer` 与 `SupplyChainModuleArchitectureTest`。

受理流程已创建订单、请求、BOrder 与履约操作，并通过 FundReservationService 预占资金。执行 Handler 解密目标后调用 claimForExecution，再于事务外调用适配器；结果交给 SupplierOrderLifecycle。新队列必须在实际主履约副作用前接入，不能仅在 HTTP Controller 限频而遗漏内部受理入口、任务恢复或重路由。

平台 SPU 由平台 SKU 的 spu_id 解析，通过 catalog 应用层提供所需身份。队列在首次入队时冻结 platformSpuId，之后同订单沿用。不能按供应商 Offer、分销商品或项目商品分队列。

发送入口不能将“没有队列项”直接解释为无需限频。已冻结队列项优先决定适用身份；尚无队列项的历史订单须从可信平台商品与实际目标判定，MOBILE直充缺项时拒绝SEND并报告迁移未完成，目标类型无法可靠解析时同样失败关闭。仅可靠确认非MOBILE直充或卡券后可沿用普通执行流程，不能以 buyerUserId、号码看起来像手机号或当前供应商商品补猜类型。

专用摘要密钥由 `aifuli.supply-chain.recharge-frequency.identity-key-ref` 配置引用。本期默认解析器只支持 `env:环境变量名`，读取该环境变量的32字节Base64密钥并在独立供应链进程启动时固定快照；部署配置填写引用，仓库不保存密钥或测试默认值。推荐引用 `env:AIFULI_SUPPLY_CHAIN_RECHARGE_FREQUENCY_IDENTITY_KEY`。引用缺失、格式错误、密钥不可解析或长度不符时供应链进程启动失败，不能关闭队列绕过。该配置不改变其他独立进程；测试上下文显式注入测试密钥端口。返回密钥字节时防御性复制，异常和日志不包含引用所指的密钥值。所有节点必须使用同一稳定密钥，在线更新环境变量不改变已有进程快照，变更密钥需要离线迁移全部队列身份，不作为普通凭证轮换操作。

内部 PreparedAcceptance 同时保存已校验目标的 accountType 与实际手机号值。仅手机号值为空不能区分非MOBILE与非法MOBILE，因此由 Facade 传递内部 targetAccountType，供受理事务判定适用范围与失败关闭；该字段不新增到外部下单契约。

队列编排放在 `border.fulfillment.application`，领域规则与枚举放在对应 domain，存储放在对应 dal。Facade 委派应用服务，不访问 DAL；Timer Handler 调用 Lifecycle/恢复入口，不直接写领域表。资金动作只经既有应用服务，border 不访问账户/账本 DAL。所有数据位于供应链独立库，无物理外键；菜单与权限部署 SQL 位于 script/deploy 并由部署过程写入 system 库。

## 3. 持久化模型与不变量

新增 Liquibase changeset，旧 changeset 不修改。以下字段语义用于后续 DDL/DTO 中文注释对齐，状态和错误名称以枚举/常量实现。

真实迁移门禁核对发现既有 046 的 `sc_business_code_scope.created_at` 与 `sc_business_code_claim.created_at` 注释未标记 Asia/Shanghai。新 048 同时以 ALTER COLUMN COMMENT 方式补正这两处元数据，保留原类型、精度、NULL 约束和数据，不修改046校验和，也不再次平移时间。既有全库测试的变更集/表/时间列数量以实际完整 changelog 为准更新，不关闭全库时间门禁。

### 3.1 队列控制行：sc_recharge_frequency_queue

每个组合一行，至少保存：

- id：直充限频队列内部主键。
- mobile_digest：规范化充值手机号的专用 HMAC 摘要；使用 BINARY(32)。
- mobile_masked：充值手机号脱敏展示值。
- platform_spu_id：入队时冻结的平台 SPU 内部主键。
- next_enqueue_sequence：下一次入队可分配的队列序号。
- holder_entry_id：当前持有占用的队列项内部主键，可空。
- occupancy_generation：每次取得新占用递增的占用代次。
- acquired_at：当前占用取得时间，Asia/Shanghai；释放后可空。
- minimum_hold_until：本次成功最短占用截止时间，Asia/Shanghai；取 acquired_at+5 秒。
- wake_generation：每次安排新的唤醒意图递增的任务代次。
- next_check_at：下一次队列补偿检查时间，Asia/Shanghai，可空。
- blocked_reason：队列阻塞原因，可空；存量并发外呼冲突等需要核实的状态不得认领新项。
- occupied_alert_active / backlog_alert_active：当前占用超时/积压告警是否处于激活状态，用于记录跨阈值及恢复。
- last_occupied_alert_at / last_backlog_alert_at：上次对应告警时间，Asia/Shanghai，可空；用于跨节点和重启后的告警限频。
- version：乐观锁版本；created_at/updated_at 统一 Asia/Shanghai。

沿用 022 数据库时间迁移及 SupplyChainDatabaseClock，持久化 DATETIME(3) 全部为 Asia/Shanghai；5 秒窗口、30 分钟阈值与扫描条件使用数据库时钟。Timer 接受 Instant 时通过 databaseClock.toInstant 转换，不直接将本地时间当 UTC。

唯一键 `(mobile_digest, platform_spu_id)`；补偿扫描索引覆盖 next_check_at 和 id。同一队列最多一个 holder，且 holder 对应冻结身份一致的队列项。摘要密钥专用于稳定队列身份，不使用项目应用密钥、nonce 或可随凭证轮换变化的密钥。上线前配置独立密钥引用，缺失则失败关闭；摘要算法或密钥变更须暂停相关写入、统一迁移存量队列后切换，不能在线生成两个身份并绕过占用。

### 3.2 队列项：sc_recharge_frequency_entry

至少保存 queue_id、order_id、order_no、fulfillment_request_id、enqueue_sequence、status、active_operation_no、occupancy_generation、dispatch_phase、enqueued_at、completed_at、version，以及用于归属筛选的 buyer_id/project_id。队列手机号与 SPU 以 queue_id 指向冻结控制行；不复制可变商品归属作为判定依据。

dispatch_phase 区分 PREPARED（本次外呼资格已取得但尚未越过发送边界）和 SEND_POSSIBLE（已越过发送边界，外部可能已受理）。不以未记录响应或没有 externalOrderNo 推断从未发送。其转换与当前持有者、占用代次、active_operation_no 一起校验，每次新的合法操作建立自己的阶段，旧操作的发送事实仍从既有调用/观察记录保留。

状态封闭为 WAITING（等待）、HOLDING（占用）、FINISHED（本订单排队流程已结束）。HOLDING 下另依据可信履约事实展示执行中、结果未知、待核实或成功等待窗口；不将这些展示状态直接等同于释放许可。订单自身终态和队列状态分别记录。

唯一键 order_id 与 `(queue_id, enqueue_sequence)`；队首查询索引 `(queue_id, status, enqueue_sequence)`，归属/时间分页索引按实际 SQL 补齐。入队序号在锁定控制行的事务内递增，FIFO 以持久化序号定义；不得仅用数据库自增 id 或客户端时间推断并发提交顺序。

一个订单只建一项。同单重试/重路由保留序号，更新当前合法操作引用。失效队列项的跳过必须有持久化终态或可信未发送事实；不能因为任务失败便跳过仍有效订单。

### 3.3 运营审计：sc_recharge_frequency_action_audit

追加记录 action_no、请求指纹、action_type（RETRY/RELEASE）、queue_id、entry_id、order_no、expected_generation、expected_version、真实内部操作用户、原因、结果、前后状态、trace_id 与 Asia/Shanghai 时间；action_no 唯一。成功操作同业务事务保存审计；拒绝和冲突通过独立审计事务保留，不能随业务回滚丢失。并发同 actionNo 仅一个事务能成功，冲突事务回滚后在新事务读取原结果，不在已失败事务里继续写业务。审计不含明文手机号、目标密文、供应商凭证或任意 Redis key。

客户端 actionNo 只用于管理操作幂等，不能替代身份认证。同一 actionNo 的不同参数拒绝；成功重放返回原审计结果，不再次释放后来的持有者。

请求指纹包含真实操作用户与完整命令参数；鉴权发生在幂等查询之前，不允许失去权限或换用户后读到原结果。一个 actionNo 只保存首个已确定的成功或业务拒绝结果，同参数重放也复用该拒绝结果；异参重放不覆盖原审计，另记含 actionNo、真实用户、traceId 与安全错误码的冲突日志，不在唯一 actionNo 表中伪造第二条同号记录。独立审计事务针对已认证且具备相应权限、参数可形成有效命令的业务拒绝；登录/权限拒绝以及无法形成有效命令的绑定/解析错误由平台安全/访问日志记录，不虚构用户或把空 actionNo/空原因塞入业务审计表。普通错误记录也必须遵循上述正文脱敏要求。

运营原因文本持久化和展示前脱敏其中的手机号，不能因原因是自由文本而把实际手机号写入审计；请求指纹仍以完整原命令计算，原文只作为内存输入，不落普通日志。前后状态快照使用显式安全字段白名单，不直接序列化含摘要或目标的DO。

## 4. 入队、认领和执行

### 4.1 入队

受理完成商品和 MOBILE 目标校验后，确定冻结 SPU 与规范化手机号摘要。与建单、资金预占、创建首次操作同事务建立队列项和唤醒意图。受理仍返回既有稳定 orderNo；排队不是受理拒绝，不要求项目改订单号重试。

预占失败则整个受理回滚，不留队列项。入队存储故障不得绕过限频向供应商发送。

并发首次创建同队列时以唯一键裁决；队列身份唯一键冲突须回滚本次完整受理事务，在事务外的受理协调入口进行最多3次有界重试，每次重新调用事务代理并读取已创建队列。只识别该身份唯一键的创建竞争，不将其他完整性异常当作队列竞争吞掉；达到上限向调用方报告失败并保留既有回执恢复语义。不得在发生冲突的同一事务里 catch 后继续资金/订单写入。

### 4.2 认领

短事务内锁定控制行，检查阻塞原因、当前持有者、成功时间窗口及有效队首。只有未阻塞队列的队首可取得占用；同事务更新控制行、队列项和目标履约操作的执行状态/版本，dispatch_phase 为 PREPARED。非队首返回正常等待结果，不递增供应商实际外呼失败次数。

外呼前另一个短事务按当前 holder、代次、操作引用及版本，将 PREPARED 改为 SEND_POSSIBLE；成功提交才允许该执行者在事务外发送。执行者携带首次认领取得的资格快照，校验失败便结束，不重新读取新代次来替换旧资格。人工未发送释放与此阶段推进竞争同一控制行锁：释放先提交则旧执行者不能越过发送边界；SEND_POSSIBLE 先提交则释放被拒绝，之后即使执行者在 HTTP 前失联也保守按可能已发送处理。不得采用“检查资格→释放锁→稍后无条件发送”的实现。占用版本只能保护本地推进，不承诺外部系统也识别本地令牌。

执行资格包含 queueId、entryId、operationNo、occupancyGeneration、entryVersion 与 operationVersion，后两者均为认领提交后的版本。发送边界同时验证原操作仍处于可发送的认领状态；可信结果或人工撤销改变任一版本后，旧资格失效。

当前执行者在越过发送边界之前发生凭证解析、目标装配等本地故障，且能以原资格证明仍为自身PREPARED、没有此前未确认发送风险时，可受控撤销本次未发送资格回原WAITING，保留资金、序号和业务操作号，并保存后续检查意图；原故障向Timer传播，不计为供应商实际外呼失败。撤销校验失败不能清占用，尤其不得把标记后的HTTP异常按该路径撤销。该运行时回滚不伪造内部运营用户或运营审计，记录安全故障类别、操作号、代次与traceId。

未决风险检查只针对可能产生充值副作用的操作：只读QUERY不构成充值发送风险。按已锁BOrder尝试号及Operation序号可确认属于当前操作之后、仍为PENDING/CLAIMED且attemptCount为0、没有结果或外部操作号的预建操作，不应阻止当前PREPARED撤销；其他充值操作必须已有可信FAILED/REJECTED事实，否则继续保守拒绝。不能仅因序号靠后就忽略EXECUTING/UNKNOWN/MANUAL或任何发送迹象。真实Handler测试覆盖预建后续操作/只读查询存在时凭证故障仍回原WAITING，以及此前未知充值风险仍保留占用；凭证恢复后的重试须能够重新认领，不能永久停留ALREADY_EXECUTING。

本地准备失败之后，若受控撤销自身发生存储故障，Timer收到的异常同时保留经过脱敏的原准备故障和撤销故障（安全cause/suppressed），不能由后一个异常覆盖前一个原因，也不能携带任意原始报文。撤销事务完整回滚并保留占用/资金，故障日志按安全类别记录；恢复仍依据数据库状态。

运行时本地准备故障撤销后的新队列唤醒安排在数据库当前时间30秒之后，与人工确认撤销后的立即检查区分；不得每次凭证失败都立即生成新代次执行任务，绕过原Timer有界重试形成忙循环。既有原执行任务的有界重试继续生效，等待及本地准备失败不计供应商实际失败次数；故障持续时按安全类别抑制重复日志。

执行、结果收敛与运营恢复入口使用相同锁序：队列控制行→队列项→履约请求/总单→BOrder/Operation→经资金服务取得资金锁，具体多行按主键升序。回调与认领须遵循同一顺序；需要预读解析 queue_id 的路径在加锁后重新校验，不依赖未加锁快照。

认领短事务显式使用READ_COMMITTED隔离：取得控制行锁后，按 `(queue_id,status,enqueue_sequence)` 索引定位当前有效队首，再将队首和候选两项按主键升序精确锁定并重新校验；不锁全部等待项。该语句看到控制行锁取得后的最新提交，且所有合法队列写入口均先取得同一控制行锁，定位到队列项锁之间没有同队列写者改变队首。其他结果/发送标记/撤销入口只锁对应队列项；该隔离选择不改变全进程事务默认配置。

首次受理保留已有回执/商品限购锁序，创建当前事务独占的新订单和操作后先取得队列锁并入队，最后调用资金预占；不能先锁资金账户再取得队列锁，否则会与结果收敛的队列→资金顺序形成反向等待。结果收敛和恢复入口不取得商品限购写锁；同事务中的入队或资金失败一起回滚。

占用在完整订单履约周期内由同订单持有；供应商切换或下一合法操作仍核对该订单与代次，不释放给后续订单后又重新抢占。QUERY_ORDER、查询确认与回调不进入等待队列。只对真正产生充值副作用的适配器步骤认领；不能将协议中所有 HTTP 请求都当作 SEND。

越过 SEND_POSSIBLE 边界后到 HTTP 外呼之间发生失联，不能由新节点推断“未发送”后放行。现有 EXECUTING/RESULT_UNKNOWN 禁止重复 SEND 的规则继续执行，通过查单和核实恢复；仅 PREPARED 的可信撤销按人工释放规则处理。占用代次只能阻止旧任务修改新状态，无法撤销已发出的外部请求。

## 5. 结果、窗口和资金

- 供应商接受或处理中：继续持有，占用不会因当前同步 HTTP 已返回而释放。
- 未知、超时、外呼异常、节点失联：继续持有并保留资金预占；查询恢复，不重新发送。
- 可信成功：经既有履约与资金状态机确认；达到 acquired_at+5 秒才释放队列，未到则安排剩余窗口唤醒。重复事实不刷新时间。
- 可信失败且本订单履约结束：经既有流程释放资金与占用，推进下一项。若同订单还有合法继续履约的操作，仅更新该订单操作引用，不能把某一次供应商尝试失败当作总订单结束。
- MANUAL：不代表供应商已结束。现有 transition 将 MANUAL 提前作为终态返回，实施须增加受控核实/恢复入口接收可信后续事实；不允许将所有 MANUAL 直接改成可重发。
- 查单 NOT_FOUND、HTTP 404 或查询鉴权/协议故障：不等于可信从未发送或可信终态，继续占用。仅可据此记录观察与安排核实，不能自动重发、释放队列或释放资金。

本次尝试可信拒绝与整单候选耗尽必须由受控编排区分。新增仅内部调用的 `confirmTrustedRejectedAndContinue` 结果入口，显式携带原资格和已存在的下一合法operationNo；无该指示的既有M1单候选结果入口仍按整单失败收敛，不自动发现任意PENDING操作作为重发依据，也不新建路由、BOrder或Operation。该入口在统一锁序内验证旧操作为当前holder/activeOperation，原资格的队列、项、操作与代次匹配，结果为可信REJECTED，旧操作为执行中/结果未知或待核实；已确认成功/失败的操作不能重复下达续履约指示。Request/总单必须仍可履约，可信MANUAL恢复按既有汇总策略恢复处理中，不复活已成功、失败或取消的整单。

下一操作必须来自锁定的同Request/Order BOrder与Operation列表，PENDING/CLAIMED、attemptCount为0且无既有结果或外部操作号，类型为FULFILL的充值副作用步骤；其他此前操作不得存在未核实发送/未知/成功风险。同BOrder的下一操作须恰为当前sequenceNo+1且当前BOrder仍活动；换供应商尝试须为同请求attemptNo+1的已存在待执行非活动BOrder、sequenceNo=1，原活动尝试结束与新尝试激活在同一事务完成，明确清空旧active_flag并核对乐观版本。QUERY、旧序列、归属不匹配或风险未清不得续履约；所有校验失败完整回滚。

Request已有activeBOrderId非空时必须指向旧当前活动BOrder；为空的历史M1记录允许由上述完整关联验证恢复。续履约事务把该指针更新为下一操作对应BOrder，同BOrder保持同一指针，不能只改队列引用而留下不一致的活动尝试指针。

续履约事务记录旧事实并将旧Operation置FAILED：同BOrder保留处理中/活动状态，跨BOrder将旧尝试结束并激活新尝试；统一把冻结队列项绑定至下一操作、保持HOLDING/PREPARED并保存新唤醒意图。SPU、FIFO序号、holder、occupancyGeneration、acquiredAt和minimumHoldUntil均保留，资金继续预占，下一执行者仍须独立claim及发送marker。旧事实不能改变新的activeOperation或清占用。禁止在旧结果已把Request置FAILED/释放资金后再用一个断开的绑定接口救回终态。真实MySQL测试必须经此可信结果入口，不得用SQL伪造旧操作失败绕过资金流程；覆盖同BOrder/跨供应商尝试、非法下一操作拒绝、队尾不能抢占与最终候选耗尽只释放一次。

限频中的MANUAL仍属于待核实的活动供应商尝试，不能因进入MANUAL清空BOrder活动标记或把completedAt作为外部结束证明。兼容旧MANUAL记录已写completedAt的情况：仅当旧Operation/BOrder均为MANUAL且仍匹配当前holder、原资格和完整续履约条件时，允许可信拒绝恢复，同BOrder恢复处理中时显式CAS清空completedAt并保留活动标记；跨BOrder按可信拒绝实际结束旧尝试后再激活新尝试。真正终态清空活动标记必须显式写NULL，不能依赖MyBatis默认忽略NULL后偶然保留的旧值。

释放和调度下一项使用 holder_entry_id+occupancy_generation+version 条件，迟到回调不得清除新持有者。相互矛盾的供应商终态进入异常核实和审计，不覆盖已确认事实或对新订单产生资金动作。

原始状态观察以既有 observation_key 唯一键做原子幂等写入，不依赖默认REPEATABLE_READ事务中预读形成的旧快照判断是否已存在，也不对不存在的观察键执行FOR UPDATE（其间隙锁可能让不同队列首次写观察时互相阻塞或死锁）。专用MyBatis XML使用MySQL原子INSERT … ON DUPLICATE KEY UPDATE：仅当冲突行的observation_key与输入一致时保持既有行所有内容不变；若命中observation_no等其他唯一键且observation_key不一致，令非空observation_key更新为NULL触发完整性异常，使整个结果事务回滚。不得使用INSERT IGNORE、无条件吞掉所有唯一键冲突，或在失败的大事务中catch后继续。调用方不依赖affectedRows判断新旧，也不重写既有观察的结果、时间或traceId。真实MySQL测试覆盖重复同事实并发收敛、不同操作/不同队列首次观察并发，以及其他唯一键冲突时状态和资金的完整回滚。

资金仍由 FundReservationService 和既有履约流程处理；队列服务不新增独立扣款/退款路径。人工释放占用不改变订单结果或预占。整个队列不得为达到 30 分钟告警条件而取消等待订单或释放其资金。

## 6. Timer 与补偿

新增受控任务类型用于队列唤醒、窗口检查与补偿，统一登记到任务常量和 HandlerRegistry。payload 带版本，仅含 queueId、wakeGeneration 等业务引用，不含手机号。

现有 scheduleOnce 永久复用同 taskType+idempotencyKey 的任务。唤醒键必须包含持久化 wake_generation：同代次重复安排幂等，新代次不会命中已完成任务。当前状态由数据库重新判定，旧代次任务不得修改新占用。

入队、释放、成功窗口检查的下一次调度意图与业务事务同时持久化；定期补偿扫描 next_check_at，补齐遗漏或死信后的调度，并记录原因。已有有效未来任务不每轮扫描新增。占用期间以 30 分钟阈值检查超时，但检查不抢占 holder。

补偿扫描保留有效未来 PENDING/RETRY 任务及有效运行租约的 wakeGeneration，同时将 next_check_at 推进到数据库当前时间后 30 秒，避免同一批到期队列反复占据每批 100 条的扫描窗口。next_check_at 只表示下一次补偿扫描时间；WakeHandler 判断当前代任务是否已到业务执行时间时，读取该代 Timer 的 next_fire_at，不读取补偿已推进的 next_check_at，避免扫描与任务执行竞速造成延期或错误完成。

当前标准履约仅适配器支持 QUERY_ORDER，尚无通用主动查单恢复 Handler。本期补充内部查询服务，由队列唤醒在已配置的供应商支持查单时调用，复用冻结 connector/offer/billno 和凭证，保留原主履约 operationNo，将查询事实提交统一结果收敛入口；不新建 SEND 操作或重复资金动作。查询无需实际充值账号，不因目标解密失败阻止安全查单。处理中/未知的后续检查沿用既有30秒恢复基线，MANUAL仅允许通过查单取得可信终态恢复，查询失败仍保留人工处理与占用；不支持查单的适配器只检查和告警，不能尝试将查询作为发送。出站调用在事务外进行，结果按相同队列锁序验证当前操作引用，避免迟到查询释放新持有者。

查单适配器须优先判定显式协议/鉴权错误，再解释订单状态，不能仅因HTTP200或携带终态字段便认定可信。QNM返回签名/商户错误（例如ResCode为-100、-101或SIGN_ERROR）时，即使同时携带orderStatus成功/失败也只产出非可信恢复事实；查询服务不解析供应商原始正文来补救适配器分类，不把供应商错误码散入通用队列领域。

等待、非队首、窗口未到均为正常结果，不把 Timer 执行次数当成供应商外呼次数。任务异常遵循现有有界重试/死信，补偿负责继续可恢复的业务检查；重复任务始终重新验证业务状态。

无持有者的唤醒在锁内选择有效队首并安排既有 OPERATION_EXECUTE 任务，真正的认领及发送资格仍由执行Handler完成，不能先将操作改成EXECUTING后再期待Handler重复认领。队列唤醒代次同时参与执行任务幂等键，人工撤销后新代次可以唤醒同一业务操作，而不会复用已完成的旧Timer任务。并行旧/新任务仍由Task4门禁裁决一个实际外呼。Timer通过延迟解析端口注入生命周期，避免Registry→Handler→Lifecycle→Timer的构造依赖环；不改变全局Timer配置。

单例装配结束后由独立的非事务启动校验组件解析必需Timer，缺失时启动失败；事务调度组件不实现启动回调接口，避免默认JDK接口代理与具体类注入冲突，不为此更改各独立进程的全局AOP配置。

已有holder经受控可信拒绝切换到下一合法PENDING/CLAIMED操作时，唤醒同样安排该activeOperation的OPERATION_EXECUTE，保留原占用，不能把尚未发送的新操作当作未知结果进行查单。只有已越过发送边界的处理中/未知或待核实操作进入查询恢复；PREPARED失联保留未发送资格供受控撤销，不能因查无订单自动重发。

## 7. 管理端契约与权限

内部管理路径前缀 `/supply-chain/recharge-frequency`，不注册项目 HMAC operation。采用 CommonResult/PageResult 和既有内部登录上下文；query/retry/release 专用权限使用 `supply-chain:recharge-frequency:query`、`:retry`、`:release`，每个 HTTP 映射带 @PreAuthorize。部署仅赋给内部运营角色，普通订单权限不隐含这些权限。

查询接口：

- `GET /statistics`：返回全局 waitingOrderCount、occupiedQueueCount、overThresholdQueueCount、overdueQueueCount、successMinimumHoldSeconds=5、occupiedAlertSeconds=1800、backlogThreshold=5、observedAt。超阈值指单队列 WAITING 数>5，超时指未释放占用超过 30 分钟。
- `GET /waiting-orders`：PageResult，展示 entryId、queueId、version、maskedMobile、SPU 名称/编码、订单号、归属、enqueuedAt、waitSeconds 与原入队序号；同队列按原序号排序，全列表按入队时间、queueId、序号稳定排序。支持分销商、项目、SPU 编码、中台/项目订单号、入队时间范围等适用筛选。
- `GET /occupancies`：PageResult，展示 queueId、maskedMobile、平台 SPU 名称/编码、holderOrderNo、履约展示状态、acquiredAt、minimumHoldUntil、heldSeconds、overdue、generation、version、canRelease/releaseBlockedReason。包含 holder 非空或 blocked_reason 非空的队列；迁移阻塞行允许 holderOrderNo/占用时间为空，展示阻塞原因与待核实订单引用，canRelease 为 false，不能将其隐藏为“无占用、可发送”。
- `GET /backlogs`：PageResult，按手机号+SPU 聚合，按等待数量降序、最早等待时间及队列主键稳定排序；返回等待数、最早等待时间、超阈值标识。

列表都由数据库分页/聚合，默认 pageSize=20、最大100，不使用截断前200条计算统计。统计卡片全局口径，列表筛选不悄悄改变卡片口径，页面明确“全局统计”；每次响应给 observedAt，不承诺不同 HTTP 请求共享同一快照。列表不返回明文手机号或摘要密钥。

手机号精确筛选使用 `POST /search`，请求体携带 mobile、view（WAITING/OCCUPANCIES/BACKLOGS）与上述筛选/分页字段；后端规范化并计算摘要后查询，不支持手机号模糊检索。该入口具有 query 权限，body 不进入普通请求日志/审计明文，响应 no-store；避免把手机号放在 URL。其他无手机号查询仍使用上述 GET。

现有通用 ApiAccessLogInterceptor 在非生产环境会直接记录正文，GlobalExceptionHandler 也会将异常请求正文写入错误记录，因此仅在Controller关闭访问日志不充分。本期将管理端限频路径登记为通用敏感路径；访问日志/错误记录省略参数，敏感路径的参数绑定、JSON解析与校验错误日志只记录安全错误类别、字段名和traceId，不打印包含 rejectedValue/原始JSON的异常报文。共享框架修改只改变明确敏感路径的日志行为，不改变认证、业务错误码、事务或其他普通路径，并以供应链HTTP契约测试覆盖正常、非法手机号、非法JSON和存储故障时的日志脱敏。

命令接口：

- `POST /entries/{entryId}/retry`：body 包含 actionNo、expectedVersion、reason；仅对仍有效 WAITING 项触发普通队列检查，不插队、不另建操作。结果为“已触发检查”，不承诺已发送或已成功。
- `POST /queues/{queueId}/release`：body 包含 actionNo、expectedHolderOrderNo、expectedGeneration、expectedVersion、reason；重校验当前持有者及可信释放依据，不接受客户端提供“已确认未发送”作为证据。

成功占用释放须达到成功最短窗口；其他释放须确认供应商已结束，或所有相关旧操作均无未确认发送风险、当前操作仍 PREPARED 且在事务中撤销其旧执行资格。SEND_POSSIBLE、未知结果、旧版本、错误持有者返回稳定业务错误并审计。未发送的占用恢复为原序号 WAITING，仍按 FIFO 检查，不能清除原操作风险后偷偷把该订单放到队尾；撤销的旧执行任务不得恢复直接发送，后续执行通过新的唤醒代次再次取得同一合法操作资格，不新建业务副作用号。若订单已结束则 FINISHED。权限失败、非法状态、参数和 CAS 冲突可区分，错误码集中定义，客户端不解析文案。

现阶段没有可绕过结果核实的 forceRelease，也不新增仅凭运营勾选便伪造供应商终态的接口。未知结果只能通过已配置供应商查单、可信回调或受控核实流程取得依据；不支持核实的供应商需要保留人工处理状态，不能以 TTL 解锁替代。

## 8. 前端监控页面

在供应链管理端订单区域增加“直充限频监控”。实现仓库为 supplychainhubweb，旧备份仅参考；现有订单 API 位于 `src/api/order/index.ts`，详情抽屉为 `src/views/supplier/order/platform/detail/index.vue`，新监控独立 API/页面模块，不复制旧 cardshop 的 Redis key 契约。

- 顶部全局统计：等待订单数、活跃占用队列数、积压超阈值队列数、占用超时队列数；旁边显示5秒窗口/30分钟告警/积压>5的含义与刷新时间。
- 三个页签：等待订单、活跃占用、手机号+SPU 积压排行。展示脱敏手机号、SPU 编码/名称、归属、业务单号、等待/占用时长；支持分页、适用筛选和手动刷新。
- 等待订单允许“触发重试”，要求 reason，提示按原顺序检查；活跃占用只对后端 canRelease 允许“释放”，确认弹窗展示脱敏队列身份与持有订单。结果未知提示先查单核实，不显示强制释放选项。
- 操作成功后刷新相关列表与全局卡片；版本冲突提示数据已变化并刷新，不静默重复命令。请求进行中防重复点击，切换页签/筛选后的迟到响应不能覆盖当前结果。
- 订单详情复用已有抽屉和订单查询权限；没有订单权限时隐藏详情入口，监控权限不自动授予详情/资产访问。
- 查询失败显示错误或保留标识为过期的上次数据，不能替换成正常零值。手机号输入不持久化到 URL、路由缓存或 localStorage，页面/响应不提供手机号明文复制和导出。

现有Axios拦截器把普通业务拒绝丢弃为字符串 `error`，本页面需要显式选择保留安全的结构化业务错误（code、message），用于识别版本冲突而非解析文案；共享客户端仅增加该显式选项，其他调用与认证刷新行为保持原契约。网络/超时及无法确定执行结果的故障保留本次actionNo，不能自动换号重做。敏感请求的客户端调试日志只输出安全错误类别，不输出Axios配置/正文。监控菜单禁用路由KeepAlive，页面离开时清除手机号输入并废弃迟到查询响应。

菜单、三个权限按钮由 script/deploy 的 system 库部署 SQL 配套，供应链程序不写 system_menu。

## 9. 告警与观测

沿用旧系统的积压>5与占用30分钟阈值作为本期默认监控基线；告警按手机号+SPU 队列聚合，在跨阈值或达到受控重复间隔时发送，恢复后记录恢复，不每次任务扫描刷日志。告警激活状态与上次时间在控制行锁内持久化，多节点或重启不各自重置限频；成功窗口不作为故障告警。

本期告警先通过受控事件日志输出，重复间隔为30分钟，与首次跨阈值和恢复事件共用持久化状态；不引入新的外部通知依赖。补偿默认每30秒扫描到期next_check_at，每批最多100个队列，参数和事件编码集中为常量或配置；持续存储/调度异常按安全类别聚合或采样，不能每轮逐队列输出完整异常。单个异常不伪装为处理成功，下一轮保留数据库恢复依据。

队列告警的激活状态和上次时间在事务内保存，事件日志通过同一事务的afterCommit回调输出；事务回滚时不输出未持久化的跨阈值/恢复事件，避免持续调度或存储故障重复记录“首次”告警。回调只捕获安全的队列/SPU/订单引用及计数，不保留或打印完整持久化对象。

补偿循环边界对扫描失败和逐队列失败统一生成安全异常，再在首次/30分钟采样日志中保留安全cause/suppressed链、调用位置及失败队列引用。不能只输出包装异常的类名后丢弃根因，也不能因扫描异常尚未经过内部脱敏而直接输出原异常正文；原始SQL、请求正文、手机号和密钥不进入日志。

入口和状态变更记录业务单号、队列ID、SPU、代次、状态、traceId；不记录明文手机号、完整目标、密钥或凭证。第三方查询/发送日志沿用适配器脱敏及耗时规范。存储故障不能 fail-open，统计/查询故障不能返回伪造空集合。

## 10. 发布、验证与依赖

发布前暂停手机号直充新受理和 SEND，等正在外呼的节点退出或明确登记其未知处理状态；对存量非终态手机号直充恢复冻结目标与 SPU，重建队列项和持有者。已外呼或无法证明未发送的存量操作按 SEND_POSSIBLE 迁移，acquiredAt 使用可信历史发送时间，无法还原时采用保守迁移时间并标记依据缺失，不伪造为 PREPARED。同组合若已有多个可能在外部处理的订单，标记迁移冲突并保持阻塞，核实后才能启用，不能任选一个 holder 后忽略其他订单。无法可靠解析的历史目标不得从 buyerUserId 推断。

迁移控制行需要支持冲突阻塞原因；有冲突时不认领新项，监控展示需人工核实。先部署新表和任务注册、完成存量检查，全节点切换后恢复受理/发送；禁止旧节点绕过新队列继续 SEND。摘要密钥在所有节点一致，部署检查验证；无密钥或迁移未完成失败关闭。

存在多个存量可能已发送订单时，控制行以 blocked_reason 阻塞且不任选 holder；各风险队列项按 HOLDING+SEND_POSSIBLE 保存已有操作引用，并标记迁移占用代次，不作为新执行资格。可信结果可结束其对应旧队列项，但不能据此清除其他风险项；全部存量风险经核实结束后，迁移恢复入口才清除阻塞并唤醒正常等待项。正常运行只允许控制行 holder 对应的一项获得新发送资格，迁移例外不能作为并行执行途径。

自动化门禁：

- *Test：手机号规范化、SPU身份、FIFO、5秒边界、未知结果、人工释放条件、资金动作幂等、错误映射、告警抑制。
- *IntegrationTest：真实 MySQL/Liquibase，队列唯一性/序号、多节点认领、事务回滚、资金预占、Timer新旧代次、节点重启/死信补偿、迟到回调、并发人工命令、MANUAL可信恢复和迁移冲突。
- *ContractTest：管理端路径与权限、分页/统计口径、字段和错误、手机号搜索不入URL、无强制释放、项目HMAC不能访问全局接口。
- *ArchitectureTest：Facade/DAL、border资金边界、纯领域依赖、管理映射授权、独立数据所有权。前端运行改动文件检查、类型检查、构建与页面验收。

人工联调清单维护在 docs/集成测试.md §7.74，不替代自动化门禁。本次只有设计变更，不表示代码或联调已通过。

限频组件可通过既有内部受理入口与可控供应商联调；对外标准下单开放仍受主设计 §8 的内部报价适配门禁约束。报价依赖不因完成限频设计而自动解决，也不能向项目方重新增加报价token或paidAt。
