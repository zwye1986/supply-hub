package com.aifuli.module.supplychain.tradecore.catalog.application;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 同步重算分销商品受理目录响应。
 * 对应前端 /buyers/{buyerCode}/products/{buyerProductCode}/acceptance-catalog/recalculate 契约。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "同步重算受理目录结果")
public class AcceptanceCatalogRecalculateRespVO {

    @Schema(description = "受理目录稳定业务编号")
    private String acceptanceListingNo;

    @Schema(description = "目录生命周期状态")
    private String publicationState;

    @Schema(description = "供给可用性状态")
    private String availabilityStatus;

    @Schema(description = "原因码")
    private String reason;

    @Schema(description = "引用分销定价版本 ID")
    private Long sourceBuyerPricingVersionId;

    @Schema(description = "最后评估时间")
    private LocalDateTime lastEvaluatedAt;

    @Schema(description = "下次必须重算时间")
    private LocalDateTime nextEvaluateAt;
}
