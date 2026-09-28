-- 变更集：036-category-product-type-description.sql
-- 补齐商品分类编辑表单的商品类型与分类描述持久化字段。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#213-分类商品类型与描述036-changeset

ALTER TABLE sc_product_category
    ADD COLUMN product_type VARCHAR(32) NULL
        COMMENT '商品类型编码，由sc_product_type动态维护' AFTER category_name,
    ADD COLUMN description VARCHAR(200) NULL COMMENT '分类描述' AFTER product_type,
    ADD KEY idx_sc_category_product_type (product_type);
