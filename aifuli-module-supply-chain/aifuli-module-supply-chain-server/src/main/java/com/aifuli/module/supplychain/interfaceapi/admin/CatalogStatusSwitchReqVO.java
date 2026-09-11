package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;

/**
 * 商品中心「启用 / 停用」端点共用请求体，覆盖商品、分类、品牌与商品类型四类实体。
 *
 * <p>不变量：状态端点只接受 {@code ACTIVE(10)} 与 {@code INACTIVE(20)}；归档（90）必须走 DELETE 端点，
 * 以确保经过删除引用强校验，不能从状态端点旁路归档。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4-管理端-rest-接口详细契约}。</p>
 */
@Data
@Schema(description = "商品中心实体启用/停用请求")
public class CatalogStatusSwitchReqVO {

    @NotNull
    @Schema(description = "目标状态：10启用，20停用", requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"10", "20"}, example = "10")
    private Short targetStatus;

    /**
     * 解析目标状态。未知取值或归档、草稿等非开关状态一律映射为 {@code PRODUCT_STATUS_INVALID}，
     * 避免向管理端抛出裸 {@link IllegalArgumentException}。
     */
    @JsonIgnore
    @Schema(hidden = true)
    public CatalogEntityStatus requireSwitchTarget() {
        if (targetStatus == null) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        if (targetStatus == CatalogEntityStatus.ACTIVE.getCode()) {
            return CatalogEntityStatus.ACTIVE;
        }
        if (targetStatus == CatalogEntityStatus.INACTIVE.getCode()) {
            return CatalogEntityStatus.INACTIVE;
        }
        throw exception(PRODUCT_STATUS_INVALID);
    }
}
