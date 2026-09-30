package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标准商品路由规则（PRD 四级范围模型）。
 *
 * <p>范围明细见 {@link StdRoutingRuleScopeDO}，保序候选见 {@link StdRoutingCandidateDO}；
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#52-新表-ddl}。</p>
 */
@TableName("sc_std_routing_rule")
@Data
@Builder
public class StdRoutingRuleDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long policyVersionId;
    private String ruleCode;
    private String ruleName;
    private String routeMode;
    private Long primarySupplierId;
    private String distributorMode;
    private String projectMode;
    private String productTypeMode;
    private String supplierMode;
    private Long maxSupplyPriceCents;
    /** 列名 strategy_priority_1：数字前缀默认驼峰转下划线不会补下划线，须显式映射。 */
    @TableField("strategy_priority_1")
    private String strategyPriority1;
    @TableField("strategy_priority_2")
    private String strategyPriority2;
    @TableField("strategy_priority_3")
    private String strategyPriority3;
    private Integer rulePriority;
    private Short status;
    private Boolean protectedRule;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
