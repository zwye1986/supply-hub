package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 标准商品路由候选（PRD 模型）：AUTO+SPECIFIED 规则下保序的候选供应商与 offer 级动态检查策略。
 *
 * <p>candidate_priority 在 PRD 模型下降级为同分裁决键；同时表达前端候选列表顺序。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#52-新表-ddl}。</p>
 */
@TableName("sc_std_routing_candidate")
@Data
@Builder
public class StdRoutingCandidateDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleId;
    private Long supplierId;
    private Integer candidatePriority;
    private String inventoryCheckMode;
    private Integer inventoryMaxAgeSeconds;
    private String balanceCheckMode;
    private Integer balanceMaxAgeSeconds;
    private String healthCheckMode;
    private Integer healthMaxAgeSeconds;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
