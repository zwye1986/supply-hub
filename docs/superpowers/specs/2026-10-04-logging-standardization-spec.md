# 全系统日志记录、链路关联与集中式日志规范

> 初版日期：2026-10-04；本次修订：2026-10-08
>
> 状态：方案已确认；本文为全仓日志准则，目标能力按第 10 章分阶段落地，不表示已全部实现或上线
>
> 核对基线：`origin/dev` / `564206bb0962f1d63f9253b6ddd85b3f88ff0c8f`
>
> 技术实现说明：`docs/superpowers/specs/2026-10-04-elk-logging-integration-design.md`
>
> 联调步骤与证据：`docs/集成测试.md` §8.4～§8.12

## 1. 目标、范围与实现基线

### 1.1 目标与总体原则

统一全系统“记录什么、由谁记录、如何关联、如何检索、如何验收”。继续采用 Logback → Logstash → Elasticsearch → Kibana，不重新建设采集体系，不要求一次性重写全部历史日志，也不在本轮切换 ECS/OpenTelemetry 模型。

1. 日志是诊断证据，不是业务事实的唯一来源。订单、账本、审计以各模块的权威存储为准，禁止根据某条日志缺失判定业务未执行。
2. 统一结构化封装，保留中文可读消息；字段契约与自然语言描述分别治理。
3. 异常由明确的终结边界负责记录，禁止逐层重复堆栈和逐层重复失败摘要。
4. 链路上下文由入口建立、跨边界传播、退出时恢复或清理，不能从复用线程的旧 MDC 推断新执行的编号。
5. 集中式日志是允许故障丢弃的诊断通道，不承担可靠业务事件投递或审计持久化职责。
6. 文档、实现和验证分别标记状态，不以框架能力合入证明全部进程已接入，不以本地测试证明生产链路已通过。

### 1.2 适用进程与模块边界

应用日志标识使用实际 `spring.application.name`，不是 Maven 模块名或启动类名：

- `gateway-server`：`aifuli-gateway`，启动类 `GatewayServerApplication`。
- `aifuli-server`：`aifuli-server`，启动类 `AifuliServerApplication`，仅聚合 system + infra。
- `system-server`：`aifuli-module-system/aifuli-module-system-server`。
- `infra-server`：`aifuli-module-infra/aifuli-module-infra-server`。
- `member-server`：`aifuli-module-member/aifuli-module-member-server`。
- `supply-chain-server`：`aifuli-module-supply-chain/aifuli-module-supply-chain-server`。
- `taobao-flash-channel`：`aifuli-module-supply-chain/aifuli-channel-taobao-flash-server`，启动类 `TaobaoFlashChannelApplication`。
- `movie-server`：`aifuli-module-movie-server`，启动类 `MovieServerApplication`。

共享日志组件不改变数据所有权、事务边界或启动形态。供应链日志治理不得通过查询 `system_*`、`member_*` 表补充日志信息，不得隐式扫描新增 Feign 依赖，不得把电影或淘宝闪购并入供应链容器。本仓不存在 `MallServerApplication`。

### 1.3 已有能力与待补齐项

在上述基线核对到：

- gateway、system、supply-chain 根 Logback 配置已引用共享 ELK 片段，本地 pattern 已包含 MDC `traceId`。配置接入不等于所有运行环境均已开启或实机验收。
- member、infra、aifuli-server 根配置尚未引用共享 ELK 片段，本地通用 pattern 尚未带 `traceId`。
- taobao-flash-channel 已直接依赖 monitor starter，但未提供同等根 Logback 接入配置；movie-server 未直接声明 monitor starter，亦未提供同等根配置。后续应核验实际依赖及启动行为，不能据声明推断已自动接入。
- Servlet `TraceFilter`、Feign `TraceRequestInterceptor`、网关 `TraceWebFilter` 与 Reactor MDC 桥接、Spring 异步 MDC/TTL 组合已具备基础能力。原生执行器和全部 Job/MQ 入口不在自动覆盖范围内。
- 当前 encoder 输出固定 JSON 字段。订单号、耗时等写在 `message` 中不等于已经成为可聚合的 ES 字段。
- 当前 Logstash 按应用与日期分流；ILM 配置为 30 天删除、单主分片、零副本，零副本是单节点测试环境选择。

第 4 章新增字段、第 5 章统一编号及后台来源关联、第 6～8 章新增治理能力均属于目标要求，须实施并通过门禁后才能标记为完成。

## 2. 核心规范细则

### 2.1 日志形态规范：方括号动作标签 + 键值对占位符

业务日志使用 `[动作标签][中文事件摘要] key1={}, key2={}`，禁止无标签的纯自然语言、字符串拼接及正常事件多行 pretty JSON：

```java
// 可读消息示例；业务字段进入 ES 顶层还需第 4 章的编码支持。
log.info("[routeSelect][路由选择完成] orderNo={}, supplierCode={}, elapsedMs={}",
        orderNo, supplierCode, elapsedMs);
```

- 动作标签是稳定动作标识，中文摘要说明当前事实；不得仅因方法重命名而改变检索口径。
- 使用 SLF4J 参数化占位符。DEBUG/TRACE 下的昂贵序列化应先检查级别，不能让关闭日志仍产生大对象计算。
- 单个业务/访问/任务事件保持单行；异常堆栈归入 `stack_trace`，本地文本可保留多行堆栈。
- 耗时统一 `elapsedMs`、单位毫秒；金额优先使用已有最小货币单位字段并以名称说明单位，例如 `amountFen`，禁止用浮点数表达金额。
- 状态、结果、错误码、渠道和事件类型使用已有枚举、常量或字典；新增日志类型在框架统一定义，领域事件由所属模块维护常量，禁止散落业务字面量。
- 以上约束针对自有代码；不要求改写第三方框架的日志消息。

### 2.2 本轮不设计敏感信息策略

按本次用户明确要求，不新增、不调整敏感信息策略，不设计字段白名单、全局正则脱敏、注解脱敏或统一脱敏组件。已有业务保护、专用日志约束和异常渲染规则保持不变；本文不能作为放宽既有规则、记录此前禁止内容的依据。字段示例不构成对任何业务数据的新增记录授权。

### 2.3 异常日志分级与防重复打印规范（职责单一原则）

异常记录遵循“同一进程、同一次异常传播路径，一个终结责任边界”。访问摘要、外呼耗时摘要可以并存，但不各自打印同一异常堆栈。

1. **预期业务异常**：`ServiceException` 由 HTTP 全局处理器或后台执行入口记录单行 WARN，包含稳定错误码和必要业务定位信息，不附异常对象、不打印 ERROR 堆栈。成功返回的幂等命中不是异常，按 INFO 或高频 DEBUG 记录。
2. **继续上抛**：当前层不打印堆栈，保留 cause 与必要 suppressed 因果。确需补充步骤事实时记录一条上下文摘要，不能每层重复相同失败。不得把系统故障无因果地转换成普通业务拒绝。
3. **本层降级终结**：系统异常不再上抛、转入明确降级结果时，本层负责记录一次 ERROR 和因果链。对可恢复重试按 §2.5 和第 7 章收敛；若恢复已成功，不能再记录最终系统失败。
4. **最终失败**：未捕获系统异常由 HTTP、网关、任务或消费者最外层边界兜底 ERROR；重试耗尽或进入需要人工介入的系统失败也由其终结边界负责。不得吞异常或仅打印异常类名后丢失诊断信息。
5. **跨进程边界**：远端可记录其内部系统故障，调用端可记录自己的外呼失败及最终结果。这里的“一次”不是全分布式链路只能有一条 ERROR；日志必须体现各自责任而非复制远端堆栈。
6. **上下文建立时机**：追踪入口应在业务处理和错误处理之前建立编号，异常处理器复用它，不能等异常发生才生成另一个编号。因果链记录遵守已有业务保护规则，不要求绕过安全异常渲染输出原文。

日志级别统一为：

- TRACE：极细粒度诊断，默认关闭。
- DEBUG：开发细节、无变化轮询、批处理逐项过程，线上按包或 logger 临时开启。
- INFO：关键业务事实、正常状态推进、执行汇总、服务生命周期。
- WARN：业务拒绝、临时可恢复故障、重试和异常但尚未终结的执行分支。
- ERROR：未处理系统故障、本层系统降级终结、重试耗尽及需人工介入的系统失败。

生产与联调默认 root INFO。临时 DEBUG 必须限定 logger、记录负责人和结束时间，到期恢复；不能为了排障将全系统永久调为 DEBUG。是否业务成功不能仅靠 HTTP 200 或日志级别判断。

### 2.4 异步多线程与后台任务（Job/MQ）的 TraceId 规范

保留本节标题与路径，兼容现有代码注释链接。

1. **Spring 执行器**：monitor 自动挂载受支持的 `ThreadPoolTaskExecutor`、`SimpleAsyncTaskExecutor`，不以注册 TaskDecorator Bean 代替实际挂载验收。入队前捕获父 MDC，执行前保存执行线程完整原快照，安装父快照，在 finally 恢复原快照；原快照为空才清空。覆盖异常、线程复用、嵌套及 `CallerRunsPolicy`。
2. **装饰器组合**：job TTL、monitor MDC 和业务显式装饰器组合，不互相覆盖，不新增 job → monitor 生产依赖。common `TaskDecoratorUtils` 集中处理反射访问和实例去重；字段变化明确失败，不静默覆盖。Spring 升级须运行真实执行器兼容测试。
3. **开关边界**：monitor 的异步装饰器自动配置受 `aifuli.tracer.enable` 控制；关闭追踪时已有 job TTL 仍有效。ELK 是否推送与是否建立日志关联编号是两项不同能力。
4. **原生执行器**：`ExecutorService`、`ScheduledThreadPoolExecutor`、自建线程与使用显式 executor 的 `CompletableFuture` 须主动包装；Runnable/Callable 使用 `MdcTaskDecorator.wrap` 或语义等价的公共包装，不宣称 Bean 后处理器已接管它们。
5. **独立入口**：XXL-Job、Scheduled、Timer、Outbox、MQ 的每次独立执行生成新的无横线 UUID 作为 `traceId`，并生成本次 `executionId`。来源请求按第 5 章记录 `originTraceId`，稳定事件/任务编号跨重试保持不变；同一次执行内的异步子任务继承本次编号。
6. **入口退出**：保存原上下文、安装本次上下文、finally 恢复原上下文。已确认独占且原上下文为空的工作线程退出后应为空；禁止为了清理本次编号无条件清掉外层或其它框架上下文。轮询任务不能继承提交调度器时残留的请求编号作为每轮编号。

```java
CompletableFuture.runAsync(MdcTaskDecorator.wrap(() -> {
    // 当前执行的异步子任务继承父快照，退出后恢复执行线程原快照。
    doAsyncTask();
}), executor);
```

独立后台入口、原生执行器和来源编号持久化的全量推广是待落地要求，不属于 2026-10-04/05 的已完成修复范围。

### 2.5 外部与第三方系统（HTTP/Feign/SDK）调用日志规范

由实际调用适配层记录 `request` + 一个终止事件（`response` 或 `failure`），不要求每次调用都产生三条日志。每次实际网络尝试建立独立 `callId`；重试保留业务操作编号并增加 `attempt`。

- request：目标系统 `remoteSystem`、稳定动作 `remoteAction`、`callId`、业务操作编号、`attempt`；重要调用默认 INFO，高频轮询遵守第 7 章。
- response：收到并完成响应分类后记录 `httpStatus`（适用时）、`remoteCode`、`outcome`、`elapsedMs`。收到响应不等于业务成功，正常业务拒绝记录 WARN，不附系统堆栈。
- 收到远端系统故障响应也属于 response，结果为 SYSTEM_FAILED 或 RETRYING；级别按是否可恢复及是否最终系统失败决定，不能因已收到响应一律记 INFO。
- failure：未能正常完成调用，记录 `errorCode`、`failureStage`、`elapsedMs` 及后续处理方式。临时传输故障重试可 WARN；最终系统失败或本地系统降级终结 ERROR，堆栈责任遵守 §2.3。
- 已记录 response 的远端业务拒绝不再追加一条相同 failure；若其导致上层业务最终失败，由上层记录不同职责的最终结果。
- 耗时以 `System.nanoTime()` 等单调时钟计算，不用墙上时间差代替；每次尝试耗时与整个业务操作耗时分开记录。
- 不开启全局 Feign FULL/HTTP wire 日志来替代业务外呼摘要，不重复在 controller、service、adapter 打印同一网络尝试。

## 3. 日志分类与记录责任

### 3.1 访问日志（ACCESS）

网关记录外部请求终止摘要，服务端记录本进程处理摘要；二者是不同处理边界，不能相加为两次请求。明确采用网关边界计算外部请求数量，服务边界分析服务自身表现。

- 基本字段：`httpMethod`、`httpStatus`（确实可得时）、`outcome`、`elapsedMs`；网关补 `routeId`，服务补稳定接口动作标识。
- success/error/cancel 均须终止收尾。取消记录 `CANCELLED`，不能用默认 200 推断成功；无可靠状态时省略 `httpStatus`。
- HTTP 成功与业务成功分开；不能从未知正文强行推断业务结果，业务结果由编排层记录。
- ACCESS 的 outcome 表达本处理边界的 HTTP/执行结果，例如正常完成 HTTP 200 可为 SUCCESS，即使业务事件另记 BUSINESS_REJECTED；这是访问成功，不是业务成功。仪表盘必须标注处理边界，不跨 logType 混算成功率。
- 访问摘要不打印异常堆栈，不为收集日志改变请求/响应语义。网关目前的多行 pretty JSON、取消状态 fallback 尚需治理，不能当作已满足目标格式。

### 3.2 业务事件（BUSINESS）

由拥有业务语义的编排层记录受理、幂等命中、路由结果、状态转换、履约发起、权威结果确认、补偿和人工处理。Mapper、通用工具和无额外事实的中间层不重复记录。

- 状态转换包含稳定业务编号、`fromStatus`、`toStatus`、结果及必要错误码。
- 事务提交前仅记录“准备执行”等当时事实；“业务成功”“状态已变更”在提交确认后记录，可采用已有事务同步机制。不在事务内把尚可回滚的更新当作最终成功。
- 提交后日志丢失不回滚已提交业务；需要可靠交付的业务事件仍走已有 Outbox 等机制。
- 冲突、未知结果、人工处理等状态如实表达，不将“请求已发送”“收到响应”当作“履约成功”。

### 3.3 外呼事件（REMOTE）与后台执行（TASK）

REMOTE 遵守 §2.5。TASK 覆盖开始、结束、扫描/成功/失败/跳过数量、重试、死信及耗时；每个 `executionId` 对应一次入口执行，不把后台单次批处理计为 HTTP 请求。

批处理以汇总为主；一个调度批次可处理多个 `eventId`，每个实际外呼另有 `callId`。失败计数以处理结果为准，不能按 WARN/ERROR 行数计算。

### 3.4 系统事件（SYSTEM）与已有审计/专用通道

SYSTEM 用于启动、关闭、配置装载结果及框架设施故障；启动时可以没有 `traceId`，但必须能够识别应用和实例。

业务审计继续使用所属模块已有权威存储。普通日志写入失败不得改变业务响应；审计持久化失败是否回滚由已有业务设计决定，不能用普通日志容灾规则覆盖。

member 的 `platform-api.WUSHENG.inbound/outbound` 使用 `additivity=false`，不会自动经 root 进入 ELK。各进程须清点专用 logger、独立文件和其它采集通道，显式记录其是否进入集中式平台、由哪个 appender 承担，禁止漏采或同时父子重复采集；不改变现有专用报文契约。

## 4. 结构化字段契约

### 4.1 当前兼容字段

共享 encoder 已输出 `@timestamp`、`app_name`、`env`、`level`、`thread`、`logger`、`line`、`traceId`、`message`、`stack_trace`。沿用字段名称，避免破坏已有检索；`line` 当前按字符串编码，仅作辅助定位，不作为告警稳定维度，也不承诺异步 appender 下始终可得。

当前 `env` 来自 `spring.profiles.active`，可能是复合 profile 字符串；不能直接据此宣称环境维度已经规范化。目标改为独立部署环境值，不混入功能 profile。

### 4.2 目标公共字段与类型

以下字段适用于纳入新契约的自有事件；第三方日志仍可保留兼容字段，不要求补齐领域语义。

- `schemaVersion`：integer，新契约初始值为 1；只增加可选字段不升主版本，修改名称、类型或语义属于破坏性变更。
- `@timestamp`：ES date，应用生成的事件时间，ISO-8601 含时区；继续兼容当前 Asia/Shanghai 偏移，ES 内部按 UTC 存储，显示由 Kibana 时区控制。
- `app_name`：keyword，§1.2 的稳定应用名。
- `env`：keyword，独立部署环境 `local`、`dev`、`test`、`prod`，不使用组合 profile 值作为目标口径。
- `instanceId`：keyword，容器/部署实例的稳定标识；非容器独立启动须使用宿主标识加启动实例编号，不能只用 PID 或线程名跨机器识别实例。
- `level`：keyword，SLF4J/Logback 级别。
- `logType`：keyword，固定分类 `ACCESS`、`BUSINESS`、`REMOTE`、`TASK`、`SYSTEM`。
- `event`：keyword，领域内稳定事件名，例如 `routing.selection.completed`；由所属模块定义常量并在事件目录登记责任层、字段和结果口径。
- `outcome`：keyword，有明确结果时使用 `SUCCESS`、`BUSINESS_REJECTED`、`SYSTEM_FAILED`、`RETRYING`、`CANCELLED`、`SKIPPED`、`UNKNOWN`；开始事件省略，不能预填 SUCCESS。
- `traceId`：keyword，日志关联编号；请求/后台执行事件必须非空，启动或无执行上下文的框架日志允许为空。
- `message`：text，中文可读单行摘要，不开启聚合 fielddata。
- `stack_trace`：text，仅承担诊断检索，不作聚合维度；仅在负责记录异常的事件中非空。
- `elapsedMs`：long，非负毫秒数，终止事件需要耗时时填写，缺失不补 0。

### 4.3 场景字段

按领域需要选用，缺失的可选字段省略，不以字符串 `"null"` 或空字符串代替未知数值：

- 业务定位（keyword）：`orderNo`、`operationNo`、`supplierCode`、`errorCode`、`fromStatus`、`toStatus`；复用既有稳定业务标识，不据日志新建业务身份。
- 外呼（keyword）：`callId`、`remoteSystem`、`remoteAction`、`remoteCode`、`failureStage`；数值字段 `attempt` 为 integer，从 1 起，`httpStatus` 为 integer。
- 访问：`httpMethod`、`routeId`、稳定接口动作标识为 keyword；适用时复用 `httpStatus`。
- 后台（keyword）：`jobName`、`executionId`、`eventId`、`taskId`；`batchSize`、`successCount`、`failureCount`、`skippedCount` 为非负 long。
- 来源关联（keyword）：`originTraceId`；Agent 关联（keyword）：`agentTraceId`，仅与平台编号不同且确实可得时填写。
- 防风暴汇总：`windowMs`、`totalCount`、`suppressedCount` 为非负 long。

平台编号、订单号等高基数字段用于定位，不作为常规仪表盘 terms 分组或监控标签。动态对象键、原始 URL 等不能用作动态字段名。

### 4.4 编码、MDC 与 ES mapping 边界

- 当前 pattern provider 只编码固定字段，既不自动解析 `message` 的 key=value，也不自动输出 MDC 内的所有字段。后续编码器须显式输出事件字段，日志事件字段使用结构化参数或等价公共门面；不以解析中文消息作为长期契约。
- MDC 只承载执行作用域上下文，如 `traceId`、`originTraceId`、`executionId`。单个订单状态、耗时和错误码通过当前日志事件传递，不能长驻工作线程 MDC。
- 显式 mapping：编号、事件、状态使用 keyword，消息/堆栈使用 text，耗时/计数使用 long，状态码/尝试次数使用 integer，时间使用 date。不依赖第一条文档决定动态类型。
- 扩展字段采用受控目录；非登记字段进入日志契约之前先更新所属模块设计，防止 mapping 爆炸。保持向前兼容，对缺少新字段的旧文档明确过滤，不能补零伪造统计。
- 新模板仅自动影响新索引；存量索引类型不兼容时创建新索引/新契约视图或受控迁移，不能原地改类型。字段发布须同步升级模板、编码器、查询和仪表盘口径，并保留旧字段检索。

## 5. HTTP、RPC 与后台执行的关联协议

### 5.1 日志关联编号所有权

保留 HTTP Header `trace-id`、MDC Key `traceId` 和 ES 字段 `traceId`。该编号用于日志关联，不等同于标准分布式 span，也不能作为业务幂等键或身份凭证。

- 网关是经过网关请求的入口所有者，复用当前入口选择的非空编号或兜底生成 UUID；请求转发和响应提交保持单值，不接受下游追加另一个编号成为第二个响应值。
- 服务端继承入站编号；无上游编号时建立本次入口编号，后续业务、异常、访问和出站调用复用，不在每个方法重新生成。
- 目标优先级：当前执行作用域已有平台编号 → 入站编号（入口建立时）→ 当前 Agent 有效编号（无平台编号时）→ 无横线 UUID。不得从工作线程遗留 MDC 继承无关请求。
- 原始执行线程 MDC 在入口退出时恢复，最外层没有原上下文时清理。Servlet 当前只移除 `traceId`，完整嵌套恢复仍是目标治理项。

当前 `TracerUtils.getTraceId()` 优先读取 SkyWalking，encoder 读取 MDC；同时启用 Agent 时可能发生出站编号与日志编号不一致。目标由平台入口编号贯穿 Header/MDC/encoder，若 Agent 编号不同则另以 `agentTraceId` 关联，不能隐式替换平台编号。该行为需专门实施和 Agent 环境验证，本文不宣称已修复。

### 5.2 WebFlux 与出站边界

- 网关使用 exchange attribute 和专用 Reactor Context key 保存平台编号，不能依赖 ThreadLocal 跨调度器自然传递。
- 桥接覆盖订阅、信号、request/cancel 及异常兜底；作用域退出恢复完整原 MDC，无 Context 编号时不能使用线程遗留 traceId。
- 继续保留命名 Hook 的独立注册/撤销，不清空其它 Hook。升级 Reactor/WebFlux 必须验证信号语义、fusion 边界和性能，不承诺未经压测的吞吐。
- Feign 每次发送使用当前作用域编号并单值注入，不能复用 RequestTemplate 上一次的编号。非 Feign HTTP/SDK 只有具备受支持协议时才显式传播，不能假设所有外部服务都支持平台 Header。
- 管理端实际实现位于 `/Users/mac/Documents/codeSpace/supplychainhubweb/src/config/axios/service.ts`，已有错误响应追踪号提取。真实浏览器仍需核验跨域凭据模式下显式暴露 `trace-id`；无响应的网络超时不保证能取得后端编号，不能用虚构编号替代。

### 5.3 后台执行与来源关联

独立任务、事件投递和重试采用“本次执行”与“原始来源”分离：

- 每次独立入口执行建立新的 `traceId`、`executionId`；同一事件重试时 `eventId` 或 `taskId` 不变，本次执行编号变化。
- 持久化/发布事件时，有来源上下文则冻结 `originTraceId`；若当前已携带来源编号则保留最初来源，否则使用当前平台 `traceId`。来源确实未知时省略，不伪造。
- 消费端不得把来源编号安装为本次 `traceId`；按 `eventId` 串联执行，按 `originTraceId` 返回初始请求。
- 一个批次处理多个不同来源事件时，批次 summary 不填某个事件的来源；来源关联只写到该事件作用域，退出后恢复，避免串号。
- 持久化来源编号需在所属模块自己的事件载荷/表契约中演进，不跨库查询，不借日志传播改变消息确认、重试、锁或事务语义。未知来源的历史事件继续可执行。

## 6. 采集、环境与留存规范

### 6.1 应用侧配置与真实保障

- 复用 monitor 的共享 ELK 片段，八个进程各自提供根配置并验证依赖，不复制各自的 TCP encoder。
- ELK 开关 `aifuli.logging.elk.enabled` 默认 false；仅非本地联调/生产显式开启，目标端点由部署配置提供，不把测试端点作为生产默认值。
- 当前共享基线：RingBuffer 8192、重连延迟 5000ms、端口 50000、固定 `appendTimeout=0`，后者不提供可放大的等待配置。仅保证 TCP 队列满时非等待丢弃，不保证整个日志调用或文件写入零耗时。
- 当前 enabled=false 阻断 TCP appender 声明；local/true 依赖根配置不引用 appender，并已在受测版本观察到跳过初始化。不能推广为所有版本保证；本地必须默认关闭，特殊组合仍以完整启动测试为准。
- 本地文件保留既有 10MB 分片、30 天历史基线。异步文件队列 512、discardingThreshold=0 不等于绝对无损：队列满可能等待，进程强制退出、磁盘故障、关闭未完成均可能丢日志。
- 目标增加本地 `totalSizeCap`，由部署责任人按磁盘预算明确配置并记录；本设计不擅自设定生产容量。容量上限可以使实际保留期短于 30 天，须在部署说明和排障预期中明确。
- 本地文件与 ELK 分别验证，不以一方成功证明另一方；日志设施故障与后台状态也须可观测，但不能通过同一已故障通道无限打印错误。

### 6.2 索引、时间与生命周期

- 第一阶段兼容实际管道 `app-logs-%{[app_name]}-%{+YYYY.MM.dd}`；稳定应用名保持小写，部署不得产生未知或不合法应用标识。缺失 app_name 的 `default` 兜底事件属于配置异常，不能当作正常应用接入。
- 当前按日建索引，不存在 rollover 动作或可靠日志归档。ILM 的 30d 以索引生命周期年龄计算，删除由后台周期执行，不等于每条日志精确保留 30 天。
- Logstash `%{+...}` 按事件时间的 UTC 日切分；应用本地文件按配置日期滚动，Kibana 可显示 Asia/Shanghai。排障须考虑北京时间午夜前后与 UTC 索引日期不同，避免只查一个日期索引。
- 后续应以测试锁定所用日期 formatter 在跨年边界的行为；调整格式 token 时显式标记索引命名迁移，不能用新的样例假称现有管道已变化。
- 30 天是当前运行日志基线，不是业务审计保留期限。ILM 不替代总容量预算，超高日增量仍可能在 30 天内打满磁盘。
- 单主分片、零副本是 pro143 单节点配置；生产须根据节点数、可用性和磁盘预算批准副本/分片配置，不能默认照搬零副本。
- 不同环境共享 ES 时必须额外完成索引或集群与权限隔离后才接入；只增加 `env` 查询条件不是隔离。具体环境命名迁移归部署设计，不改变现有索引来冒充已隔离。

## 7. 高频防风暴与性能规范

- 无变化轮询默认 DEBUG，正常逐项细节不以 INFO 永久输出；批量过程优先一次执行汇总。
- 重复失败按应用、稳定事件、目标系统、错误码组成低基数聚合键，禁止每个订单或 traceId 一个永久限频桶导致内存无限增长。
- 首次失败保留诊断信息，持续失败在有界窗口输出 `windowMs`、`totalCount`、`suppressedCount` 和当前处理状态；恢复时记录恢复事件。窗口和桶容量由具体高频路径配置并通过测试，不设全系统任意统一采样次数。
- 最终失败、死信、人工介入事件仍需单独记录；其暴发时可聚合诊断堆栈，但必须保留可计数的结果和权威业务记录，不能因为抑制日志而省略处理结果。
- 限频、采样不替代业务审计和状态存储。采样后的日志不得直接用于计算未经校正的完整业务量。
- 异步入队前固定日志事件所需的上下文快照；不能发送时再读取其它线程的 MDC 或已变化的可变 DTO。
- 变更 encoder、caller data、MDC 桥接、采样和本地队列策略须验证吞吐/延迟/内存影响；性能阈值来自具体环境基线，不在文档中编造达标数据。

## 8. 查询、监控与告警口径

### 8.1 分阶段查询

当前兼容阶段先按时间范围、`app_name` 和 `traceId` 查询；Kibana 实际字段类型应从当前 mapping 核验，不能假设已存在 `.keyword` 子字段。跨进程按相同 traceId 关联，但仅按时间排序不证明因果先后，实例时钟须同步。

目标字段落地后：

- 定位一条请求：`traceId` + 时间范围，再按应用/实例分解；后台通过 `eventId`、`originTraceId`、`executionId` 接续。
- 接口表现：ACCESS 的稳定接口维度、`outcome`、`httpStatus`、`elapsedMs`；只统计终止事件。
- 外呼表现：REMOTE 按 `remoteSystem`、`remoteAction` 分组，request 不计为完成尝试，response/failure 每个 callId 一次。
- 业务故障：BUSINESS 的稳定事件、`errorCode`、状态转换；重试分析按稳定事件编号串联。
- 新仪表盘只统计满足所需字段契约的文档；旧日志缺失数值不填 0，以免降低平均耗时或失败率。

### 8.2 告警责任

应用告警关注系统失败率、外呼超时、重试耗尽、任务异常和死信积压。失败率的分子/分母必须来自同一处理边界，排除 request/start 事件；业务拒绝单独观察，不默认计入系统错误率。

日志设施告警关注 TCP 丢弃与连接故障、Logstash 写入失败和队列、采集延迟、ES 磁盘水位、索引写入失败与 ILM 异常。本地监听端口开放不证明管道成功写入 ES。

ERROR 行数不是请求失败率；不能只依赖允许丢弃的日志作为严格 SLO 或无故障证明，关键指标优先使用独立监控计数和已有权威业务状态。采集延迟使用事件时间与入库时间比较，需要先新增受控入库时间字段，当前尚不具备。

告警须明确负责人、处理入口、恢复条件、聚合窗口及阈值来源。阈值根据真实流量、容量和 SLA 确认，不以本设计中的建议冒充已上线配置。

## 9. 架构组件与变更责任

- common：`TracerUtils` 声明平台 key 和读取协议；`TaskDecoratorUtils` 处理真实执行器装饰链兼容，不承载领域事件。
- monitor：`TraceFilter`、`MdcTaskDecorator`、自动配置和 `logback-elk-appender.xml`；负责作用域、编码契约和推送配置。公共能力须可被八个独立进程复用，不引入业务 server/DAL。
- rpc：`TraceRequestInterceptor` 负责 Feign 出站编号，不为补日志隐式注册更多服务依赖。
- gateway：`TraceWebFilter` 拥有网关编号与响应头；`GatewayReactorMdcConfiguration` 管理命名 Hook；`AccessLogFilter` 负责访问摘要；网关异常处理器负责最终异常。
- 各业务模块：定义稳定事件常量/目录，记录本模块事实与事务结果，维护自身专用 logger、后台入口及审计。
- 部署与运维：Logstash 管道、ES 模板/ILM、Kibana 数据视图、环境/实例元数据、容量预算与告警。

改动追踪、启动、RPC、线程池、日志依赖或自动配置须评估八个进程影响。尤其 movie-server 使用基础 Spring Boot 组件，接入 monitor 时不能顺带引入其它模块容器或改变其安全、数据源和任务执行语义。

## 10. 分阶段落地与验收门禁

### 10.1 阶段一：统一准则与全进程接入

完成本规范及 ELK 设计的保障边界修订；逐个补齐五个尚未同等接入的进程；核验依赖、独立启动、应用/实例元数据、本地编号、关闭推送静默、专用通道路由。现有三个试点也须运行完整配置回归。

阶段一不宣称所有历史消息已满足新字段契约，不以更改文档代替 Agent 优先级修复或后台推广。

### 10.2 阶段二：字段、编号与关键事件

同步扩展编码器、mapping 和查询；实现平台编号唯一读取协议与 Agent 分离；推广原生执行器、后台入口和来源关联；优先结构化访问终止摘要、关键业务状态、外呼结果与执行汇总。逐条治理事务提交前假成功、取消假成功、重复堆栈和防风暴路径。

字段升级涉及旧索引兼容与新模板生效，来源持久化涉及各所属模块的独立设计/迁移。必须在实现前细化并评审对应变更，不在本设计阶段擅自改表、重启或部署。

### 10.3 阶段三：自动化与真实环境验收

自动化至少验证：

1. 解析真实 encoder 输出的顶层 JSON，验证编号、字段类型、单行消息及缺失值语义；不能仅断言 message 含编号。
2. HTTP/Feign/Agent 的 Header、MDC、encoder 编号一致；异常、并发及响应提交保持单值且不串号。
3. WebFlux 成功/异常/取消与多调度器；命名 Hook 生命周期、订阅/request/cancel 和信号语义。
4. Spring MDC/TTL/业务装饰器组合，原生执行器、CallerRuns、嵌套、异常、线程复用及 finally 恢复。
5. 后台多次执行编号不同、事件编号稳定、来源不丢失或伪造，批次多来源不串号。
6. 业务拒绝无 ERROR 堆栈，上抛链只在终结处记录，外呼结果不重复，提交回滚时无最终成功日志。
7. 八进程真实根配置启停和独立启动；禁用观察窗口无连接/重连且无残留线程，队列满时 TCP 入队不等待。本地队列、磁盘/关闭故障另行验证其实际边界。
8. mapping、模板新索引生效、字段类型兼容、跨日期/跨年查询、ILM 托管及重复失败限频/恢复。

供应链必须按 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁` 运行适用的 `*Test`、`*IntegrationTest`（真实 MySQL、Liquibase、事务、锁和并发）、`*ContractTest`、`*ArchitectureTest`；不得将仅跳过其它模块单测理解为跳过供应链门禁。共享框架/网关的直接受影响日志与追踪回归也须按变更范围验证。

真实联调逐进程采集事件，验证请求 → 网关 → 业务服务 → 已显式注册 RPC → Logstash/ES；不假设一次请求必然经过全部八个进程。验证前端响应头、并发隔离、后台重试关联、设施故障行为和留存。完整步骤及证据维护到 `docs/集成测试.md` §8.12「全系统日志规范验收清单」。

验收证据须包括受测提交/制品、配置组合、命令、原始输出和范围；测试、配置审阅、实机联调分别记录。既有验证记录保留历史范围，不能将新设计标记为已全绿或已部署。
