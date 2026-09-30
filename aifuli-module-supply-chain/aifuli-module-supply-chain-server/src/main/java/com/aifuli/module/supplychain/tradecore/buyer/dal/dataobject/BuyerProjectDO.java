package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购项目 DO，对应 {@code sc_buyer_project}。
 *
 * <p>projectId 是订单、会员业务身份和集成配置的归属主键；externalTenantId 不存放在本表。
 * 设计见 {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#42-buyerproject}
 * 与 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。</p>
 */
@TableName("sc_buyer_project")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProjectDO {

    /** 采购项目内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 采购项目全局永久编码。 */
    private String projectCode;
    /** 采购项目名称。 */
    private String projectName;
    /** 采购商内部主键，逻辑引用sc_buyer.id。 */
    private Long buyerId;
    /** 目录模式：MANAGED中台管理，INDEPENDENT项目独立。 */
    private String catalogMode;
    /** 项目类型封闭枚举 */
    private String projectType;
    /** 项目开始日 */
    private LocalDate startDate;
    /** 项目结束日 */
    private LocalDate endDate;
    /** 结算方，本期仅DISTRIBUTOR */
    private String settlementPartyType;
    /** 项目说明 */
    private String description;
    /** 项目状态：0草稿，10启用，20暂停，90归档。 */
    private Short status;
    /** 项目乐观锁版本号。 */
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
