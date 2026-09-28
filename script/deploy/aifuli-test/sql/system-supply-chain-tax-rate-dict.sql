-- 通用批量定价税率下拉。管理员可继续在 system 字典中新增启用项，无需发布供应链服务。
-- 字典值格式：NON_TAXABLE 或 RATE_<百分数>，例如 RATE_6、RATE_9.5；标签由运营维护。
INSERT INTO system_dict_type (name, type, status, remark, creator, create_time, updater, update_time, deleted)
SELECT '供应链结算展示税率', 'supply_chain_tax_rate', 0, '仅用于结算展示，不参与任何价格计算', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_type WHERE type = 'supply_chain_tax_rate' AND deleted = b'0');

INSERT INTO system_dict_data (sort, label, value, dict_type, status, color_type, css_class, remark, creator, create_time, updater, update_time, deleted)
SELECT 10, '6%', 'RATE_6', 'supply_chain_tax_rate', 0, 'default', '', '0.06', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type = 'supply_chain_tax_rate' AND value = 'RATE_6' AND deleted = b'0');
INSERT INTO system_dict_data (sort, label, value, dict_type, status, color_type, css_class, remark, creator, create_time, updater, update_time, deleted)
SELECT 20, '9%', 'RATE_9', 'supply_chain_tax_rate', 0, 'default', '', '0.09', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type = 'supply_chain_tax_rate' AND value = 'RATE_9' AND deleted = b'0');
INSERT INTO system_dict_data (sort, label, value, dict_type, status, color_type, css_class, remark, creator, create_time, updater, update_time, deleted)
SELECT 30, '13%', 'RATE_13', 'supply_chain_tax_rate', 0, 'default', '', '0.13', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type = 'supply_chain_tax_rate' AND value = 'RATE_13' AND deleted = b'0');
INSERT INTO system_dict_data (sort, label, value, dict_type, status, color_type, css_class, remark, creator, create_time, updater, update_time, deleted)
SELECT 40, '不征税', 'NON_TAXABLE', 'supply_chain_tax_rate', 0, 'default', '', '0', '1', NOW(), '1', NOW(), b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type = 'supply_chain_tax_rate' AND value = 'NON_TAXABLE' AND deleted = b'0');
