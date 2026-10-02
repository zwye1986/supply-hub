-- 直充限频队列控制行；设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量
CREATE TABLE sc_recharge_frequency_queue (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '直充限频队列内部主键',
    mobile_digest BINARY(32) NOT NULL COMMENT '规范化充值手机号的专用 HMAC 摘要',
    mobile_masked VARCHAR(32) NOT NULL COMMENT '充值手机号脱敏展示值',
    platform_spu_id BIGINT NOT NULL COMMENT '入队时冻结的平台 SPU 内部主键',
    next_enqueue_sequence BIGINT NOT NULL DEFAULT 1 COMMENT '下一次入队可分配的队列序号',
    holder_entry_id BIGINT NULL COMMENT '当前持有占用的队列项内部主键',
    occupancy_generation BIGINT NOT NULL DEFAULT 0 COMMENT '每次取得新占用递增的占用代次',
    acquired_at DATETIME(3) NULL COMMENT '当前占用取得时间，Asia/Shanghai',
    minimum_hold_until DATETIME(3) NULL COMMENT '本次成功最短占用截止时间，Asia/Shanghai',
    next_check_at DATETIME(3) NULL COMMENT '下一次队列补偿检查时间，Asia/Shanghai',
    wake_generation BIGINT NOT NULL DEFAULT 0 COMMENT '每次安排新的唤醒意图递增的任务代次',
    blocked_reason VARCHAR(64) NULL COMMENT '队列阻塞原因',
    occupied_alert_active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '占用超时告警是否处于活跃状态',
    backlog_alert_active BOOLEAN NOT NULL DEFAULT FALSE COMMENT '积压超阈值告警是否处于活跃状态',
    last_occupied_alert_at DATETIME(3) NULL COMMENT '最近占用超时告警时间，Asia/Shanghai',
    last_backlog_alert_at DATETIME(3) NULL COMMENT '最近积压超阈值告警时间，Asia/Shanghai',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_recharge_frequency_queue_identity (mobile_digest, platform_spu_id),
    KEY idx_sc_recharge_frequency_queue_check (next_check_at, id),
    CONSTRAINT ck_sc_recharge_frequency_queue_positive CHECK (platform_spu_id > 0 AND next_enqueue_sequence > 0 AND (holder_entry_id IS NULL OR holder_entry_id > 0)),
    CONSTRAINT ck_sc_recharge_frequency_queue_generation CHECK (occupancy_generation >= 0 AND wake_generation >= 0 AND version >= 0),
    CONSTRAINT ck_sc_recharge_frequency_queue_holder CHECK ((holder_entry_id IS NULL AND acquired_at IS NULL AND minimum_hold_until IS NULL) OR (holder_entry_id IS NOT NULL AND acquired_at IS NOT NULL AND minimum_hold_until IS NOT NULL AND occupancy_generation > 0 AND minimum_hold_until >= acquired_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='直充限频队列控制行';

-- 直充限频队列项；设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量
CREATE TABLE sc_recharge_frequency_entry (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '直充限频队列项内部主键',
    queue_id BIGINT NOT NULL COMMENT '直充限频队列内部主键',
    order_id BIGINT NOT NULL COMMENT '供应链总订单内部主键',
    order_no VARCHAR(32) NOT NULL COMMENT '供应链总订单号',
    fulfillment_request_id BIGINT NOT NULL COMMENT '履约请求内部主键',
    enqueue_sequence BIGINT NOT NULL COMMENT '本订单首次入队分配的队列序号',
    status SMALLINT NOT NULL DEFAULT 0 COMMENT '队列项状态：0等待，10占用，20已结束',
    active_operation_no VARCHAR(32) NULL COMMENT '当前合法履约操作号',
    occupancy_generation BIGINT NOT NULL DEFAULT 0 COMMENT '本队列项取得占用的代次',
    dispatch_phase SMALLINT NULL COMMENT '外呼阶段：0已取得资格尚未发送，10外部可能已受理',
    buyer_id BIGINT NOT NULL COMMENT '分销商内部主键',
    project_id BIGINT NULL COMMENT '采购项目内部主键；存量订单可空',
    enqueued_at DATETIME(3) NOT NULL COMMENT '首次入队时间，Asia/Shanghai',
    completed_at DATETIME(3) NULL COMMENT '本订单排队流程结束时间，Asia/Shanghai',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '更新时间，Asia/Shanghai',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_recharge_frequency_entry_order (order_id),
    UNIQUE KEY uk_sc_recharge_frequency_entry_sequence (queue_id, enqueue_sequence),
    KEY idx_sc_recharge_frequency_entry_head (queue_id, status, enqueue_sequence),
    KEY idx_sc_recharge_frequency_entry_owner_time (buyer_id, project_id, enqueued_at, id),
    KEY idx_sc_recharge_frequency_entry_time (enqueued_at, queue_id, enqueue_sequence),
    CONSTRAINT ck_sc_recharge_frequency_entry_positive CHECK (queue_id > 0 AND order_id > 0 AND fulfillment_request_id > 0 AND enqueue_sequence > 0 AND buyer_id > 0 AND (project_id IS NULL OR project_id > 0)),
    CONSTRAINT ck_sc_recharge_frequency_entry_status CHECK (status IN (0, 10, 20)),
    CONSTRAINT ck_sc_recharge_frequency_entry_generation CHECK (occupancy_generation >= 0 AND version >= 0),
    CONSTRAINT ck_sc_recharge_frequency_entry_phase CHECK (dispatch_phase IS NULL OR dispatch_phase IN (0, 10)),
    CONSTRAINT ck_sc_recharge_frequency_entry_holding CHECK (status <> 10 OR (active_operation_no IS NOT NULL AND CHAR_LENGTH(TRIM(active_operation_no)) > 0 AND occupancy_generation > 0 AND dispatch_phase IS NOT NULL)),
    CONSTRAINT ck_sc_recharge_frequency_entry_waiting CHECK (status <> 0 OR dispatch_phase IS NULL),
    CONSTRAINT ck_sc_recharge_frequency_entry_completed CHECK ((status = 20 AND completed_at IS NOT NULL) OR (status <> 20 AND completed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='直充限频队列项';

-- 直充限频运营追加审计；设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量
CREATE TABLE sc_recharge_frequency_action_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '直充限频运营审计内部主键',
    action_no VARCHAR(64) NOT NULL COMMENT '管理操作幂等号',
    request_fingerprint BINARY(32) NOT NULL COMMENT '管理操作请求指纹',
    action_type VARCHAR(16) NOT NULL COMMENT '管理操作类型：RETRY触发检查，RELEASE释放占用',
    queue_id BIGINT NULL COMMENT '直充限频队列内部主键；资源不存在时可空',
    entry_id BIGINT NULL COMMENT '直充限频队列项内部主键；资源不存在时可空',
    order_no VARCHAR(32) NULL COMMENT '供应链总订单号',
    expected_generation BIGINT NULL COMMENT '请求预期的占用代次',
    expected_version BIGINT NULL COMMENT '请求预期的乐观锁版本',
    operator_user_id BIGINT NOT NULL COMMENT '真实内部操作用户ID',
    reason VARCHAR(512) NOT NULL COMMENT '管理操作原因',
    result_code VARCHAR(64) NOT NULL COMMENT '管理操作结果码',
    before_state_json JSON NULL COMMENT '管理操作前状态快照',
    after_state_json JSON NULL COMMENT '管理操作后状态快照',
    trace_id VARCHAR(64) NOT NULL COMMENT '请求链路追踪ID',
    created_at DATETIME(3) NOT NULL COMMENT '审计创建时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_recharge_frequency_action_no (action_no),
    KEY idx_sc_recharge_frequency_action_queue_time (queue_id, created_at, id),
    CONSTRAINT ck_sc_recharge_frequency_action_type CHECK (action_type IN ('RETRY', 'RELEASE')),
    CONSTRAINT ck_sc_recharge_frequency_action_positive CHECK ((queue_id IS NULL OR queue_id > 0) AND (entry_id IS NULL OR entry_id > 0) AND operator_user_id > 0),
    CONSTRAINT ck_sc_recharge_frequency_action_expected CHECK ((expected_generation IS NULL OR expected_generation >= 0) AND (expected_version IS NULL OR expected_version >= 0)),
    CONSTRAINT ck_sc_recharge_frequency_action_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='直充限频运营追加审计';

-- 046 已按上海本地时间存储，仅补正注释，不再次平移存量值；旧 changeset 与校验和保持不变。
-- 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量
ALTER TABLE sc_business_code_scope
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai';
ALTER TABLE sc_business_code_claim
    MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '占号时间，Asia/Shanghai';
