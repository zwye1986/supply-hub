package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_offer_version")
@Data
@Builder
public class SupplierOfferVersionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long offerId;
    private Integer versionNo;
    private String externalSkuCode;
    private Long supplierSettlementUnitPrice;
    private String currency;
    private Long connectorVersionId;
    private Long inventorySourceId;
    private Long minQuantity;
    private Long maxQuantity;
    private Long quantityStep;
    private String primaryCapabilityCode;
    private String completionMode;
    private String createIdempotencyMode;
    private String unknownResultPolicy;
    private String partialDeliveryMode;
    private String resultMultiplicity;
    private Integer fulfillmentConfigSchemaVersion;
    private String fulfillmentConfigJson;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String publishReason;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
