-- 本变更不承担旧采购商级集成档案的数据迁移。有历史数据时先失败关闭，避免MySQL DDL自动提交后留下半迁移结构。
CREATE TEMPORARY TABLE sc_migration_guard_016 (
    valid TINYINT NOT NULL,
    CONSTRAINT ck_sc_migration_guard_016 CHECK (valid = 1)
);

INSERT INTO sc_migration_guard_016 (valid)
SELECT CASE WHEN EXISTS (SELECT 1 FROM sc_buyer_integration_profile) THEN 0 ELSE 1 END;

DROP TEMPORARY TABLE sc_migration_guard_016;

-- 表中文名：采购项目
-- 核心职责：在同一采购商下承载独立的业务身份、认证边界和版本化集成配置
CREATE TABLE sc_buyer_project (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '采购项目内部主键',
    project_code VARCHAR(32) NOT NULL COMMENT '采购项目全局永久编码',
    project_name VARCHAR(128) NOT NULL COMMENT '采购项目名称',
    buyer_id BIGINT NOT NULL COMMENT '采购商内部主键，逻辑引用sc_buyer.id',
    catalog_mode VARCHAR(16) NOT NULL COMMENT '目录模式：MANAGED中台管理，INDEPENDENT项目独立',
    status SMALLINT NOT NULL COMMENT '项目状态：0草稿，10启用，20暂停，90归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '项目乐观锁版本号',
    created_by BIGINT NULL COMMENT '创建人用户ID',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_by BIGINT NULL COMMENT '最后修改人用户ID',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_buyer_project_code (project_code),
    KEY idx_sc_buyer_project_buyer_status (buyer_id, status),
    CONSTRAINT ck_sc_buyer_project_catalog_mode CHECK (catalog_mode IN ('MANAGED', 'INDEPENDENT')),
    CONSTRAINT ck_sc_buyer_project_status CHECK (status IN (0, 10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购商下独立认证与配置的采购项目';

-- 表中文名：项目能力
-- 核心职责：声明采购项目已开通的受控业务场景
CREATE TABLE sc_project_capability (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目能力内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键，逻辑引用sc_buyer_project.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '项目能力：STANDARD_PRODUCT标准品、O2O到家、INSTORE到店购',
    status SMALLINT NOT NULL COMMENT '能力状态：10启用，20停用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '项目能力乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_capability (project_id, capability_code),
    CONSTRAINT ck_sc_project_capability_code CHECK (capability_code IN ('STANDARD_PRODUCT', 'O2O', 'INSTORE')),
    CONSTRAINT ck_sc_project_capability_status CHECK (status IN (10, 20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购项目已开通业务场景';

-- 表中文名：项目应用身份
-- 核心职责：维护采购项目唯一稳定的入站应用标识
CREATE TABLE sc_project_application (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目应用身份内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键，逻辑引用sc_buyer_project.id',
    app_id VARCHAR(128) NOT NULL COMMENT '项目全局唯一稳定应用标识',
    status SMALLINT NOT NULL COMMENT '应用状态：10启用，20暂停，90归档',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '项目应用身份乐观锁版本号',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_application_project (project_id),
    UNIQUE KEY uk_sc_project_application_app (app_id),
    CONSTRAINT ck_sc_project_application_status CHECK (status IN (10, 20, 90))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购项目入站稳定应用身份';

-- 表中文名：项目应用凭证版本
-- 核心职责：维护项目appKey的密钥系统引用、生效窗口和无停机轮换位
CREATE TABLE sc_project_app_credential_version (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目应用凭证版本内部主键',
    application_id BIGINT NOT NULL COMMENT '项目应用身份内部主键，逻辑引用sc_project_application.id',
    key_version INT NOT NULL COMMENT '调用方携带的密钥版本号',
    secret_ref VARCHAR(512) NOT NULL COMMENT '密钥系统引用，禁止保存明文appKey',
    masked_hint VARCHAR(128) NULL COMMENT '管理页可见的脱敏凭证提示',
    status VARCHAR(16) NOT NULL COMMENT '密钥状态：CURRENT当前，GRACE过渡，REVOKED已吊销，EXPIRED已过期',
    valid_from DATETIME(3) NOT NULL COMMENT '密钥生效时间，UTC',
    valid_to DATETIME(3) NULL COMMENT '密钥失效时间，UTC',
    active_slot VARCHAR(16) NULL COMMENT '活动轮换位：CURRENT当前，GRACE过渡，非活动版本为NULL',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_app_credential_version (application_id, key_version),
    UNIQUE KEY uk_sc_project_app_credential_slot (application_id, active_slot),
    CONSTRAINT ck_sc_project_app_credential_status CHECK (status IN ('CURRENT', 'GRACE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_sc_project_app_credential_slot CHECK (
        (status IN ('CURRENT', 'GRACE') AND active_slot = status)
        OR (status IN ('REVOKED', 'EXPIRED') AND active_slot IS NULL)),
    CONSTRAINT ck_sc_project_app_credential_time CHECK (valid_to IS NULL OR valid_to > valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目appKey密钥引用与无停机轮换版本';

-- 集成档案由“每个采购商唯一”下沉为“每个采购项目唯一”；buyer_id仅保留用于归属校验和审计。
ALTER TABLE sc_buyer_integration_profile
    DROP INDEX uk_sc_buyer_integration_profile_buyer,
    ADD COLUMN project_id BIGINT NOT NULL COMMENT '采购项目内部主键，逻辑引用sc_buyer_project.id' AFTER buyer_id,
    ADD UNIQUE KEY uk_sc_buyer_integration_profile_project (project_id);

-- MEMBER_PROFILE/CASHIER 必须从已发布能力配置取得完整SaaS路由，禁止由projectId或buyerUserId推导。
ALTER TABLE sc_buyer_integration_capability
    DROP CHECK ck_sc_buyer_integration_capability,
    ADD COLUMN source_system VARCHAR(32) NULL COMMENT 'SaaS外部路由来源系统' AFTER contract_version,
    ADD COLUMN external_tenant_id VARCHAR(128) NULL COMMENT 'SaaS外部租户路由标识' AFTER source_system,
    ADD CONSTRAINT ck_sc_buyer_integration_capability CHECK (
        capability_code IN ('MEMBER_PROFILE', 'CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT')),
    ADD CONSTRAINT ck_sc_buyer_integration_external_route CHECK (
        (capability_code IN ('MEMBER_PROFILE', 'CASHIER')
            AND source_system IS NOT NULL
            AND CHAR_LENGTH(TRIM(source_system)) > 0
            AND external_tenant_id IS NOT NULL
            AND CHAR_LENGTH(TRIM(external_tenant_id)) > 0)
        OR (capability_code NOT IN ('MEMBER_PROFILE', 'CASHIER')
            AND source_system IS NULL
            AND external_tenant_id IS NULL));

-- 调用日志能力枚举必须与可调用的版本化能力保持一致。
ALTER TABLE sc_buyer_integration_call_log
    DROP CHECK ck_sc_buyer_integration_call_capability,
    ADD CONSTRAINT ck_sc_buyer_integration_call_capability CHECK (
        capability_code IN ('MEMBER_PROFILE', 'CASHIER', 'ORDER_EVENT', 'FULFILLMENT_EVENT', 'CATALOG_EVENT'));
