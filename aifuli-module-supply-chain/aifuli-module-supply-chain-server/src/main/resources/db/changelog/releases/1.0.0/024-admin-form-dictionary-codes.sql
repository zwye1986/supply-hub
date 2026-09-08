-- 供应链表单字典化：供货配置 / 分销商范围改三级独立枚举码，平台SPU打同一套标签。
-- 设计见 docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#52-供应链
-- 决策 d10：测试库现有范围行无法可靠映射到新枚举码，先清空再改列；不回改 001-023。

-- 1. 供应商供货配置：类目树三元组 -> 三级独立枚举码。
DELETE FROM sc_supplier_supply_scope_mode
WHERE supply_scope_id IN (SELECT id FROM sc_supplier_supply_scope);
DELETE FROM sc_supplier_supply_scope;

ALTER TABLE sc_supplier_supply_scope
    DROP KEY uk_sc_supplier_scope_triple;

ALTER TABLE sc_supplier_supply_scope
    DROP COLUMN category_l1_id,
    DROP COLUMN category_l2_id,
    DROP COLUMN category_l3_id;

ALTER TABLE sc_supplier_supply_scope
    ADD COLUMN sellable_category VARCHAR(32) NOT NULL COMMENT '可售品类封闭枚举，见SellableCategory' AFTER supplier_id,
    ADD COLUMN product_type VARCHAR(32) NOT NULL COMMENT '商品类型封闭枚举，见ProductTypeTag' AFTER sellable_category,
    ADD COLUMN product_classification VARCHAR(32) NOT NULL COMMENT '商品分类封闭枚举，见ProductClassification' AFTER product_type;

ALTER TABLE sc_supplier_supply_scope
    ADD UNIQUE KEY uk_sc_supplier_scope_triple (supplier_id, sellable_category, product_type, product_classification),
    ADD CONSTRAINT ck_sc_supplier_scope_sellable_category CHECK (sellable_category IN (
        'EQUITY', 'PHYSICAL', 'MOVIE', 'JD', 'DINING', 'TAKEOUT', 'SUPERMARKET', 'HEMA', 'RTMART', 'LIFE')),
    ADD CONSTRAINT ck_sc_supplier_scope_product_type CHECK (product_type IN (
        'EQUITY_PRODUCT', 'MOVIE', 'DINING', 'JD', 'VIP')),
    ADD CONSTRAINT ck_sc_supplier_scope_product_classification CHECK (product_classification IN (
        'ELECTRONICS', 'VIRTUAL_CARD', 'MOVIE_TICKET'));

-- 2. 分销商可售范围：类目主键 -> scope_kind + scope_code。
DELETE FROM sc_buyer_category_scope;

ALTER TABLE sc_buyer_category_scope
    DROP KEY uk_sc_buyer_category_scope;

ALTER TABLE sc_buyer_category_scope
    DROP COLUMN category_id,
    DROP COLUMN category_level;

ALTER TABLE sc_buyer_category_scope
    ADD COLUMN scope_kind VARCHAR(16) NOT NULL COMMENT 'SELLABLE可售品类 PRODUCT_TYPE商品类型' AFTER buyer_id,
    ADD COLUMN scope_code VARCHAR(32) NOT NULL COMMENT '对应枚举名，见SellableCategory/ProductTypeTag' AFTER scope_kind;

ALTER TABLE sc_buyer_category_scope
    ADD UNIQUE KEY uk_sc_buyer_category_scope (buyer_id, scope_kind, scope_code),
    ADD CONSTRAINT ck_sc_buyer_category_scope_kind CHECK (scope_kind IN ('SELLABLE', 'PRODUCT_TYPE'));

-- 3. 平台SPU：新增三码列（先可空，应用层强制必填校验）；primary_category_id 改为允许空，
--    不再是供货命中或加购筛选的依据，仅保留给主数据/旧类目工具查阅。
ALTER TABLE sc_platform_spu
    MODIFY COLUMN primary_category_id BIGINT NULL
        COMMENT '平台主类目主键，逻辑引用sc_product_category.id（历史字段，主数据保留，不用于供货命中或加购筛选）';

ALTER TABLE sc_platform_spu
    ADD COLUMN sellable_category VARCHAR(32) NULL COMMENT '可售品类封闭枚举，见SellableCategory，供货命中用' AFTER primary_category_id,
    ADD COLUMN product_type VARCHAR(32) NULL COMMENT '商品类型封闭枚举，见ProductTypeTag，供货命中用' AFTER sellable_category,
    ADD COLUMN product_classification VARCHAR(32) NULL COMMENT '商品分类封闭枚举，见ProductClassification，供货命中用' AFTER product_type;

ALTER TABLE sc_platform_spu
    ADD CONSTRAINT ck_sc_spu_sellable_category CHECK (sellable_category IS NULL OR sellable_category IN (
        'EQUITY', 'PHYSICAL', 'MOVIE', 'JD', 'DINING', 'TAKEOUT', 'SUPERMARKET', 'HEMA', 'RTMART', 'LIFE')),
    ADD CONSTRAINT ck_sc_spu_product_type CHECK (product_type IS NULL OR product_type IN (
        'EQUITY_PRODUCT', 'MOVIE', 'DINING', 'JD', 'VIP')),
    ADD CONSTRAINT ck_sc_spu_product_classification CHECK (product_classification IS NULL OR product_classification IN (
        'ELECTRONICS', 'VIRTUAL_CARD', 'MOVIE_TICKET'));
