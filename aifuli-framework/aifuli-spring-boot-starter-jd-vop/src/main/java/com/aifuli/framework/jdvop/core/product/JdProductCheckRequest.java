package com.aifuli.framework.jdvop.core.product;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 京东商品可采购校验请求。
 *
 * @param skuNumInfoList SKU 与数量列表，最多 100 条
 * @param areaInfo 京东四级地址
 */
public record JdProductCheckRequest(
        @JsonProperty("skuNumInfoList") List<JdSkuQuantity> skuNumInfoList,
        @JsonProperty("areaInfo") JdAreaInfo areaInfo) {

    private static final int MAX_SKU_COUNT = 100;

    public JdProductCheckRequest {
        if (skuNumInfoList == null || skuNumInfoList.isEmpty()) {
            throw new IllegalArgumentException("京东商品校验的 SKU 列表不能为空");
        }
        if (skuNumInfoList.size() > MAX_SKU_COUNT) {
            throw new IllegalArgumentException("京东商品校验单次最多支持 100 个 SKU");
        }
        if (areaInfo == null) {
            throw new IllegalArgumentException("京东商品校验的地址不能为空");
        }
        Set<Long> skuIds = new LinkedHashSet<>();
        for (JdSkuQuantity skuQuantity : skuNumInfoList) {
            if (skuQuantity == null) {
                throw new IllegalArgumentException("京东商品校验的 SKU 不能为空");
            }
            if (!skuIds.add(skuQuantity.skuId())) {
                throw new IllegalArgumentException(
                        "京东商品校验的 SKU 不能重复：" + skuQuantity.skuId());
            }
        }
        // 防止调用方在请求执行期间修改列表，导致签名内容和请求内容不一致。
        skuNumInfoList = List.copyOf(skuNumInfoList);
    }

}
