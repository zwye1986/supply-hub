package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_routing_round")
@Data
@Builder
public class SupplierRoutingRoundDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String routingRoundNo;
    private Long buyerId;
    private String routingSubjectType;
    private String routingSubjectKey;
    private String routingType;
    private Long orderId;
    private Long fulfillmentRequestId;
    private Integer roundSequence;
    private Long policyId;
    private Long policyVersionId;
    private Long matchedRuleId;
    private byte[] routingInputDigest;
    private Integer snapshotSchemaVersion;
    private String factSnapshotJson;
    private Integer candidateCount;
    private Short decisionStatus;
    private String decisionReasonCode;
    private String decisionReasonMessage;
    private LocalDateTime computedAt;
    private LocalDateTime createdAt;
}
