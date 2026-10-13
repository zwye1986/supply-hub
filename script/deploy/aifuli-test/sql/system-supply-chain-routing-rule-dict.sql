-- 供应链“路由模式”与“路由规则状态”数据字典，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；禁止在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-10-12-routing-rule-dictionaries-design.md#4-迁移与存量兼容
-- 只补缺失的未删除项，不覆盖管理员元数据或启用状态；顺序重放幂等，不承诺并发执行安全。
-- 字典 value 对齐 HTTP 字符串枚举，不使用规则表 status 的 10/20；DISABLED 数据项自身仍为启用。
-- 本文件同时作为存量热更脚本交付，两份 SQL 保持一致；热更不会伪标 DATABASECHANGELOG。

SET @sc_dict_creator = 'system';

INSERT INTO system_dict_type
    (name, type, status, remark, creator, create_time, updater, update_time, deleted, deleted_time)
SELECT desired.name, desired.type, 0, desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0', NULL
FROM (
    SELECT '路由模式' name, 'supply_chain_routing_mode' type,
           '路由规则管理模式；提交值与RoutingRuleRouteMode枚举名一致，不可自由扩展编码' remark
    UNION ALL SELECT '路由规则状态', 'supply_chain_routing_rule_status',
                     '路由规则启停状态；提交值与RoutingRuleStatus枚举名一致，不使用数据库内部10/20'
) desired
WHERE NOT EXISTS (
    SELECT 1 FROM system_dict_type existing
    WHERE CAST(existing.type AS BINARY) = CAST(desired.type AS BINARY)
      AND existing.deleted = b'0'
);

INSERT INTO system_dict_data
    (sort, label, value, dict_type, status, color_type, css_class, remark,
     creator, create_time, updater, update_time, deleted)
SELECT desired.sort, desired.label, desired.value, desired.dict_type, 0,
       desired.color_type, '', desired.remark,
       @sc_dict_creator, NOW(), @sc_dict_creator, NOW(), b'0'
FROM (
    SELECT 1 sort, '自动路由' label, 'AUTO' value,
           'supply_chain_routing_mode' dict_type, '' color_type,
           '对应RoutingRuleRouteMode.AUTO；业务约束由供应链封闭枚举与路由引擎维护' remark
    UNION ALL SELECT 2, '固定供应商', 'FIXED', 'supply_chain_routing_mode', '',
                     '对应RoutingRuleRouteMode.FIXED；必须指定主供应商'
    UNION ALL SELECT 1, '启用', 'ENABLED', 'supply_chain_routing_rule_status', 'success',
                     '对应RoutingRuleStatus.ENABLED；规则参与路由，持久化内部码10'
    UNION ALL SELECT 2, '停用', 'DISABLED', 'supply_chain_routing_rule_status', 'info',
                     '对应RoutingRuleStatus.DISABLED；规则不参与路由，持久化内部码20；非删除'
) desired
WHERE NOT EXISTS (
    SELECT 1 FROM system_dict_data existing
    WHERE CAST(existing.dict_type AS BINARY) = CAST(desired.dict_type AS BINARY)
      AND CAST(existing.value AS BINARY) = CAST(desired.value AS BINARY)
      AND existing.deleted = b'0'
);
