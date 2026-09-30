package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 项目应用凭证版本认证只读投影，对应 {@code sc_project_app_credential_version}。
 *
 * <p>{@code secret_ref} 只能保存密钥系统引用，禁止保存明文 appKey。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_project_app_credential_version")
@Data
public class ProjectAppCredentialVersionDO {

    /** 项目应用凭证版本内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 项目应用身份内部主键，逻辑引用sc_project_application.id */
    private Long applicationId;
    /** 调用方携带的密钥版本号 */
    private Integer keyVersion;
    /** 密钥系统引用，禁止保存明文appKey */
    @ToString.Exclude
    private String secretRef;
    /** 密钥状态：CURRENT当前，GRACE过渡，REVOKED已吊销，EXPIRED已过期 */
    private String status;
    /** 密钥生效时间，Asia/Shanghai */
    private LocalDateTime validFrom;
    /** 密钥失效时间，Asia/Shanghai */
    private LocalDateTime validTo;
}
