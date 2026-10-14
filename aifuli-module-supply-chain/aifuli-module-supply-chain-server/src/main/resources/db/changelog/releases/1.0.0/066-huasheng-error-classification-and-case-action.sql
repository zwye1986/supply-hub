-- 066：华昇错误分类与失败关单前向数据兼容
-- 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#7-持久化与协议兼容
-- 原则：只做前向扩展，不改已执行脚本语义，不回填存量行；旧行 NULL 表示未知事实。

-- 1. BOrder 供应商履约状态增加 60 已取消（可信供应商取消且零交付），CHECK 与 COMMENT 同步扩展。
ALTER TABLE sc_b_order
    DROP CHECK ck_sc_b_order_fulfillment;
ALTER TABLE sc_b_order
    ADD CONSTRAINT ck_sc_b_order_fulfillment CHECK (fulfillment_status IN (0, 10, 20, 40, 50, 60, 90));
ALTER TABLE sc_b_order
    MODIFY COLUMN fulfillment_status SMALLINT NOT NULL COMMENT '履约状态：0待执行，10待履约，20履约中，40履约成功，50确定失败，60已取消，90人工处理；一期不支持部分成功';

-- 2. BOrder 补充供应商终结原因码与脱敏说明（分事实保存：原始状态在 sc_raw_status_observation，价格差异在金额与结算状态列）。
ALTER TABLE sc_b_order
    ADD COLUMN terminal_reason_code VARCHAR(64) NULL COMMENT '供应商侧订单终结的稳定原因码，如HUASHENG_SUPPLIER_CANCELLED；未终结或历史单为NULL' AFTER settlement_status,
    ADD COLUMN terminal_reason_note VARCHAR(256) NULL COMMENT '供应商终结的脱敏说明，不含凭据、私钥或原始报文' AFTER terminal_reason_code;
ALTER TABLE sc_b_order
    ADD CONSTRAINT ck_sc_b_order_terminal_reason CHECK (terminal_reason_code IS NULL OR terminal_reason_code <> '');

-- 3. 供应商调用审计扩展可选 remoteCode、supplierTraceId、稳定错误原因与业务发送标识；旧行 NULL 表示未知，不回填假证据。
ALTER TABLE sc_fulfillment_call_log
    ADD COLUMN remote_code INT NULL COMMENT '本次调用最终业务错误码，0表示业务成功；未获得响应时为NULL表示未知' AFTER http_status,
    ADD COLUMN supplier_trace_id VARCHAR(128) NULL COMMENT '供应商返回的诊断编号，仅保存无控制字符且不超过128字符的合法编号' AFTER remote_code,
    ADD COLUMN stable_error_reason VARCHAR(64) NULL COMMENT '平台稳定错误原因码，如HUASHENG_NOT_CREATED；未分类时为NULL' AFTER supplier_trace_id,
    ADD COLUMN business_send_started TINYINT NULL COMMENT '本次调用是否已开始业务请求：1已开始，0未开始；NULL未知' AFTER stable_error_reason;
ALTER TABLE sc_fulfillment_call_log
    ADD CONSTRAINT ck_sc_fulfillment_call_send CHECK (business_send_started IN (0, 1) OR business_send_started IS NULL);

-- 逐次追加的受保护结果事实；独立于覆盖式checkpoint，旧行NULL不构成查询计数证据。
ALTER TABLE sc_fulfillment_call_log
    ADD COLUMN execution_generation BIGINT NULL COMMENT '结果事实所属执行代次；历史行NULL',
    ADD COLUMN evidence_schema_version INT NULL COMMENT '独立结果证据schema版本；历史行NULL',
    ADD COLUMN evidence_key_version VARCHAR(64) NULL COMMENT '独立结果证据信封密钥版本；历史行NULL',
    ADD COLUMN evidence_ciphertext MEDIUMBLOB NULL COMMENT '独立结果证据密文，AAD绑定调用、原单、版本与代次',
    ADD CONSTRAINT ck_sc_call_evidence CHECK (
        (evidence_schema_version IS NULL AND evidence_key_version IS NULL AND evidence_ciphertext IS NULL)
        OR (evidence_schema_version = 1 AND execution_generation IS NOT NULL
            AND evidence_key_version IS NOT NULL AND evidence_ciphertext IS NOT NULL));

-- 3.5 履约操作补充“无业务副作用证明”持久标记：证明必须覆盖本单全部相关历史尝试；
-- 旧行与尚无结果的行为 NULL（未知），缺失即无法证明安全拒绝，不默认无副作用。
ALTER TABLE sc_fulfillment_operation
    ADD COLUMN side_effect_free TINYINT NULL COMMENT '本操作无业务副作用证明：1全部历史发送均为明确建单前拒绝或从未发送业务请求，0可能存在业务副作用；NULL未知' AFTER business_send_possible_at;
ALTER TABLE sc_fulfillment_operation
    ADD CONSTRAINT ck_sc_fulfillment_operation_side_effect CHECK (side_effect_free IN (0, 1) OR side_effect_free IS NULL);

-- 4. 新增供应链自有履约异常 Case 命令审计表（人工核实/确认失败两步命令）。
-- 表中文名：履约异常Case命令审计
-- 核心职责：保存人工核实原单与失败关单命令的指纹、代次、执行状态与受保护核实证据
CREATE TABLE sc_fulfillment_case_action (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Case命令审计内部主键',
    action_no VARCHAR(32) NOT NULL COMMENT 'Case命令稳定审计编号，运营提交并用于幂等重放',
    case_no VARCHAR(32) NOT NULL COMMENT '关联履约异常处置单编号',
    case_id BIGINT NOT NULL COMMENT '关联履约异常处置单主键，逻辑引用sc_fulfillment_exception_case.id',
    b_order_id BIGINT NOT NULL COMMENT '核实的B端履约尝试主键',
    operation_no VARCHAR(32) NOT NULL COMMENT '核实的当前履约外部操作编号',
    command_type VARCHAR(32) NOT NULL COMMENT '命令类型：VERIFY_ORIGIN核实原单，CONFIRM_FAILURE确认失败关单',
    active_intent_flag TINYINT NULL COMMENT '活动意图标记：1受理或执行中，NULL已终结；同一Case同时只允许一个活动意图',
    request_fingerprint BINARY(32) NOT NULL COMMENT '命令指纹SHA-256，绑定真实操作人、命令类型、Case与原单身份及全部业务参数',
    actor_id BIGINT NOT NULL COMMENT '提交命令的真实管理端操作人用户ID，取自登录态不可由客户端传入',
    verify_action_no VARCHAR(32) NULL COMMENT '失败确认引用的核实命令编号，仅CONFIRM_FAILURE使用',
    expected_case_version BIGINT NOT NULL COMMENT '命令提交时操作人看到的Case乐观锁版本',
    expected_generation BIGINT NOT NULL COMMENT '命令提交时操作人看到的执行代次',
    actual_case_version BIGINT NULL COMMENT '命令实际执行时的Case乐观锁版本',
    actual_generation BIGINT NULL COMMENT '命令实际执行时的执行代次',
    execution_status VARCHAR(32) NOT NULL COMMENT '执行状态：RECEIVED已受理，STARTED已开始外呼，SUCCEEDED已成功，FAILED已失败，UNKNOWN结果未知',
    query_started_flag TINYINT NULL COMMENT '查询已开始标记：1已开始外呼；Timer重投或进程恢复见此标记后不得再次外呼',
    lease_until DATETIME(3) NULL COMMENT '命令执行租约截止时间；到期无完整结果则终结为UNKNOWN',
    evidence_schema_version INT NULL COMMENT '受保护核实证据的显式schema版本',
    evidence_key_version VARCHAR(64) NULL COMMENT '受保护核实证据的密钥版本',
    evidence_ciphertext BLOB NULL COMMENT '受保护核实证据密文，AAD绑定actionNo、caseNo、operationNo、连接器与供给版本、generation',
    verify_conclusion VARCHAR(64) NULL COMMENT '核实结论稳定原因码，如HUASHENG_NOT_CREATED、HUASHENG_SUPPLIER_CANCELLED',
    external_supplier_status VARCHAR(64) NULL COMMENT '核实观察到的供应商原始主状态，脱敏保真',
    valid_not_created_count INT NULL COMMENT '本命令累计的未建单有效查询次数',
    fresh_until DATETIME(3) NULL COMMENT '核实结果可用于失败确认的截止时间',
    completion_summary VARCHAR(1024) NULL COMMENT '命令完成的脱敏摘要',
    created_at DATETIME(3) NOT NULL COMMENT '命令受理时间',
    updated_at DATETIME(3) NOT NULL COMMENT '命令最后更新时间',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '命令审计CAS乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_case_action_no (action_no),
    UNIQUE KEY uk_sc_case_action_active (case_id, active_intent_flag),
    KEY idx_sc_case_action_case (case_id, created_at),
    KEY idx_sc_case_action_actor (actor_id, created_at),
    CONSTRAINT ck_sc_case_action_type CHECK (command_type IN ('VERIFY_ORIGIN', 'CONFIRM_FAILURE')),
    CONSTRAINT ck_sc_case_action_status CHECK (execution_status IN ('RECEIVED', 'STARTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_sc_case_action_verify_ref CHECK ((command_type = 'VERIFY_ORIGIN' AND verify_action_no IS NULL) OR (command_type = 'CONFIRM_FAILURE' AND verify_action_no IS NOT NULL AND verify_action_no <> action_no)),
    CONSTRAINT ck_sc_case_action_active CHECK ((active_intent_flag IS NULL AND execution_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')) OR (active_intent_flag IS NOT NULL AND active_intent_flag = 1 AND execution_status IN ('RECEIVED', 'STARTED'))),
    CONSTRAINT ck_sc_case_action_fingerprint CHECK (LENGTH(request_fingerprint) = 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='履约异常Case人工核实与失败关单的命令指纹、代次与证据审计';
