package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_external_sku_binding")
@Data
@Builder
public class SupplierExternalSkuBindingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long connectorId;
    private String externalSkuCode;
    private Long offerId;
    private Long offerVersionId;
    @Version
    private Long version;
    private LocalDateTime updatedAt;
}
