-- 供应链“新增供应商”表单数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#521-新增供应商数据字典
-- 约束：字典值是供应链接口封闭枚举契约；脚本只同步已知值，不删除管理员额外创建的数据。

SET @sc_dict_creator = 'system';

-- 六个供应链专用字典类型。按 type 判重，重复执行时同步名称、状态和备注。
INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '供应商配送区域' name, 'supply_chain_delivery_region_type' type,
           '新增供应商：全国或指定区域' remark
    UNION ALL SELECT '供应商发货时效', 'supply_chain_shipping_sla_code',
                     '新增供应商：标准发货时效或自定义'
    UNION ALL SELECT '供应商是否支持售后', 'supply_chain_aftersales_supported',
                     '新增供应商：是否支持售后；值需转换为 Boolean'
    UNION ALL SELECT '供应商结算方式', 'supply_chain_settlement_method',
                     '新增供应商：结算方式'
    UNION ALL SELECT '供应商对接方式', 'supply_chain_integration_mode',
                     '新增供应商：可多选的系统对接方式'
    UNION ALL SELECT '供应商合作状态', 'supply_chain_supplier_status',
                     '新增供应商：不包含归档态'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '供应商配送区域' name, 'supply_chain_delivery_region_type' type,
           '新增供应商：全国或指定区域' remark
    UNION ALL SELECT '供应商发货时效', 'supply_chain_shipping_sla_code',
                     '新增供应商：标准发货时效或自定义'
    UNION ALL SELECT '供应商是否支持售后', 'supply_chain_aftersales_supported',
                     '新增供应商：是否支持售后；值需转换为 Boolean'
    UNION ALL SELECT '供应商结算方式', 'supply_chain_settlement_method',
                     '新增供应商：结算方式'
    UNION ALL SELECT '供应商对接方式', 'supply_chain_integration_mode',
                     '新增供应商：可多选的系统对接方式'
    UNION ALL SELECT '供应商合作状态', 'supply_chain_supplier_status',
                     '新增供应商：不包含归档态'
) desired ON CAST(desired.type AS BINARY) = CAST(dict_type.type AS BINARY)
SET dict_type.name = desired.name,
    dict_type.status = 0,
    dict_type.remark = desired.remark,
    dict_type.updater = @sc_dict_creator,
    dict_type.update_time = NOW()
WHERE dict_type.deleted = b'0';

-- 二十个封闭选项。sort 从 1 开始，status=0 表示启用。
INSERT INTO system_dict_data
    (sort, label, value, dict_type, status, color_type, css_class, remark,
     creator, create_time, updater, update_time, deleted)
SELECT desired.sort, desired.label, desired.value, desired.dict_type, 0,
       desired.color_type, '', desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0'
FROM (
    SELECT 1 sort, '全国' label, 'NATIONWIDE' value,
           'supply_chain_delivery_region_type' dict_type, 'success' color_type,
           '配送范围覆盖全国' remark
    UNION ALL SELECT 2, '指定区域', 'DESIGNATED',
                     'supply_chain_delivery_region_type', 'warning', '配送范围由地区树 adcode 指定'

    UNION ALL SELECT 1, '当日发', 'SAME_DAY',
                     'supply_chain_shipping_sla_code', 'success', '当日发货'
    UNION ALL SELECT 2, '次日发', 'NEXT_DAY',
                     'supply_chain_shipping_sla_code', 'primary', '次日发货'
    UNION ALL SELECT 3, '48小时发', 'HOURS_48',
                     'supply_chain_shipping_sla_code', 'warning', '48 小时内发货'
    UNION ALL SELECT 4, '72小时发', 'HOURS_72',
                     'supply_chain_shipping_sla_code', 'warning', '72 小时内发货'
    UNION ALL SELECT 5, '自定义', 'CUSTOM',
                     'supply_chain_shipping_sla_code', 'info', '时效填写在 shippingSlaCustom'

    UNION ALL SELECT 1, '是', 'true',
                     'supply_chain_aftersales_supported', 'success', '支持售后'
    UNION ALL SELECT 2, '否', 'false',
                     'supply_chain_aftersales_supported', 'info', '不支持售后'

    UNION ALL SELECT 1, '日结', 'DAILY',
                     'supply_chain_settlement_method', 'primary', '按日结算'
    UNION ALL SELECT 2, '周结', 'WEEKLY',
                     'supply_chain_settlement_method', 'primary', '按周结算'
    UNION ALL SELECT 3, '月结', 'MONTHLY',
                     'supply_chain_settlement_method', 'primary', '按月结算'
    UNION ALL SELECT 4, '季结', 'QUARTERLY',
                     'supply_chain_settlement_method', 'primary', '按季度结算'
    UNION ALL SELECT 5, '预付', 'PREPAID',
                     'supply_chain_settlement_method', 'warning', '预付结算'

    UNION ALL SELECT 1, 'API直连', 'API',
                     'supply_chain_integration_mode', 'primary', 'API 系统直连'
    UNION ALL SELECT 2, 'H5直连', 'H5',
                     'supply_chain_integration_mode', 'success', 'H5 页面直连'
    UNION ALL SELECT 3, '人工对接', 'MANUAL',
                     'supply_chain_integration_mode', 'info', '人工对接'

    UNION ALL SELECT 1, '正常', 'ACTIVE',
                     'supply_chain_supplier_status', 'success', '正常合作'
    UNION ALL SELECT 2, '暂停供货', 'SUSPENDED',
                     'supply_chain_supplier_status', 'warning', '暂停供货'
    UNION ALL SELECT 3, '黑名单', 'BLACKLIST',
                     'supply_chain_supplier_status', 'danger', '黑名单'
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
    SELECT 1 sort, '全国' label, 'NATIONWIDE' value,
           'supply_chain_delivery_region_type' dict_type, 'success' color_type,
           '配送范围覆盖全国' remark
    UNION ALL SELECT 2, '指定区域', 'DESIGNATED',
                     'supply_chain_delivery_region_type', 'warning', '配送范围由地区树 adcode 指定'

    UNION ALL SELECT 1, '当日发', 'SAME_DAY',
                     'supply_chain_shipping_sla_code', 'success', '当日发货'
    UNION ALL SELECT 2, '次日发', 'NEXT_DAY',
                     'supply_chain_shipping_sla_code', 'primary', '次日发货'
    UNION ALL SELECT 3, '48小时发', 'HOURS_48',
                     'supply_chain_shipping_sla_code', 'warning', '48 小时内发货'
    UNION ALL SELECT 4, '72小时发', 'HOURS_72',
                     'supply_chain_shipping_sla_code', 'warning', '72 小时内发货'
    UNION ALL SELECT 5, '自定义', 'CUSTOM',
                     'supply_chain_shipping_sla_code', 'info', '时效填写在 shippingSlaCustom'

    UNION ALL SELECT 1, '是', 'true',
                     'supply_chain_aftersales_supported', 'success', '支持售后'
    UNION ALL SELECT 2, '否', 'false',
                     'supply_chain_aftersales_supported', 'info', '不支持售后'

    UNION ALL SELECT 1, '日结', 'DAILY',
                     'supply_chain_settlement_method', 'primary', '按日结算'
    UNION ALL SELECT 2, '周结', 'WEEKLY',
                     'supply_chain_settlement_method', 'primary', '按周结算'
    UNION ALL SELECT 3, '月结', 'MONTHLY',
                     'supply_chain_settlement_method', 'primary', '按月结算'
    UNION ALL SELECT 4, '季结', 'QUARTERLY',
                     'supply_chain_settlement_method', 'primary', '按季度结算'
    UNION ALL SELECT 5, '预付', 'PREPAID',
                     'supply_chain_settlement_method', 'warning', '预付结算'

    UNION ALL SELECT 1, 'API直连', 'API',
                     'supply_chain_integration_mode', 'primary', 'API 系统直连'
    UNION ALL SELECT 2, 'H5直连', 'H5',
                     'supply_chain_integration_mode', 'success', 'H5 页面直连'
    UNION ALL SELECT 3, '人工对接', 'MANUAL',
                     'supply_chain_integration_mode', 'info', '人工对接'

    UNION ALL SELECT 1, '正常', 'ACTIVE',
                     'supply_chain_supplier_status', 'success', '正常合作'
    UNION ALL SELECT 2, '暂停供货', 'SUSPENDED',
                     'supply_chain_supplier_status', 'warning', '暂停供货'
    UNION ALL SELECT 3, '黑名单', 'BLACKLIST',
                     'supply_chain_supplier_status', 'danger', '黑名单'
) desired ON CAST(desired.dict_type AS BINARY) = CAST(dict_data.dict_type AS BINARY)
         AND CAST(desired.value AS BINARY) = CAST(dict_data.value AS BINARY)
SET dict_data.sort = desired.sort,
    dict_data.label = desired.label,
    dict_data.status = 0,
    dict_data.color_type = desired.color_type,
    dict_data.css_class = '',
    dict_data.remark = desired.remark,
    dict_data.updater = @sc_dict_creator,
    dict_data.update_time = NOW()
WHERE dict_data.deleted = b'0';
