package com.aifuli.module.supplychain.tradecore.catalog.application;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 选品／定价保存响应：保存维度与逐目录刷新维度分开；COMMITTED 仅在原保存代理确认提交后产生。
 * 失败条目不放入未确认的事务状态或时间，不补默认 PUBLISHED/AVAILABLE；诊断只关联平台 traceId。
 * 由管理保存编排产出，Controller 直接透传；设计见
 * docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#5-管理保存成功与刷新失败契约。
 */
@Data
@Schema(description = "选品/定价保存结果：保存与逐目录刷新两个维度")
public class BuyerSelectionCatalogSaveRespVO {

    @Schema(description = "保存状态；仅原保存代理确认提交后为 COMMITTED", example = "COMMITTED")
    private String saveState;

    @Schema(description = "真实已提交操作身份：分销操作 BPD，项目操作 PLT，不混用编号")
    private List<String> savedCodes;

    @Schema(description = "逐目录刷新结果，按去重后的受理目录顺序")
    private List<CatalogRefreshResultVO> catalogResults;

    @Schema(description = "后台扫描是否启用；关闭时不承诺自动重试收敛")
    private boolean automaticRecoveryEnabled;

    @Data
    @Schema(description = "单个受理目录的刷新结果")
    public static class CatalogRefreshResultVO {
        @Schema(description = "已提交操作身份：分销 BPD 或项目 PLT")
        private String savedCode;
        @Schema(description = "受理目录稳定编号")
        private String listingNo;
        @Schema(description = "封闭刷新执行码：UPDATED/UNCHANGED/DEFERRED/FAILED/SKIPPED_MANUAL/PAUSED/TERMINAL")
        private String refreshOutcome;
        @Schema(description = "真实生命周期，不用执行码代替")
        private String publicationState;
        @Schema(description = "真实供给投影状态")
        private String availabilityStatus;
        @Schema(description = "稳定等待/失败原因码；技术失败为目录刷新错误码")
        private String reasonCode;
        @Schema(description = "评估时间；仅真实评估结果提供，失败不放未确认时间")
        private LocalDateTime evaluatedAt;
        @Schema(description = "平台 traceId，诊断关联用；不含 SQL、堆栈或供应商秘密")
        private String traceId;
    }
}
