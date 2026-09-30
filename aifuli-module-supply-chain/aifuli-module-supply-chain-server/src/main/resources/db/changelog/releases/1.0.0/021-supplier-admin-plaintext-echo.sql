-- 供应商管理端详情回显：法人身份证号明文列。
-- 设计见 docs/superpowers/specs/2026-08-28-supply-chain-prd-alignment-design.md#1511-管理端详情回显明文暂时关闭脱敏

ALTER TABLE sc_supplier
    ADD COLUMN legal_person_id_no VARCHAR(32) NULL COMMENT '法人身份证号' AFTER legal_person_name;

UPDATE sc_supplier
SET onboarded_at = created_at
WHERE onboarded_at IS NOT NULL
  AND onboarded_at < '1971-01-01 00:00:00';
