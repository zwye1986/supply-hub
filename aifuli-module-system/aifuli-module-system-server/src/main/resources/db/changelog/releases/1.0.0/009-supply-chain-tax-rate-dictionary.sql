-- 供应链“结算展示税率”数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性
-- 约束：供通用批量定价税率下拉及报价使用，值包括 RATE_6、RATE_9、RATE_13、NON_TAXABLE；仅用于结算展示，不参与价格计算。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '供应链结算展示税率' name, 'supply_chain_tax_rate' type,
           '仅用于结算展示，不参与任何价格计算' remark
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '供应链结算展示税率' name, 'supply_chain_tax_rate' type,
           '仅用于结算展示，不参与任何价格计算' remark
) desired ON CAST(desired.type AS BINARY) = CAST(dict_type.type AS BINARY)
SET dict_type.name = desired.name,
    dict_type.status = 0,
    dict_type.remark = desired.remark,
    dict_type.updater = @sc_dict_creator,
    dict_type.update_time = NOW()
WHERE dict_type.deleted = b'0';

INSERT INTO system_dict_data
    (sort, label, value, dict_type, status, color_type, css_class, remark,
     creator, create_time, updater, update_time, deleted)
SELECT desired.sort, desired.label, desired.value, desired.dict_type, 0,
       desired.color_type, '', desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0'
FROM (
    SELECT 10 sort, '6%' label, 'RATE_6' value,
           'supply_chain_tax_rate' dict_type, 'default' color_type,
           '0.06' remark
    UNION ALL SELECT 20, '9%', 'RATE_9', 'supply_chain_tax_rate', 'default', '0.09'
    UNION ALL SELECT 30, '13%', 'RATE_13', 'supply_chain_tax_rate', 'default', '0.13'
    UNION ALL SELECT 40, '不征税', 'NON_TAXABLE', 'supply_chain_tax_rate', 'default', '0'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_data existing
    WHERE CAST(existing.dict_type AS BINARY) = CAST(desired.dict_type AS BINARY)
      AND CAST(existing.value AS BINARY) = CAST(desired.value AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_data dict_data
JOIN (
    SELECT 10 sort, '6%' label, 'RATE_6' value,
           'supply_chain_tax_rate' dict_type, 'default' color_type,
           '0.06' remark
    UNION ALL SELECT 20, '9%', 'RATE_9', 'supply_chain_tax_rate', 'default', '0.09'
    UNION ALL SELECT 30, '13%', 'RATE_13', 'supply_chain_tax_rate', 'default', '0.13'
    UNION ALL SELECT 40, '不征税', 'NON_TAXABLE', 'supply_chain_tax_rate', 'default', '0'
) desired ON CAST(desired.dict_type AS BINARY) = CAST(dict_data.dict_type AS BINARY)
         AND CAST(desired.value AS BINARY) = CAST(dict_data.value AS BINARY)
SET dict_data.sort = desired.sort,
    dict_data.label = desired.label,
    dict_data.status = 0,
    dict_data.color_type = desired.color_type,
    dict_data.remark = desired.remark,
    dict_data.updater = @sc_dict_creator,
    dict_data.update_time = NOW()
WHERE dict_data.deleted = b'0';
