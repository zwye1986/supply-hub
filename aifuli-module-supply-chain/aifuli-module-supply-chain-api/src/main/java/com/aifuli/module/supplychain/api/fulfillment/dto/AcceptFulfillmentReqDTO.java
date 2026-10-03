package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
/**
 * 采购商标准商品履约受理请求（双入口共享的内部可信命令）。
 *
 * <p>已按设计删除购买人身份 buyerUserId、调用方商品版本 listingVersion、报价凭证 pricingQuoteToken
 * 与支付时间 paidAt：手机号身份只经 target.accountType=MOBILE 的实际值表达，报价由服务端按
 * 「项目+采购商订单号」永久键解析，商品版本由服务端在受理事务内锁定校验。@Schema 描述须与
 * 052 增量 DDL 的 COMMENT 语义保持一致。</p>
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#1-双入口与共享命令}。</p>
 */
@Schema(description = "采购商标准商品履约受理请求")
@Data
public class AcceptFulfillmentReqDTO implements Serializable {

    /** 调用来源系统编码 */
    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64, message = "调用来源系统编码不能超过 64 个字符")
    private String sourceSystem;

    /** 来源系统中的外部租户标识 */
    @Schema(description = "来源系统中的外部租户标识",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128, message = "外部租户标识不能超过 128 个字符")
    private String externalTenantId;

    /** 采购项目全局永久编码 */
    @Schema(description = "采购项目全局永久编码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "PRJ_01ABC")
    @NotBlank(message = "采购项目编码不能为空")
    @Size(max = 32, message = "采购项目编码不能超过 32 个字符")
    private String projectCode;

    /** 采购商侧C端订单号，与buyer_id组成永久受理键 */
    @Schema(description = "采购商侧C端订单号，与buyer_id组成永久受理键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "C202608110001")
    @NotBlank(message = "采购商订单号不能为空")
    @Size(max = 128, message = "采购商订单号不能超过 128 个字符")
    private String buyerOrderNo;

    /** 首次请求固化的采购商商品编码 */
    @Schema(description = "首次请求固化的采购商商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    @NotBlank(message = "采购商商品编码不能为空")
    @Size(max = 64, message = "采购商商品编码不能超过 64 个字符")
    private String buyerSkuCode;

    /** 请求数量，一期固定为1 */
    @Schema(description = "请求数量，一期固定为1",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "履约数量不能为空")
    @Positive(message = "履约数量必须为正数")
    private Long quantity;

    /** 履约目标封闭Schema结构 */
    @Schema(description = "履约目标封闭Schema结构",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @Valid
    @NotNull(message = "履约目标不能为空")
    @ToString.Exclude
    private FulfillmentTargetDTO target;

    /** 回执状态及条件重试CAS版本号 */
    @Schema(description = "回执状态及条件重试CAS版本号",
            example = "1")
    @Positive(message = "上一回执版本必须为正数")
    private Long previousReceiptVersion;
}
