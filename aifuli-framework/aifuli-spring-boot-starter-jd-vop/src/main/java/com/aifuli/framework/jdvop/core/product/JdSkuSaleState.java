package com.aifuli.framework.jdvop.core.product;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 京东 SKU 可采购结果。
 *
 * @param canPurchase 是否可采购
 * @param message 不可采购原因
 * @param skuId 京东 SKU ID
 */
public record JdSkuSaleState(
        @JsonProperty("canPurchase") Boolean canPurchase,
        @JsonProperty("message") String message,
        @JsonProperty("skuId") Long skuId) {
}
