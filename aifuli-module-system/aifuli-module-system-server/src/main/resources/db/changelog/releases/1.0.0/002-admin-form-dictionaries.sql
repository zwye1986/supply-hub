-- 供应链“新增分销商”表单数据字典 + 三级独立封闭枚举（可售品类/商品类型/商品分类）。
-- 目标库：system 主数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#3-分销商表单其它字典
-- 约束：字典值是供应链接口封闭枚举契约；脚本只同步已知值，不删除管理员额外创建的数据。
-- 不重复迁移 001 已落地的六类供应商字典（配送区域/发货时效/售后/结算方式/对接方式/供应商合作状态）。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '可售品类' name, 'supply_chain_sellable_category' type,
           '供应商供货配置与分销商档案共用；平台SPU同码打标' remark
    UNION ALL SELECT '商品类型', 'supply_chain_product_type',
                     '供应商供货配置与分销商档案共用；平台SPU同码打标'
    UNION ALL SELECT '商品分类', 'supply_chain_product_classification',
                     '仅供货配置行与平台SPU使用，分销商档案无此级'
    UNION ALL SELECT '分销等级', 'supply_chain_distribution_level',
                     '新增分销商：组织树等级，提交仍为整数'
    UNION ALL SELECT '合作模式', 'supply_chain_cooperation_mode',
                     '新增分销商：可多选'
    UNION ALL SELECT '分销商类型', 'supply_chain_distributor_type',
                     '新增分销商：单选'
    UNION ALL SELECT '供货择优倾向', 'supply_chain_supply_preference',
                     '新增分销商：不改变路由引擎'
    UNION ALL SELECT '分销商合作状态', 'supply_chain_buyer_status',
                     '新增分销商：不含归档态；文案与供应商合作状态区分'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '可售品类' name, 'supply_chain_sellable_category' type,
           '供应商供货配置与分销商档案共用；平台SPU同码打标' remark
    UNION ALL SELECT '商品类型', 'supply_chain_product_type',
                     '供应商供货配置与分销商档案共用；平台SPU同码打标'
    UNION ALL SELECT '商品分类', 'supply_chain_product_classification',
                     '仅供货配置行与平台SPU使用，分销商档案无此级'
    UNION ALL SELECT '分销等级', 'supply_chain_distribution_level',
                     '新增分销商：组织树等级，提交仍为整数'
    UNION ALL SELECT '合作模式', 'supply_chain_cooperation_mode',
                     '新增分销商：可多选'
    UNION ALL SELECT '分销商类型', 'supply_chain_distributor_type',
                     '新增分销商：单选'
    UNION ALL SELECT '供货择优倾向', 'supply_chain_supply_preference',
                     '新增分销商：不改变路由引擎'
    UNION ALL SELECT '分销商合作状态', 'supply_chain_buyer_status',
                     '新增分销商：不含归档态；文案与供应商合作状态区分'
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
    SELECT 1 sort, '权益商品' label, 'EQUITY' value,
           'supply_chain_sellable_category' dict_type, 'primary' color_type, '' remark
    UNION ALL SELECT 2, '实物商品', 'PHYSICAL', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 3, '电影快充', 'MOVIE', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 5, '点餐', 'DINING', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 6, '外卖', 'TAKEOUT', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 7, '商超', 'SUPERMARKET', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 8, '盒马购', 'HEMA', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 9, '大润发', 'RTMART', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 10, '生活服务', 'LIFE', 'supply_chain_sellable_category', 'primary', ''

    UNION ALL SELECT 1, '权益商品', 'EQUITY_PRODUCT', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 2, '电影', 'MOVIE', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 3, '点餐', 'DINING', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 5, '唯品会', 'VIP', 'supply_chain_product_type', 'success', ''

    UNION ALL SELECT 1, '数码电子', 'ELECTRONICS', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 2, '虚拟卡券类', 'VIRTUAL_CARD', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 3, '电影快票', 'MOVIE_TICKET', 'supply_chain_product_classification', 'warning', ''

    UNION ALL SELECT 1, '一级分销商', '1', 'supply_chain_distribution_level', 'primary', '提交为整数1'
    UNION ALL SELECT 2, '二级分销商', '2', 'supply_chain_distribution_level', 'primary', '提交为整数2'
    UNION ALL SELECT 3, '三级分销商', '3', 'supply_chain_distribution_level', 'primary', '提交为整数3'

    UNION ALL SELECT 1, '直营分销', 'DIRECT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 2, '代理分销', 'AGENT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 3, '渠道分销', 'CHANNEL', 'supply_chain_cooperation_mode', 'success', ''

    UNION ALL SELECT 1, '企业', 'ENTERPRISE', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 2, '个人', 'PERSONAL', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 3, '平台入驻', 'PLATFORM', 'supply_chain_distributor_type', 'info', ''

    UNION ALL SELECT 1, '稳定优先', 'STABLE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 2, '价格优先', 'PRICE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 3, '综合优先', 'COMPREHENSIVE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'

    UNION ALL SELECT 1, '正常', 'ACTIVE', 'supply_chain_buyer_status', 'success', ''
    UNION ALL SELECT 2, '暂停分销', 'SUSPENDED', 'supply_chain_buyer_status', 'warning', ''
    UNION ALL SELECT 3, '黑名单', 'BLACKLIST', 'supply_chain_buyer_status', 'danger', ''
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
    SELECT 1 sort, '权益商品' label, 'EQUITY' value,
           'supply_chain_sellable_category' dict_type, 'primary' color_type, '' remark
    UNION ALL SELECT 2, '实物商品', 'PHYSICAL', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 3, '电影快充', 'MOVIE', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 5, '点餐', 'DINING', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 6, '外卖', 'TAKEOUT', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 7, '商超', 'SUPERMARKET', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 8, '盒马购', 'HEMA', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 9, '大润发', 'RTMART', 'supply_chain_sellable_category', 'primary', ''
    UNION ALL SELECT 10, '生活服务', 'LIFE', 'supply_chain_sellable_category', 'primary', ''

    UNION ALL SELECT 1, '权益商品', 'EQUITY_PRODUCT', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 2, '电影', 'MOVIE', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 3, '点餐', 'DINING', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 4, '京东', 'JD', 'supply_chain_product_type', 'success', ''
    UNION ALL SELECT 5, '唯品会', 'VIP', 'supply_chain_product_type', 'success', ''

    UNION ALL SELECT 1, '数码电子', 'ELECTRONICS', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 2, '虚拟卡券类', 'VIRTUAL_CARD', 'supply_chain_product_classification', 'warning', ''
    UNION ALL SELECT 3, '电影快票', 'MOVIE_TICKET', 'supply_chain_product_classification', 'warning', ''

    UNION ALL SELECT 1, '一级分销商', '1', 'supply_chain_distribution_level', 'primary', '提交为整数1'
    UNION ALL SELECT 2, '二级分销商', '2', 'supply_chain_distribution_level', 'primary', '提交为整数2'
    UNION ALL SELECT 3, '三级分销商', '3', 'supply_chain_distribution_level', 'primary', '提交为整数3'

    UNION ALL SELECT 1, '直营分销', 'DIRECT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 2, '代理分销', 'AGENT', 'supply_chain_cooperation_mode', 'success', ''
    UNION ALL SELECT 3, '渠道分销', 'CHANNEL', 'supply_chain_cooperation_mode', 'success', ''

    UNION ALL SELECT 1, '企业', 'ENTERPRISE', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 2, '个人', 'PERSONAL', 'supply_chain_distributor_type', 'info', ''
    UNION ALL SELECT 3, '平台入驻', 'PLATFORM', 'supply_chain_distributor_type', 'info', ''

    UNION ALL SELECT 1, '稳定优先', 'STABLE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 2, '价格优先', 'PRICE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'
    UNION ALL SELECT 3, '综合优先', 'COMPREHENSIVE', 'supply_chain_supply_preference', 'primary', '不改变路由引擎'

    UNION ALL SELECT 1, '正常', 'ACTIVE', 'supply_chain_buyer_status', 'success', ''
    UNION ALL SELECT 2, '暂停分销', 'SUSPENDED', 'supply_chain_buyer_status', 'warning', ''
    UNION ALL SELECT 3, '黑名单', 'BLACKLIST', 'supply_chain_buyer_status', 'danger', ''
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
