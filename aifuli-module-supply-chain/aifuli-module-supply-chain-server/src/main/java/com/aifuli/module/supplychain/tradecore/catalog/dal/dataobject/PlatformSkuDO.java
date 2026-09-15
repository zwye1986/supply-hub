package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
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
    /** 税率，如0.0600表示6%；仅记录展示，不参与价格计算 */
    private BigDecimal taxRate;
    /** 是否含税：1含税，0不含税；仅记录展示，不参与价格计算 */
    private Boolean taxIncluded;
    /** SKU单次最小起购数量 */
    private Long minQuantity;
    /** SKU单次最大限购数量 */
    private Long maxQuantity;
    /** 是否启用每人限购：1启用，0停用 */
    private Boolean userLimitEnabled;
    /**
     * 每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天。
     * 关闭限购时必须把周期写成 NULL 以满足 CHECK，因此更新策略为 ALWAYS。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String userLimitPeriod;
    /** 每人每周期最多购买数量 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long userLimitQuantity;
    /** 是否启用每订单限购：1启用，0停用 */
    private Boolean orderLimitEnabled;
    /** 是否启用绑定手机号限购：1启用，0停用 */
    private Boolean phoneBindLimitEnabled;
    /**
     * 绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天。
     * 关闭限购时必须把周期写成 NULL 以满足 CHECK，因此更新策略为 ALWAYS。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String phoneBindLimitPeriod;
    /** 同一履约手机号每周期最多购买数量 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long phoneBindLimitQuantity;
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
