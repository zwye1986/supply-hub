# R3-03 重开修正实施计划：数据库 Collation 等价分组／确定性排序与单次 Flush 完整守卫

日期：2026-10-06（工具取得）。状态：**DESIGN_PENDING_INDEPENDENT_PRE_REVIEW**。本文件是待独立预审的实施分片候选，不是实施完成、预审 passed 或验收；不运行门禁、不写生产/测试代码。

规范：主设计 `docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md` 的 §7.2(5)、§7.2.1(1–6)、**§7.2.2（2026-10-06 修正替代文本，R3-03 唯一规范性来源）**、§9「追加/调度唯一键」「间接锁环」两行中 R3-03 部分，以及本文件。旧版 §7.2.2 算法文本已废止，`deleg_23a851ff` 时代实现与预审记录仅为历史，不得沿用放行修正候选。完整条款矩阵与 RED 负例以仓外 `r3-03-repair-20261006/clause-matrix.json` 为准（程序核计数），本文件不以其摘要替代。

**2026-10-06 rev2 修订（首轮独立预审 35 passed／11 failed／0 unknown，按 BLK-1/2/3 最小修订方向与用户裁定 BLK-2 选 B 修订 §3–§8；阶段仍 `DESIGN_PENDING_INDEPENDENT_PRE_REVIEW`）：** ① BLK-1 两条冻结 SQL 改逐键返回行＋`DENSE_RANK() OVER (ORDER BY 已带 collation 派生列)` 组号＋原始精确列 `k*_orig` 回传定位，废弃 GROUP BY（仍每 flush 每类恰 1 条、参数化、零 InnoDB 访问）；② BLK-2 不新增生产 `appendLocalTechnical` 通道：生产 collector 对 Outbox 槽执行保持失败关闭（零 DAL 前 `SystemIntegrityException`、不静默跳过、整事务失败），协议由仅 src/test 同模块反射桥驱动真实 main 底座证明，`OutboxEventRepository.java` 零修改，商业接线时再裁决生产执行通道（R3-02O-S 冻结边界文本不改）；③ BLK-3 冻结锁定 SELECT 词法规则与 7 个既有 Mapper XML 语句分类表及假阳/假阴负控制；④ N1 triggerFlush 事实表述按本地 spring-tx-6.2.18 源码勘误。未实施、未运行测试、未验收；待再次独立预审。

## 1. P0 依赖校准

### 1.1 原冻结强制契约（不因补核放宽）

- §7.2(5)：新 B 生产者在真实写点记录事务内刷新意图；整批/级联结束后只 flush 一次；中间意图按契约合并但保留最终真实版本；flush 后不再取得新的领域行锁；嵌套方法不提前 flush；最后一行/最终版本不漏；flush 后新增领域锁或意图使整事务失败关闭。
- §7.2.1(6)：Outbox 追加账本在前、Timer 调度账本在后；表内按其业务 unique 列顺序比较完整键；数值列按数值、字符串按源列实际 `utf8mb4_0900_ai_ci` 等效排序/等价关系；不能用 Java 默认 locale 或把外部原始值大小写/Unicode 归一化代替；收集器在取得首个账本锁前完成身份集合、等价合并/异载荷检查及排序；排序键可由无实体表的参数化 SQL 按同 collation 确定，不提前建立领域 RR 视图；锁后发现新意图必须整事务失败；自增空洞正常。
- §7.2.1(1–5)：单行参数化 `INSERT ... ON DUPLICATE KEY UPDATE id=id`（省略 id、重复分支仅 id=id）→ 完整业务键 `SELECT ... FOR UPDATE` 当前读 → RESERVED 且 nonce 匹配才首次 → 源严格 INSERT1 → 绑定 CAS1 → BOUND；RESERVED 残留由提交守卫拒绝（经已闭环 R3-02O 底座与 R3-02T Timer 服务复用，不重写）。
- §7.2.2 协议意图：最外层供应链写事务归属、单次显式 flush、双向 beforeCommit 守卫、afterCompletion 清空、等价合并保留最终元数据、异 target/version/payload 拒、flush 后领域写/锁守卫、整批回滚保留完整 cause。
- §9 两行中 R3-03 部分：真实 MySQL 多槽排序/合并、第二槽及后续槽/源/绑定失败整批回滚、RESERVED/flush 提交守卫；不能仅靠 mock 锁序或死锁重试。
- F10（旧计划索引）。

### 1.2 后加候选/已闭环依赖（复用，不重验，不修改其冻结内容）

- R3-01：`LedgerJsonEncoding.canonicalBytes`／`legacyMatches`（Q-R3-01-01 资源预算）。
- R3-02O：`TrustedOutboxAppendCore` 私有底座（排他建槽/当前读/nonce/源核验/CAS/TransactionGuard）＋ D1 advisor 旧 `appendLocal` 隔离（14 调用点冻结表不变）。
- O-S07：`SupplyChainTransactionObservationManager` 装饰器与 Scope 在途 status 登记（外层 owner 判定的依据）。
- R3-02T：`DatabasePersistentTimer.scheduleOnce` 账本路径（Timer 意图执行复用）。

### 1.3 不属于本片（不改、不依赖、不提前启动）

- R01/R02 预算包装（§7.2.1 冻结规范，实施片 R3-05A/B–07 未开始）：本片不接入预算、不动 `BudgetContext`、`BudgetRoutingDataSource` 与包装类。
- V4A 完整事务重跑：属 R3-08A/B；本片不动 `LocalLedgerTransactionalWorkerCoordinator`、`LocalLedgerDeadlockDetector`、`LocalLedgerDeadlockException`（字节不变），不新增 retry。
- 真实商业接线（嵌套 saveBuyer/初始化/项目级联）：§9 明文留后续 B 接线验收；本片仅技术领域 fixture 证明同构收集/flush 边界。
- R3-04（`PAUSED_BY_USER_BOUNDARY_UNRESOLVED`）、R3-05/06/07/08、B 商业启用：全部不放行。

### 1.4 部署前置

无。R3-03 无新增 DDL/迁移/配置项/部署步骤；两账本表由 061 既有（A39 保护，不追加 changeSet）；不需要旧 Timer 回填（新键直接写账本，跨事务重复走既有 BOUND 重复路径）。

### 1.5 隔离验收声明

R3-03 可独立隔离验收：运行依赖全部为已闭环项（1.2）＋ 061 既有表＋ `SystemIntegrityException`。R3-03 验收通过不解除 R3-04 暂停、不启动 R3-05+、不启用商业；1213 单槽反例保留；一次 A/Z 并发成功只证排序，完整事务重跑属后 08，不擅改 R3-03 为所有死锁绝无。

## 2. 实际实现追读结论（HEAD d1762b6，本阶段已核）

真实调用链（生产，全部无业务调用者）：

```
(无生产调用者) LocalLedgerTransactionalWorkerCoordinator.executeWorker
                  └─ TransactionTemplate(supplyChainTransactionManager=SupplyChainTransactionObservationManager 装饰器)
                     └─ LocalLedgerWorker.execute (仅测试实现) → collector.record* / collector.flush()
(无生产调用者) LocalLedgerIntentCollector
   recordOutboxIntent/recordTimerIntent → TransactionContext(TransactionSynchronization).record*
   flush → context.flush(): 排序(Java CASE_INSENSITIVE_ORDER) → outboxEventRepository.appendLocal(旧严格INSERT)
                                → persistentTimer.scheduleOnce(R3-02T 账本路径)
OutboxEventRepository.appendLocalIdempotent → 直接抛 SystemIntegrityException（B 入口失败关闭，无生产调用者）
OutboxEventRepository.TrustedOutboxAppendCore.append → 仅 src/test 反射桥（TrustedOutboxAppendCoreBridge）可达，无生产调用者
```

偏差清单（每条对应 clause-matrix 一条 RED 负例）：
1. Java `String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo)` 排序，非数据库 collation（大小写/重音/组合字符权重序不保证一致）。
2. 键为 `aggregateType+"|"+aggregateId+"|"+aggregateVersion+"|"+eventType`／`taskType+"|"+idempotencyKey` 拼接，复合键分隔碰撞可错误合并。
3. 同键合并仅 `isPayloadEquivalent`（canonicalBytes），不比对 targetName/targetType/payloadVersion/protocol；异 target/version 被静默覆盖而非拒绝。
4. 合并保留“最后 put 覆盖”，但元数据字段（traceId/occurredAt/fireAt）随记录整体替换，未定义“最终元数据”契约且未验证。
5. Outbox 意图 flush 走旧 `appendLocal` 严格 INSERT：无账本底座、无 RESERVED 守卫、无幂等；与 §7.2.2“私有底座追加”不符。
6. `requireContext` 仅查 `isActualTransactionActive`；不核 synchronization/read-only/holder 归属/rollbackOnly/观测 Scope → 错 manager、只读、rollbackOnly、外层 scope 不明均可通过。
7. `TransactionContext` 覆写 `TransactionSynchronization.flush()`：状态机可被 `TransactionSynchronizationUtils.triggerFlush()` 重入。已核本地 spring-tx-6.2.18-sources.jar：`AbstractPlatformTransactionManager.prepareForCommit` 为空实现，`triggerFlush()` 为 public 静态方法且 6.2.18 内**无框架内调用者**（原「仅 JtaTransactionObject.flush() 调用」为旧版描述）；MyBatis-Spring 3.0.5 不调用 triggerFlush——当前 JdbcTransactionManager 路径不被偷触发，但 triggerFlush 为 public 静态方法，任何未来框架版本/外部代码调用会令显式 flush 后提交失败（二次进入抛 SystemIntegrityException）。
8. flush 后守卫仅 record API 状态机（record* 拒绝）；无真实领域 DML/行锁守卫。
9. `beforeCommit` 仅查“非空意图且未 FLUSHED”；FLUSHED 后新领域写不被拦截。
10. 槽执行失败异常直接上抛（未包裹、不局部重试）——本点与新契约一致，保留。
11. `afterCompletion` 将状态重置回 COLLECTING 而非清空解绑——线程复用后旧上下文残留风险。
12. `OutboxIntent.payload` 为可变 `Object`/`JsonNode` 引用，record 后调用方修改原对象会污染已缓冲意图。

## 3. 修正设计要点（完整文本见主设计 §7.2.2 修正替代文本）

1. 收集窗口与最外层 owner 能力：record/flush 全量资源守卫（activity/sync/read-only/holder 同步与 rollbackOnly/代理 status/观测 Scope 存在且未 completed/在途 status 全非 rollbackOnly），错 manager/只读/rollbackOnly/外层 scope 不明一律零 SQL 拒绝；REQUIRED 嵌套 join 复用同一上下文。
2. 身份记录与深不可变：record 元组键（无拼接）；record 时即序列化＋`canonicalBytes` 校验并缓冲不可变 String/byte[]；意图显式携带 targetType(LOCAL)/targetName/payloadVersion/comparisonProtocol。
3. 等价合并：flush 冻结阶段由参数化无实体表派生表 SQL 做数据库 collation 等价分组＋排序（rev2：Outbox 4 列、Timer 2 列，`CAST(... AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_ai_ci`，数值列 `CAST(... AS SIGNED)`，`UNION ALL` foreach 占位＋逐键返回行＋`DENSE_RANK() OVER (ORDER BY 已带 collation 派生列) AS grp` 组号＋原始精确列 `k*_orig` 回传定位＋`ORDER BY` 派生列；废弃 GROUP BY）；Java 按 grp 聚类、按 `k*_orig` 定位内存 record；组内 target/version/protocol/canonical payload 全等 → 合并保留最后元数据（recordSeq 最大者），否则 SystemIntegrityException（首个账本锁之前、零 DAL）。
4. 单次 flush 与 Spring 回调防偷触发：守卫同步不得覆写 `flush()`；显式 flush 仅 collector 公共入口；结构 RED 负例用 `TransactionSynchronizationUtils.triggerFlush()` 证明旧实现可重入、新实现无副作用；ArchUnit 禁生产 ledger/outbox 同步覆写 flush()。
5. 槽执行（用户裁定 B，rev2）：**不新增生产 `appendLocalTechnical` 通道，`OutboxEventRepository` 零修改**；生产 collector flush 冻结意图集合含 Outbox 意图 → `SystemIntegrityException` 失败关闭（零 DAL、不静默跳过、整事务失败）；协议由仅 src/test 同模块反射桥 `TrustedOutboxTechnicalAppendBridge`（参照既有 `TrustedOutboxAppendCoreBridge`）构造 `VerifiedAppendCommand`（LOCAL／LEGACY_JSON_V1、correlationId/causationId=null）驱动**同一**真实 main 底座证明，算法不复制；商业接线时再裁决生产执行通道。Timer 经 `scheduleOnce`；Outbox 全组先于 Timer 全组；表内按组号/行序（DB collation 序）。
6. flush 后写守卫：生产 MyBatis Interceptor（候选名 `LedgerPostFlushWriteGuardInterceptor`）仅装配 supplyChainSqlSessionFactory；FLUSHED 上下文 holder 匹配时 `Executor.update` 与锁定 SELECT 执行前拒绝；锁定 SELECT 词法规则冻结（rev2）：剥离 `'…'`/`"…"`/反引号字面量与 `--`/`#`/`/* */` 注释后顶层匹配 `FOR UPDATE`／`FOR SHARE`／`LOCK IN SHARE MODE`（含 `OF 列`／`NOWAIT`／`SKIP LOCKED` 后缀，大小写不敏感，语句级含子查询/CTE/括号），7 个既有 Mapper XML 语句分类表与假阳/假阴负控制固化；FLUSHING 窗口放行；普通无锁 SELECT 放行；无上下文事务透明；同线程新事务不拦截。
7. 整批回滚与完整 cause：槽 k 失败原样上抛（包裹时以原异常为 cause），整事务回滚，独立连接读回零孤儿/零残留 RESERVED；catch-swallow 不能 commit（beforeCommit 守卫兜底）。
8. 死锁声明边界：排序消除锁序环风险，不宣称无死锁；1213 反例保留。

## 4. 垂直 TDD 实施顺序（预审通过后、唯一实施者、每步 RED→GREEN）

每条先写真实 MySQL 反例并确证旧实现 RED（编译/夹具/同步失败不算行为 RED），再最小生产修复 GREEN；完整矩阵见 `clause-matrix.json`，此处为垂直顺序（不含平行负例全部枚举）：

1. C-OWNER 收集窗口与最外层归属：错 manager／只读／rollbackOnly／外层 scope 不明／无事务 → record/flush 零 SQL 拒绝；真实 REQUIRED 代理＋观测装饰器 → 接受；REQUIRED 嵌套 join 复用同一上下文。
2. C-KEY 复合键分隔碰撞：`("A|B","C",1,"E")` vs `("A","B|C",1,"E")`（Outbox）与 Timer 同形 → 两独立槽，不合并。
3. C-PAYLOAD mutable payload 防污染：record 后修改原 JsonNode → 缓冲意图不受影响。
4. C-MERGE 合并与冲突：同键等价（canonical 相等）不同元数据 → 合并保留最后元数据（recordSeq 最大者；Outbox targetName/traceId/occurredAt、Timer fireAt）；异 targetName／异 payloadVersion／异 payload → SystemIntegrityException 且零 DAL（flush 合并阶段、首个账本锁前）；Timer 生产路径＋Outbox 测试夹具路径（生产 CollationOrderingMapper＋反射桥）同构证明。
5. C-ORDER 数据库 collation 分组与排序：大小写变体、重音/预组合替换（é→E）、组合附标插入（U+0301/U+034F）、零权重变体 → 由 DB 等价分组（`DENSE_RANK` 组号，合为同槽或冲突拒绝）与 DB 排序（真实槽执行顺序与 SQL 返回顺序一致）；语句计数：Timer 生产路径每 flush 恰 1 条无表 SQL，Outbox 经测试夹具路径（生产 `CollationOrderingMapper`）每夹具 flush 等价步骤恰 1 条（生产 collector 对含 Outbox 意图的 flush 在分组 SQL 前失败关闭），零 data_locks 增量、不锚定 RR 视图（O-S08c 式负例）；Java 快速路径只做字节相等预合并。
6. C-FLUSH 单次 flush 与防偷触发：二次显式 flush 拒；未 flush 提交由 beforeCommit 拒；`triggerFlush()` 在新实现下无副作用（旧实现 RED 证明可重入）；嵌套提前 flush 后外层继续领域写 → 整事务失败关闭。
7. C-SLOT 槽执行与 RESERVED 守卫：生产 collector 含 Outbox 意图 → flush 失败关闭（零 DAL、不静默跳过、整事务失败）；Outbox 槽协议经仅 test 反射桥驱动真实底座证明（upsert/当前读/nonce/源 INSERT1/源读回核验/CAS1）；Timer 经 scheduleOnce；残留 RESERVED／未验证绑定提交拒；同事务 Outbox 先 Timer 的真实锁序（独立观察连接语句历史，Outbox 侧在桥测试路径）。
8. C-ROLLBACK 第二槽/源/绑定失败整批回滚：真实 SQL 故障（触发器/唯一冲突）在槽 k → 全量领域/源/账本零孤儿、零 RESERVED，完整 cause 链保留，afterCompletion 清空。
9. C-POST flush 后写守卫：FLUSHED 后领域 fixture Mapper INSERT/UPDATE/DELETE 与锁定 SELECT → 拒；普通无锁 SELECT → 放行（负控制）；词法规则假阳（字面量/注释/反引号标识符内含锁定文本）→ 放行、假阴（大小写/空白/后缀/FOR SHARE/LOCK IN SHARE MODE/子查询外层）→ 拒；record* → 拒；同线程下一事务 → 放行；无收集上下文事务 → 透明。
10. C-ARCH 架构门禁：禁止生产新增 Outbox 执行通道（除既有 `OutboxEventRepository` 自身外任何生产类不得依赖 `TrustedOutboxAppendCore`）、mapper 写方法仍仅底座、同步不得覆写 flush()、CollationOrderingMapper 仅 collector 可调用（测试桥除外），全部带违规负控制。
11. C-CONC A/Z 反序并发真实多物理连接：确定性排序下成功（只证排序），失败也保留为证据；不得以单次成功宣称无死锁；1213 反例不改写。
12. C-REGR 回归：既有 7 用例中仍成立的行为（冲突拒绝、未 flush 提交守卫、下游失败回滚）以新算法重证；旧测试字节变更先归档原字节+SHA。

## 5. 最小运行字节白名单（拟名；实施前核对既有类，超出即停报；rev2 按用户裁定 B 调整）

- 修改生产：`infrastructure/ledger/LocalLedgerIntentCollector.java`；`infrastructure/configuration/SupplyChainDataSourceConfiguration.java`（supplyChainSqlSessionFactory 注册拦截器；该文件不在 A39）。**`infrastructure/outbox/OutboxEventRepository.java` 零修改**（rev2：不新增生产通道，底座/`VerifiedAppendCommand`/`BindingProof` 全部字节不变）。
- 新增生产：`infrastructure/ledger/dal/mysql/CollationOrderingMapper.java`、`src/main/resources/mapper/infrastructure/CollationOrderingMapper.xml`、`infrastructure/ledger/LedgerPostFlushWriteGuardInterceptor.java`。
- 测试：`infrastructure/ledger/LocalLedgerIntentFlushIntegrationTest.java`（重写/扩展，Outbox 段改为生产失败关闭断言，Timer 段保留，原字节归档）；新增 `infrastructure/ledger/LocalLedgerIntentCollationOrderingIntegrationTest.java`（Outbox 排序/合并/组序经生产 CollationOrderingMapper＋反射桥驱动真实底座）、`infrastructure/ledger/LocalLedgerIntentPostFlushGuardIntegrationTest.java`（锁定 SELECT 词法规则正反例＋假阳/假阴负控制）、`architecture/LocalLedgerIntentCollectorArchitectureTest.java`（禁止生产通道负控制）、`infrastructure/ledger/TrustedOutboxTechnicalAppendBridge.java`（仅 src/test 反射桥）。
- 零修改：A39 全 39 路径（逐字）；历史 changelog（061 不追加）；旧 `appendLocal` 方法体字节；`OutboxEventRepository.java` 全文件；`LocalLedgerTransactionalWorkerCoordinator`／`LocalLedgerDeadlockDetector`／`LocalLedgerDeadlockException`／`BudgetContext`／预算包装；R3-04 全部路径；O-S01~O-S11／R1–R9 已闭环产物；`OutboxAppendIsolationArchitectureTest`（既有架构测试不动，新规则进新文件）。

## 6. 独立预审输入与裁决要点

预审必须读主设计完整 §6.1/§7.2/§7.2.1/§7.2.2 修正文本（含 rev2）/§9、本计划与仓外 `clause-matrix.json`、`design-handoff.json`、`report.md`、`pre-review/pre-review-report.json`，重点裁决：
1. 派生表无实体 SQL（rev2：逐键返回行＋`DENSE_RANK` 组号＋原始精确列定位）的可行性（无锁/无读视图/语句计数）与 collation 分组排序正确性（含组合字符、零权重、预组合替换边界，真实 MySQL 验证后逐项固定）。
2. 生产 Outbox 失败关闭＋测试反射桥方案与 R3-02O-S「不新增生产 LEGACY 入口」边界相容性（rev2 用户裁定 B：不再新增生产通道、`OutboxEventRepository` 零修改；商业接线时再裁决生产执行通道并独立预审，本次仅记录）。
3. MyBatis Interceptor 的 flush 后写守卫装配范围、锁定 SELECT 词法规则（rev2 冻结）与假阳/假阴负控制、7 个既有 Mapper XML 语句分类表、普通 SELECT 放行负控制、与 MybatisPlusInterceptor 同链装配。
4. 旧 7 用例中与“不得无死锁”冲突的表述（原 A/Z 用例只证排序）与 1213 反例保留。
5. 白名单边界与 A39 零接触、`OutboxEventRepository` 零修改。

## 7. 门禁与验收（预审通过后另行执行，本阶段不运行）

**R3-03 最终验收记录（2026-10-07，父验收通过）：** 实施 12 步垂直 TDD 全部闭环；独立实现规格审查初判 45/46（A-07 唯一失败）→ 最小修复（recordOutbox 快路径补 payloadVersion 判定＋新负例）→ 独立最终复核 8/8 passed；独立质量/安全审查 passed（0 阻断）。最终候选完整门禁 2595/2595（303 XML，0 fail/0 error/0 skip，maven0/runner0）、API 契约 45/45（7 类）、定向回归 54/54、架构 6/6、写守卫 10/10；最终 manifest `de6ae54f99714c0bad331b49f13c52edcf7236b382fab969acd5c87a4c02405d`，与工作区逐字节一致。父验收回执仓外 `r3-03-repair-20261006/parent-final-acceptance.json`。遗留：生产 Outbox 通道按用户裁定 B 保持失败关闭（商业接线时再裁决）；R3-04 保持暂停；低危注意 O-01~O-07／N5/N10 记录在案。工作区 14 路径未提交，等待用户授权。

**rev2 独立预审结果（父已核 JSON）：`PASSED`，46/46 passed、0 failed、0 unknown、0 阻断（`deleg_cf76c538`，报告 `pre-review-rev2/`）。** 非阻断注意 N9–N18 转为实施义务（不得因“非阻断”遗漏）：

- N9：『禁止生产通道』ArchUnit 负控制沿用既有模板模式（参数化规则＋违例 fixture），规则须豁免 `OutboxEventRepository` 全部嵌套类（`TransactionGuard.owner` 依赖底座），§7.2.2(9) 同步表述。
- N10：wrapper 型锁定语句（`.last("FOR UPDATE")`／`selectOneForUpdate` 系 ≥5 处）补入实施前逐条重核清单（机制同覆盖，证据目录须完整）。
- N11：锁定语句判别按（语句签名＋展开文本）缓存或每执行单遍扫描，禁止按静态 MappedStatement 签名缓存（动态 SQL 不成立）。
- N12：词法剥离明确不处理反斜杠转义语义（`NO_BACKSLASH_ESCAPES` 理论边界注释说明，当前语句面无影响）。
- N13：规范以设计 §7.2.2(7) 分类表 16 行为准（10 锁定/4 写/2 非锁定，已实核与 HEAD 全对）；旧 report.md 摘要计数为勘误对象。
- N14：等价组代表成员身份字节冻结为 `recordSeq` 最大者，写入 C-MERGE 测试断言。
- N15：Outbox 测试路径的 grp 聚类/合并/冲突判定 **pin 死为生产 collector 包私有实现**（与 Timer 生产路径同一实现），测试不得自实现第二套合并算法。
- N16：新测试桥优先复用既有 `TrustedOutboxAppendCoreBridge` 的 newCore/newCommand/append（最小性提示）。
- N17：`k*_orig` 定位 miss 显式 `SystemIntegrityException` 失败关闭。
- N18：实施期以连接 collation 断言固定 utf8mb4（`k*_orig` 字节回传前提）。

运行时义务（设计层已闭合、实施期逐项取证）：DENSE_RANK 派生表 SQL 真实 MySQL 8.4 零 data_locks/不锚定 RR 视图/每 flush 每类恰 1 条语句计数；collation 变体逐项固定 RED；锁定 SELECT 词法规则假阳 6 类/假阴 9 类负控制；7 XML 语句文本实施前逐条重核（含 wrapper 面）漂移即停；N5 直接 JDBC 写面为零事实重核。

- 定向片：上述垂直顺序每步真实 MySQL RED→GREEN（原 XML、Maven/runner 退出码、受测哈希绑定）。
- 完整门禁：`mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am -Psupply-chain-integration verify` 纯单测/架构/Integration 全量＋API 契约，逐类逐方法原 XML。
- 独立实现规格审查、另一上下文质量/安全审查、父最终验收；任何一步失败不闭合 R3-03。

## 8. 停止条件与交回

- 需改 A39 路径、历史 changelog、旧 `appendLocal` 方法体、`OutboxEventRepository`、coordinator/预算、共享池或日志范围 → 停报具体最小 diff。
- 技术边界无法实际保证（如 DENSE_RANK 派生表 SQL／锁定 SELECT 词法规则在真实 MySQL 8.4 不成立）→ 停报证据，不降级协议。
- 生产失败关闭＋测试反射桥方案被预审推翻 → 停报父选择，不自行改边界（R3-02O-S 冻结文本不改，仅记录未来待裁决）。
- 本轮完成即交回：不自主派审查、不自行验收、不跨 R3-04、不解除暂停、不启用商业。
