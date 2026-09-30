-- INSTORE 采购项目身份与收银台档案版本快照前向迁移。
-- 设计依据：
-- docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#9-订单用户与幂等
-- docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#51-saas-商城收银台编排

-- 014 的来源租户四元组无法无损推导 projectId；存在历史 INSTORE 事实时失败关闭，
-- 由发布流程先完成显式项目归属迁移。守卫必须位于任何永久 DDL 之前，避免 MySQL DDL 自动提交半迁移。
CREATE TEMPORARY TABLE sc_migration_guard_017 (
    valid TINYINT NOT NULL,
    CONSTRAINT ck_sc_migration_guard_017 CHECK (valid = 1)
);

INSERT INTO sc_migration_guard_017 (valid)
SELECT CASE
    WHEN EXISTS (SELECT 1 FROM sc_instore_channel_user_binding)
      OR EXISTS (SELECT 1 FROM sc_instore_channel_order)
    THEN 0
    ELSE 1
END;

DROP TEMPORARY TABLE sc_migration_guard_017;

-- 稳定会员绑定以项目为业务作用域。source_system/external_tenant_id 继续保留为
-- 已发布 MEMBER_PROFILE 路由的不可变审计快照，但不再承担业务身份唯一性。
ALTER TABLE sc_instore_channel_user_binding
    ADD COLUMN project_id BIGINT NOT NULL
        COMMENT '采购项目内部主键，与buyer_user_id共同确定会员业务身份' AFTER buyer_id,
    DROP INDEX uk_sc_instore_user_binding_tenant,
    ADD UNIQUE KEY uk_sc_instore_user_binding_project
        (project_id, buyer_user_id, channel_code),
    ADD KEY idx_sc_instore_user_binding_project_status
        (project_id, channel_code, status);

-- 订单受理时同时固化项目业务归属与 CASHIER 档案版本。回调、主动查询及 Timer
-- 只能使用这两个字段解析原 SaaS 收银台路由，禁止从渠道请求覆盖 externalTenantId。
ALTER TABLE sc_instore_channel_order
    ADD COLUMN project_id BIGINT NOT NULL
        COMMENT '受理时固化的采购项目内部主键' AFTER buyer_id,
    ADD COLUMN cashier_profile_version_id BIGINT NOT NULL
        COMMENT '受理时固化的CASHIER集成档案版本内部主键' AFTER project_id,
    ADD KEY idx_sc_instore_order_project_user
        (project_id, buyer_user_id, created_at),
    ADD KEY idx_sc_instore_order_cashier_profile
        (cashier_profile_version_id);

