package com.aifuli.module.supplychain.infrastructure.auth.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目能力记录，对应 {@code sc_project_capability}；认证只读，管理写入由项目应用服务负责。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#2-持久化与并发}。</p>
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
    /** 项目能力乐观锁版本号 */
    private Long version;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
