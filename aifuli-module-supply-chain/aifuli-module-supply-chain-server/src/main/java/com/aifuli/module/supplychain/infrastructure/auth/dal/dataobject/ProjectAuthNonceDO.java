package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目应用认证 nonce 防重放记录 DO，对应 {@code sc_project_auth_nonce}。
 *
 * <p>复合主键 {@code (app_id, nonce)} 无合成 id；字段语义与 019 DDL COMMENT 保持一致。
 * 详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#64-noncestore-mysql-防重放}。</p>
 */
@TableName("sc_project_auth_nonce")
@Data
public class ProjectAuthNonceDO {

    /** 项目应用稳定标识，逻辑引用sc_project_application.app_id */
    private String appId;

    /** 调用方生成的Base64URL随机串 */
    private String nonce;

    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;

    /** 过期时间，Asia/Shanghai，取插入时刻加防重放窗口上限 */
    private LocalDateTime expireAt;
}
