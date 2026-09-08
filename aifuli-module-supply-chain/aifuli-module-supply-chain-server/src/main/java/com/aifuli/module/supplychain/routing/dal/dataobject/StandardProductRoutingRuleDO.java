package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_standard_product_routing_rule")
@Data
@Builder
public class StandardProductRoutingRuleDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long policyVersionId;
    private String ruleCode;
    private String ruleName;
    private Long buyerId;
    private Long platformSkuId;
    private String fulfillmentType;
    private Long minQuantity;
    private Long maxQuantity;
    private Integer rulePriority;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
