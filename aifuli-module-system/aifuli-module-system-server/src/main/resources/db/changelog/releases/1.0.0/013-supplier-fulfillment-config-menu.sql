-- 供应链“供应商履约技术配置”管理端菜单，由 system-server Liquibase 执行。
-- 目标库：system 数据源；不要在 supply-chain 库执行。
-- 设计：docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#81-技术菜单a1新顶级菜单
-- 约束：必须幂等、非破坏性；挂接在 application_code='supply-chain-admin' 根目录（/supplier）下；统一按 supply-chain:admin 显隐。

SET @sc_app = 'supply-chain-admin';
SET @sc_creator = 'system';

-- 获取供应链中台根目录编号（path='/supplier'）
SET @sc_root_id = (
    SELECT MIN(id) FROM system_menu
    WHERE parent_id = 0
      AND CAST(path AS BINARY) = CAST('/supplier' AS BINARY)
      AND deleted = b'0'
);

-- 插入供应商履约技术配置页面菜单（若不存在）
INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_app, '供应商履约技术配置', 'supply-chain:admin', 2, 7, @sc_root_id, 'fulfillment-config',
       'ep:setting', 'supplier/config/index', 'SupplierFulfillmentConfig',
       0, b'1', b'1', b'1', @sc_creator, NOW(), @sc_creator, NOW(), b'0'
WHERE @sc_root_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE CAST(application_code AS BINARY) = CAST(@sc_app AS BINARY)
      AND parent_id = @sc_root_id
      AND CAST(path AS BINARY) = CAST('fulfillment-config' AS BINARY)
      AND deleted = b'0'
);

-- 更新供应商履约技术配置页面菜单到最新设计定义
UPDATE system_menu
SET name = '供应商履约技术配置',
    permission = 'supply-chain:admin',
    type = 2,
    sort = 7,
    parent_id = @sc_root_id,
    path = 'fulfillment-config',
    icon = 'ep:setting',
    component = 'supplier/config/index',
    component_name = 'SupplierFulfillmentConfig',
    status = 0,
    visible = b'1',
    keep_alive = b'1',
    always_show = b'1',
    updater = @sc_creator,
    update_time = NOW()
WHERE CAST(application_code AS BINARY) = CAST(@sc_app AS BINARY)
  AND parent_id = @sc_root_id
  AND CAST(path AS BINARY) = CAST('fulfillment-config' AS BINARY)
  AND deleted = b'0';
