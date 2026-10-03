-- 退役 Offer 保留外部编码和版本历史，但释放编码唯一槽（057）。
-- 草稿0、已发布10、暂停20均占用；退役90返回NULL，MySQL唯一键允许多行NULL。
-- 保留053原文件及校验和；存量全行唯一保证新约束兼容，无需删除或重写业务行。
-- 单条ALTER原子替换唯一键，避免运行期出现无唯一约束的窗口。
-- 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#154-退役商品释放外部编码
ALTER TABLE sc_supplier_offer
    DROP INDEX uk_sc_supplier_offer_external_sku,
    ADD COLUMN live_external_sku_code VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN status <> 90 THEN external_sku_code END
    ) STORED COMMENT '未退役商品外部编码，退役时NULL；仅用于供应商内未退役商品唯一约束',
    ADD UNIQUE KEY uk_sc_supplier_offer_external_sku (supplier_id, live_external_sku_code);
