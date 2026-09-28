-- SKU 销售建议价规则：规则持久化，最终金额继续物化在 suggested_sale_price。
-- 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#221-销售建议价规则039-changeset。

ALTER TABLE sc_platform_sku
    ADD COLUMN suggested_sale_price_rule_json JSON NULL
        COMMENT 'SKU销售建议价生成规则JSON；物化结果保存在suggested_sale_price'
        AFTER suggested_sale_price;

-- 历史金额无法可靠反推原模式；统一固定化以保证迁移前后金额完全不变。
UPDATE sc_platform_sku
SET suggested_sale_price_rule_json = JSON_OBJECT(
        'mode', 'FIXED',
        'fixedPriceCents', suggested_sale_price
    )
WHERE suggested_sale_price_rule_json IS NULL;

ALTER TABLE sc_platform_sku
    MODIFY COLUMN suggested_sale_price_rule_json JSON NOT NULL
        COMMENT 'SKU销售建议价生成规则JSON；物化结果保存在suggested_sale_price';
