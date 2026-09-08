package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_platform_sku")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSkuDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String skuCode;
    private Long spuId;
    private String skuName;
    private String fulfillmentType;
    private Long faceValue;
    private String currency;
    private String targetSchemaCode;
    private Integer targetSchemaVersion;
    private String resultSchemaCode;
    private Integer resultSchemaVersion;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
