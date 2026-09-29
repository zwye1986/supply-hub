package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目应用凭证操作追加审计，禁止保存 appKey、secretRef 与主密钥。
 * 设计见 docs/superpowers/specs/2026-09-29-project-app-issuance-platform-signing-callback-design.md#6-liquibase-变更。
 */
@TableName("sc_project_app_credential_audit")
@Data
public class ProjectAppCredentialAuditDO {

    /** 项目应用凭证操作审计内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id */
    private Long projectId;
    /** 项目应用身份内部主键，逻辑引用sc_project_application.id */
    private Long applicationId;
    /** 凭证操作：ENABLE开通，ROTATE轮换，REVOKE吊销 */
    private String action;
    /** 本次开通或轮换生成的密钥版本号；吊销时为空 */
    private Integer keyVersion;
    /** 操作人用户ID */
    private Long operatorId;
    /** 吊销原因；开通与轮换时为空 */
    private String reason;
    /** 操作时间，Asia/Shanghai */
    private LocalDateTime occurredAt;
}
