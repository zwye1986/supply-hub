-- 供应链“供应价模式”数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#25-供应价模式-offerpricemode
-- 约束：字典值严格对齐 OfferPriceMode；供应链运行时不读取 system 字典表。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '供应价模式' name, 'supply_chain_offer_price_mode' type,
           '供应商合作商品供应价模式；提交值与OfferPriceMode枚举名一致' remark
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '供应价模式' name, 'supply_chain_offer_price_mode' type,
           '供应商合作商品供应价模式；提交值与OfferPriceMode枚举名一致' remark
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
    SELECT 1 sort, '固定值' label, 'FIXED' value,
           'supply_chain_offer_price_mode' dict_type, 'warning' color_type,
           '直接填入固定供应价' remark
    UNION ALL SELECT 2, '实时更新', 'REALTIME', 'supply_chain_offer_price_mode', 'primary',
                     '按供应商API实时返回价格（点餐/电影票）'
    UNION ALL SELECT 3, '按供货折扣', 'DISCOUNT', 'supply_chain_offer_price_mode', 'success',
                     '按销售建议价×折扣率计算供应价'
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
    SELECT 1 sort, '固定值' label, 'FIXED' value,
           'supply_chain_offer_price_mode' dict_type, 'warning' color_type,
           '直接填入固定供应价' remark
    UNION ALL SELECT 2, '实时更新', 'REALTIME', 'supply_chain_offer_price_mode', 'primary',
                     '按供应商API实时返回价格（点餐/电影票）'
    UNION ALL SELECT 3, '按供货折扣', 'DISCOUNT', 'supply_chain_offer_price_mode', 'success',
                     '按销售建议价×折扣率计算供应价'
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
