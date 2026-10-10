package com.aifuli.module.supplychain.api.fulfillment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目标准订单封闭响应。设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#1-分层与可信输入。
 */
@Data
public class ProjectStandardOrderAssetRespDTO {
    /** 履约交付资产稳定业务编号，前缀 FA_ */
    private String assetNo;

    /** BOrder范围内资产顺序号，从1递增 */
    private Integer sequenceNo;

    /** 资产类型：VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接；仅投影可交付卡券类型 */
    private String assetType;

    /** 卡号；仅卡号卡密类型交付 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cardCode;

    /** 卡密；仅卡号卡密类型交付 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String cardSecret;

    /** 券码；仅券码类型交付 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String voucherCode;

    /** 券链接；仅券链接类型交付 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String voucherUrl;

    /** 后台和授权查询使用的脱敏资产值 */
    private String maskedValue;

    /** 资产有效期开始时间，Asia/Shanghai */
    private OffsetDateTime validFrom;

    /** 资产有效期结束时间，Asia/Shanghai */
    private OffsetDateTime validUntil;

    /** 供应商批次有效期原文，不保证日期格式；VALUE保留空字符串，ABSENT/NULL为null。设计见生产闭环设计 §7.2。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String validTimeRaw;

    /** 供应商有效期字段状态：ABSENT字段缺失，NULL显式null，VALUE字符串（包括空字符串）；旧资产不提供。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SupplierValidTimeState validTimeState;

    /** 资产生命周期的枚举名称投影：AVAILABLE可交付，DELIVERED已交付；对应存储语义10可交付、20已交付 */
    private String lifecycleStatus;

    /** 安全诊断摘要；任何交付值均不得进入日志。 */
    @Override
    public String toString() {
        return "ProjectStandardOrderAssetRespDTO[sensitive values omitted]";
    }
}
