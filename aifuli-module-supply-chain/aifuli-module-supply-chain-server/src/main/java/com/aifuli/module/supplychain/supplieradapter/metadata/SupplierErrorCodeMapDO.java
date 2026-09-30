package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_supplier_error_code_map")
@Data
@Builder
public class SupplierErrorCodeMapDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long connectorVersionId;
    private Integer mappingVersion;
    private String externalErrorCode;
    private String externalSubCode;
    private String errorClass;
    private String actionType;
    private Short retryableFlag;
    private Short status;
    private LocalDateTime publishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
