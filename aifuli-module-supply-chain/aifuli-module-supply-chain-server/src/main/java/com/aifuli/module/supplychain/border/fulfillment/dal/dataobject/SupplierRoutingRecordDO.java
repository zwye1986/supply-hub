package com.aifuli.module.supplychain.border.fulfillment.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("sc_supplier_routing_record")
@Data
@Builder
public class SupplierRoutingRecordDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long routingRoundId;
    private Integer candidateSequence;
    private Long supplierId;
    private String supplierCodeSnapshot;
    private String supplierNameSnapshot;
    private Long offerId;
    private Long offerVersionId;
    private Long connectorVersionId;
    private Long inventorySourceId;
    private Integer configuredPriority;
    private String availabilityClass;
    private Long supplierSettlementUnitPrice;
    private BigDecimal healthScore;
    private LocalDateTime factCapturedAt;
    private Integer checkSnapshotSchemaVersion;
    private String checkResultJson;
    private Boolean eligibleFlag;
    private String decisionReasonCode;
    private String decisionReasonMessage;
    private Boolean selectedFlag;
    private Long attemptBOrderId;
    private LocalDateTime createdAt;
}
