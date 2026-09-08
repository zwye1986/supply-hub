package com.aifuli.module.supplychain.integration.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 采购项目集成档案 DO，对应 {@code sc_buyer_integration_profile}。
 *
 * <p>每个 projectId 唯一；buyerId 只用于归属校验与审计。设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
@TableName("sc_buyer_integration_profile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerIntegrationProfileDO {

    /** 采购商集成档案内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购商集成档案稳定业务编号。 */
    private String profileNo;
    /** 采购商内部主键，逻辑引用sc_buyer.id。 */
    private Long buyerId;
    /** 采购项目内部主键，逻辑引用sc_buyer_project.id。 */
    private Long projectId;
    /** 采购商集成档案名称。 */
    private String profileName;
    /** 当前已发布配置版本内部主键，逻辑引用sc_buyer_integration_profile_version.id。 */
    private Long currentVersionId;
    /** 档案状态：10启用，20暂停新业务，90已归档。 */
    private Short status;
    /** 档案乐观锁版本号。 */
    @Version
    private Long version;
    /** 创建人用户ID。 */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 最后修改人用户ID。 */
    private Long updatedBy;
    /** 最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
