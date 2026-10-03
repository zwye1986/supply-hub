-- D1 每供应商至多一个已发布连接器（056，C3 强制机制）：
-- 生成列 published_flag 以 current_version_id 非空为条件（publish 置位、retire 清空），
-- 而非 status=10——status=10 在 createConnectorDraft 建草稿时即置位，同供应商多草稿都 status=10，
-- 用它驱动唯一键会误伤草稿。已发布行 published_flag=1，草稿/退役行为 NULL（MySQL 唯一键允许多行 NULL）。
-- (supplier_id, published_flag) 唯一 ⇒ 每供应商至多一行已发布连接器。
-- 破坏性 DDL（加唯一键）前的 HALT 预条件检查见 db.changelog-1.0.0.yaml 056 变更集：
-- 存量若已有供应商持有 2+ 已发布连接器则 HALT，保留原数据供离线核实，不自动修复。
-- 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#55-d1-published_flag-唯一键变更集-056
ALTER TABLE sc_supplier_connector
    ADD COLUMN published_flag TINYINT GENERATED ALWAYS AS (
        CASE WHEN current_version_id IS NOT NULL THEN 1 END
    ) STORED COMMENT '已发布标记：current_version_id非空为1，否则NULL；驱动每供应商至多一个已发布连接器的唯一键',
    ADD UNIQUE KEY uk_sc_connector_supplier_published (supplier_id, published_flag);
