package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 路由规则范围明细：分销商/项目/商品类型三个匹配维度的指定范围编码。
 *
 * <p>供应商不落本表（AUTO+SPECIFIED 的候选供应商落在候选表并保序）。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#52-新表-ddl}。</p>
 */
@TableName("sc_std_routing_rule_scope")
@Data
@Builder
public class StdRoutingRuleScopeDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private String scopeKind;
    private String scopeCode;
    private LocalDateTime createdAt;
}
