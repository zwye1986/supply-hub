package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_inventory_source")
@Data
@Builder
public class InventorySourceDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceNo;
    private Long supplierId;
    private Long platformSkuId;
    private String sourceType;
    private String externalSourceCode;
    private String ownerType;
    private Long currentPolicyVersionId;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
