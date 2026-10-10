-- 华昇原单恢复的技术事实；不修改历史迁移、操作类型或既有恢复索引。
-- 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#41-追加迁移
ALTER TABLE sc_fulfillment_operation
  ADD COLUMN checkpoint_key_version VARCHAR(64) NULL COMMENT '恢复检查点信封加密密钥版本',
  ADD COLUMN execution_generation BIGINT NOT NULL DEFAULT 0 COMMENT '履约操作认领与恢复围栏代次',
  ADD COLUMN execution_lease_until DATETIME(3) NULL COMMENT '当前履约操作认领到期时间，Asia/Shanghai',
  ADD COLUMN recovery_count INT NOT NULL DEFAULT 0 COMMENT '本操作已认领的原单查单恢复次数',
  ADD COLUMN recovery_deadline_at DATETIME(3) NULL COMMENT '自动恢复截止时间，Asia/Shanghai',
  ADD COLUMN business_send_possible_at DATETIME(3) NULL COMMENT '本操作首次可能发送业务请求的时间，Asia/Shanghai',
  ADD CONSTRAINT ck_sc_operation_huasheng_recovery
    CHECK (execution_generation >= 0 AND recovery_count >= 0);

-- 扩容 event_id 至 VARCHAR(64) 容纳 SHA-256 十六进制，并扩展摘要注释，既有 consumer 的算法与 BINARY(32) 存储契约保持不变。
ALTER TABLE sc_inbox_event
  MODIFY COLUMN event_id VARCHAR(64) NOT NULL
    COMMENT '被消费的Outbox事件或回调稳定业务编号，华昇使用SHA-256十六进制',
  MODIFY COLUMN payload_digest BINARY(32) NOT NULL
    COMMENT '收到的事件载荷SHA-256族摘要，华昇使用HMAC-SHA256';

-- 调整状态映射唯一键，纳入 business_phase 以支持华昇等适配器在不同协议阶段共用外部状态键。
ALTER TABLE sc_status_mapping_definition
  DROP INDEX uk_sc_status_mapping,
  ADD UNIQUE KEY uk_sc_status_mapping (connector_version_id, mapping_version, business_phase, external_status, external_sub_status);
