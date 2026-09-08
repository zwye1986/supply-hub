package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_status_mapping_definition")
@Data
@Builder
public class StatusMappingDefinitionDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long connectorVersionId;
    private Integer mappingVersion;
    private String externalStatus;
    private String externalSubStatus;
    private String businessPhase;
    private String factType;
    private Short terminalFlag;
    private Integer priority;
    private Short status;
    private LocalDateTime publishedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
