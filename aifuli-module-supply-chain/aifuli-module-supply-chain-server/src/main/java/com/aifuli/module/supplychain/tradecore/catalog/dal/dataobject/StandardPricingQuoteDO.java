package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 标准商品15分钟项目报价凭证。设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#7-项目报价凭证与标准履约。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_standard_pricing_quote")
public class StandardPricingQuoteDO {
    /** 标准商品报价凭证主键 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 随机不可猜测报价凭证 */
    private String quoteToken;
    /** 调用来源系统编码 */
    private String sourceSystem;
    /** 来源系统外部租户标识 */
    private String externalTenantId;
    /** 采购商内部主键 */
    private Long buyerId;
    /** 采购项目内部主键 */
    private Long projectId;
    /** 项目商品主键 */
    private Long listingId;
    /** 平台SKU内部主键 */
    private Long platformSkuId;
    /** 平台SKU永久业务编码 */
    private String platformSkuCode;
    /** 冻结的项目SKU综合定价版本主键 */
    private Long projectPricingVersionId;
    /** 项目范围内用户标识；一期为规范化后的大陆手机号 */
    private String buyerUserId;
    /** 采购商订单号 */
    private String buyerOrderNo;
    /** 报价商品数量 */
    private Long quantity;
    /** 冻结的商品分销单价，单位CNY分 */
    private Long distributionUnitPriceCents;
    /** 冻结的商品销售单价，单位CNY分 */
    private Long salesUnitPriceCents;
    /** 冻结的商品小计，单位CNY分 */
    private Long productSubtotalCents;
    /** 冻结的平台服务费，单位CNY分 */
    private Long serviceFeeCents;
    /** 冻结的用户应付总额，单位CNY分 */
    private Long userPayableAmountCents;
    /** 冻结的税务展示属性JSON */
    private String taxMetadataJson;
    /** 冻结的服务费展示文案；隐藏文案时为空 */
    private String serviceFeeCopy;
    /** 凭证状态：ISSUED已签发，CONSUMED已消费 */
    private String status;
    /** 凭证失效时间，Asia/Shanghai */
    private LocalDateTime expiresAt;
    /** 消费该凭证的供应链订单主键 */
    private Long consumedOrderId;
    /** 凭证消费时间，Asia/Shanghai */
    private LocalDateTime consumedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
