-- INSTORE 入口凭证项目化与撤销状态前向迁移。
-- 设计依据：
-- docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#7-Liquibase-018
-- docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#8-凭证状态机

-- 历史凭证的签发上下文无法证明 MEMBER_PROFILE 档案版本与 companyCode 快照；
-- 每条凭证必须能通过 binding_id 关联到 project_id 非空的稳定绑定
-- （绑定主键唯一，同一凭证不可能关联多条绑定）。
-- 任一条件不满足则在任何永久 DDL 之前失败关闭，避免 MySQL DDL 自动提交半迁移。
CREATE TEMPORARY TABLE sc_migration_guard_018 (
    valid TINYINT NOT NULL,
    CONSTRAINT ck_sc_migration_guard_018 CHECK (valid = 1)
);

INSERT INTO sc_migration_guard_018 (valid)
SELECT CASE
    WHEN EXISTS (
        SELECT 1
        FROM sc_instore_entry_credential iec
        LEFT JOIN sc_instore_channel_user_binding iub ON iub.id = iec.binding_id
        WHERE iub.id IS NULL OR iub.project_id IS NULL
    )
    THEN 0
    ELSE 1
END;

DROP TEMPORARY TABLE sc_migration_guard_018;

-- 凭证固化采购项目归属、签发时冻结的 MEMBER_PROFILE 档案版本和 companyCode 快照；
-- 撤销事实与稳定撤销原因同列保存，禁止保存外部异常正文。
ALTER TABLE sc_instore_entry_credential
    ADD COLUMN project_id BIGINT NULL
        COMMENT '凭证所属采购项目内部主键' AFTER binding_id,
    ADD COLUMN member_profile_version_id BIGINT NULL
        COMMENT '签发时冻结的MEMBER_PROFILE档案版本内部主键' AFTER project_id,
    ADD COLUMN company_code VARCHAR(64) NULL
        COMMENT '接口①返回和旅通入口使用的企业组快照' AFTER member_profile_version_id,
    ADD COLUMN revoked_at DATETIME(3) NULL
        COMMENT '凭证撤销时间，UTC' AFTER used_at,
    ADD COLUMN revoke_reason VARCHAR(64) NULL
        COMMENT '稳定撤销原因' AFTER revoked_at;

UPDATE sc_instore_entry_credential iec
JOIN sc_instore_channel_user_binding iub ON iub.id = iec.binding_id
SET iec.project_id = iub.project_id;

-- 旧状态 CHECK 不含 40，必须先移除再转换历史在途凭证。
ALTER TABLE sc_instore_entry_credential
    DROP CHECK ck_sc_instore_credential_status;

-- 历史 ACTIVE/PROCESSING 无法证明签发时冻结的会员档案版本和 companyCode，
-- 统一失败关闭转为 REVOKED 并写入 MIGRATION_UNFROZEN_ROUTE；
-- USED/EXPIRED 保持终态，快照列允许为空且不再参与业务读取。
UPDATE sc_instore_entry_credential
SET status = 40,
    revoked_at = UTC_TIMESTAMP(3),
    revoke_reason = 'MIGRATION_UNFROZEN_ROUTE',
    processing_started_at = NULL,
    updated_at = UTC_TIMESTAMP(3),
    version = version + 1
WHERE status IN (10, 15);

ALTER TABLE sc_instore_entry_credential
    ADD CONSTRAINT ck_sc_instore_credential_status CHECK (status IN (10, 15, 20, 30, 40));

ALTER TABLE sc_instore_entry_credential
    MODIFY COLUMN project_id BIGINT NOT NULL
        COMMENT '凭证所属采购项目内部主键',
    MODIFY COLUMN status SMALLINT NOT NULL
        COMMENT '凭证状态：10 ACTIVE，15 PROCESSING，20 USED，30 EXPIRED，40 REVOKED',
    ADD KEY idx_sc_instore_credential_project (project_id, status, expires_at),
    ADD KEY idx_sc_instore_credential_member_profile (member_profile_version_id, status);

-- 新凭证从 ACTIVE 创建，必须冻结会员档案版本和 companyCode；
-- 撤销事实与状态互斥：REVOKED 必须有撤销时间与原因，其它状态必须为空。
ALTER TABLE sc_instore_entry_credential
    ADD CONSTRAINT ck_sc_instore_credential_snapshot CHECK (
        status NOT IN (10, 15)
        OR (member_profile_version_id IS NOT NULL AND company_code IS NOT NULL)
    );

ALTER TABLE sc_instore_entry_credential
    ADD CONSTRAINT ck_sc_instore_credential_revoke CHECK (
        (status = 40 AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
        OR (status <> 40 AND revoked_at IS NULL AND revoke_reason IS NULL)
    );
