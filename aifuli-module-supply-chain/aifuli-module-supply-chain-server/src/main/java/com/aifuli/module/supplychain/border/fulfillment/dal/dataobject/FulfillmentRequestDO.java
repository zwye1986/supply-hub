package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_fulfillment_request")
@Data
@Builder
public class FulfillmentRequestDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private Long orderId;
    private Long buyerId;
    private Long listingId;
    private Long listingVersionId;
    private Long platformSkuId;
    private String targetSchemaCode;
    private Integer targetSchemaVersion;
    private byte[] targetCiphertext;
    private byte[] targetDigest;
    private String targetKeyVersion;
    private Long requestedQuantity;
    private Long fulfilledQuantity;
    private Long activeBOrderId;
    private Short status;
    private Long aggregateVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
