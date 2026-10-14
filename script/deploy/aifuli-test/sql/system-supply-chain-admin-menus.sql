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

-- 供应商、分销商、项目三个页面各自的动作按钮。页面 query 权限已经记录在 PAGE 行中。
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

-- 平台订单页面；查询权限由 PAGE 行承载，真实标准订单录入使用独立按钮权限。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '订单管理', 'supply-chain:order:query', 2, 4, @sc_root_id, 'orders', 'ep:list',
       'supplier/order/index', 'SupplyChainOrder', 0, b'1', b'1', b'1',
       @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND BINARY permission = BINARY 'supply-chain:order:query' AND deleted = b'0'
);

SET @sc_order_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND BINARY permission = BINARY 'supply-chain:order:query' AND deleted = b'0'
);

UPDATE system_menu
SET name = '订单管理', type = 2, sort = 4, parent_id = @sc_root_id, path = 'orders',
    icon = 'ep:list', component = 'supplier/order/index', component_name = 'SupplyChainOrder',
    status = 0, visible = b'1', keep_alive = b'1', always_show = b'1',
    updater = @sc_creator, update_time = NOW()
WHERE id = @sc_order_page_id;

-- 管理员真实标准订单录入；只准备幂等种子，不自动赋权或执行共享库部署。
-- 设计：docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#5-数据与交付
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '录入订单', 'supply-chain:order:create', 3, 2, @sc_order_page_id, '', '', '', NULL,
       0, b'1', b'0', b'0', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE @sc_order_page_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM system_menu existing WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY 'supply-chain:order:create' AND existing.deleted = b'0'
);

UPDATE system_menu
SET name = '录入订单', type = 3, sort = 2, parent_id = @sc_order_page_id,
    path = '', icon = '', component = '', component_name = NULL, status = 0,
    visible = b'1', keep_alive = b'0', always_show = b'0',
    updater = @sc_creator, update_time = NOW()
WHERE @sc_order_page_id IS NOT NULL AND BINARY application_code = BINARY @sc_app
  AND BINARY permission = BINARY 'supply-chain:order:create' AND deleted = b'0';

-- 商品中心目录及四个管理页面。电影、点餐等商品类型共用平台商品页面，不建立专属菜单。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '商品中心', '', 1, 3, @sc_root_id, 'product-center', 'ep:goods-filled', NULL,
       'SupplierProductCenter', 0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu WHERE BINARY application_code = BINARY @sc_app
      AND parent_id = @sc_root_id AND path = 'product-center' AND deleted = b'0'
);

SET @sc_product_center_id = (
    SELECT MIN(id) FROM system_menu WHERE BINARY application_code = BINARY @sc_app
      AND parent_id = @sc_root_id AND path = 'product-center' AND deleted = b'0'
);

INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, pages.name, pages.permission, 2, pages.sort, @sc_product_center_id, pages.path,
       pages.icon, pages.component, pages.component_name, 0, b'1', b'1', b'1',
       @sc_creator, NOW(), @sc_creator, NOW(), b'0'
FROM (
    SELECT '平台商品' name, 'supply-chain:product:query' permission, 1 sort, 'products' path,
           'ep:shopping-bag' icon, 'supplier/product-center/product/index' component,
           'SupplierPlatformProduct' component_name
    UNION ALL SELECT '商品分类', 'supply-chain:category:query', 2, 'categories', 'ep:menu',
           'supplier/product-center/category/index', 'SupplierProductCategory'
    UNION ALL SELECT '商品品牌', 'supply-chain:brand:query', 3, 'brands', 'ep:price-tag',
           'supplier/product-center/brand/index', 'SupplierProductBrand'
    UNION ALL SELECT '商品类型', 'supply-chain:product-type:query', 4, 'product-types', 'ep:grid',
           'supplier/product-center/product-type/index', 'SupplierProductType'
) pages
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu existing WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY pages.permission AND existing.deleted = b'0'
);

SET @sc_product_page_id = (SELECT MIN(id) FROM system_menu WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:product:query' AND deleted = b'0');
SET @sc_category_page_id = (SELECT MIN(id) FROM system_menu WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:category:query' AND deleted = b'0');
SET @sc_brand_page_id = (SELECT MIN(id) FROM system_menu WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:brand:query' AND deleted = b'0');
SET @sc_product_type_page_id = (SELECT MIN(id) FROM system_menu WHERE BINARY application_code = BINARY @sc_app AND permission = 'supply-chain:product-type:query' AND deleted = b'0');

-- 商品中心动作权限。查询权限由 PAGE 行承载。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, actions.name, actions.permission, 3, actions.sort, actions.parent_id, '', '', '', NULL,
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
FROM (
    SELECT '商品新增' name, 'supply-chain:product:create' permission, 1 sort, @sc_product_page_id parent_id
    UNION ALL SELECT '商品修改', 'supply-chain:product:update', 2, @sc_product_page_id
    UNION ALL SELECT '商品状态变更', 'supply-chain:product:update-status', 3, @sc_product_page_id
    UNION ALL SELECT '商品删除', 'supply-chain:product:delete', 4, @sc_product_page_id
    UNION ALL SELECT '分类新增', 'supply-chain:category:create', 1, @sc_category_page_id
    UNION ALL SELECT '分类修改', 'supply-chain:category:update', 2, @sc_category_page_id
    UNION ALL SELECT '分类状态变更', 'supply-chain:category:update-status', 3, @sc_category_page_id
    UNION ALL SELECT '分类删除', 'supply-chain:category:delete', 4, @sc_category_page_id
    UNION ALL SELECT '品牌新增', 'supply-chain:brand:create', 1, @sc_brand_page_id
    UNION ALL SELECT '品牌修改', 'supply-chain:brand:update', 2, @sc_brand_page_id
    UNION ALL SELECT '品牌状态变更', 'supply-chain:brand:update-status', 3, @sc_brand_page_id
    UNION ALL SELECT '品牌删除', 'supply-chain:brand:delete', 4, @sc_brand_page_id
    UNION ALL SELECT '类型新增', 'supply-chain:product-type:create', 1, @sc_product_type_page_id
    UNION ALL SELECT '类型修改', 'supply-chain:product-type:update', 2, @sc_product_type_page_id
    UNION ALL SELECT '类型状态变更', 'supply-chain:product-type:update-status', 3, @sc_product_type_page_id
    UNION ALL SELECT '类型删除', 'supply-chain:product-type:delete', 4, @sc_product_type_page_id
) actions
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu existing WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY actions.permission AND existing.deleted = b'0'
);

-- ===== 订单导出与订单智能路由（2026-09-23 订单前端契约对齐与路由规则 PRD 模型重构）=====
-- 设计：docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#6-菜单与权限

-- 订单管理页动作按钮：订单导出。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '订单导出', 'supply-chain:order:export', 3, 1, @sc_order_page_id, '', '', '', NULL,
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu existing WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY 'supply-chain:order:export' AND existing.deleted = b'0'
);

UPDATE system_menu
SET name = '订单导出', type = 3, sort = 1, parent_id = @sc_order_page_id,
    path = '', icon = '', component = '', component_name = NULL, status = 0,
    visible = b'1', keep_alive = b'1', always_show = b'1',
    updater = @sc_creator, update_time = NOW()
WHERE BINARY application_code = BINARY @sc_app
  AND BINARY permission = BINARY 'supply-chain:order:export' AND deleted = b'0';

-- 订单智能路由中间目录。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '订单智能路由', '', 1, 5, @sc_root_id, 'routing', 'ep:guide', NULL,
       'SupplyChainRouting', 0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND BINARY parent_id = BINARY @sc_root_id AND BINARY path = BINARY 'routing' AND deleted = b'0'
);

SET @sc_routing_dir_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND BINARY parent_id = BINARY @sc_root_id AND BINARY path = BINARY 'routing' AND deleted = b'0'
);

UPDATE system_menu
SET name = '订单智能路由', permission = '', type = 1, sort = 5, parent_id = @sc_root_id,
    path = 'routing', icon = 'ep:guide', component = NULL, component_name = 'SupplyChainRouting',
    status = 0, visible = b'1', keep_alive = b'1', always_show = b'1',
    updater = @sc_creator, update_time = NOW()
WHERE id = @sc_routing_dir_id;

-- 路由规则页面。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '路由规则', 'supply-chain:routing-rule:query', 2, 1, @sc_routing_dir_id, 'rule',
       'ep:set-up', 'supplier/routing/rule/index', 'SupplyChainRoutingRule',
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND BINARY permission = BINARY 'supply-chain:routing-rule:query' AND deleted = b'0'
);

SET @sc_routing_rule_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND BINARY permission = BINARY 'supply-chain:routing-rule:query' AND deleted = b'0'
);

UPDATE system_menu
SET name = '路由规则', type = 2, sort = 1, parent_id = @sc_routing_dir_id, path = 'rule',
    icon = 'ep:set-up', component = 'supplier/routing/rule/index', component_name = 'SupplyChainRoutingRule',
    status = 0, visible = b'1', keep_alive = b'1', always_show = b'1',
    updater = @sc_creator, update_time = NOW()
WHERE id = @sc_routing_rule_page_id;

-- 路由规则动作按钮。查询权限由 PAGE 行承载。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, actions.name, actions.permission, 3, actions.sort, actions.parent_id, '', '', '', NULL,
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
FROM (
    SELECT '规则新增' name, 'supply-chain:routing-rule:create' permission, 1 sort, @sc_routing_rule_page_id parent_id
    UNION ALL SELECT '规则修改', 'supply-chain:routing-rule:update', 2, @sc_routing_rule_page_id
    UNION ALL SELECT '规则删除', 'supply-chain:routing-rule:delete', 3, @sc_routing_rule_page_id
    UNION ALL SELECT '规则启停', 'supply-chain:routing-rule:update-status', 4, @sc_routing_rule_page_id
) actions
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu existing WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY actions.permission AND existing.deleted = b'0'
);

UPDATE system_menu menu
JOIN (
    SELECT '规则新增' name, 'supply-chain:routing-rule:create' permission, 1 sort, @sc_routing_rule_page_id parent_id
    UNION ALL SELECT '规则修改', 'supply-chain:routing-rule:update', 2, @sc_routing_rule_page_id
    UNION ALL SELECT '规则删除', 'supply-chain:routing-rule:delete', 3, @sc_routing_rule_page_id
    UNION ALL SELECT '规则启停', 'supply-chain:routing-rule:update-status', 4, @sc_routing_rule_page_id
) actions ON BINARY actions.permission = BINARY menu.permission
SET menu.name = actions.name, menu.type = 3, menu.sort = actions.sort,
    menu.parent_id = actions.parent_id, menu.path = '', menu.icon = '', menu.component = '',
    menu.component_name = NULL, menu.status = 0, menu.visible = b'1', menu.keep_alive = b'1',
    menu.always_show = b'1', menu.updater = @sc_creator, menu.update_time = NOW()
WHERE BINARY menu.application_code = BINARY @sc_app AND menu.deleted = b'0';

-- ===== 内部运营直充限频监控 =====
-- 本块已迁出：改由 system-server 自身 Liquibase 变更集 014-supply-chain-recharge-frequency-menus 部署，
-- 见 aifuli-module-system/aifuli-module-system-server/src/main/resources/db/changelog/releases/1.0.0/。
-- 迁移原因：本脚本是人工执行的一次性迁移工具，2026-10-01 新增该块后测试库从未重跑，导致 system_menu 缺行、
-- 前端侧栏看不到菜单。菜单唯一事实来源改为 Liquibase，本脚本不再重复维护同一份菜单定义。
-- 设计：docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#84-新增供应链菜单的-liquibase-部署

-- ===== 供应商履约技术配置（A1 新顶级技术菜单）=====
-- 设计：docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#81-技术菜单a1新顶级菜单
-- H2 MVP：菜单与按钮统一按 supply-chain:admin 显隐；后端所有端点挂 @PreAuthorize(@ss.hasPermission('supply-chain:admin'))。
-- 技术配置（连接器/凭据/能力/映射）独立成顶级页面，与业务侧「供应商管理」分离，避免技术配置与业务配置混在一起。
-- 入口按 supplierCode 维度组织：选供应商 → 4 子页（el-tabs），无需为每个子页单独建菜单。
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '供应商履约技术配置', 'supply-chain:admin', 2, 7, @sc_root_id, 'fulfillment-config',
       'ep:setting', 'supplier/config/index', 'SupplierFulfillmentConfig',
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND parent_id = @sc_root_id AND BINARY path = BINARY 'fulfillment-config' AND deleted = b'0'
);

SET @sc_fulfillment_config_page_id = (
    SELECT MIN(id) FROM system_menu
    WHERE BINARY application_code = BINARY @sc_app
      AND parent_id = @sc_root_id AND BINARY path = BINARY 'fulfillment-config' AND deleted = b'0'
);

UPDATE system_menu
SET name = '供应商履约技术配置', permission = 'supply-chain:admin', type = 2, sort = 7,
    parent_id = @sc_root_id, path = 'fulfillment-config', icon = 'ep:setting',
    component = 'supplier/config/index', component_name = 'SupplierFulfillmentConfig',
    status = 0, visible = b'1', keep_alive = b'1', always_show = b'1',
    updater = @sc_creator, update_time = NOW()
WHERE id = @sc_fulfillment_config_page_id;

-- 人工异常 Case 两步命令：复用订单页面与现有 RBAC，不新增部门权限模型、不自动赋予角色。
-- 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#6-存量人工单的受控入口
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, buttons.name, buttons.permission, 3, buttons.sort, @sc_order_page_id, '', '', '', NULL,
       0, b'1', b'0', b'0', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
FROM (
    SELECT '异常Case查询' AS name, 'supply-chain:fulfillment-case:query' AS permission, 20 AS sort
    UNION ALL SELECT '核实原单', 'supply-chain:fulfillment-case:verify', 21
    UNION ALL SELECT '确认失败关单', 'supply-chain:fulfillment-case:confirm-failure', 22
) buttons
WHERE @sc_order_page_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM system_menu existing
    WHERE BINARY existing.application_code = BINARY @sc_app
      AND BINARY existing.permission = BINARY buttons.permission AND existing.deleted = b'0'
);
