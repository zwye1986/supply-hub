-- 页面选品仅建立DRAFT身份，存量手工目录不回填、不改编码或商业价。
-- 设计见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#4-数据库增量与来源所有权。
ALTER TABLE sc_buyer_listing
    ADD COLUMN management_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '受理目录管理来源：MANUAL手工管理，AUTO_SELECTION页面选品自动管理',
    ADD COLUMN buyer_product_id BIGINT NULL COMMENT '自动受理目录关联的分销商品主键，逻辑引用sc_buyer_product.id；手工目录为空',
    ADD UNIQUE KEY uk_sc_listing_buyer_product (buyer_product_id),
    ADD CONSTRAINT ck_sc_listing_management CHECK (management_mode IN ('MANUAL', 'AUTO_SELECTION')),
    ADD CONSTRAINT ck_sc_listing_product_owner CHECK (
        (management_mode = 'MANUAL' AND buyer_product_id IS NULL) OR
        (management_mode = 'AUTO_SELECTION' AND buyer_product_id IS NOT NULL)),
    ADD KEY idx_sc_listing_management_buyer_sku (management_mode, buyer_id, platform_sku_id);

ALTER TABLE sc_buyer_listing_version
    ADD COLUMN source_buyer_pricing_version_id BIGINT NULL COMMENT '自动商业快照来源的分销通用定价版本主键，逻辑引用sc_buyer_sku_pricing_version.id；手工版本为空',
    ADD COLUMN commercial_snapshot_digest BINARY(32) NULL COMMENT '自动商业快照来源及展示商业字段的SHA256摘要；手工版本为空',
    ADD UNIQUE KEY uk_sc_listing_commercial_source (listing_id, source_buyer_pricing_version_id, commercial_snapshot_digest);

ALTER TABLE sc_buyer_listing_availability
    ADD COLUMN basis_digest BINARY(32) NULL COMMENT '供给评估来源身份版本及路由资格的SHA256摘要',
    ADD COLUMN basis_json JSON NULL COMMENT '供给评估来源引用及资格摘要，不含凭据、履约目标或完整供应商报文',
    ADD COLUMN valid_until DATETIME(3) NULL COMMENT '供给可用性投影下次必须重建的时间，Asia/Shanghai；历史未评估为空',
    ADD KEY idx_sc_listing_availability_valid_until (valid_until, listing_id);
