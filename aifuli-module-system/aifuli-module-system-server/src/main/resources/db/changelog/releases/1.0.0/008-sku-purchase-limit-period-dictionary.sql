-- 供应链商品“限购周期”数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更
-- 约束：供商品管理端 SKU 限购下拉使用，值严格对齐 PurchaseLimitPeriod 枚举（LIFETIME, YEAR, MONTH, WEEK, DAY）。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT 'SKU限购周期' name, 'supply_chain_purchase_limit_period' type,
           '平台SKU每人限购与绑定手机号限购周期；提交值为 PurchaseLimitPeriod 英文枚举值' remark
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT 'SKU限购周期' name, 'supply_chain_purchase_limit_period' type,
           '平台SKU每人限购与绑定手机号限购周期；提交值为 PurchaseLimitPeriod 英文枚举值' remark
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
    SELECT 1 sort, '终身' label, 'LIFETIME' value,
           'supply_chain_purchase_limit_period' dict_type, 'default' color_type,
           '对应PurchaseLimitPeriod.LIFETIME' remark
    UNION ALL SELECT 2, '每年', 'YEAR', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.YEAR'
    UNION ALL SELECT 3, '每月', 'MONTH', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.MONTH'
    UNION ALL SELECT 4, '每周', 'WEEK', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.WEEK'
    UNION ALL SELECT 5, '每天', 'DAY', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.DAY'
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
    SELECT 1 sort, '终身' label, 'LIFETIME' value,
           'supply_chain_purchase_limit_period' dict_type, 'default' color_type,
           '对应PurchaseLimitPeriod.LIFETIME' remark
    UNION ALL SELECT 2, '每年', 'YEAR', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.YEAR'
    UNION ALL SELECT 3, '每月', 'MONTH', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.MONTH'
    UNION ALL SELECT 4, '每周', 'WEEK', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.WEEK'
    UNION ALL SELECT 5, '每天', 'DAY', 'supply_chain_purchase_limit_period', 'default',
                     '对应PurchaseLimitPeriod.DAY'
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
