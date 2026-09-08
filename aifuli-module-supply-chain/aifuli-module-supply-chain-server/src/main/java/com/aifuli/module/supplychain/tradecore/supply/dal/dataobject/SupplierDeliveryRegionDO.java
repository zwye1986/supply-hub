package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_supplier_delivery_region")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDeliveryRegionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long supplierId;
    private String adcode;
    private LocalDateTime createdAt;
}
