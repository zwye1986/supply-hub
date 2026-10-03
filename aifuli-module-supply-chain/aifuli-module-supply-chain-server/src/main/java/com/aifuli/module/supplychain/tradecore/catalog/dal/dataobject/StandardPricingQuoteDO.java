package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 标准商品项目订单首次永久冻结报价。永久键为「project_id + buyer_order_no」：
 * 首次成功报价冻结价格与商品身份，同键重复询价重放首次结果；不绑定用户或支付时间。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#2-首次签发与重放。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_standard_pricing_quote")
public class StandardPricingQuoteDO {
    /** 标准商品报价凭证主键 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 随机内部追踪标识；不作为HTTP请求字段，报价永久键为项目+采购商订单号 */
    private String quoteToken;
    /** 内部RPC调用来源系统编码；项目直接下单首次冻结为空 */
    private String sourceSystem;
    /** 内部RPC来源系统外部租户标识；项目直接下单首次冻结为空 */
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
    /** 历史报价失效时间，Asia/Shanghai；永久冻结报价为空且不作为本流程下单条件 */
    private LocalDateTime expiresAt;
    /** 消费该凭证的供应链订单主键 */
    private Long consumedOrderId;
    /** 凭证消费时间，Asia/Shanghai */
    private LocalDateTime consumedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
