-- 表中文名：项目能力变更追加审计
-- 核心职责：与项目能力状态同事务追加操作主体、变更版本和关闭原因，不修改既有能力或授予任何项目。
CREATE TABLE sc_project_capability_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目能力操作审计内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键，逻辑引用sc_buyer_project.id',
    capability_code VARCHAR(32) NOT NULL COMMENT '项目能力：STANDARD_PRODUCT标准品、O2O到家、INSTORE到店购',
    before_status SMALLINT NULL COMMENT '变更前能力状态：10启用，20停用；首次开通为空',
    after_status SMALLINT NOT NULL COMMENT '变更后能力状态：10启用，20停用',
    capability_version BIGINT NOT NULL COMMENT '变更后的项目能力乐观锁版本号',
    operator_id BIGINT NOT NULL COMMENT '操作人用户ID',
    reason VARCHAR(256) NULL COMMENT '关闭原因；开启时为空',
    occurred_at DATETIME(3) NOT NULL COMMENT '操作时间，Asia/Shanghai',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sc_project_capability_audit_version (project_id, capability_code, capability_version),
    CONSTRAINT ck_sc_project_capability_audit_code CHECK (capability_code IN ('STANDARD_PRODUCT', 'O2O', 'INSTORE')),
    CONSTRAINT ck_sc_project_capability_audit_version CHECK (capability_version >= 0),
    CONSTRAINT ck_sc_project_capability_audit_transition CHECK (
        (after_status = 10 AND (before_status IS NULL OR before_status = 20) AND reason IS NULL)
        OR (after_status = 20 AND before_status IS NOT NULL AND before_status = 10
            AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目能力变更追加审计';
