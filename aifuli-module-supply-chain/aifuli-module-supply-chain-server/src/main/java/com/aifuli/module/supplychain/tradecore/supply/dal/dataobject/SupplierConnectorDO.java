package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_connector")
@Data
@Builder
public class SupplierConnectorDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String connectorNo;
    private Long supplierId;
    private String connectorCode;
    private String adapterType;
    private Long currentVersionId;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
