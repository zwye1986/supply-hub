-- SKU 展示资料：对齐商品编辑原型中的主图、详情图和商品描述。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#212-sku-展示资料034-changeset
ALTER TABLE sc_platform_sku
    ADD COLUMN main_image_url VARCHAR(512) NULL COMMENT 'SKU主图地址' AFTER sku_name,
    ADD COLUMN detail_image_urls JSON NULL COMMENT 'SKU详情图地址列表(JSON数组)' AFTER main_image_url,
    ADD COLUMN description VARCHAR(200) NULL COMMENT 'SKU商品描述' AFTER detail_image_urls;
