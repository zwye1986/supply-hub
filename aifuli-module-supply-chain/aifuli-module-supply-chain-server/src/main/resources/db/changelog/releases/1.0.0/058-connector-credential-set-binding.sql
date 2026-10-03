-- 显式凭据模式：旧槽引用不迁移，集引用与项身份快照独立冻结。
-- 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#123-第二切片显式凭据集绑定与运行时配置
ALTER TABLE sc_supplier_connector_version
    MODIFY COLUMN credential_slot_id BIGINT NULL COMMENT '凭证槽主键，逻辑引用sc_credential_slot.id',
    ADD COLUMN credential_set_id BIGINT NULL COMMENT '凭据集主键，逻辑引用sc_credential_set.id' AFTER credential_slot_id,
    ADD COLUMN credential_item_snapshot JSON NULL COMMENT '连接器冻结的凭据项身份与版本快照，不含秘密' AFTER credential_set_id,
    ADD KEY idx_sc_connector_version_credential_set (credential_set_id),
    ADD CONSTRAINT chk_sc_connector_credential_binding CHECK ((credential_slot_id IS NULL) <> (credential_set_id IS NULL));
ALTER TABLE sc_credential_item
    MODIFY COLUMN secret_ref TEXT NOT NULL COMMENT '密钥系统中的秘密引用或信封密文，不是秘密明文；ENV存env:NAME';
