-- 供应链“商品管理-商品列表”查询条件数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page
-- 约束：商品状态使用 SKU 状态码；兑换方式只同步当前商品列表原型开放的卡号卡密与直充。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '商品兑换方式' name, 'supply_chain_fulfillment_type' type,
           '商品列表兑换方式筛选；提交值为Platform SKU fulfillment_type' remark
    UNION ALL SELECT 'SKU商品状态', 'supply_chain_sku_status',
                     '商品列表状态筛选；提交值为Platform SKU status短整型码'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '商品兑换方式' name, 'supply_chain_fulfillment_type' type,
           '商品列表兑换方式筛选；提交值为Platform SKU fulfillment_type' remark
    UNION ALL SELECT 'SKU商品状态', 'supply_chain_sku_status',
                     '商品列表状态筛选；提交值为Platform SKU status短整型码'
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
    SELECT 1 sort, '卡号卡密' label, 'CARD_CODE_SECRET' value,
           'supply_chain_fulfillment_type' dict_type, 'primary' color_type,
           '对应Platform SKU fulfillment_type' remark
    UNION ALL SELECT 2, '直充', 'DIRECT_RECHARGE', 'supply_chain_fulfillment_type', 'success',
                     '对应Platform SKU fulfillment_type'

    UNION ALL SELECT 1, '草稿', '0', 'supply_chain_sku_status', 'info',
                     '对应CatalogEntityStatus.DRAFT'
    UNION ALL SELECT 2, '上架', '10', 'supply_chain_sku_status', 'success',
                     '对应CatalogEntityStatus.ACTIVE'
    UNION ALL SELECT 3, '下架', '20', 'supply_chain_sku_status', 'warning',
                     '对应CatalogEntityStatus.INACTIVE'
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
    SELECT 1 sort, '卡号卡密' label, 'CARD_CODE_SECRET' value,
           'supply_chain_fulfillment_type' dict_type, 'primary' color_type,
           '对应Platform SKU fulfillment_type' remark
    UNION ALL SELECT 2, '直充', 'DIRECT_RECHARGE', 'supply_chain_fulfillment_type', 'success',
                     '对应Platform SKU fulfillment_type'

    UNION ALL SELECT 1, '草稿', '0', 'supply_chain_sku_status', 'info',
                     '对应CatalogEntityStatus.DRAFT'
    UNION ALL SELECT 2, '上架', '10', 'supply_chain_sku_status', 'success',
                     '对应CatalogEntityStatus.ACTIVE'
    UNION ALL SELECT 3, '下架', '20', 'supply_chain_sku_status', 'warning',
                     '对应CatalogEntityStatus.INACTIVE'
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
