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
    /** 市场划线价/吊牌价，单位为分 */
    private Long marketPrice;
    /** 建议零售价/销售建议价，单位为分 */
    private Long suggestedSalePrice;
    /** 税率，如0.0600表示6%，NULL表示不征税/未配置 */
    private java.math.BigDecimal taxRate;
    /** SKU单次最小起购数量 */
    private Long minQuantity;
    /** SKU单次最大限购数量 */
    private Long maxQuantity;
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
