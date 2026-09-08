package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_config_publish_record")
@Data
@Builder
public class SupplierConfigPublishRecordDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordNo;
    private Long connectorId;
    private Long sourceVersionId;
    private Long targetVersionId;
    private String actionType;
    private String validationResult;
    private Long operatorId;
    private String reason;
    private byte[] configDigest;
    private String traceId;
    private LocalDateTime createdAt;
}
