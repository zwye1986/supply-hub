-- 商户额度配置真实操作追加审计；不回填存量历史、不设置生产额度、无物理外键。
-- 设计：docs/superpowers/specs/2026-10-19-merchant-quota-admin-design.md#2-配置与审计原子性
CREATE TABLE sc_supplier_merchant_quota_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商户额度审计主键',
    quota_id BIGINT NOT NULL COMMENT '商户额度主键，逻辑引用sc_supplier_merchant_quota.id',
    before_rate_limit_per_second INT NULL COMMENT '变更前每秒成功准入上限；首次创建为空',
    after_rate_limit_per_second INT NOT NULL COMMENT '变更后每秒成功准入上限',
    before_concurrency_limit INT NULL COMMENT '变更前同步调用并发占用上限；首次创建为空',
    after_concurrency_limit INT NOT NULL COMMENT '变更后同步调用并发占用上限',
    quota_version BIGINT NOT NULL COMMENT '变更后的乐观锁版本号',
    operator_id BIGINT NOT NULL COMMENT '操作人用户ID',
    reason VARCHAR(255) NOT NULL COMMENT '配置变更原因',
    occurred_at DATETIME(3) NOT NULL COMMENT '操作时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_merchant_quota_audit_version (quota_id, quota_version),
    CONSTRAINT ck_sc_merchant_quota_audit_limits CHECK (
        after_rate_limit_per_second > 0 AND after_concurrency_limit > 0 AND quota_version >= 0
        AND ((quota_version = 0 AND before_rate_limit_per_second IS NULL AND before_concurrency_limit IS NULL)
            OR (quota_version > 0 AND before_rate_limit_per_second IS NOT NULL AND before_rate_limit_per_second > 0
                AND before_concurrency_limit IS NOT NULL AND before_concurrency_limit > 0))),
    CONSTRAINT ck_sc_merchant_quota_audit_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商商户额度配置追加审计';
