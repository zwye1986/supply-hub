# R3-04 只读解阻分析与设计修订边界

日期：2026-10-08（设备时间）。源码基线：`103db7f7c9eb1d3643a1a6b977e050264b3eee2e`；分析开始时工作区与暂存区为空。

## 1. 本轮授权与裁决

用户确认下一阶段优先处理目录商业发布前置，并明确允许恢复 R3-04 **只读分析与设计修订**。编码、测试（包括微型运行探针）、共享配置改动、部署与真实数据库操作继续暂停。本轮只读取仓库及本地依赖源码、解包第三方源码到仓外临时目录，并更新文档；没有编译、启动服务、执行 Maven、连接数据库或调用供应商。

当前状态为 `READ_ONLY_DESIGN_REOPENED_IMPLEMENTATION_PAUSED`，不是设计预审通过、实施放行或验收通过。原暂停决定、预审失败及历史运行记录全部保留。主规范仍为 `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md` §7.2、§7.2.1、§8.2～§8.2.3、§9；本文不替代或放宽这些契约。

**有限结论：现有冻结范围内，仍没有已证明可以闭合 B03 的完整方案。** 源码核对支持继续保留早期启动屏障、维护隔离及有界读取的设计候选，但不能因为这些候选存在，就宣布全部 R3-04 可实施。下一决策应围绕“是否允许研究新增可证明归属的池级诊断接口”，而不是重复已被拒绝的风险豁免或跨池 class-logger 改造。

## 2. 当前实现的明确缺口

### 2.1 正常启动仍自动回填，Runner 不是屏障

来源：server 模块 `infrastructure/timer/TimerLegacyMigrationRunner.java`、`TimerEngineLifecycle.java`、`infrastructure/outbox/OutboxDeliveryEngine.java`、`border/fulfillment/application/RechargeFrequencyRecoveryConfiguration.java`。

- Runner 使用最高 `@Order`，先调用 `backfillLegacyTasks()` 再核验；没有区分正常启动只读核验和受控维护回填。
- Timer、Outbox 使用 `SmartLifecycle.start()`，启动前没有强制账本准入断言。
- 直充补偿通过 `@Bean(initMethod="start")` 在 Bean 初始化时建立调度器，不等 Runner。初始延迟不是屏障，核验阻塞时仍可能越过延迟开始扫描。
- 本地 Spring Boot 3.5.14 `SpringApplication.run()` 先 `refreshContext()`，后 `callRunners()`。Runner 排序只约束 Runner 之间，不覆盖前述初始化与生命周期启动。

因此，§8.2.2 的元数据检查→只读数据核验→真实实例准入候选仍有必要；实际支持的 Bean 图、全部 JDBC 写面与无循环性尚未运行验证。

### 2.2 旧任务协议与双向完整性未落实

来源：server 模块 `infrastructure/timer/TimerLegacyMigrationService.java`。

- 回填查询取得 `payload_version`，INSERT 却硬编码为 1，未复制源真实版本。
- 现有核验未验证双方 payloadVersion 和比较协议；Timer JOIN 用数据库字符串等值，不能替代 UTF-8 身份逐字比较。
- 正向 JOIN 与残留 RESERVED 检查不能发现全部孤儿 BOUND；没有完整反向核验。
- 回填无待处理行时直接返回，不能证明已有槽全部合格。
- 无分页的 `queryForList` 先物化全部 JSON，再调用编码器。编码器六预算不能追溯限制已经发生的 JDBC 分配。
- 核验由多个独立 JdbcTemplate 查询组成，未显式建立统一最外层只读 RR 快照。
- 异常日志输出业务文本和完整 Throwable；`SystemIntegrityException` 持有 cause，当前没有安全的框架最终呈现路径。

这些是静态源码事实，不是本轮运行复现。修正继续受源表零修改、真实版本、双向完整性、八状态、六预算及 A39/DDL 保护约束。

### 2.3 既有测试不能覆盖上述完整契约

来源：`infrastructure/timer/TimerLegacyMigrationIntegrationTest.java`。

测试导入的是窄 Fixture，没有生产 Runner、双引擎或补偿循环；七状态夹具都使用 payloadVersion=1，源快照未包含全部时间、计划、租约及错误字段。六个历史用例可以作为原范围证据保留，不能替代生产生命周期、非1版本、MANUAL、完整源字段、孤儿 BOUND 和框架日志负例。

本轮未重跑这些测试，也没有将发现改写为新测试失败。

## 3. B03 重新核对：可见信息不等于可靠归属

主来源：本地 `com.alibaba:druid:1.2.28` sources.jar 的 `DruidDataSource.java`、`DruidAbstractDataSource.java`、`support/logging/LogFactory.java`、`SLF4JImpl.java`。

### 3.1 后台创建线程

- `DruidDataSource.java:83` 为类级 `private static final Log LOG`，不是每池 logger。
- `:1000` 附近以 `"Druid-ConnectionPool-Create-" + System.identityHashCode(this)` 命名创建线程，实例存储在 private 字段 `createConnectionThread`，随后立即 start。
- `CreateConnectionThread.run()` 在调用 `createPhysicalConnection()` 后的 SQLException/RuntimeException/Error catch 内打日志（`:2795`、`:2822`、`:2826`）。异常发生在借用者异常返回之前，调用者事后捕获无法撤销已经输出的事件。
- 同步调用线程的 ThreadLocal/MDC 不会自动成为后台创建线程的可靠业务归属。
- 线程名包含池 hash 是可观测线索，但 `identityHashCode` 不保证唯一，线程名可以改变且不是不可伪造身份；仅前缀或字符串匹配无法证明全部其它池原日志不变。
- 配置 createScheduler 后还有 `CreateConnectionTask` 路径；没有授权改变调度参数，不能通过关闭/替换后台建连来补齐安全证明。

### 3.2 日志正文与 URL 局部脱敏

`sanitizedUrl()`（`:3880` 附近）确实对若干 MySQL 密码参数做替换，不能忽略这项既有保护。但同一事件仍携带原 SQLException；局部 URL 密码替换不是完整 Throwable/任意凭据安全保证，也不提供 R3 或池身份。不能把它写成“Druid 已保证日志安全”，也不能声称本轮发现了实际秘密泄漏。

### 3.3 日志后端与最终异常呈现

- Druid `LogFactory` 可受 `druid.logType` 影响，并按可用实现选择后端；只为 Logback 设计机制不能自动覆盖 JUL 等实际选择。本轮没有运行有效配置核验。
- 本地 Boot BOM 3.5.14 管理 Logback 1.5.32/SLF4J 2.0.17；根 POM 插件属性仍有 Boot 3.5.9。这是版本来源区分，不以一个 POM 属性冒称有效运行 classpath。
- Logback `ThrowableProxy` 会读取 `getMessage()`、`getCause()`、`getSuppressed()` 并构造递归代理；只重写顶层 `toString()` 或 `printStackTrace()` 不能保证 cause 安全。
- `TurboFilter.decide()` 收到 marker、logger、level、format、params、Throwable，不直接得到 Druid 池对象或 R3 调用身份。它允许 ACCEPT/NEUTRAL/DENY，不提供修改原事件的返回值；替换呈现须另设计安全事件、去重、重入与保留原因机制。
- Boot `handleRunFailure()` 在 report 后仍返回原 RuntimeException或含原 cause 的异常；单独的 `SpringBootExceptionReporter` 不覆盖更早 Druid 日志、refresh 日志及所有 CLI 未捕获出口。

## 4. 三种方向的范围判断

1. **继续使用现有公开日志信息，无运行装配变化。** 范围最小，可处理明确带 R3 标记的同步事件；当前不能覆盖未带标记的后台池事件，故不能关闭 B03。
2. **以线程名、URL、异常内容或类 logger 推断来源。** 无可靠对象身份/业务身份；可能碰撞或改变其它池、非R3事件。不得作为获审设计，也不再提出原跨池呈现方案。
3. **先研究对象级来源绑定接口。** 方向是让诊断进入最终呈现前携带可证明的来源，并且保持其它池及非R3同调用点行为不变。是否存在可用公开扩展点、是否必须变更原供应链 DataSource 的构造/装配、如何覆盖 init/Liquibase/create/reconnect/cleanup、如何保留因果与清理所有权，均尚未定案。

推荐仅考虑第3项的**设计可行性研究**，但它不是现有20路径候选的自动扩展授权。原 DataSource factory 方法体、池参数、共享日志和其它模块仍保护；不使用反射读取私有线程、不换池、不加代理/子类或驱动包装试运行，不以第三方源码具有 protected 方法就宣告可行。

在新增接口方向获明确授权之前，本轮止于本记录，不继续设计跨出保护范围的具体实现，不要求用户接受原 A/B 取舍。

## 5. 与业务主链的关系

- Task A 身份关联和 R3-03 最新有限验收不重做；华昇 A1～A5 通信实现不等于生产履约已启用。
- R3-04 完整解阻→剩余 B0 可靠性/预算/恢复等前置完成→B1 真实商业镜像与可用性→C 当前项目准入和冻结回放，仍是主链推进方向；R3-04 单项通过也不能自动宣布整个 B0 已完成。
- 生产 Outbox 商业通道当前失败关闭；BCS Schema及生产者/消费者契约需独立裁决，不因日志研究而启用。
- 不直接 SQL 补 LST、价格或 AVAILABLE，不让 QNM 基准回归替代既定华昇目标订单。

## 6. 后续必须验证的场景（全部 NOT_RUN）

仅登记需求，不构成测试授权或完整新矩阵：

- 生产 Bean 图缺账本/错资源时，在 factory、补偿、引擎及入站写面之前拒绝；阻塞超过补偿初始延迟仍零扫描/零业务写入。
- 正常启动只读，维护窄上下文不启动引擎/补偿/HTTP/Feign；维护失败整批回滚与关闭所有权。
- 非1版本、八状态、合法计划、身份字节差异、孤儿 BOUND/RESERVED、未知协议、完整源字段不变。
- JDBC物化前单对 JSON 有界、统一 RR、有序游标、末页预算与SQL失败。
- 若将来获审诊断接口：冷池、init/Liquibase、后台create/reconnect、失败关闭/清理、SQL异常cause/suppressed、INFO/DEBUG/CLI、有效后端与后端负控制；同进程其它池及本池非R3事件保持原行为。不得仅用合成无秘密标记证明实际安全。

真实维护控制者、全节点静止/排尽与同版本交接仍未确定；本地验证不能代替部署授权或集群证据。

## 7. 来源冻结与声明

本地依赖源码 SHA-256：

- Druid 1.2.28：`42ba7b7fc7e1913fbcac5dcea5a94093765e251b135630b06c7d9a606ad514da`
- Spring Boot 3.5.14：`9d4adfb489dd34f750e9ca2b87c209df6c39b90e0bc308c4efab20bfa33a6260`
- Logback classic 1.5.32：`be553204e18ce1f628c535bb063a1e611182854d15154382b00afd4968ce591f`

本轮结论来自直接源码读取，不冒称已读取全部旧仓外预审产物，不复用历史 passed 为本轮通过。无独立设计预审、无新运行结果、无实施验收、无 Git 暂存/提交/推送。
