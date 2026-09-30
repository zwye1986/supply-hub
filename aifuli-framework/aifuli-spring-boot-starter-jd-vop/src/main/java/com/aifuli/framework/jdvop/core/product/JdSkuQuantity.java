package com.aifuli.framework.jdvop.core.product;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 京东 SKU 及采购数量。
 *
 * @param skuId 京东 SKU ID
 * @param skuNumber 采购数量
 */
public record JdSkuQuantity(
        @JsonProperty("skuId") long skuId,
        @JsonProperty("skuNumber") int skuNumber) {

    public JdSkuQuantity {
        if (skuId <= 0) {
            throw new IllegalArgumentException("京东 SKU ID 必须大于 0");
        }
        if (skuNumber <= 0) {
            throw new IllegalArgumentException("京东 SKU 采购数量必须大于 0");
        }
    }

}
