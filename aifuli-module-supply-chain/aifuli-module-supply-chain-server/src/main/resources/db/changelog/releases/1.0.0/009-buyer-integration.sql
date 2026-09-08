-- 表中文名：采购商集成档案
-- 核心职责：维护每个采购商唯一的集成配置入口和当前发布版本
CREATE TABLE sc_buyer_integration_profile (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成档案内部主键',
    profile_no VARCHAR(32) NOT NULL COMMENT '采购商集成档案稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    profile_name VARCHAR(128) NOT NULL COMMENT '采购商集成档案名称',
    current_version_id BIGINT NULL COMMENT '当前已发布配置版本内部主键，逻辑引用sc_buyer_integration_profile_version.id',
    status SMALLINT NOT NULL COMMENT '档案状态：10启用，20暂停新业务，90已归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '档案乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_profile_no (profile_no),
    UNIQUE KEY uk_sc_buyer_integration_profile_buyer (buyer_id),
    KEY idx_sc_buyer_integration_profile_status (status),
    KEY idx_sc_buyer_integration_profile_current (current_version_id),
    CONSTRAINT ck_sc_buyer_integration_profile_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商唯一集成配置档案';

-- 表中文名：采购商集成档案版本
-- 核心职责：维护草稿、验证、发布、替代和退役的不可变配置版本
CREATE TABLE sc_buyer_integration_profile_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成档案版本内部主键',
    profile_id BIGINT NOT NULL COMMENT '采购商集成档案内部主键，逻辑引用sc_buyer_integration_profile.id',
    version_no INT NOT NULL COMMENT '同一档案下递增的配置版本号',
    status SMALLINT NOT NULL COMMENT '版本状态：0草稿，5已验证，10已发布，20已被替代，90已退役',
    effective_at DATETIME(3) NULL COMMENT '版本业务生效时间，UTC',
    validated_by BIGINT NULL COMMENT '执行配置验证的用户ID',
    validated_at DATETIME(3) NULL COMMENT '配置验证通过时间，UTC',
    published_by BIGINT NULL COMMENT '发布配置版本的用户ID',
    published_at DATETIME(3) NULL COMMENT '配置版本发布时间，UTC',
    change_reason VARCHAR(512) NOT NULL COMMENT '本版本变更或回滚原因',
    content_digest BINARY(32) NOT NULL COMMENT '版本全部能力配置的SHA-256摘要',
    current_flag TINYINT NULL COMMENT '当前已发布版本标记：1当前，NULL历史或草稿',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '草稿修改和发布状态乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_version_no (profile_id, version_no),
    UNIQUE KEY uk_sc_buyer_integration_current (profile_id, current_flag),
    KEY idx_sc_buyer_integration_version_status (profile_id, status, effective_at),
    CONSTRAINT ck_sc_buyer_integration_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_buyer_integration_current CHECK (current_flag IS NULL OR (current_flag = 1 AND status = 10)),
    CONSTRAINT ck_sc_buyer_integration_version_status CHECK (status IN (0, 5, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商集成配置不可变发布版本';

-- 表中文名：采购商集成能力配置
-- 核心职责：维护一个集成版本内各能力的传输、Adapter、契约和执行策略
CREATE TABLE sc_buyer_integration_capability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成能力配置内部主键',
    profile_version_id BIGINT NOT NULL COMMENT '采购商集成档案版本内部主键，逻辑引用sc_buyer_integration_profile_version.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '能力编码：CASHIER、ORDER_EVENT、FULFILLMENT_EVENT、CATALOG_EVENT',
    transport_mode VARCHAR(16) NOT NULL COMMENT '传输模式：RPC内部服务调用，HTTP签名网络调用',
    adapter_code VARCHAR(64) NOT NULL COMMENT '受控的采购商Adapter编码',
    contract_version VARCHAR(32) NOT NULL COMMENT '采购商集成契约版本',
    http_base_url VARCHAR(512) NULL COMMENT 'HTTP模式HTTPS基础地址，RPC模式必须为NULL',
    credential_set_id BIGINT NULL COMMENT 'HTTP出站凭证集内部主键，逻辑引用sc_buyer_credential_set.id',
    connect_timeout_ms INT NOT NULL COMMENT '连接超时时间，毫秒',
    read_timeout_ms INT NOT NULL COMMENT '读取超时时间，毫秒',
    total_timeout_ms INT NOT NULL COMMENT '单次调用端到端超时时间，毫秒',
    max_response_bytes INT NOT NULL COMMENT '允许的最大响应体字节数',
    bulkhead_concurrency INT NOT NULL COMMENT '同一Buyer同一能力的最大并发舱大小',
    rate_limit_per_second INT NOT NULL COMMENT '同一Buyer同一能力的每秒请求上限',
    circuit_breaker_policy VARCHAR(64) NOT NULL COMMENT '受控的熔断策略编码',
    retry_policy_code VARCHAR(32) NOT NULL COMMENT '重试策略：NONE、QUERY_SAME_KEY、OUTBOX_RETRY',
    status SMALLINT NOT NULL COMMENT '能力状态：10启用，20停用',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_integration_capability (profile_version_id, capability_code),
    KEY idx_sc_buyer_integration_adapter (capability_code, transport_mode, adapter_code, contract_version),
    KEY idx_sc_buyer_integration_credential (credential_set_id),
    CONSTRAINT ck_sc_buyer_integration_capability CHECK (capability_code IN ('CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT')),
    CONSTRAINT ck_sc_buyer_integration_transport CHECK (transport_mode IN ('RPC', 'HTTP')),
    CONSTRAINT ck_sc_buyer_integration_endpoint CHECK ((transport_mode = 'RPC' AND http_base_url IS NULL AND credential_set_id IS NULL) OR (transport_mode = 'HTTP' AND http_base_url IS NOT NULL AND credential_set_id IS NOT NULL)),
    CONSTRAINT ck_sc_buyer_integration_timeout CHECK (connect_timeout_ms > 0 AND read_timeout_ms > 0 AND total_timeout_ms > 0),
    CONSTRAINT ck_sc_buyer_integration_limits CHECK (max_response_bytes > 0 AND bulkhead_concurrency > 0 AND rate_limit_per_second > 0),
    CONSTRAINT ck_sc_buyer_integration_retry CHECK (retry_policy_code IN ('NONE', 'QUERY_SAME_KEY', 'OUTBOX_RETRY')),
    CONSTRAINT ck_sc_buyer_integration_capability_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商版本化集成能力与执行策略';

-- 表中文名：采购商凭证集
-- 核心职责：维护采购商入站或出站鉴权凭证的稳定逻辑身份
CREATE TABLE sc_buyer_credential_set (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商凭证集内部主键',
    credential_set_no VARCHAR(32) NOT NULL COMMENT '采购商凭证集稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    direction VARCHAR(16) NOT NULL COMMENT '凭证方向：INBOUND采购商调用中台，OUTBOUND中台调用采购商',
    credential_code VARCHAR(64) NOT NULL COMMENT '同一采购商和方向下的凭证用途编码',
    authentication_scheme VARCHAR(32) NOT NULL COMMENT '鉴权方案，HTTP一期保留HMAC_SHA256',
    status SMALLINT NOT NULL COMMENT '凭证集状态：10启用，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '凭证集乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_credential_set_no (credential_set_no),
    UNIQUE KEY uk_sc_buyer_credential_set_code (buyer_id, direction, credential_code),
    KEY idx_sc_buyer_credential_set_status (buyer_id, status),
    CONSTRAINT ck_sc_buyer_credential_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_sc_buyer_credential_scheme CHECK (authentication_scheme IN ('HMAC_SHA256')),
    CONSTRAINT ck_sc_buyer_credential_set_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商入站与出站鉴权凭证集';

-- 表中文名：采购商凭证版本
-- 核心职责：维护密钥系统引用、生效窗口和ACTIVE/PREVIOUS无停机轮换位
CREATE TABLE sc_buyer_credential_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商凭证版本内部主键',
    credential_set_id BIGINT NOT NULL COMMENT '采购商凭证集内部主键，逻辑引用sc_buyer_credential_set.id',
    version_no INT NOT NULL COMMENT '同一凭证集下递增的凭证版本号',
    key_id VARCHAR(128) NOT NULL COMMENT '签名协议中可传输的密钥标识，不是密钥本身',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥管理系统引用，禁止保存或回显明文密钥',
    masked_hint VARCHAR(128) NULL COMMENT '用于管理页确认的脱敏凭证提示',
    slot_code VARCHAR(16) NULL COMMENT '轮换位：ACTIVE当前，PREVIOUS过渡，NULL待激活或已退役',
    valid_from DATETIME(3) NOT NULL COMMENT '凭证最早可使用时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '凭证最晚可验证时间，UTC，空表示未预设到期',
    status SMALLINT NOT NULL COMMENT '凭证版本状态：0待激活，10当前使用，20轮换过渡，90已退役',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    activated_by BIGINT NULL COMMENT '激活或轮换本版本的用户ID',
    activated_at DATETIME(3) NULL COMMENT '凭证版本激活时间，UTC',
    retired_at DATETIME(3) NULL COMMENT '凭证版本退役时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_credential_version_no (credential_set_id, version_no),
    UNIQUE KEY uk_sc_buyer_credential_slot (credential_set_id, slot_code),
    UNIQUE KEY uk_sc_buyer_credential_key (credential_set_id, key_id),
    KEY idx_sc_buyer_credential_validity (credential_set_id, status, valid_from, valid_to),
    CONSTRAINT ck_sc_buyer_credential_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_sc_buyer_credential_slot CHECK ((status = 0 AND slot_code IS NULL) OR (status = 10 AND slot_code = 'ACTIVE') OR (status = 20 AND slot_code = 'PREVIOUS') OR (status = 90 AND slot_code IS NULL)),
    CONSTRAINT ck_sc_buyer_credential_time CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_sc_buyer_credential_version_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商密钥引用版本与无停机轮换位';

-- 表中文名：采购商HTTP访问客户端
-- 核心职责：为保留的HTTP入站方式把appKey和来源系统映射为可验证Buyer与scope
CREATE TABLE sc_buyer_access_client (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商HTTP访问客户端内部主键',
    client_no VARCHAR(32) NOT NULL COMMENT '采购商HTTP访问客户端稳定业务编号',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    client_code VARCHAR(64) NOT NULL COMMENT '同一采购商下的HTTP访问客户端编码',
    app_key VARCHAR(128) NOT NULL COMMENT 'HTTP签名中用于定位客户端的公开appKey',
    source_system VARCHAR(32) NOT NULL COMMENT '客户端允许声明的来源系统编码',
    credential_set_id BIGINT NOT NULL COMMENT 'HTTP入站凭证集内部主键，逻辑引用sc_buyer_credential_set.id',
    scope_json JSON NOT NULL COMMENT '允许该客户端使用的受控scope编码数组',
    allowed_ip_cidrs_json JSON NULL COMMENT '可选的入站IP网段白名单数组',
    status SMALLINT NOT NULL COMMENT '访问客户端状态：10启用，20暂停，90已退役',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '访问客户端乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_access_client_no (client_no),
    UNIQUE KEY uk_sc_buyer_access_app_key (app_key),
    UNIQUE KEY uk_sc_buyer_access_client_code (buyer_id, client_code),
    KEY idx_sc_buyer_access_source (source_system, status),
    KEY idx_sc_buyer_access_credential (credential_set_id),
    CONSTRAINT ck_sc_buyer_access_client_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商HTTP入站签名客户端与权限映射';

-- 表中文名：采购商集成调用日志
-- 核心职责：追加记录每次采购商集成尝试使用的配置、凭证、摘要和标准结果
CREATE TABLE sc_buyer_integration_call_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购商集成调用日志内部主键',
    invocation_id VARCHAR(64) NOT NULL COMMENT '业务操作或事件投递的稳定调用标识',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '本次调用的能力编码',
    operation_code VARCHAR(64) NOT NULL COMMENT '本次调用的标准操作编码',
    profile_version_id BIGINT NOT NULL COMMENT '实际使用的采购商集成档案版本内部主键',
    credential_version_id BIGINT NULL COMMENT '实际使用或验证的凭证版本内部主键，RPC可为NULL',
    transport_mode VARCHAR(16) NOT NULL COMMENT '本次调用的传输模式：RPC或HTTP',
    business_key_hash BINARY(32) NOT NULL COMMENT '业务键SHA-256摘要，禁止保存敏感业务键明文',
    request_digest BINARY(32) NOT NULL COMMENT '脱敏标准请求SHA-256摘要',
    response_digest BINARY(32) NULL COMMENT '脱敏标准响应SHA-256摘要',
    result_status SMALLINT NOT NULL COMMENT '调用结果：0调用中，10成功，20业务拒绝，30结果未知，40技术失败',
    error_code VARCHAR(64) NULL COMMENT '标准化调用错误码',
    http_status INT NULL COMMENT 'HTTP响应状态码，RPC或无响应时为NULL',
    duration_ms BIGINT NULL COMMENT '本次调用耗时，毫秒，调用未终结时为NULL',
    trace_id VARCHAR(64) NOT NULL COMMENT '本次调用的链路追踪标识',
    started_at DATETIME(3) NOT NULL COMMENT '本次调用开始时间，UTC',
    completed_at DATETIME(3) NULL COMMENT '本次调用结束时间，UTC',
    PRIMARY KEY (id),
    KEY idx_sc_buyer_integration_call_invocation (invocation_id, started_at, id),
    KEY idx_sc_buyer_integration_call_buyer (buyer_id, capability_code, started_at),
    KEY idx_sc_buyer_integration_call_profile (profile_version_id, started_at),
    KEY idx_sc_buyer_integration_call_result (result_status, started_at),
    KEY idx_sc_buyer_integration_call_trace (trace_id),
    CONSTRAINT ck_sc_buyer_integration_call_capability CHECK (capability_code IN ('CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT')),
    CONSTRAINT ck_sc_buyer_integration_call_transport CHECK (transport_mode IN ('RPC', 'HTTP')),
    CONSTRAINT ck_sc_buyer_integration_call_result CHECK (result_status IN (0, 10, 20, 30, 40)),
    CONSTRAINT ck_sc_buyer_integration_call_time CHECK ((result_status = 0 AND completed_at IS NULL AND duration_ms IS NULL) OR (result_status <> 0 AND completed_at IS NOT NULL AND completed_at >= started_at AND duration_ms IS NOT NULL AND duration_ms >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商集成调用脱敏追加审计';

-- 表中文名：Outbox事件（采购商目标扩展）
-- 核心职责：为采购商事件固化RPC/HTTP目标能力与发布配置版本
ALTER TABLE sc_outbox_event
    MODIFY COLUMN target_type VARCHAR(32) NOT NULL COMMENT '投递目标类型：LOCAL内部消费者，RPC内部服务，HTTP外部签名端点',
    ADD COLUMN target_capability_code VARCHAR(32) NULL COMMENT '采购商投递能力编码，非采购商目标可为NULL' AFTER target_name,
    ADD COLUMN target_config_version_id BIGINT NULL COMMENT '创建事件时固化的目标配置版本内部主键' AFTER target_capability_code,
    ADD KEY idx_sc_outbox_target_config (target_type, target_capability_code, target_config_version_id);

ALTER TABLE sc_outbox_event
    DROP CHECK ck_sc_outbox_target;

ALTER TABLE sc_outbox_event
    ADD CONSTRAINT ck_sc_outbox_target CHECK (target_type IN ('LOCAL', 'RPC', 'HTTP'));
