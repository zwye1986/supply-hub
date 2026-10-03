-- 供给身份行新增供应商外部商品编码（053，f1）：
-- 选合作商品时录入，上架即发布时冻结进 sc_supplier_offer_version.external_sku_code，
-- 执行侧经 FulfillmentOperationTicketFactory 注入、适配器作为供应商 productId 发出。
-- 列可空：历史身份行无值；(supplier_id, external_sku_code) 唯一，MySQL 唯一键允许多行 NULL，
-- 历史空值不冲突也不阻塞；新建合作商品由前端与服务层强制必填。
-- 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#54-f1-external_sku_code变更集-053
ALTER TABLE sc_supplier_offer
    ADD COLUMN external_sku_code VARCHAR(128) NULL
    COMMENT '供应商外部商品编码，选合作商品时录入' AFTER platform_sku_id,
    ADD UNIQUE KEY uk_sc_supplier_offer_external_sku (supplier_id, external_sku_code);
