package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_routing_policy")
@Data
@Builder
public class SupplierRoutingPolicyDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String policyCode;
    private String policyName;
    private String routingType;
    private Short status;
    private Short activeFlag;
    /** 综合排序稳定性评分权重%；三权（稳定/价格/时效）之和恒为100。 */
    private Integer stabilityWeight;
    /** 综合排序价格权重%。 */
    private Integer priceWeight;
    /** 综合排序时效评分权重%。 */
    private Integer timelinessWeight;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
