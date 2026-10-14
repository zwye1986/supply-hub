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
    /** 供应商侧订单终结的稳定原因码，如 HUASHENG_SUPPLIER_CANCELLED；未终结或历史单为 NULL。设计：2026-10-13 华昇错误分类设计 §3.3。 */
    private String terminalReasonCode;
    /** 供应商终结的脱敏说明，不含凭据、私钥或原始报文。 */
    private String terminalReasonNote;
    private Boolean activeFlag;
    @Version
    private Long version;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
