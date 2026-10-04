-- 供应链「直充限频监控」菜单，由 system-server Liquibase 执行。
-- 目标库：system 主数据源；不要在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#84-新增供应链菜单的-liquibase-部署
--       docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限
-- 约束一：只建菜单，不写 system_role_menu。可见性依赖 RBAC §8.1 超集展开——yudao 超管走全量菜单分支，
--         持有 supply-chain:admin 的角色按 parent_id 展开整棵子树；query/retry/release 的独立授权
--         只在将来建立非超管运营角色时才需要额外授权变更集。
-- 约束二：system_menu 使用 utf8mb4_unicode_ci，大小写与重音不敏感；所有 application_code 与 permission
--         的判重、定位与同步比较必须 CAST(... AS BINARY)，否则会误判重或触发 Illegal mix of collations。
-- 约束三：keep_alive 固定 b'0'，精确手机号只能留在页面内存，不能被路由缓存保留。
-- 幂等：按 permission 判重插入，再用 UPDATE 把既有行收敛到设计值；人工执行过 script/deploy 旧脚本的
--       环境重跑不产生重复菜单，也不会把已收敛的行改回旧值。

SET @sc_app = 'supply-chain-admin';
SET @sc_creator = 'system';

-- 供应链根目录 id。变更集前置条件已校验其存在，此处解析而非硬编码数字 id。
SET @sc_root_id = (
    SELECT MIN(id) FROM system_menu
    WHERE CAST(application_code AS BINARY) = CAST(@sc_app AS BINARY)
      AND parent_id = 0
      AND CAST(permission AS BINARY) = CAST('supply-chain:admin' AS BINARY)
      AND deleted = b'0'
);

-- 直充限频监控页面菜单；permission 即查询权限 supply-chain:recharge-frequency:query。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '直充限频监控', 'supply-chain:recharge-frequency:query', 2, 6, @sc_root_id,
       'recharge-frequency', 'ep:timer', 'supplier/order/recharge-frequency/index', 'RechargeFrequencyMonitor',
       0, b'1', b'0', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE CAST(application_code AS BINARY) = CAST(@sc_app AS BINARY)
      AND CAST(permission AS BINARY) = CAST('supply-chain:recharge-frequency:query' AS BINARY)
      AND deleted = b'0'
);

SET @sc_frequency_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE CAST(application_code AS BINARY) = CAST(@sc_app AS BINARY)
      AND CAST(permission AS BINARY) = CAST('supply-chain:recharge-frequency:query' AS BINARY)
      AND deleted = b'0'
);

UPDATE system_menu
SET name = '直充限频监控', type = 2, sort = 6, parent_id = @sc_root_id,
    path = 'recharge-frequency', icon = 'ep:timer',
    component = 'supplier/order/recharge-frequency/index',
    component_name = 'RechargeFrequencyMonitor', status = 0, visible = b'1', keep_alive = b'0',
    always_show = b'1', updater = @sc_creator, update_time = NOW()
WHERE id = @sc_frequency_page_id;

-- 触发限频检查、释放限频占用两个按钮；与查询权限独立授权，普通订单权限不隐含。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, actions.name, actions.permission, 3, actions.sort, @sc_frequency_page_id, '', '', '', NULL,
       0, b'1', b'0', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
FROM (
    SELECT '触发限频检查' name, 'supply-chain:recharge-frequency:retry' permission, 1 sort
    UNION ALL SELECT '释放限频占用', 'supply-chain:recharge-frequency:release', 2
) actions
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu existing
    WHERE CAST(existing.application_code AS BINARY) = CAST(@sc_app AS BINARY)
      AND CAST(existing.permission AS BINARY) = CAST(actions.permission AS BINARY)
      AND existing.deleted = b'0'
);

UPDATE system_menu menu
JOIN (
    SELECT '触发限频检查' name, 'supply-chain:recharge-frequency:retry' permission, 1 sort
    UNION ALL SELECT '释放限频占用', 'supply-chain:recharge-frequency:release', 2
) actions ON CAST(actions.permission AS BINARY) = CAST(menu.permission AS BINARY)
SET menu.name = actions.name, menu.type = 3, menu.sort = actions.sort,
    menu.parent_id = @sc_frequency_page_id, menu.path = '', menu.icon = '', menu.component = '',
    menu.component_name = NULL, menu.status = 0, menu.visible = b'1', menu.keep_alive = b'0',
    menu.always_show = b'1', menu.updater = @sc_creator, menu.update_time = NOW()
WHERE CAST(menu.application_code AS BINARY) = CAST(@sc_app AS BINARY) AND menu.deleted = b'0';
