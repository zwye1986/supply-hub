-- changeset: 041-sku-inventory
-- 为 sc_platform_sku 增加 SKU 库存字段，管理后台可在创建/编辑商品时设置库存数量。
-- 本列是商品资料数量，不是 InventorySource 履约库存，本期不参与路由预占与扣减。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#216-sku-库存资料041-changeset

ALTER TABLE sc_platform_sku
    ADD COLUMN sku_inventory BIGINT NOT NULL DEFAULT 0 COMMENT 'SKU库存数量，管理端创建或编辑商品时设置'
    AFTER phone_bind_limit_quantity;

ALTER TABLE sc_platform_sku
    ADD CONSTRAINT ck_sc_sku_inventory CHECK (sku_inventory >= 0);
