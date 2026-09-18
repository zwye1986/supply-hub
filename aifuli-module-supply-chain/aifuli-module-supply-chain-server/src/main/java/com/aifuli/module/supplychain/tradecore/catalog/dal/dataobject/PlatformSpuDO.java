package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@TableName(value = "sc_platform_spu", autoResultMap = true)
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
    /** 启用商品类型编码，来源sc_product_type，供货命中用 */
    private String productType;
    /** 商品分类供货命中码：ProductClassification枚举名或sc_product_category.category_code */
    private String productClassification;
    /** 辅助/扩展分类ID列表(JSON数组) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> secondaryCategoryIds;
    private String spuName;
    private String subtitle;
    private String coverUrl;
    /** 商品轮播图地址列表(JSON数组) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sliderImageUrls;
    private String useInstruction;
    /** 商品富文本详情与图文介绍 */
    private String description;
    private Short status;
    /** 下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架 */
    private String unshelveMode;
    /** 配送履约模式：VIRTUAL虚拟发货, EXPRESS物流快递, PICKUP自提核销 */
    private String deliveryType;
    /** 履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益 */
    private String fulfillmentType;
    /** SPU默认单次最低起购量 */
    private Long minQuantity;
    /** SPU默认单次最高限购量 */
    private Long maxQuantity;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
