-- 供应链“开票抬头类型”数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#31-抬头类型数据字典-supply_chain_invoice_title_type
-- 约束：字典值与供应链 InvoiceTitleType 封闭枚举一致；只同步已知值，不删除管理员额外创建的数据。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT '开票抬头类型', 'supply_chain_invoice_title_type', 0,
       '供应商与分销商开票信息共用；提交值为titleType枚举名（ENTERPRISE企业/PERSONAL个人）',
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST('supply_chain_invoice_title_type' AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
SET dict_type.name = '开票抬头类型',
    dict_type.status = 0,
    dict_type.remark = '供应商与分销商开票信息共用；提交值为titleType枚举名（ENTERPRISE企业/PERSONAL个人）',
    dict_type.updater = @sc_dict_creator,
    dict_type.update_time = NOW()
WHERE CAST(dict_type.type AS BINARY) = CAST('supply_chain_invoice_title_type' AS BINARY)
  AND dict_type.deleted = b'0';

INSERT INTO system_dict_data
    (sort, label, value, dict_type, status, color_type, css_class, remark,
     creator, create_time, updater, update_time, deleted)
SELECT desired.sort, desired.label, desired.value, 'supply_chain_invoice_title_type', 0,
       desired.color_type, '', desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0'
FROM (
    SELECT 1 sort, '企业' label, 'ENTERPRISE' value, 'primary' color_type,
           '企业抬头' remark
    UNION ALL SELECT 2, '个人', 'PERSONAL', 'info', '个人抬头'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_data existing
    WHERE CAST(existing.dict_type AS BINARY) = CAST('supply_chain_invoice_title_type' AS BINARY)
      AND CAST(existing.value AS BINARY) = CAST(desired.value AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_data dict_data
JOIN (
    SELECT 1 sort, '企业' label, 'ENTERPRISE' value, 'primary' color_type,
           '企业抬头' remark
    UNION ALL SELECT 2, '个人', 'PERSONAL', 'info', '个人抬头'
) desired ON CAST(desired.value AS BINARY) = CAST(dict_data.value AS BINARY)
SET dict_data.sort = desired.sort,
    dict_data.label = desired.label,
    dict_data.status = 0,
    dict_data.color_type = desired.color_type,
    dict_data.css_class = '',
    dict_data.remark = desired.remark,
    dict_data.updater = @sc_dict_creator,
    dict_data.update_time = NOW()
WHERE CAST(dict_data.dict_type AS BINARY) = CAST('supply_chain_invoice_title_type' AS BINARY)
  AND dict_data.deleted = b'0';
