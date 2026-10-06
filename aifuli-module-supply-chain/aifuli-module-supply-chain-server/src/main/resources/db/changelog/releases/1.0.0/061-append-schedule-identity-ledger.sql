-- 表中文名：Outbox追加身份账本
-- 核心职责：维护Outbox事件追加的独立不可变身份账本，消除重复追加等待源行与共享锁升级死锁
-- 设计依据：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#72-独立不可变身份账本确定消除重复追加等待源行
-- 与 #721-冻结排他建槽sql与首次所有权
CREATE TABLE sc_outbox_append_identity (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Outbox追加身份账本内部主键',
    aggregate_type VARCHAR(64) NOT NULL COMMENT '产生事件的聚合类型',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '产生事件的聚合业务编号',
    aggregate_version BIGINT NOT NULL COMMENT '产生事件时的聚合版本号',
    event_type VARCHAR(128) NOT NULL COMMENT '版本稳定的领域事件类型',
    binding_status SMALLINT NOT NULL DEFAULT 10 COMMENT '绑定状态：10 RESERVED保留，20 BOUND已绑定',
    reservation_nonce BINARY(16) NOT NULL COMMENT '首次建槽尝试不可变保留标识nonce',
    target_type VARCHAR(32) NOT NULL COMMENT '投递目标类型：LOCAL内部消费者，HTTP外部HTTP端点',
    target_name VARCHAR(128) NOT NULL COMMENT '内部消费者名或外部目标编码',
    payload_version INT NOT NULL COMMENT '事件载荷Schema版本',
    comparison_protocol VARCHAR(32) NOT NULL COMMENT '版本化业务载荷比较协议',
    payload_json JSON NOT NULL COMMENT '版本化业务载荷',
    event_id VARCHAR(32) NULL COMMENT '绑定的Outbox事件稳定业务编号（RESERVED时NULL，BOUND时非空）',
    created_at DATETIME(3) NOT NULL COMMENT '账本创建时间，Asia/Shanghai',
    bound_at DATETIME(3) NULL COMMENT '账本绑定时间，Asia/Shanghai（RESERVED时NULL，BOUND时非空）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_outbox_identity (aggregate_type, aggregate_id, aggregate_version, event_type),
    CONSTRAINT ck_sc_outbox_identity_status CHECK (binding_status IN (10, 20)),
    CONSTRAINT ck_sc_outbox_identity_binding CHECK (
        (binding_status = 10 AND event_id IS NULL AND bound_at IS NULL) OR
        (binding_status = 20 AND event_id IS NOT NULL AND bound_at IS NOT NULL)
    ),
    CONSTRAINT ck_sc_outbox_identity_version CHECK (aggregate_version >= 0 AND payload_version >= 1),
    CONSTRAINT ck_sc_outbox_identity_target CHECK (target_type IN ('LOCAL', 'HTTP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Outbox追加身份独立不可变账本';

-- 表中文名：Timer调度身份账本
-- 核心职责：维护Timer定时任务调度的独立不可变身份账本，消除重复调度等待源行与共享锁升级死锁
CREATE TABLE sc_timer_schedule_identity (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Timer调度身份账本内部主键',
    task_type VARCHAR(64) NOT NULL COMMENT '受控的定时任务处理器类型',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '同一任务类型内的业务幂等键',
    binding_status SMALLINT NOT NULL DEFAULT 10 COMMENT '绑定状态：10 RESERVED保留，20 BOUND已绑定',
    reservation_nonce BINARY(16) NOT NULL COMMENT '首次建槽尝试不可变保留标识nonce',
    payload_version INT NOT NULL COMMENT '任务载荷Schema版本',
    comparison_protocol VARCHAR(32) NOT NULL COMMENT '版本化业务载荷比较协议',
    payload_json JSON NOT NULL COMMENT '任务处理器版本化业务载荷',
    task_no VARCHAR(32) NULL COMMENT '绑定的定时任务稳定业务编号（RESERVED时NULL，BOUND时非空）',
    task_id BIGINT NULL COMMENT '绑定的定时任务内部主键（RESERVED时NULL，BOUND时非空）',
    created_at DATETIME(3) NOT NULL COMMENT '账本创建时间，Asia/Shanghai',
    bound_at DATETIME(3) NULL COMMENT '账本绑定时间，Asia/Shanghai（RESERVED时NULL，BOUND时非空）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_timer_identity (task_type, idempotency_key),
    CONSTRAINT ck_sc_timer_identity_status CHECK (binding_status IN (10, 20)),
    CONSTRAINT ck_sc_timer_identity_binding CHECK (
        (binding_status = 10 AND task_no IS NULL AND task_id IS NULL AND bound_at IS NULL) OR
        (binding_status = 20 AND task_no IS NOT NULL AND task_id IS NOT NULL AND bound_at IS NOT NULL)
    ),
    CONSTRAINT ck_sc_timer_identity_payload CHECK (payload_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Timer调度身份独立不可变账本';
