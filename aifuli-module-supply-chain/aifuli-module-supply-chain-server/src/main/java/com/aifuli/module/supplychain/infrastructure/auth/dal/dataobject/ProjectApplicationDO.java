package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目应用身份认证只读投影，对应 {@code sc_project_application}。
 *
 * <p>只承载凭证链需要的最小字段，不承担管理侧写入。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_project_application")
@Data
public class ProjectApplicationDO {

    /** 项目应用身份内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id */
    private Long projectId;
    /** 项目全局唯一稳定应用标识 */
    private String appId;
    /** 应用状态：10启用，20暂停，90归档 */
    private Short status;
}
