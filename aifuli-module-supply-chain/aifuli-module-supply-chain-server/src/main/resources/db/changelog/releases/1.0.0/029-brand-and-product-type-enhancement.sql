-- 品牌与商品类型管理能力原型对齐增强。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#27-品牌与商品类型原型对齐扩展029-changeset

-- 1. 品牌表 sc_brand 增加简称与介绍
ALTER TABLE sc_brand
    ADD COLUMN short_name VARCHAR(64) NULL COMMENT '品牌简称' AFTER brand_name,
    ADD COLUMN description VARCHAR(512) NULL COMMENT '品牌介绍与详细说明' AFTER logo_url;

-- 2. 商品类型表 sc_product_type 增加图标地址与类型描述
ALTER TABLE sc_product_type
    ADD COLUMN icon_url VARCHAR(512) NOT NULL DEFAULT '' COMMENT '商品类型图标地址' AFTER type_name,
    ADD COLUMN description VARCHAR(512) NULL COMMENT '商品类型详细描述' AFTER status;

-- 3. 数据迁移：将存量商品类型的 remark 复制到 description（仅当 remark 有值时）
UPDATE sc_product_type SET description = remark WHERE description IS NULL AND remark IS NOT NULL;
