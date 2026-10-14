-- 用户指定的现有超级管理员 admin 显式录入授权；只在 system 库执行。
-- 设计：docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#52-当前-admin-的显式授权迁移
-- 超管已有全量权限，本迁移仅补可核验关系，不创建/提升用户或角色，不修改其它授权。
-- 从有效用户角色绑定动态解析真实租户与角色，禁止假定 user_id/role_id/tenant_id=1。
-- 关联表没有组合唯一键：DISTINCT 消除重复绑定，NOT EXISTS 按有效租户/角色/菜单判重。
-- Liquibase SQL 不触发服务层 CacheEvict；发布后重新拉取 admin 的菜单/权限信息。

INSERT INTO system_role_menu
    (role_id, menu_id, tenant_id, creator, create_time, updater, update_time, deleted)
SELECT DISTINCT role.id, menu.id, role.tenant_id, 'system', NOW(), 'system', NOW(), b'0'
FROM system_users admin_user
JOIN system_user_role user_role
  ON user_role.user_id = admin_user.id AND user_role.tenant_id = admin_user.tenant_id
  AND user_role.deleted = b'0'
JOIN system_role role
  ON role.id = user_role.role_id AND role.tenant_id = admin_user.tenant_id
  AND role.status = 0 AND role.deleted = b'0'
JOIN system_menu menu
  ON CAST(menu.application_code AS BINARY) = CAST('supply-chain-admin' AS BINARY)
  AND CAST(menu.permission AS BINARY) = CAST('supply-chain:order:create' AS BINARY)
  AND menu.type = 3 AND menu.status = 0 AND menu.deleted = b'0'
WHERE CAST(admin_user.username AS BINARY) = CAST('admin' AS BINARY)
  AND admin_user.status = 0 AND admin_user.deleted = b'0'
  AND CAST(role.code AS BINARY) = CAST('super_admin' AS BINARY)
  AND NOT EXISTS (
    SELECT 1 FROM system_role_menu existing
    WHERE existing.tenant_id = role.tenant_id AND existing.role_id = role.id
      AND existing.menu_id = menu.id AND existing.deleted = b'0'
  );
