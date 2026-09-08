package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_connector_capability")
@Data
@Builder
public class SupplierConnectorCapabilityDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long connectorVersionId;
    private String capabilityCode;
    private Short enabled;
    private Integer metadataVersion;
    private LocalDateTime createdAt;
}
