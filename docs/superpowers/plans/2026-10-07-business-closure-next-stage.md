# 业务闭环下一阶段推进计划

> **2026-10-09 跨 worktree 决策校准（优先于下方旧停止点）：** 已只读核对 `silent-moon / 21abca4e650469838b842c341b161a6a0519c6e6` 的最新提交、Hikari 迁移设计、B0 状态补充及实际数据源代码。该 worktree 已提交去 Druid 与标准 JDBC/Hikari 预算链路，B03 更新为 `PENDING_VERIFICATION`；不再把 Druid 对象级来源绑定研究列为下一步。当前 `dev / c6bdd7b3838da11c0e1c933eaef5270623a37a94` 尚未纳入这些提交，不能把跨 worktree 实现写成本分支已生效。旧 Timer 启动核验、双向映射与有界读取未见明确撤销裁定，旧复杂候选也不因此自动恢复实施；先对齐目标基线与任务裁定，再确定剩余最小必要方案。详见本文 §7.4。

> **2026-10-09 主链范围更新：** 用户明确要求按五阶段主链推进，主动通知和售后放到最后，不计入本轮全流程贯通任务。当前执行检查点、完成判据及必须另行确认的 R3-04 设计研究范围见本文 §7。下文关于通知/售后独立验收的历史记录保留，但不再作为五阶段主链完成前置；已有暂停和保护边界不因推进指示自动解除。

> **2026-10-08最新主链授权：** 华昇A1～A5通信组件沿现有最新记录复用，不等于生产履约已注册。用户确认优先处理目录商业发布前置，并仅恢复R3-04只读分析与设计修订；当前状态为 `READ_ONLY_DESIGN_REOPENED_IMPLEMENTATION_PAUSED`，编码、测试、共享配置、部署及真实库继续暂停。最新 [R3-04只读解阻分析](../specs/2026-10-08-r3-04-readonly-deblocking-analysis.md) 已核当前源码和Druid/Boot/Logback本地源码：启动时序、协议映射和有界读取仍须修正，B03没有已证明满足完整冻结范围的方案。只读授权不解除B0/B1/C或生产Outbox/HUA_SHENG启用门禁，不重做Task A/R3-03，不复提被拒绝的风险取舍；对象级来源绑定研究的新增范围须另确认。

日期：2026-10-07（工具取得）。基线：`dev / 2b61ba413668119f9193b7334a21a4a0af964c18`，开始时后端工作区及暂存区为空。

## 1. 授权与交付目标

用户本轮指示「按你的建议继续推进」。本轮先校准依赖与最新验收、同步仓内计划，并独立预审下一可隔离实施片；预审通过后按垂直 TDD 推进。该指令不撤销 R3-04 的明确暂停，不批准共享日志、跨池配置、维护方案范围改变，不包含暂存、提交、推送、部署、真实业务数据库或供应商调用。

业务主目标保持：管理端全新配置 → 分销商品真实价格和受理目录发布 → 项目当前供给准入 → 签名订单 → 路由 → HUA_SHENG 固定价、quantity=1、type201 资产交付 → 资金核对。QNM 仅作共用机制回归，不能替代华昇首单。禁止直接 SQL 补 LST、商业价或 AVAILABLE。

## 2. 最新状态与四路依赖校准

以最新有效验收记录及当前源码为准，旧文档未更新的页首/历史复选框不是新授权或当前通过结论。

| 工作 | 当前事实 | 原冻结契约/运行依赖 | 部署或启用前置 | 可独立隔离范围与本轮处置 |
| --- | --- | --- | --- | --- |
| Task A 身份桥接 | 已验收；选品 worker 只建立 DRAFT/UNKNOWN 身份 | BPD/PLT/LST 各自身份、双编码、NULL CAS、事务整批回滚不变 | 本轮不部署 | 复用既有成果，不重做、不宣称可下单 |
| R3-03 技术收集/flush | 二次重开修正已于 2026-10-07 父验收通过，已进入当前 HEAD | 数据库 Collation 分组/排序、最外层事务、单次 flush 与提交守卫 | 无新增迁移；Outbox 生产通道仍失败关闭 | 复用验收；不扩写为商业生产者/消费者已交付 |
| R3-04 旧 Timer 回填/启动切换 | `PAUSED_BY_USER_BOUNDARY_UNRESOLVED` | 真版本/协议、双向逐字映射、全字段不变、核验前零副作用及日志安全全部保留 | 维护静止/排尽/同版本交接；当前边界无获审完整方案 | 不研究、不编码、不测试、不重新提供已被拒绝的风险取舍 |
| R3-05/06/07 预算及清理 | 未交付；旧计划保持暂停推进 | 公开 API、物理资源隔离、SESSION/Statement/缓存恢复及预算 guard | 不以隔离测试授权生产启用 | 不因本轮主目标直接恢复；具体片若要解耦，另列完整契约、依赖和白名单预审，不能凭编号猜依赖 |
| R3-08/09 完整重跑/最终前置门禁 | 未完成 | 完整真实 REQUIRED worker、确定回滚和清理后最多一次重跑；依赖 02O/T、03、04、07 | 全量门禁/双审/父验收 | 有显式 R3-04 依赖，继续阻断，不绕过 |
| 后续 B0 栅栏/Inbox/页轮次/心跳 | 仅部分前置有既有证据，整体未验收 | 真实源栅栏、业务同事务、CAS、乱序/宕机恢复及容量契约 | 整体前置验收 | 不把 R3-03 通过当整体 B0 完成 |
| Task B1 商业发布与可用性 | 未交付 | READY 真实来源、MANUAL/AUTO 所有权、可靠生产者/消费者及完整 Schema、生产 Outbox 通道须另裁决 | B0 前置和商业通道独立获审 | 保留主链第一优先级；当前不接线，不填假价格/状态 |
| Task C 当前准入/冻结回放 | 未交付 | 真实项目供给快照；已冻结 SKU/Schema 优先重放、不重路由/扣款 | B 及华昇后续生产履约前置 | 不用通信组件测试冒充 C 验收 |
| HUA_SHENG Task3-A 隔离通信 | 已有独立复审通过的详细设计，源码当前仅 HuashengCrypto | 纯通信事实、AD-4 分层、无 DAL/Timer/Outbox/事务、无生产自动注册 | 无 DDL 或目录 B 启用依赖；本地挡板不等于供应商验收 | 本轮选择为可独立推进实现范围，先核完整规格和当前基线后预审 |
| 华昇 type201 资产桥接、生产步骤与回调 | 未完成/须各自设计审查 | 无损资产、冻结凭据身份、金额 checkpoint、恢复代次与唯一下一步、分布式授权、回调真实性 | 目录 B/C 和相关独立审查全部满足 | 不注册 metadata/executor，不上架华昇实际履约 |
| 通知/运营/售后 | 真实通知未交付；页面与资金审计尚需验收 | 通知成功须真实传输、签名/重投；退款/补发须真实资金动作 | 首单后按各自规格验收，风险不得伪成功 | 本轮不顺带改造、不算全业务已闭环 |

状态依据：`2026-10-04-buyer-catalog-bridge.md` Task A/B/C；`2026-10-05-b0-slice3-reopened-repair.md` R3-04~09；`2026-10-06-r3-03-reopened-repair.md` §7 最新验收；`2026-10-04-huasheng-http-token-parser-design.md` 全文。

## 3. 下一可隔离实施范围：HUA_SHENG Task3-A

本计划只细化实施顺序，不替代或降低既有详细设计任何条款。完整范围、公开职责、接口与安全边界以 `docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md` §1~13 为准；依赖方向须同时遵守总设计 AD-4 及实际 `SupplyChainModuleArchitectureTest`。

### 3.1 先预审，后实施

- [x] 当前后端 HEAD/工作区、已交付源码、Maven 依赖、详细设计与 R3-03 最新验收记录核对。
- [x] 独立只读首审返回 failed（`deleg_d2d569d1`），完整矩阵113行/首片20行全部运行 NOT_RUN；依赖隔离相容，唯一阻断 BLK-API-01 为精确技术接口缺口，不代表运行门禁失败。
- [x] 父核首审三产物哈希、冻结输入无漂移及当前 HEAD/status/空暂存区；已先在详细设计 §14 补唯一类型布局、准确签名/失败形状、面值raw-only、空发送证据和11 main/3 test候选白名单。保留首审 failed，不将候选自动当获审接口。
- [x] 针对 §14 技术补正独立复审 `deleg_bd83a2c0` passed=true；父核两报告哈希与12冻结文件无漂移、HEAD/status/空暂存区，BLK-API-01闭合。原113条、8条关联复核、新增30条及F01–F20均保留，运行全部NOT_RUN。
- [ ] 唯一实施者执行 §14 的11 main/3 test有限首片，先垂直TDD及定向/完整纯测/QNM契约，交回证据后父安排独立双审；无自主提交或跨片。
- [ ] 仅一名实施者按垂直 RED→GREEN 编码；运行受影响门禁后独立规格审查，再另一上下文质量/安全审查，父最终核证据。

### 3.2 垂直行为顺序与首个交付检查点

禁止“全部测试先写完/全部 DTO 先建完”式横向推进。按一个可执行行为一组测试和最小实现，每步保留测试字节、原 XML、Maven/runner 真实退出码和候选哈希。

1. **首个有限检查点：严格金额解析及安全失败边界。** 对合法 price/totalPrice 与面值原文按 §5.2 解析，元到分 HALF_UP、不从面值推成本；先验合法最小输入与金额边界，再逐项拒绝非法格式/表示范围。内部真实原因保留、外部固定诊断/表达无秘密遵守 §9.1；如金额 helper 返回/异常形状尚未由既有规格固定，必须在预审明确契约后再编码，不自行用普通异常链外抛。仅新增必需协议金额值、MoneyParser、其必需受保护失败/安全投影支持及对应测试，不提前实现 HTTP/token/订单语义。有限验收明确称金额/诊断子范围，不关闭 Task3-A。
2. 严格 envelope 与订单事实解析：最小合法字段、身份/数量/状态矛盾、QUERY 必需维度、资源预算和未知错误保真；卡片七型无损与敏感表达。每项逐行为推进，不输出资金动作。
3. 请求构造与独立 JCA：类型化五端点、冻结业务字段、每发送新 nonce/timestamp、raw-body/签名及安全输入；复用 HuashengCrypto，不改其算法。
4. 真实本地 HTTPS 传输：逐次现有 policy、封闭 URL、毫秒超时、有限读取、禁止隐式重发/重定向；真实 socket+独立 JCA，测试信任/DNS 仅测试装配。
5. Token/cache/facade：应用所有权、冻结身份与实际材料 HMAC 隔离、TTL 双时钟、有限容量/锁退休、generation CAS、至多 2 业务/2 TOKEN、业务已开始证据及失败单向投影。
6. 全量安全/架构/QNM 回归、完整 Task3-A 条款核验与独立双审。生产注册、真实供应商、资金和目录启用仍禁止。

**首片代码白名单须由预审核实后冻结。** 不能仅凭本计划的新路径摘要代替详细设计 §11，不能为了编译修改已验收的 HuashengCrypto、QNM、Ticket/Outcome/AssetFact、resolver/factory/ExecuteHandler、架构保护测试、池/manager/预算、R3-04 或 DDL。需要扩大范围必须先停报精确增量。

### 3.3 门禁与证据

先确认实际 Maven JDK21；用固定 HEAD 的仓库外独占 archive 副本加精确工作区增量验证，保留原仓开发位置，不新建 Git worktree。依赖构建带 `-am`，不沿用旧规格中的无 `-am` 命令造成 stale API jar。供应链以外不运行单测。

定向命令按实际新增类冻结，纯单测/架构命令模板：

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  '-Dtest=HuashengMoneyParserTest,HuashengSensitiveDiagnosticsTest,HuashengProtocolArchitectureTest,HuashengCryptoTest,SupplyChainModuleArchitectureTest,QnmGameOrderV1MetadataTest,QnmSupplierAdapterTest,QnmSignUtilsTest,QnmCallbackParserTest,QnmProtocolParserTest,QnmMaskingUtilsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

最终纯测/架构：

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am test \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

HTTP 契约按现有 profile 的 Failsafe 实际绑定运行；`it.test` 只选择本片实际契约类，不以窄运行宣称全量集成：

```bash
mvn -B -ntp -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration verify \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**/*IntegrationTest,!**/*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=HuashengHttpContractTest,HuashengCommunicationFacadeContractTest' \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

最后两个契约类未交付的首片不得执行空选择并记成成功；首片按实际纯逻辑范围运行，不造无意义 MySQL 集成证据。原 XML 逐类/逐方法核声明与实际执行集合、失败/错误/跳过和 BeforeAll 阻断；统计由程序生成，不预填数量。完整 profile 若按后续验收要求运行，另记录，不与定向混称。

首片另运行 `QnmSupplierAdapterContractTest` 的 Failsafe 定向契约（完整命令见详细设计 §14.4），不能用 `*Qnm*Test` 将它混入 Surefire 后冒称 HTTP 契约。N01–N05 已转详细设计 §14.4 实施义务：精确插件选集、保持目录暂停、有限声明、面值接受集合不变、新架构规则带负控制。11/3唯一布局及接口以该候选附表为准，待针对性复审，不授权提前横向造齐 DTO 或接口桩。

**复审通过后的必需实现断言：** RC-JACKSON 诊断四字段实际可序列化，普通Mapper/ANY及evidence空/有值均核字段和值，不能以空对象/抛错冒称安全；RC-CONSTRUCT 两金额有参构造与Diagnostics构造归属以ArchUnit结构负控制核，生产/fixture集合分离；RC-SERIALIZER ProtectedFailure/carrier/Parsed/Failed均类型级显式拒绝，先拒绝后写字段；RC-CRYPTO-CLASSIFY 实际Crypto包装IAE归VALIDATION，直接GSE归CRYPTO，不擅自遍历原链；RC-SAFE-EXCEPTION initCause不能挂原cause，suppression关闭且公开序列化不漏秘密。上述并入F11/F13–F17，不列为可忽略建议，全部尚未运行。

## 4. 主链最终验收（均待完成）

- [ ] 目录 B0 前置及商业 B1 全部获审/实施/验收，R3-04 暂停的完整约束得到另行正式处理，不静默放宽。
- [ ] C 当前准入和冻结 Schema/身份回放完成；新单读当前真实供给，旧单不依赖当前映射重新扣款/路由。
- [ ] 华昇 A 全通信、type201 资产、生产步骤/恢复和必要回调分别通过独立规格与质量审查，满足分布式授权等启用前置。
- [ ] 本地真实管理 Controller 从零配置、签名下单、实际路由与协议挡板、查单/资产、幂等/并发/跨项目隔离、成功提交/失败释放/未知保留资金恰一次验收。
- [ ] 另经部署/真实环境授权后，菜单与权限、全新管理页面配置、真实供应商及订单/资产/资金核对；挡板不算真实供应商完成。
- [ ] 通知、路由/订单投影、资金工作台、售后独立验收，不随首单勾选。

## 5. 本轮进度记录

已完成依赖校准、独立首审failed、技术补正及针对性复审passed，父核回执后有限实施放行。当前未改生产/测试代码、未执行新测试，运行/规格实现审查/质量/父最终验收仍待完成。主链仍被目录前置阻断。本轮可独立推进的通信切片不得解除任何既有暂停或商业启用门禁。后续实际结果同步 `docs/集成测试.md` §7.87。

### 5.1 首片候选实际结果（2026-10-07T11:53:18.743196+08:00，工具时间）

仅 §14 的11新增main/3新增test已实现，严格String价格/面值、受保护真实原因、直接类型单向诊断、普通/ANY Mapper安全编码、内部类型先拒绝序列化及不可挂原链的公开异常；没有HTTP/token/facade/SafeLog或目录生产接线。此前“未改代码/运行NOT_RUN”描述保留为历史。

最终候选真实离线JDK21门禁：定向11类189项；全供应链纯测/架构195类1,894项（API4类、server191类）；QNM profile verify同轮Surefire1,894项及Failsafe仅QnmSupplierAdapterContractTest 12项。三轮Maven/runner均0，失败/错误/跳过均0，新三类20方法实际运行。14个垂直循环保留原XML、源码快照、命令、双退出码及hash；构造ArchUnit负控制的断言方法字节不变、规则实现位于同一测试文件，因此该轮不冒称整个test文件同hash。初次GREEN保护检查记回归。

证据 `business-closure-20261007/implementation-a1/`：report.json/report.md/handoff.json、candidate-manifest、protected-verification、tdd-cycles、clause-method-mapping及runs原XML。4,067个既有受保护跟踪文件字节不变，原仓target未用，暂存区仍空，无Git写/部署/真实供应商/新数据库。父独立规格→另一质量安全→最终候选验收尚未执行；首片为待审候选，不勾关闭任务，不关闭完整Task3-A或业务，全部暂停及商业启用阻断不变。

### 5.2 当前停止点：质量/安全审查失败（2026-10-07 12:29 CST）

- [x] 独立规格审查 `deleg_9727e51d` passed，13生产行为TDD＋1测试承载架构规则TDD分别核验；保留旧passed报告及受测范围。
- [x] 独立质量/安全审查 `deleg_4f264a20` 完成但failed；父核报告哈希与真实反例，Q-SEC-01为initCause拒绝新异常文本泄漏，Q-QUAL-02为重要公开方法中文契约注释强制缺口。
- [ ] 等待用户确认最小修正范围；未确认前代码冻结，不指派修复，不进入下一片。建议只修本片安全异常及回归、补本片重要入口注释，不涉及HTTP/token/共享配置；该建议不是已批准设计或实施。
- [ ] 获确认后先更新精确设计，再同一新增反例RED→GREEN、重跑最终候选门禁、独立规格增量确认与另一质量复审、父最终验收。旧通过不能替代修复后证据。

本次只同步状态和仓外父回执，未修代码、未提交或部署；完整Task3-A及业务仍未完成，R3-04/R3-05+/商业启用限制不变。

### 5.3 质量修正最小范围授权（2026-10-07 12:31 CST，用户确认“是”）

- [x] 父按两阻断起草最小修正并获用户明确确认；详细契约见设计 §14.7，只关 Q-SEC-01（固定安全 initCause 拒绝）与 Q-QUAL-02（重要公开入口中文契约注释）。
- [ ] 唯一实施者先设计内先落地上述契约（已落），再逐行为 TDD：同一反例先 RED（真实断言、非编译/夹具失败），最小实现 GREEN。
- [x] 最终三命令门禁在修复后候选重新执行（11/190、195/1895、196/1907 全0双exit0），父独立聚合核原XML。
- [x] 独立规格增量确认与另一新上下文质量/安全复审均 passed；父最终验收完成（见设计 §14.8），首片有限验收成立。
- [ ] 未完成即不宣称质量通过；修复不解除 R3-04/R3-05+/目录商业/华昇生产启用，无 Git 写/部署授权。

**首片已验收，下一主链工作：** 目录商业发布与可用性（Task B1）仍被 B0 整体前置和 R3-04 暂停阻断，是主链下一个待决依赖；华昇 Task3-A 后续通信/解析/资产桥接可继续按既有设计独立推进。均需重新明确授权后才放行，不自动跨片。

## 6. HUA_SHENG Task3-A 后片切片规划（2026-10-07，用户指示继续）

完整行为以详细设计 `docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md` §4–§13 为唯一规范（首审113条矩阵 DESIGN_COMPATIBLE）；首片已交付其 §5.2/§9.1 金额与安全诊断基础（§14.8 验收）。后片按垂直顺序四片推进，每片独立预审→唯一实施者垂直TDD→独立规格+另一质量/安全→父最终验收；任何一片不得解除 R3-04/R3-05+/目录商业/生产注册限制，不调用真实供应商。

### 6.1 A2 严格响应解析与订单/卡片事实（纯逻辑+真实Jackson原因）

- 范围（已由设计 §15 完整冻结）：`protocol` 包 `HuashengErrorCode/HuashengOrderStatus/HuashengRefundStatus/HuashengTakeStatus/HuashengCardType`；`HuashengBusinessError/HuashengParseFact`；`HuashengOrderResponse/HuashengTakeResponse/HuashengTokenResponse/HuashengProtocolCard/HuashengCardPayload/HuashengField/HuashengExpectedOrder`；`HuashengResponseParser`（parseEnvelope/parseToken/parseOrder/parseTake，专用 Jackson：1MiB/深度32/字符串16384/STRICT_DUPLICATE_DETECTION/nextToken null/严格 UTF-8，整数 token 必须 IntNode/LongNode）。失败经既有 `HuashengInternalAttempt`/`HuashengProtectedFailure`，新增失败 kind/reason 7 个常量，`HuashengDiagnosticProjector` 映射为 `valueOf(kind.name())`；金额/身份/数量/56 组状态矛盾/七型 required/QUERY 两维度/未知 rawCode 保真/TAKE 无金额全部按设计 §5.3/§6/§15。复用首片 MoneyParser，不得新写金额算法。不实现 builder/HTTP/token。
- 测试：`HuashengResponseParserTest`、`HuashengProtocolCardTest`（含§12.2最小合法PLACE/CONFIRM/QUERY/TAKE夹具、逐required删字段负例、五十六组状态矛盾、七型正反例、敏感表达）；真实 Jackson 秘密 cause 夹具沿用§9.1。门禁=首片定向11类+新2类、完整纯测/架构、QNM Failsafe 契约，无新 MySQL/HTTP。

### 6.1.1 A2 独立预审与复审推进状态（2026-10-07）

- [x] 独立只读预审 `deleg_9e06724c` 返回 failed（`BLK-A2-01` 接口缺口 + `BLK-A2-02` 冻结文件重开冲突）。
- [x] 用户明确确认重开首片 3 个文件（`HuashengFailureDiagnostics` 枚举追加、`HuashengDiagnosticProjector` 映射修复、`HuashengProtocolArchitectureTest` 追加断言），并授权按设计附表 §15 进行针对性复审。
- [x] 详细设计已补 §15 技术附表，完整冻结接口签名、密封两层 ParseFact、56 组矛盾矩阵、43 错误码及最小白名单（15 新 main + 2 修改 main + 2 新 test + 1 修改 test）。
- [x] 针对 §15 附表独立设计针对性复审 `deleg_88896454` passed=true（BLK-A2-01/02 完全闭合，静态可实施）。
- [x] 实施者按 §15 白名单与 7 轮 TDD 完成编码，独占 scratch 副本通过三门禁（13 类 198 项、197 类 1,903 项、198 类 1,915 项全 0，双 exit 0）。
- [x] 实施者补齐 15 个 main 类全部 98 项公开方法/构造中文契约 Javadoc，三门禁重新验证全绿。
- [x] 独立规格复审 `deleg_e4706a4c` passed=true（BLK-SPEC-A2-01 完全闭合，10 项指标全过）。
- [x] 独立质量与安全审查 `deleg_e4706a4c` passed=true（0 安全隐患，0 逻辑错误，脱敏与边界全部过审）。
- [x] 父最终验收完成（见详细设计 §15.6），A2 片有限验收成立。

**A2 已验收，下一可推进工作：** A3 片（请求构造与独立 JCA，见下文 §6.2），可继续按既有设计独立推进；主链目录商业发布（Task B1）仍被 B0 整体前置和 R3-04 暂停阻断。

### 6.2 A3 请求构造与独立 JCA

- 范围（已由设计 §16 完整冻结）：`protocol` 包 `HuashengCallContext`（复用 domain 层 `CredentialMaterial`）、`HuashengRequest`（密封接口，permits 五封闭请求）、`HuashengTokenRequest/HuashengPlaceRequest/HuashengConfirmRequest/HuashengQueryRequest/HuashengTakeRequest`、`HuashengWireRequest`（归属 protocol 包，消除 ArchUnit 冲突）、`HuashengRequestBuilder`（支持注入 Clock 与 nonce 供应商，每次物理发送新秒级 timestamp 与 32 字符 UUID nonce，密文 AES/CBC/PKCS5Padding 小写 hex，签名原文为小写 hex 密文字符串的 UTF-8 字节并调 HuashengCrypto.signBodyHex，TOKEN 无 Authorization、业务带 Bearer）。不实现网络传输/发送。
- 测试：`HuashengRequestBuilderTest`；独立 JCA 契约测试（服务端独立调用原生 JCA 解密 AES 与验签 RSA SHA512withRSA，绝不调用 HuashengCrypto 辅助方法生成预期）。门禁：定向 14 类纯单测、全供应链纯单测与架构测试、QNM Failsafe 契约测试。

### 6.2.1 A3 独立预审推进状态（2026-10-07）

- [x] 独立只读预审 `deleg_61e04967` 返回 passed=true（0 阻断，0 重开文件）。
- [x] 架构分层解耦裁定：`HuashengWireRequest` 归属 `protocol` 包，彻底消除原设计草案置于 `http` 包引起的 `protocolForbidden` 架构违例。
- [x] 详细设计已补 §16 技术接口附表，完整冻结五请求模型、构造器参数与签名不变量、双盲 JCA 测试设计与 9 新 main + 1 新 test 白名单。
- [x] 实施者按 §16 白名单与 4 轮垂直 TDD 完成编码，独占 scratch 副本通过三门禁（14 类 207 项、198 类 1,912 项、199 类 1,924 项全 0，双 exit 0）。
- [x] 独立实现规格审查 `deleg_fd1989ad` passed=true（10 项指标全量达成，0 阻断）。
- [x] 独立质量与安全审查 `deleg_fd1989ad` passed=true（0 安全隐患，0 逻辑错误，独立原生 JCA 双盲测试过审）。
- [x] 父最终验收完成（见详细设计 §16.5），A3 片有限验收成立。

**A3 已验收，下一可推进工作：** A4 片（本地 HTTPS 传输桩与端口契约，见下文 §6.3）；主链目录商业发布（Task B1）仍被 B0 整体前置和 R3-04 暂停阻断。

### 6.3 A4 真实本地 HTTPS 传输

- 范围以详细设计 §17 为准：新纯 protocol `HuashengTransportContext` 正向投影冻结 base 与共享单调 deadline；http 包 `HuashengHttpTransportPort/HuashengHttpExecutor/HuashengHttpExchange/HuashengHttpSafeLog`。双参 exchange，逐次真实 SupplierEndpointPolicy、毫秒超时/公共 Call.timeout 精确 long、oneShot 与单次发送闩锁、1MiB+1 应用解码界限、严格 UTF-8 桥接与安全计量。沿用 A3 protocol WireRequest，不新增 http 同名模型，不改 A3/parser。
- 测试：新 HuashengHttpContractTest、HuashengHttpBoundaryArchitectureTest、HuashengTransportCauseContractTest；真实 MockWebServer HTTPS、独立 JCA、policy 零请求、TLS、HTTP/2 coalescing 421、各类 follow-up/超时/流/因果/日志负控制。标准 JSSE/keytool 仓外证书，无 pom/资源增量；测试信任/DNS 仅 private 窄构造与 src/test MethodHandles 桥，不开放生产装配入口。

### 6.3.1 A4 当前授权与预审输入（2026-10-07 17:15 CST）

用户已明确授权继续推进 A4。当前只放行独立只读设计预审，运行状态为 NOT_RUN，尚未放行编码；预审通过且精确接口与白名单落库后才进入实施。A1–A3 有限验收不等于 Task3-A 整体或业务闭环完成。

预审必须以当前代码而非旧草案核对以下衔接点：
- `HuashengWireRequest` 已在 A3 归属 `protocol`，本节上方的 `http` 包清单中同名文件是历史草案，不新建第二个同名模型。
- 当前 WireRequest 只有最终 URL，没有冻结 baseUrl 或单次调用剩余 deadline；必须定案如何在不依赖应用 CallBudget 的前提下逐次校验两者并落实共享 deadline，不从最终 URL 猜原 baseUrl。
- 当前 FailureKind/ReasonCode 尚无 PREFLIGHT、HTTP_ERROR、TRANSPORT_FAILURE；不得借用解析失败类别表达传输错误。若必须扩展受保护枚举或其它前片文件，预审先列最小 diff，呈报用户确认，不擅自重开。
- 当前白名单不允许修改 pom，且本地未发现 okhttp-tls 缓存；先评估不改依赖的标准 JCA/JSSE 测试证书方案。任何新 test-scope 依赖都须作为精确增量呈报，不因总设计提及而自动放行。
- 以真实 OkHttp 版本检查重定向、408/429/5xx、连接恢复、认证及重试路径；单独关闭三个开关不能替代实际一次物理发送的负控制。

保持 R3-04 暂停、目录商业前置限制、不注册生产适配器、不访问真实供应商、不执行 Git 暂存/提交/推送或部署。

#### A4 当前有效裁决（2026-10-07 17:42 CST）

独立预审 `deleg_13d5496a` 返回 `passed=false / BLOCKING`，BLK-A4-01～05 未闭合，尚未放行编码。父已核 report/receipt 及关联产物哈希、HEAD/空暂存区、46 个冻结路径字节无漂移。仓外 `prereview-a4/` 保存完整候选、最小授权清单和父核回执；它们不是已批准技术附表。

最小必需重开候选仅 `HuashengFailureDiagnostics.java` 第 7–8 行：两个枚举末尾各追加 PREFLIGHT、HTTP_ERROR、TRANSPORT_FAILURE，其余字节固定；Projector 的现有 valueOf 映射无需改动。A3 WireRequest/Builder 和 A2 parser 保持不变，采用新增纯 protocol TransportContext 与双参 exchange 的候选，正向传入冻结 baseUrl 与共享单调 deadline。须先经用户确认技术取舍、落库 §17 并独立针对性复审，不能直接实施。

第三方 HTTPS 微probe 实证：OkHttp 4.12.0 即使关闭两个重定向开关和 retryOnConnectionFailure，503 Retry-After: 0 仍产生两次请求；oneShot 产生一次。父核原微probe结果，但未重跑且不将其计入 A4 门禁。单次发送还须独立防重入、认证与 HTTP/2 421 等反例验证，不降低 TLS 或关闭 HTTP/2 补绿。

待确认候选边界：requestBytes 表示 execute 开始后的计划编码长度而非实际送达字节，用以区分 TOKEN 已开始尝试；响应 1MiB+1 是应用接收的解码字节上限，不承诺底层 socket/TLS 含预取的总字节上限；严格 UTF-8、关闭/擦除、A4 安全日志及 TLS 测试装配需完整附表。首选不改 pom/资源，以标准 JSSE/keytool 生成仓外测试证书；测试装配建议 private 窄构造与 test-only MethodHandles 桥，仍需明确确认并送复审，不冒称预审首选包私有构造为语言级 test-only。

A4 代码、运行门禁、独立实现双审和父最终验收均 NOT_RUN；A1–A3 有限验收及所有暂停/商业限制不变。

#### A4 最小补正获确认、待针对性复审（2026-10-07 18:03 CST）

用户明确回复 `agree`，同意上一轮呈报的最小方案并补正设计送复审。上述 17:42 裁决为首轮历史报告，原 failed 保留；本次未凭确认将其改为 passed。当前仅文档写入授权，尚未改 Java、尚未执行 A4 门禁，须复审通过后再校准实施授权。

- [x] 详细设计 §17 已落精确接口、双参传输上下文、private TLS 构造、计量/资源边界与失败矩阵、完整测试索引。
- [x] 最小实施候选白名单确认：5 新 main、3 新 test、1 个前片枚举文件重开；只在 FailureDiagnostics 两个枚举末尾各追加 PREFLIGHT/HTTP_ERROR/TRANSPORT_FAILURE，余字节固定。不改 Projector/Evidence/parser/A3/pom/资源或旧测试。
- [x] requestBytes 计划编码长度而非送达字节、1MiB+1 应用解码字节而非底层 socket 总字节两项计量边界显式落文；不以该选择放宽已批准的应用流限制。
- [ ] 独立针对性复审核 BLK-A4-01～05 闭合、完整 54 条 A4 矩阵及 48 本片/2 后片测试场景与 §17 实际可实施性，回交通过或阻断。
- [ ] 通过且实施授权校准后，唯一实施者逐行为 TDD、旧断言回归、新 HTTP/原 cause 契约/架构门禁；独立双审与父核后有限验收。

复审不得将 third-party 微probe 当运行通过；尤其 HTTP/2 合并连接 421、deadline 各时点、真实线程中断、资源擦除、JSON 普通/ANY 拒绝和真实 Logback 限频仍全部 NOT_RUN。保留共享池/HTTP2/TLS，不用关闭能力补绿。48 场景及新增阶段绑定/并发关闭/私有装配/日志约束须映射专属断言；A5 61/缓存完整矩阵保留后片，不提前实现。

#### A4 第二轮当前有效裁决（2026-10-07 18:25 CST）

`deleg_fca58c4f` passed=false，未放行编码。BLK-A4-02～05 仅设计层闭合；BLK-A4-01 的上下文/截止/桥接已闭合，新增路径兼容 BLK-A4-REV2-01 阻断。父核原报告/回执、无网络路径微probe 源码与结果哈希及 46 冻结路径无漂仪，尚未改 Java。

A3 可接受的 `/tenant/..` 或 `/tenant/%2e%2e` 前缀会被 OkHttp HttpUrl 规范化，违反物理前缀保真。按 §17.2 已有停报条款，等待用户确认是否新增 A4 传输准入规则。

N-A4-R2-01～12 非阻断实施义务保留在第二轮报告，设计放行后必须逐条转入实施清单，不以本轮设计部分闭合代替运行证明。

#### A4 路径决策定案、进入第三轮针对性复审（2026-10-07 18:35 CST）

用户明确答复“不需要增加”路径变化拒绝规则，确认允许 OkHttp 按照标准 HTTP 客户端默认行为规范化路径。
- [x] 设计 §17.2 与 §17.11 明确闭合 BLK-A4-REV2-01：OkHttp `HttpUrl` 默认规范化语义成立，不增加人为拦截，不修改 A3，不引入额外 Java 重开文件。
- [x] 6 项历史阻断已全部在设计层形成明确闭合条款（BLK-A4-01～05 + BLK-A4-REV2-01）。
- [x] 启动第三轮独立只读针对性复审（rev3），核定 §17 完整设计可实施性，复审裁决 passed=true（0 阻断，report.json sha256: `e638c8b7…`）。
- [x] 用户明确授权继续推进 A4 实施。
- [x] 实施者按 §17 白名单与 4 轮垂直 TDD 完成编码，独占 scratch 副本通过三门禁（15 类 219 项、199 类 1,924 项、202 类 1,990 项全 0，双 exit 0）。
- [x] 独立实现规格审查 `deleg_52747bc8` passed=true（0 阻断）。
- [x] 独立质量与安全审查 `deleg_52747bc8` passed=true（0 安全隐患，0 逻辑错误）。
- [x] 父最终验收完成（见详细设计 §17.12），A4 片有限验收成立。

**A4 已验收，下一可推进工作：** A5 片（Token / 缓存 / Facade / 安全日志全链编排，见下文 §6.4）；主链目录商业发布（Task B1）仍被 B0 整体前置和 R3-04 暂停阻断。

### 6.4 A5 Token/缓存/Facade/安全日志

- 范围（已由设计 §18 完整冻结）：应用包 11 类（`HuashengCredentialIdentity/HuashengAuthorizedCallContext/HuashengTokenIdentity/HuashengTokenCachePort/HuashengTokenEntry/HuashengTokenPolicy/HuashengTokenManager/HuashengCallBudget/HuashengCallResult/HuashengCommunicationFacade/HuashengSafeLog`），infra 包 1 类（`HuashengInMemoryTokenCache`）。有界 61 至多 2 业务 + 2 TOKEN，budget/deadline 逐发送检查，PREFLIGHT/LocalFailure 与累计 evidence 聚合，内存单飞锁 + CAS generation + 1024 键上限。
- 测试：5 类（`HuashengTokenManagerTest/HuashengTokenCacheTest/HuashengCommunicationFacadeContractTest/HuashengApplicationArchitectureTest/HuashengApplicationSensitiveDiagnosticsTest`）。

### 6.4.1 A5 独立预审推进状态（2026-10-07）

- [x] 独立只读预审 `deleg_bf3a667f` 返回 passed=true（0 阻断，0 重开文件）。
- [x] 详细设计已补 §18 技术接口附表，完整冻结复合身份模型、Token 策略、内存单飞锁缓存、有界 61 重试编排、安全日志与 12 新 main + 5 新 test 白名单（0 重开文件）。
- [x] 用户明确授权继续推进 A5 实施。
- [x] 实施者按 §18 白名单与 5 轮垂直 TDD 完成编码，独占 scratch 副本通过三门禁（定向 20 类 253 项、全纯测 203 类 1,946 项、Failsafe 4 类 74 项，联合全验 226 类 2,123 项全 0，双 exit 0）。
- [x] 独立实现规格审查 `deleg_5bba38f1` passed=true（0 阻断）。
- [x] 独立质量与安全审查 `deleg_5bba38f1` passed=true（0 安全隐患，0 逻辑错误）。
- [x] 父最终验收完成（见详细设计 §18.6），A5 片有限验收成立。

**至此华昇 Task3-A 通信组件五大切片（A1～A5）全部完成垂直实施与双审验收闭环！**
下一主链断点：买家选品到可下单目录桥接（Task B0/B1）及 R3-04 暂停边界决策。

### 6.5 四片共通边界

每片白名单以本片获审附表及明确授权为准；本轮 A4 唯一既有 Java 修改例外是 §17.1 的 FailureDiagnostics 枚举末尾增量，其余代码/pom/资源/DDL 不改。先前 A2/A3 的有限验收与重开范围仅属各自历史授权，不扩展本次例外。不注册 Spring/HUA_SHENG metadata/executor；Facade 之外不接 Timer/Outbox/资金；中文契约注释与设计锚点延续 Q-QUAL-02 标准。旧 §12.5 无-am 命令不沿用；契约按 profile、-Dit.test、integration-test+verify 实际执行与原 XML 核验。当前 A4 待针对性复审，不沿用历史“A2 下一片”状态，预审失败即停。

## 7. 五阶段主链推进与首个检查点（2026-10-09）

### 7.1 范围与执行方式

目标是全新管理配置的 HUA_SHENG 固定价、quantity=1、type201 卡密订单：签名下单、正确路由、自动终态、资产查询、资金核对。通知与售后属于后续独立运营任务，不计入本轮完成条件；成功提交、明确失败释放、未知保留资金以及同号不重复履约/扣款仍必须验证。内部目录事件/定时任务可靠性不可因排除主动通知而省略，未支持传输必须失败关闭，不新增真实通知传输。

沿既有详细设计分片执行，不一次委派全部五阶段，不从缩略计划直接编码。每片先明确接口、文件范围、依赖和适用测试，再设计审查、逐行为 TDD、最终候选门禁、规格/质量审查和有限验收；必须有新的授权与完整方案才能突破旧保护边界。没有 Git 暂存/提交/推送、部署、真实业务数据库或真实供应商调用授权。原仓已有 AGENTS.md 工作区修改保留，不纳入本轮编辑。

### 7.2 依赖顺序与完成判据

1. **目录可靠性前置（B0）**：先闭合 R3-04 正常启动只读核验、受控维护回填、真实载荷版本/协议、双向逐字映射、完整源字段保护、JDBC 物化前有界读取及安全诊断；再按原依赖完成尚未交付的预算/资源清理、完整 worker 恢复、事务栅栏、Inbox、页轮次、心跳和容量门禁。R3-04 单项通过不等于 B0 整体完成；复用 R3-01/02O/02T、R3-03及切片1/2既有成果。
2. **自动商业发布与可用性（B1）**：核定生产商业通道和完整载荷 Schema，真实生产者与消费者按同事务协议接线；从 READY 分销定价与真实供给资格生成商业快照、幂等发布和可用性，覆盖乱序/宕机/到期/巡检。AUTO 不恢复人工暂停或退役，MANUAL 不被接管价格/生命周期/共享可用性。缺条件保持真实等待原因，不填假价或 AVAILABLE。
3. **项目当前准入和冻结回放（C）**：首冻前目录准备、建单/资金写入前当前项目供给核验、路由快照复用；MANUAL 的新 HTTP 准入按真实资格，不沿滞后投影误拒绝。已接受同参请求优先使用冻结 SKU/Schema/身份，配置变化不重复路由/扣款；同号异参仍冲突，认证及买家/项目 ACTIVE 边界不变。准入机制可独立验证，完整华昇场景留阶段5，不伪造已完成。
4. **华昇生产履约与资产交付**：复用通信 A1～A5，先细化并审查 type201 无损资产桥接、生产 metadata/executor、多步骤及 checkpoint、查单优先恢复、冻结凭据和分布式授权；按协议需要完成可信供应商入站回调。供应商入站履约回调不属于排除的对项目主动通知。生产注册须满足目录 B/C 和本阶段完整门禁，不能因通信测试通过直接启用。
5. **从零配置全链验收**：本地真实管理 Controller 建立全新商品/供给/分销/项目/凭证/路由，签名下单、实际生产执行器与华昇 HTTPS 挡板、查单/资产、幂等/跨项目隔离/配置变化回放/资金三种结果核验，不直接 SQL 补 LST、商业价或 AVAILABLE。另获真实环境授权后进行管理页面及实际供应商验收；本地挡板通过和真实环境通过分开声明，不能以 QNM 替代华昇。

- [x] 用户五阶段顺序与通知/售后排除项已同步主设计、计划和集成测试文档。
- [x] 当前源码核对：Runner 仍回填后核验；Timer start 无账本准入断言；直充补偿在 initMethod 启动；回填 INSERT 硬编码 payloadVersion=1，核验无完整反向映射且载荷先全量物化。
- [ ] R3-04 完整设计解阻及独立预审。
- [ ] B0 剩余实现/完整门禁/验收。
- [ ] B1 自动发布、可用性及可靠商业接线验收。
- [ ] C 当前准入和冻结身份回放验收。
- [ ] 华昇生产履约、资产和恢复验收。
- [ ] 本地从零全链自动化验收。
- [ ] 另获授权后的真实页面/供应商/资产/资金验收。

### 7.3 原停止点与最小新增研究范围（跨 worktree 核对前的历史记录）

基线 `c6bdd7b3838da11c0e1c933eaef5270623a37a94`。本次直接读取生产 Runner、迁移服务、Timer、补偿配置、原 DataSource factory、SystemIntegrityException、实际供应链根 Logback 及架构门禁；日志规范已读取。此前分析的启动/映射/读取缺口仍存在，当前源码没有新的池级诊断机制。新近根 Logback 的 ELK 接入不提供 Druid 池对象/R3 调用身份，不能据此宣布 B03 已解阻；此次没有重新运行第三方探针或测试。

阻断仍是 `docs/superpowers/specs/2026-10-08-r3-04-readonly-deblocking-analysis.md` §4：现约束下尚无已证明同时满足“R3 诊断安全、其它池及非R3行为不变”的完整方案。用户本次推进路径不明确授权变更原 DataSource 构造/装配或扩大对象级来源研究，故不编码、不测试，不将非阻断条款部分实现后称 R3-04 完成。

下一最小请求仅为**对象级来源绑定的只读设计可行性研究**：读取本地 Druid/驱动/Spring/Logback 公开扩展接口，逐个核对 init、Liquibase、后台 create/reconnect、维护核验和 cleanup 的来源归属与因果传播，提交“可行/不可行”证据、必要精确文件增量和兼容验证清单。研究不改 Java/XML/YAML/pom/DDL，不编译或跑微probe，不连接任何数据库，不注册代理/子类/新池，不改变池参数、共享日志或其它进程；任何后续候选实现及保护范围重开另行确认。该研究尚未获精确授权，不以本计划作为放行依据，不复提此前被拒绝的风险豁免或跨池 class-logger 改造。

### 7.4 silent-moon 最新决策与下一步纠偏（2026-10-09，只读核对）

1. **依赖层路线已改变，不继续绕 Druid 做补丁。** `silent-moon` 最新 HEAD 为 `21abca4`，`cab12e0` 移除 Druid Maven 依赖，`e9075fc` 切换供应链数据源装配，`3f084ed` 将旧深绑定类桩化并加入架构门禁；实际 `SupplyChainDataSourceConfiguration` 装配 `HikariDataSource` 与 `StandardBudgetRoutingDataSource`。对应设计为该 worktree 的 `docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md`。撤下 §7.3 的对象级来源绑定研究请求，不将其继续作为五阶段主链首个前置。
2. **B03 是待验证，不是已验收。** 该 worktree 的 B0 §7.2.1 R02 与 §8.2.3 增加新状态指针：预算不变量保留，底层淘汰改走 Hikari 公开能力与标准 JDBC；B03 为 `PENDING_VERIFICATION`，启动失败、早期 Liquibase、后台重连的安全呈现与归属待按迁移设计 §6.5 验证。其 `docs/集成测试.md` 的迁移记录不得当成本轮重新执行结果。
3. **未找到整组旧 Timer 要求取消的证据。** 最新 B0 §8.2～§8.2.2 仍保留真实版本、启动前核验、双向映射与物化前有界读取；与当前基线相比，`TimerLegacyMigrationService`、`TimerLegacyMigrationRunner`、`TimerEngineLifecycle`、`RechargeFrequencyRecoveryConfiguration` 无提交差异。全仓 Markdown 搜索未找到“过度设计”或“依赖缺陷”的明确任务撤销表述。此结果只说明已读提交中没有该裁定，不能据此断言其它会话未曾裁定，也不能自动重启旧20路径候选。
4. **先对齐基线和裁定，后收敛必要实现。** 当前 dev 尚未纳入该 worktree 的 Hikari 迁移；两 HEAD 非当前 dev 基线的祖先顺承关系。本轮不合并、cherry-pick 或修改 silent-moon。下一检查点是明确以哪一分支/提交作为推进基线，并取得若存在的“哪些要求取消、哪些仅取消实现复杂度”的具体裁定，再按新依赖保留必要数据一致性与启动安全、删除无必要的实现绕行。五阶段业务顺序及通知/售后后置不变。
5. **证据边界。** 本轮仅读取 Git 元数据、文档与源码并更新本计划及集成记录；未编译、运行测试/探针、启动服务、连接数据库或供应商，没有新增通过/启用结论；原工作区 AGENTS.md 修改保留不编辑。
