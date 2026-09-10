-- 项目类型按 PRD 8.3.1 增加六个可选值；历史值保留在 CHECK 中，仅用于兼容已有数据。
-- 设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#421-项目列表数据字典

ALTER TABLE sc_buyer_project DROP CHECK ck_sc_buyer_project_type;

ALTER TABLE sc_buyer_project
    MODIFY COLUMN project_type VARCHAR(32) NULL COMMENT '项目类型封闭枚举';

ALTER TABLE sc_buyer_project
    ADD CONSTRAINT ck_sc_buyer_project_type CHECK (
        project_type IS NULL OR project_type IN (
            'HOLIDAY_BENEFIT', 'POINTS_REDEMPTION', 'EMPLOYEE_BENEFIT',
            'CUSTOMER_REWARD', 'FLEXIBLE_BENEFIT', 'OTHER',
            'WELFARE', 'BENEFIT', 'CAMPAIGN', 'SUBSIDY', 'GROUP'));
