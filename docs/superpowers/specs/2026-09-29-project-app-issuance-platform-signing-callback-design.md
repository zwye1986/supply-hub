# 项目应用发码与平台签名回调设计（2026-09-29）

## 1. 目标与现状

本文定义项目 appId/appKey 发码、平台签名公钥发布，以及标准商品订单事件向项目的 HTTP 回调。供应商侧见《2026-09-29-supplier-inbound-verify-inventory-design.md》，后端落点见《2026-09-29-access-config-backend-impl-design.md》。cardshop 与 cardshop-trade-engine 仅作旧业务参考，不接入，也不沿用其 V1 报文协议。供应链进程只读写独立 sc_* 表。

现有项目应用表、HMAC 过滤器和本地信封编解码器可复用。现有 BuyerEventRpcDispatchHandler 只解析并记录日志；标准商品受理 Outbox 写入的是 BUYER_ORDER_SUMMARY_CHANGED 普通 Map，履约终态没有 BuyerEventEnvelope 事件。因此本设计必须包含事件生产与投递链改造，不能只增加回调地址和签名器。

本轮覆盖标准商品 ORDER_SUMMARY_CHANGED 与 FULFILLMENT_RESULT。O2O、到店购现有事件行为不在本轮改造；CATALOG_CHANGED 继续由项目拉取。HTTP 通知可选，订单查询仍是权威状态获取方式。

## 2. 身份与凭证

| 方向 | 材料 | 用途 |
| --- | --- | --- |
| 项目 → 中台 | 稳定 appId、可轮换 appKey | 沿用项目 HMAC 请求签名；HTTPS 提供传输保密，appKey 不用作请求体加解密密钥。 |
| 中台 → 项目 | 全平台统一、按版本轮换的 RSA 私钥 | 中台签名事件；项目按版本获取平台公钥验签。 |
| 供应商 → 中台 | 连接器原生材料或标准协议公钥 | 由订单冻结的连接器版本决定。 |

一个项目最多一个应用身份，appId 永久绑定项目。凭证最多一个 CURRENT 和一个 GRACE；轮换先撤销旧 GRACE、再将旧 CURRENT 降为 GRACE、最后插入新 CURRENT。GRACE 到期在认证时拒绝，吊销不删除审计。appKey 明文仅在开通或轮换响应中返回一次；列表仅返回 masked_hint。

吊销是立即撤销该应用当前全部凭证，不删除 appId。需要恢复同一项目时，管理员在审计确认后执行轮换，生成更高 keyVersion 的新 CURRENT；不能再次开通另一个 appId。吊销后的项目回调保持停用，须由管理员重新启用。

认证服务先确认应用、项目、密钥和签名，再按 operationCode 校验项目能力。标准商品要求 STANDARD_PRODUCT，到店购继续要求 INSTORE。现有 ProjectAppCredentialService.authenticate 无条件检查 INSTORE，须将能力校验移至按操作授权层；否则标准商品项目即使已发码也无法调用。错误响应继续避免应用枚举。

## 3. 项目应用管理

发码服务放在 tradecore.buyer.application，使用 BuyerProjectMapper 校验 projectCode，调用 infrastructure.auth 的凭证 Mapper 与 LocalEnvelopeAppKeyCodec。不得让 infrastructure.auth 反向依赖 tradecore；AuthBuyerProjectDO 保持认证只读投影。ProjectAppCredentialVersionDO 补齐现有 DDL 已有的 masked_hint、active_slot、created_at 字段，ProjectApplicationMapper 补按项目查询与锁定方法。

| 管理端接口 | 行为 |
| --- | --- |
| POST /supply-chain/projects/{projectCode}/application/enable | 项目启用且尚无应用时生成 appId 和第 1 版 CURRENT；重复开通返回冲突。响应一次性含 appId/keyVersion/appKey/maskedHint。 |
| POST /supply-chain/projects/{projectCode}/application/rotate | 锁应用行，原子切换 CURRENT/GRACE；默认过渡窗口 24 小时，由 aifuli.supply-chain.auth.grace-window 配置。 |
| POST /supply-chain/projects/{projectCode}/application/revoke | 要求确认标记与原因，吊销活动凭证，并停用该项目后续事件回调。 |
| GET /supply-chain/projects/{projectCode}/application/credentials | 返回 appId、版本、状态、脱敏提示及生效时间；不返回明文或 secret_ref。 |

appKey 用 32 字节 SecureRandom 生成并 Base64URL 编码。secret_ref 复用 LocalEnvelopeAppKeyCodec，AAD 绑定 appId 与 keyVersion。masterKey 按 ProjectAppAuthProperties 现有规则解析。应用行锁、版本唯一键和 active_slot 唯一键共同约束并发。发码响应设置 Cache-Control: no-store；日志、DTO toString、浏览器持久化存储均不得包含明文 appKey。管理接口全部带 @PreAuthorize 并记录审计。

## 4. 平台签名密钥与验签协议

平台使用 SHA256withRSA、2048 位 RSA 密钥。sc_platform_signing_key 只保存公钥、状态和审计；私钥通过部署侧 keyVersion → secret_ref 映射解析。状态为 NEXT → CURRENT → PREVIOUS → RETIRED，允许多个 PREVIOUS 并存。sc_platform_signing_control 固定单行负责串行化切换和保存当前版本，密钥表的可空 active_slot 唯一键兜底保证最多一个 CURRENT，避免空表时“锁全部密钥行”无效。

预发布校验 PEM 和位数；激活前用部署侧私钥签随机挑战，再用待发布公钥验证，确认密钥对匹配。启动及当前版本切换时复核控制行指向的 CURRENT 与可解析私钥一致，签名前只使用已校验的当前版本；配置不一致时失败关闭。激活事务锁控制行，原 CURRENT 转 PREVIOUS，新版本转 CURRENT。保留窗口按 Outbox 最大尝试数、退避上限、HTTP 超时与双倍时间戳容差计算，配置溢出或小于计算值时启动失败；旧公钥至少保留该窗口。再次轮换不得自动退役未到保留期的 PREVIOUS，提前退役在保留期内拒绝。每次重投以当时 CURRENT 重新签名并使用新时间戳。项目启用 HTTP 回调前必须存在 CURRENT。

公开接口无登录态：GET /supply-chain/open/platform-signing-keys 返回 CURRENT 和所有未退役 PREVIOUS；GET /supply-chain/open/platform-signing-keys/{keyVersion} 还允许查询 NEXT 供预缓存，RETIRED 不下发。管理端提供预发布、激活、退役和列表接口。

HTTP 回调头：X-Aifui-Key-Version、X-Aifui-Timestamp、X-Aifui-Signature、X-Aifui-Event-Id。时间戳为 Unix 毫秒十进制，签名为标准 Base64。规范串按以下九行以 LF 连接，无末尾换行；对其 UTF-8 字节做 SHA256withRSA：

~~~text
SC-PROJECT-CALLBACK-V1
keyVersion
timestamp
eventId
eventType
occurredAt
projectCode
appId
lowercaseHex(SHA-256(bodyBytes))
~~~

正文使用 BuyerEventEnvelope 的固定 JSON 序列化。项目必须以收到的原始 body 字节计算摘要，不重新序列化；验签后核对正文 eventId 与头一致、projectCode/appId 属于自己，并检查时间戳 ±5 分钟。同一 eventId 只处理一次。重试保持 eventId 和 bodyBytes 不变，可更新请求时间戳与签名。

## 5. 项目回调配置与事件投递

项目回调配置采用不可变版本 sc_project_callback_config_version，sc_buyer_project 仅保存当前版本 ID。PUT /supply-chain/projects/{projectCode}/callback 创建新版本，包含 HTTPS URL、启用标记、操作人和时间。启用要求项目应用可用、平台签名 CURRENT 存在。保存时校验出口白名单；发送前再次核对主机与**实际连接目的 IP**，由受控 HTTP 客户端或出口代理固定已校验的解析结果，禁止私网/回环地址及跨白名单重定向，避免检查后再次解析造成绕过。地址变更或停用不覆盖旧版本。

标准商品受理后、每次对项目可见的订单汇总变化，在同一订单事务内生成 ORDER_SUMMARY_CHANGED 信封和 Outbox；履约终态额外生成 FULFILLMENT_RESULT。Outbox event_id 与信封 eventId 一致，event_type 使用信封稳定类型；按订单聚合版本有序。旧 BUYER_ORDER_SUMMARY_CHANGED 普通 Map 仅作迁移兼容，不得直接反序列化为新信封。BuyerEventEnvelope 增 projectCode/appId；启用回调的项目两者必填。事件创建时将当前回调配置版本写入现有 target_config_version_id，重试始终使用冻结 URL。项目后续改址不改变在途事件的目标；若运营要停止这些事件，应显式转人工处理并留审计。

ORDER_SUMMARY_CHANGED 的 data 使用现有 OrderSummaryChangedEventData：orderNo、buyerOrderNo、orderType、公共主状态、售后状态、businessStage、detailVersion、供应商展示快照和 updatedAt 均取同一事务中刚更新的订单事实。FULFILLMENT_RESULT 的 data 使用现有 FulfillmentResultEventData：请求号、请求与已交付数量、标准履约状态、完成时间取履约请求和订单事实；assetAvailable 仅表示是否存在可授权查询的资产，assetReference 只给查询引用，正文绝不包含卡号、卡密或成本。数据库 Asia/Shanghai 的 LocalDateTime 转为对外 OffsetDateTime 时必须带明确时区。旧 CARD 订单的 projectId/projectCodeSnapshot 可为空，这类订单只保留原本地事件，不产生项目 HTTP 目标，不能凭采购商猜测项目。

Outbox 是唯一的重试主控。继续使用现有 LOCAL 目标 buyer-event-rpc，在其分发器中依据冻结配置版本执行 HTTP POST；不新建 buyer-event-http 目标，也不另建项目投递调度器。HTTP 调用在数据库事务之外；2xx 才视为项目接收，非 2xx 或超时抛给 Outbox 退避重试，达到 max_attempts 后死信。投递状态、尝试数与重试时刻直接使用 sc_outbox_event，不新增重复事实源 sc_project_event_delivery。运营可查看死信并按同一 eventId 人工重投。

未配置回调的项目沿用 buyer-event-rpc 的日志分支；它仅表示本地事件已处理，不表示项目已收到通知。此时项目通过订单查询获取权威状态。O2O/到店购原有 Map 事件保持原行为，不误按标准商品信封解析。

## 6. Liquibase 变更

所有新增 DATETIME(3) 使用数据库 Asia/Shanghai 本地时间，与 SupplyChainDatabaseClock 和 022-database-local-time.sql 一致；外部 occurredAt 使用带时区 ISO 8601。项目凭证表现有 masked_hint 和 active_slot，不重复加列。

~~~sql
ALTER TABLE sc_buyer_project
    ADD COLUMN current_callback_config_version_id BIGINT NULL
        COMMENT '当前项目事件回调配置版本主键，逻辑引用sc_project_callback_config_version.id';

CREATE TABLE sc_project_callback_config_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目事件回调配置版本内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键，逻辑引用sc_buyer_project.id',
    version_no INT NOT NULL COMMENT '项目内递增的回调配置版本号',
    callback_url VARCHAR(512) NOT NULL COMMENT '项目事件回调HTTPS地址',
    enabled TINYINT NOT NULL COMMENT '回调启用标记：1启用，0停用',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_callback_config_version (project_id, version_no),
    CONSTRAINT ck_sc_project_callback_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目事件回调不可变配置版本';

CREATE TABLE sc_platform_signing_control (
    id TINYINT NOT NULL COMMENT '平台签名密钥控制行固定主键，值为1',
    current_key_id BIGINT NULL COMMENT '当前平台签名公钥版本主键，逻辑引用sc_platform_signing_key.id',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '切换乐观锁版本号',
    PRIMARY KEY (id),
    CONSTRAINT ck_sc_platform_signing_control_id CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台签名密钥切换控制';

CREATE TABLE sc_platform_signing_key (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台签名密钥版本内部主键',
    key_version VARCHAR(32) NOT NULL COMMENT '平台签名密钥版本标识，回调请求头携带',
    algorithm VARCHAR(32) NOT NULL COMMENT '签名算法，固定SHA256withRSA',
    public_key_pem TEXT NOT NULL COMMENT '平台公钥PEM，可公开下发',
    status VARCHAR(16) NOT NULL COMMENT '密钥状态：NEXT预发布，CURRENT当前，PREVIOUS验签保留，RETIRED已退役',
    active_slot VARCHAR(16) NULL COMMENT '当前签名位：CURRENT当前，其它状态为空',
    activated_at DATETIME(3) NULL COMMENT '切换为CURRENT时间，Asia/Shanghai',
    retain_until DATETIME(3) NULL COMMENT '公钥最早可退役时间，Asia/Shanghai',
    retired_at DATETIME(3) NULL COMMENT '退役时间，Asia/Shanghai',
    remark VARCHAR(256) NULL COMMENT '备注',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后更新时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_platform_signing_key_version (key_version),
    UNIQUE KEY uk_sc_platform_signing_key_active (active_slot),
    KEY idx_sc_platform_signing_key_status (status, retain_until),
    CONSTRAINT ck_sc_platform_signing_key_status CHECK (status IN ('NEXT', 'CURRENT', 'PREVIOUS', 'RETIRED')),
    CONSTRAINT ck_sc_platform_signing_key_slot CHECK (
        (status = 'CURRENT' AND active_slot = 'CURRENT')
        OR (status <> 'CURRENT' AND active_slot IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台事件签名公钥版本';
~~~

迁移建表后插入 sc_platform_signing_control(id=1)。Outbox 既有 target_config_version_id 保存冻结的回调版本；事件正文不保存回调 URL 或私钥。

## 7. 管理端配套和安全边界

supplychainhubweb 项目详情增加应用凭证、回调地址和通知记录：开通/轮换/吊销、一次性密钥展示与复制、脱敏凭证列表、回调配置、最近投递与死信入口。平台密钥管理页展示版本、公钥指纹、状态和保留截止时间；不回显 secret_ref 或私钥。菜单和权限由 script/deploy 写入 system 库，供应链进程不访问 system_*。

公开公钥接口须在供应链服务 aifuli.security.permit-all-urls 精确放行，并核对 Gateway 路由和访问日志脱敏；管理接口继续要求登录和 @PreAuthorize。公开接口限流。

## 8. 测试与联调门禁

- *Test：发码与轮换状态机、按 operationCode 的能力校验、公私钥匹配、多次连续轮换、时间戳篡改、公钥保留、回调目标校验。
- *IntegrationTest：真实 MySQL/Liquibase 下并发发码与切换、CURRENT/GRACE 并行验签、受理及履约终态事件生产、冻结 URL 重试、HTTP 成功/失败/死信、未配置项目日志分支。
- *ContractTest：BuyerEventEnvelope 新字段与旧消费者兼容、事件类型和字段、公开公钥接口、逐字节签名验签；旧 Map 事件不被误解析。
- *ArchitectureTest：管理接口 @PreAuthorize、认证包不反向依赖 tradecore、供应链仅访问 sc_*、公开端点在服务内实际匿名可达。
- 将联调前置条件、签名样例、轮换与失败排障写入 docs/集成测试.md；完成后端和管理端配套后再做真实标准订单闭环联调。
