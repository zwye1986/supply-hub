package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_supplier_offer")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierOfferDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String offerNo;
    private String offerCode;
    private Long supplierId;
    private Long platformSkuId;
    private Long supplyScopeId;
    private String priceMode;
    private Long draftUnitPrice;
    private Integer discountBp;
    private String offerSupplyMode;
    private Long draftMaxQuantity;
    private Long draftMinQuantity;
    private Long currentVersionId;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
