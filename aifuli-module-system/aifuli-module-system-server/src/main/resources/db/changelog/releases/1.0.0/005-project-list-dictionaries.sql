-- 供应链“项目管理-项目列表”查询条件数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#421-项目列表数据字典
-- 约束：字典值与供应链接口封闭枚举一致；只同步 PRD 8.3.1 已知值，不删除管理员额外创建的数据。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '项目类型' name, 'supply_chain_project_type' type,
           '项目管理查询与项目档案共用；提交值为ProjectType枚举名' remark
    UNION ALL SELECT '项目状态', 'supply_chain_project_status',
                     '项目管理查询使用；仅包含PRD 8.3.1筛选状态'
) desired
WHERE NOT EXISTS (
    SELECT 1
    FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_dict_type dict_type
JOIN (
    SELECT '项目类型' name, 'supply_chain_project_type' type,
           '项目管理查询与项目档案共用；提交值为ProjectType枚举名' remark
    UNION ALL SELECT '项目状态', 'supply_chain_project_status',
                     '项目管理查询使用；仅包含PRD 8.3.1筛选状态'
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
    SELECT 1 sort, '节日福利' label, 'HOLIDAY_BENEFIT' value,
           'supply_chain_project_type' dict_type, 'primary' color_type, '' remark
    UNION ALL SELECT 2, '积分兑换', 'POINTS_REDEMPTION', 'supply_chain_project_type', 'success', ''
    UNION ALL SELECT 3, '员工福利', 'EMPLOYEE_BENEFIT', 'supply_chain_project_type', 'primary', ''
    UNION ALL SELECT 4, '客户回馈', 'CUSTOMER_REWARD', 'supply_chain_project_type', 'warning', ''
    UNION ALL SELECT 5, '弹性福利', 'FLEXIBLE_BENEFIT', 'supply_chain_project_type', 'success', ''
    UNION ALL SELECT 6, '其他', 'OTHER', 'supply_chain_project_type', 'info', ''

    UNION ALL SELECT 1, '筹备中', 'DRAFT', 'supply_chain_project_status', 'info', ''
    UNION ALL SELECT 2, '进行中', 'ACTIVE', 'supply_chain_project_status', 'success', ''
    UNION ALL SELECT 3, '已结束', 'CLOSED', 'supply_chain_project_status', 'info', ''
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
    SELECT 1 sort, '节日福利' label, 'HOLIDAY_BENEFIT' value,
           'supply_chain_project_type' dict_type, 'primary' color_type, '' remark
    UNION ALL SELECT 2, '积分兑换', 'POINTS_REDEMPTION', 'supply_chain_project_type', 'success', ''
    UNION ALL SELECT 3, '员工福利', 'EMPLOYEE_BENEFIT', 'supply_chain_project_type', 'primary', ''
    UNION ALL SELECT 4, '客户回馈', 'CUSTOMER_REWARD', 'supply_chain_project_type', 'warning', ''
    UNION ALL SELECT 5, '弹性福利', 'FLEXIBLE_BENEFIT', 'supply_chain_project_type', 'success', ''
    UNION ALL SELECT 6, '其他', 'OTHER', 'supply_chain_project_type', 'info', ''

    UNION ALL SELECT 1, '筹备中', 'DRAFT', 'supply_chain_project_status', 'info', ''
    UNION ALL SELECT 2, '进行中', 'ACTIVE', 'supply_chain_project_status', 'success', ''
    UNION ALL SELECT 3, '已结束', 'CLOSED', 'supply_chain_project_status', 'info', ''
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
