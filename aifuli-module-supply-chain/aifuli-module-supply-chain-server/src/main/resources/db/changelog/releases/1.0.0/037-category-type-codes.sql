-- 变更集：037-category-type-codes.sql
-- 补齐商品分类支持多商品类型编码列表（JSON数组），对齐前端与产品原型多选规范。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#214-分类多商品类型与原型别名037-changeset

ALTER TABLE sc_product_category
    ADD COLUMN type_codes JSON NULL
        COMMENT '商品类型编码列表(JSON数组)，由sc_product_type动态维护' AFTER product_type;

UPDATE sc_product_category
    SET type_codes = JSON_ARRAY(product_type)
    WHERE product_type IS NOT NULL AND type_codes IS NULL;
