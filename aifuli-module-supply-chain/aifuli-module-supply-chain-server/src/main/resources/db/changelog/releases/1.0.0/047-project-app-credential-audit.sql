-- 表中文名：项目应用凭证操作追加审计
-- 核心职责：与凭证版本状态变更同事务记录开通、轮换和吊销的操作主体与原因
CREATE TABLE sc_project_app_credential_audit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '项目应用凭证操作审计内部主键',
    project_id BIGINT NOT NULL COMMENT '采购项目内部主键，逻辑引用sc_buyer_project.id',
    application_id BIGINT NOT NULL COMMENT '项目应用身份内部主键，逻辑引用sc_project_application.id',
    action VARCHAR(16) NOT NULL COMMENT '凭证操作：ENABLE开通，ROTATE轮换，REVOKE吊销',
    key_version INT NULL COMMENT '本次开通或轮换生成的密钥版本号；吊销时为空',
    operator_id BIGINT NOT NULL COMMENT '操作人用户ID',
    reason VARCHAR(256) NULL COMMENT '吊销原因；开通与轮换时为空',
    occurred_at DATETIME(3) NOT NULL COMMENT '操作时间，Asia/Shanghai',
    PRIMARY KEY (id),
    KEY idx_sc_project_app_credential_audit_project (project_id, occurred_at),
    CONSTRAINT ck_sc_project_app_credential_audit_action CHECK (action IN ('ENABLE', 'ROTATE', 'REVOKE')),
    CONSTRAINT ck_sc_project_app_credential_audit_reason CHECK (
        (action = 'REVOKE' AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0 AND key_version IS NULL)
        OR (action <> 'REVOKE' AND reason IS NULL AND key_version IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目应用凭证操作追加审计';
