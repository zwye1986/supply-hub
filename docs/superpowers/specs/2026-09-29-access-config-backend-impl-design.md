# 对外接入配置后端实现设计（伪代码级）（2026-09-29）

## 1. 目标与约束

本文细化《2026-09-29-project-app-issuance-platform-signing-callback-design.md》与《2026-09-29-supplier-inbound-verify-inventory-design.md》。两份概要设计是业务契约来源；本文件给出代码落点、事务边界和可验证伪代码。管理端前端的页面范围在两份概要设计中列明，本文件仅细化后端。

供应链使用独立 supplyChainDataSource 和 supplyChainTransactionManager，只访问 sc_* 表。管理接口 @PreAuthorize；外部公开接口在服务内精确放行登录态，随后独立验签。MyBatis Plus 优先 LambdaQueryWrapper/LambdaUpdateWrapper；只有带锁批量认领等 Lambda 无法表达的 SQL 使用 XML。重要入口记业务标识和脱敏摘要，第三方请求/响应记摘要、错误码与耗时，高频路径按错误码限频。枚举和常量承载业务状态。注释链接到本设计对应章节。

## 2. 代码与迁移落点

| 块 | 主要代码落点 | 现有代码改造 |
| --- | --- | --- |
| A 项目发码 | tradecore.buyer.application.ProjectAppCredentialAdminService；infrastructure.auth.AppKeyIssuer | 项目应用 Admin Controller；凭证 DO/Mapper；认证能力校验 |
| B 平台密钥 | tradecore.platform 的密钥服务与 Mapper；infrastructure.security.PlatformSigningPrivateKeyResolver、PlatformEventSigner | 管理/公开公钥 Controller；服务内安全放行 |
| C 项目通知 | border.fulfillment 的 BuyerEventProducer、ProjectCallbackDispatchService；integration.project 的 HTTP 客户端 | 订单受理和 SupplierOrderLifecycle 事件发布；OutboxEventRepository；BuyerEventRpcDispatchHandler |
| D 供应商回调 | interfaceapi.callback.SupplierCallbackController；supplieradapter 的协议验签器；border.fulfillment.application.SupplierCallbackConvergenceService | 版本解析、QnmCallbackParser、状态映射、SupplierOrderLifecycle |
| E 库存查询 | tradecore.supply 的来源管理/快照 Mapper；infrastructure.job 的刷新扫描与认领；supplieradapter 的查询端口 | SupplierConfigurationAdminController、ConnectorPublicationService、SupplierOfferPublicationService |
| F 路由快照 | routing.application 的来源快照读取器 | SupplierRoutingApplicationService、路由规则发布校验 |

Liquibase 新增项目凭证操作审计、平台密钥、控制行、回调配置版本、连接器回调保留字段和库存来源列；准确 DDL 见两份概要设计。**不新增 sc_project_event_delivery**，Outbox 是项目通知唯一的重试和状态事实。所有新 DATETIME(3) 注释与持久化值使用 Asia/Shanghai；对外事件时间为带时区 ISO 8601。

## 3. A 项目应用发码

### 3.1 发码材料与安全生命周期

~~~text
AppKeyIssuer.issue():
    bytes = new byte[32]
    STATIC_SECURE_RANDOM.nextBytes(bytes)
    appKey = Base64URL_without_padding(bytes)
    maskedHint = first4(appKey) + "****" + last4(appKey)
    wipe(bytes)
    return IssueMaterial(appKey, maskedHint)

seal(appId, keyVersion, appKey):
    masterKeyBytes = ProjectAppAuthProperties 解码并校验的 32 字节主密钥
    return LocalEnvelopeAppKeyCodec.encrypt(masterKeyBytes, appId, keyVersion, appKey)
~~~

发码响应对象禁止 Lombok @Data 自动生成含密钥的 toString；响应头 Cache-Control: no-store。日志只记 projectCode、appId、keyVersion、操作者和审计号。若部署使用自定义 AppKeySecretResolver/KMS，发码侧必须配套可写入同一系统的 Issuer SPI，不能把本地信封密钥写入一个无法被认证侧解析的环境。

### 3.2 管理服务

服务放在 tradecore.buyer.application，使用 BuyerProjectMapper 而非认证只读 AuthBuyerProjectMapper。所有写入在 supplyChainTransactionManager 短事务中：

~~~text
enable(projectCode, operatorId):
    project = buyerProjectMapper.selectByProjectCodeForUpdate(projectCode)
    require project.status == ACTIVE
    require applicationMapper.selectByProjectId(project.id) == null
    appId = "AP_" + ULID
    material = issuer.issue()
    insert ProjectApplication(projectId, appId, ENABLED)
    insert CredentialVersion(applicationId, 1, seal(...), maskedHint,
                             CURRENT, activeSlot=CURRENT, validFrom=databaseNow)
    append CredentialAudit(ENABLE, applicationId, keyVersion=1, operatorId, now)
    return oneTimeCredential(appId, 1, material.appKey)

rotate(projectCode, operatorId):
    app = applicationMapper.selectByProjectIdForUpdate(project.id)
    require app ENABLED
    revoke old GRACE if any, clear its activeSlot
    update old CURRENT to GRACE, activeSlot=GRACE, validTo=now+graceWindow
    newVersion = max(keyVersion)+1
    insert new CURRENT with activeSlot=CURRENT
    append CredentialAudit(ROTATE, applicationId, keyVersion=newVersion, operatorId, now)
    return new oneTimeCredential

revoke(projectCode, confirmed, reason, operatorId):
    require confirmed && nonblank(reason)
    project = buyerProjectMapper.selectByProjectCodeForUpdate(projectCode)
    require project exists; do not require ACTIVE (SUSPENDED/CLOSED still permit cleanup)
    lock app; set CURRENT/GRACE to REVOKED and activeSlot=null
    append CredentialAudit(REVOKE, applicationId, reason, operatorId, now)
    publish disabled callback config version for future events
    leave historical appId and credential audit intact
~~~

凭证表已有 masked_hint 和 active_slot；补全 ProjectAppCredentialVersionDO 字段及 Mapper 方法，不加重复 DDL。唯一键保护并发，锁顺序固定为 project → application → credential。现有 `ProjectAuthOperations` 只登记 INSTORE 操作；为每个登记操作显式声明所需项目能力，先保持既有 10 个操作的 INSTORE 授权行为。认证服务只负责应用/项目/凭证及解密，过滤器在签名通过后按注册操作的能力检查，不再在 `authenticate` 内无条件要求 INSTORE。新增标准商品项目 HMAC 操作须先明确其 HTTP 契约、登记路径及能力映射；现有 `BuyerFulfillmentCommandApi` RPC 不会仅因发码而自动受此过滤器保护。

开通和轮换要求项目 ACTIVE；吊销是清理性操作，只要求项目存在并保持 project → application → credential 锁顺序。项目停用期间吊销后，即使重新启用，已吊销版本也不得恢复认证。

## 4. B 平台签名密钥

### 4.1 控制行、轮换与材料校验

~~~text
prePublish(publicKeyPem, operatorId):
    parse X.509 SubjectPublicKeyInfo PEM; require RSA modulus == 2048
    generate globally unique keyVersion
    insert SigningKey(status=NEXT, publicKeyPem, algorithm=SHA256withRSA)

activate(keyVersion, operatorId):
    @Transactional(supplyChainTransactionManager)
    control = controlMapper.selectByIdForUpdate(1)       // 固定行，空密钥表也可锁
    next = require NEXT
    privateKey = resolver.resolvePrivateKey(keyVersion)
    challenge = SecureRandom(32 bytes)
    require verify(next.publicKey, sign(privateKey, challenge))
    old = control.currentKeyId ? selectById(control.currentKeyId) : null
    if old:
        update old status=PREVIOUS, activeSlot=null,
                   retainUntil=max(existing, now+retentionWindow)
    update next status=CURRENT, activeSlot=CURRENT, activatedAt=now
    update control.currentKeyId=next.id, version=version+1
    // 其他未到保留期 PREVIOUS 不动

retire(keyVersion):
    lock control; require status=PREVIOUS && now >= retainUntil
    update status=RETIRED, retiredAt=now
~~~

控制行固定由 Liquibase 初始化。publicKey 接口按状态读取，允许多个 PREVIOUS；NEXT 仅单版本预缓存接口返回。私钥 Resolver 是 SPI：生产从部署侧 keyVersion → secret_ref 解析，测试注入内存密钥；不可直接把现有 AesGcmEnvelopeCrypto 的 ciphertext 格式当作 LocalEnvelopeAppKeyCodec 的 v1 secret_ref。签名用真实私钥，解析失败或不匹配时失败关闭，不会自动换另一版本。

启动校验控制行/current 状态/active_slot 三者一致，且 CURRENT 的部署私钥与公钥匹配。retentionWindow 用 Outbox maxAttempts、每轮退避上限、HTTP 调用超时及 2 × 时间戳容差做安全上界计算；算术溢出或部署配置比该下界短则启动失败关闭。

### 4.2 严格规范串

~~~text
sign(envelope, bodyBytes, selectedCurrentKey):
    timestamp = decimal Unix epoch milliseconds from trusted clock
    fields = [
        "SC-PROJECT-CALLBACK-V1", keyVersion, timestamp,
        envelope.eventId, envelope.eventType,
        textual occurredAt extracted from serialized bodyBytes,
        envelope.projectCode, envelope.appId,
        lowercaseHex(SHA-256(bodyBytes))
    ]
    canonicalBytes = UTF8(join(fields, LF))
    signature = Base64(RSA-SHA256(privateKey, canonicalBytes))
    return keyVersion, timestamp, eventId, signature
~~~

先完成一次固定 JSON 序列化，再从同一 bodyBytes 获取参与签名的文本字段，避免 Java OffsetDateTime.toString 与 JSON 字符串格式不同。时间戳和 keyVersion 均在签名范围内。重试正文不变，时间戳和签名重算。公钥/私钥错配、时间戳被篡改、body 任一字节被篡改均应验签失败。

## 5. C 项目事件生产与投递

### 5.1 从订单事务生产信封

新增 OutboxEventRepository.appendBuyerEvent：先生成 eventId，组装 BuyerEventEnvelope，再将同一 eventId 与版本化 payload 原子写入 Outbox。新信封 payloadVersion=2，Outbox eventType 与信封 eventType 均为 ORDER_SUMMARY_CHANGED 或 FULFILLMENT_RESULT，targetType=LOCAL，targetName=buyer-event-rpc。订单受理后与 SupplierOrderLifecycle 每次项目可见的汇总变化写 ORDER_SUMMARY_CHANGED；履约终态另写 FULFILLMENT_RESULT。新旧采购商事件沿用 `aggregateType=BUYER_ORDER`、`aggregateId=orderNo`，聚合版本取订单 detailVersion，不能固定写 1；现有唯一键 `(aggregate_type, aggregate_id, aggregate_version, event_type)` 允许同版本不同事件类型。旧 Map 类型 `BUYER_ORDER_SUMMARY_CHANGED` 与新信封类型 `ORDER_SUMMARY_CHANGED` 必须保持区分，即使聚合版本相同也不撞唯一键；重复状态机调用不得重复产生同聚合版本、同类型事件。`target_config_version_id` 已由 `009-buyer-integration.sql` 增加，实施时新增 `OutboxEventDO.targetConfigVersionId` 及仓储写入/读取映射，不重复建列。

~~~text
onOrderStateChanged(order, project, now):
    if order.projectId == null or order.projectCodeSnapshot == null:
        retain legacy local event only; return
    envelope = createFromCurrentTransactionalFacts(order, eventType, occurredAtUTC)
    envelope.projectCode = order.projectCodeSnapshot
    app = applicationMapper.selectByProjectId(project.id)
    envelope.appId = app != null ? app.appId : null
    envelope.traceId/correlationId/causationId = current business context
    envelope.data = map OrderSummaryChangedEventData or FulfillmentResultEventData
        from current order/request facts; never include raw assets or cost
    callbackConfigId = project.currentCallbackConfigVersionId if config.enabled else null
    outboxRepository.appendBuyerEvent(envelope, aggregateVersion=order.detailVersion,
                                      targetConfigVersionId=callbackConfigId)
~~~

以上伪代码在**订单事务内部**执行，业务事实和 Outbox 同时提交；不得先提交订单再异步临时拼信封。配置版本 ID 在事件创建时冻结；未配置时为 null。旧 BUYER_ORDER_SUMMARY_CHANGED Map 及 O2O/到店购 Map 保持 payloadVersion=1 的日志兼容分支，不强转新 DTO。新事件的 eventId 与 body 在重试时固定。

### 5.2 分发与 HTTP 调用

~~~text
BuyerEventRpcDispatchHandler.dispatch(outboxEvent):
    if payloadVersion != 2 or eventType not in new standard event set:
        log legacy event summary; return             // 仅本地处理，非外部已送达
    envelope = deserializeAndValidate(payloadJson)
    require envelope.eventId == outboxEvent.eventId
    configId = outboxEvent.targetConfigVersionId
    if configId == null:
        log NO_HTTP_TARGET; return                    // 项目查询订单状态
    config = configMapper.selectById(configId)        // 不读取项目当前 URL
    project = projectMapper.selectByProjectCode(envelope.projectCode)
    require project != null && config.projectId == project.id
    require envelope.appId == applicationMapper.selectByProjectId(project.id).appId
    deliveryService.post(config, envelope, outboxEvent.payloadJson)

ProjectCallbackDispatchService.post(config, envelope, frozenBody):
    require callback URL host and actual connection IP still allowed; reject redirects
    require platform signing CURRENT
    sign fresh timestamp over frozenBody
    POST HTTPS with connectTimeout=3s, readTimeout=10s
    log projectCode, eventId, HTTP status/response digest, elapsedMs; redact sensitive data
    if response is not 2xx: throw typed delivery error
~~~

从可信 projectCode 查得 projectId 并与冻结配置归属核对，不将 projectId 加入外部信封。启用 HTTP 回调时 appId 必须存在且与冻结项目应用一致；无 HTTP 目标的旧事件可没有 appId。OutboxDeliveryEngine 现只会为 LOCAL target 调注册 handler；故本设计不增加 targetType=HTTP 或 buyer-event-http 注册名。失败抛给 Outbox 引擎，attempt_count、available_at、max_attempts 和死信均由现有引擎控制。无需 ProjectEventDeliveryService 自建重试，也不需要 sc_project_event_delivery。管理端投递记录按 Outbox 的 eventId、target_config_version_id 和状态查询只读投影。

吊销应用或运营停用回调对**新事件**生效；已冻结事件原则上继续投往原配置。若需要阻止在途投递，提供有原因、有审计的批量转人工处理命令；不能因读取当前 callback_enabled=false 就静默标记成功。HTTP 客户端禁止自动重定向，须由受控连接器或出口代理使实际连接 IP 与已校验的白名单结果一致，避免 DNS 二次解析绕过。

## 6. D 供应商订单回调

### 6.1 协议验签器与冻结订单

~~~text
interface SupplierInboundProtocol:
    adapterType()
    extractCorrelation(rawLimitedRequest)        // 只做限长结构预解析
    verify(rawBytes, headers, frozenConnectorVersion, normalizedPath)
    parseVerifiedFact(rawBytes)
    successAck(), failureAck(reason)

SupplierCallbackController.handle(connectorNo, callbackPath, raw):
    enforce size/rate limit; log digest only
    connector = findByNo(connectorNo)
    billno = protocol.extractCorrelation(raw)
    bOrder = findBySupplierAndBillno(connector.supplierId, billno)
    if absent: aggregate security reject; return protocol failureAck
    frozenVersion = callbackResolver.resolveForExistingOrder(bOrder.connectorVersionId)
    require frozenVersion.connectorId == connector.id
    require normalize(frozenVersion.callbackPath) == normalize(callbackPath)
    protocol.verify(raw, frozenVersion)             // 不使用 currentVersionId
    fact = protocol.parseVerifiedFact(raw)
    require fact.billno == billno
    return callbackConvergence.accept(bOrder, frozenVersion, fact)
~~~

Controller 仅依赖应用服务和协议入口 Facade，不直连 DAL。`findBySupplierAndBillno` 只查已持久化的 `(supplier_id, billno)`；建单时 `sc_b_order.billno NOT NULL` 且由 `FulfillmentAcceptanceLifecycle` 生成，出站请求必须直接使用该值。实施时移除 `FulfillmentOperationExecuteHandler` 的空 billno 改发 BOrderNo 兜底，发现缺失则失败并告警；不增加静默 BOrderNo 回查。历史/导入数据若破坏约束，应审计修复其单号关联。resolveForExistingOrder 可读取 PUBLISHED 或当前时间早于 callback_accept_until 的 RETIRED 版本，且只用于已创建的 BOrder；新下单仍要求当前发布版本。ConnectorPublicationService 退役前检查不存在非终态引用，并按供应商合同的 callback_retention_seconds 计算所有既有订单的验收截止；该值未知时拒绝退役。QNM 使用冻结 CredentialSlot 的秘密引用和 QnmSignUtils。标准协议从冻结 adapter_config_json 加载 inboundVerify；PublishedConnectorConfigurationResolver 需返回验证用的受控配置视图，当前返回值没有 adapterConfigJson，不能按现有 record 直接取该节点。标准协议严格校验 keyVersion、messageType、connectorNo、callbackPath、eventId、时间戳与 body SHA-256，规范串见供应商概要设计 §3.2。

FulfillmentOperationExecuteHandler 生成 notifyurl 时增加固定前缀与 connectorNo；上线前清点已发给供应商的旧 notifyurl。仍有在途订单的旧路径需要受限兼容路由，并使用同一冻结版本解析与验签流程；无 notifyurl 的历史订单继续查单恢复。契约测试覆盖新旧路径，兼容路由到验收截止后再下线。

### 6.2 事实去重与状态收敛

~~~text
SupplierCallbackConvergence.accept(bOrder, frozenVersion, verifiedFact):
    mapped = statusMappingResolver.resolve(frozenVersion.id, fact.rawStatus)
    originalOperation = operationMapper.selectOriginalSupplierOperation(bOrder.id)
    require originalOperation belongs to frozen BOrder and supplier
    if known externalOrderNo mismatches fact.externalOrderNo:
        record matched-order exception; return protocol failureAck
    key = stableSemanticKey(frozenVersion.id, fact)
    digestOfVerifiedBody = sha256(redact(fact.rawBody))

    @Transactional(supplyChainTransactionManager)
    inserted = observationMapper.insertIfAbsentByObservationKey(
        key=key,
        bOrderId=bOrder.id, operationId=originalOperation.id,
        mappingDefinitionId=mapped.id, rawDigest=digestOfVerifiedBody,
        mappedResultClass=mapped.factType)
    if !inserted:
        existing = observationMapper.selectByObservationKey(key)
        if standardProtocol && existing.payloadDigest != digestOfVerifiedBody:
            record protocol collision; return FAILURE_ACK
        return DUPLICATE_ACK
    lock originalOperation + BOrder; assess current order/fund state
    if compatible nonterminal: lifecycle.transitionPersistedFact(originalOperation, mapped, fact)
    else if conflicting or late success:
        append exception Case; quarantine new assets; preserve terminal order/fund facts
    return SUCCESS_ACK after transaction commit
~~~

insertIfAbsentByObservationKey 必须只把 observation_key 唯一冲突识别为重复；标准协议同一 eventId 对应的正文摘要变化是协议冲突，不能成功确认。其他约束/数据库错误上抛。可为该原子插入使用 XML Mapper，不能用会吞掉其他错误的 INSERT IGNORE。不得调用仍会自行 persistObservation 的旧 transition 路径；也不能让终态早退发生在新事实持久化前。状态映射 ID 必须是真实已发布定义，不再写 0。相同语义事实重投确认成功；不同终态事实保留审计并进入异常处理。无法匹配订单只做限频安全审计，因为没有 Case 所需的订单关联。

## 7. E 远程库存查询

### 7.1 来源管理与连接器绑定

~~~text
createRemoteObserved(supplierCode, skuCode, queryConnectorNo,
                     externalSourceCode, refreshSec, ttlSec):
    require active supplier and SKU
    connector = require connector by supplier + queryConnectorNo
    published = resolver.resolve(connector.currentVersionId)
    require published implements QUERY_INVENTORY and query port registered
    require 60 <= refreshSec <= maxBackoffSeconds (default 3600)
    require 60 <= ttlSec, bounded ttl upper limit
    insert InventorySource(type=REMOTE_OBSERVED, queryConnectorId=connector.id,
                           refreshIntervalSeconds=refreshSec, snapshotTtlSeconds=ttlSec,
                           nextRefreshAt=now, status=DRAFT)
~~~

来源与稳定 connector_id 绑定，不引用不存在的 source.connectorVersionId。刷新时解析该连接器当前发布版本；ConnectorPublicationService 发布新版本时，须拒绝移除任何启用来源依赖的 QUERY_INVENTORY。SupplierOfferPublicationService.publish 也须允许 REMOTE_OBSERVED，并验证来源的供应商/SKU、查询连接器归属及能力；查询连接器可与履约连接器不同。来源切换查询连接器用受审计的新来源或配置版本，不能悄悄改原有历史来源。QNM 不声明此能力。

~~~text
interface SupplierInventoryQueryPort:
    adapterType()
    queryInventory(publishedConnectorConfig, externalSourceCode)
        -> InventoryQueryResult(status, quantity?, supplierRequestNo?,
                                supplierObservedAt?, rawResponseDigestInput)
~~~

仅注册了 AdapterMetadata、真实查询实现和完整响应映射的适配器才能声明 QUERY_INVENTORY；SupplierInventoryQueryPort 不访问库存或订单 DAL。supplierObservedAt 只做诊断，中台接收时间决定快照 TTL。

### 7.2 多实例安全刷新

~~~text
InventorySnapshotRefreshScanner.tick():
    claimed = claimService.claimDueBatch(now, batchSize)
        // 短事务，FOR UPDATE SKIP LOCKED，按来源租约+fencing_token认领
    for claim in boundedWorkerPool:
        refreshOne(claim)                         // 数据库事务外 HTTP 查询

refreshOne(claim):
    started = monotonicClock
    try:
        config = resolver.resolveCurrent(claim.source.queryConnectorId)
        queryPort = registry.require(config.adapterType)
        result = queryPort.queryInventory(config, claim.source.externalSourceCode)
        receivedAt = supplyChainDatabaseClock.now()
        normalized = normalize(result)            // qty 0 -> UNAVAILABLE，矛盾值拒绝
        digest = sha256(redact(result.rawResponse))
    catch typed error:
        receivedAt = supplyChainDatabaseClock.now()
        normalized = ERROR, quantity=null
        digest = sha256(redactedErrorCode)
        aggregate warn by sourceNo/errorCode
    @Transactional(supplyChainTransactionManager)
    require claim.leaseOwner/fencingToken still current
    append InventorySnapshot(observedAt=receivedAt,
                             validUntil=receivedAt+source.snapshotTtlSeconds,
                             status=normalized.status, quantity=normalized.quantity,
                             rawDigest=digest)
    if normalized.status == ERROR:
        source.consecutiveErrorCount = min(30, source.consecutiveErrorCount + 1)
        delaySeconds = min(maxBackoffSeconds,
                           saturatingMultiply(source.refreshIntervalSeconds,
                                              2 ^ source.consecutiveErrorCount))
    else:
        source.consecutiveErrorCount = 0
        delaySeconds = source.refreshIntervalSeconds
    source.nextRefreshAt = receivedAt + delaySeconds
    release lease
    log query request/response digest and elapsedMs without credentials
~~~

任务扫描与工作池可参照 TimerEngineLifecycle，但必须有持久化租约，不能仅实现 SmartLifecycle 就声称多实例安全。认领 SQL 属于 Lambda 难以表达的锁/批次场景，可用 XML；调用超时和租约/续租配置要能覆盖最慢合法查询。失联后由租约到期恢复，旧执行者不得写迟到快照。调度仍受连接器 rateLimit/concurrencyLimit 约束。`consecutive_error_count` 与快照、`next_refresh_at` 在同一持锁短事务更新；成功时归零，失败（包括查询、解析、响应归一化错误）时递增，最大计数 30，首次失败等待 2 倍基础周期，之后指数增长至配置上限。服务配置 `aifuli.supply-chain.inventory.refresh.max-backoff-seconds` 默认 3600，且不得小于 60；来源创建、修改和进程启动时校验所有启用来源满足 `60 <= refresh_interval_seconds <= max_backoff_seconds`，保证失败调度不会比正常周期更频繁；乘法须饱和计算，避免溢出。租约失效或 fencing_token 不匹配时不写快照、不增加失败次数。ERROR 快照可在 TTL 内显示 ERROR，但路由语义是 UNKNOWN，不能覆盖更晚的成功快照。

## 8. F 路由读取快照

~~~text
resolveOffer(candidate):
    resolve published offer + its inventorySource
    allow REMOTE_ON_DEMAND and REMOTE_OBSERVED
    if REMOTE_OBSERVED:
        snapshot = snapshotMapper.latestBySourceId(source.id)
        inventoryFact = mapStatusQuantityAndFreshness(snapshot, order.quantity, now)
    else:
        inventoryFact = absent / NOT_CHECKED per published routing policy
    evaluateEligibility(candidate, inventoryFact, balanceFact, healthFact)
~~~

现有 RoutingInput.factsBySupplierCode 不足以承载同供应商不同 SKU/库存来源的库存事实；库存读数应按 InventorySource ID 在每个已解析 Offer 上注入，余额与健康事实可继续按供应商。用现有 REQUIRED/OPTIONAL/UNSUPPORTED 规则以及 inventoryMaxAgeSeconds 和 valid_until 判定：REQUIRED 遇缺失、过期、UNKNOWN、ERROR 拒绝；OPTIONAL 保留并标未知；UNSUPPORTED 不读取。明确缺货或已知数量不足本单需求时拒绝。订单事务内不得发 HTTP 库存查询，不得对远程库存创建本地预占。规则发布和运行时均校验 REMOTE_ON_DEMAND 与 REQUIRED 库存策略的不兼容配置。

## 9. 安全、事务和测试门禁

公开公钥 GET 与供应商回调 POST 更新供应链服务 application.yaml 的 aifuli.security.permit-all-urls，同时验证 Gateway 实际路由；项目管理接口保持登录及 @PreAuthorize。公开端点限制请求大小、速率和响应缓存策略。公网回调与库存调用日志只保留业务标识、脱敏摘要、耗时与错误码；不能输出卡密、私钥、appKey、shared secret、完整请求体。

- *Test：密码学规范串、能力授权、版本选择、幂等键、库存归一化、路由 REQUIRED/OPTIONAL。
- *IntegrationTest：真实 MySQL/Liquibase 下并发轮换、Outbox 冻结配置与死信、供应商旧版本回调及乱序事实、库存多节点认领和过期快照。
- *ContractTest：BuyerEventEnvelope 新旧载荷、QNM 响应、标准协议签名、公开公钥接口、库存查询适配器。
- *ArchitectureTest：管理端授权、认证包依赖方向、callback Controller 不直连 DAL、供应链数据库所有权。
- 联调步骤、前置条件、验证点及排障记录持续维护 docs/集成测试.md。文档先行；实现修改启动、安全、缓存或事务配置时评估供应链独立进程影响。
