package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_fulfillment_acceptance_receipt")
@Data
@Builder
public class FulfillmentAcceptanceReceiptDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String receiptNo;
    private Long buyerId;
    private String buyerOrderNo;
    private String buyerUserId;
    private String buyerSkuCode;
    private Long quantity;
    private String targetSchemaCode;
    private byte[] targetDigest;
    private byte[] invariantFingerprint;
    private byte[] requestFingerprint;
    private Integer listingVersionNo;
    private Long listingVersionId;
    private Integer attemptNo;
    private Short status;
    private Long resultOrderId;
    private String resultOrderNo;
    private String resultFulfillmentRequestNo;
    private String rejectionCode;
    private LocalDateTime acceptedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
