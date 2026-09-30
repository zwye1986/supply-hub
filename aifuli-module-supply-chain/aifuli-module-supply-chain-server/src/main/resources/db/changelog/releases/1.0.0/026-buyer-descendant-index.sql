-- 分销层级按 parent_buyer_id 逐层查询，并以创建时间和主键倒序稳定展示全部下级。
-- 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#61-组织树。
ALTER TABLE sc_buyer
    ADD KEY idx_sc_buyer_parent_created (parent_buyer_id, created_at, id);
