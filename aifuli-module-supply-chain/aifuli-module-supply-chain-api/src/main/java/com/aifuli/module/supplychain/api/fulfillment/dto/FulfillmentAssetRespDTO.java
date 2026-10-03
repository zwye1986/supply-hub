package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "授权后的履约资产；查询必须校验采购商、采购商用户、独立 Scope 并写不可变审计")
@Data
public class FulfillmentAssetRespDTO implements Serializable {

    /** 履约交付资产稳定业务编号 */
    @Schema(description = "履约交付资产稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FA_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String assetNo;

    /** BOrder范围内资产顺序号，从1递增 */
    @Schema(description = "BOrder范围内资产顺序号，从1递增", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer sequenceNo;

    /** 资产类型：VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益 */
    @Schema(description = "资产类型：VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "CARD_CODE_SECRET")
    private String assetType;

    /** 卡号；仅 CARD_CODE_SECRET 返回，文档示例始终脱敏 */
    @Schema(description = "卡号；仅 CARD_CODE_SECRET 返回，文档示例始终脱敏",
            example = "6222********1234")
    private String cardCode;

    /** 卡密；仅 CARD_CODE_SECRET 返回，文档示例始终脱敏 */
    @Schema(description = "卡密；仅 CARD_CODE_SECRET 返回，文档示例始终脱敏",
            example = "****")
    private String cardSecret;

    /** 券码；仅 VOUCHER_CODE 返回，文档示例始终脱敏 */
    @Schema(description = "券码；仅 VOUCHER_CODE 返回，文档示例始终脱敏",
            example = "****")
    private String voucherCode;

    /** 券链接；仅 VOUCHER_URL 返回，文档使用非生产占位地址 */
    @Schema(description = "券链接；仅 VOUCHER_URL 返回，文档使用非生产占位地址",
            example = "https://example.invalid/voucher/****")
    private String voucherUrl;

    /** 后台和授权查询使用的脱敏资产值 */
    @Schema(description = "后台和授权查询使用的脱敏资产值", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "6222********1234")
    private String maskedValue;

    /** 资产有效期开始时间，Asia/Shanghai */
    @Schema(description = "资产有效期开始时间，Asia/Shanghai", example = "2026-08-11T02:31:00Z")
    private OffsetDateTime validFrom;

    /** 资产有效期结束时间，Asia/Shanghai */
    @Schema(description = "资产有效期结束时间，Asia/Shanghai", example = "2027-08-11T02:31:00Z")
    private OffsetDateTime validUntil;

    /** 资产生命周期：10可交付，20已交付，30已作废，40已解绑，50已过期，90隔离不可交付 */
    @Schema(description = "资产生命周期：10可交付，20已交付，30已作废，40已解绑，50已过期，90隔离不可交付", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACTIVE")
    private String lifecycleStatus;
}
