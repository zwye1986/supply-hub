package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购建单请求：渠道按旅通接口②提交的支付单受理数据。
 *
 * <p>字段语义与 013 迁移 DDL COMMENT 一致；channelCode/projectId 来自服务端可信渠道上下文，
 * 不允许调用方提交。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购建单请求")
@Data
public class CreatePaymentOrderReqDTO implements Serializable {

    @Schema(description = "旅通订单号order_id，与channel_code构成永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-ORDER-001")
    @NotBlank(message = "旅通订单号不能为空")
    @Size(max = 128, message = "旅通订单号不能超过 128 个字符")
    private String channelOrderId;

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    @Schema(description = "旅通会员编号user_code，受理时固化用于归属二次校验",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "opaque-user")
    @NotBlank(message = "旅通会员编号不能为空")
    @Size(max = 128, message = "旅通会员编号不能超过 128 个字符")
    @ToString.Exclude
    private String userCode;

    @Schema(description = "本笔支付流水金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "支付流水金额不能为空")
    @Positive(message = "支付流水金额必须大于 0")
    private Long payAmount;

    @Schema(description = "旅通支付单类型order_type：1线下扫码购，2动态码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "旅通支付单类型不能为空")
    private Integer orderType;

    @Schema(description = "订单应付总额total_amount，单位为分，旅通建单传入",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "订单应付总额不能为空")
    @Positive(message = "订单应付总额必须大于 0")
    private Long totalAmount;

    @Schema(description = "旅通企业组编号company_code，受理时固化",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "COMPANY-001")
    @NotBlank(message = "旅通企业组编号不能为空")
    @Size(max = 32, message = "旅通企业组编号不能超过 32 个字符")
    private String companyCode;

    @Schema(description = "渠道中的稳定外部商户标识",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "MERCHANT-001")
    @NotBlank(message = "外部商户标识不能为空")
    @Size(max = 128, message = "外部商户标识不能超过 128 个字符")
    private String externalMerchantId;

    @Schema(description = "渠道外部门店编号shop_code", example = "SHOP-001")
    @Size(max = 128, message = "门店编号不能超过 128 个字符")
    private String shopCode;

    @Schema(description = "渠道外部门店名称shop_name快照", example = "大润发门店")
    @Size(max = 256, message = "门店名称不能超过 256 个字符")
    private String shopName;

    @Schema(description = "交易主题/商品摘要，外呼收银台时透传", example = "到店购")
    @Size(max = 256, message = "交易主题不能超过 256 个字符")
    private String subject;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
