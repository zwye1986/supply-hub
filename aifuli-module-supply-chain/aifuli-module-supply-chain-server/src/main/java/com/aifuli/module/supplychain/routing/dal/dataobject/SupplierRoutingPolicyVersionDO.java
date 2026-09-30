package com.aifuli.module.supplychain.routing.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_routing_policy_version")
@Data
@Builder
public class SupplierRoutingPolicyVersionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long policyId;
    private Integer versionNo;
    private String versionName;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private byte[] configDigest;
    private Short status;
    private String publishReason;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
