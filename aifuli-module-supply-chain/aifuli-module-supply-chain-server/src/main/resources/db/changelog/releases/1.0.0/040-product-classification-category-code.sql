-- 商品分类供货命中码允许类目树编码，去掉三枚举 CHECK。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d34）。
-- 禁止回改 024。

ALTER TABLE sc_platform_spu
    DROP CHECK ck_sc_spu_product_classification;

ALTER TABLE sc_supplier_supply_scope
    DROP CHECK ck_sc_supplier_scope_product_classification;

ALTER TABLE sc_platform_spu
    MODIFY COLUMN product_classification VARCHAR(32) NULL
        COMMENT '商品分类供货命中码：ProductClassification枚举名或sc_product_category.category_code';

ALTER TABLE sc_supplier_supply_scope
    MODIFY COLUMN product_classification VARCHAR(32) NOT NULL
        COMMENT '商品分类供货命中码：ProductClassification枚举名或sc_product_category.category_code';
