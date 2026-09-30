package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目能力认证只读投影，对应 {@code sc_project_capability}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#62-projectappcredentialservice应用服务}。</p>
 */
@TableName("sc_project_capability")
@Data
public class ProjectCapabilityDO {

    /** 项目能力内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id */
    private Long projectId;
    /** 项目能力：STANDARD_PRODUCT标准品、O2O到家、INSTORE到店购 */
    private String capabilityCode;
    /** 能力状态：10启用，20停用 */
    private Short status;
}
