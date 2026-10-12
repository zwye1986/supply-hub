-- 平台SKU v2独立请求身份；不回填或重算存量0/2，不修改永久项目键。
-- 设计：docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#6-数据增量与存量安全。
ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD COLUMN requested_platform_sku_code VARCHAR(64) NULL COMMENT '格式3首次请求的平台SKU编码；格式0/2为空' AFTER buyer_sku_code,
    MODIFY COLUMN buyer_sku_code VARCHAR(64) NOT NULL COMMENT '采购商商品编码快照：格式0/2为首次请求编码，格式3为服务端解析的BPD编码',
    MODIFY COLUMN fingerprint_version SMALLINT NOT NULL DEFAULT 0 COMMENT '受理请求指纹格式版本：0历史未验证不可重放，2采购商编码无用户格式，3平台SKU编码无用户格式';
ALTER TABLE sc_fulfillment_acceptance_receipt
    DROP CHECK ck_sc_fulfillment_receipt_fingerprint_version;
ALTER TABLE sc_fulfillment_acceptance_receipt
    ADD CONSTRAINT ck_sc_fulfillment_receipt_fingerprint_version CHECK (fingerprint_version IN (0, 2, 3)),
    ADD CONSTRAINT ck_sc_fulfillment_receipt_platform_sku_request CHECK (
        (fingerprint_version IN (0, 2) AND requested_platform_sku_code IS NULL)
        OR (fingerprint_version = 3
            AND requested_platform_sku_code IS NOT NULL AND REGEXP_LIKE(requested_platform_sku_code, '[^[:space:]]')
            AND platform_sku_id IS NOT NULL AND platform_sku_id > 0
            AND target_schema_code IS NOT NULL AND REGEXP_LIKE(target_schema_code, '[^[:space:]]')
            AND target_schema_version IS NOT NULL AND target_schema_version > 0
            AND REGEXP_LIKE(buyer_sku_code, '[^[:space:]]')));
