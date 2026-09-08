package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_standard_product_routing_candidate")
@Data
@Builder
public class StandardProductRoutingCandidateDO {
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
