# 华昇 type201 单实例生产履约与资金闭环详细设计

> 日期：2026-10-09。状态：用户已同意最小方案；Task1持久字段/checkpoint基础已实现并通过本地门禁（证据见`docs/集成测试.md`§7.90.4），Task2～8及生产闭环尚未完成／上线。
> 用户最新约束：**单实例优先，先不考虑 QNM**。不改造 QNM，不建设通用工作流、分布式 token 或调度平台。
> 2026-10-10 Task8修订：用户明确使用华晟通官方测试环境，不新建端到端挡板；真实验收范围仍为type201、FIXED、quantity=1、单实例。
> 基线：当前工作区 HEAD `0be390d`；通信 A1～A5 已存在。历史验收不是本轮重新运行结果。
> 2026-10-12 回调数量与映射管理修订：`2026-10-12-huasheng-fulfillment-hardening-design.md` §2～§3覆盖原回调无条件 num=1 及通用追加入口；恢复能力仍保持本设计范围，本轮不扩展补单与人工资金处置。
> 实施计划：`docs/superpowers/plans/2026-10-09-huasheng-type201-production-closure.md`。
> 2026-10-14 用户授权修订：§7.3将新单成功交付扩展为201／202，贯通主动取卡与成功回调；不恢复或修改历史原单。该节优先于本文历史“仅201”限制，不扩大数量、价格、退款或人工处置范围。

## 1. 范围与权威关系

目标：真实冻结配置 → 华昇 PLACE → 按事实 CONFIRM → TAKE／原单 QUERY 恢复 → type201 卡密加密存储与授权查询 → 资金恰一次收敛。

首期只接受 `HUA_SHENG_ORDER_V1`、FIXED、quantity=1、CARD_CODE_SECRET、协议 type201；其它六型即使通信层解析成功，也不能落成不完整资产或提交资金。通知／售后、取消／作废、自动切供应商、通用步骤引擎、多实例授权均不纳入。

本设计在生产接线、checkpoint、恢复、回调和 type201 资产方面替代 `2026-10-02-huasheng-fulfillment-adapter-design.md` 的旧候选方案。通信格式、strict parser、有界 61、受保护因果与 token 身份仍以 `2026-10-04-huasheng-http-token-parser-design.md` 及现有 A1～A5 为准；不重写它们。

人民币元／HALF_UP 转分、同 orderSn 重复 CONFIRM 只扣一次、重复 TAKE 返回同一整套卡密，来自 2026-10-04 用户补充。正式协议摘要见旧总设计 §12.6。挡板实现这些约定，不等于真实供应商已经验证。

QNM 不列接线、能力扩展、兼容设计或专项回归任务；已有代码不删除、不重构。供应链全量自动化门禁仍运行，不能因此批量排除共享状态机、账户或架构测试。

## 2. 现有缺口与最小接口

已核：TicketFactory 直传 checkpoint 密文；配置 resolver 未返回冻结凭据项快照；首 Operation 按履约类型生成 SEND_KM；ExecuteHandler 遇 EXECUTING／RESULT_UNKNOWN 跳过；ACCEPTED 不创建下一步；现有终结入口会在缺金额时用冻结成本兜底。资产事实 trim 卡号／卡密，持久层只写三个标准字段，前端只格式化 validFrom／validUntil。

新增华昇专用履约模块，Interface 仅为：

- `HuashengFulfillmentRunner.run(operationNo, trigger, expectedGeneration)`：执行／恢复入口；短事务资格 → 事务外外呼 → 短事务结果。trigger 封闭为 EXECUTE／RECOVER。
- `HuashengFulfillmentLifecycle.claim(String operationNo, HuashengFlowTrigger trigger, Long expectedGeneration)`：返回`Optional<HuashengExecutionPermit>`，空值表示当前资格不可认领；不在事务内等待进程互斥锁或做 HTTP。EXECUTE无需expectedGeneration，RECOVER必须携带来源代次并与当前generation一致。
- `HuashengFulfillmentLifecycle.apply(permit, fact)`：验证代次、身份、金额、完整性，持久下一步或终结。事实不含资金指令。
- `HuashengCallbackApplicationService.receive(connectorVersionId, headers, ciphertext)`：冻结版本认证 → 同一事实入口；不拥有独立扣款逻辑。

`HuashengOperationExecutor`实现OperationExecutorPort，返回OperationOutcome的新增可选`protocolFact`；其类型为领域纯标记Interface `FulfillmentProtocolFact`，华昇实现`HuashengStepFact`只带纯值（端点、双号、数量、金额、状态／退款／提卡、封闭编码资产载荷和安全观察）。不带Mapper、A5对象、Throwable或资金动作。通用outcomeClass中间为ACCEPTED／未知为RESULT_UNKNOWN；只有lifecycle验证后产生最终SUCCEEDED／REJECTED，不让executor独自认定资金结果。Ticket增加华昇permit所需generation及已解密checkpoint／冻结凭据身份，不携带生命周期实现。

生产 executor 位于 `border.fulfillment.application.huashengflow`，不是 supplieradapter 下反向依赖 A5 应用门面。现有 CompositeOperationExecutor 以 OperationExecutorPort 注册它。A1～A5 的纯对象继续零 Spring 注解；独立 `infrastructure.configuration.HuashengFulfillmentConfiguration` 显式建 Bean，不放宽 A5 两包的架构规则。

华昇业务 lifecycle/runner 位于新 `huashengflow` 包，可访问履约 DAL；只经 FundReservationService 访问资金。原通信 `application.huasheng` 和 `supplieradapter.huasheng` 不引入 Mapper／Timer／事务。共享 ExecuteHandler 在普通 claim 和旧提前跳过前按冻结 adapter 类型委派华昇 runner；其它路径不变。

## 3. 单实例授权与生产配置

### 3.1 执行边界

同一华昇经销商只允许一个执行／授权进程；外部旧系统、脚本不能同时为该账号刷新 token。升级先停旧实例并确认退出，再启动新实例，不使用滚动重叠。此为部署前提，不宣称进程内锁提供跨机器保证。

首期使用**一个进程级华昇执行互斥锁**，串行包住认领、token 获取、有界 61、一次业务 facade 调用与短结果事务；不同冻结版本也不并发刷新同账号 token。复用 A5 复合身份缓存和 generation 失效，不把不同版本 token 合并成一个缓存项。顺序切换版本若使旧 token 失效，由已有一次 61 刷新处理；不保证所有旧缓存 token 始终有效。

Runner 先尝试获取互斥锁，再进入 claim；等待中不拿数据库锁、不占已开始的 Operation 资格。锁忙返回可重试的本地结果，仍有持久触发；不创建第二条下单。回调认证后、Inbox／结果事务开始前也获取该锁，最多等3秒；未获得则HTTP503让供应商重投。这样回调不能在已认领CONFIRM尚未发送的窗口先释放资金，也不能抢先覆盖已经取得但尚未落库的TAKE事实。进程锁→Inbox（仅回调）→聚合→资金是固定方向；不得在持有数据库锁时等待进程锁。异常和中断 finally 释放锁；锁不是跨实例保证，数据库围栏仍保护旧Timer／重启资格。

### 3.2 Metadata 与发布约束

`HuashengOrderV1Metadata`：CREDENTIAL_SET，required keys 恰为 appSecret／privateKey／callbackPublicKey；capabilities 为 ISSUE_ASSET／QUERY_ORDER／RECEIVE_CALLBACK。默认画像 QUERY_OR_CALLBACK、DEDUP_ONLY、QUERY_ORDER、FORBIDDEN、SINGLE；不能因 CONFIRM 幂等就声明整个创建结果可回放。

封闭 Schema V1：connector／offer／fulfillment config 均为空对象；extraConfig 仅可选整数 expireTime、字符串 outMobile。ClosedJsonSchemaValidator 仅支持有限类型／enum，范围 60～1800、默认300与长度校验在 metadata／绑定校验中显式做，不假设支持 minimum／maximum。未知字段、第二 appId、token、密钥均拒绝。Offer 发布进一步验证 FIXED／CARD_CODE_SECRET／数量策略1；couponId来自冻结 externalSkuCode 的严格正整数解析，不由最新商品编码替代。

状态模板把 PLACE／CONFIRM／QUERY 的 WAIT、SUCCESS 记作非终态 ACCEPTED，FAILED／CANCEL 保守记作 RESULT_UNKNOWN；只有应用事实校验决定释放。CALLBACK SUCCESS 也先按非终态模板记录，由完整资产及金额判定决定本地成功。映射管理不得把订单端点 SUCCESS 改成直接终态；发布与运行时验证华昇封闭模板的安全语义。错误模板不提供通用 RETRY_SAFE，7014／7101 等只能引导原单恢复。

模板业务阶段沿既有CREATE／QUERY／CALLBACK，不在数据库新增PLACE／CONFIRM阶段：CREATE覆盖PLACE/CONFIRM，具体端点写纯事实；外部状态键固定`status=WAIT/SUCCESS/FAILED/CANCEL`，externalSubStatus为空。CREATE与QUERY各四行，CALLBACK仅SUCCESS/FAILED；全部terminal=false，SUCCESS/WAIT的factType=ACCEPTED，FAILED/CANCEL的factType=RESULT_UNKNOWN。错误模板首期为空，仍拒绝华昇RETRY_SAFE或REJECT直终结配置。

### 3.3 冻结投影与首单 checkpoint

Task3 接口落点：`AdapterMetadata` 增加默认空实现的商户身份／extraConfig 校验钩子，旧适配器行为不变；华昇实现严格16字节 appId、expireTime 60～1800、outMobile 非空白且UTF-8最多64字节／无控制字符。华昇封闭映射校验在 validate、publish 与 published resolver 三处调用，必须恰为§3.2十行模板且错误模板为空，避免验证后修改映射绕过发布守卫；发布供给同时校验 Offer 身份价模式FIXED、SKU CARD_CODE_SECRET、数量三值均1及默认执行画像不变。Task3类本体无自动注册注解，Task5才统一条件装配。

`OperationTicket` 新增 credentialSetId、不可变 frozenCredentialItems、executionGeneration，保留24/27字段构造入口及builder；checkpointPayload仍为已解密的封闭JSON字节（由Task4 TicketFactory提供），executor以封闭codec解码入口读取并逐项核Ticket已有身份／billno／externalSkuCode，不能将密文当JSON或从最新配置补参数。恢复Ticket有效step可为QUERY，持久checkpoint绑定仍为原step。Ticket、材料、builder、protocolFact及Outcome拒绝意外JSON序列化并固定脱敏诊断；新增领域自有拒绝器，不依赖A内部failure对象。

`HuashengStepFact` 用本地封闭枚举表达endpoint/result/status与字段三态，订单原金额及分值可缺省，TAKE不补金额；每次物理attempt完整投影安全字段（含TOKEN与61第二次），不携带A5结果、Throwable或原始消息。TAKE受保护资产字节由Task2 codec生成，其它卡型／不完整资产转RESULT_UNKNOWN并保留attempts，终结仍由Task4判定。FrozenCredentialSetResolver短独立事务用REQUIRES_NEW，先集再按项主键升序当前读，核全部ACTIVE身份且无重复后仅解析requestedKeys；解析失败擦除包括空材料在内的已取得数组，调用者用毕finally擦除返回数组。并发测试以真实MySQL证明解析期间版本更新阻塞，提交后漂移失败关闭，不宣称历史材料可恢复。

PublishedConnectorConfiguration 增加不可变凭据项身份列表，由**该版本 credential_item_snapshot**解析 itemId／itemKey／providerType／activeSecretVersion；先按现有绑定守卫核对，不能拿当前项版本组装代替。Ticket 承载通用冻结身份值，应用 context factory 仅选 appSecret／privateKey 两项构造 A5 HuashengCredentialIdentity；回调公钥不进出站材料／缓存。

现有`CredentialSetResolver.resolveSet(setId)`只读取当前项，不能独自证明材料属于冻结身份。新增窄`FrozenCredentialSetResolver.resolve(setId, expectedItems, requestedKeys)`，内部短独立事务先锁凭据集、按主键升序当前读项，核全部冻结身份与ACTIVE，再经已有CredentialItemResolver解析所需项；解析失败擦除已取材料，返回后事务已结束。出站请求两项，回调请求appSecret／callbackPublicKey，不改变旧resolveSet行为。TicketFactory和回调均使用此入口，不先verify再另一次无守卫resolve。此能力不保存历史secretRef：轮换新建凭据集与连接器版本，旧集与ENV引用的实际材料保留到在途结束；原地改版本／删除／停用导致旧单失败关闭并保留，不声称仍可自动解出旧密钥。ENV变量不在同版本下热替换；配置投影不是历史秘密仓库。

出站凭据准备在持有进程锁但尚未进入claim聚合事务时完成；回调准备在认证阶段完成。不得持有Request／Order／资金锁后再进入凭据集锁事务。claim锁后再核冻结版本与预读身份一致，准备失败不设发送marker。TicketFactory提供华昇专用装配入口接受已核材料和permit，普通create不自行把当前集解析结果当冻结材料；所有材料仍经上述resolver获得，不接受HTTP传入材料。

merchantAccount 是 appId 唯一来源，UTF-8严格16字节。baseUrl、超时、凭据集、extraParameters 均取 BOrder 的 connectorVersionId；运行时出站仍经过 SupplierEndpointPolicy，不扩大允许主机，不取消 HTTPS。

受理创建首 Operation 时，华昇显式选 PLACE_ORDER，先冻结下列非认证业务参数并加密 checkpoint：billno→outOrderSn、couponId、num=1、expireTime、可选 outMobile、最终 notifyUrl、连接器／Offer版本、配置摘要及原始来源 traceId（有则保存）。手机号仍属于受保护业务数据，禁止日志输出；“非认证”不等于可公开。冻结失败则整笔受理／预占回滚；不能先提交资金预占后再拼一个可变化的通知地址。

受理同事务另创建5分钟后到期的首步准备watchdog（不同taskType，payload仅operationNo）；尚无发送marker且仍PENDING时只转本地准备人工case，保留资金，不自行PLACE或宣称供应商FAILED。这样首执行Timer在claim前耗尽／死信也有持久诊断收尾；已认领则该watchdog跳过，由认领代次watchdog接管。

notifyUrl 使用 publicCallbackBaseUrl + 固定 callbackPath `/callbacks/suppliers/huasheng` + `/{connectorVersionId}`。版本 ID 已存在后才拼最终地址；由该版本回调路径和受理时部署公网 base 冻结完整地址。以后部署参数变化不改变同单 PLACE 业务参数。回调位置是非秘密身份，不作认证凭证。

## 4. 持久模型与 checkpoint

### 4.1 追加迁移

当前 YAML 到061。实施前重新核实际末尾；本基线追加 `062-huasheng-operation-recovery.sql`，不修改历史 changeSet。Operation 新字段：

```sql
ALTER TABLE sc_fulfillment_operation
  ADD COLUMN checkpoint_key_version VARCHAR(64) NULL COMMENT '恢复检查点信封加密密钥版本',
  ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0 COMMENT '履约操作认领与恢复围栏代次',
  ADD COLUMN execution_lease_until DATETIME(3) NULL COMMENT '当前履约操作认领到期时间，Asia/Shanghai',
  ADD COLUMN recovery_count INT NOT NULL DEFAULT 0 COMMENT '本操作已认领的原单查单恢复次数',
  ADD COLUMN recovery_deadline_at DATETIME(3) NULL COMMENT '自动恢复截止时间，Asia/Shanghai',
  ADD COLUMN business_send_possible_at DATETIME(3) NULL COMMENT '本操作首次可能发送业务请求的时间，Asia/Shanghai',
  ADD CONSTRAINT ck_sc_operation_huasheng_recovery
    CHECK (execution_generation >= 0 AND recovery_count >= 0);
```

为支持多阶段相同外部状态键映射（如华昇 CREATE/QUERY/CALLBACK 共用 `status=WAIT/SUCCESS` 等），062 同时调整状态映射唯一键，纳入 `business_phase`：
```sql
ALTER TABLE sc_status_mapping_definition
  DROP INDEX uk_sc_status_mapping,
  ADD UNIQUE KEY uk_sc_status_mapping (connector_version_id, mapping_version, business_phase, external_status, external_sub_status);
```

这些列是技术事实，不改变 operation_kind CHECK。新增 PLACE_ORDER／CONFIRM_ORDER／TAKE_CARD／QUERY_ORDER step；FULFILL 用于前三者，QUERY_ORDER kind用于恢复外呼 Ticket。恢复消费同一原 Operation，不创建新的 BOrder、不重新路由／预占。

复用已有`idx_sc_fulfillment_operation_recover(status,next_recover_at,id)`，不重复建同列索引。`checkpoint_key_version`保持EnvelopeCryptoPort字符串契约，与现有资产密钥版本列一致。恢复时Ticket的有效step可为QUERY_ORDER，但checkpoint AAD仍用原Operation持久step；不能因为切换外呼端点就用QUERY_ORDER去解原PLACE／CONFIRM密文。

generation／lease／business_send_possible_at以当前Operation列为权威；recovery_count记录本Operation已认领QUERY数，checkpoint记录整笔累计QUERY／CONFIRM／TAKE数，claim在同事务一起更新，两者不是同一含义。recovery_deadline_at为整笔首发送时固定的截止镜像，后续步骤复制同值，锁后与checkpoint交叉校验；不得从新Operation.createdAt重新计算。当前PENDING下一步marker为空不表示原订单尚未创建，回调资格依据首PLACE的持久marker。所有业务期限／回调窗口用SupplyChainDatabaseClock，转换Timer的Instant沿既有toInstant；外呼超时用A5单调时钟预算，不混用JVM本地时间作为数据库资格时钟。

### 4.2 检查点 V1

内容分为不可变绑定、可信累计事实与有界计数：

- 绑定：schemaVersion=1、bOrderId／bOrderNo、operationNo／sequenceNo／step、outOrderSn、可选已验证 orderSn、connectorVersionId／offerVersionId／configDigest、规范化 PLACE 参数和其确定性 SHA-256。
- 金额：已验证端点／观察身份、totalPrice 原元字符串和分值；未取得事实时缺省，不填冻结成本。后续 totalPrice BigDecimal数值必须与此前事实相同（9.99与9.9900等价），再比较换算分值与 expectedSupplierSettlementAmount。
- 流程：已获供应商状态、退款／提卡事实（缺省不补值）、累计 QUERY 次数、CONFIRM／TAKE业务认领次数、首次发送及恢复截止、可选 originTraceId。
- 禁止 token、密钥、签名、原始密文响应与无关字段进入 checkpoint。敏感材料不作 JSON 通用序列化。

AAD 为固定 UTF-8 `HUA_SHENG_CHECKPOINT|1|bOrderId|operationNo|sequenceNo|step|connectorVersionId|offerVersionId`，数字按十进制、step按枚举；所有组成项来自锁后实体，不信任密文内自报身份。EnvelopeCryptoPort 返回密文和 keyVersion一起保存；schema／key／密文恰全有或全无（华昇执行必须全有）。解密后再校验内部绑定与 configDigest。

跨步骤不是复制密文：解密／校验旧 checkpoint → 更新事实 → 以新 Operation AAD重加密。TicketFactory只给华昇传解密后的防御复制明文；解密临时数组 finally 清零，Ticket／checkpoint类禁止明文 toString。损坏、未知 schema、缺key、身份搬移皆转人工保留，绝不当首次下单。

#### 4.2.1 Task 1 封闭编码与基础接口

V1 使用固定字段顺序的 UTF-8 JSON，不接受任意 Map 或调用方 ObjectMapper 配置。根键固定为 `schemaVersion/binding/placeParameters/placeParametersDigest/orderSn/observation/progress/pendingAssetPayload`；无可选值时省略键，不写推测值。binding 包含 §4.2 的全部身份（configDigest 使用64位小写十六进制）；placeParameters 固定 `outOrderSn/couponId/num/expireTime/outMobile/notifyUrl` 顺序，缺省 outMobile 省略，digest 为该封闭对象字节的 SHA-256。num 只接受1，couponId 为正整数，expireTime 为60～1800秒。outOrderSn 来自冻结 billno，**不等同于 bOrderNo**（现有受理分别生成两者）；codec只核 observation.outOrderSn 与 PLACE 参数相同，后续 lifecycle 必须锁后核冻结 billno，不由密文自报单号定位原单。原单 orderSn 建立后与 observation 保持一致。

`HuashengCheckpoint` 是不可变纯值，分为 Binding、PlaceParameters、Observation、Progress 四组；orderSn、观察与待核资产可缺省。Observation 包含已验证端点、双号、num、totalPriceRaw/totalPriceMinorUnits、status、refundStatus/isTake 三态；仅 PLACE/CONFIRM/QUERY 可建立金额来源，TAKE/TOKEN 不得伪造。通信事实仍由 A 的 HuashengMoneyParser 校验产生；checkpoint仅校验持久值不变量（最多32字符、ASCII非负定点且最多4位小数、BigDecimal×100/HALF_UP的long精确结果等于所存分值），原元字符串逐字保留，以测试对照 A 的接受与换算结果；**不得引用 A 的 HuashengInternalAttempt/ProtectedFailure**，不放宽原架构门禁或改写 A parser。Progress 保存整笔 queryCount/confirmCount/takeCount、firstSendAt/recoveryDeadlineAt、originTraceId；计数非负，首次发送与截止恰全有或全无，截止严格晚于首次发送。时间使用严格 ISO_LOCAL_DATE_TIME、毫秒精度，语义仍为 Asia/Shanghai 数据库时间，不在codec内读取时钟。

`HuashengCheckpointCodec.encrypt(checkpoint)` 返回 schemaVersion/keyVersion/ciphertext 成组的 Stored 值；`decrypt(stored, expectedBinding)` 要求三项齐全、版本=1，expectedBinding 由事务锁后实体提供，先用其构造AAD再逐项核明文绑定（包括 bOrderNo/configDigest）。未知/额外字段、重复键、尾随值、浮点或超范围整数、非法枚举、摘要漂移均固定安全拒绝；不附带可能含明文的 Jackson 异常。codec 不负责将失败转为 MANUAL 或更新资金，后续 lifecycle 承担此边界。

编码/解码正文最多1MiB，JSON深度32、普通字符串16384字符；`pendingAssetPayload` 使用 Base64 保存 §6.3 的受保护证据，整体仍受1MiB上限，Task1只提供不可变存储槽，不解释资产schema。数组在构造和返回时均复制；加解密临时明文finally清零。checkpoint及嵌套值禁止通用JSON序列化，toString固定脱敏；codec不记录日志、不含token/认证材料、不访问DAL或外呼。首期不实现通用序列化框架、跨版本升级或历史密钥仓库。

## 5. 资格、唯一下一步与宕机恢复

### 5.1 锁序与 permit

复用现有 `RechargeFrequencyLifecycle.lockForResult` 的聚合锁能力：Request → Order → 同 Order 的 BOrder主键升序 → Operation主键升序；华昇没有手机号队列项，不新增频控资格。可提取窄聚合锁模块，但不改锁顺序／其它供应商行为。资金服务锁最后获取。

permit 包含 operationNo／bOrderId／executionGeneration／有效step／trigger／leaseUntil；不传 DO可变对象。锁后重新核全部关联、activeBOrderId、活动标志及华昇配置身份。

EXECUTE 仅认领尚未可能发送的 PENDING。claim原子增加generation，设置EXECUTING、业务可能发送marker与lease，创建到期watchdog Timer，随后提交才允许外呼。marker在网络之前，宁可保守进入QUERY，不因没有实际 socket就自动重发PLACE。

RECOVER 仅针对EXECUTING租约过期或RESULT_UNKNOWN到nextRecoverAt；增加generation使旧permit失格，有效step为QUERY_ORDER。认领QUERY也同事务创建租约watchdog，QUERY外呼亦在事务外。手工态不自动认领。

apply在同样锁序下检查generation、当前活动尝试、step和lease未到期；失格结果只记安全观察，不更新checkpoint／资产／资金或下一步。迟到结果即使是成功也不绕围栏；新QUERY或可信回调可独立再次取得权威事实。

### 5.2 唯一下一步

前一协议操作完成不等于BOrder交付成功。中间步骤使用Operation status=SUCCEEDED、resultClass=ACCEPTED、completedAt，BOrder／Request仍处理中，资金保留。

同事务：保存观察和checkpoint → CAS结束前Operation → 创建sequence+1唯一Operation／新AAD checkpoint → PersistentTimer.scheduleOnce → 更新活动事实。利用已有 `(b_order_id,sequence_no)`／idempotency_key唯一键，重复先锁后核已有行内容；冲突不能仅吞DuplicateKey。

下一步唯一键继续用 `FulfillmentIdempotencyKeys.operation(bOrderNo, sequence)`。Timer键执行 `HS:EXEC:operationNo`；恢复 `HS:REC:operationNo:generation`；payload仅operationNo／expectedGeneration／trigger／可选originTraceId，不包含checkpoint、卡密或token。新增专用execute Handler注册华昇EXEC taskType，不能调度未注册的taskType；首Operation既有Outbox→通用execute Handler只负责分派，后续华昇步骤走专用Handler。每个下一步同事务注册其5分钟准备watchdog，防止新步骤claim前死信无人收尾。scheduleOnce复用已实现身份建槽／绑定，参与同一供应链事务；失败全部回滚。不要把整段含HTTP的Handler纳入纯本地事务重跑。

首步 Outbox 转 Timer 时使用事件中冻结的 traceId 作为 originTraceId，不用调度线程 MDC／taskId。没有来源的历史事件仍省略该字段。来源字段加入后的部署需确认旧任务身份槽兼容；不得在本次修复中修改已持久任务载荷或绕过 scheduleOnce 内容冲突检查。

### 5.3 Watchdog与计数

本地策略固定默认：每次facade调用总预算120秒，lease150秒；QUERY间隔30秒；从首业务可能发送起自动恢复30分钟，整笔最多20次QUERY、3次CONFIRM、5次TAKE认领。61的第二次物理请求只由A5预算控制，不生成第二Operation；上述次数是业务认领数，不冒称供应商实际扣款次数。计数和截止写checkpoint并传后续Operation，不能每次新步骤／重启归零。

2026-10-10 P2 修复约束：次数在 claim 内而非 apply 内消耗，首发送／截止同事务建立并镜像至 Operation，后续步骤复制截止。claim 前检查自动资格，最后一次合法认领的结果仍允许应用；到限只拒绝下一次自动外呼，不拦已有可信终态或 CALLBACK。可信回调恢复仅针对 HUASHENG_RECOVERY_LIMIT case；金额／资产／准备异常 case 保留。已认领但未实际发送仍消耗保守业务配额，不把业务认领数宣称为物理 HTTP 次数。

可信回调收敛后，在到限 case 的安全说明中注明原单已核实，保留活动标记与审计记录，待人工按既有处置契约结案。DDL 的结案要求操作人和处置动作，自动回调不得伪造操作人或绕过该约束。

每次claim先落watchdog。即时UNKNOWN则同事务把nextRecoverAt提前到30秒并创建当前代次恢复Timer；watchdog迟后到达先核generation／终态，不能重复推进。若只登记同一个恢复键，必须保持其payload恒定，不能用修改payload偷偷改变身份；即时恢复和租约watchdog使用不同taskType常量而共享generation校验。

锁忙属于未执行的可重试本地失败，execute/recovery/watchdog Handler不能返回“已完成”把唯一触发吞掉。持锁执行尚未退出而租约已到期时，watchdog保持可重试；原apply因租约到期只观察，退出锁后watchdog再认领QUERY。准备watchdog的人工收尾也先获取同一锁再进入事务，不能在业务claim进行中自行改状态。异步超时不强制丢弃线程后释放互斥锁，必须等待实际调用退出或停止旧进程后由新进程恢复。

RESULT_UNKNOWN或QUERY未知安排新代次恢复。QUERY进程也宕机，由它认领时已创建的watchdog接续。PENDING步骤已有execute Timer；初始化／本地准备异常由Timer最多3次重试，次数耗尽写人工case并保留资金，不将死信当作供应商拒绝。所有重试入口确认持久触发已提交，不能依赖内存afterCommit回调必达。

启动只复用持久Timer恢复，不新建全库修复／迁移工程；必须以集成测试证明业务watchdog在Timer返回成功或进程重启后仍可运行。首次claim事务回滚则marker/watchdog都不存在，仍可认领未发送步骤。

达到次数／期限：MANUAL、稳定原因码、唯一异常case；保留活动原BOrder关系、可信checkpoint和资金预占，不设业务完成时间伪装终结。人工权限入口和人工强制释放不在本期；只保留既有case查询和原单核实证据。

准备 watchdog 也复用同一人工保留事务：原因 HUASHENG_PREPARATION_FAILED，写唯一 case 并保持 completedAt 缺省，不能只改 Operation 丢失聚合处置入口。

## 6. 协议事实推进规则

### 6.1 PLACE／CONFIRM

解析后的双号、num、金额先校验。WAIT且未到CONFIRM上限 → 创建CONFIRM_ORDER；SUCCESS → 创建TAKE_CARD。PLACE成功直接跳过CONFIRM。只有同一orderSn，不能覆盖已经建立的双号。

CONFIRM超时／7101／7015／非法响应／其他无法证明终态的业务错误 → UNKNOWN +原单QUERY。QUERY返回WAIT且退款NONE／isTake=N才可沿同orderSn新建受控CONFIRM操作，仍服从累计上限；不能再建PLACE。

FAILED的正式语义为未发券且已退款：合法PLACE／CONFIRM可接受其省略refundStatus，但出现NONE／WAIT或isTake=Y则不可信；QUERY还须显式refundStatus=SUCCESS／isTake=N。CANCEL仅在显式退款SUCCESS／未提卡且没有已知资产时可释放，NONE／WAIT／缺省保留。任何已有交付／金额矛盾先转人工。

业务错误code不是退款证据，首期不维护“任意错误码直接释放”列表；包括本地准备失败也不冒充远端FAILED。

### 6.2 QUERY

有可信orderSn优先按它查，否则按冻结outOrderSn；严格二选一。返回必须匹配原双号/数量：

- WAIT／NONE／N：受控CONFIRM；不能先重复PLACE。
- SUCCESS／NONE／N或Y：TAKE，Y仍可重取同套卡密。
- FAILED／SUCCESS／N：可信失败候选，经无资产／无冲突检查后释放。
- CANCEL／SUCCESS／N：同上；其它退款组合保留。
- 7014、未知/矛盾字段、传输或解析失败：保持UNKNOWN，下一次QUERY或到限人工；查无单不证明PLACE没执行。

恢复结束原操作并创建下一协议操作，原步骤编码保留供审计；QUERY不以SUCCEEDED直接终结订单。不是让通用Timer把原下单任务重新执行。

### 6.3 TAKE与最终资金

TAKE没有总价，必须与checkpoint同orderSn对应的可信PLACE／CONFIRM／QUERY金额合并；若没有金额来源，先查单，不用配置成本补实报。

2026-10-10 评审修复（先 P1，后 P2）：executor 保持 ACCEPTED／金额缺省；lifecycle 在聚合锁后通过决策才复制 outcome，将终结分类改为 SUCCEEDED，并仅从已核 checkpoint.observation 合并 TAKE 的实报分值，保留原资产、调用摘要和协议事实。缺来源先 QUERY，已经取得的资产载荷随加密 checkpoint 保留，不能丢卡或从冻结成本兜底。

最终成功须同时满足：订单身份一致、num=1、list恰一完整type201、资产无冲突、累计金额事实一致、实报分值等于冻结供应商成本。一个事务保存加密资产、结束当前Operation／活动BOrder、更新Request／Order、审计、调用commitByReference一次。预占／买家冻结售价不被供应商价格覆盖。

可信失败须无已交付资产、未成功终结、同原单退款规则成立及无冲突，才通过releaseByReference一次。成功／失败相反的迟到事实开冲突case，已终结资金不反向再释放／再扣。

无资产检查同时覆盖 checkpoint.pendingAssetPayload：已提取而待核的卡密不是“未发券”。即使后续 FAILED／CANCEL 回调自身没有 list，也只能留证转人工，不释放预占。

资产已收到但金额不匹配或重复TAKE不同卡：MANUAL保留，必要原始资产以加密checkpoint隔离证据或独立非可交付资产生命周期保存；不得授权查询为成功交付，不覆盖既有卡密。首期选择加密checkpoint保存待核实完整载荷，受1MiB与原协议结构界限约束，case只存安全摘要；成功后清掉待核实分支。

人工保留与唯一活动 case 在同一聚合事务提交，reasonCode 使用稳定常量，不设置 completedAt 伪装业务完成。case 引用当前 operation 的加密 checkpoint，并对事实证据生成 HMAC 摘要。已有待核载荷不得被不同载荷覆盖：首期只有一个证据槽，后续不同证据返回冲突非200并回滚 Inbox，要求人工核实；相同证据可确认而不重复开 case。已存在金额／资产冲突 case 时，新回调不得自动终结。

现有SupplierOrderLifecycle只复用**终结事务能力**：新增受控华昇permit入口，在同一事务先由华昇guard完成完整资产／金额／代次检查，再调用现有状态汇总与资金服务；不得走无permit的通用SUCCEEDED入口、SEND_KM空资产提交分支或缺金额兜底。不复制第二份账户／账本算法。入口不回调HuashengFulfillmentLifecycle（避免Bean循环）；guard为纯规则模块，验证材料由同一事务锁后读取。无permit的旧transition／confirmTrustedOutcome检测华昇step／protocolFact后拒绝推进，只允许专用路径。

## 7. type201无损资产与查询

华昇payload V1采用固定白名单编码，不直接Jackson序列化A的禁序列化对象：

```json
{
  "payloadSchemaVersion": 1,
  "assetType": "CARD_CODE_SECRET",
  "cardCode": " TEST_CODE ",
  "cardSecret": " TEST_PASS ",
  "supplierPayload": {
    "adapterType": "HUA_SHENG_ORDER_V1",
    "type": 201,
    "id": null,
    "code": " TEST_CODE ",
    "pass": " TEST_PASS ",
    "validTime": "长期有效"
  }
}
```

supplierPayload完整保留协议id／code／pass／crc／url／urlPass／type／validTime：ABSENT不写键，NULL写JSON null，VALUE原样。type201不接受其它型所需非空载荷，沿A严格parser；未出现、null与空串仍分别保真。标准cardCode/cardSecret必须与协议code/pass逐字一致，不trim；全空白/缺值由协议规则拒绝，不经trim“修复”。validFrom／validUntil保持NULL，不猜日期截止／时区。

OperationAssetFact增加可选不可变`protectedPayload`字节值，用于这一封闭schema；新增华昇工厂只接受已验证type201，保留原字符串。已有构造行为不在本期统一改造。持久层对protectedPayload验证schema与标准字段一致后整体信封加密，仍用已有资产AAD `bOrderId|sequenceNo|assetType`。所有数组防御复制／擦除，AssetFact字符串表示不含秘密。

华昇卡片id没有供应商内全局唯一保证，`external_asset_id=NULL`，原id只在加密supplierPayload中保留。HMAC指纹输入使用固定UTF-8 canonical JSON的supplierId／type=201／code／pass，字段固定顺序，不能冒号拼接发生歧义；账户内跨单重复卡密触发现有唯一键冲突并人工保留，不借BOrder盐绕开重复发卡检测。原始validTime等非凭证元数据不进入凭证指纹。

同BOrder／sequence重复事实：完整payload的三态/原值相同是幂等；卡密或其它已保存字段不同开冲突case，不能覆盖。跨单唯一键冲突须整个资产/资金事务回滚，再以独立受控事务开case；不能catch异常继续提交一半资产。

### 7.1 Task2桥接与持久基础边界

`HuashengType201AssetCodec.toFact(card)`仅接受A parser产生的T201卡，生成sequence=1的CARD_CODE_SECRET事实；crc/url/urlPass如为VALUE只能空白，不接受其它型非空载荷，仍完整保留ABSENT/NULL/空串/空白。id遵循A实际接受范围（非负Long），仅在加密payload保存。codec使用固定白名单/顺序写根schema/标准字段/supplierPayload，不序列化协议对象；不调用A内部结果载体。`validate(fact)`对受保护payload做1MiB/深度32/字符串16384限制、重复键/尾随/未知字段/严格整数校验，核标准code/pass与协议字段逐字相等，并核sequence=1、externalAssetId与日期均NULL；返回固定顺序规范化字节，字段三态不变。`fingerprintInput(supplierId,fact)`只输出固定顺序UTF-8 JSON `supplierId/type/code/pass`，不含id/有效期/订单盐，交由现有HmacFingerprintPort的FULFILLMENT_ASSET purpose生成指纹。

回调应用层保留 `toFact(card)` 返回的完整 OperationAssetFact，与步骤事实一起传给 lifecycle；不丢弃为仅 payload 后再手工 builder，也不新增不必要的 payload 重解接口。终结前核完整事实与步骤 payload 同源，持久层继续严格校验。

OperationAssetFact保留现有7参数构造和builder默认行为；仅携带非空protectedPayload的专用工厂路径保留code/pass原值，其数组双向复制，并固定安全toString/禁止意外通用序列化。通用builder即便传入protectedPayload也不能绕过持久入口的封闭schema核验；这不是生产履约授权，Task4另以permit封闭终结入口。

事实本体及Lombok builder的toString均固定脱敏，禁止builder默认诊断输出卡号/卡密/数组；序列化拒绝器由OperationAssetFact自有纯序列化类型提供，不让通用领域事实依赖华昇协议failure包。华昇资产maskedValue固定为`****`，不复用QNM展示工具，不泄漏短卡号，也不更改旧展示分支。

SupplierOrderLifecycle仅在persistAssets对protectedPayload分支执行以上校验/指纹/整体加密；旧无payload分支不更改指纹或trim规则。华昇分支先查同BOrder/sequence现有资产（调用者已持有聚合锁），核原归属/type/外部id/日期和解密完整规范化payload：相同则跳过insert，不同固定安全抛出冲突；不得按同卡号忽略有效期或NULL/ABSENT差异。跨单重复由既有supplierId/fingerprint唯一键拒绝，异常不在资产方法中吞掉，整笔外层事务回滚。Task2只验证持久基础与原事务回滚；人工case/未知资产隔离/资金收敛仍由Task4受控入口实现，不增加无permit公共存储或终结接口。成功资产日志仅在本事务afterCommit写安全身份；加密/指纹与比较临时明文finally擦除。

已有密文比较使用`validateStoredPayload(expectedFact,decryptedPayload)`校验并规范化；入参不修改，方法内部临时拷贝finally擦除，外层持久方法擦除原解密数组。不能为一次比较构造另一个长期持有payload副本的临时OperationAssetFact；JSON树及String由JVM回收，不承诺不可变字符串原地擦除。

Task2真实MySQL测试在独占空库全量Liquibase下，通过测试反射调用真实private persistAssets，使用真实Mapper/TransactionTemplate/AES-GCM/HMAC/业务编号；不经旧通用SUCCEEDED入口、不伪造生产资金成功。隔离测试只提供资产所需关联身份（无物理外键），不构建可售配置或订单闭环，资金表保持无流水；此证据不等同Task8生产链联调。

ProjectStandardAssetPayloadDecoder识别新增schema，验证supplierPayload与标准字段；旧标准载荷读取不在本期重构。项目ProjectStandardOrderAssetRespDTO和管理CardInfo增加可选`validTimeRaw`及`validTimeState`（ABSENT／NULL／VALUE），原始含义为“供应商批次有效期原文，不保证日期格式”；字段来自加密payload，无新增日期DDL语义。内部BuyerFulfillmentQueryService原解密payload接口保持完整返回。

真实前端 `src/api/order/types.ts`／`index.ts`／`src/views/supplier/order/components/ExclusiveOrderInfo.vue`展示规则：VALUE且原文非空优先原样显示，空串或ABSENT／NULL显示“供应商未提供”；没有新增state的既有响应仍用原validFrom／validUntil格式化。不可把中文/非ISO原文丢给Date解析，也不trim或改变卡号／密码。继续原归属校验、权限与敏感访问审计；不在列表／日志新增卡密。

### 7.2 Task7授权查询与有效期展示契约

本片范围为已保存的华昇type201资产查询，不扩展履约与资金流程。两查询出口复用 `HuashengType201AssetCodec` 的封闭V1校验：严格schema、固定白名单、重复键/尾随拒绝、标准cardCode/cardSecret与supplierPayload.code/pass逐字一致；供应商adapter/type必须匹配。出现schema或supplierPayload任一标记就进入新schema分支，缺失另一标记不得降级为旧资产。非文本validTime拒绝，只有JSON null允许作为NULL。

新增API枚举 `SupplierValidTimeState`：ABSENT表示supplierPayload不含validTime，NULL表示JSON null，VALUE表示字符串（包括空串和全空白）。`validTimeRaw`语义统一为“供应商批次有效期原文，不保证日期格式”；VALUE原样返回，ABSENT/NULL为null，state负责区分。旧资产两个新字段均不提供。此为加密payload投影，无对应新DDL字段，不改变现有validFrom/validUntil COMMENT。华昇查询还核资产类型、sequence=1、externalAssetId/日期为null，不推算日期。

管理端在原解密边界内完成严格读取和投影，载荷失败写FAILED/PAYLOAD_CORRUPTED审计，成功后才写SUCCESS；明文byte[]在finally擦除。错误使用固定安全摘要，不保留可能携带卡密的JSON解析cause；审计落库失败继续安全拒绝，不在中间层重复打印堆栈。项目端继续通过可信项目上下文与快照归属验证，审计独立提交；新增字段不绕过权限或审计入口。CardInfo诊断字符串固定脱敏。

前端使用 `OrderCardAssetView` 判别联合及纯映射 `mapCardAsset`；VALUE必须有字符串raw，NULL/ABSENT不得携带非null原文，未知state拒绝，避免坏响应被日期兜底掩盖。VALUE非空原样展示（包括首尾空白、“长期有效”、非ISO文本）；空串/NULL/ABSENT为“供应商未提供”。没有state的旧资产使用已有日期格式化函数。页面用文本插值并保持空白，不解析原文为Date、不trim、卡号卡密不重写。

验证涵盖两出口真实AES-GCM密文解密的一致性、四态与序列化、坏载荷拒绝/缓冲区清理、跨项目拒绝/无解密、审计失败拒绝及真实Spring方法权限；真实MySQL/Liquibase复用项目查询独占库夹具，契约类放application包以复用其包级支持。前端测试执行实际OrderApi映射与SFC渲染，另执行定向lint、全局类型检查与本地构建。自动化结果与未执行实机范围登记 `docs/集成测试.md` §7.90.11，Task8真实配置到资金闭环另行验收。

### 7.3 201／202卡号卡密兼容与新单成功闭环

用户于2026-10-14明确要求修复新单，并最终澄清两个独立条件：华昇返回“履约成功且已取卡”即供应商订单成功；在此基础上本地成功获取并保存卡密，平台订单才成功。不以资产或金额对账作为供应商成功门槛，不以金额核对作为平台履约成功门槛；禁止恢复历史原单。既有原单授权诊断确认TAKE返回202、code非空、pass空；用户提供卡管同测试商品22成功订单的Excel，经核查两列同值，与参考源码202投影一致。采用扩展华昇专用桥接及分离供应商履约事实与平台交付状态的方案；不改变真实卡型或SKU资产合同，不仅根据SUCCESS/isTake=Y强制平台成功。

**资产合同。** 201继续要求非空白code及pass，标准cardCode/cardSecret各自逐字对应原字段。202至少一个字段非空白，优先选非空白code，否则选非空白pass；选中的完整值同时作为标准cardCode和cardSecret，不trim、不拆分、不生成第二个独立密码。两字段均有效时仍以code优先，另一原值保存在受保护supplierPayload，不丢弃证据。原type=202及id、code、pass、crc、url、urlPass、validTime三态和原值保真；非空crc/url/urlPass与其它卡型仍拒绝。202字段缺省／null／空白不会被投影反写成供应商已返回的另一个值。

**唯一校验与兼容。** 扩展现有HuashengType201AssetCodec，保留历史类名与调用入口以减少无关重命名，201／202使用同一个封闭V1 schema和严格持久／查询校验。标准字段与原字段的一致性按上述卡型投影验证，builder不能伪造另一密码或绕过类型白名单。旧201有效载荷、AAD及HMAC输入逐字兼容，不迁移历史资产；202指纹使用supplierId、真实type=202及选中值的标准code/pass投影，code-only与pass-only的同一凭证不能靠字段位置变化绕过跨单重复保护。原始字段三态差异仍参与同顺序号完整payload比较，不添加独立202资产／资金分支。

**供应商成功独立留存。** 已认证且绑定原单／数量／有效代次的QUERY等事实为SUCCESS/isTake=Y，或合法TAKE／含完整卡片的SUCCESS回调证实已取卡时，直接保存BOrder=SUCCEEDED及供应商履约数量／时间，不依赖本地资产或金额。尚待本地交付时保留activeFlag及Request.activeBOrderId作为原单恢复占位，允许原orderSn受控TAKE／QUERY；这不是创建新采购资格。缺卡或预算耗尽时Operation、Request及平台可转MANUAL，但不得将已经证实的BOrder成功改成人工或失败。终态回调短路必须同时检查平台交付是否完成，不能只看供应商成功就阻止补充资产。

首次TAKE或成功回调与本地资产写入同事务发生时，资产写入失败不能抹掉已核实的供应商成功。通过事务回滚完成回调，在原事务释放数据库锁后，以供应链REQUIRES_NEW短事务重取聚合锁、复核原Operation代次／状态及检查点绑定，仅保存供应商成功；不得提交平台交付、资产、资金、Inbox成功或复位操作。原事务已成功则不额外写库，旧代次失格事实不注册此处理；后续同nonce回调仍可重投完成本地交付。独立留存事务自身数据库故障时由事务终结边界报错，不能宣称已持久成功，也不在回调内无限重试。

**平台成功路径。** TAKE与已认证SUCCESS回调接受恰一合法201／202；回调复用现有通信卡片parser，不放宽数量、字段类型、验签、双单号、版本、nonce、时间或代次。仅成功事实不再被价格差异、金额缺失或历史金额变化否决，TAKE不得自生金额；非成功事实的既有购买前价格保护与失败释放规则不变。平台成功仍在同一事务内保存加密资产、完成Operation/Request/总单、清除BOrder活动占位、确认原预留资金并审计。实报金额只按存在的事实留存，匹配则MATCHED，差异则MISMATCH，缺失维持原结算状态，不伪造已确认金额；金额差异不修改冻结报价或买方预留。项目查单与两授权资产出口沿原权威状态读取；没有成功保存卡密就不能显示平台成功。身份／资产冲突与旧代次仍受安全围栏约束。

**测试与交付边界。** 扩展既有测试先复现202在执行／回调、落库／成功资格、两资产出口的断链，再验证code-only、pass-only、code优先的双值、三态及原字符串、201回归、非法投影与未知类型拒绝。真实MySQL门禁覆盖“供应商成功但未保存卡密时平台不成功”、后续原单提卡成功、缺卡进入人工仍保留供应商成功，以及金额不同／缺失不阻断平台成功；已有资金恰一次测试保持有效，不在实机进行额外资产／金额对账。供应链单元、契约、架构门禁不得跳过。本次无DDL、无HTTP/DTO变更，前端继续显示同值卡号／卡密，不需要配套修改。用户要求完成后告知修复结果，重新部署pro143后仅测试一笔新单；部署前检查无未授权迁移、冻结配置和单实例外呼条件，部署仅替换供应链制品，不改其它进程或凭据。历史原单的订单／资产／资金／Case不手动变更、无复位／重取，记录其部署前后不变性；不能用新单验收代替旧单恢复授权。

## 8. 必需华昇回调

### 8.1 冻结身份认证

POST `/callbacks/suppliers/huasheng/{connectorVersionId}`，text/plain；只此精确根/子路径免登录token，仍由华昇认证失败关闭。网关必须配置实际可达路由，不能把permitAll当成路由已发布。Controller只依赖应用服务，不能直接Mapper／HttpTransport。

URL版本只定位冻结配置：验证HUA_SHENG类型、已发布版本、凭据绑定、Header Appid逐字等于merchantAccount；解析该版本callbackPublicKey与appSecret，先对**原密文文本字节**用X-Sign验签，再AES解密。不扫描所有供应商密钥、不用最新版本尝试解密。公钥不进入出站Ticket。

Parser复用A金额／卡片验证规则，不复用普通明文envelope作为公开入口。原hex密文正文在验签／AES前按2MiB上限有界读取，不先无限制绑定String；header亦限制Appid为16字节、X-Sign为4096字节。专用严格reader：1MiB解码上限／深度32／字符串16384／重复键和尾随拒绝；timestamp整数秒、nonce非空最多64字符、双号、totalPrice、SUCCESS／FAILED。回调 num 为交付数量：SUCCESS 必须 num=1 且 list 恰一type201；FAILED 接受 num=0 或省略，保留字段存在性，list 允许缺省／空数组，不接受任何非空卡片。不能将原单订购数量 1 用作 FAILED 的交付数量；检查点零交付与历史 V1 读取兼容见 `2026-10-12-huasheng-fulfillment-hardening-design.md` §2。金额精度／换算沿A，阈值写常量且受测，不借此建设通用WAF。

### 8.2 持久防重与收敛

不使用分布式回调锁。复用sc_inbox_event，增加华昇专用封装而不改变通用InboxService行为。consumer固定华昇回调常量；eventId为固定字段顺序UTF-8 JSON的supplierId／Appid／nonce之SHA-256，不用连接器版本／密钥轮换让同账号同nonce成为新身份；payloadDigest以HmacFingerprintPort固定purpose对规范化完整解密事实及connectorVersionId计算（不存明文）。同nonce不同事实或改投其它版本拒绝；不同nonce同资产走聚合幂等，不重复资金。三态字段和金额原字符串都进入事实摘要，重投要求事实相同，不只比较双单号。HMAC密钥在本期去重存续期间保持不变，不在本片增加摘要密钥轮换工程。

为使DDL与字段语义一致，062追加`ALTER TABLE sc_inbox_event MODIFY COLUMN event_id VARCHAR(64) NOT NULL COMMENT '被消费的Outbox事件或回调稳定业务编号，华昇使用SHA-256十六进制', MODIFY COLUMN payload_digest BINARY(32) NOT NULL COMMENT '收到的事件载荷SHA-256族摘要，华昇使用HMAC-SHA256';`，只扩容与扩注释，不改其它consumer算法。InboxEventDO对应字段注释同义对齐。

首次回调必须在±300秒内；先认证后查询nonce。已经成功持久处理的同内容重投可超出该窗口仍HTTP200（为已处理消息确认，不重新执行），否则旧timestamp拒绝。首次成功标记、观察、checkpoint／资产／资金／下一步在同一供应链事务；失败回滚Inbox状态，供应商可重投。锁序：Inbox身份 → Request／Order／BOrder／Operation → 资金；其他事实入口不反过来获取Inbox。

回调双号只能定位同版本、同supplier且首PLACE业务marker已存在的原BOrder；当前CONFIRM/TAKE仍PENDING不影响该资格。无本地原单／首marker不存在不创建订单、不200伪确认。若PLACE同步响应未落库，按冻结outOrderSn找到原单，orderSn尚未知可在经过身份核实后建立关系；已有远端号不能覆盖。

可信回调是独立事实源，不用伪造某个同步permit。同一apply规则在锁后验证当前原单并增加当前generation使在途结果失格：SUCCESS完整资产／金额核对后终结；FAILED满足退款语义且无资产后释放。因自动恢复到限进入MANUAL的原单仍可被可信回调核实终结，次数上限只停止自动外呼，不删除事实入口；金额/资产冲突人工单不能被新回调静默覆盖旧冲突。冲突仍开case、保留资金／原终态，完成冲突登记后可200确认；无条件将回调交给旧SUCCEEDED入口禁止。

回调预读仅定位锁；取得 Request→Order→BOrder→Operation 聚合锁后，从已锁行重新选原 BOrder 的最新步骤并核首 PLACE marker。判定订单终态以 BOrder/Request 业务终态为准，不把中间 Operation 的 SUCCEEDED/ACCEPTED 当订单交付成功。同向成功须双号、数量、实报金额与已存完整规范化 payload（三态、有效期等全部字段）相同才幂等；失败须同原单且无资产。同向异卡或相反终态开唯一冲突 case、保存加密待核证据，绝不反向扣款／释放或覆盖订单终态。

HTTP200固定正文`success`表示处理事务已提交或已处理同内容，不代表订单必成功；认证／格式／时间非法400，未找到可处理原单404，nonce异参409，数据库／内部失败500，进程执行锁忙503。这些是本地响应选择；正式文档只保证HTTP200确认、非200/超时重投，不臆造重投次数或供应商解析正文规则。

事实摘要使用固定顺序 JSON encoder，timestamp、双号、nonce、金额原文/分值、状态以及 card 的全部三态字段均参与 HMAC；eventId 使用 JSON encoder 转义 Appid/nonce，不用无转义拼接或 `&` 分隔。解析拒绝及 HTTP 拒绝只输出固定安全信息，禁止解密原值穿透异常 message；受保护原始原因不能作为普通 WARN/ERROR 堆栈。回调/资金“完成”日志在事务提交确认后记录；后续 checkpoint/Timer 沿用初始 originTraceId，不以 taskId 或本次后台 traceId 替换。

### 8.3 安全与日志影响

复用ApiAccessLogPathUtils的精确根／context-path匹配，把回调根加入正文／参数／响应／敏感异常保护；不要把callback路径塞入“凭据写入”命名列表。真实Servlet Filter／Interceptor／异常处理器及Gateway验证不泄露密文／签名／卡密。共享规则只新增该路径，不扩其它进程业务行为，影响构建为common／web／gateway／供应链；其它独立进程不新增Bean或Feign。

遵守全系统日志规范：每次后台执行新traceId／executionId，originTraceId有来源才携带；保存并finally恢复完整MDC。复用A4／A5实际外呼日志，不另打wire正文；新编排日志只写安全身份、步骤、代次、结果、elapsedMs，业务成功在提交后记录。稳定event／reason用常量。没有结构化encoder真实顶层证据不宣称新字段已进入ES。实机日志验收同步 `docs/集成测试.md` §8.12。

## 9. 验收、启用与停止条件

### 9.1 官方测试环境与证据边界

Task8端到端验收直接连接华晟通官方测试环境，不新建HTTPS挡板或本地供应商去重账本。2026-10-10重新读取[统一下单详情](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/486554275e0)，“测试环境”显示 `https://coupon-api.yunhuishou.vip`，路径为 `/api/order/placeOrder`。[概述](https://s.apifox.cn/57114177-5188-44e4-99f8-2a8581e4be12/9165626m0)表格中的HTTP地址与其HTTPS要求不一致；本期使用详情列出的HTTPS origin，并从实际部署出口核验DNS、证书及访问权限。继续执行SupplierEndpointPolicy的HTTPS和允许主机校验，不使用trust-all或绕过生产parser；分享访问凭据不写入仓库。

平台侧复用pro143已有测试环境（`docs/集成测试.md` §8.13.5），只运行一个供应链后台实例；不新建MySQL/Redis或启动第二个共库worker。新业务配置经实际管理页面/Controller及真实配置服务createDraft→validate→publish创建，真实目录准入→项目订单→生产Bean/Timer→官方供应商→资产/资金→项目接口/管理端页面走完整链；不以fake metadata、mock executor、直补已发布行或QNM替代。

既有通信、生命周期、并发与真实MySQL/Liquibase自动化继续作为回归门禁，不增加Task8专用供应商挡板。若需编写环境执行/核验脚本，必须显式启用并指定测试环境、订单标识及次数上限，不让默认CI调用外部供应商。环境脚本复用生产HTTP/管理接口，不另写可绕过正式协议和资金收敛的下单实现。

### 9.2 实机步骤与完成标准

1. 发布前核对后端/前端制品版本、正式Liquibase待执行增量、已有订单/Timer/Outbox和在途任务；停旧实例后启动新实例，确认无并行授权进程。不得清库、clearCheckSums、覆盖历史业务数据或为本片批量开启历史Outbox消费。
2. 安全配置测试appId、appSecret、请求私钥、callbackPublicKey；经凭据集绑定连接器冻结版本，baseUrl使用上述测试origin，允许主机限定 `coupon-api.yunhuishou.vip`。检查公网回调、时钟、`huasheng-fulfillment.enabled`、项目OpenAPI及Timer实际生效值；仓库示例默认关闭不代表远端已经启用。按既有授权执行测试交易，具体SKU、价格和账户资料以测试环境事实核准。
3. 用官方测试商品查询核实couponId、type201、库存及实际价格；通过真实管理入口创建可识别的新SKU、FIXED Offer、连接器/履约配置、供给、目录、项目路由及账户条件。成本和销售价分别配置，不以面值或未经确认单位的余额接口数值替代结算价。
4. 从项目接口提交数量1订单，观察生产执行的PLACE→按事实CONFIRM→TAKE/原单QUERY及真实回调；正常链发生哪种分支就记录哪种。不能因完成一笔WAIT分支就宣称已验证PLACE直接SUCCESS。
5. 核对同一订单的冻结配置、双单号、Operation/checkpoint、Reservation、资产和账户账本：成功资产恰一、本地资金commit恰一；失败仅在明确退款事实后release恰一；未知/人工保持预占且不换单。项目接口与实际管理端页面比对同张卡号/卡密/有效期原文，核跨项目拒绝、管理权限及敏感访问审计。原文仅在授权窗口比对，证据只记录一致性结果及脱敏标识。
6. 核对官方查单/交付及可取得的供应商扣款或订单记录，结合本地账本证明业务幂等；不能以本地计数单独宣称供应商只扣一次。保存制品/配置版本、时间、traceId/供应商traceID、脱敏订单标识、实际分支、资金前后差额、资产计数和审计结果。

### 9.3 异常覆盖与门禁

必须覆盖：WAIT→CONFIRM→SUCCESS→TAKE；PLACE直接SUCCESS；FAILED／已退款CANCEL释放；退款未完成保留；各外呼响应前／成功后本地事务前宕机；下一步／Timer事务注入失败；QUERY7014／未知；QUERY恢复WAIT／SUCCESS（含Y）；重复／迟到／callback竞态／进程重启；冻结配置轮换；金额与资产冲突；完整三态有效期／首尾空白保真；其它六型拒绝；认领／恢复／取券到限人工。

上述覆盖分别登记既有自动化与官方测试环境结果。先核实供应商测试环境可提供的场景/SKU/回调控制；不为凑分支伪造官方响应、改业务库状态或新增挡板。无法在官方环境触发的分支保留自动化证据并标注实机未验证、原因及后续条件；未验证项不能打勾或据此声明Task8全部完成。宕机、事务故障、凭据轮换和审计故障先在既有隔离自动化中验证；在共享pro143环境演练前核对历史任务影响，不通过破坏共享数据制造故障。

断言不是只看HTTP：一笔Reservation、资产恰一、本地commit或release恰一、未知/人工不终结资金；PLACE业务身份不变且UNKNOWN不重发；CONFIRM／TAKE按原orderSn幂等；无第二BOrder/路由；跨项目资产不可读取。真实多线程MySQL验证围栏与唯一下一步，不能只用Mockito宣称锁正确。

门禁：供应链全量*Test／*ArchitectureTest，适用*ContractTest／*IntegrationTest，Liquibase重放及受影响shared web／gateway保护回归；前端定向测试、修改文件lint和构建。没有QNM专项接线/回归，既有全量测试如实际失败按因果诊断，不借机扩工程。

生产metadata／executor显式启用属性默认false，用同一装配条件避免仅metadata可售而executor缺失；完成全部本地门禁后才允许部署方启用。停止新单使用供给暂停／路由移除；**不要关闭处理在途的Bean／凭据**，回调与恢复仍须运行。部署和供应商调用沿用会话已有授权，单实例／公网回调／密钥／allowlist／账本核对分开登记；本次验收方案修订本身不表示已经部署或交易成功。

若发现必须更改A1～A5通信语义、需要多实例／账号同时授权、需要扩卡型／改金额容差／强制释放人工单，先停止并更新设计取得确认。本设计不把目录自动发布或旧B0专项重新列为前置；首单真实配置链尚缺其它业务片时如实记录，不SQL补业务状态冒称从零贯通。
