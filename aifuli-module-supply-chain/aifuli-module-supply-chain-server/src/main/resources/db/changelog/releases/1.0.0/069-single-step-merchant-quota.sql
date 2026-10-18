-- 商户统一额度、同步调用占用与单步容量等待；不播种任何生产额度，无物理外键。
-- 设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#5-数据结构与归属
CREATE TABLE sc_supplier_merchant_quota (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商户额度主键',
    platform_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '供应商平台码',
    merchant_account VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '非秘密供应商商户账号，原值精确匹配',
    rate_limit_per_second INT NOT NULL COMMENT '每秒成功准入上限',
    concurrency_limit INT NOT NULL COMMENT '同步调用并发占用上限',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT NOT NULL COMMENT '创建人',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_by BIGINT NOT NULL COMMENT '最后修改人',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    change_reason VARCHAR(255) NOT NULL COMMENT '配置变更原因',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_merchant_quota_identity (platform_code, merchant_account),
    CONSTRAINT ck_sc_merchant_quota_limits CHECK (rate_limit_per_second > 0 AND concurrency_limit > 0 AND version >= 0),
    CONSTRAINT ck_sc_merchant_quota_platform CHECK (platform_code IN ('RENYUE','QNM','SZ_CARD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商商户当前统一额度';

CREATE TABLE sc_supplier_merchant_call_lease (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '调用额度租约主键',
    quota_id BIGINT NOT NULL COMMENT '商户额度主键，逻辑引用sc_supplier_merchant_quota.id',
    operation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原履约操作号',
    execution_generation BIGINT NOT NULL COMMENT '执行围栏代次',
    admitted_at DATETIME(3) NOT NULL COMMENT '成功准入时间，Asia/Shanghai',
    lease_until DATETIME(3) NOT NULL COMMENT '调用占用截止时间，Asia/Shanghai',
    released_at DATETIME(3) NULL COMMENT '并发占用释放时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_merchant_call_operation (operation_no, execution_generation),
    KEY idx_sc_merchant_call_rate (quota_id, admitted_at),
    KEY idx_sc_merchant_call_active (quota_id, lease_until),
    CONSTRAINT ck_sc_merchant_call_generation CHECK (execution_generation > 0),
    CONSTRAINT ck_sc_merchant_call_time CHECK (lease_until > admitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商户同步调用准入与并发租约';

CREATE TABLE sc_single_step_capacity_wait (
    operation_id BIGINT NOT NULL COMMENT '原履约操作主键，逻辑引用sc_fulfillment_operation.id',
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '等待动作CREATE或QUERY',
    expected_generation BIGINT NOT NULL COMMENT '等待对应的原单围栏代次',
    wake_sequence BIGINT NOT NULL COMMENT '永久递增的容量唤醒序次',
    next_wake_at DATETIME(3) NOT NULL COMMENT '最早重新竞争容量时间，Asia/Shanghai',
    active_flag BIT(1) NOT NULL COMMENT '是否仍为当前有效等待',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，Asia/Shanghai',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，Asia/Shanghai',
    PRIMARY KEY (operation_id),
    KEY idx_sc_capacity_wait_due (active_flag, next_wake_at),
    CONSTRAINT ck_sc_capacity_wait_action CHECK (action IN ('CREATE','QUERY')),
    CONSTRAINT ck_sc_capacity_wait_generation CHECK (expected_generation >= 0 AND wake_sequence > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单步原单持久容量等待';
