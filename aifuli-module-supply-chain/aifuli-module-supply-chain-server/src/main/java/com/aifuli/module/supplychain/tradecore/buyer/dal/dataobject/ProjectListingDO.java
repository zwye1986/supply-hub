package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

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
 * 项目对分销商品的采购目录项。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md}。
 */
@TableName("sc_project_listing")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectListingDO {
    /** 项目商品主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 项目商品稳定业务编码 */
    private String listingCode;
    /** 采购项目主键，逻辑引用sc_buyer_project.id */
    private Long projectId;
    /** 分销商品主键，逻辑引用sc_buyer_product.id */
    private Long buyerProductId;
    /** 当前已发布版本 */
    private Long currentVersionId;
    /** 10可采购 90已移除 */
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
