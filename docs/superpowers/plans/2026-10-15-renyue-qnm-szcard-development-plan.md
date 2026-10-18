# RENYUE、QNM、SZ_CARD 供应商接入开发计划

> **执行者说明：** 获开发授权后可按需使用 `executing-plans` 或 `subagent-driven-development` 逐任务执行；代理分工遵守工具的适用范围，不因本计划自动启动新线程。以下复选框用于跟踪；2026-10-15已获本地开发授权，T00基线检查已启动，未满足的门禁见§7。

**目标：** 在供应链中台现有配置、路由和资金底座上交付三家普通直充的创建、查询、回调与持久恢复闭环。

**架构：** 三家共享受控单步骤应用流程，各自拥有协议Adapter；不代理卡管交易、不整体泛化华昇四步骤。QNM按已确认的开发阶段边界直接调整现有`QNM_GAME_ORDER_V1`实现为USERKEY合同，不保留旧协议回调/恢复或双协议过渡。

**技术栈：** Java 21、Spring Boot、MyBatis Plus、MySQL/Liquibase、既有Redis/Outbox/PersistentTimer、OkHttp、JUnit 5/MockWebServer/Testcontainers/ArchUnit；管理端Vue 3/Element Plus/TypeScript/pnpm。

> 文档编号：SC-SUP3-PLAN；版本：0.2；日期：2026-10-15；状态：已获本地开发授权，T00仍有延期及外部门禁；T02及T03本地执行/恢复核心已验证，商户总限额、生产协议与T04资金闭环未完成；供应商合同与发布门禁未解除。
> 基线：[需求SRS](../specs/2026-10-15-renyue-qnm-szcard-requirements.md)、[可行性FSR](../specs/2026-10-15-renyue-qnm-szcard-feasibility-report.md)、[概要设计SAD](../specs/2026-10-15-renyue-qnm-szcard-solution-design.md)、[详细设计DDD](../specs/2026-10-15-renyue-qnm-szcard-detailed-design.md)。

---

## 1. 启动条件、职责与估算口径

T0是取得开发授权、评审通过首期范围/资金策略、具备隔离验证环境后的启动日，不是本文件日期。供应商协议未确认时可以做不依赖其业务语义的公共机制，不可猜测关键字段/失败判据；对应供应商不能进入发布。

建议2名后端（BE-A公共应用/事务，BE-B协议）、0.5名前端（FE）、1名测试（QA）；业务运营、供应商对接、运维/安全按门禁参与。角色不是已分配的具体人员；项目负责人在T00落实责任人。工作量包含各角色的有效投入，不能把各任务人日当日历天简单相加。

- 基础工作量：T00～T11合计**35～49人日**。
- 风险预留：约20%，向上取整为**7～10人日**；预算容量**42～59人日**。
- 建议日历：**4～6周**，需满足人员和外部响应前提；外部合同、账号、审批等待单独记录。
- 不含：卡密/厘价模型/余额准入/自由工作流、旧QNM双协议或历史恢复迁移、生产数据清理、大规模历史资金修复。增加范围需变更评审与重估。

所有任务开始先读对应DDD章节和适用AGENTS.md；涉及日志/异常/外呼读全系统日志规范。先修订设计再改代码。纯机制重构与行为变更分别验证、分别形成可审阅提交；提交标题/正文用中文，推送另需授权。

## 2. 文件所有权与依赖

以下前缀是精确目录缩写，不是待寻找的文件：

```text
J = aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/java/com/aifuli/module/supplychain/
T = aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/test/java/com/aifuli/module/supplychain/
R = aifuli-module-supply-chain/aifuli-module-supply-chain-server/src/main/resources/
F = /Users/mac/codespace/supplychainhubweb/
```

BE-A拥有`J/border/fulfillment/application/`及`J/infrastructure/security/`共享改动；BE-B在协议合同冻结后分别修改`J/supplieradapter/renyue/`、`qnm/`、`szcard/`。元数据/配置发布的公共文件由BE-A集成，BE-B不同时覆盖。FE只改F，卡管仓库仅取证，不复制交易服务或修改生产配置。

拟新增责任文件按任务列出，优先保留既有名称与深模块；无需为每个字段/步骤再建一个Service。新增Java类的实际进度以任务勾选及§8～§11实施记录为准；未实施文件仍是设计目标，有等价职责时合并到既有所有者并同步DDD。

```diagram
┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐
│ T00 │───▶│ T01 │───▶│ T02 │───▶│ T03 │───▶│ T04 │
└──┬──┘    └──┬──┘    └─────┘    └──┬──┘    └──┬──┘
   │          │                     │          │
   │          ├────────▶ T08 前端    └─▶ T05 仁跃样板
   │          └────────▶ T09 商品审核     T06 QNM替换
   └─▶ 供应商合同/向量准备                T07 SZ_CARD
                                           │
                      T04～T09全部适用门禁 ───┤
                                      ┌────▼┐    ┌─────┐
                                      │ T10 │───▶│ T11 │
                                      └─────┘    └─────┘
```

协议向量与传输测试可和公共机制并行，完整协议接线依赖T02～T04；先让仁跃样板验证公共合同，再接QNM/SZ_CARD。公共文件单人写入，并行不能跳过接口冻结和合并验证。

## 3. 工作包与完成条件

### T00 基线与合同冻结（2～3人日；项目/对接/BE-A/QA）

依赖：开发授权；输出：C项决定、可复现基线、供应商逐家准入单。

- [ ] 评审SRS C-01～C-08，记录责任人/结论/证据。旧QNM兼容已排除，不再索要排空证明；2026-10-17用户又排除与cardshop共用账号，共享账号共存/回调分流及跨系统额度协调不再作为门禁，供应商订单号及中台回调准入仍待验证。
- [ ] 将两附件摘要、生产配置观察时间、正式协议/脱敏向量来源纳入评审记录；不得保存生产秘密。附件摘要已复核一致；RENYUE回执/ExpandParams、QNM USERKEY、SZ_CARD逐单callbackUrl仍待C-03确认。
- [ ] 在干净基线运行§4适用门禁；登记已存在失败及报告路径。检查测试使用disposable库，不能运行带DROP库的旧根脚本。
- [x] 更新一期详细设计§7.3/10/17与本文件范围的交叉引用，明确QNM开发期合同调整，不改写其他历史设计的调查事实。

验收：业务范围/资金策略可冻结；未关闭的供应商事项有明确阻断阶段，不能用“后续再说”代替。

### T01 元数据、凭据绑定、能力与封闭模板（2～3人日；BE-A）

依赖：T00公共合同。修改`J/supplieradapter/metadata/AdapterMetadata.java`、`PublishedConnectorConfigurationResolver.java`，以及`J/tradecore/supply/application/ConnectorValidationService.java`、`ConnectorPublicationService.java`。

新增`J/supplieradapter/metadata/RenyueRechargeV2Metadata.java`、`SzCardEquityV1Metadata.java`；调整现有`QnmGameOrderV1Metadata.java`。资源放R下`supply-chain/adapter-schemas/`，两家新Schema命名分别为`renyue-recharge-v2-{connector,offer}.schema.json`、`sz-card-equity-v1-{connector,offer}.schema.json`；QNM使用现有资源路径。

- [ ] 先经现有公开发布接口加入回归：无任意错误表、缺merchantKey/privateKey、错绑定模式、非法URL和虚假余额/资产能力必须拒绝，观察旧实现失败。
- [x] 把华昇既有`validateMappings`提升为元数据默认钩子并替换三处映射特判，先不改变协议行为，运行华昇发布/架构回归；2026-10-16证据见§8。
- [ ] 再增加两家、调整QNM首期三个能力及NONE画像，实现封闭模板/Schema一致性；注册不得重复，不新增第二QNM类型。
- [ ] 扩展`T/tradecore/supply/ConnectorPublicationIntegrationTest.java`和`ConnectorCredentialSetIntegrationTest.java`，验证管理GET不回显秘密、版本发布互斥及伪造模板拒绝；重跑§4单测/集成门禁。

2026-10-16分阶段执行：C-01/C-05已确认，先完成公共钩子及三边界回归，保持QNM/华昇原合同。现有Registry只查Metadata重名、执行分派未查缺失/重复，故不提前注册两家新Metadata或改变QNM能力；逐家合同、Schema、完整执行/回调接线及门禁齐备再放行。公共钩子通过不等于T01整体或AC-01完成，其他拒绝场景随对应协议合同落实。

验收：AC-01；只有支持的直充配置能发布，映射不是资金动作执行器。

### T02 冻结快照与受保护Ticket（3～4人日；BE-A）

依赖：T01。修改`J/border/fulfillment/domain/OperationTicket.java`、`application/FulfillmentOperationTicketFactory.java`、`FulfillmentAcceptanceLifecycle.java`及原配置解析器；新增`domain/ResolvedCredentialMaterial.java`、`application/singlestep/SingleStepCheckpointCodec.java`。FrozenCredentialSetResolver直接复用。

- [x] 基础切片先行：按DDD§3.2实现冻结槽身份/独立短事务解析、按Metadata键接管材料、可关闭Ticket及工厂显式装配入口；不依赖未决协议码/查询预算，不注册供应商、不迁移既有协议。先验证轮换拒绝、String版本不数值化、锁覆盖解析、成功/异常擦除、冻结供价与旧构造器兼容。本项独立于完整检查点/受理/既有单读取验收，不能将其通过标为T02完成。2026-10-16组合回归证据见§9。
- [x] 在`T/border/fulfillment/application/FulfillmentOperationTicketFactoryTest.java`与真实受理集成中加入错误槽版本String、集项版本Integer、发布后调价/换回调、冻结手机号目标及坏AAD场景，先证明现有Ticket/快照不足。
- [x] 按DDD§3保存封闭检查点和内容摘要；只将非秘密定位放Timer/Outbox。凭据材料按Metadata键解析，finally擦除所有权内数组。
- [x] 受理同事务写订单/预留/检查点/准备watchdog/Outbox；新增内部既有订单解析入口，拒绝草稿、错绑定、漂移，但不禁止原版本退役后读取。
- [x] 验证发布新Offer/凭据不改变原单手机号；三家首期拒绝EXTERNAL_ACCOUNT，不把QQ改成MOBILE；新单仍不能选退役版本。华昇Ticket及通用外部账号模型不改语义。

验收：AC-01/07/08/10；无最新值兜底、无明文秘密序列化。新增列并非默认需要，若存储约束迫使变更，先修订DDD§3.4。

2026-10-17：§12的本地冻结、受理、原单重建机制已实现；真实供应商协议、生产Metadata及T03/T04恢复资金闭环未完成，不能把本任务的本地机制验收等同三家端到端AC全部通过。

### T03 单步骤创建、查询与持久围栏（5～7人日；BE-A）

依赖：T02。新增`J/border/fulfillment/application/singlestep/SingleStepFulfillmentRunner.java`、`SingleStepFulfillmentLifecycle.java`，`J/border/fulfillment/domain/SingleStepSupplierFact.java`；动作/permit/策略值放所属模块内部。修改`FulfillmentOperationExecuteHandler.java`、`RechargeFrequencyQueryService.java`、`J/border/fulfillment/domain/FulfillmentTimerTaskTypes.java`并接既有Timer注册。

**2026-10-18当前进度**：DDD§4.7公共认领/执行/恢复核心与24项真实MySQL恢复测试已通过；执行入口和手机号频控查询均接同一Runner。此处原复选项含逐家协议、资金及商户限额的完整条件，不能因公共子集通过全部勾选，实际完成/剩余分界见§13。生产Metadata仍未启用单步，旧QNM/华昇路径不切换。

- [ ] 新增`T/border/fulfillment/SingleStepRecoveryIntegrationTest.java`，由公开受理和Timer入口覆盖两个工作者、手机号频控恢复与原单Timer竞争、marker提交后未发/远端已受理、查询到期边界，验证旧实现无持久闭环。
- [ ] 实现DDD§4认领/事务外HTTP/结果事务；generation、lease、marker、recoveryCount和下一Timer同事务，禁止以本地超时认定未发送。
- [x] 先按DDD§4.6实现HTTP预算基础切片：冻结超时纯值及单次Call工厂，初值10000/20000/30000/60000ms，使用固定单调截止裁剪剩余资格、覆盖响应体读取，禁重试/跳转及透明二次发送；以MockWebServer先RED再GREEN验证慢响应、延迟execute、短租约及正常对照。2026-10-17契约20项、API单测125项、server单测/架构2313项及四类契约共90项通过，30秒初值本地滴流探针通过，详见§11。该项不提前注册三家或替换旧QNM，不能代表数据库60秒认领、持久化冻结、实际外呼日志或完整T03完成；第24项完整验收仍待T02～T04接线。
- [ ] MOBILE恢复委派同一原单资格；按DDD§3.1/4.3/4.4冻结每原单maxQueryClaims=1000及累计QUERY成功认领数，999可原子领第1000次、禁止第1001次。认领提交后未发HTTP即宕机也耗一次，未取得资格/认领回滚不耗，结果回滚不退还；重启/配置变化/结果重放/NOT_FOUND连续计数清零不重置累计数。到限只停新查询，仍未知Case保留预留，不丢弃最后合法结果；按`docs/集成测试.md`本项目C-04第19项验证并发、事务和时间边界，华昇不变；QQ/OTHER正向履约和恢复本期不实现。
- [ ] 按DDD§2.2/3.1/4.4实现跨Connector/版本/实例的商户总限额及等待合同，使用同商户当前统一额度约束新单外呼/旧单补查，不随订单冻结；调低不取消已发HTTP、不改资金，待在途数低于新并发上限后准入，调高不绕过原单资格。原恢复间隔、1000次认领上限/累计数、截止及冻结履约材料不变；按`docs/集成测试.md`本项目C-04第20～22项验证共享、等待及调额，具体数值与调度/配置一致性实现须先补足，不把已有版本字段当已生效，不改变华昇。
- [ ] 按DDD§4.4实现CREATE未知回执的限定首次补查：尚无可信受理/终态且无已知安全冲突时，无法解析/状态未识别事实持久化后180秒才安全查原单，复用冻结的timeoutFirstQueryDelaySeconds。事实/到期点/下一Timer同事务，重启/重复或旧事实不重新起算或覆盖后续查询状态；不重发创建、不释放或切供，仁跃明确受理仍600秒、QUERY未知响应仍600秒，验签/鉴权失败及身份冲突不纳入，日历截止优先。按`docs/集成测试.md`本项目C-04第16项覆盖边界、回滚与重放；CREATE租约过期另按下项实现。
- [ ] 按DDD§4.3/4.4实现CREATE租约过期的限定首次补查：marker已提交、无已持久化可信受理/终态且无已知安全冲突时，当前代租约过期后将创建结果未知事实、首次持久化起180秒到期点及下一Timer同事务保存。复用冻结的timeoutFirstQueryDelaySeconds，marker后即使未发HTTP也不再CREATE；保留预留、不切供或释放，不伪造发送/网络超时/失败事实。已有超时/异常事实及有效调度不重算，重启/重复看门狗/旧事实不覆盖后续QUERY到期点或计数；可信事实、安全冲突及日历截止优先。按`docs/集成测试.md`本项目C-04第17项覆盖marker后未发、已发未落、回滚、围栏及时间边界，QUERY租约过期另按下项实现。
- [ ] 按DDD§4.3/4.4/4.5实现QUERY租约过期且无可应用结果的未知分支：仅当前代已过期且无已知安全冲突时，查询结果未知事实、连续NOT_FOUND清零、首次持久化起600秒到期点及下一Timer同事务，复用冻结的errorBackoffSeconds。该次已有有效结果/异常事实或原单已有更新调度时不另造未知；重启/重复看门狗/旧事实不重计时或清掉后续新计数。保留预留，不重下/切供/释放，不把中断算有效NOT_FOUND或伪造网络超时；可信事实、安全冲突及日历截止优先。按`docs/集成测试.md`本项目C-04第18项覆盖2→0→1计数、时间边界、回滚、旧任务及有效结果对照，不套用CREATE的180秒。
- [ ] 按DDD§4.5增加有效NOT_FOUND持久化后180秒分支、限定候选的连续3次计数与历史可信存在永久退出；计数/到期点/退出标记持久化、重复不多计，截止不延长。QUERY网络超时/连接失败、响应解析失败或状态未识别均清零连续计数、保留预留，从对应事实持久化起固定600秒再安全查原单已获确认；事实/清零/到期点/下一Timer同事务，复用冻结的errorBackoffSeconds。无立即/递增重试，不混入CREATE回执异常或验签/鉴权失败、身份冲突；重启、重复结果及旧事实重放不重置等待或再次清零、多计。完整恢复合同与协议资格齐备后接自动失败释放，不把三次认领、HTTP错误或坏响应算成三次有效NOT_FOUND。
- [ ] 同一请求输入下执行宕机/任务重投：marker前未发送才有创建资格；marker后无论是否真的发出均只核原单；旧代可信成功保留观察、不让旧permit改写租约。
- [ ] 扩展`T/border/fulfillment/RechargeFrequencyExecutionIntegrationTest.java`、`RechargeFrequencyTimerIntegrationTest.java`、`RechargeFrequencyLifecycleIntegrationTest.java`；确认华昇原路径不变。

验收：AC-05/07/08；测试核实际订单、预留和Timer状态，不以mock调用次数代替一致性证据。

### T04 回调Inbox、可信结果与资金（4～6人日；BE-A/QA）

依赖：T03。新增`J/border/fulfillment/application/singlestep/SingleStepCallbackInbox.java`、`J/interfaceapi/callback/SingleStepSupplierCallbackController.java`。修改`SupplierOrderLifecycle.java`、`J/infrastructure/security/SupplyChainSecurityConfiguration.java`与`aifuli-gateway/src/main/resources/application.yaml`；资金只经既有FundReservationService。

- [ ] 新增`T/border/fulfillment/SingleStepCallbackIntegrationTest.java`及`SingleStepCallbackRestContractTest.java`，经HTTP/公开入口覆盖重复、篡改、早回调、ACK丢失、相反终态和事务失败，确认旧代码缺少三家入口。
- [ ] 接DDD§6精确POST路由与各家原格式ACK，认证后构造稳定事件身份；Inbox消费、订单/资金/通知同事务，认证失败/事务回滚不成功ACK。
- [ ] 按DDD§7.4先独立持久化可信成功观察，再做资金事务；注入结果提交失败与并发失败观察，保证已持久成功证据阻断RELEASE，失败终态不自动翻转。
- [ ] 实现直充结果入口，使用原冻结结算方；缺实报PENDING、有效差额MISMATCH、非法金额人工。未知原则上保留，限定候选连续3次有效NOT_FOUND按DDD§4.5平台风险关单、一次释放原剩余预留；不将其改成供应商零交付事实，不切供或重购。曾可信存在永久排除，成功/冲突阻断释放；已失败释放后的迟到成功留证转Case、不自动翻转补扣。MANUAL停自动查询但保留认证迟到回调入口；QUERY网络异常、解析失败/状态未识别清零计数并保留资金，不由此声称未决恢复合同或自动释放接线已完成。
- [ ] 扩展`T/tradecore/account/FundReservationIntegrationTest.java`，核一次COMMIT或一次RELEASE、无双动作，权限/网关/日志保护均通过。

验收：AC-03/04/06/11；Controller不访问DAL，不复用华昇force-release/卡密成功permit。

### T05 RENYUE协议与首个闭环样板（3～4人日；BE-B，BE-A集成）

依赖：T01/02协议合同，最终接线依赖T03/04；C-02/03阻断真实发布。新增`J/supplieradapter/renyue/RenyueSupplierAdapter.java`、`RenyueProtocol.java`、`RenyueCallbackParser.java`，只分离传输、签名/回执和解析的真实职责。

- [ ] 新增`T/supplieradapter/renyue/RenyueSupplierAdapterContractTest.java`，使用已确认的独立向量和MockWebServer；覆盖请求签名与三种回执字段集不同、StoreNo为billno、秒时间、884分→8.84元。
- [ ] 按DDD§5.1实现form、SHA256withRSA、ReturnSign分段解密核对；非法回执未知，1000仅受理，01/02/03/04及2/3回调分类；不发ExpandParams。
- [ ] 禁客户端透明重试/跳转；接单步流程，运行受理→回调和受理→查单、坏回执与宕机后原单查询。C-04已修订确认仁跃正常受理后600秒首次补查，与其协议一致；创建请求超时事实持久化后180秒再具备安全资格时查原单，后续正常间隔600秒。查询网络超时/连接失败从异常事实持久化起固定600秒再查，不立即连续重试。正常/超时首次延迟及网络异常间隔分别冻结；其它未知分支恢复节奏待确认，平台策略仍须核实供应商准入，不写死为供应商通用SLA。

验收：AC-02～AC-07适用场景；不是能连通HTTP就关闭任务。

### T06 QNM开发期合同直接替换（3～4人日；BE-B）

依赖：T01/02、已确认USERKEY向量，最终接线依赖T03/04。修改现有`J/supplieradapter/qnm/QnmSupplierAdapter.java`、`QnmSignUtils.java`、`QnmProtocolParser.java`、`QnmCallbackParser.java`、`QnmHttpExecutor.java`及`QnmMaskingUtils.java`；不新建第二协议包或Legacy分派。

- [ ] 更新`T/supplieradapter/qnm/QnmSupplierAdapterContractTest.java`，先以独立向量、嵌套ResContent、整数元金额与真实编码证明旧字段/签名不兼容；旧断言改变需注明合同变化，不能把其他回归删除以求绿色。
- [ ] 统一userid/userkey/毫秒time/backurl、排序原始k=v的MD5大写；正常响应条件`ResCode=1且ResMes=OK`；100/100.00/"100"均10000分，0.001和溢出拒绝。
- [ ] 旧customerid/timestamp/notifyurl、整数按分猜测及SendKm执行分支退出首期入口；元数据与测试按新合同一致调整，只保留QNM_GAME_ORDER_V1注册名。
- [ ] 经HTTP测试回调userid/time/billno/orderno/status认证与纯文本OK；新订单接单步恢复，开发期旧订单回调/恢复不列测试范围，也不擅自删数据。

验收：AC-02/05/10；QNM不是“同时支持两种看起来类似的参数”。

### T07 SZ_CARD协议与中台回调（原估算3～4人日；BE-B）

依赖：T01/02及向量，最终接线依赖T03/04。新增`J/supplieradapter/szcard/SzCardSupplierAdapter.java`、`SzCardProtocol.java`、`SzCardCallbackParser.java`。

- [ ] 新增`T/supplieradapter/szcard/SzCardSupplierAdapterContractTest.java`，使用非对称字段值证明“排序值拼接”不同于k=v，覆盖小写MD5、JSON字符串、retCode2/3及success/fail/processing。
- [ ] 按DDD§5.3接/api/apiCharge与/api/queryOrder；冻结逐单callbackUrl；retCode3保留未知，不映射退款；意外cardInfo受保护留证而非直接交付/失败释放。
- [ ] 在获授权的中台测试商户验证冻结的逐单回调地址生效、原单定位/验签/ACK正确；不能验证则保持未发布，不把测试留空标成功。2026-10-17用户已排除与cardshop共用账号，不实现/验证旧入口分流或共享账号回调共存，不修改cardshop账号/回调。

验收：AC-02/05/11及C-07；DES卡密不在首期开发量中。

### T08 管理端适配与只读定位（2～3人日；FE）

依赖：T01冻结元数据DTO，最终依赖T04。修改`F/src/views/supplier/config/components/ConnectorFormDialog.vue`、`CredentialItemDialog.vue`、`MappingPanel.vue`、`CapabilityPanel.vue`、`F/src/api/supplier/config/types.ts`和`F/src/views/supplier/manage/detail/utils/fulfillment.ts`中实际受影响部分，不要求无必要地改所有文件。

- [ ] 扩展现有Node真实组件测试，覆盖CREDENTIAL_SET/单槽、私钥多行只写、拒绝封闭模板追加、卡密/余额不可选、Case原因与结算差异；命名`F/tests/supplier-single-step-config.test.mjs`仅在没有合适既有文件时新增。
- [ ] 接现有动态Metadata字段；表单不写死商户/密钥，不显示任意资金指令；三家首期仅手机号，QQ/其他账号及卡密不可作为本期供给发布，不删除其他既有业务的EXTERNAL_ACCOUNT模型。
- [ ] 执行§4前端门禁，真实浏览器检查三家配置、私钥空白回显、拒绝/人工/差异状态，捕获并逐张检查截图；DOM权限与提交正文另验，截图不证明真实交易。

验收：AC-01/11；已有前端基线失败与新增失败区分报告。

### T09 商品清单、配置与发布演练（2～3人日；运营/BE-B/QA）

依赖：T00生产观察、T01配置发布；正式草稿/发布另需目标环境授权。

- [ ] 按DDD§8形成不含秘密的审核清单：374条只是候选记录；隔离SZ_CARD0号/测试/重复目标和仁跃分以下成本，逐条标批准或拒绝原因。
- [ ] 在隔离环境经既有管理入口演练Supplier/Connector/Offer草稿与发布，记录cardshopProductId→Offer追溯、供价单位与账号类型；重复演练不重复上架。
- [ ] 对`T/tradecore/supply/ConnectorPublicationIntegrationTest.java`及既有Offer发布集成补必要合同验证；无共享SQL导入、不直接复用卡管主键/机构售价。
- [ ] 出具已批准SKU白名单和逐家配置核对单；目标环境执行前再只读复核生产源，秘密由受控渠道写入，不进入清单。

验收：AC-09与C-06；未审核记录没有发布资格。

### T10 联合质量门禁与故障演练（4～5人日；QA，后端/FE支持）

依赖：T04～T09适用任务完成；供应商未就绪可分家验收，但不得虚称三家全通过。

- [ ] 串行运行§4后端完整门禁、受影响网关回归及前端门禁，保留实际命令、制品/提交标识和报告；不得用跳过供应链单测制造绿色。
- [ ] 在disposable MySQL注入认领后宕机、成功事实落库后结果事务失败、Inbox唯一冲突、资金/Outbox失败；检查真实账本与任务，不只查日志。
- [ ] 验证新单退役/凭据轮换恢复、MANUAL迟到回调、MOBILE恢复竞争与非手机号目标拒绝及华昇201/202回归；不执行QQ/OTHER正向履约或旧QNM历史恢复兼容。
- [ ] 按确认后的NFR-04做隔离容量测试，记录样本数/P95/吞吐/积压和供应商响应延迟分项；验证真实顶层日志JSON、MDC恢复、限长/超时/错误响应和凭据不回显。
- [ ] 更新`docs/集成测试.md`本项目场景，分清自动化、供应商实机和浏览器记录；有阻断缺陷则No-Go，不降低断言或改资金语义绕过。

验收：AC-01～AC-11证据齐全，NFR-01～NFR-08适用门禁通过；本地通过不自动获得部署授权。

### T11 授权灰度、回滚演练与移交（2～3人日；运维/对接/QA/业务）

依赖：T10及C项解除、明确目标环境/商品/金额的交易和部署授权。

- [ ] 发布新制品/精确回调接线但不开供给；有新增Liquibase时核前向迁移与制品兼容，无需迁移时记录“不涉及”。不删除列或更改历史changeSet。
- [ ] 逐家白名单小额验证成功、经批准失败场景、未知恢复；先仁跃样板，再按合同就绪情况QNM/SZ_CARD，商品与金额预算由业务批准，禁止擅自制造生产故障。
- [ ] 核对原单冻结/供应商事实/账本/采购结果；停新Offer演练后，新在途仍能回调/查单，不能直接退到不识新检查点的旧镜像。
- [ ] 移交配置版本、白名单、监控/告警、Case处理边界、回滚责任人与证据索引；业务/测试/运维签收后才标记对应供应商交付。

验收：AC-12；上线结果按供应商登记，不把文档、合并、部署和真实交易验收混为一个状态。

## 4. 验证命令与证据要求

以下是未来实施命令，**本轮文档编写没有执行**。后端在本工作区根目录执行，同一Maven target不并行写；测试夹具使用disposable MySQL/Redis，具体Testcontainers/外部JDBC方式以选中测试夹具为准，不能把生产地址填入测试变量。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# A：仅供应链纯逻辑与架构；-am依赖模块可编译，但不运行其它模块纯单测
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**.*IntegrationTest,!**.*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# B：供应链完整集成/契约门禁，仍执行供应链单测；禁止-DskipTests
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**.*IntegrationTest,!**.*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=true verify

# C：仅用于迭代的定向集成（新测试类须已实现，不能以零测试代替通过）
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  -Dtest=SupplyChainModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ConnectorPublicationIntegrationTest,ConnectorCredentialSetIntegrationTest,SingleStepRecoveryIntegrationTest,SingleStepCallbackIntegrationTest,SingleStepCallbackRestContractTest,RenyueSupplierAdapterContractTest,QnmSupplierAdapterContractTest,SzCardSupplierAdapterContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=true verify
```

Surefire默认排除`*IntegrationTest/*ContractTest`，它们由profile中的Failsafe到verify阶段运行。报告看server与API实际`target/surefire-reports`、server的`target/failsafe-reports`，核测试数/失败/错误/跳过；`BUILD SUCCESS`加0测试不算验收。C不能替代A/B全量；根目录旧`scripts-gate-integration.sh`含过时路径与删库行为，本计划禁止直接执行。

网关新增路由在既有精确回调路由测试扩展三家正例、GET/多余路径负例；按新增类实际名称运行受影响网关/路径保护门禁。若改共享日志设施，只运行直接受影响回归并记录模块，不借此执行其它模块全量纯单测。

前端在F目录执行：

```bash
node --test tests/*.test.mjs
pnpm exec eslint src/views/supplier/config/components/ConnectorFormDialog.vue src/views/supplier/config/components/CredentialItemDialog.vue src/views/supplier/config/components/MappingPanel.vue src/views/supplier/config/components/CapabilityPanel.vue src/api/supplier/config/types.ts src/views/supplier/manage/detail/utils/fulfillment.ts
pnpm exec prettier --check src/views/supplier/config/components/ConnectorFormDialog.vue src/views/supplier/config/components/CredentialItemDialog.vue src/views/supplier/config/components/MappingPanel.vue src/views/supplier/config/components/CapabilityPanel.vue src/api/supplier/config/types.ts src/views/supplier/manage/detail/utils/fulfillment.ts
pnpm ts:check
pnpm build:dev
```

实际修改Vue样式时增加受影响文件Stylelint。浏览器使用现有Vite/隔离fixture流程，检查默认、私钥空回显、校验拒绝、人工/差异状态；不以模拟网络截图冒充供应商联调。既有失败先在未改基线确认，分别报告“新增问题/既有问题/未验证”，不能静默忽略。

## 5. 逐项需求追踪

格式为“需求 → DDD章节 → 任务 → 验收”；AC定义以SRS§7为准。

- FR-01 → §2 → T01/T09 → AC-01。
- FR-02 → §2.3/3 → T01/T02 → AC-01/07/10。
- FR-03 → §3/8 → T02/T09 → AC-09。
- FR-04 → §2.1/5 → T01/T05/T06/T07 → AC-01/02。
- FR-05 → §2.2 → T01/T08 → AC-01/11。
- FR-06 → §3 → T02 → AC-07/10。
- FR-07 → §4.2/5/9 → T03/T05/T06/T07 → AC-05/07。
- FR-08 → §7.1/7.2 → T03/T04 → AC-03/04。
- FR-09 → §4.3/4.4/4.5 → T03 → AC-05/07/08。
- FR-10 → §5/6 → T04/T05/T06/T07 → AC-02/11。
- FR-11 → §6 → T04 → AC-06/07。
- FR-12 → §4/6/7.4 → T03/T04/T10 → AC-06/07。
- FR-13 → §4.5/7.2/7.3 → T03/T04 → AC-03/04/05。
- FR-14 → §5.2/7.3 → T04/T06/T08 → AC-02/03/11。
- FR-15 → §2.1/4.3 → T02/T03 → AC-08。
- FR-16 → §4.3/7.5 → T03/T04/T08 → AC-05/11。
- FR-17 → §8 → T09 → AC-09。
- FR-18 → §2.1/5.2 → T01/T06 → AC-02/10。
- FR-19 → §3.3/7.4/8 → T02/T04/T10/T11 → AC-10/12。
- FR-20 → §9 → T03/T04/T05/T06/T07/T10 → AC-11。
- FR-21 → §6/8/9 → T01/T04/T08 → AC-01/11。
- FR-22 → §8/10 → T09/T10/T11 → AC-12。
- NFR-01 → §2.3/3/6/9 → T01/T02/T04/T05/T06/T07/T08/T10 → AC-01/02/11。
- NFR-02 → §4/6/7 → T03/T04/T10 → AC-03/04/06。
- NFR-03 → §3/4/7.4 → T02/T03/T04/T10 → AC-07。
- NFR-04 → §4.4/9 → T03/T10 → AC-11，另附容量测试实测报告。
- NFR-05 → §4.3/4.4/6/9 → T03/T04/T05/T06/T07/T10 → AC-05/11。
- NFR-06 → §1/2/3.4/10 → T01～T10 → AC-10/11及ArchitectureTest报告。
- NFR-07 → §1.1/3.3/5.2/7.3 → T02/T06/T10 → AC-10。
- NFR-08 → §10 → T00/T10/T11 → AC-01～AC-12及独立测试报告。

每个任务必须交付相应测试结果而不是等T10才补测试。T10做组合回归和故障/容量证据；T11补实际供应商与目标环境证据。人工/自动化记录统一维护[集成测试章节](../../集成测试.md#2026-10-15-renyueqnmsz_card-供应商接入待实施)。

## 6. 里程碑、发布与变更控制

- **M0/T0**：T00评审通过、责任人/隔离环境明确；合同未就绪家保持阻断。
- **M1/建议第2～4周**：T01～T04公共不变量和仁跃协议向量可跑；BE-B可并行准备合同/协议，不等待公共代码才读附件。公共事务文件单人负责，不能假设两名后端能把该串行链直接对半压缩。
- **M2/建议第3～5周**：T05～T09逐家闭环、管理端和清单可验收；仁跃样板先验证接口，QNM替换不夹带旧单兼容。
- **M3/建议第4～6周**：T10完整门禁、故障与回滚预案通过，形成逐家Go/No-Go。
- **M4/建议第4～6周**：在外部条件和授权满足时执行T11；未授权/外部等待就维持“待发布/待联调”，不把日历目标当已完成承诺。

关键路径是T00→T01→T02→T03→T04→仁跃样板→T10→T11；QNM正式合同或SZ_CARD账号/回调若更晚，成为对应供应商的独立关键路径。风险消耗超过预留、协议变化影响资金语义、需厘价/卡密或业务要求重发时，先变更SRS/DDD与估算，禁止直接调大重试次数解决。

交付包应包含评审版五文档、源码与中文提交、自动化报告、商品/配置版本清单、实际联调与浏览器证据、制品/回滚记录及签收。**当前T00保留明确延期的基线错误；T01公共映射钩子、T02材料基础及T03 HTTP预算基础已本地实现和回归，尚无三家完整协议/资金闭环、提交、推送、部署或供应商交易。不能以公共机制回归代替三家功能验收。**

## 7. T00启动与阻断登记（2026-10-15）

开发授权来自[本次执行线程](https://ampcode.com/threads/T-01a13edc-8056-773d-84f7-75b917eba752)用户“开始按照开发计划实施”；仅覆盖本地实现与隔离验证，不把启动请求解释为C项逐条确认或共享操作授权。工作区已是`Dev-1031`隔离worktree，沿用当前目录，完整保留上游五份未提交文档和集成测试追加记录。当前执行/自动化验证由本线程负责；业务、对接、运营、运维的具体责任人仍待指定，未编造签收。

### 7.1 合同门禁

- **C-01／已确认／范围决策解除**：2026-10-16用户逐项确认仅手机号直充、单商品单手机号单份（quantity=1）、不支持多份或自动拆单、SRS§4.1的FIXED/CNY整数分供价及REMOTE_ON_DEMAND按单采购、不预囤商品；QQ、其他账号和卡密全部后置，取代此前选择A，OTHER不在本期逐商品放行。本项不是功能实现验收，具体手机号商品白名单仍依C-06，C-05亦已逐项确认。
- **C-02／供应商对接负责人待指定／待确认／阻断对应可靠失败判据和真实放行**：重复单号作用域、保留期、仁跃去重及零交付拒绝码尚无新证据；NONE、未知只查不重建仍为保守设计，不提升幂等承诺。SRS C-05另行批准三次查无单的平台风险关单，不以供应商“不再执行”保证为前提，也不冒充该保证；有效NOT_FOUND的逐家识别合同与其它C-02事项仍待证据。
- **C-03／供应商对接负责人待指定／待确认／阻断对应协议合同定稿与发布**：两附件SHA-256与SRS§3.2一致；卡管生产观察仍为2026-10-15 15:00—15:02，不再次查询生产。QNM正式USERKEY协议/独立向量、仁跃回执差异及SZ_CARD逐单回调实测未补齐。
- **C-04／平台持续期限、主要查询节奏及认领次数保护已确认，其余待定／对接及业务/运维负责人待指定／仍阻断完整恢复合同及容量验收**：2026-10-16用户确认持续低频补查，以Asia/Shanghai首次创建认领日期D为准，覆盖D+1全天，在D+2日00:00停止发起新查询；不是滚动24小时。收到明确受理响应后仁跃按协议等待10分钟，QNM/SZ_CARD等待3分钟；三家创建请求超时均从超时事实持久化起等待3分钟，再在具备安全资格时查原单，不重下/切供/释放预留。后续正常处理中每单每10分钟最多补查一次，查询网络超时/连接失败从异常事实持久化起固定等待10分钟再查；验签失败/身份冲突不归入普通网络重试。预警不停止安全补查，终态提前收敛，到期Case保留资金、迟到可信回调仍可收敛；次数保护改为每原单1000次成功QUERY认领，不截断正常跨日窗口。原短时截止/次数候选撤回；仁跃不再要求正常受理提前查询许可，CREATE未知回执/租约过期、QUERY租约过期及认领次数按下项追加确认。商户限流/并发、HTTP/lease、预警时点及容量目标未确认，其它安全处置合同及逐家供应商准入仍须补齐，见DDD§4.4及本文件§10。该业务决定尚未实现，不是供应商SLA或整体放行。
  - **CREATE未知回执追加决定**：无法解析/状态未识别、尚无可信受理/终态且无已知安全冲突时，异常事实持久化后180秒首次安全查原单，保留预留、不重发创建、不切供；事实/到期点/下一Timer同事务，复用冻结的timeoutFirstQueryDelaySeconds，重复/旧事实不重新起算或覆盖后续查询状态。仁跃明确受理仍600秒，QUERY未知响应仍600秒；验签/鉴权失败、身份冲突不纳入，可信事实及日历截止优先，CREATE租约过期另按下项确认。此为平台策略，发布前仍须核实逐家供应商准入，见DDD§4.4。
  - **CREATE租约过期追加决定（2026-10-17）**：marker已提交、当前CREATE租约过期且无已持久化可信受理/终态、无已知安全冲突时，从创建结果未知事实首次持久化起等待180秒，再安全查原单；marker后即使未发HTTP也不再CREATE，保留原预留、不切供或释放。事实/到期点/下一Timer同事务，已有超时/异常事实及有效调度不重算，重启/重复看门狗/旧事实不覆盖后续QUERY状态。复用冻结的timeoutFirstQueryDelaySeconds，可信事实、安全冲突及日历截止优先；不是供应商SLA，准入及第17项功能验收尚未完成，QUERY租约过期另按下项独立确认，详见DDD§4.3/4.4。
  - **QUERY租约过期追加决定（2026-10-17）**：当前QUERY租约过期且无可应用结果、无已知安全冲突时，记录该次查询结果未知、清零连续NOT_FOUND、保留原预留；从该未知事实首次持久化起固定等600秒再安全查原单，复用冻结的errorBackoffSeconds。事实/清零/到期点/下一Timer同事务；该次已有有效结果/异常事实或原单已有更新调度时不另造未知，重启/重复看门狗/旧事实不重新等待或清掉后续新计数。NOT_FOUND→NOT_FOUND→QUERY中断结果未知→NOT_FOUND只算连续1次；可信事实、安全冲突及日历截止优先，不重下/切供/提交或释放预留，不把中断当有效NOT_FOUND或网络超时。600秒是平台策略，供应商准入及第18项功能验收尚未完成，见DDD§4.3/4.4/4.5。
  - **QUERY认领预算追加决定（2026-10-17）**：三家每原单冻结maxQueryClaims=1000，统计成功提交的QUERY认领，非每日额度/查满目标/供应商SLA；未发HTTP即宕机也耗，未取得资格/认领回滚不耗、结果回滚不退还。重启/配置变化/结果重放/连续NOT_FOUND清零不重置累计数；999可领第1000次、禁第1001次，不丢弃最后合法结果。到限仍未知只停新查询、转Case保留预留，可信终态/限定三次风险关单/截止可提前结束，不改华昇。商户总限流/并发另行确认，详见DDD§3.1/4.3/4.4及本文件§10，第19项功能NOT_RUN。
  - **NOT_FOUND追加决定**：结果持久化后180秒再安全查询；仅从未有可信受理/处理中/存在证据的原单，连续3次有效NOT_FOUND且安全检查通过时平台最终失败并一次释放剩余预留。历史可信存在永久排除，重启/重复结果不重计；QUERY网络超时/连接失败、响应解析失败/业务状态未识别均清零连续计数、保留预留，并从对应事实持久化起固定600秒再安全查原单已确认，QUERY租约过期限定规则另见上项。事实/清零/到期点/下一Timer同事务，无立即/递增重试；重启/同一或旧事实不重置等待或清掉后续计数，不把未知响应当作有效NOT_FOUND或最终失败。验签/鉴权失败、身份冲突不纳入普通补查，CREATE未知回执及CREATE租约过期的限定180秒另按上项确认，其它安全处置/资源合同仍须补齐。完整恢复/自动释放接线仍依未决C-04及协议合同实施验证，详见DDD§4.4/4.5及§10。
- **C-05／基本策略、风险例外及主要计数规则已确认／剩余恢复合同依C-04待定**：2026-10-16逐项确认按采购方结算价预留、仅受理不扣款、可信成功一次提交原预留、可信零交付失败释放剩余预留且不按供价退款；除DDD§4.5限定的三次NOT_FOUND平台风险关单例外外，未知/人工保留预留，只核原商户原单、不重下不切供，到限停止自动查询转Case，迟到可信回调无冲突时收敛，接受长期占用代价。用户另已接受没有供应商“不再执行”保证仍三次关单的迟到成功损失风险；不伪造供应商拒绝/零交付证明，已失败释放后的成功转冲突Case而非自动补扣。实报缺失PENDING、有效相等MATCHED/差异MISMATCH与履约分离，成本高于售价也不自动转嫁或调账，非法金额仍未知。三家代码提供并在发布时校验封闭状态/错误模板，不能自由配置资金、重购或切供，新状态须合同/代码/测试及版本发布，未知状态不放行；华昇原判据不变。QUERY网络异常、解析失败/业务状态未识别均清零连续计数、保留预留并从对应事实持久化起固定600秒再查已确认；查询预算及剩余异常分支恢复合同仍依C-04，供应商可信失败仍依C-02，见SRS§8；不等于功能验收、T00/M0整体通过或真实资金操作授权。
- **C-06／运营负责人待指定／待确认／阻断T09白名单和商品发布**：374条仍只是候选源记录，QQ/OTHER及卡密本期排除；没有擅自批准手机号SKU或厘价舍入。
- **C-07／共享账号共存已排除、订单号与中台回调准入仍待证据／对接/运维负责人待指定**：2026-10-17用户明确不考虑与cardshop共用账号，共享账号回调共存/分流和跨系统额度协调退出本期，非相关验证PASS。24位billno长度/字符/唯一作用域、中台商户与冻结回调地址生效仍依C-03/C-07/C-08阻断对应实机放行；不代为开通账号、不修改cardshop配置，不再要求旧中台QNM排空。
- **C-08／本地隔离测试条件已具备，外部负责人待指定／部分就绪**：JDK21、Docker及Testcontainers MySQL可运行；本轮不配置外部JDBC覆盖，不写共享库。测试商户、出入口及推送/共享迁移/部署/真实交易授权未取得，继续阻断T11及实机操作。

### 7.2 基线与后续执行边界

- 原业务源码未改时执行§4命令A：API 125项、server 2309项通过，失败/错误/跳过均为0，含架构门禁；证据`.amp/in/artifacts/sup3-t00-baseline-unit.log`。
- 同一未改业务基线执行§4命令B：集成/契约963项，8项失败、3项错误、0跳过，完整门禁未通过；证据`.amp/in/artifacts/sup3-t00-baseline-verify.log`及已保全的`sup3-t00-baseline-reports/`。具体失败与复验记录见`docs/集成测试.md`本项目章节。
- 原基线Liquibase资源末尾为067、DATABASECHANGELOG为69个changeSet；文件编号不是迁移总数。2026-10-16收口新增068，仅补066四列时间注释，当前为70个changeSet、129张业务表、311列DATETIME(3)，不改历史变更集或业务数据。
- **T00未完成、M0未达成**。C-01/C-05业务决策已确认，T01公共钩子已隔离验证；T02材料基础切片已实现并通过组合回归，见§9。三家元数据/协议行为、完整冻结/单步闭环及T03～T11未完成，逐家合同仍按§7.1阻断。用户已授权除到店创建/并发两项外的基线收口，进度如下；不得降低断言或清理旧数据绕过基线，也不扩大成修复所有历史模块。

### 7.3 本轮基线收口（2026-10-16）

- **明确延期**：`ChannelOrderNumberIntegrationTest`到店首次创建/同键并发两项不修、不禁用、不降低断言；完整门禁继续报告，不据此宣称全部通过。
- **数据边界**：用户明确中台全部为开发测试数据，不要求历史订单/核实记录保留。本轮新增的历史Case保留夹具已撤除，不为旧数据增加兼容/排空工作；既有022时间语义测试不删。该决定不授权清库，也不取消新方案订单的幂等、资金与持久恢复要求。
- **迁移**：7项旧数量断言按实际065～067合同更新；补新表/列存在及时间列精度/时区检查。先复现066四列缺少Asia/Shanghai注释，再追加068最小修复；不运行共享迁移。
- **Outbox**：已确认处理器计数先于PUBLISHED提交。原10秒有界等待现同时检查计数与数据库终态，再核发布时间/租约清空，不修改生产投递逻辑或增加固定sleep。
- **鉴权**：完整应用夹具增加真实隔离Redis发号依赖，nonce仍在MySQL；按10-12已定合同修正停用能力的旧断言，摘要查询到达业务且消费nonce，创建/资产继续在nonce前拒绝。生产认证逻辑未改。
- **定向证据／最终源码10:34复验PASS**：撤掉新增历史Case保留夹具后重新编译并运行8类集成，共54项、失败/错误/跳过0，另模块架构31项、华昇元数据4项通过；最终日志`.amp/in/artifacts/sup3-t00-closure-final-source.log`。此前`sup3-t00-closure-targeted-final.log`为含已撤夹具的迭代证据，不替代最终结果。
- **完整门禁／10:32完成／保留延期项FAIL**：API单测125、server单测/架构2309均通过；集成/契约1006项、0失败、2错误、0跳过，唯一错误为明确延期的到店创建/并发两项，仍报`Project funding owner mismatch or project missing`。日志`.amp/in/artifacts/sup3-t00-closure-full.log`，原始129类XML及汇总保全于`sup3-t00-closure-full-reports/`。相比原963项增加43，不是新增43个测试：供应商配置夹具恢复后1项变7项，完整鉴权启动恢复后1项变38项。完整门禁编译时尚含随后撤除的Case保留夹具，最终受影响8类以上述10:34源码复验为准。不得把0失败误写为全通过。
- **前端基线证据**：修改前全量Node369项中1项引用已删除模块失败；改为执行真实RuleForm保存入口后370项全通过。修改文件ESLint/Prettier通过，`build:dev`通过；`ts:check`仍有53项既有错误，逐条与修改前相同，没有关闭检查或以构建代替类型通过。原始错误明细见`.amp/in/artifacts/sup3-t00-frontend-types-{before,after}.log`，涉及定价类型、重复DTO字段、缺失导入、Element Plus空值绑定及未使用声明等，跨供应链/AI/BPM/MES等既有模块；本轮只收集基线，不扩大修复这些历史模块。
- **交付状态**：仅本地变更，未提交/推送/部署/共享迁移/供应商交易；C-01/C-05业务决策已解除，供应商合同仍按§7.1阻断，历史数据保留不再列为缺口。

## 8. T01公共映射钩子实施记录（2026-10-16）

- **已实现**：AdapterMetadata增加默认无附加约束的validateMappings；配置验证、发布和已发布解析都按真实Metadata调用。华昇原严格方法仅增加@Override，其额外参数与能力专属检查保留；QNM模板、能力、凭据、协议与可追加行为均未改变。未注册RENYUE/SZ_CARD，未修改资金、恢复、前端或数据库结构。
- **RED／14:11**：先新增ConnectorCredentialSetIntegrationTest三边界参数化回归；旧业务实现运行3项、3失败、0错误/跳过，均因篡改后未抛异常，不是编译或夹具启动错误。证据`.amp/in/artifacts/sup3-t01-mapping-red.log`。
- **GREEN／14:13**：最小钩子改造后，同一3项回归全部通过；每个边界同时核事实篡改、非法错误表、无发布副作用和恢复合法模板后的正常流程。证据`sup3-t01-mapping-green.log`。
- **组合回归／14:15**：供应链API单测125、server单测/架构2309均通过；ConnectorPublicationIntegrationTest 1、ConnectorCredentialSetIntegrationTest 10、SupplierConfigurationQueryIntegrationTest 4、HuashengFrozenConfigurationIntegrationTest 4、HuashengOperationPersistenceIntegrationTest 1，共20项真实MySQL集成全部通过。日志`sup3-t01-mapping-regression.log`及所选XML/汇总`sup3-t01-mapping-regression-reports/`均位于`.amp/in/artifacts/`。
- **契约回归／14:16**：QnmSupplierAdapterContractTest 12、HuashengOperationExecutorContractTest 10、SupplierConfigurationAdminRestContractTest 8，共30项全部通过，另架构31/华昇元数据4复跑通过。证据`.amp/in/artifacts/sup3-t01-mapping-contract.log`。本批未重跑完整1006项集成；T00两项延期错误继续保留，不以定向通过宣称完整门禁全绿。
- **剩余与交付**：T01整体、AC-01及三家模板尚未完成；按§7.1补齐C-02/C-03协议与可信失败证据，再做逐家Metadata/Schema及完整接线。本切片执行时C-04查询预算未确认，后续1000次认领决定见§10，不纳入本切片通过证据。均为本地未提交修改，无推送/部署/共享写入/真实交易；到店两项仍按用户要求延期。

## 9. T02材料基础切片实施记录（2026-10-16）

- **范围**：先修订DDD§3.2，再实现冻结槽身份/解析、按Metadata键接管材料、可关闭Ticket及工厂新入口。槽版本为原String，集项版本为Integer；供价只投影BOrder冻结字段。旧24/27/30字段构造、secretKey/CredentialMaterial路径及华昇专用入口保留；没有注册新供应商、接协议或调整资金动作，也没有新增本切片迁移。
- **材料RED／14:50**：接口桩阶段运行ResolvedCredentialMaterialTest 3项，1失败/2错误，证明材料消费/所有权合同尚未实现；不是将该阶段当作既有业务缺陷。日志`.amp/in/artifacts/sup3-t02-material-red.log`。
- **Ticket RED／14:52与纯装配提取／14:54**：新增工厂测试在新入口桩上报未实现；提取共用装配后，原11项Ticket测试和3项材料测试全部通过。日志`sup3-t02-ticket-red.log`和`sup3-t02-ticket-refactor.log`；材料测试迭代中的空数组断言已修正，不降低非空材料必须擦除的合同。
- **首轮GREEN／14:57**：工厂12项、材料3项、真实MySQL冻结槽/集/并发3项全部通过。冻结版本01与1不等、漂移拒绝、锁覆盖解析、材料关闭和装配失败擦除、884分原供价不被传入Offer的999分替代均覆盖。日志`.amp/in/artifacts/sup3-t02-frozen-green.log`。此前冻结入口桩阶段3项集成为1失败/2错误，日志`sup3-t02-frozen-red.log`。
- **事务移交RED／15:00**：新增真实MySQL故障注入，在材料解析完毕后于短事务beforeCommit抛异常；工厂未取得返回值，槽/集全部3份数组仍有内容，1项测试失败、0错误/跳过。日志`.amp/in/artifacts/sup3-t02-handoff-red.log`。随后冻结槽/集解析器注册afterCompletion，未提交或结果不明时擦除未移交数组，保留原异常因果与身份/锁序合同；不在中间层重复打日志。
- **组合回归／15:10完成／PASS**：供应链API单测125、server单测/架构2313，失败/错误/跳过均0；13类定向真实集成/契约133项全部通过，含冻结材料4项、华昇冻结/持久化/恢复/重启、手机号频控执行/Timer、标准项目HTTP闭环、配置发布/凭据集及三类既有契约。事务提交故障场景已复验通过。日志`.amp/in/artifacts/sup3-t02-regression.log`，所选13类原始XML与汇总保全于`sup3-t02-regression-reports/`；实际命令及分项见`docs/集成测试.md`的T02材料基础节。本批未重跑完整集成，T00到店两项仍按要求延期，不宣称完整门禁全绿。
- **剩余与交付**：基础材料入口尚无生产Runner调用；checkpointPayload不直传信封密文。完整检查点/AAD/内容摘要、受理同事务、既有单退役读取仍未完成，不将本切片标为T02或AC-07整体完成。本切片执行时C-04查询预算仍为建议，后续1000次认领决定见§10，原133项回归不验证该新合同；供应商合同/向量、商品白名单及外部操作继续依§7.1。均为本地未提交修改，未推送、部署、写共享库或发起真实供应商交易；到店两项仍延期。

## 10. C-04持续低频与跨日窗口确认（2026-10-16）

- **依据与撤回**：用户追问原数值依据后，确认仁跃60分钟/30次、QNM/SZ_CARD30分钟/20次没有供应商合同或订单时延/容量实测支撑，已撤回作为实施参数。只读核对cardshop两个Adapter、请求查单调用链、定时任务分派、回调和告警SQL；没有访问数据库或供应商。源码摘录在`.amp/in/artifacts/cardshop-c04-source-excerpts-20261016.txt`；10分钟未决筛选是告警，不是失败/停查上限，不能复制其SZ_CARD异常重购行为。
- **用户已确认**：选择持续低频原单补查，不选一次补查即转人工；北京时间首次创建认领日期为D，覆盖完整D+1日，到D+2日00:00不再发起新自动查询。提前预警不停止安全补查，可信终态提前结束；截止仍未知则Case保留资金，不退款/重下/切供，迟到可信回调仍可收敛。不是滚动24小时，次数保护须与完整跨日窗口匹配。
- **正常间隔追加确认**：用户确认正常处理中每单每10分钟最多补查一次（processingIntervalSeconds=600）。平台按查询成本与兜底延迟取舍，不冒充供应商额度承诺；采购方反复查单不能增加上游调用。该次确认不包含首次补查、异常退避、HTTP/lease或次数数值；正常受理首次补查随后按下项另行确认。
- **正常首次最终修订**：用户因仁跃协议依据，将正常明确受理后首次补查改为仁跃10分钟（firstQueryDelaySeconds=600）、QNM/SZ_CARD3分钟（180），取代此前三家统一3分钟目标。从经过协议与身份校验的明确受理响应接收时点计算，尚无可信终态、未达截止且具备查询资格才补查；受理事实与到期点持久化，重复受理/重启不顺延。仁跃正常路径与附件一致，不再保留要求提前查询许可的差异门禁。
- **创建请求超时首次确认**：用户同意三家从超时事实持久化起等待3分钟（timeoutFirstQueryDelaySeconds=180），再在具备安全查询资格时查原商户/原单；不重新下单、不切供、不释放预留。超时事实与到期点同事务保存，重启/重复事实不顺延，不绕过执行资格/租约/安全校验或延长日历截止。与正常首次延迟分开冻结；不扩大到回执异常、仅租约过期或查询请求自身超时。三家超时及QNM/SZ_CARD正常首次属于平台策略，仍须核实供应商准入。
- **查询网络异常间隔确认**：用户同意三家补查请求本身网络超时/连接失败，从该次异常事实持久化起固定等待10分钟（errorBackoffSeconds=600）再安全查原单，不立即连续重试、不递增退避，不CREATE/切供/释放预留。参数随检查点冻结，异常事实与到期点同事务保存；重启/重复事实不重置到期点，每次新的实际查询网络失败按新事实等待600秒，日历截止和可信终态优先。验签失败/身份冲突等不归入普通网络重试，不据此扩大到其它未知结果。
- **有效NOT_FOUND追加确认**：三家从该次有效结果持久化起等待3分钟（notFoundIntervalSeconds=180）再安全查原单，区别于正常处理中/网络异常600秒。仅从未有可信受理、处理中或存在证据的原单，连续3次有效查询结果（notFoundConsecutiveThreshold=3）且共同检查通过时平台失败、一次释放原采购方结算价的剩余预留；曾可信存在者永久退出候选，仍按NOT_FOUND180秒间隔核实，不因后续查无单/重启重新入选。用户确认接受缺少供应商“不再执行”保证的迟到成功损失风险；这是平台风险关单，不把原始NOT_FOUND改成供应商REJECTED/零交付证明。已持久成功/存在或冲突阻止释放；失败释放后迟到成功留证转Case，不自动翻转或补扣；不重购、不切供，华昇四次7014不变。未满足条件先到日历截止仍Case保留资金。
- **网络异常打断计数确认**：用户确认查询网络超时/连接失败清零已有连续NOT_FOUND计数，不暂停或累计；异常事实、清零及600秒下一到期点同事务。NOT_FOUND→网络超时→NOT_FOUND→NOT_FOUND只算连续2次，还需一次有效NOT_FOUND才达3次阈值。重启/重复任务/同一结果或已处理异常重放不再次清零或多计，不能删除历史可信存在永久退出证据、重置截止或提前释放；本项不擅自决定其它未知响应的连续性与恢复节奏。
- **未知响应打断计数追加确认**：用户同意查询已收到响应但解析失败/业务状态未识别时也清零连续计数，保持结果未知、保留预留，不把它当作第三次NOT_FOUND或供应商最终失败。NOT_FOUND→NOT_FOUND→解析失败→NOT_FOUND只算连续1次；事实/清零同事务，重启/旧事实重放不清掉后续新计数，原始证据/永久退出/截止不变。已可信证明原单存在者仍永久排除；该次先确认计数，随后另行确认的QUERY间隔及CREATE未知回执首次间隔见下两项，不放开验签/身份冲突的普通补查。
- **未知响应补查间隔追加确认**：用户随后同意三家QUERY响应解析失败/业务状态未识别，从该次未知响应事实持久化起固定等待10分钟，再在具备安全查询资格时核实原单，复用冻结的errorBackoffSeconds=600；不是供应商协议时限。无立即/递增重试、不重购/切供/释放预留；事实、清零、到期点及下一Timer同事务，每次新的实际未知响应重新等待600秒，重启/同一或旧事实不重置等待或清掉后续计数。可信终态提前收敛、日历截止优先且不顺延；验签失败、鉴权失败、身份冲突不纳入普通补查。CREATE未知回执及CREATE租约过期另按下两项180秒处理，QUERY租约过期随后独立确认见后项。
- **创建未知回执首次间隔追加确认**：用户再次同意三家CREATE已收到回执但无法解析/状态未识别，且尚无可信受理/终态、没有已知安全冲突时，从该次异常事实持久化起等3分钟再安全查原单；复用冻结的timeoutFirstQueryDelaySeconds=180，不伪造网络超时事实。原预留保留、不重新CREATE或切供，事实/到期点/下一Timer同事务；重启、同一或旧事实重放不重新起算或覆盖后续查询状态。仁跃明确受理仍按协议600秒、QUERY未知响应仍600秒，可信存在/成功证据及日历截止优先；验签/鉴权失败、身份冲突不适用，CREATE租约过期随后独立确认见下项。平台策略与逐家供应商准入证据仍分开，实施与第16项验收均未完成。
- **CREATE租约过期首次间隔追加确认（2026-10-17）**：用户同意三家CREATE可能发送标记已提交、执行租约过期且无已持久化可信受理/终态、无已知安全冲突时，从创建结果未知事实首次持久化起等待3分钟，再安全核实原单，复用冻结的timeoutFirstQueryDelaySeconds=180。租约过期不证明发送、超时或业务失败，marker后即使HTTP未执行也禁止再CREATE；保留原预留、不切供或释放。事实/到期点/下一Timer按原单围栏同事务，已有超时/异常事实与有效调度不重算；重启/重复看门狗/旧事实不重新起算或覆盖后续QUERY到期点和计数。仁跃已明确受理仍600秒，可信存在/成功、安全冲突及日历截止优先；该次只确认CREATE，QUERY租约过期随后独立确认见下项。供应商准入及功能实现仍待补齐，第17项NOT_RUN，不把平台180秒写成供应商承诺。
- **QUERY中断恢复追加确认（2026-10-17）**：用户同意三家当前QUERY租约过期且无可应用结果、无已知安全冲突时，记录该次查询结果未知、清零连续NOT_FOUND并保留原预留，从该未知事实首次持久化起固定等10分钟，再安全查询原商户/原单，复用冻结的errorBackoffSeconds=600。事实/清零/到期点/下一Timer按原单围栏同事务；该次已有有效结果/异常事实或原单已有更新调度时不再制造未知，重启/重复看门狗/旧事实不能重计时或清掉后续新计数。NOT_FOUND→NOT_FOUND→QUERY中断结果未知→NOT_FOUND只算连续1次；不重下/切供/提交或释放预留，可信终态、历史存在保护、安全冲突及日历截止优先。每次新的当前查询再中断仍固定600秒，不立即/递增重试，不把中断当有效NOT_FOUND或伪造网络超时；此为平台策略，非供应商SLA，实现与第18项验收均NOT_RUN。
- **QUERY认领预算追加确认（2026-10-17）**：用户同意仁跃/QNM/SZ_CARD统一每原单maxQueryClaims=1000，按成功提交的QUERY认领计数。最长48小时、最快180秒后续间隔保守包络960次，1000留余量；这是理论核算，非供应商额度/SLA或容量实测，不要求查满或每日重置。认领提交后未发HTTP即宕机也耗，未取得资格/认领回滚不耗、结果回滚不退还；上限与累计数冻结持久化，重启/配置变化/结果重放/NOT_FOUND连续计数清零不重置。999可领第1000次、禁第1001次，不能因已到1000丢弃最后合法结果或迟到可信回调，仍按原事实/围栏/冲突合同处理。可信终态/限定三次风险关单/日历截止可提前结束，到限仍未知只停新查询、转Case保留预留，不改华昇；商户总限流/并发另行确认，详见DDD§3.1/4.3/4.4，第19项验收NOT_RUN。
- **设计同步**：SRS§8及DDD§3.1/4.4/4.5/7.2/7.3同步，检查点冻结日历策略/时区、NOT_FOUND间隔/阈值，首次创建认领时一次性持久化绝对截止，恢复不顺延；计数/永久退出证据需持久化，重复结果不能多计。以上均为待实现合同，不把此前材料基础133项回归当作跨日或风险关单验证。
- **商户限额共享范围追加确认（2026-10-17）**：三家在中台内，同一供应商平台的同一真实商户跨Connector、配置版本和服务实例共用总限流/并发额度，不按每处叠加；复制、版本发布或凭据轮换不增额，不同供应商或不同商户不误并。用户不考虑与cardshop共用账号，共享账号回调共存/分流及跨系统额度协调退出本期，不修改其账号/回调或代为开通账号。后续T01/T03接线按DDD§2.2/4.4验证该范围，补集成测试第20项（NOT_RUN）；具体数值及等待后的具体调度机制仍待确认，统一当前配置的生效原则见下项，不能用单笔1000次预算替代商户总限额或把字段落库当运行时已生效，不改华昇。
- **额度不足等待追加确认（2026-10-17）**：到期QUERY暂缺商户速率/并发额度时延后调度，保留原预留、不重下/切供，不记供应商超时、NOT_FOUND或最终失败。未取得QUERY认领不耗1000次预算，已提交认领不退还/重置；如已认领当前QUERY确实租约过期且无可应用结果，仍按既定未知、清零及600秒合同处理。恢复额度后仍校验原单安全查询资格，不顺延截止；截止仍未知转Case保留预留，期间可信终态沿原单合同收敛。按DDD§4.4补集成测试第21项（NOT_RUN），等待原则不再待定，具体调度及额度数值仍待确认。
- **限额调整生效追加确认（2026-10-17）**：用户同意商户总限额使用当前统一配置，不随原单或Connector版本冻结，新单外呼/旧单补查均遵守新限额；调低不取消已发HTTP、不判失败/动账，已有在途数低于新并发上限才准入新请求，调高亦不绕过原单安全资格。原单冻结材料、恢复间隔、1000次认领上限/累计数及截止不变；具体数值、配置管理/一致性实现尚待补齐，不授权改共享/生产配置。按DDD§2.2/3.1补集成测试第22项（NOT_RUN），生效原则不再待定，不改华昇。
- **商户额度证据暂缺（2026-10-17）**：用户确认目前没有三家供应商明确给出的商户总请求速率、并发上限或对应后台截图，生产数值仍待核实，不编造默认值或视为无限额。外部证据缺口不阻断与其无关的本地实现；第20～22项用明确标为夹具的限额验证共享/等待/调额，但测试取值与通过结果不构成供应商许可，真实供给发布门禁保持。当前三项功能仍NOT_RUN，不将本次信息确认记为C-04整体放行。
- **运营提醒退出本期（2026-10-17，覆盖此前提前预警确认）**：用户明确“不接入通知渠道，忽略”，此前30分钟一次运营提醒退出本期实施与验收；移除T03对应待办，不新增提醒参数、标记、Timer或待通知事件，不用WARN日志替代通知。历史记录中的预警时点、渠道、接收人及投递/送达待定项不再构成本期门禁；第23项标记OUT_OF_SCOPE，而非通过或本期待执行。持续低频安全补查、1000次认领保护、截止后的Case及保留资金不变，不修改既有频控告警、日志或采购方订单结果通知合同，不改华昇。
- **HTTP与执行租约追加确认（2026-10-17）**：用户同意连接10秒、读写各20秒、整次HTTP预算30秒、CREATE/QUERY租约60秒作为三家首期平台初值，授权推进本地实现及隔离测试。阶段不叠加突破总预算，租约从认领起算，发送前核剩余资格，排队不重新获得完整时长；四值逐单冻结，联调修订只影响新单，不随当前商户额度更新。禁客户端重试/跳转，超时或租约过期仍不证明远端未执行，不重下或释放；沿原事实持久化后的180秒/600秒合同恢复。初值非供应商SLA/实测，真实供给发布仍逐家验证；历史HTTP/lease待定项以本条为准，按DDD§4.6及集成测试第24项实施，不改华昇。
- **仍需确认**：商户总限流/并发的具体数值及等待后的具体调度机制、容量目标，其它安全异常处置合同及逐家供应商准入仍须补齐；HTTP/lease初值已确认，运营主动提醒及通知渠道已退出本期，商户共享范围、额度不足等待及统一当前配置的生效原则不再待定，配置管理/跨实例一致性须按已确认原则实现验证。CREATE未知回执/租约过期的限定180秒首次补查，以及QUERY解析失败/状态未识别、当前QUERY租约过期且无可应用结果的清零计数、保留预留及对应未知事实持久化后固定600秒规则、每原单1000次成功QUERY认领保护均已确认，不再列为待定；验签/鉴权失败、身份冲突没有因此获得普通补查授权。完整恢复/自动释放接线仍依上述门禁实施验证；C-04仅部分解除，按已确认节奏及认领上限补足测试覆盖，不重新选择无依据的短时截止，已确认的平台参数不替代供应商准入证据。
- **交付边界**：以上决定先于实现记录；随后仅完成§11的HTTP预算基础代码及隔离验证，不代表本节跨日/资金合同已实现。未新增本切片数据库迁移、提交/推送、部署或真实交易，T00到店两项仍延期。

## 11. T03 HTTP预算基础切片实施记录（2026-10-17）

- **范围**：先更新SRS§8、DDD§3.1/4.4并增加§4.6，再新增`SingleStepTimeoutContract`、`SingleStepHttpCallFactory`及契约测试。纯值承载10000/20000/30000/60000ms初值，工厂用固定单调截止裁剪剩余资格、覆盖响应体，POST请求体one-shot与网络发送闩锁共同禁止透明补发，关闭跳转/认证补发。无Spring注册，现有QNM/华昇运行路径不变。
- **RED／13:37**：修正滴流夹具后，接口桩阶段19项为15失败、0错误，缺少预算、资格/合同校验及禁补发行为；4项正常对照通过。证据`.amp/in/artifacts/sup3-http-budget-red-final.log`及`sup3-http-budget-reports/red/`，此前夹具迭代日志不作最终RED证据。
- **503反例与修复**：关闭`retryOnConnectionFailure`仍触发OkHttp4.12.0对`503 + Retry-After: 0`的follow-up；仅靠网络闩锁会丢失原响应而抛异常。先验证该反例，再按DDD§4.6声明一次性请求体，在保留原503/响应体的同时只发送一次；不以HTTP503判定供应商最终失败。
- **GREEN／13:42**：最终20项契约及31项模块架构通过，失败/错误/跳过均0，日志`sup3-http-budget-green-final.log`及`sup3-http-budget-reports/green/`；覆盖form/JSON原字节、非法合同、滴流总预算、剩余资格、过期/延迟执行、302/307/408/503及收请求后断连。
- **初值探针／13:45**：用批准初值调用本地MockWebServer，50字节每秒1字节持续响应，不触发20秒读超时，但整次调用约30089ms以`InterruptedIOException`终止，服务端仅1次请求。证据`sup3-http-budget-initial-30s-probe.log`；这是本机HTTP实测，不是供应商SLA、数据库租约或远端停止执行证明。
- **组合回归／13:46／PASS**：API单测125、server单测/架构2313全部通过；新HTTP契约20、QNM既有契约12、华昇执行器10和HTTP48，共90项契约通过，失败/错误/跳过均0。日志`sup3-http-budget-regression.log`、本轮XML及汇总`sup3-http-budget-reports/regression/`位于`.amp/in/artifacts/`。实际命令见`docs/集成测试.md`第24项后的实施记录；未重跑全量真实MySQL集成门禁，不读取旧报告凑本轮测试数。
- **剩余与交付**：本项只完成HTTP调用构造基础，无生产调用者；四时限持久化冻结/摘要、数据库60秒认领、Runner/Timer与未知事实同事务、跨实例商户限额、实际外呼日志/认证及资金闭环仍未接线。第24项PARTIAL、T03整体和C-04均未完成，不发布供给；到店两项仍延期，运营提醒仍OUT_OF_SCOPE。全部为本地未提交变更，无推送、部署、共享写入或真实供应商调用。

## 12. T02完整冻结与原单读取实施（2026-10-17）

**目标**：为后续Runner提供可验证的原单材料。今天受理的手机号订单，次日发布新价格/连接器或退役原版本后，仍只能读取原单号、手机号、价格、回调、凭据身份和恢复合同；原内容被篡改/凭据原身份失效则拒绝外呼，不回退最新配置。用户已授权本批本地实施；本节只记录该材料机制，不把冻结策略当作恢复/资金接线已完成。

**实施前源码依据**：`FulfillmentAcceptanceLifecycle.createOperation`普通路径仅对幂等键和版本ID拼接做摘要，不写单步检查点，配置解析失败还可能退回普通Operation；`PublishedConnectorConfigurationResolver.resolve`仅接受PUBLISHED和状态为10的映射，而发布退役会将版本/映射退役；`FulfillmentOperationTicketFactory.createWithFrozenCredentials`明确将checkpointPayload置空。HTTP基础无生产调用者，故先补T02而不是直接替换执行Handler。

**顺序与边界**：按12.1→12.2→12.3→12.4→12.5串行集成；每步先更新对应DDD细节，再用最小可编译接口桩取得行为RED，最小实现后复跑同一输入取得GREEN，不用编译失败冒充业务缺口。公共所有者仍由一个执行者修改。T01逐家Metadata/Schema、封闭协议向量未齐不阻断无关材料机制，但不注册新供应商或启用新QNM合同；以测试专用Metadata显式选择新路径，既有QNM/华昇继续原行为。完整协议接入仍按T01/T05～T07门禁执行。

### 12.1 T02-A：封闭检查点、AAD与配置内容摘要

文件：新增`J/border/fulfillment/domain/singlestep/SingleStepCheckpoint.java`、`J/border/fulfillment/application/singlestep/SingleStepCheckpointCodec.java`，新增`T/border/fulfillment/application/singlestep/SingleStepCheckpointCodecTest.java`。复用`J/infrastructure/security/EnvelopeCryptoPort.java`，仅借鉴华昇编码边界，不修改其Codec或泛化其四步骤。

- [x] 先细化DDD§3.1：固定JSON字段、大小/深度/长度边界、规范化字段顺序与集合排序、AAD原SEND绑定及临时明文字节所有权。目标引用优先复用FulfillmentRequest已有受保护存储；明确请求身份、Schema版本、目标摘要与密钥版本的绑定，不复制明文手机号到Timer/Outbox。
- [x] 从公开编解码入口验证：未知Schema/字段、重复键、尾随JSON、字符串冒充数字、错Operation/原步骤/版本/摘要均拒绝；同一配置重排对象键/集合仍得相同摘要，相同版本ID下改地址/回调/外部SKU/供价/恢复参数/凭据身份则摘要变化。独立构造规范化向量及预期值，不由被测函数生成自己的期望。
- [x] 实现加密检查点及SHA-256内容摘要，覆盖DDD§3.1已定内容；冻结10000/20000/30000/60000ms、600/180秒首次、180/600秒异常节奏、1000次上限和跨日策略/时区。版本发布/退役状态、商户当前共享限额及秘密明文不入配置摘要。此步只冻结策略，不能在受理时提前生成首次CREATE日期/绝对截止或实际认领租约。

验收：合法加密往返保持原合同，密文移植/错绑定/同ID内容漂移失败关闭；不调用供应商，不自行决定Case或资金动作。

### 12.2 T02-B：原单读取允许退役，新单准入保持不变

文件：修改`J/supplieradapter/metadata/PublishedConnectorConfigurationResolver.java`；新增`T/border/fulfillment/SingleStepFrozenConfigurationIntegrationTest.java`（同包复用受理夹具），保留`T/tradecore/supply/ConnectorPublicationIntegrationTest.java`作为新单拒绝退役的回归。

- [x] 细化DDD§3.3的内部绑定投影：由已落库BOrder/Operation与经认证检查点形成，不接受HTTP传入任意版本/摘要；解析器不依赖border DAL。先分离共用内容解析并验证旧入口行为不变，再增加原单入口。
- [x] 用真实MySQL发布配置、形成原单绑定、发布新版本并退役原版本；新单`resolve`仍拒绝旧版本，原单入口读取原PUBLISHED/RETIRED版本及对应生命周期的10/90映射，DRAFT拒绝。不能只放宽版本枚举而漏掉映射退役检查。
- [x] 在版本ID不变下分别篡改商户/地址/回调/映射/外部SKU或价格，原单入口必须拒绝；正常发布新版本不影响旧单，退役不改变内容摘要。凭据原身份停用/漂移拒绝，不使用最新槽或集；槽版本"01"与"1"不同，不能数值化。旧QNM切换前开发单不增加兼容入口，华昇不迁入。

验收：原单可核验退役版本，但不能通过该入口为新单选择退役供给；无设置currentVersionId或改写原版本/原凭据的副作用。

### 12.3 T02-C：受理事务原子保存检查点与恢复定位

文件：修改`J/border/fulfillment/application/FulfillmentAcceptanceLifecycle.java`、`J/supplieradapter/metadata/AdapterMetadata.java`、`J/border/fulfillment/domain/FulfillmentTimerTaskTypes.java`；扩展`T/border/fulfillment/FulfillmentAcceptanceIntegrationTest.java`。最小单步声明默认不启用，仅测试专用Metadata启用本片路径。

- [x] 先更新DDD§2.2/3.1/4.3：明确新路径选择、配置核验失败不能回退普通Operation、必需依赖缺失失败关闭及准备watchdog定位。复用现有Operation字段与资金服务，默认不新增表/列；如实际存储约束不满足，先修订DDD§3.4后单独追加迁移。
- [x] 通过现有公开受理入口，以真实MySQL验订单/BOrder、Operation检查点三字段与内容摘要、采购预留、准备watchdog、Outbox及回执成功一起提交；复用阶段故障注入，新增在检查点/Timer/预留/Outbox前后失败的回滚断言。回执抢占是先前独立事务，允许保留既有PROCESSING回执，不错误要求回滚整个claim；不能留下ACCEPTED或半份业务状态。
- [x] 用采购结算1000分、供价884分、quantity=1的非对称夹具，随后新版本改为1200/999分，断言旧单及预留仍按1000/884，初始仅FREEZE、无COMMIT/RELEASE。同项目同订单号并发重放只有一份有效受理/预留，异参拒绝；非MOBILE或quantity不为1在新路径拒绝。Offer只须允许数量1，不新增min=max=step=1的全局限制。
- [x] 准备watchdog在受理事务内持久化，执行Timer仍由既有Outbox Dispatch可靠生成；分开断言，不能把执行Timer错误改为受理事务直接创建。准备watchdog消费、CREATE认领及日历截止实算留到T03，不能仅有Timer行就宣称宕机恢复完成。

验收：受理不会提交缺检查点或缺准备watchdog的新路径原单；事务失败不残留孤立预留/任务或成功回执，已有协议受理语义不变。

### 12.4 T02-D：把经核验冻结内容交给Ticket

文件：修改`J/border/fulfillment/application/FulfillmentOperationTicketFactory.java`，按实际需要最小扩展`J/border/fulfillment/domain/OperationTicket.java`；扩展`T/border/fulfillment/application/FulfillmentOperationTicketFactoryTest.java`、`FrozenCredentialTicketIntegrationTest.java`及12.2的新集成测试。

- [x] 在DDD§3.2明确经核验检查点的装配入口和所有权；原步骤用于AAD，查询端点为QUERY_ORDER时不能改AAD身份。先验证检查点/原单绑定/内容摘要，再在订单和资金锁外按冻结身份解析材料；装配失败和短事务提交失败均擦除材料。
- [x] 验证发布新价格/回调/凭据绑定后，旧Ticket仍使用原冻结内容；传错供应商/Operation/目标引用/Offer版本拒绝。冻结目标损坏或材料不可用不借用当前目标/新密钥，不能借失败重新创建或动账。
- [x] 首创建和查询都消费同一原单冻结合同，HTTP四时限不从运行期最新配置重建；旧Ticket构造入口与华昇专用入口保持不变。首次交接只构造Ticket并关闭材料，不激活供应商Adapter或HTTP执行。

验收：从落库原单重新读取、核验、解密并构造Ticket的本地链可跑；断开旧应用实例后仍能重建同一原单材料，不把单次内存对象复用称作重启恢复。

### 12.5 T02-E：组合回归、证据与交付检查

- [x] 执行下列计划命令，确认新增检查点及原单读取测试确实被收集；供应链单测/架构及所选真实MySQL/契约失败、错误、跳过均0才记本批通过。新测试文件仅在上述职责成立时新增，不为每个私有方法拆测试。
- [x] 在`docs/集成测试.md`记录RED/GREEN、实际测试数量、原单重建与事务回滚证据；既有第24项仍PARTIAL，数据库60秒认领/发送围栏/Timer恢复/资金结果未接入，不能改为PASS。未执行项写NOT_RUN，保留T00到店两项延期和完整门禁未全绿的实际状态。

实际组合命令（2026-10-17执行通过；结果与补充回归见本节交付记录及`docs/集成测试.md`）：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Psupply-chain-integration \
  '-Dtest=com.aifuli.module.supplychain.**.*Test,!**.*IntegrationTest,!**.*ContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SingleStepFrozenConfigurationIntegrationTest,FulfillmentAcceptanceIntegrationTest,FrozenCredentialTicketIntegrationTest,ConnectorPublicationIntegrationTest,ConnectorCredentialSetIntegrationTest,HuashengFrozenConfigurationIntegrationTest,HuashengOperationPersistenceIntegrationTest,HuashengRestartIntegrationTest,RechargeFrequencyAcceptanceIntegrationTest,SingleStepHttpCallFactoryContractTest,QnmSupplierAdapterContractTest,HuashengOperationExecutorContractTest \
  -Dfailsafe.failIfNoSpecifiedTests=true verify
```

**后续顺序**：本批完成后，T03先做60秒持久认领/generation/CREATE marker及数据库时间到单调剩余资格转换，用隔离Adapter验证不重复发送；再接正常受理、CREATE未知180秒、QUERY未知600秒、跨日截止/1000次预算及NOT_FOUND连续计数；随后T04接可信观察/回调Inbox和一次资金收敛，最后按T05仁跃样板→T06 QNM→T07 SZ_CARD逐家闭环。连续3次NOT_FOUND的自动释放必须等历史可信存在保护、并发成功留证及结果事务验收完成，不能在单独计数测试通过后提前启用。

**并行准备但不代填的外部材料**：C-02/C-03的可信失败/可计数NOT_FOUND合同与独立向量、三家商户总速率/并发证据、手机号SKU白名单及中台回调/单号准入。用户已确认的HTTP/恢复时点不重复询问；未明确的安全异常普通补查或容量参数不从网络异常规则猜测。原单冻结底座可先行，真实供应商发布保持阻断。

**范围不变**：仅手机号直充、单份；不接QQ/OTHER/卡密，不实现运营通知，不考虑与cardshop共用账号，不为旧开发数据加兼容或清库，不修延期到店两项。本批未执行提交、推送、部署、共享迁移或真实供应商调用。

### 12.6 本批实际交付与证据

- **实现范围**：T02-A～E的本地检查点、配置摘要、受理原子冻结及原单Ticket重建机制完成；测试专用Metadata启用，生产三家仍未切入新路径。原步骤SEND用于AAD，QUERY只改变执行端点；回调域名/版本更新不能改变原单，原内容/目标/凭据身份异常失败关闭，无最新值兜底。
- **真实MySQL证据**：8个受理注入阶段均回滚业务/预留/任务/成功回执，独立PROCESSING claim可保留；8线程同请求最终一份预留。真实发布新Offer/Connector并退役原版本、关闭重开Spring容器/连接池后，旧单仍采购结算1000分/供价884分及原手机号/地址/回调，新单1200/999分，账户冻结总额2200分；不把上下文重开称为崩溃后的自动补查。
- **组合回归／18:21／PASS**：API125、server单测/架构2332，所选集成/契约84，失败/错误/跳过均0，日志`.amp/in/artifacts/t02-e-combined.log`。最终补充1项Codec集合排序/Integer版本/恢复策略摘要判别后，18:23单测/架构复跑为API125、server2333，均0失败/错误/跳过，日志`t02-e-final-unit.log`；该补充未改生产实现，不冒充又跑了84项集成。
- **保全与排障**：末次单测XML249份/2458项，组合集成契约XML12份/84项及Failsafe汇总保全于`.amp/in/artifacts/t02-reports/`，只复制日志中实际执行类，不汇总旧报告。各步RED/GREEN、失败中间日志及夹具修正见`docs/集成测试.md`“T02完整冻结与原单材料重建”。
- **剩余状态**：第24项由仅HTTP基础推进为HTTP+四时限持久冻结/材料重建PARTIAL，真实认领/marker/watchdog消费/未知调度/跨日预算/商户限额及资金结果仍未接线；第1～22项NOT_RUN、第23项OUT_OF_SCOPE。未重跑全量MySQL门禁，到店两项继续延期，不宣称三家已接通或可发布。全部本地未提交，无推送、部署或共享数据库写入。

## 13. T03本地执行与恢复核心实施记录（2026-10-18）

本批按用户“继续完成执行与恢复机制”推进，先细化DDD§4.7，再做RED/GREEN。只使用测试专用`TEST_SINGLE_STEP`及隔离MySQL；不把未确认的商户速率/并发值设为默认值或无限额，不提前接T04动账。

- [x] CREATE认领同事务取得手机号频控发送资格、generation、数据库时间起算的60秒lease、不可重发marker、首次CREATE日期对应D+2午夜截止及同代watchdog；失败整笔回滚。旧频控公开入口事务语义不变。
- [x] 事务外准备/释放原Ticket与秘密，原单认领转保守单调截止，执行前核活动原单/代次/lease；Composite转发带截止的新入口，旧Adapter默认拒绝而非退回不受约束的旧execute。
- [x] V1冻结态保持原样，认证加密V2持久运行进度；正常受理按响应接收时间加原冻结600/180秒，CREATE未知/过期按首次事实持久化加180秒，QUERY未知/过期按600秒，NOT_FOUND按180秒；事实、计数及下一Timer同事务，重放不重算。
- [x] 原单Timer与手机号频控恢复竞争相同资格；999时四工作者只取得一次第1000次认领，认领回滚不耗预算、结果回滚不退还，最后合法终态仍留证；到限或截止只开Case保留原预留。
- [x] NOT_FOUND连续计数、网络/响应未知及QUERY过期的2→0→1、可信存在永久保护、旧代存在/成功观察留证，以及旧代非网络故障不关闭新代；材料损坏看门狗转Case，正常FIFO等待不被准备任务误判。
- [x] 保留同一MySQL，两次销毁/重开Spring容器及连接池后，仅用持久watchdog/query载荷恢复；marker后HTTP前中断也只查原单，不重复CREATE、不依赖原Ticket/permit。此为容器重开与持久Handler验收，不冒充OS级强杀/真实供应商崩溃联调。
- [ ] 商户跨Connector/版本/实例共享当前总限额、等待/调额一致性与容量；尚无供应商数值证据，具体调度/管理方案仍待确认。第20～22项保持NOT_RUN。
- [ ] T04的回调Inbox、受保护完整终态证据及一次资金收敛；连续3次NOT_FOUND的最终失败/自动释放仍未启用。当前可信终态只追加观察并转Case，资金相关场景检查1000分预留不变及COMMIT/RELEASE为0。
- [ ] T05～T07生产协议Adapter消费冻结HTTP预算与截止、正式认证/错误分类及外呼日志、逐家准入和真实联调。三家Metadata仍关闭单步，隔离Adapter通过不解除发布门禁。

**最终验证／10:10／PASS**：组合`verify`完成，API125、server单测/架构2334，所选集成/契约193（18类，包含新恢复24及既有频控、QNM、华昇、Timer回归），失败/错误/跳过均0，耗时19分05秒。日志`.amp/in/artifacts/t03-combined-regression.log`；按该日志实际收集类核对并保全XML，`t03-reports/unit/`249份/2459项，`integration-contract/`18份/193项及Failsafe汇总，派生计数见`summary.json`。RED/GREEN、夹具错误及去围栏反例详见`docs/集成测试.md`的本批记录，不把失败日志文件名中的green当通过。

**交付边界**：C-04第24项仍PARTIAL，T03整体及三家端到端验收尚未完成。到店创建/并发两项继续延期，不改断言；本批无新增迁移、共享数据库操作、清库、通知、真实供应商调用、提交、推送或部署。§11、§12的未接线描述保留其历史时点，以本节为当前进度。

## 14. T04公共资金核心执行顺序（2026-10-18）

用户已批准按成功留证→原子资金收敛→三次NOT_FOUND风险关单的顺序本地实施。沿用本计划与DDD§7.6，不另开生产协议、通知或商户限额子项目；使用executing-plans在当前Dev-1031隔离工作树串行实现。

- [x] **A：成功保护RED/GREEN**。扩展现有`T/border/fulfillment/SingleStepRecoveryIntegrationTest.java`，由公开受理及Runner产生可信SUCCESS，使用隔离MySQL CHECK约束使COMMIT/结果Outbox写入失败；应保留加密成功证据但订单未成功、预留未动。修改`J/border/fulfillment/domain/SingleStepSupplierFact.java`、`domain/singlestep/SingleStepRuntimeCheckpoint.java`与`application/singlestep/SingleStepCheckpointCodec.java`，增加金额/零交付事实及认证V3证据，保留V1/V2读取；扩展现有Codec测试验证跨版本AAD和原冻结内容不变。Runner调用顺序固定为`lifecycle.observeSuccess(permit,outcome,traceId); lifecycle.apply(permit,outcome,traceId)`，前者独立提交、后者失败不能清除保护。
- [x] **B：资金收敛RED/GREEN**。扩展同一恢复集成文件的终态行为，核成功一次COMMIT1000、可信零交付失败一次RELEASE1000、实报缺失PENDING/884匹配/900及1100差异；未知与无零交付依据的失败不释放。`SupplierOrderLifecycle`新增MANDATORY单步终态入口并复用既有状态/资金职责，旧通用入口拒绝已启用单步的原单。`SingleStepFulfillmentLifecycle`核证据、原预留及原单资格后调用，终态与一条既有本地订单Outbox同事务；watchdog优先补交已保护成功，不再外呼。真实结果Outbox失败必须回滚订单及账本；独立成功证据不回滚。
- [x] **C：三次NOT_FOUND RED/GREEN**。在同一公开恢复入口覆盖前两次保持冻结、第3次平台失败且一次RELEASE、重复结果不多计、历史受理永久退出、网络异常清零、截止先到仍保留。第三次与成功并发，已持久成功阻断释放；平台失败提交后迟到成功只留证转Case。采用DDD§7.6的稳定关单原因，不伪造供应商失败或再次采购。
- [x] **D：组合门禁与实录**。执行供应链全部单测/架构以及`SingleStepRecoveryIntegrationTest,SingleStepFrozenConfigurationIntegrationTest,FulfillmentAcceptanceIntegrationTest,FrozenCredentialTicketIntegrationTest,RechargeFrequencyExecutionIntegrationTest,RechargeFrequencyTimerIntegrationTest,RechargeFrequencyLifecycleIntegrationTest,HuashengSettlementIntegrationTest,HuashengRestartIntegrationTest,SingleStepHttpCallFactoryContractTest,QnmSupplierAdapterContractTest,HuashengOperationExecutorContractTest,HuashengHttpContractTest`所选真实MySQL/契约。命令沿§12.5的verify，仅替换`-Dit.test`列表；核日志实际执行类而非历史XML总数。在`docs/集成测试.md`记录RED/GREEN、失败夹具、真实回滚及重开容器证据，保留未跑全量集成、到店两项延期、回调Inbox和真实协议未接线的界限。

**最终验证／14:28／PASS**：组合`verify`退出0、BUILD SUCCESS，耗时18分55秒；API125、server单测/架构2336，所选集成/契约211（13类，含恢复40、华昇结算26及既有频控/QNM/HTTP回归），失败/错误/跳过均0。日志`.amp/in/artifacts/t04-combined-regression.log`；逐一匹配日志实际执行类与本轮新XML后，保全于`t04-reports/unit/`249份/2461项、`integration-contract/`13份/211项及Failsafe汇总，派生计数为`summary.json`。恢复40已含原24项及本批新增16次执行，不能重复加总；其中外部单号保护反例在修复前1断言失败、修复后通过。

**收口结果**：成功保护独立于订单/资金回滚，结果Outbox与订单/账本同事务；已保护成功由持久watchdog或QUERY任务本地补交，不再外呼。限定第三次NOT_FOUND按平台原因失败并一次释放，历史存在/安全冲突/截止阻止错误释放；已释放后迟到成功只留证开Case。细节与RED/GREEN见`docs/集成测试.md`本批实录。

**当前边界**：本节覆盖§13中“完整终态证据及资金/三次释放未接线”的历史状态；T04公共核心完成，不代表回调HTTP/Inbox或T04整体完成。三家真实协议、商户总限额及逐家准入仍未接线，生产Metadata保持关闭，第24项PARTIAL，不发布供给。到店两项继续延期，未跑全量MySQL集成门禁；本批全部本地未提交，未推送、部署或操作共享数据库。

## 15. T04回调公共核心执行顺序（2026-10-18）

用户要求先中文提交固定§14基线，再开展回调改动。基线已本地提交，标题“完善单步直充成功保护与订单资金原子收敛”，提交前Codec/订单生命周期/架构63项复验通过；无推送或部署授权。以下按DDD§7.7在当前隔离工作树串行实施，不启用三家HTTP/安全白名单或原始协议解析。

- [x] **A：公开入口RED**。新增`T/border/fulfillment/SingleStepCallbackIntegrationTest.java`，复用原单冻结与执行夹具；新增可编译`SingleStepCallbackApplicationService.handle(fact,traceId)`桩。先验marker后创建回执前SUCCESS应COMMIT1000、一个已消费Inbox且不递增QUERY预算；桩应产生行为断言失败，不能以编译失败代替。
- [x] **B：身份与事务GREEN**。修改`SingleStepSupplierFact`分离来源与执行动作，构造器保留CREATE/QUERY调用兼容；在`application/singlestep/SingleStepCallbackInbox.java`封装事件、HMAC与MANDATORY认领/完成，首次插入唯一冲突单独分类。`SingleStepCallbackApplicationService`认证原冻结内容后两阶段调用Lifecycle；Lifecycle增加无permit的成功保护与Inbox结果事务，复用既有settle，CALLBACK不能进入认领/外呼。先验证A的公开结果。
- [x] **C：冲突/故障RED→GREEN**。同一集成文件覆盖回调重投时间变化、相同事件不同金额、已有成功保护但尚无Inbox、并发重复/QUERY竞争、MANUAL后的可信零交付失败、未知不改当前调度、未认证/错原单/无marker拒绝及已释放后迟到成功。MySQL CHECK分别拒绝COMMIT/结果Outbox/Inbox成功标记，核全部结果回滚但成功保护保留，重开容器后重投只处理一次。重复/冲突核实际Inbox、观察、Case、订单和账本，不只数mock调用。
- [x] **D：组合验证与实录**。供应链全部单测/架构，所选真实MySQL/契约增加`SingleStepCallbackIntegrationTest`并保留§14的恢复/资金及华昇回调回归；按实际收集类核新XML，不汇总旧报告。同步`docs/集成测试.md`，明确内部应用通过不等于HTTP认证/ACK、网关或三家端到端通过；C-04仍PARTIAL、商户限额与到店两项边界不变。本批回调变更不自动提交或推送。

**定向验证／16:46／PASS**：首例RED为1个断言失败/0错误，完整矩阵RED为16次执行全部断言失败/0错误，均是可编译空入口未消费/未拒绝事实；日志分别为`.amp/in/artifacts/t04-callback-a-red.log`与`t04-callback-matrix-red.log`。实现后`t04-callback-core-green.log`的Codec23、主架构31及回调集成16全部通过，失败/错误/跳过均0。随后补强重投传输摘要变化与冲突首摘要不变断言，并显式声明事务外编排；最终版本验证见下文，不用首轮通过替代。

**最终组合／17:10／PASS**：`verify`退出0、BUILD SUCCESS，耗时23分28秒；API125、server单测/架构2336，所选集成/契约250（16类），失败/错误/跳过均0。含最终回调16、恢复40、华昇回调集成14及REST契约9，其余保留§14全部13类。日志`.amp/in/artifacts/t04-callback-combined-regression.log`；逐一核日志实际执行类、新XML的数量/结果及时间后保全`t04-callback-reports/unit/`249份/2461项、`integration-contract/`16份/250项及Failsafe汇总，派生明细`summary.json`。不汇入历史报告，不把多轮定向测试重复加总。

**交付与剩余**：§14资金基线已中文提交；用户后续明确授权本批回调公共核心随本次中文提交固定，无推送或部署。Inbox与内部认证后事实应用已接线，成功独立保护，结果消费/资金/终态事件原子提交；三家HTTP协议认证、原格式ACK、网关安全及实机重投仍未接线/未验收，生产Metadata保持关闭。C-04第24项仍PARTIAL，商户总限额及逐家准入门禁不关闭；未跑全量MySQL集成，到店两项继续延期。不接通知渠道、不操作共享库或真实供应商。

**提交前复验／17:44／PASS**：Codec23、主架构31、回调真实MySQL集成16，合计70项，失败/错误/跳过均0，`verify`退出0。日志`.amp/in/artifacts/t04-callback-before-commit.log`；本次为提交前定向复验，不冒充重新执行2461/250组合门禁，也不重复加总测试项。

## 16. T05独立向量核验与密码基础（2026-10-18）

在同一`Dev-1031`工作树从已提交回调核心继续。先核原附件，再依DDD§5.1.1实现不依赖未决字段集的密码基础。已发现创建/查询回执示例复用旧请求密文、回调示例单号不匹配；不能以卡管自签自验填补C-03，HTTP/生产注册保持关闭。

- [x] **A：独立取证**。以Python cryptography读取现有附件，仅输出字段名/相等布尔值和源SHA-256，核请求固定签名、PHP回执正向对照及三种示例；不输出秘密、目标或完整明文。复现脚本与结果保存在`.amp/in/artifacts/renyue-vector-audit.{py,json}`。源附件不迁入Git，不调用真实供应商。
- [x] **B：密码入口RED**。新增`T/supplieradapter/renyue/RenyueProtocolTest.java`和纯合成资源`src/test/resources/supply-chain/renyue/synthetic-crypto-vector.json`，向量用独立Python按固定规范串生成，不调用生产实现。新增可编译`J/supplieradapter/renyue/RenyueProtocol.java`桩：`signRequest(Map<String,String>,ResolvedCredentialMaterial)`返回空签名、`decryptReceipt(String,ResolvedCredentialMaterial)`返回空数组。运行下述定向命令，确认固定签名/跨块明文断言失败，而非编译失败。
- [x] **C：最小实现GREEN**。按DDD§5.1.1实现SHA256withRSA请求签名与有界RSA分段解密。正例使用含中文/前后空格/`+&=`的原值和排序不对称字段；反例区分先URL编码、trim、大小写归一化、MIME忽略垃圾字符、固定错误块宽或只解第一块、错误密钥和后续块损坏。异常不含原输入，不创建已认证业务事实；凭据由现有可关闭材料持有，不注册Metadata或Adapter。
- [x] **D：验证与实录**。定向GREEN后执行§4命令A的供应链全部单测/架构，核新XML，不把历史2461/250计为本轮执行。不改HTTP/事务/安全/资金，故不以本切片重跑全部MySQL门禁；保持全量集成未复验、C-04第24项PARTIAL、两项到店延期及本批未提交、未推送、未部署的交付边界。将独立核验结果、RED/GREEN、实际命令及剩余C-03问题写入`docs/集成测试.md`，请求确认当前三种回执合同及供应商脱敏向量，不猜测接线。

定向命令（JDK21；各轮日志分别保存于`.amp/in/artifacts/renyue-crypto-{red,green}.log`）：

```bash
mvn -pl aifuli-module-supply-chain/aifuli-module-supply-chain-server -am \
  -Dtest=RenyueProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test
```

本节只推进T05密码基础，不标T04 HTTP或T05闭环完成；签名与解密是技术结果，不构成供应商事实认证或资金释放授权。

**验证结果／19:31／PASS**：可编译空实现RED为16次执行、16断言失败、0错误；实现后定向16项通过，随后补强同2048位错误私钥及1MiB上限等值正例。最终完整供应链单测/架构`test`退出0、BUILD SUCCESS，API125、server2352共2477项（含仁跃16），失败/错误/跳过均0，耗时59.704秒；250个实际运行类逐一核新XML并保全于`.amp/in/artifacts/renyue-crypto-reports/`及`summary.json`，完整日志`renyue-crypto-unit-regression.log`。编译仍有既有Druid废弃桩的6条警告，不改变该无关测试。全量MySQL与所选250项集成/契约本轮未重跑，不沿用旧结果宣称此次已执行。

**阻断与交付**：无争议密码基础完成，仅本地未提交。C-03仍缺当前CREATE/QUERY回执字段集及逐字节一致的供应商向量、比较规则和回调失败重投合同，C-02仍缺逐家可信零交付失败依据；不能将解密成功直接标VERIFIED或据状态3自动释放。HTTP/网关规则、仁跃Metadata/Adapter及T05端到端样板尚未接线，生产三家新路径继续关闭；需取得协议确认后继续，不修改共享库、不发真实请求、不接通知、不推送或部署。

## 17. 仁跃只接直充与请求装配（2026-10-18）

用户再次限定仁跃不接卡券、只对照cardshop直充；范围见SRS§4.2、实现合同见DDD§5.1.2。保留前批未提交密码基础，原工作树继续；不改cardshop、不注册生产适配器、不扩展QQ/OTHER。卡券材料不作为阻断，已确定的直充请求装配继续实施；直充回执矛盾和供应商重投保证不伪装成已解决。

- [x] **A：核真实直充调用链及历史**。只读核`RenyueConstants`、`RenyueSupplierTradeServiceImpl`、`RenyueCrypto`、`RenyueCallbackApi`和直充提交；请求取值确认后先更新SRS§4.2和DDD§5.1.2，再动实现。
- [x] **B：请求入口RED**。扩展`T/supplieradapter/renyue/RenyueProtocolTest.java`，经公开`buildRequest(OperationTicket,Instant)`断言创建九字段、查询四字段和固定原文签名、884分/100分/大整分精确格式、毫秒时刻转秒、不同外部号不能替代原billno、恶意extra不能覆盖，以及卡券/查资产/错步骤/非单份/缺价/非CNY拒绝。先加返回空Map的可编译桩，定向运行确认断言失败。
- [x] **C：最小GREEN**。只在已有`J/supplieradapter/renyue/RenyueProtocol.java`装配Map，封闭操作/步骤，投影冻结字段，以`BigDecimal.valueOf(price,2).toPlainString()`定价、Instant.getEpochSecond()定时、现有signRequest签原值并返回Map.copyOf；不合并extra、不关闭材料、不发HTTP、不输出认证事实。
- [x] **D：验证与实录**。沿用§16定向命令与完整供应链单测/架构命令，日志分别为`.amp/in/artifacts/renyue-direct-request-{red,green,regression}.log`；检查实际新XML与diff，将本轮结果、未执行HTTP/MySQL和未提交/未推送边界记入`docs/集成测试.md`。不沿用旧2477项充当本轮执行，不将T04/T05勾为整体完成。

**验证／20:34／PASS**：RED执行33项，新增17项断言失败、错误0，原密码16项仍通过；GREEN定向33项全通过，退出0。随后完整供应链单测/架构BUILD SUCCESS、退出0，API125+server2369=2494项，失败/错误/跳过均0，250类，耗时51.060秒；按日志实际运行类核新XML，保全于`.amp/in/artifacts/renyue-direct-request-reports/summary.json`及对应报告。无新HTTP Controller、Adapter/Metadata、网关/安全或资金改动，未跑HTTP/集成/契约/MySQL，不宣称闭环完成。所有变更仍仅本地未提交、未推送、未部署、未真实外呼；三家生产新路径关闭，C-04第24项PARTIAL不变。

**提交前复验／20:52／PASS**：按用户要求准备中文本地提交；原认证后回调核心已在父提交保存，本次增量是仁跃直充请求与密码基础，不能描述为HTTP回调闭环完成。完整供应链单测/架构重新执行2494项/250类，失败/错误/跳过均0，BUILD SUCCESS、退出0；日志`.amp/in/artifacts/renyue-direct-before-commit.log`与逐类XML核对汇总`renyue-direct-before-commit-summary.json`。未重跑集成/契约或MySQL，不新增真实调用、推送或部署授权。

## 18. T04 HTTP与T05仁跃临时本地闭环（2026-10-18）

用户批准按cardshop当前直充字段集与精确字节比较先做本地闭环，并明确处理失败必须HTTP 200+错误码/错误信息。先更新DDD§5.1.3，再按TDD实施；附件矛盾、供应商向量与重投实测仍未关闭，不把本地合同冒充供应商确认。不接卡券/卡密/取券/核销/余额，不接通知渠道，不改三家生产准入。

- [x] **A：协议HTTP RED/GREEN**。增加`RenyueSupplierAdapterContractTest`，经本机MockWebServer验证创建/查询不同回执字段、严格字节比较、原单查询、form编码、坏回执/非法JSON/1MiB及回调四字段认证。RED为24断言失败/0错误，原密码33通过；最小实现后24+33通过。Adapter带截止、一次物理HTTP、不重试不重定向；未认证扩展OrderNo/金额不首次绑定或动账，不输出NOT_FOUND或零交付证明。
- [x] **B：HTTP响应与精确授权**。增加仁跃单版本POST Controller与应用入口；原始form有界解析，版本+StoreNo有限定位，要求原CREATE marker，复用Ticket工厂/冻结材料认证后调用既有Inbox/资金核心。success只在提交后返回；失败固定200 JSON `{code,message}`、no-store、不包CommonResult。补充无上游Header与通配Content-Type反例，继承TraceFilter的MDC平台编号而非重新读Header。
- [x] **C：网关与共享保护**。网关Path+Method原样转发，只免登录POST单版本段；已登记仁跃路径沿用正文/查询串不读取、版本尾段隐藏与安全异常保护。父线程复核共享改动并独立复跑直接受影响67项（common17/web15/gateway35），全部通过，日志`renyue-shared-regression.log`；不宣称八进程/ELK实机验证。
- [x] **D：原单未知分类**。普通损坏响应仅允许UNVERIFIED/RESPONSE_UNKNOWN/RESULT_UNKNOWN且无金额/资产/零交付的窄组合沿180秒恢复，不产生可信存在或NOT_FOUND计数；认证/身份异常转Case。可信QUERY 04为人工未知，仅当前代停普通查询。隔离MySQL RED已证三处行为缺失（空回调未扣款、普通未知误转人工、人工未知误继续查）；之后实现对应分支，不改变旧协议入口。
- [x] **E：最终组合门禁与实录**。原Amp最终组合于22:01完成：2494项单测/架构及207项集成/契约通过；Thread结束时仍在等待，未回填结果。接续审查先补日志反例RED，再修复仁跃Controller业务异常级别和suppressed丢失，增加查询未知600秒与MANUAL迟到成功回归。最终以本轮22:41的2494项单测/架构、212项集成/契约及共享/根配置验证为准，详见下文；不累计历史轮次。保留重开Spring/连接池与持久数据的证据，不冒称OS强杀或供应商重投实测。

**接续审查及验证／22:43／本地范围PASS**：已读取Thread `T-01a14f15-c2f6-71b8-8a34-283e55bf874b`全部204条消息及工具记录，核HEAD `8b2f358c`与原16个已跟踪修改、9个未跟踪文件，沿用同一工作树，没有覆盖原实现。先修订DDD§5.1.3，新增日志回归得到21项中2断言失败/0错误：ServiceException误记ERROR、suppressed丢失；修复为固定安全响应、业务WARN无堆栈与有界防环安全异常图。补3次真实MySQL执行验证QUERY损坏/未知状态600秒及MANUAL后可信成功回调。最终`verify`退出0、BUILD SUCCESS，耗时18分27秒：API125+server2369共2494项单测/架构，10类集成/契约212项（仁跃14、REST21、回调16、恢复40、频控Timer8、QNM12、华昇HTTP48/REST9、仁跃协议24、单步HTTP20），失败/错误/跳过均0。共享common17/web15/gateway35共67项再次通过；TraceFilter2及真实根配置28组合共30项通过。日志`renyue-resume-{logging-red,combined,shared,root-logging}.log`，本轮逐类XML/汇总保全在`.amp/in/artifacts/renyue-resume-reports/`。根配置探针是7进程×4组合的本机自动化，不是实际启动7个服务或真实ELK验收。

**交付界限**：本批只本地未提交，不推送、不部署、不写共享库、不真实调用。生产Metadata/Schema均未注册新路径，商户总限额与独立直充向量/重投实测仍为发布门禁；C-04第24项PARTIAL、到店两项延期及全量MySQL门禁未重跑保持不变。本节仅覆盖此前HTTP尚未接线的历史进度，不能据此勾选T04/T05整体发布验收。前端实际仓库已只读核对，未改DTO或启用生产Metadata，本切片不需配套界面变更；其中已有未提交修改保持不动。

**提交前复验／23:17／PASS**：用户已授权中文本地提交本批全部相关改动。提交前定向`verify`退出0、BUILD SUCCESS：主架构31、仁跃密码/请求33、回调REST21、仁跃协议HTTP24，共109项，失败/错误/跳过均0；日志`.amp/in/artifacts/renyue-before-commit.log`。本次不冒充重跑2494/212组合或真实MySQL，上一轮结果保持上述范围。拟提交包含仁跃适配器/回调/共享日志与网关、测试及三份文档，不含已忽略取证材料、前端无关修改或生产配置放行；未获推送/部署/真实交易授权。
