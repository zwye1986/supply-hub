# 仓库服务形态约束

- 供应链管理端前端仓库位于 `/Users/mac/Documents/codeSpace/supplychainhubweb`。排查页面展示、前端请求或判断是否需要前端配套改动时，应检查该仓库的实际实现。
- 本仓由 mall-pro 拆分而来，`mall`（含 `product`、`promotion`、`trade`）、`pay`、`erp` 模块已删除，仓库内不存在 `MallServerApplication`。不要沿用「商城三模块聚合为同一进程」的旧假设排查问题。拆仓范围见 `docs/superpowers/specs/2026-09-07-supply-hub-repo-split-design.md`。
- 可独立启动的进程共 8 个：`GatewayServerApplication`、`AifuliServerApplication`（仅聚合 system + infra，member 依赖已注释）、`SystemServerApplication`、`InfraServerApplication`、`MemberServerApplication`、`SupplyChainServerApplication`、`TaobaoFlashChannelApplication`、`MovieServerApplication`。
- `aifuli-module-supply-chain-server` 是独立进程：独立数据源 `supplyChainDataSource`、独立事务管理器 `supplyChainTransactionManager`、独立库（表名统一 `sc_*` 前缀，表间无物理外键）。供应链代码不得访问 `system_*`、`member_*` 等其它模块的表；管理端菜单与数据字典由 `script/deploy` 下的部署 SQL 写入 system 库。
- 供应链出站调用走显式注册的 Feign：当前只注册会员的 `MemberProfileResolveApi`、`MemberSsoTokenApi`（`framework/rpc/config/RpcConfiguration`）。新增外部服务依赖必须显式注册，不得依赖包扫描隐式引入。
- 淘宝闪购渠道 `aifuli-channel-taobao-flash-server` 与电影 `aifuli-module-movie-server` 各自是独立进程与独立库，不得并入供应链容器。
- 供应链模块边界由 `SupplyChainModuleArchitectureTest` 以 ArchUnit 强制（Facade 不得依赖 `..dal..`、`routing.domain` 不得依赖 Spring/MyBatis、`border` 不得访问账户与账本 `dal`、管理端每个 HTTP 映射必须有 `@PreAuthorize` 等）。调整分层或依赖方向前先读该测试。
- 修改启动、RPC、事务、缓存或安全配置时，必须评估对应进程的整体影响，并确认是否触发上述架构约束测试。

# 开发规范（Cursor / Codex / ChatGPT 共用）

以下规范适用于本仓库所有 AI 辅助开发（含 Cursor、Codex、ChatGPT）。冲突时以用户当次明确指示为准，否则必须遵守。

## 1. 设计先行

- 改实现方案或调整已有设计决策前，先更新对应详细设计文档（如 `docs/superpowers/specs/` 下相关文档），再改代码。
- 禁止「先写代码、事后补文档」；文档与实现应同一次变更内对齐。

## 2. 语义不清先确认

- 需求、边界条件、领域语义或接口契约不清时，先停下来向用户确认，再写代码。
- 不得用猜测填补关键业务语义。

## 3. 联调 / 集成测试文档

- 联调步骤、集成测试场景、前置条件、验证点与排障记录，写入并持续维护 `docs/集成测试.md`。
- 新增或变更可联调流程时，同步更新该文档。

## 4. 重要注释与设计链接

- 重要类、方法、流程须写详细中文注释，说明职责、不变量与关键决策。
- 注释中用文档路径 + 章节标题/锚点链接到详细设计对应章节（例如：`docs/superpowers/specs/xxx.md#章节名`）。

## 5. DTO 与 DDL、TODO

- DTO 字段注释必须与 DDL `COMMENT` 语义一致（同一业务含义用同一表述）。
- 未完成实现处使用 `TODO`，并附带指向设计/任务文档的链接；禁止无上下文的裸 TODO。

## 6. 全系统日志规范（含异常与链路关联）

- **强制规范入口**：全系统日志规范是本开发规范的组成部分，适用于全仓人工与 AI 辅助开发及全部 8 个独立进程。新增或修改日志、异常处理、线程池、追踪、外呼及 ELK 配置前，必须阅读并遵守 [全系统日志记录、链路关联与集中式日志规范](docs/superpowers/specs/2026-10-04-logging-standardization-spec.md)（2026-10-08 修订）；本节仅作执行摘要，详细规则以该文档为唯一来源。
- **规范与实现分开**：现有能力和待落地目标按详细规范 §1.3、§10 区分。规范生效不表示八进程接入、结构化字段、Agent 编号分离或后台来源关联已实现；相关变更须设计先行并通过门禁，禁止只改文档就宣称已上线。
- **格式与字段**：业务日志使用 `log.info("[动作标签][业务简述] key1={}, key2={}", val1, val2)`，动作标识稳定、消息单行、参数占位符，禁止字符串拼接及正常事件多行 pretty JSON。目标事件按 ACCESS / BUSINESS / REMOTE / TASK / SYSTEM 分类；字段及 ES 类型遵守详细规范第 4 章，耗时统一数值型 `elapsedMs`。message 内的 key=value 不等于可聚合的顶层字段，不得以此声称满足结构化契约。
- **级别与异常责任**：正常关键事实 INFO，细节及无变化轮询 DEBUG，业务拒绝及可恢复故障 WARN，系统降级终结或最终系统失败 ERROR。禁止吞异常或丢失因果；`ServiceException` 由 HTTP/后台入口记录单行 WARN，无 ERROR 堆栈；继续上抛的中间层不打堆栈。同一进程、同一次传播路径由一个终结边界记录异常因果，不逐层重复失败摘要；保留已有业务安全异常渲染规则。
- **业务事实与访问结果**：由拥有业务语义的编排层记录关键入参、状态转换和结果，Mapper/工具层不重复记录；“业务成功”须在事务提交确认后记录。访问日志覆盖成功、异常及取消，取消不能默认算成功；HTTP/访问成功不等于业务成功，ERROR 行数不等于请求失败率。日志不替代订单、账本或审计的权威存储。
- **关联协议**：沿用 Header `trace-id`、MDC/ES `traceId`，由入口建立、下游继承、响应保持单值。目标要求 Header/MDC/encoder/出站编号一致；Agent 编号不同时单独关联，不能隐式替换平台编号。网关使用 Reactor Context 与短暂 MDC 作用域，不依赖线程遗留值；后台独立执行生成新的 `traceId`、`executionId`，以 `originTraceId` 关联来源、以稳定 eventId/taskId 串联重试，不伪造历史来源。
- **异步作用域**：受支持 Spring 执行器使用 `MdcTaskDecorator`，与 TTL/业务装饰器组合，不覆盖已有装饰链；原生线程池、Timer/Outbox 和显式 CompletableFuture executor 须主动包装，不能假设自动覆盖。提交时捕获父快照，执行前保存原快照，finally 恢复原快照，原为空才清空；覆盖嵌套、异常、线程复用及 CallerRuns，禁止无条件清掉调用者上下文。
- **外呼责任**：HTTP/Feign/SDK 在实际调用边界记录 request + 一个终止事件（response 或 failure），每次实际尝试有 callId、attempt、目标/动作和终止耗时 `elapsedMs`。收到响应不等于业务成功，正常拒绝不一律 ERROR；重试及堆栈责任遵守详细规范 §2.3、§2.5，不在多个层重复同一调用，不启用全局报文日志替代摘要。
- **防风暴**：循环、轮询、消费和回调按低基数事件/目标/错误码聚合限频，首次保留诊断、持续输出计数汇总、恢复输出恢复事件；限频桶必须有界，最终失败/死信保持可计数的结果及权威状态。采样不替代业务审计，也不能直接作为完整业务量统计。
- **采集与保障边界**：各独立进程复用共享 ELK 配置，以实际 `spring.application.name` 标识应用；清点 additivity=false 等专用通道，避免漏采/重复。TCP 固定 `appendTimeout=0` 仅保证满队列入队不等待，不保证整个日志调用零耗时；本地文件不承诺无损。ELK 默认关闭，本地静默须完整配置验证；30 天 ILM 不等于可靠归档，零副本仅为单节点测试配置，环境字段不等于环境隔离。
- **验证与证据**：涉及日志/追踪变更须按详细规范 §10 验证真实顶层 JSON、类型、异常收敛、上下文恢复和完整根配置；涉及供应链按本规范第 9 条运行适用自动化门禁，共享框架/网关的直接受影响回归同样须验证。联调与人工验收维护 `docs/集成测试.md` §8.12，配置审阅、自动化、实机结果分开记录；不得以本地通过替代真实八进程或前端联调。
- **敏感信息范围不变**：本轮不设计或调整敏感信息策略，已有业务保护规则保持不变；账号密钥与通讯私钥严禁打入日志正文。规范字段示例不构成新增数据记录授权。

## 7. 禁止业务魔法值

- 业务状态、类型、错误码、渠道标识等不得散落字面量；使用枚举、常量或已有字典/配置。
- 仅对无业务语义的技术字面量（如 `0`、`1` 作下标）可例外，且应显而易见。

## 8. MyBatis Plus 查询 / 更新写法

- 不推荐在 Mapper 上新增 `@Select`、`@Update`、`@Insert`、`@Delete` 等注解 SQL。
- 优先使用 MyBatis Plus 的 `LambdaQueryWrapper` / `LambdaUpdateWrapper`（或等价 `lambdaQuery()` / `lambdaUpdate()`），以方法引用绑定列，避免字符串列名漂移。
- 仅当 Lambda 无法表达（复杂多表、数据库方言特性、已存在且稳定的 XML）时才使用 XML Mapper；新增注解 SQL 须有明确理由。

## 9. 单元测试范围

- 仅跳过 `aifuli-module-supply-chain` **以外**模块的单元测试；不得将「跳过其它模块单测」理解为跳过全仓库单测。
- `aifuli-module-supply-chain` 须按 `docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁` 编写与运行测试：
  - `*Test`：状态机、金额不变量、路由策略、错误映射等纯逻辑单测。
  - `*IntegrationTest`：真实 MySQL、Liquibase、Mapper、事务、锁与并发（Maven profile `supply-chain-integration`）。
  - `*ContractTest`：RPC/HTTP 契约一致性。
  - `*ArchitectureTest`：模块依赖与数据所有权边界。
- 第 3 条的 `docs/集成测试.md` 记录联调与人工验收步骤，不替代上述供应链自动化测试要求。

## 10. Git 提交信息

- Git 提交标题与正文必须使用中文描述，准确说明本次变更的业务目的或技术结果。
- 必要的代码标识、模块名、协议名与命令可保留英文；禁止只使用无语义的英文模板或编号作为提交说明。

## 11. 可视化与交付规范（OpenCode Desktop 适配）

- 当前运行环境支持 OpenCode Desktop。系统不具备 Cursor 专有的 `cursor/canvas` 侧边栏编译与渲染管线。
- 需要展示可视化、架构设计、数据统计或分析结果时，**严禁生成 `.canvas.tsx` 文件**或使用 `cursor/canvas` 组件库。
- 可视化请优先采用以下形式：
  1. **Markdown 表格 / Mermaid 图表**：直接在对话中输出，OpenCode 会话窗口原生支持高亮与图表渲染。
  2. **独立单文件 HTML**：如需独立看板或离线交互图表，输出内嵌 CDN（如 Tailwind CSS、ECharts、Chart.js）的 `.html` 文件，支持在本地浏览器直接双击打开查看。
