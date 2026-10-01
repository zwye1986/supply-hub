-- 存量迁移冻结时间与无任意holder的多风险阻塞；不改变048及已执行变更集。
-- 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界
ALTER TABLE sc_recharge_frequency_entry
    ADD COLUMN migration_acquired_at DATETIME(3) NULL COMMENT '迁移恢复的原占用取得时间，Asia/Shanghai；非迁移项为空',
    ADD COLUMN migration_time_basis VARCHAR(32) NULL COMMENT '迁移占用时间依据；非迁移项为空',
    ADD CONSTRAINT ck_sc_recharge_frequency_entry_migration CHECK
        ((migration_acquired_at IS NULL AND migration_time_basis IS NULL)
         OR (migration_acquired_at IS NOT NULL AND migration_time_basis IS NOT NULL AND migration_time_basis IN ('VERIFIED_SEND', 'MIGRATION_CLOCK')));
ALTER TABLE sc_recharge_frequency_queue DROP CHECK ck_sc_recharge_frequency_queue_holder;
ALTER TABLE sc_recharge_frequency_queue ADD CONSTRAINT ck_sc_recharge_frequency_queue_holder CHECK
    ((holder_entry_id IS NULL AND acquired_at IS NULL AND minimum_hold_until IS NULL)
     OR (holder_entry_id IS NOT NULL AND acquired_at IS NOT NULL AND minimum_hold_until IS NOT NULL
         AND occupancy_generation > 0 AND minimum_hold_until >= acquired_at)
     OR (holder_entry_id IS NULL AND blocked_reason IS NOT NULL AND blocked_reason = 'MIGRATION_CONFLICT' AND acquired_at IS NOT NULL
         AND occupancy_generation > 0 AND (minimum_hold_until IS NULL OR minimum_hold_until >= acquired_at)));

CREATE TABLE sc_recharge_frequency_migration_gate (
    id BIGINT NOT NULL COMMENT '迁移发布门禁固定主键',
    state VARCHAR(16) NOT NULL COMMENT '迁移覆盖状态：CLOSED未核验，READY已核验',
    verified_artifact VARCHAR(80) NULL COMMENT '完成迁移覆盖核验的不可变部署版本',
    identity_key_fingerprint BINARY(32) NULL COMMENT '迁移覆盖核验的专用手机号摘要密钥指纹；不含密钥原文',
    verified_at DATETIME(3) NULL COMMENT '完成迁移覆盖核验时间，Asia/Shanghai',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    CONSTRAINT ck_sc_recharge_frequency_migration_gate_id CHECK (id=1 AND version>=0),
    CONSTRAINT ck_sc_recharge_frequency_migration_gate_state CHECK
        (state='CLOSED' OR (state='READY' AND verified_artifact IS NOT NULL AND identity_key_fingerprint IS NOT NULL AND verified_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='直充限频存量覆盖发布门禁';
INSERT INTO sc_recharge_frequency_migration_gate(id,state,version) VALUES(1,'CLOSED',0);
