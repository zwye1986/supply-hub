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
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
