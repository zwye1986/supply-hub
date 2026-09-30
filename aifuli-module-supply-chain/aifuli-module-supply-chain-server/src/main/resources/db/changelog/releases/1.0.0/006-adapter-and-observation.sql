-- 表中文名：供应商连接器能力
-- 核心职责：声明连接器版本通过契约验证后可启用的封闭能力
CREATE TABLE sc_supplier_connector_capability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '连接器能力内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '封闭能力编码，例如CREATE_RECHARGE、ISSUE_ASSET或QUERY_ORDER',
    enabled TINYINT NOT NULL COMMENT '能力启用标记：1启用，0禁用',
    metadata_version INT NOT NULL COMMENT '能力元数据契约版本',
    created_at DATETIME(3) NOT NULL COMMENT '能力记录创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_connector_capability (connector_version_id, capability_code),
    KEY idx_sc_connector_capability_enabled (capability_code, enabled),
    CONSTRAINT ck_sc_connector_capability_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_sc_connector_capability_version CHECK (metadata_version >= 1),
    CONSTRAINT ck_sc_connector_capability_code CHECK (capability_code IN ('CREATE_RECHARGE', 'ISSUE_ASSET', 'BIND_BENEFIT', 'QUERY_ORDER', 'QUERY_ASSET', 'VOID_ASSET', 'UNBIND_ASSET', 'QUERY_BALANCE', 'QUERY_INVENTORY', 'RECEIVE_CALLBACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商连接器已验证的封闭能力';

-- 表中文名：供应商错误码映射
-- 核心职责：把供应商原始错误码版本化映射为受限标准动作
CREATE TABLE sc_supplier_error_code_map (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商错误码映射内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    mapping_version INT NOT NULL COMMENT '错误码映射发布版本',
    external_error_code VARCHAR(64) NOT NULL COMMENT '供应商原始错误码',
    external_sub_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商原始子错误码，无子码使用空字符串',
    error_class VARCHAR(32) NOT NULL COMMENT '标准错误分类：BUSINESS_REJECTED、RETRYABLE、RESULT_UNKNOWN或MANUAL_REQUIRED',
    action_type VARCHAR(32) NOT NULL COMMENT '受限动作：REJECT拒绝，RETRY_SAFE安全重试，QUERY_FIRST先查单，MANUAL人工',
    retryable_flag TINYINT NOT NULL COMMENT '是否允许在策略约束下重试：1允许，0不允许',
    status SMALLINT NOT NULL COMMENT '映射状态：0草稿，10已发布，90已退役',
    published_at DATETIME(3) NULL COMMENT '映射发布时间，UTC',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '映射创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_supplier_error_map (connector_version_id, mapping_version, external_error_code, external_sub_code),
    KEY idx_sc_supplier_error_lookup (connector_version_id, status, external_error_code),
    CONSTRAINT ck_sc_supplier_error_version CHECK (mapping_version >= 1),
    CONSTRAINT ck_sc_supplier_error_retry CHECK (retryable_flag IN (0, 1)),
    CONSTRAINT ck_sc_supplier_error_class CHECK (error_class IN ('BUSINESS_REJECTED', 'RETRYABLE', 'RESULT_UNKNOWN', 'MANUAL_REQUIRED')),
    CONSTRAINT ck_sc_supplier_error_action CHECK (action_type IN ('REJECT', 'RETRY_SAFE', 'QUERY_FIRST', 'MANUAL')),
    CONSTRAINT ck_sc_supplier_error_status CHECK (status IN (0, 10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商原始错误码到受限标准动作的版本化映射';

-- 表中文名：供应商状态映射定义
-- 核心职责：把供应商原始状态版本化映射为业务阶段和封闭事实
CREATE TABLE sc_status_mapping_definition (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商状态映射定义内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    mapping_version INT NOT NULL COMMENT '状态映射发布版本',
    external_status VARCHAR(64) NOT NULL COMMENT '供应商原始主状态码',
    external_sub_status VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商原始子状态码，无子状态使用空字符串',
    business_phase VARCHAR(32) NOT NULL COMMENT '供应商协议业务阶段，例如CREATE、PROCESSING或DELIVERY',
    fact_type VARCHAR(32) NOT NULL COMMENT '封闭标准事实：ACCEPTED、SUCCEEDED、PARTIALLY_SUCCEEDED、REJECTED、NOT_FOUND、RESULT_UNKNOWN或MANUAL_REQUIRED',
    terminal_flag TINYINT NOT NULL COMMENT '该供应商原始状态是否为其协议终态：1是，0否',
    priority INT NOT NULL DEFAULT 0 COMMENT '同一状态匹配优先级，数值越大越优先',
    status SMALLINT NOT NULL COMMENT '映射生命周期：0草稿，10已发布，90已退役',
    published_at DATETIME(3) NULL COMMENT '映射发布时间，UTC',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '映射创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_status_mapping (connector_version_id, mapping_version, external_status, external_sub_status),
    KEY idx_sc_status_mapping_lookup (connector_version_id, status, external_status),
    CONSTRAINT ck_sc_status_mapping_version CHECK (mapping_version >= 1),
    CONSTRAINT ck_sc_status_mapping_terminal CHECK (terminal_flag IN (0, 1)),
    CONSTRAINT ck_sc_status_mapping_fact CHECK (fact_type IN ('ACCEPTED', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'REJECTED', 'NOT_FOUND', 'RESULT_UNKNOWN', 'MANUAL_REQUIRED')),
    CONSTRAINT ck_sc_status_mapping_status CHECK (status IN (0, 10, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商原始状态到业务阶段和封闭事实的版本化映射';

-- 表中文名：供应商履约调用日志
-- 核心职责：追加记录每次供应商网络调用的脱敏传输审计
CREATE TABLE sc_fulfillment_call_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商调用审计内部主键',
    call_no VARCHAR(32) NOT NULL COMMENT '供应商调用稳定审计编号',
    operation_id BIGINT NOT NULL COMMENT '履约外部操作主键，逻辑引用sc_fulfillment_operation.id',
    b_order_id BIGINT NOT NULL COMMENT 'B端履约尝试主键',
    attempt_no INT NOT NULL COMMENT '同一操作内网络调用尝试序号，从1递增',
    direction VARCHAR(16) NOT NULL COMMENT '调用方向：OUTBOUND中台请求供应商，INBOUND供应商回调中台',
    connector_version_id BIGINT NOT NULL COMMENT '本次调用冻结使用的连接器版本主键',
    endpoint_code VARCHAR(64) NOT NULL COMMENT '适配器声明的供应商端点编码',
    started_at DATETIME(3) NOT NULL COMMENT '调用开始时间，UTC',
    duration_ms BIGINT NOT NULL COMMENT '调用耗时，毫秒',
    result_class VARCHAR(32) NOT NULL COMMENT '脱敏调用结果分类',
    http_status INT NULL COMMENT 'HTTP响应状态码，未获得响应时为空',
    request_digest BINARY(32) NOT NULL COMMENT '脱敏请求SHA-256摘要',
    response_digest BINARY(32) NULL COMMENT '脱敏响应SHA-256摘要，未响应时为空',
    redaction_version INT NOT NULL COMMENT '日志脱敏规则版本',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    created_at DATETIME(3) NOT NULL COMMENT '调用审计入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_fulfillment_call_no (call_no),
    UNIQUE KEY uk_sc_fulfillment_call_attempt (operation_id, attempt_no),
    KEY idx_sc_fulfillment_call_b_order (b_order_id, started_at),
    KEY idx_sc_fulfillment_call_connector (connector_version_id, started_at),
    KEY idx_sc_fulfillment_call_result (result_class, started_at),
    CONSTRAINT ck_sc_fulfillment_call_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_sc_fulfillment_call_duration CHECK (duration_ms >= 0),
    CONSTRAINT ck_sc_fulfillment_call_direction CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT ck_sc_fulfillment_call_redaction CHECK (redaction_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商网络调用脱敏传输审计';

-- 表中文名：供应商账号余额快照
-- 核心职责：追加记录供应商账号级资金、授信或通用额度观察
CREATE TABLE sc_supplier_account_balance_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '供应商账号余额观察内部主键',
    connector_version_id BIGINT NOT NULL COMMENT '连接器版本主键，逻辑引用sc_supplier_connector_version.id',
    balance_type VARCHAR(32) NOT NULL COMMENT '余额类型：CASH资金，CREDIT授信，GENERAL_QUOTA通用额度',
    amount BIGINT NOT NULL COMMENT '观察到的账号余额或额度，单位为最小计量单位',
    currency CHAR(3) NULL COMMENT '资金或授信币种，一期固定CNY；无货币单位的通用额度为空',
    observed_status VARCHAR(32) NOT NULL COMMENT '观察状态：AVAILABLE可用，UNAVAILABLE不可用，UNKNOWN未知，ERROR查询异常',
    observed_at DATETIME(3) NOT NULL COMMENT '供应商账号余额观察时间，UTC',
    valid_until DATETIME(3) NOT NULL COMMENT '余额观察有效截止时间，UTC',
    supplier_request_no VARCHAR(128) NULL COMMENT '供应商余额查询请求号',
    raw_digest BINARY(32) NOT NULL COMMENT '脱敏原始余额响应SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '余额快照入库时间，UTC',
    PRIMARY KEY (id),
    KEY idx_sc_supplier_balance_observed (connector_version_id, balance_type, observed_at),
    KEY idx_sc_supplier_balance_validity (connector_version_id, balance_type, valid_until),
    CONSTRAINT ck_sc_supplier_balance_amount CHECK (amount >= 0),
    CONSTRAINT ck_sc_supplier_balance_currency CHECK ((balance_type IN ('CASH', 'CREDIT') AND currency = 'CNY') OR (balance_type = 'GENERAL_QUOTA' AND currency IS NULL)),
    CONSTRAINT ck_sc_supplier_balance_time CHECK (valid_until > observed_at),
    CONSTRAINT ck_sc_supplier_balance_type CHECK (balance_type IN ('CASH', 'CREDIT', 'GENERAL_QUOTA')),
    CONSTRAINT ck_sc_supplier_balance_status CHECK (observed_status IN ('AVAILABLE', 'UNAVAILABLE', 'UNKNOWN', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商Connector账号级资金授信或通用额度观察';
