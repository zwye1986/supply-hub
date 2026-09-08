package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "授权后的履约资产；查询必须校验采购商、采购商用户、独立 Scope 并写不可变审计")
@Data
public class FulfillmentAssetRespDTO implements Serializable {

    @Schema(description = "履约资产稳定业务号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FA_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String assetNo;

    @Schema(description = "资产顺序号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer sequenceNo;

    @Schema(description = "资产类型", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "CARD_CODE_SECRET")
    private String assetType;

    @Schema(description = "卡号；仅 CARD_CODE_SECRET 返回，文档示例始终脱敏",
            example = "6222********1234")
    private String cardCode;

    @Schema(description = "卡密；仅 CARD_CODE_SECRET 返回，文档示例始终脱敏",
            example = "****")
    private String cardSecret;

    @Schema(description = "券码；仅 VOUCHER_CODE 返回，文档示例始终脱敏",
            example = "****")
    private String voucherCode;

    @Schema(description = "券链接；仅 VOUCHER_URL 返回，文档使用非生产占位地址",
            example = "https://example.invalid/voucher/****")
    private String voucherUrl;

    @Schema(description = "脱敏资产摘要", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "6222********1234")
    private String maskedValue;

    @Schema(description = "资产有效期开始时间", example = "2026-08-11T02:31:00Z")
    private OffsetDateTime validFrom;

    @Schema(description = "资产有效期结束时间", example = "2027-08-11T02:31:00Z")
    private OffsetDateTime validUntil;

    @Schema(description = "资产生命周期状态", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACTIVE")
    private String lifecycleStatus;
}
