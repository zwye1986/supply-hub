-- system库追加供应价选项；018保持原样。人工额外项不删除，供应链不跨库读字典。
-- 设计：docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#42-下游依赖与版本快照
INSERT INTO system_dict_data
    (sort, label, value, dict_type, status, color_type, css_class, remark,
     creator, create_time, updater, update_time, deleted)
SELECT 2, '供应价', 'SUPPLY_PRICE', 'supply_chain_suggested_sale_price_base', 0, 'default', '',
       '当前最低有效供货价；建议价随有效供给变化自动重算', 'system', NOW(), 'system', NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_dict_data
    WHERE CAST(dict_type AS BINARY) = CAST('supply_chain_suggested_sale_price_base' AS BINARY)
      AND CAST(value AS BINARY) = CAST('SUPPLY_PRICE' AS BINARY) AND deleted = b'0'
);
UPDATE system_dict_data
SET sort = 2, label = '供应价', status = 0, color_type = 'default',
    remark = '当前最低有效供货价；建议价随有效供给变化自动重算', updater = 'system', update_time = NOW()
WHERE CAST(dict_type AS BINARY) = CAST('supply_chain_suggested_sale_price_base' AS BINARY)
  AND CAST(value AS BINARY) = CAST('SUPPLY_PRICE' AS BINARY) AND deleted = b'0';
