-- 供应链商品类型状态与商品分类状态数据字典部署热更脚本。
-- 目标库：system 库；不要在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#53-商品中心数据字典与配置变更

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '商品类型状态' name, 'supply_chain_product_type_status' type,
           '商品类型管理状态；提交值为 ProductType status 短整型码' remark
    UNION ALL SELECT '商品分类状态', 'supply_chain_category_status',
                     '商品分类管理状态；提交值为 ProductCategory status 短整型码'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '商品类型状态' name, 'supply_chain_product_type_status' type,
           '商品类型管理状态；提交值为 ProductType status 短整型码' remark
    UNION ALL SELECT '商品分类状态', 'supply_chain_category_status',
                     '商品分类管理状态；提交值为 ProductCategory status 短整型码'
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
    SELECT 1 sort, '启用' label, '10' value,
           'supply_chain_product_type_status' dict_type, 'success' color_type,
           '对应CatalogEntityStatus.ACTIVE' remark
    UNION ALL SELECT 2, '禁用', '20', 'supply_chain_product_type_status', 'danger',
                     '对应CatalogEntityStatus.INACTIVE'

    UNION ALL SELECT 1, '启用', '10',
           'supply_chain_category_status' dict_type, 'success' color_type,
           '对应CatalogEntityStatus.ACTIVE' remark
    UNION ALL SELECT 2, '禁用', '20', 'supply_chain_category_status', 'danger',
                     '对应CatalogEntityStatus.INACTIVE'
    UNION ALL SELECT 3, '草稿', '0', 'supply_chain_category_status', 'info',
                     '对应CatalogEntityStatus.DRAFT'
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
    SELECT 1 sort, '启用' label, '10' value,
           'supply_chain_product_type_status' dict_type, 'success' color_type,
           '对应CatalogEntityStatus.ACTIVE' remark
    UNION ALL SELECT 2, '禁用', '20', 'supply_chain_product_type_status', 'danger',
                     '对应CatalogEntityStatus.INACTIVE'

    UNION ALL SELECT 1, '启用', '10',
           'supply_chain_category_status' dict_type, 'success' color_type,
           '对应CatalogEntityStatus.ACTIVE' remark
    UNION ALL SELECT 2, '禁用', '20', 'supply_chain_category_status', 'danger',
                     '对应CatalogEntityStatus.INACTIVE'
    UNION ALL SELECT 3, '草稿', '0', 'supply_chain_category_status', 'info',
                     '对应CatalogEntityStatus.DRAFT'
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
