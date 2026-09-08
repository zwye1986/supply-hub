package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_b_order")
@Data
@Builder
public class BOrderDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bOrderNo;
    private Long fulfillmentRequestId;
    private Long orderId;
    private Integer attemptNo;
    private Long supplierId;
    private Long offerId;
    private Long offerVersionId;
    private Long connectorVersionId;
    private Long inventorySourceId;
    private String billno;
    private String idempotencyKey;
    private String externalOrderNo;
    private Long requestedQuantity;
    private Long fulfilledQuantity;
    private Long supplierSettlementUnitPrice;
    private Long expectedSupplierSettlementAmount;
    private Long reportedSupplierSettlementAmount;
    private Long confirmedSupplierSettlementAmount;
    private String currency;
    private Short fulfillmentStatus;
    private Short settlementStatus;
    private Boolean activeFlag;
    @Version
    private Long version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
