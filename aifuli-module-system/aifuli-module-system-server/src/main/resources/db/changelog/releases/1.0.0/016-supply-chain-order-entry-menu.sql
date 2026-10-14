-- 管理端真实标准订单录入按钮，由 system-server Liquibase 写入 system 库。
-- 设计：docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#51-system-菜单迁移
-- 只维护本应用的录入按钮，不改订单页面、其它应用、软删除历史或角色关系。
-- YAML 前置条件保证启用订单页面唯一、录入按钮至多一行；不硬编码菜单 id。

SET @sc_entry_app = 'supply-chain-admin';
SET @sc_entry_creator = 'system';
SET @sc_entry_order_page_id = (
    SELECT MIN(page.id) FROM system_menu page JOIN system_menu root ON root.id = page.parent_id
    WHERE CAST(page.application_code AS BINARY) = CAST(@sc_entry_app AS BINARY)
      AND CAST(page.permission AS BINARY) = CAST('supply-chain:order:query' AS BINARY)
      AND page.type = 2 AND page.status = 0 AND page.deleted = b'0'
      AND CAST(root.application_code AS BINARY) = CAST(@sc_entry_app AS BINARY)
      AND CAST(root.permission AS BINARY) = CAST('supply-chain:admin' AS BINARY)
      AND root.parent_id = 0 AND root.path = '/supplier'
      AND root.type = 1 AND root.status = 0 AND root.deleted = b'0'
);

INSERT INTO system_menu
    (application_code, name, permission, type, sort, parent_id, path, icon, component, component_name,
     status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT @sc_entry_app, '录入订单', 'supply-chain:order:create', 3, 2, @sc_entry_order_page_id,
       '', '', '', NULL, 0, b'1', b'0', b'0', @sc_entry_creator, NOW(), @sc_entry_creator, NOW(), b'0'
WHERE @sc_entry_order_page_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM system_menu
    WHERE CAST(application_code AS BINARY) = CAST(@sc_entry_app AS BINARY)
      AND CAST(permission AS BINARY) = CAST('supply-chain:order:create' AS BINARY)
      AND deleted = b'0'
);

UPDATE system_menu
SET name = '录入订单', type = 3, sort = 2, parent_id = @sc_entry_order_page_id,
    path = '', icon = '', component = '', component_name = NULL, status = 0,
    visible = b'1', keep_alive = b'0', always_show = b'0',
    updater = @sc_entry_creator, update_time = NOW()
WHERE @sc_entry_order_page_id IS NOT NULL
  AND CAST(application_code AS BINARY) = CAST(@sc_entry_app AS BINARY)
  AND CAST(permission AS BINARY) = CAST('supply-chain:order:create' AS BINARY)
  AND deleted = b'0';
