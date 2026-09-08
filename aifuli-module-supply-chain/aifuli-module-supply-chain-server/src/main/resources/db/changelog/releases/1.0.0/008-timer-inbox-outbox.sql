-- 表中文名：持久化定时任务
-- 核心职责：维护多节点可抢占、可续租、可恢复的可靠业务定时任务
CREATE TABLE sc_timer_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '持久化定时任务内部主键',
    task_no VARCHAR(32) NOT NULL COMMENT '持久化定时任务稳定业务编号',
    task_type VARCHAR(64) NOT NULL COMMENT '受控的定时任务处理器类型',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '同一任务类型内的业务幂等键',
    payload_version INT NOT NULL COMMENT '任务载荷Schema版本',
    payload_json JSON NOT NULL COMMENT '任务处理器版本化业务载荷',
    schedule_type VARCHAR(16) NOT NULL COMMENT '调度类型：ONCE一次，FIXED_DELAY固定延迟',
    next_fire_at DATETIME(3) NOT NULL COMMENT '下一次计划触发时间，UTC',
    priority INT NOT NULL DEFAULT 0 COMMENT '任务优先级，数值越大越优先',
    shard INT NOT NULL DEFAULT 0 COMMENT '稳定任务分片编号',
    status SMALLINT NOT NULL COMMENT '任务状态：0待调度，10已认领，20执行中，30等待重试，40已完成，50已取消，60死信，90人工处理',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已经开始的执行次数',
    max_attempts INT NOT NULL COMMENT '转死信前允许的最大执行次数',
    lease_owner VARCHAR(128) NULL COMMENT '当前任务租约持有节点标识',
    lease_until DATETIME(3) NULL COMMENT '当前任务租约到期时间，UTC',
    fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '每次成功认领递增的防旧执行者令牌',
    last_error_code VARCHAR(64) NULL COMMENT '最近一次执行失败原因码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '任务状态乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '任务创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '任务最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_timer_task_no (task_no),
    UNIQUE KEY uk_sc_timer_task_idempotency (task_type, idempotency_key),
    KEY idx_sc_timer_task_scan (status, next_fire_at, lease_until, id),
    KEY idx_sc_timer_task_owner (lease_owner, lease_until),
    CONSTRAINT ck_sc_timer_task_payload CHECK (payload_version >= 1),
    CONSTRAINT ck_sc_timer_task_schedule CHECK (schedule_type IN ('ONCE', 'FIXED_DELAY')),
    CONSTRAINT ck_sc_timer_task_attempt CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_sc_timer_task_shard CHECK (shard >= 0),
    CONSTRAINT ck_sc_timer_task_status CHECK (status IN (0, 10, 20, 30, 40, 50, 60, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='多节点可恢复持久化定时任务';

-- 表中文名：定时任务执行记录
-- 核心职责：追加记录持久化定时任务每次带防旧令牌的执行结果
CREATE TABLE sc_timer_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '定时任务执行审计内部主键',
    execution_no VARCHAR(32) NOT NULL COMMENT '定时任务执行稳定业务编号',
    task_id BIGINT NOT NULL COMMENT '持久化定时任务内部主键',
    attempt_no INT NOT NULL COMMENT '任务执行尝试序号，从1递增',
    fencing_token BIGINT NOT NULL COMMENT '本次执行持有的防旧执行者令牌',
    worker_id VARCHAR(128) NOT NULL COMMENT '实际执行任务的节点和线程池工作者标识',
    status SMALLINT NOT NULL COMMENT '执行结果：10执行中，20成功，30失败可重试，40死信，90人工处理',
    started_at DATETIME(3) NOT NULL COMMENT '任务执行开始时间，UTC',
    finished_at DATETIME(3) NULL COMMENT '任务执行结束时间，UTC',
    duration_ms BIGINT NULL COMMENT '任务执行耗时，毫秒',
    error_code VARCHAR(64) NULL COMMENT '任务执行失败原因码',
    error_digest BINARY(32) NULL COMMENT '脱敏异常信息SHA-256摘要',
    result_digest BINARY(32) NULL COMMENT '脱敏执行结果SHA-256摘要',
    created_at DATETIME(3) NOT NULL COMMENT '执行审计入库时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_timer_execution_no (execution_no),
    UNIQUE KEY uk_sc_timer_execution_attempt (task_id, attempt_no),
    KEY idx_sc_timer_execution_started (status, started_at),
    CONSTRAINT ck_sc_timer_execution_attempt CHECK (attempt_no >= 1 AND fencing_token >= 1),
    CONSTRAINT ck_sc_timer_execution_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_sc_timer_execution_status CHECK (status IN (10, 20, 30, 40, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持久化定时任务每次执行的追加式审计';

-- 表中文名：Outbox事件
-- 核心职责：维护至少一次投递、按聚合有序和可恢复的领域事件
CREATE TABLE sc_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Outbox事件内部主键',
    event_id VARCHAR(32) NOT NULL COMMENT 'Outbox事件稳定业务编号',
    aggregate_type VARCHAR(64) NOT NULL COMMENT '产生事件的聚合类型',
    aggregate_id VARCHAR(64) NOT NULL COMMENT '产生事件的聚合业务编号',
    aggregate_version BIGINT NOT NULL COMMENT '产生事件时的聚合版本号',
    event_type VARCHAR(128) NOT NULL COMMENT '版本稳定的领域事件类型',
    target_type VARCHAR(32) NOT NULL COMMENT '投递目标类型：LOCAL内部消费者，HTTP外部HTTP端点',
    target_name VARCHAR(128) NOT NULL COMMENT '内部消费者名或外部目标编码',
    payload_version INT NOT NULL COMMENT '事件载荷Schema版本',
    payload_json JSON NOT NULL COMMENT '版本化事件载荷',
    available_at DATETIME(3) NOT NULL COMMENT '事件最早可投递时间，UTC',
    status SMALLINT NOT NULL COMMENT '事件状态：0待投递，10已认领，20投递中，30等待重试，40已投递，50死信，90人工处理',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已经发起投递次数',
    max_attempts INT NOT NULL COMMENT '转死信前最大投递次数',
    lease_owner VARCHAR(128) NULL COMMENT '当前事件租约持有节点标识',
    lease_until DATETIME(3) NULL COMMENT '当前事件租约到期时间，UTC',
    fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '每次成功认领递增的防旧执行者令牌',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    correlation_id VARCHAR(64) NULL COMMENT '跨事件业务关联标识',
    causation_id VARCHAR(64) NULL COMMENT '触发当前事件的命令或事件标识',
    last_error_code VARCHAR(64) NULL COMMENT '最近一次投递失败原因码',
    created_at DATETIME(3) NOT NULL COMMENT '事件创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '事件状态最后更新时间，UTC',
    published_at DATETIME(3) NULL COMMENT '事件成功投递时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_outbox_event_id (event_id),
    UNIQUE KEY uk_sc_outbox_aggregate_event (aggregate_type, aggregate_id, aggregate_version, event_type),
    KEY idx_sc_outbox_scan (status, available_at, lease_until, id),
    KEY idx_sc_outbox_aggregate_order (aggregate_type, aggregate_id, aggregate_version),
    CONSTRAINT ck_sc_outbox_version CHECK (aggregate_version >= 0 AND payload_version >= 1),
    CONSTRAINT ck_sc_outbox_attempt CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_sc_outbox_target CHECK (target_type IN ('LOCAL', 'HTTP')),
    CONSTRAINT ck_sc_outbox_status CHECK (status IN (0, 10, 20, 30, 40, 50, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='至少一次可靠投递的Outbox事件';

-- 表中文名：Inbox消费记录
-- 核心职责：维护内部消费者的事件去重和幂等处理结果
CREATE TABLE sc_inbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Inbox消费记录内部主键',
    consumer_name VARCHAR(128) NOT NULL COMMENT '内部幂等消费者名称',
    event_id VARCHAR(32) NOT NULL COMMENT '被消费的Outbox事件稳定业务编号',
    payload_digest BINARY(32) NOT NULL COMMENT '收到的事件载荷SHA-256摘要',
    status SMALLINT NOT NULL COMMENT '消费状态：10处理中，20成功，30失败可重试，90人工处理',
    trace_id VARCHAR(64) NOT NULL COMMENT '链路追踪标识',
    received_at DATETIME(3) NOT NULL COMMENT '消费者首次收到事件时间，UTC',
    processed_at DATETIME(3) NULL COMMENT '消费者成功完成业务事务时间，UTC',
    last_error_code VARCHAR(64) NULL COMMENT '最近一次消费失败原因码',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '消费状态乐观锁版本号',
    updated_at DATETIME(3) NOT NULL COMMENT '消费记录最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_inbox_consumer_event (consumer_name, event_id),
    KEY idx_sc_inbox_status_time (status, updated_at),
    CONSTRAINT ck_sc_inbox_status CHECK (status IN (10, 20, 30, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='内部事件消费者幂等Inbox记录';
