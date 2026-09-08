-- 供应链中台动态菜单与细粒度权限。
-- 前置：新版 system-server 已支持 applicationCode=supply-chain-admin 菜单投影。
-- 目标库：system 数据源；不要在 supply-chain-test 执行。
-- 设计：docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#82-供应链菜单树

SET @sc_app = 'supply-chain-admin';
SET @sc_creator = 'system';

-- 根目录；permission=supply-chain:admin 使旧管理员权限在供应链站点展开为整棵菜单。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '供应链中台', 'supply-chain:admin', 1, 10, 0, '/supplier', 'ep:goods', NULL,
       'SupplierCenter', 0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND parent_id = 0 AND path = '/supplier' AND deleted = b'0'
);

SET @sc_root_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND parent_id = 0 AND path = '/supplier' AND deleted = b'0'
);

-- 将重复执行时的既有根目录收敛到设计值；不按 permission 更新，避免影响其它应用的旧 admin 节点。
UPDATE system_menu
SET name = '供应链中台', permission = 'supply-chain:admin', type = 1, sort = 10,
    icon = 'ep:goods', component = NULL, component_name = 'SupplierCenter', status = 0,
    visible = b'1', keep_alive = b'1', always_show = b'1', updater = @sc_creator, update_time = NOW()
WHERE id = @sc_root_id;

-- 供应商页面。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '供应商管理', 'supply-chain:supplier:query', 2, 1, @sc_root_id, 'manage', 'ep:user',
       'supplier/manage/index', 'SupplierManage', 0, b'1', b'1', b'1',
       @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:supplier:query' AND deleted = b'0'
);

SET @sc_supplier_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:supplier:query' AND deleted = b'0'
);

UPDATE system_menu
SET name = '供应商管理', type = 2, sort = 1, parent_id = @sc_root_id, path = 'manage',
    icon = 'ep:user', component = 'supplier/manage/index', component_name = 'SupplierManage',
    status = 0, visible = b'1', keep_alive = b'1', always_show = b'1',
    updater = @sc_creator, update_time = NOW()
WHERE id = @sc_supplier_page_id;

-- 分销管理中间目录。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '分销管理', '', 1, 2, @sc_root_id, 'distribution', 'ep:share', NULL,
       'SupplierDistribution', 0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND parent_id = @sc_root_id AND path = 'distribution' AND deleted = b'0'
);

SET @sc_distribution_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND parent_id = @sc_root_id AND path = 'distribution' AND deleted = b'0'
);

UPDATE system_menu
SET name = '分销管理', permission = '', type = 1, sort = 2, parent_id = @sc_root_id,
    path = 'distribution', icon = 'ep:share', component = NULL,
    component_name = 'SupplierDistribution', status = 0, visible = b'1', keep_alive = b'1',
    always_show = b'1', updater = @sc_creator, update_time = NOW()
WHERE id = @sc_distribution_id;

-- 分销商页面。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '分销商管理', 'supply-chain:buyer:query', 2, 1, @sc_distribution_id, 'distributor',
       'ep:avatar', 'supplier/distribution/distributor/index', 'SupplierDistributor',
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:buyer:query' AND deleted = b'0'
);

SET @sc_buyer_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:buyer:query' AND deleted = b'0'
);

UPDATE system_menu
SET name = '分销商管理', type = 2, sort = 1, parent_id = @sc_distribution_id,
    path = 'distributor', icon = 'ep:avatar', component = 'supplier/distribution/distributor/index',
    component_name = 'SupplierDistributor', status = 0, visible = b'1', keep_alive = b'1',
    always_show = b'1', updater = @sc_creator, update_time = NOW()
WHERE id = @sc_buyer_page_id;

-- 项目页面。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '项目管理', 'supply-chain:project:query', 2, 2, @sc_distribution_id, 'project',
       'ep:folder-opened', 'supplier/distribution/project/index', 'SupplierDistributionProject',
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:project:query' AND deleted = b'0'
);

SET @sc_project_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:project:query' AND deleted = b'0'
);

UPDATE system_menu
SET name = '项目管理', type = 2, sort = 2, parent_id = @sc_distribution_id, path = 'project',
    icon = 'ep:folder-opened', component = 'supplier/distribution/project/index',
    component_name = 'SupplierDistributionProject', status = 0, visible = b'1', keep_alive = b'1',
    always_show = b'1', updater = @sc_creator, update_time = NOW()
WHERE id = @sc_project_page_id;

-- 三个页面各自的动作按钮。页面 query 权限已经记录在 PAGE 行中。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, actions.name, actions.permission, 3, actions.sort, actions.parent_id, '', '', '', NULL,
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
FROM (
    SELECT '供应商新增' name, 'supply-chain:supplier:create' permission, 1 sort, @sc_supplier_page_id parent_id
    UNION ALL SELECT '供应商修改', 'supply-chain:supplier:update', 2, @sc_supplier_page_id
    UNION ALL SELECT '供应商商品移除', 'supply-chain:supplier:delete', 3, @sc_supplier_page_id
    UNION ALL SELECT '供应商状态变更', 'supply-chain:supplier:update-status', 4, @sc_supplier_page_id
    UNION ALL SELECT '分销商新增', 'supply-chain:buyer:create', 1, @sc_buyer_page_id
    UNION ALL SELECT '分销商修改', 'supply-chain:buyer:update', 2, @sc_buyer_page_id
    UNION ALL SELECT '分销商品移除', 'supply-chain:buyer:delete', 3, @sc_buyer_page_id
    UNION ALL SELECT '分销商状态变更', 'supply-chain:buyer:update-status', 4, @sc_buyer_page_id
    UNION ALL SELECT '项目新增', 'supply-chain:project:create', 1, @sc_project_page_id
    UNION ALL SELECT '项目修改', 'supply-chain:project:update', 2, @sc_project_page_id
    UNION ALL SELECT '项目商品移除', 'supply-chain:project:delete', 3, @sc_project_page_id
    UNION ALL SELECT '项目状态变更', 'supply-chain:project:update-status', 4, @sc_project_page_id
) actions
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu existing
    WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY actions.permission
      AND existing.deleted = b'0'
);

-- 重复执行时同步按钮名称、顺序和父页面，但不触碰角色菜单关系。
UPDATE system_menu menu
JOIN (
    SELECT '供应商新增' name, 'supply-chain:supplier:create' permission, 1 sort, @sc_supplier_page_id parent_id
    UNION ALL SELECT '供应商修改', 'supply-chain:supplier:update', 2, @sc_supplier_page_id
    UNION ALL SELECT '供应商商品移除', 'supply-chain:supplier:delete', 3, @sc_supplier_page_id
    UNION ALL SELECT '供应商状态变更', 'supply-chain:supplier:update-status', 4, @sc_supplier_page_id
    UNION ALL SELECT '分销商新增', 'supply-chain:buyer:create', 1, @sc_buyer_page_id
    UNION ALL SELECT '分销商修改', 'supply-chain:buyer:update', 2, @sc_buyer_page_id
    UNION ALL SELECT '分销商品移除', 'supply-chain:buyer:delete', 3, @sc_buyer_page_id
    UNION ALL SELECT '分销商状态变更', 'supply-chain:buyer:update-status', 4, @sc_buyer_page_id
    UNION ALL SELECT '项目新增', 'supply-chain:project:create', 1, @sc_project_page_id
    UNION ALL SELECT '项目修改', 'supply-chain:project:update', 2, @sc_project_page_id
    UNION ALL SELECT '项目商品移除', 'supply-chain:project:delete', 3, @sc_project_page_id
    UNION ALL SELECT '项目状态变更', 'supply-chain:project:update-status', 4, @sc_project_page_id
) actions ON BINARY actions.permission = BINARY menu.permission
SET menu.name = actions.name, menu.type = 3, menu.sort = actions.sort,
    menu.parent_id = actions.parent_id, menu.path = '', menu.icon = '', menu.component = '',
    menu.component_name = NULL, menu.status = 0, menu.visible = b'1', menu.keep_alive = b'1',
    menu.always_show = b'1', menu.updater = @sc_creator, menu.update_time = NOW()
WHERE BINARY menu.application_code = BINARY @sc_app AND menu.deleted = b'0';
