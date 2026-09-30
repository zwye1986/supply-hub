-- 变更集：030-product-management-grid-enhancement.sql
-- 业务目的：支持管理端商品列表聚合展示、下架方式记录、SKU税率与商品类型图标及索引优化。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#28-商品管理列表端模型增强与类型优化030-changeset

-- 1. SPU 表增加下架方式与类型状态复合索引
ALTER TABLE sc_platform_spu
    ADD COLUMN unshelve_mode VARCHAR(32) NULL COMMENT '下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架' AFTER status,
    ADD KEY idx_sc_platform_spu_type_status (product_type, status);

-- 2. SKU 表增加税率配置
ALTER TABLE sc_platform_sku
    ADD COLUMN tax_rate DECIMAL(6,4) NULL COMMENT '税率，如0.0600表示6%，NULL表示不征税/未配置' AFTER suggested_sale_price;

-- 3. 存量商品类型回填默认图标，避免空串
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/card.svg' WHERE type_code = 'PT_CARD' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/recharge.svg' WHERE type_code = 'PT_RECHARGE' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/movie_fast.svg' WHERE type_code = 'PT_MOVIE_FAST' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/movie_slow.svg' WHERE type_code = 'PT_MOVIE_SLOW' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/dining.svg' WHERE type_code = 'PT_DINING' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/jd.svg' WHERE type_code = 'PT_JD' AND (icon_url IS NULL OR icon_url = '');
UPDATE sc_product_type SET icon_url = 'https://img.qihaofu.cn/supplyhub/icons/default.svg' WHERE icon_url IS NULL OR icon_url = '';
