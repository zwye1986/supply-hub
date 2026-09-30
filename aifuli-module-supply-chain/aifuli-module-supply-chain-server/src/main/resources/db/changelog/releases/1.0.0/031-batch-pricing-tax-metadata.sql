-- 分销商/项目批量定价继续复用既有不可变价格版本表，本变更仅补齐 SKU 税务展示元数据。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#29-分层批量定价与税务元数据隔离031-changeset
ALTER TABLE sc_platform_sku
    MODIFY COLUMN tax_rate DECIMAL(6,4) NULL COMMENT '税率，如0.0600表示6%；仅记录展示，不参与价格计算',
    ADD COLUMN tax_included TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '是否含税：1含税，0不含税；仅记录展示，不参与价格计算' AFTER tax_rate,
    ADD CONSTRAINT ck_sc_sku_tax_rate CHECK (tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 1));
