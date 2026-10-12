package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目能力变更追加审计，不存密钥，只有插入、没有管理端更改/删除入口。
 * 见 docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#2-持久化与并发。
 */
@Data
@TableName("sc_project_capability_audit")
public class ProjectCapabilityAuditDO {
    /** 项目能力操作审计内部主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id */
    private Long projectId;
    /** 项目能力：STANDARD_PRODUCT标准品、O2O到家、INSTORE到店购 */
    private String capabilityCode;
    /** 变更前能力状态：10启用，20停用；首次开通为空 */
    private Short beforeStatus;
    /** 变更后能力状态：10启用，20停用 */
    private Short afterStatus;
    /** 变更后的项目能力乐观锁版本号 */
    private Long capabilityVersion;
    /** 操作人用户ID */
    private Long operatorId;
    /** 关闭原因；开启时为空 */
    private String reason;
    /** 操作时间，Asia/Shanghai */
    private LocalDateTime occurredAt;
}
