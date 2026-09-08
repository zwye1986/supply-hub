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

@TableName("sc_platform_spu")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSpuDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String spuCode;
    private Long brandId;
    /** 平台主类目主键，逻辑引用sc_product_category.id（历史字段，主数据保留，不用于供货命中或加购筛选） */
    private Long primaryCategoryId;
    /** 可售品类封闭枚举，见SellableCategory，供货命中用 */
    private String sellableCategory;
    /** 商品类型封闭枚举，见ProductTypeTag，供货命中用 */
    private String productType;
    /** 商品分类封闭枚举，见ProductClassification，供货命中用 */
    private String productClassification;
    private String spuName;
    private String subtitle;
    private String coverUrl;
    private String useInstruction;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
